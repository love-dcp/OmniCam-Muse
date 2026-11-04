package local.jiege.hook.aicomposition;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.media.FaceDetector;
import android.os.SystemClock;

import java.io.FileOutputStream;
import java.util.Locale;

import local.jiege.hook.common.Log;

/**
 * The half of real-scene composition that this build is missing: turning a preview frame into a
 * suggested crop.
 *
 * The camera can draw the guide frame and it can run its tracker -- what it cannot do here is
 * decide. Its own decision engine (libai_camera_composition_lite2.so) only ships in the MediaTek
 * build, and the AIUnit cloud unit the standalone Inspiration app uses is not installed, so
 * runCompositionStreaming returns false and the worker falls back to a canned demo crop that never
 * moves.
 *
 * So the decision is made here:
 *
 *   preview HardwareBuffer -> 384x512 ARGB (native lock) -> face detector, else skin blob
 *   -> subject box -> 4:3 crop in the camera's 768x1024 reference frame -> onEvent(1, json)
 *
 * Two properties of that JSON are not negotiable, both read off the build's own canned answers:
 * srcCrop coordinates live in a 768x1024 space, and zoom == 768 / cropWidth. The crops that ship
 * inside the apk (3.0/[306,191,562,533], 4.76279/[208,349,369,564]) are exactly 3:4 with that
 * relation, which is how the widths were reverse engineered.
 */
final class SubjectLocator {
    private static final String TAG = "CompFollow";

    /** Reference frame the composition JSON is expressed in. */
    static final int REF_W = 768;
    static final int REF_H = 1024;

    private static final int SAMPLE_H = 512;
    private static final int MAX_SAMPLE_W = 512;
    /** Height of the still handed to the cloud model; it resizes internally, this is the ceiling. */
    private static final int SNAPSHOT_H = 768;
    private static final int MAX_FACES = 4;
    /** Below this the eye distance is noise, usually a false hit on a textured background. */
    private static final float MIN_EYES = 6f;
    /** FaceDetector's own score for a hit; below this it is guessing. */
    private static final float MIN_CONFIDENCE = 0.30f;
    /** Brightness gap a blob may cross; a desk is dimmer than a can, and text on the can is darker. */
    private static final float LUMA_TOLERANCE = 26f;
    /**
     * How far a blob may drift in saturation as it grows. Brightness alone was not enough on the
     * scene that started this: a gold can on a wooden desk are close in luma, so the blob walked off
     * the can onto the table, hit three edges, and was thrown away as backdrop -- after which the
     * only candidate left was a blue file rack in the corner. Saturation separates them: the can is
     * vivid, the desk is not.
     */
    private static final float SAT_TOLERANCE = 18f;
    /** The same gates for a subject the user tapped; see {@link #growPinned}. */
    private static final float PIN_LUMA_TOLERANCE = 44f;
    private static final float PIN_SAT_TOLERANCE = 30f;
    /**
     * How far a pinned blob may spread from the finger, as a fraction of the frame. Twenty percent
     * was a quarter of the frame in each direction, which let a blob seeded on a can reach the
     * papers beside it; a subject picked by hand stays near the finger.
     */
    private static final float PIN_SPAN = 0.12f;
    /** Bounds on the subject height a single tap may ask for, as a fraction of the frame. */
    private static final float PIN_MIN_H = 0.14f;
    private static final float PIN_MAX_H = 0.20f;
    /**
     * How much tighter the second suggested crop is than the first. Mirrors the stock answer in
     * this build: its two crops are 3.0 and 4.76279, a ratio of 1.59, and the panel lays the two
     * cards out in proportion to the zoom they ask for.
     */
    private static final float TIGHT_RATIO = 1.15f;
    /**
     * How much room an object gets around its blob. The panel applies the tighter of the two crops
     * it is handed, so a generous first crop is what keeps the preview from jumping to a close-up
     * the moment composition opens.
     *
     * <p>1.3 was still a close-up: a can occupying 17% of the frame came back as 4.6x, which reads
     * as "the camera zoomed all the way in on my drink" rather than as a suggestion. A composition
     * keeps the subject's surroundings -- that is the whole point of a composition -- so the
     * object box is doubled before it becomes a crop.
     */
    private static final float OBJECT_MARGIN = 2.0f;

    /** How far the box may move in one frame while following a locked subject, as a fraction. */
    private static final float TRACK_STEP = 0.075f;
    /** Brightness match needed to re-acquire a locked subject; looser than the blob growth gate. */
    private static final float TRACK_TONE = 52f;
    /** Frames without a match before the lock admits the subject is gone. */
    private static final int TRACK_LOST = 6;
    /**
     * While a subject is locked the box has to move with it, so the button-animation budget that
     * paces automatic suggestions is too slow here: at 1.8 s the box would visibly lag the object.
     */
    private static final long TRACK_PUSH = 700L;
    /**
     * How far the box must move before the camera is told again. A hand-held shot wobbles by one to
     * two percent every frame, and pushing each of those made the box crawl across the screen even
     * when the subject was standing still.
     */
    private static final float TRACK_DEADBAND = 0.028f;

    /* What a decision was made about; only the wording differs. */
    private static final int KIND_FACE = 0;
    private static final int KIND_SKIN = 1;
    private static final int KIND_OBJECT = 2;
    private static final int KIND_LANDSCAPE = 3;
    private static final int KIND_AMBIENT = 4;
    /** A subject the user pinned by hand; frames itself differently on screen. */
    private static final int KIND_PINNED = 5;
    /** Foliage: flowers, leaves, a potted plant. Found by hue, not by shape. */
    private static final int KIND_PLANT = 6;

    /** A portrait crop shows roughly four face heights. */
    private static final float FACE_TO_CROP = 4.2f;
    /** Eyes sit about here below the top of the head. */
    private static final float EYE_TO_HEAD = 0.42f;
    /** Space above the head, as a fraction of the crop. */
    private static final float HEAD_ROOM = 0.12f;
    /** How far the subject is pushed towards its own third, per unit of off-centre distance. */
    private static final float SIDE_BIAS = 0.45f;

    /**
     * How far off the middle a subject may sit and still count as "what the phone is pointed at".
     *
     * <p>Two tenths of the frame is about the width of the centre third; a face or a plant further
     * out than this is in the shot beside the subject, not the subject itself.
     */
    private static final float CENTRE_X = 0.22f;

    /** Crop width ladder step; 12 keeps both sides on the 3:4 grid so srcCrop stays exact. */
    private static final int CROP_STEP = 12;
    private static final int MIN_CROP_W = 132;
    /** Nearly the whole frame is not a suggestion, it is a shrug; 588 keeps zoom at 1.3 or more. */
    private static final int MAX_CROP_W = 588;
    /** Loosest automatic suggestion, in reference pixels: 336 is zoom 2.28. */
    private static final int AUTO_MIN_CROP_W = 336;

    private static final float ALPHA = 0.24f;
    /** Movement below this (as a fraction of the frame) is left alone, so the box does not jitter. */
    private static final float DEADBAND = 0.045f;
    private static final long DETECT_INTERVAL = 220L;
    /**
     * The camera restarts the entry button's animation every time a result arrives, so pushing at
     * the detection rate makes that icon bounce nonstop. Two pushes a second is still smooth to the
     * eye and leaves the button alone in between.
     */
    private static final long PUSH_INTERVAL = 1800L;

    private static final Object LOCK = new Object();
    private static final Paint BOX = new Paint();

    /** Smoothed subject: {centre x, eye y, face height}, normalised to the frame. */
    private static float[] subject;
    /** Previous raw detection, for the two-frames-must-agree gate. */
    private static float[] lastRaw;
    private static int[] crop;
    private static String json;
    private static long lastGrab;
    private static long lastPush;
    private static boolean busy;
    private static boolean dirty = true;
    private static int detections;
    private static int misses;
    private static int objectCalls;

    /**
     * Point on the preview the user pressed, normalised to the frame, or null to pick a subject
     * automatically. It is the lock's origin, not its position: see {@link #anchor}.
     */
    private static volatile float[] pin;

    /**
     * Where the locked subject currently is -- starts at the finger, then follows the blob frame by
     * frame. The tap is the only moment the user's intent is known; after that the object is what
     * is worth following, and it does not stay under a fixed pixel when the phone is swung around.
     */
    private static volatile float[] anchor;
    /** Brightness of the locked subject; a blob far away from it is a different thing. */
    private static float anchorTone = -1f;
    /** Subject height last confirmed, to reject a blob that changed scale absurdly. */
    private static float anchorHeight;
    private static int trackMisses;
    private static int sceneCalls;
    /** Which kind of scene {@link #sceneIn} just decided on; that method returns a finished crop. */
    private static volatile int sceneKind = KIND_LANDSCAPE;

    /**
     * True while the answer on screen is the last-resort centre crop rather than a found subject.
     *
     * <p>The caller freezes one answer per session so the camera's align guide can converge, and a
     * frozen fallback is a session that can never improve: the first press, taken a moment before
     * the frame settled, locked in "nothing found" until the user pressed the button again. A
     * fallback is explicitly not worth freezing -- it is the answer that has to be replaced as soon
     * as any frame does find something.
     */
    private static volatile boolean ambientAnswer;

    static boolean ambientAnswer() {
        return ambientAnswer;
    }

    /** Reused sampling scratch; the detection thread and the one inline call both come through here. */
    private static int[] pixels;
    private static int sampleW;
    private static int sampleH;
    /**
     * The one bitmap every sample is written into.
     *
     * <p>This used to be {@code Bitmap.createBitmap(pixels, w, h, ARGB_8888)} -- a fresh bitmap per
     * sample, three or four times a second. A bitmap's pixels come from libc malloc, so that meant
     * a 786KB allocate/free cycle every 220ms; glibc keeps those pages in its arenas instead of
     * handing them back, and the process ended up with 1.5GB spread over a hundred
     * {@code [anon:libc_malloc]} mappings, all swapped out and never touched again. The camera then
     * could not get a capture buffer and the shutter sat on "saving photo" until the system killed
     * it. Writing into one mutable bitmap instead allocates nothing after the first frame.
     */
    private static Bitmap sampleFrame;
    /** Kept for the diagnostic dump only; points at {@link #sampleFrame} and is never recycled. */
    private static Bitmap lastFrame;
    /** Candidate boxes from the last automatic pass, in reference-frame coordinates. Diagnostics. */
    private static volatile int[][] lastCandidates;
    /** Reused 565 copy of the sample for the face detector, plus the canvas that fills it. */
    private static Bitmap faceFrame;
    private static Canvas faceCanvas;

    private SubjectLocator() {}

    /**
     * One preview frame in, one decision out.
     *
     * @return null when nothing is known yet (the caller should fall back to its canned answer),
     *         "" when the box is known and unchanged (the caller should stay quiet), otherwise the
     *         JSON to push through {@code callback.onEvent(1, ...)}. {@link #current()} is the last
     *         decided JSON: a fresh callback object arrives with every composition attempt and has
     *         to be answered at least once, or the camera reports "no suggestion for this scene".
     */
    static String current() {
        synchronized (LOCK) {
            return json;
        }
    }

    /**
     * Forgets the answer being held, so the next pass re-runs the detector instead of repeating it.
     * Needed when the source of the answer changes -- switching between the model and the device --
     * because a held string would otherwise be pushed as if nothing had changed.
     */
    static void dropHeld() {
        synchronized (LOCK) {
            json = null;
            dirty = true;
            lastGrab = 0;
            // The remembered crop has to go too. Keeping it made the next pass compare its answer
            // with the old one, decide "nothing moved" and return without ever writing a new json
            // -- and a pass with no json is answered with the demo/plain fallback, so the button
            // looked like it needed pressing again to take effect.
            crop = null;
            lastRaw = null;
            subject = null;
            ambientAnswer = false;
            // A new session gets a new opinion from the model, including a fresh budget: holding
            // the old scored box would answer the new scene with the previous subject's framing.
            lastVariant = 1;
            lastVariantScore = Float.NaN;
            lastScoredSubject = null;
            lastScoredBox = null;
            aestheticCalls = 0;
        }
    }

    /** Locks onto a point on the preview, or clears the lock when the point is out of range. */
    static void pinAt(float x, float y) {
        if (x < 0f || x > 1f || y < 0f || y > 1f) {
            clearPin();
            return;
        }
        float[] at = pin;
        // A second press a few percent away is the same request, not a new one; re-pinning there
        // would wipe the smoothed state and make the box jump for no reason.
        if (at != null && Math.abs(at[0] - x) < 0.08f && Math.abs(at[1] - y) < 0.08f) return;
        pin = new float[]{x, y};
        anchor = new float[]{x, y};
        anchorTone = -1f;
        anchorHeight = 0f;
        trackMisses = 0;
        reset();
        Log.i(TAG, String.format(Locale.US, "locked onto %.2f,%.2f", x, y));
    }

    static void clearPin() {
        if (pin == null) return;
        pin = null;
        anchor = null;
        anchorTone = -1f;
        anchorHeight = 0f;
        reset();
        Log.i(TAG, "lock cleared, back to automatic");
    }

    static float[] pinned() {
        return pin;
    }

    /** Where the locked subject currently is, which is not where it was pressed. */
    static float[] anchor() {
        return anchor;
    }

    /** Drops the smoothed state so a new point takes effect on the very next frame. */
    /** When the decided crop last changed. A still scene needs far fewer frames than a moving one. */
    private static volatile long movedAt;

    /**
     * How long the decided crop has been unchanged. The camera rebuilds the whole composition panel
     * every time a frame is answered, so asking for one at full rate while nothing moves is what
     * makes a locked shot look like the feature keeps restarting itself.
     */
    static long idleFor() {
        long at = movedAt;
        return at == 0 ? 0L : SystemClock.uptimeMillis() - at;
    }

    private static void reset() {
        synchronized (LOCK) {
            subject = null;
            lastRaw = null;
            crop = null;
            // A new lock or a new scene gets a fresh opinion from the model.
            lastVariant = 1;
            lastVariantScore = Float.NaN;
            lastScoredSubject = null;
            lastScoredBox = null;
            aestheticCalls = 0;
            // A fresh lock counts as movement, so tracking starts at full rate before backing off.
            movedAt = SystemClock.uptimeMillis();
        }
    }

    static String follow(HardwareBuffer buffer) {
        if (buffer == null || buffer.isClosed()) return json == null ? null : "";
        // This used to ignore the switch entirely: the camera's callback runs it on every frame
        // regardless of what the probe decided, so persist.omnicam.realfollow=0 silenced the
        // detector but not the sampling, and the shut-off could not be used to bisect a problem.
        if ("0".equals(prop("persist.omnicam.realfollow", "1"))) return json == null ? null : "";
        long now = SystemClock.uptimeMillis();
        boolean inline = false;
        boolean submit = false;
        synchronized (LOCK) {
            boolean due = now - lastGrab >= DETECT_INTERVAL;
            if (json == null) {
                // Nothing to show yet; retry at the detection rate, not at the frame rate.
                if (lastGrab != 0 && !due) return null;
                inline = true;
                lastGrab = now;
            } else if (!busy && due) {
                busy = true;
                submit = true;
                lastGrab = now;
            }
        }
        if (inline) {
            // The very first answer has to be synchronous: returning nothing here means the worker
            // paints its demo crop and the box visibly jumps once following starts.
            update(buffer);
        } else if (submit) {
            submit(buffer);
        }
        synchronized (LOCK) {
            if (json == null) return null;
            if (!dirty) return "";
            long gap = pin != null ? TRACK_PUSH : PUSH_INTERVAL;
            if (lastPush != 0 && now - lastPush < gap) return "";
            dirty = false;
            lastPush = now;
            return json;
        }
    }

    /**
     * One detection thread for the life of the process.
     *
     * <p>This used to start a fresh thread for every pass -- every 220ms while the panel is open,
     * which is a thread stack plus its own copy of the per-thread pixel scratch below, forever
     * recycled through libc. {@code busy} already guarantees one pass at a time, so a single
     * queued executor expresses the same thing without the churn.
     */
    private static final java.util.concurrent.ExecutorService DETECT =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "comp-follow");
            thread.setDaemon(true);
            return thread;
        });

    private static void submit(final HardwareBuffer buffer) {
        try {
            DETECT.execute(() -> {
                try {
                    update(buffer);
                } catch (Throwable error) {
                    Log.e(TAG, "detect pass failed", error);
                } finally {
                    synchronized (LOCK) {
                        busy = false;
                    }
                }
            });
        } catch (Throwable error) {
            synchronized (LOCK) {
                busy = false;
            }
            Log.e(TAG, "detect thread failed", error);
        }
    }

    private static void update(HardwareBuffer buffer) {
        try {
            if (buffer.isClosed()) return;
            Bitmap frame = sample(buffer);
            if (frame == null) {
                synchronized (LOCK) {
                    misses++;
                    if (misses <= 3) Log.i(TAG, "no pixels from frame (" + misses + ")");
                }
                return;
            }
            synchronized (LOCK) {
                // The sample is written into one reused bitmap (see sampleFrame), so there is
                // nothing to hand back here: recycling it would break the very next sample.
                lastFrame = frame;
            }
            float[] raw = null;
            int[] framed = null;
            int kind = KIND_FACE;
            boolean ambient = false;
            if (pin != null) {
                // A lock outranks every detector. A Red Bull can is red enough to pass the skin-tone
                // test, and the skin fallback then framed a patch of desk near it -- which is
                // exactly the "I pressed the can but the box landed on the table" report.
                raw = objectIn(frame);
                // Its own label, not the generic object one: a lock looks identical to an automatic
                // suggestion on screen, so the user had no way of telling that composition had
                // switched into follow mode or how to get out of it.
                kind = KIND_PINNED;
            } else {
                raw = faceIn(frame);
                boolean centrefirst = !"0".equals(prop("persist.omnicam.centrefirst", "1"));
                // A face on the edge of the frame is a bystander, not the shot. The camera is a
                // pointing device: whatever sits in the middle is what the user aimed at, and the
                // detector order used to hand the frame to any face anywhere -- which is the
                // "I centred on the object and composition picked the person beside it" report.
                // So a face that is not near the middle no longer wins by itself; the middle gets
                // first refusal, and the face is kept only when the middle has nothing to offer.
                boolean centredFace = raw != null && Math.abs(raw[0] - 0.5f) <= CENTRE_X;
                if (centrefirst && raw != null && !centredFace) {
                    float[] centre = null;
                    int centreKind = KIND_OBJECT;
                    if (!"0".equals(prop("persist.omnicam.plantdetect", "1"))) {
                        float[] plant = plantIn(frame);
                        if (plant != null && Math.abs(plant[0] - 0.5f) <= CENTRE_X) {
                            centre = plant;
                            centreKind = KIND_PLANT;
                        }
                    }
                    if (centre == null) {
                        centre = centredIn(frame);
                        centreKind = KIND_OBJECT;
                    }
                    if (centre != null) {
                        raw = centre;
                        kind = centreKind;
                        if (detections < 3) {
                            Log.i(TAG, String.format(Locale.US,
                                "centre outranks the edge face (%.2f)", centre[0]));
                        }
                    }
                    // centre == null keeps the edge face: a person is still a better subject than
                    // a full-frame answer.
                }
                // Foliage outranks the object scorer. A flowering plant is a mass of one soft-edged
                // colour, so the object scorer's "compact high-contrast blob" test hands the shot to
                // the pot or to whatever rectangle is behind it -- which is why pointing at flowers
                // used to frame the table edge.
                if (raw == null && !"0".equals(prop("persist.omnicam.plantdetect", "1"))) {
                    raw = plantIn(frame);
                    if (raw != null) kind = KIND_PLANT;
                }
                // Objects outrank skin. A wooden desk is warm brown and passes the skin test, and a
                // can of energy drink is that same warm orange, so "largest skin blob" framed the
                // tabletop next to the can -- the "I aimed at the can and the box landed on the
                // folder" report. objectIn scores subjectness instead (contrast against the frame,
                // closeness to the centre, size, staying off the edges), which is the better judge
                // once no face was found. persist.omnicam.objectfirst=0 restores the old order.
                boolean objectFirst = !"0".equals(prop("persist.omnicam.objectfirst", "1"));
                boolean objectTried = false;
                if (raw == null && objectFirst) {
                    raw = objectIn(frame);
                    kind = KIND_OBJECT;
                    objectTried = true;
                }
                if (raw == null) {
                    raw = skinIn(frame);
                    kind = KIND_SKIN;
                }
                // Without this the feature is a portrait-only tool, and a desk, a plate or a sign
                // has nothing to say. On by default now; persist.omnicam.objectdetect=0 turns it
                // back off without a rebuild.
                if (raw == null && !objectTried
                    && !"0".equals(prop("persist.omnicam.objectdetect", "1"))) {
                    raw = objectIn(frame);
                    kind = KIND_OBJECT;
                }
                // Last resort: no person and no distinct thing, but a horizon or a room with layers
                // is still worth framing. This is the branch that used to end in "no suggestion
                // for this scene" every time the camera was pointed at a street or a skyline.
                if (raw == null && !"0".equals(prop("persist.omnicam.scenedetect", "1"))) {
                    framed = sceneIn(frame);
                    kind = framed == null ? KIND_FACE : sceneKind;
                }
            }
            if (raw == null && framed == null
                && !"0".equals(prop("persist.omnicam.comp.fallback", "1"))) {
                // Nothing identified at all -- a blank wall, a plate of food, a sign, a flower bed
                // seen from too far. Answering with nothing is what made the default button feel
                // like it did nothing ("不点击默认点构图按钮成功率不高"): the pass still gets a valid
                // full-frame answer, but there is no rect on screen and no card in the panel.
                // A gentle centre crop is an honest composition for a scene without a subject, and
                // it is the widest suggestion the ladder allows, so it cannot ruin the shot.
                framed = fallbackBox();
                kind = KIND_AMBIENT;
                ambient = true;
                int calls;
                synchronized (LOCK) {
                    calls = ++fallbackCalls;
                }
                if (calls <= 5 || calls % 60 == 0) {
                    Log.i(TAG, "no subject found, offering the whole scene (" + calls + ")");
                }
            }
            if (raw == null && framed == null) {
                synchronized (LOCK) {
                    misses++;
                    if (misses <= 3) Log.i(TAG, "no subject in frame (" + misses + ")");
                }
                return;
            }
            final float[] detected = raw;
            final int[] sceneBox = framed;
            final int decided = kind;
            float[] smoothed = null;
            int[] base = null;
            boolean first;
            synchronized (LOCK) {
                if (sceneBox != null) {
                    // A scene is not a point that can drift; its frame is already decided, and the
                    // two-frames-must-agree gate would only delay the first suggestion.
                    lastRaw = null;
                    subject = null;
                    base = sceneBox;
                    first = false;
                } else {
                    // A single frame that disagrees with the previous one is usually the detector
                    // guessing (this camera's desk scene scores an oval speaker grille at 0.52 every
                    // frame, drifting a few percent each time). Two agreeing frames in a row before
                    // the box moves keeps that noise off the screen, and a person moving smoothly
                    // still passes because their deltas are small. A locked subject is exempt: the
                    // window and the per-frame step cap already bound how far it can go.
                    boolean agrees = crop == null || pin != null || agreesWithLast(detected);
                    lastRaw = detected;
                    if (!agrees) return;
                    subject = smooth(detected);
                    smoothed = subject.clone();
                    base = cropOf(subject);
                    first = crop == null;
                }
            }
            // Scoring runs the on-device aesthetics model, which is far too slow to hold LOCK: the
            // frame thread reads the latest answer through it, and a one second hold would stall
            // the preview. The very first frame also skips it -- that one has to answer inline.
            int[] next = sceneBox != null || first
                ? base : refineByAesthetic(frame, smoothed, base);
            synchronized (LOCK) {
                // A real subject always replaces a fallback, even when the two crops look alike:
                // the fallback is a placeholder, and the callers freeze whatever arrives first.
                // Without this the placeholder stayed for the whole session because `moved` saw a
                // centre crop and a centre crop and called it "unchanged".
                boolean upgrade = ambientAnswer && !ambient;
                if (crop != null && !upgrade && !moved(crop, next)) return;
                crop = next;
                ambientAnswer = ambient;
                movedAt = SystemClock.uptimeMillis();
                json = jsonOf(next, decided);
                dirty = true;
                detections++;
                if (detections <= 3 || detections % 40 == 0) {
                    Log.i(TAG, String.format(Locale.US,
                        "crop %d,%d %dx%d zoom %.3f (%s%s)",
                        next[0], next[1], next[2], next[3], REF_W / (double) next[2],
                        label(decided),
                        subject == null ? "" : String.format(Locale.US, " h %.1f%% x %.2f",
                            subject[2] * 100f, subject[0])));
                }
            }
            if ("1".equals(prop("persist.omnicam.followdump", "0"))) dump(next);
        } catch (Throwable error) {
            Log.e(TAG, "update failed", error);
        }
    }

    /* ---------------------------------------------------------------- sampling */

    /**
     * Whole-frame ARGB buffer, reused for the life of the calling thread.
     *
     * <p>Every detector used to allocate its own {@code int[width * height]} -- 786 KB at 384x512
     * -- and one pass can run the object scorer, the plant mask and the scene mask in a row. At
     * three passes a second that is roughly 10 MB/s handed to libc and freed again; glibc keeps
     * those pages in its arenas instead of returning them to the kernel (the same reason
     * {@link #sampleFrame} exists), and the camera's native heap grew into the hundreds of
     * megabytes with a third of it swapped out. One buffer per thread, grown on demand, allocates
     * nothing after the first frame.
     *
     * <p>Per-thread rather than one shared array: the frame thread can take the inline first pass
     * while the follow thread is still finishing the previous one.
     */
    private static final ThreadLocal<int[]> SCRATCH = new ThreadLocal<>();

    private static int[] scratch(int size) {
        int[] buffer = SCRATCH.get();
        if (buffer == null || buffer.length < size) {
            buffer = new int[size];
            SCRATCH.set(buffer);
        }
        return buffer;
    }

    /** Reads the whole of {@code frame} into the shared scratch buffer; valid until the next call. */
    private static int[] pixelsOf(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int[] data = scratch(width * height);
        frame.getPixels(data, 0, width, 0, 0, width, height);
        return data;
    }

    /** Locks the preview buffer into an ARGB bitmap at roughly 384x512. */
    private static Bitmap sample(HardwareBuffer buffer) {
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        if (width <= 0 || height <= 0) return null;
        int targetH = SAMPLE_H;
        int targetW = Math.round(targetH * (float) width / height / 2f) * 2;
        if (targetW > MAX_SAMPLE_W) {
            targetW = MAX_SAMPLE_W;
            targetH = Math.round(targetW * (float) height / width / 2f) * 2;
        }
        int result;
        synchronized (LOCK) {
            if (pixels == null || sampleW != targetW || sampleH != targetH) {
                pixels = new int[targetW * targetH];
                sampleW = targetW;
                sampleH = targetH;
            }
            result = NativeFrames.read(buffer, pixels, sampleW, sampleH);
            if (result == 0) {
                if (sampleFrame == null || sampleFrame.isRecycled()
                    || sampleFrame.getWidth() != sampleW || sampleFrame.getHeight() != sampleH) {
                    sampleFrame = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888);
                }
                sampleFrame.setPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH);
                return sampleFrame;
            }
        }
        if (misses < 3) Log.i(TAG, "native read failed: " + result);
        return null;
    }

    /**
     * A still for the cloud model. It does its own resize, but the box it returns is meaningful
     * only if it can see the subject at more than the detector's 384x512.
     */
    static Bitmap snapshot(HardwareBuffer buffer) {
        if (buffer == null || buffer.isClosed()) return null;
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        if (width <= 0 || height <= 0) return null;
        int targetH = SNAPSHOT_H;
        int targetW = Math.round(targetH * (float) width / height / 2f) * 2;
        if (targetW > SNAPSHOT_H) {
            targetW = SNAPSHOT_H;
            targetH = Math.round(targetW * (float) height / width / 2f) * 2;
        }
        if (targetW < 16 || targetH < 16) return null;
        int[] pixels = new int[targetW * targetH];
        if (NativeFrames.read(buffer, pixels, targetW, targetH) != 0) return null;
        try {
            return Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888);
        } catch (Throwable error) {
            Log.i(TAG, "snapshot failed: " + error);
            return null;
        }
    }

    /* ---------------------------------------------------------------- detection */

    /** {centre x, eye y, face height} in frame-normalised units, or null. */
    private static float[] faceIn(Bitmap frame) {
        Bitmap rgb = null;
        try {
            // A 565 copy of the frame is what the Neven detector takes. It is drawn into one
            // reused bitmap for the same reason the sample is: a fresh copy per call is another
            // 393KB of malloc traffic every 220ms that glibc never gives back.
            if (faceFrame == null || faceFrame.isRecycled()
                || faceFrame.getWidth() != frame.getWidth()
                || faceFrame.getHeight() != frame.getHeight()) {
                faceFrame = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(),
                    Bitmap.Config.RGB_565);
                faceCanvas = new Canvas(faceFrame);
            }
            if (faceCanvas == null) faceCanvas = new Canvas(faceFrame);
            faceCanvas.drawBitmap(frame, 0f, 0f, null);
            rgb = faceFrame;
            if (rgb == null) return null;
            int width = rgb.getWidth();
            int height = rgb.getHeight();
            FaceDetector detector = new FaceDetector(width, height, MAX_FACES);
            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            int count = detector.findFaces(rgb, faces);
            if (count <= 0) return null;
            FaceDetector.Face best = null;
            float eyes = 0f;
            float bestRank = 0f;
            PointF probe = new PointF();
            for (int i = 0; i < count; i++) {
                FaceDetector.Face face = faces[i];
                if (face == null) continue;
                float distance = face.eyesDistance();
                if (distance < MIN_EYES) continue;
                // The largest face is the one being photographed; bystanders are noise here. But
                // size alone loses the case this feature kept getting wrong: a big face at the edge
                // of a group shot beat the person standing where the phone was pointed. Position
                // carries half the vote now, so the face in the middle wins unless the edge one is
                // more than about twice as large.
                face.getMidPoint(probe);
                float dx = probe.x / width - 0.5f;
                float dy = probe.y / height - 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float rank = distance * (1f - 0.50f * Math.min(1f, dist / 0.55f));
                if (rank > bestRank) {
                    bestRank = rank;
                    eyes = distance;
                    best = face;
                }
            }
            if (best == null) return null;
            // The Neven detector is happy to call wood grain or an oval speaker grille a face, and
            // its own score does not separate those (this build scores a speaker at 0.52). Skin
            // colour inside the box does: a face is never mostly grey.
            if (best.confidence() < MIN_CONFIDENCE) return null;
            if (eyes * 2.9f > height * 0.55f) return null;
            PointF mid = new PointF();
            best.getMidPoint(mid);
            if (!skinInside(frame, mid, eyes)) {
                if (misses < 3) {
                    Log.i(TAG, String.format(Locale.US,
                        "face rejected: conf %.2f eyes %.1fpx at %.2f,%.2f, no skin",
                        best.confidence(), eyes, mid.x / width, mid.y / height));
                }
                return null;
            }
            if (detections <= 3) {
                Log.i(TAG, String.format(Locale.US,
                    "face: conf %.2f eyes %.1fpx at %.2f,%.2f", best.confidence(), eyes,
                    mid.x / width, mid.y / height));
            }
            return new float[]{mid.x / width, mid.y / height, (eyes * 2.9f) / height};
        } catch (Throwable error) {
            Log.e(TAG, "face detect failed", error);
            return null;
        }
        // Deliberately no recycle: rgb is the shared faceFrame now.
    }

    /** Warm-skin test in the same cheap YCbCr box the blob fallback uses. */
    private static boolean isSkin(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;
        int luma = (77 * r + 150 * g + 29 * b) >> 8;
        if (luma < 40 || luma > 246) return false;
        if (r < 70 || r <= b + 12) return false;
        // Skin is warm but never vivid. A golden drink can and a varnished desk both sit inside the
        // chroma box below, and this saturation ceiling is the cheapest thing that tells them apart
        // from a person -- measured on this scene, the can sits near 70 and skin below 50.
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max > 0 && (max - min) * 100 / max > 58) return false;
        int cb = 128 + ((-43 * r - 85 * g + 128 * b) >> 8);
        int cr = 128 + ((128 * r - 107 * g - 21 * b) >> 8);
        return cb >= 74 && cb <= 133 && cr >= 134 && cr <= 182;
    }

    /** Is at least a quarter of the face box warm skin? The central patch, to skip hair and edges. */
    private static boolean skinInside(Bitmap frame, PointF mid, float eyes) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int left = Math.max(0, Math.round(mid.x - eyes * 0.55f));
        int right = Math.min(width - 1, Math.round(mid.x + eyes * 0.55f));
        int top = Math.max(0, Math.round(mid.y - eyes * 0.35f));
        int bottom = Math.min(height - 1, Math.round(mid.y + eyes * 0.85f));
        int w = right - left + 1;
        int h = bottom - top + 1;
        if (w <= 0 || h <= 0) return false;
        int[] data = scratch(w * h);
        frame.getPixels(data, 0, w, left, top, w, h);
        int hits = 0;
        // Bounded by the face box, not by the buffer: the scratch array is shared and usually
        // longer than this patch, and an unbounded loop would count the previous frame's pixels.
        int total = w * h;
        for (int i = 0; i < total; i++) {
            if (isSkin(data[i])) hits++;
        }
        return hits > total * 0.25f;
    }

    /**
     * Fallback for frames the face detector cannot read -- backlit, turned away, too far. Warm skin
     * chroma is enough to find the subject region; the largest blob wins, so hands and wooden
     * furniture lose to a person.
     */
    private static float[] skinIn(Bitmap frame) {
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final int[] data = pixelsOf(frame);

        final int step = 2;
        final int gw = width / step;
        final int gh = height / step;
        final boolean[] mask = new boolean[gw * gh];
        int hits = 0;
        for (int gy = 0; gy < gh; gy++) {
            int row = gy * step * width;
            for (int gx = 0; gx < gw; gx++) {
                if (!isSkin(data[row + gx * step])) continue;
                mask[gy * gw + gx] = true;
                hits++;
            }
        }
        if (hits < gw * gh / 60) return null;

        int[] stack = new int[gw * gh];
        int bestArea = 0;
        int bestLeft = 0, bestTop = 0, bestRight = 0, bestBottom = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start]) continue;
            int top = 0;
            stack[top++] = start;
            mask[start] = false;
            int area = 0;
            int left = gw, right = 0, upper = gh, lower = 0;
            while (top > 0) {
                int index = stack[--top];
                int x = index % gw;
                int y = index / gw;
                area++;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < upper) upper = y;
                if (y > lower) lower = y;
                if (x > 0 && mask[index - 1]) { mask[index - 1] = false; stack[top++] = index - 1; }
                if (x + 1 < gw && mask[index + 1]) { mask[index + 1] = false; stack[top++] = index + 1; }
                if (y > 0 && mask[index - gw]) { mask[index - gw] = false; stack[top++] = index - gw; }
                if (y + 1 < gh && mask[index + gw]) { mask[index + gw] = false; stack[top++] = index + gw; }
            }
            if (area > bestArea) {
                bestArea = area;
                bestLeft = left;
                bestTop = upper;
                bestRight = right;
                bestBottom = lower;
            }
        }
        if (bestArea < gw * gh / 80) return null;
        float boxW = (bestRight - bestLeft + 1) * (float) step;
        float boxH = (bestBottom - bestTop + 1) * (float) step;
        // A person is taller than wide and does not run edge to edge; a wall, a table or a sheet of
        // paper fails one of these and is better treated as "nothing here".
        if (boxH < height * 0.14f || boxH < boxW || boxW > width * 0.75f) return null;
        // A person blob is body, not a face: the head is roughly a third of what is visible.
        float faceH = boxH * 0.30f;
        return new float[]{
            ((bestLeft + bestRight) / 2f) * step / width,
            (bestTop * step + faceH * EYE_TO_HEAD) / height,
            faceH / height,
        };
    }

    /* ---------------------------------------------------------------- framing */

    /**
     * Generic subject detection for scenes without a face: a can on a desk, a plate, a sign.
     *
     * <p>Two modes. With {@link #pin} set, a blob is grown from the cell the user tapped and only
     * that blob is considered -- the box then sits on the thing they pointed at instead of drifting
     * to whichever corner happens to be the most saturated this frame. Without a pin, every blob is
     * scored for "subject-ness" (contrast, position, size, staying off the edges) and the best one
     * wins.
     */
    private static float[] objectIn(Bitmap frame) {
        return objectIn(frame, false);
    }

    /**
     * Only the middle of the frame: what the phone is pointed at, with no fallback to the best
     * scoring blob anywhere else.
     *
     * <p>Used to arbitrate between detectors. A face at the edge of the frame is a bystander, and
     * an object in the corner is in the shot by accident -- when the middle holds something, that
     * is the subject, whichever detector happened to find something first.
     *
     * @return the centre blob as a subject triple, or null when the middle is a surface
     */
    private static float[] centredIn(Bitmap frame) {
        return objectIn(frame, true);
    }

    private static float[] objectIn(Bitmap frame, boolean centreOnly) {
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final int[] data = pixelsOf(frame);

        final int step = 8;
        final int gw = width / step;
        final int gh = height / step;
        if (gw < 4 || gh < 4) return null;
        final float[] score = new float[gw * gh];
        /** Mean luma per cell; a blob may only cross cells that look like the one it started in. */
        final float[] tone = new float[gw * gh];
        /** Mean saturation per cell, the second half of that "looks like the one it started in". */
        final float[] satMap = new float[gw * gh];
        float peak = 0f;
        float sum = 0f;
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int sumSat = 0, sumAbs = 0, sumL = 0, prevL = -1;
                for (int y = 0; y < step; y++) {
                    int row = (gy * step + y) * width + gx * step;
                    for (int x = 0; x < step; x++) {
                        int pixel = data[row + x];
                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int b = pixel & 0xff;
                        int luma = (77 * r + 150 * g + 29 * b) >> 8;
                        sumL += luma;
                        int max = Math.max(r, Math.max(g, b));
                        int min = Math.min(r, Math.min(g, b));
                        sumSat += max == 0 ? 0 : (max - min) * 100 / max;
                        if (prevL >= 0) sumAbs += Math.abs(luma - prevL);
                        prevL = luma;
                    }
                }
                int count = step * step;
                float edge = sumAbs / (float) count;
                float sat = sumSat / (float) count;
                float value = sat * 0.55f + Math.min(edge, 24f) * 0.45f;
                score[gy * gw + gx] = value;
                tone[gy * gw + gx] = sumL / (float) count;
                satMap[gy * gw + gx] = sat;
                sum += value;
                if (value > peak) peak = value;
            }
        }
        // The peak guard only applies to automatic picking; a pinned point is answered even on a
        // flat wall, where the user has still asked for that spot to be framed.
        if (pin == null && peak < 9f) return null;
        final float mean = sum / score.length;

        final boolean[] used = new boolean[score.length];
        final int[] stack = new int[score.length];
        final int cellLimit = score.length / 3;

        final float[] point = anchor != null ? anchor : pin;
        if (point != null) {
            // The window is centred on where the subject was last seen, not on the original press,
            // so carrying the object across the frame brings the box with it. Confining the fill to
            // a window is still what keeps the blob from walking off the can onto the tabletop.
            int gx = Math.max(0, Math.min(gw - 1, Math.round(point[0] * gw - 0.5f)));
            int gy = Math.max(0, Math.min(gh - 1, Math.round(point[1] * gh - 0.5f)));
            int spanX = Math.max(3, Math.round(gw * PIN_SPAN));
            int spanY = Math.max(3, Math.round(gh * PIN_SPAN));
            int minX = Math.max(0, gx - spanX);
            int maxX = Math.min(gw - 1, gx + spanX);
            int minY = Math.max(0, gy - spanY);
            int maxY = Math.min(gh - 1, gy + spanY);

            float lockedTone = anchorTone;
            int seed = -1;
            if (lockedTone >= 0f) {
                // Already locked: re-acquire the subject inside a small window around where it was
                // last seen, by brightness. Searching the whole pin window used to be enough to
                // lose it: the highest-scoring cell in that window could be a different object --
                // the user pointed at a can and the box settled on the papers beside it. Three
                // cells is about 6% of the frame, enough to follow a hand-held subject and not
                // enough to jump to a neighbour.
                int reach = 3;
                int nearX0 = Math.max(minX, gx - reach);
                int nearX1 = Math.min(maxX, gx + reach);
                int nearY0 = Math.max(minY, gy - reach);
                int nearY1 = Math.min(maxY, gy + reach);
                float bestValue = 0f;
                for (int y = nearY0; y <= nearY1; y++) {
                    for (int x = nearX0; x <= nearX1; x++) {
                        int index = y * gw + x;
                        if (score[index] <= bestValue) continue;
                        if (Math.abs(tone[index] - lockedTone) > TRACK_TONE) continue;
                        bestValue = score[index];
                        seed = index;
                    }
                }
            }
            if (seed < 0) {
                // First frame after the press, or the subject wandered out of the window. Start from
                // whichever cell in the 3x3 neighbourhood scores highest: the pressed cell can be a
                // dark gap between two bright parts.
                seed = gy * gw + gx;
                float local = 0f;
                for (int ny = Math.max(0, gy - 1); ny <= Math.min(gh - 1, gy + 1); ny++) {
                    for (int nx = Math.max(0, gx - 1); nx <= Math.min(gw - 1, gx + 1); nx++) {
                        float value = score[ny * gw + nx];
                        if (value > local) {
                            local = value;
                            seed = ny * gw + nx;
                        }
                    }
                }
            }
            float floor = Math.max(score[seed] * 0.35f, mean * 1.10f);
            float[] blob = growPinned(score, tone, satMap, used, stack, seed, floor, gw, gh,
                cellLimit, minX, maxX, minY, maxY);

            float boxH = anchorHeight > 0f ? anchorHeight : 0.24f;
            float cx = point[0];
            float cy = point[1];
            boolean tracked = false;
            if (blob != null && blob[0] >= 4) {
                float foundW = (blob[2] - blob[1] + 1) / (float) gw;
                float foundH = (blob[4] - blob[3] + 1) / (float) gh;
                float foundTone = blob[6] / blob[0];
                // Same thing? A blob of a very different brightness, or wildly larger or smaller
                // than the one that was locked, is the backdrop or a neighbour, not the subject.
                boolean same = lockedTone < 0f
                    || (Math.abs(foundTone - lockedTone) <= TRACK_TONE
                        && foundH <= Math.max(0.30f, anchorHeight * 2.4f)
                        && foundH >= anchorHeight * 0.35f);
                if (foundW <= 0.90f && foundH <= 0.90f && same) {
                    boxH = foundH;
                    float bx = (blob[1] + blob[2] + 1) * 0.5f / gw;
                    float by = (blob[3] + blob[4] + 1) * 0.5f / gh;
                    // One frame may only move a little. The seed is picked from the window, so a
                    // bright neighbour can still outscore the subject; capping the step keeps a
                    // single bad frame from throwing the box across the screen.
                    cx = point[0] + clamp(bx - point[0], -TRACK_STEP, TRACK_STEP);
                    cy = point[1] + clamp(by - point[1], -TRACK_STEP, TRACK_STEP);
                    anchorTone = lockedTone < 0f ? foundTone
                        : lockedTone + (foundTone - lockedTone) * 0.30f;
                    anchorHeight = anchorHeight <= 0f ? boxH
                        : anchorHeight + (boxH - anchorHeight) * 0.30f;
                    trackMisses = 0;
                    tracked = true;
                } else {
                    trackMisses++;
                }
            } else {
                trackMisses++;
            }
            if (trackMisses > TRACK_LOST) {
                // Gone from the window for a while: forget what it looked like so the next frame can
                // lock onto whatever is under the box now, rather than refusing everything.
                anchorTone = -1f;
                anchorHeight = 0f;
                trackMisses = 0;
            }
            anchor = new float[]{cx, cy};

            // Height is only trusted inside a range: the brightness gate trims the top and bottom of
            // a shiny can, so a raw 12% blob would otherwise ask for a 3.7x zoom.
            float subjectH = Math.max(PIN_MIN_H, Math.min(boxH * 0.62f, PIN_MAX_H)) * height;
            // Same backwards solve as subjectOf: centre the crop on the locked object instead of
            // lifting it by a face's worth of headroom.
            float faceH = Math.max(6f, subjectH / height * REF_H);
            int cropW = snap(faceH * FACE_TO_CROP * 0.75f);
            int cropH = cropW * 4 / 3;
            float eyeY = cy * height
                + (faceH * EYE_TO_HEAD + cropH * HEAD_ROOM - cropH * 0.5f) * (height / (float) REF_H);
            int calls;
            synchronized (LOCK) {
                calls = ++objectCalls;
            }
            if (calls <= 8 || calls % 40 == 0) {
                Log.i(TAG, String.format(Locale.US,
                    "lock #%d at %.2f,%.2f%s blob h %.1f%% -> subject h %.1f%% misses %d",
                    calls, cx, cy, tracked ? "" : " (hold)", boxH * 100f, subjectH / height * 100f,
                    trackMisses));
            }
            return new float[]{cx, eyeY / height, subjectH / height};
        }

        // Aiming is a spatial act: what the phone is pointed at sits in the middle of the frame.
        // Scoring alone does not know that -- it ranks by size, saturation and compactness, so a
        // blue file rack standing at the edge beat the can the shot was actually of, twice, on the
        // device. Growing from the centre instead answers "what is in the middle of this shot",
        // which is the question composition is being asked. When the middle is empty (a wall, a
        // tabletop) this returns null and the scoring loop below picks as before.
        if (!"0".equals(prop("persist.omnicam.centreanchor", "1"))) {
            float[] centred = centreBlob(score, tone, satMap, used, stack, gw, gh, cellLimit, mean,
                width, height);
            if (centred != null) return centred;
        }
        if (centreOnly) return null;

        // peak * 0.55 only ever captured the loudest patch -- the logo on a can, not the can. A
        // third of the peak still excludes flat backdrop but lets a blob reach the whole object.
        final float floor = Math.max(peak * 0.30f, mean * 1.40f);
        float[] best = null;
        float bestScore = 0f;
        boolean diag = "1".equals(prop("persist.omnicam.objdiag", "0"));
        java.util.ArrayList<int[]> marks = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            // Seeds are taken from the middle of the frame first. What a phone is aimed at sits
            // there, and letting the whole frame compete on raw score is how a blue file rack in
            // the corner came to frame a shot that was pointed at a can: its saturation beat the
            // can's, so its blob formed first and took the top slot. A seed outside the middle band
            // is still accepted when the middle has nothing to offer.
            int seed = pickSeed(score, used, gw, gh, floor, true);
            if (seed < 0) seed = pickSeed(score, used, gw, gh, floor, false);
            if (seed < 0) break;
            int seedIndex = seed;
            if (diag && attempt < 8) {
                Log.i(TAG, String.format(Locale.US, "seed a%d at %.2f,%.2f score %.1f",
                    attempt, (seedIndex % gw) / (float) gw, (seedIndex / gw) / (float) gh,
                    score[seedIndex]));
            }

            float[] blob = growBlob(score, tone, satMap, used, stack, seed, floor, gw, gh, cellLimit,
                0, gw - 1, 0, gh - 1);
            // A blob that ate a third of the frame is the backdrop, not a subject.
            if (blob == null || blob[0] < 4) continue;
            int area = (int) blob[0];
            int left = (int) blob[1], right = (int) blob[2];
            int upper = (int) blob[3], lower = (int) blob[4];

            float boxW = (right - left + 1) / (float) gw;
            float boxH = (lower - upper + 1) / (float) gh;
            if (boxW > 0.92f || boxH > 0.92f) continue;
            // A subject is not a speck. Anything under this on both axes is a detail on something
            // else -- a logo on a can, a bright gap between two things -- and framing it asks for a
            // zoom the preview cannot hold steady. This is what put a 4.3x box on a 5%-tall blob
            // while the can filled a third of the frame.
            if (boxW < 0.15f && boxH < 0.15f) continue;

            float cx = (left + right + 1) * 0.5f / gw;
            float cy = (upper + lower + 1) * 0.5f / gh;
            float dx = cx - 0.5f;
            float dy = cy - 0.5f;
            float centre = 1f - Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / 0.60f);

            // A blob touching three or four edges is a wall or the desk itself; one or two edges is
            // allowed but pushed down, because a crop clamped to the border frames nothing well.
            int edges = (left <= 0 ? 1 : 0) + (right >= gw - 1 ? 1 : 0)
                + (upper <= 0 ? 1 : 0) + (lower >= gh - 1 ? 1 : 0);
            if (edges >= 3) continue;
            // A thing parked against the border was in the shot by accident. This used to be a
            // gentle 0.88 per edge, which left a rack standing at the right edge of the desk
            // competitive with the can in the middle of the frame.
            float edgePenalty = (float) Math.pow(0.74, edges);

            // Contrast against the frame average is what says "this thing, not the backdrop".
            float contrast = (blob[5] / area) / (mean + 1f);
            float contrastScore = Math.min(1f, contrast / 2.2f);

            float size = 1f;
            if (boxW < 0.30f) size *= boxW / 0.30f;
            if (boxH < 0.28f) size *= boxH / 0.28f;
            if (boxW > 0.80f) size *= Math.max(0f, (0.95f - boxW) / 0.15f);
            if (boxH > 0.85f) size *= Math.max(0f, (0.97f - boxH) / 0.12f);
            float areaRatio = area / (float) score.length;
            if (areaRatio > 0.45f) size *= Math.max(0f, (0.70f - areaRatio) / 0.25f);

            float aspect = (boxW * width) / (boxH * height);
            float shape = aspect < 0.5f ? aspect / 0.5f
                : (aspect > 2.5f ? Math.max(0f, (3.6f - aspect) / 1.1f) : 1f);

            // Size outranks position now. What a phone is pointed at is the big thing in the frame,
            // and weighting the centre this heavily let a small, well-centred object win over the
            // can the camera was aimed at -- that is how a 5% blob came to ask for 4.3x.
            //
            // Compactness (how much of its own bounding box a blob actually fills) is what separates
            // an object from a surface: a can fills most of its box, while "desk plus monitor plus
            // cable" fills half of a much larger one. Without it the looseness won on size alone.
            float fill = area / (float) Math.max(1, (right - left + 1) * (lower - upper + 1));
            // Position carries its own weight now. It used to be 0.15 against 0.40 for size, which
            // is a ranking that cannot tell "the thing I aimed at" from "the biggest colourful thing
            // that happened to be in the shot" -- and the file rack won on exactly that.
            float radial = (float) Math.sqrt(dx * dx + dy * dy);
            float total = contrastScore * 0.14f + centre * 0.30f + size * 0.32f
                + shape * 0.10f + fill * 0.14f;
            // And a second, continuous pull: even when the middle has nothing, the nearer candidate
            // is the better answer, so a corner blob has to be clearly better to take the shot.
            total *= 1f - 0.25f * Math.min(1f, radial / 0.55f);
            total *= edgePenalty;
            if (diag && attempt < 8) {
                Log.i(TAG, String.format(Locale.US,
                    "cand a%d centre %.2f,%.2f box %.2fx%.2f area %.2f edges %d "
                        + "contrast %.2f size %.2f shape %.2f fill %.2f edge %.2f total %.2f",
                    attempt, cx, cy, boxW, boxH, areaRatio, edges,
                    contrastScore, size, shape, fill, edgePenalty, total));
            }
            if (marks.size() < 6) {
                marks.add(new int[]{
                    Math.round(left * REF_W / (float) gw),
                    Math.round(upper * REF_H / (float) gh),
                    Math.round(boxW * REF_W),
                    Math.round(boxH * REF_H)});
            }
            if (total <= bestScore) continue;
            bestScore = total;
            best = subjectOf(blob, gw, gh, width, height);
        }
        lastCandidates = marks.toArray(new int[0][]);
        if (best == null) return null;
        int calls;
        synchronized (LOCK) {
            calls = ++objectCalls;
        }
        if (calls <= 5 || calls % 40 == 0) {
            Log.i(TAG, String.format(Locale.US,
                "object #%d score %.2f centre %.2f,%.2f height %.1f%%",
                calls, bestScore, best[0], best[1], best[2] * 100f));
        }
        return best;
    }

    /**
     * Foliage: flowers, leaves, a potted plant, a bush, grass.
     *
     * <p>This is the subject class no other detector here can see. A plant has no face; its colour
     * fails the warm-skin test by construction (green, not warm); and the object scorer wants one
     * compact high-contrast blob, while a plant is a mass of the same colour with soft edges, so it
     * loses to whatever crisp rectangle is nearby. What a plant does have is a hue nothing else in a
     * room usually shares, so a green mask finds it directly instead of by shape.
     *
     * <p>Green is the whole test on purpose: flowering plants with yellow or red blossoms still have
     * leaves around them, and a mask that also accepted warm hues would claim wooden furniture -- the
     * exact confusion the skin classifier was rewritten to avoid. {@code
     * persist.omnicam.plantdetect=0} switches it off without a rebuild.
     */
    private static float[] plantIn(Bitmap frame) {
        if ("0".equals(prop("persist.omnicam.plantdetect", "1"))) return null;
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final int step = 8;
        final int gw = width / step;
        final int gh = height / step;
        if (gw < 4 || gh < 4) return null;
        final int[] data = pixelsOf(frame);

        final boolean[] leaf = new boolean[gw * gh];
        int cells = 0;
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int hits = 0;
                for (int y = 0; y < step; y++) {
                    int row = (gy * step + y) * width + gx * step;
                    for (int x = 0; x < step; x++) {
                        if (isLeaf(data[row + x])) hits++;
                    }
                }
                // A third of a cell is enough: leaf edges are soft and a strict majority would
                // break the mask up into speckle that no flood fill can join into a subject.
                if (hits * 3 >= step * step) {
                    leaf[gy * gw + gx] = true;
                    cells++;
                }
            }
        }
        // Trim on a desk, a green sticker or a plant on a distant shelf is not the subject. Two
        // percent of the frame is about a small bunch of flowers held at arm's length.
        if (cells < Math.max(6, gw * gh / 50)) return null;

        int[] stack = new int[gw * gh];
        boolean[] seen = new boolean[gw * gh];
        int bestCount = 0, bestLeft = 0, bestRight = 0, bestUpper = 0, bestLower = 0;
        float bestRank = 0f;
        for (int seed = 0; seed < leaf.length; seed++) {
            if (!leaf[seed] || seen[seed]) continue;
            int top = 0;
            stack[top++] = seed;
            seen[seed] = true;
            int count = 0, left = gw, right = -1, upper = gh, lower = -1;
            while (top > 0) {
                int index = stack[--top];
                int y = index / gw, x = index % gw;
                count++;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < upper) upper = y;
                if (y > lower) lower = y;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                        int next = ny * gw + nx;
                        if (!leaf[next] || seen[next]) continue;
                        seen[next] = true;
                        stack[top++] = next;
                    }
                }
            }
            // Largest wins only among plants the phone is actually pointed at. A windowsill of
            // pots beside a single flower filled more of the frame and took the shot; position now
            // weighs in, so the mass in the middle outranks the bigger mass at the edge.
            float cx = (left + right + 1) * 0.5f / gw;
            float cy = (upper + lower + 1) * 0.5f / gh;
            float dx = cx - 0.5f, dy = cy - 0.5f;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float rank = count * (1f - 0.45f * Math.min(1f, dist / 0.60f));
            if (rank > bestRank) {
                bestRank = rank;
                bestCount = count;
                bestLeft = left;
                bestRight = right;
                bestUpper = upper;
                bestLower = lower;
            }
        }
        if (bestCount < 6) return null;
        float boxW = (bestRight - bestLeft + 1) / (float) gw;
        float boxH = (bestLower - bestUpper + 1) / (float) gh;
        // A frame full of grass or a hedge fills the picture; that is scenery, and the scene branch
        // frames it better than a blob that touches both sides of the frame.
        if (boxW > 0.92f && boxH > 0.92f) return null;
        float[] blob = {bestCount, bestLeft, bestRight, bestUpper, bestLower, 0f, 0f};
        float[] subject = subjectOf(blob, gw, gh, width, height);
        if (subject != null) {
            int calls;
            synchronized (LOCK) {
                calls = ++plantCalls;
            }
            if (calls <= 5 || calls % 40 == 0) {
                Log.i(TAG, String.format(Locale.US,
                    "plant #%d cells %d box %.2fx%.2f centre %.2f,%.2f height %.0f%%",
                    calls, bestCount, boxW, boxH, subject[0], subject[1], subject[2] * 100f));
            }
        }
        return subject;
    }

    /** Green, as a leaf is green: enough `g` to beat both other channels at any brightness. */
    private static boolean isLeaf(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;
        int luma = (77 * r + 150 * g + 29 * b) >> 8;
        // Deep shade reads as noise; a lit leaf is never that dark.
        if (luma < 20) return false;
        // Green has to be the dominant channel with a margin. `g > r + 10` alone also accepts olive
        // walls, so the blue side is checked too -- foliage reflects very little blue.
        return g > 42 && g > r + 12 && g > b + 8;
    }

    private static int plantCalls;

    /**
     * Whatever the camera is pointed at: a blob grown from the middle of the frame.
     *
     * <p>This is the automatic path's first attempt, and it is deliberately a hard rule rather than
     * another term in the score. Pointing a phone at a can means the can is in the middle; an
     * object in a corner is in the shot by accident. Ranking every blob by how big and how vivid it
     * is cannot express that, and on the scene that prompted this the ranking kept handing the shot
     * to a blue file rack at the edge of the desk.
     *
     * <p>The middle still has to contain something. A flat wall, a bare tabletop or a length of
     * sky scores about the frame mean, and this returns null there so the scoring loop (or the
     * scene branch) takes over instead of framing an arbitrary patch.
     *
     * @return the same {centre x, eye y, subject height} triple the other detectors return, or
     *         null when the middle is not a subject
     */
    private static float[] centreBlob(float[] score, float[] tone, float[] satMap, boolean[] used,
                                      int[] stack, int gw, int gh, int cellLimit, float mean,
                                      int width, int height) {
        java.util.Arrays.fill(used, false);
        int cgx = gw / 2;
        int cgy = gh / 2;
        // A subject framed roughly on the thirds is still "aimed at", so the search window is wide
        // enough to reach it: a seventh of the frame either side covers a can held off centre, or a
        // subject the user put on a third line. It used to be a tenth, and anything the user aimed
        // slightly off -- the usual case when the phone is held one-handed -- fell through to the
        // whole-frame scorer, which then picked whatever large thing sat beside it.
        int radius = Math.max(2, Math.round(gw * 0.14f));
        int seed = -1;
        float best = 0f;
        for (int y = Math.max(0, cgy - radius); y <= Math.min(gh - 1, cgy + radius); y++) {
            for (int x = Math.max(0, cgx - radius); x <= Math.min(gw - 1, cgx + radius); x++) {
                int index = y * gw + x;
                if (score[index] <= best) continue;
                best = score[index];
                seed = index;
            }
        }
        // Nothing in the middle stands out from the frame at all: treat the shot as subject-less.
        // The margin over the mean is deliberately thin. At 1.05 a subject lit softer than its
        // background -- a pale object against a bright wall, which is most of what gets shot
        // indoors -- read as "nothing here", and the pass fell through to the corner picker.
        if (seed < 0 || best < mean * 1.02f) return null;

        float floor = Math.max(mean * 1.02f, best * 0.28f);
        float[] blob = growBlob(score, tone, satMap, used, stack, seed, floor, gw, gh, cellLimit,
            0, gw - 1, 0, gh - 1);
        if (blob == null || blob[0] < 4) {
            // The fill marks cells as used as it goes; the scoring loop below needs them back.
            java.util.Arrays.fill(used, false);
            return null;
        }
        int area = (int) blob[0];
        int left = (int) blob[1], right = (int) blob[2];
        int upper = (int) blob[3], lower = (int) blob[4];
        float boxW = (right - left + 1) / (float) gw;
        float boxH = (lower - upper + 1) / (float) gh;
        int edges = (left <= 0 ? 1 : 0) + (right >= gw - 1 ? 1 : 0)
            + (upper <= 0 ? 1 : 0) + (lower >= gh - 1 ? 1 : 0);
        // The middle of the frame cannot be mostly backdrop, mostly speck, or a band across an
        // edge; any of those means the centre happened to land on a surface, not on a thing.
        // The lower bounds are loose on purpose: a small subject held at arm's length sits near
        // 2-3% of the frame, and the old 8% / 1.2% pair rejected exactly those, which is why the
        // first press so often framed a neighbour instead.
        boolean usable = boxW <= 0.88f && boxH <= 0.88f
            && boxW >= 0.06f && boxH >= 0.06f
            && area >= score.length * 0.009f
            && edges < 3;
        if (!usable) {
            java.util.Arrays.fill(used, false);
            return null;
        }
        int calls;
        synchronized (LOCK) {
            calls = ++objectCalls;
        }
        if (calls <= 6 || calls % 40 == 0) {
            Log.i(TAG, String.format(Locale.US,
                "centre lock #%d box %.2fx%.2f area %.1f%% edges %d (score %.1f vs mean %.1f)",
                calls, boxW, boxH, area * 100f / score.length, edges, best, mean));
        }
        return subjectOf(blob, gw, gh, width, height);
    }

    /**
     * Highest-scoring unused cell that clears {@code floor}, optionally restricted to the middle of
     * the frame.
     *
     * @return the cell index, or -1 when nothing qualifies
     */
    private static int pickSeed(float[] score, boolean[] used, int gw, int gh, float floor,
                                boolean centreOnly) {
        int seed = -1;
        float best = floor;
        int minX = centreOnly ? Math.round(gw * 0.15f) : 0;
        int maxX = centreOnly ? Math.round(gw * 0.85f) : gw - 1;
        int minY = centreOnly ? Math.round(gh * 0.15f) : 0;
        int maxY = centreOnly ? Math.round(gh * 0.85f) : gh - 1;
        for (int gy = minY; gy <= maxY; gy++) {
            int row = gy * gw;
            for (int gx = minX; gx <= maxX; gx++) {
                int index = row + gx;
                if (used[index] || score[index] <= best) continue;
                best = score[index];
                seed = index;
            }
        }
        return seed;
    }

    /**
     * Flood-fills from {@code seed} over cells at or above {@code floor} whose brightness is close
     * to the seed's. The brightness gate is what keeps a blob from walking off a bright object onto
     * a dimmer desk: without it, growing from a can spilled into the tabletop, blew past the cell
     * limit and the can was thrown away as "the backdrop".
     *
     * @return {area, left, right, upper, lower, sum, toneSum} in grid cells, or null when the fill
     *         spills past {@code cellLimit}; {@code toneSum} is what says whether a later blob is
     *         the same thing or something else that happens to be in the window
     */
    private static float[] growBlob(float[] score, float[] tone, float[] satMap, boolean[] used,
                                    int[] stack, int seed, float floor, int gw, int gh,
                                    int cellLimit, int minX, int maxX, int minY, int maxY) {
        return growBlob(score, tone, satMap, used, stack, seed, floor, gw, gh, cellLimit,
            minX, maxX, minY, maxY, LUMA_TOLERANCE, SAT_TOLERANCE);
    }

    /**
     * The same fill with the gates opened up, for a subject the user picked by hand.
     *
     * <p>The tight gates exist to stop an automatic seed from wandering -- nothing told it which
     * thing it was looking at. A tap is that instruction, and a can is not one flat colour: its
     * label, its shadow and its highlight sit more than 26 luma apart, so the strict fill stopped
     * at 9% of the frame on an object occupying 25% of it and asked for a crop three times too
     * tight. Loosened, not open: the desk is still a different brightness and much less saturated.
     */
    private static float[] growPinned(float[] score, float[] tone, float[] satMap, boolean[] used,
                                      int[] stack, int seed, float floor, int gw, int gh,
                                      int cellLimit, int minX, int maxX, int minY, int maxY) {
        return growBlob(score, tone, satMap, used, stack, seed, floor, gw, gh, cellLimit,
            minX, maxX, minY, maxY, PIN_LUMA_TOLERANCE, PIN_SAT_TOLERANCE);
    }

    private static float[] growBlob(float[] score, float[] tone, float[] satMap, boolean[] used,
                                    int[] stack, int seed, float floor, int gw, int gh,
                                    int cellLimit, int minX, int maxX, int minY, int maxY,
                                    float lumaTolerance, float satTolerance) {
        final float seedTone = tone[seed];
        final float seedSat = satMap[seed];
        int top = 0;
        stack[top++] = seed;
        used[seed] = true;
        int area = 0, left = gw, right = 0, upper = gh, lower = 0;
        float blobSum = 0f;
        float toneSum = 0f;
        while (top > 0) {
            int index = stack[--top];
            int x = index % gw;
            int y = index / gw;
            area++;
            blobSum += score[index];
            toneSum += tone[index];
            if (x < left) left = x;
            if (x > right) right = x;
            if (y < upper) upper = y;
            if (y > lower) lower = y;
            for (int ny = Math.max(minY, y - 1); ny <= Math.min(maxY, y + 1); ny++) {
                for (int nx = Math.max(minX, x - 1); nx <= Math.min(maxX, x + 1); nx++) {
                    int next = ny * gw + nx;
                    if (used[next] || score[next] < floor) continue;
                    if (Math.abs(tone[next] - seedTone) > lumaTolerance) continue;
                    if (Math.abs(satMap[next] - seedSat) > satTolerance) continue;
                    used[next] = true;
                    stack[top++] = next;
                }
            }
            if (area > cellLimit) return null;
        }
        return new float[]{area, left, right, upper, lower, blobSum, toneSum};
    }

    /**
     * Last resort: no person and no distinct object. A skyline still has a horizon and a room still
     * has layers, and either is worth a suggestion -- skipping this is why pointing the camera at a
     * street, a beach or a wall of shelves ended in "no suggestion for this scene".
     *
     * <p>Two outcomes. A frame with sky over roughly a sixth of its height is read as a landscape,
     * and the horizon is placed on whichever third is nearer, which is the one composition rule
     * that actually applies to a view. A frame with no sky but real vertical structure is read as an
     * environment and merely tightened, since inventing a subject for it would be a lie.
     *
     * @return {left, top, width, height} in the reference frame, or null when the frame is flat
     *         enough that any crop would be arbitrary
     */
    private static int[] sceneIn(Bitmap frame) {
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final int step = 4;
        final int gh = height / step;
        if (width < step * 8 || gh < 12) return null;

        final int[] data = pixelsOf(frame);

        final float[] luma = new float[gh];
        final float[] sat = new float[gh];
        final float[] blue = new float[gh];
        for (int gy = 0; gy < gh; gy++) {
            int sumL = 0, sumS = 0, sumB = 0;
            int count = 0;
            int row = gy * step * width;
            for (int x = 0; x < width; x += step) {
                int pixel = data[row + x];
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                sumL += (77 * r + 150 * g + 29 * b) >> 8;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                sumS += max == 0 ? 0 : (max - min) * 100 / max;
                sumB += b - r;
                count++;
            }
            luma[gy] = sumL / (float) count;
            sat[gy] = sumS / (float) count;
            blue[gy] = sumB / (float) count;
        }

        // Sky: the top rows are bright and washed out, or clearly bluer than they are red. Overcast
        // skies are grey, which is why the brightness test carries as much weight as the colour.
        int skyRows = 0;
        for (int gy = 0; gy < gh; gy++) {
            if (!(luma[gy] > 118f && (sat[gy] < 46f || blue[gy] > 22f))) break;
            skyRows++;
        }
        float skyRatio = skyRows / (float) gh;

        // Steepest brightness step between rows, plus how much the frame changes at all. Both are
        // needed below: the step is what separates a horizon from a gradient, the total is what
        // separates a room with layers from a blank wall.
        int edgeRow = -1;
        float edge = 0f;
        float texture = 0f;
        for (int gy = 1; gy < gh; gy++) {
            float delta = Math.abs(luma[gy] - luma[gy - 1]);
            texture += delta;
            if (delta > edge) {
                edge = delta;
                edgeRow = gy;
            }
        }
        texture /= (gh - 1);
        float meanSat = 0f;
        for (int gy = 0; gy < gh; gy++) meanSat += sat[gy];
        meanSat /= gh;

        // A white wall under a cool lamp is bright, unsaturated and slightly blue, so brightness
        // alone calls it sky. A real sky ends somewhere: the steepest step between rows has to fall
        // on the boundary the sky test just found, and it has to be a step rather than a gradient.
        // Without both checks every indoor wall became a landscape.
        boolean boundary = edgeRow >= 0 && edge >= 5f
            && Math.abs(edgeRow - skyRows) <= Math.max(2, Math.round(gh * 0.08f));
        boolean landscape = skyRatio >= 0.16f && skyRatio <= 0.90f && boundary;

        float horizon;
        if (landscape) {
            horizon = clamp(skyRatio, 0.08f, 0.86f);
        } else {
            // No sky. Still worth framing when the scene has layers; a blank wall has neither a step
            // between rows nor much colour, and a suggestion there would be noise. Both gates used
            // to sit higher (2.4 / 16, 6.0) and that turned ordinary rooms, streets without a
            // visible horizon and close-up foliage into "no suggestion for this scene".
            if (texture < 1.9f && meanSat < 13f) return null;
            if (edgeRow < 0 || edge < 4.5f) return null;
            horizon = edgeRow / (float) gh;
        }

        // A view keeps the widest crop the zoom ladder allows (1.3x) so the horizon still reads as
        // one; an environment is only tightened a little, since there is nothing to isolate.
        int cropW = landscape ? MAX_CROP_W : 480;
        int cropH = cropW * 4 / 3;
        float left = (REF_W - cropW) * 0.5f;
        float top;
        if (landscape) {
            // Horizon on the near third: sky in the upper third when it is already a thin band,
            // ground in the lower third when the sky is the subject.
            float target = horizon < 0.5f ? 1f / 3f : 2f / 3f;
            top = horizon * REF_H - target * cropH;
        } else {
            top = (REF_H - cropH) * 0.5f;
        }
        top = clamp(top, 0f, REF_H - cropH);
        int[] box = {Math.round(left), Math.round(top), cropW, cropH};
        sceneKind = landscape ? KIND_LANDSCAPE : KIND_AMBIENT;
        int calls;
        synchronized (LOCK) {
            calls = ++sceneCalls;
        }
        if (calls <= 5 || calls % 40 == 0) {
            Log.i(TAG, String.format(Locale.US,
                "scene #%d %s sky %.0f%% horizon %.2f -> %d,%d %dx%d",
                calls, landscape ? "landscape" : "ambient", skyRatio * 100f, horizon,
                box[0], box[1], box[2], box[3]));
        }
        return box;
    }

    /**
     * The framing of last resort: the widest suggestion the crop ladder allows, centred.
     *
     * <p>{@link #MAX_CROP_W} is 588 of 768 reference pixels, which is 1.3x -- the loosest crop that
     * still counts as a composition to the camera. It is deliberately not tighter: this answer is
     * given for scenes where nothing was recognised, so the only thing it may do is tidy the edges.
     */
    private static int[] fallbackBox() {
        int cropW = MAX_CROP_W;
        int cropH = cropW * 4 / 3;
        if (cropH > REF_H) cropH = REF_H;
        return new int[]{(REF_W - cropW) / 2, (REF_H - cropH) / 2, cropW, cropH};
    }

    private static int fallbackCalls;

    /** Blob bounds in grid cells to the {centre x, eye y, subject height} triple cropOf expects. */
    private static float[] subjectOf(float[] blob, int gw, int gh, int width, int height) {
        int left = (int) blob[1], right = (int) blob[2];
        int upper = (int) blob[3], lower = (int) blob[4];
        float cx = (left + right + 1) * 0.5f / gw;
        float cy = (upper + lower + 1) * 0.5f / gh;
        float blobW = (right - left + 1) / (float) gw;
        float blobH = (lower - upper + 1) / (float) gh;
        // The crop is the blob plus a margin, not the blob scaled by the portrait head ratio:
        // FACE_TO_CROP counts how many face-heights fit the frame, which means nothing for a can and
        // had every object asking for 2.5x or more -- tight enough that the preview showed a patch
        // of desk rather than the thing it was aimed at.
        int cropW = snap(Math.max(blobW, blobH * 0.75f) * OBJECT_MARGIN * REF_W);
        // Never tighter than this on the automatic path. The panel applies the crop the moment
        // composition opens, and a can occupying 16% of the frame came back at 3.05x -- the same
        // "it zoomed all the way in by itself" complaint, only this time there was a real subject
        // behind it. A suggestion shows the subject and its surroundings, not a macro shot.
        cropW = Math.max(cropW, AUTO_MIN_CROP_W);
        int cropH = cropW * 4 / 3;        // cropOf() derives its width from this value; invert that relation so the margin above is
        // what actually comes out the other end.
        float subjectH = cropW / (FACE_TO_CROP * 0.75f);
        float eyeY = cy * REF_H + subjectH * EYE_TO_HEAD + cropH * HEAD_ROOM - cropH * 0.5f;
        return new float[]{cx, eyeY / REF_H, subjectH / REF_H};
    }

    /** Does this detection sit close enough to the previous one to be trusted with a move? */
    private static boolean agreesWithLast(float[] raw) {
        if (lastRaw == null) return true;
        return Math.abs(raw[0] - lastRaw[0]) < 0.045f
            && Math.abs(raw[1] - lastRaw[1]) < 0.06f
            && Math.abs(raw[2] - lastRaw[2]) < Math.max(0.04f, lastRaw[2] * 0.45f);
    }

    private static float[] smooth(float[] raw) {        if (subject == null) return raw.clone();
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = subject[i] + (raw[i] - subject[i]) * ALPHA;
        return out;
    }

    private static boolean moved(int[] before, int[] after) {
        // A locked subject has to keep up with the object, so the threshold that hides detector
        // jitter in automatic mode would freeze the box on screen while it is being carried.
        float deadband = pin != null ? TRACK_DEADBAND : DEADBAND;
        return Math.abs(before[0] - after[0]) >= REF_W * deadband
            || Math.abs(before[1] - after[1]) >= REF_H * deadband
            || Math.abs(before[2] - after[2]) >= REF_W * deadband * 2
            || Math.abs(before[3] - after[3]) >= REF_H * deadband * 2;
    }

    /** {left, top, width, height} in the 768x1024 reference frame. */
    private static int[] cropOf(float[] s) {
        float centreX = s[0] * REF_W;
        float eyeY = s[1] * REF_H;
        float faceH = Math.max(6f, s[2] * REF_H);

        float wanted = faceH * FACE_TO_CROP;
        int width = snap(wanted * 0.75f);
        int height = width * 4 / 3;

        // Rule of thirds, applied as a bias rather than a snap: a subject already off centre is
        // pushed further towards its own third, a centred one stays centred. Nothing flips.
        float fraction = 0.5f + (s[0] - 0.5f) * SIDE_BIAS;
        float left = centreX - width * fraction;
        float top = eyeY - faceH * EYE_TO_HEAD - height * HEAD_ROOM;

        left = clamp(left, 0f, REF_W - width);
        top = clamp(top, 0f, REF_H - height);
        return new int[]{Math.round(left), Math.round(top), width, height};
    }

    /** Nearest width on the 12 px ladder, clamped to the zoom range the camera can actually reach. */
    private static int snap(float width) {
        int value = Math.round(width / CROP_STEP) * CROP_STEP;
        return Math.max(MIN_CROP_W, Math.min(MAX_CROP_W, value));
    }

    /* ----------------------------------------------------------- aesthetics model */

    /**
     * How much scene each candidate keeps around the subject. The detector already knows where the
     * subject is; what rules cannot answer is how tight the frame should be, and that is exactly
     * what an aesthetics model is for. 1.0 is the detector's own pick.
     *
     * <p>The spread is deliberately narrow. A wide spread (0.74 to 1.34) gives the model a bigger
     * say but moves the box by more than a third of the frame whenever it changes its mind, and
     * the camera redraws the whole composition each time -- which looks like the feature restarting
     * on its own.
     */
    private static final float[] TIGHTNESS = {1.18f, 1.0f, 0.86f};
    /** Margin a challenger needs before it replaces the framing already on screen. */
    private static final float SCORE_MARGIN = 0.18f;

    private static int lastVariant = 1;
    private static float lastVariantScore = Float.NaN;
    /** The subject the last aesthetic pass scored, and the crop it settled on. */
    private static float[] lastScoredSubject;
    private static int[] lastScoredBox;

    /** How far a subject may drift before its framing is worth re-scoring, normalised. */
    private static final float RESCORE_DEADBAND = 0.04f;

    /**
     * How many times one session may run the aesthetics model.
     *
     * <p>One pass is enough to pick a tightness, and the model has no memory of its own: every
     * call re-enters AIUnit, whose native heap the camera keeps. Measured on the phone, each extra
     * round of scoring added a step to the process (264 -> 328 -> 390 MB) and never gave it back.
     * Two is the ceiling -- the first framing, plus one correction if the subject moved a long way.
     */
    private static final int AESTHETIC_BUDGET = 2;
    private static int aestheticCalls;

    /**
     * Offers the model three framings of the same subject and keeps the best-scoring one.
     *
     * <p>Only the tightness varies: the centre still comes from the detector, so a model that
     * disagrees about what the subject is cannot drag the box onto something else. When the model
     * is unavailable the detector's own crop is used unchanged.
     *
     * <p>The aesthetics model is the one truly expensive step in the follow loop: it runs a
     * cross-process AIUnit inference once per candidate, so three times per detection. That is what
     * drove the camera process from ~300MB to 400MB+ in seconds and left the preview stuttering
     * after a shot. A subject that has not moved does not need a fresh opinion, so a cached crop is
     * reused until it drifts past {@link #RESCORE_DEADBAND}.
     */
    private static int[] refineByAesthetic(Bitmap frame, float[] subject, int[] fallback) {
        if (frame == null || subject == null || fallback == null) return fallback;
        if (frame.isRecycled() || !AestheticScorer.enabled()) return fallback;
        // A pinned subject is answered by the detector alone. The model only knows how tight a
        // frame looks good, and it was tightening a hand-picked subject to 3x -- the box the user
        // asked for, then a close-up they did not.
        if (pin != null) return fallback;
        // Still subject, same opinion: the model already decided this framing, and re-asking it
        // every 220ms was the whole memory storm. Reuse the held crop until the subject drifts.
        if (lastScoredSubject != null && lastScoredBox != null && !Float.isNaN(lastVariantScore)) {
            float dx = Math.abs(subject[0] - lastScoredSubject[0]);
            float dy = Math.abs(subject[1] - lastScoredSubject[1]);
            float dh = Math.abs(subject[2] - lastScoredSubject[2]);
            if (dx < RESCORE_DEADBAND && dy < RESCORE_DEADBAND && dh < RESCORE_DEADBAND) {
                return lastScoredBox;
            }
        }
        int frameW = frame.getWidth();
        int frameH = frame.getHeight();
        if (frameW < 32 || frameH < 32) return fallback;
        // Hard cap per session, on top of the drift cache above. Without it a subject that keeps
        // moving keeps re-entering the model, and each entry leaves native memory behind.
        synchronized (LOCK) {
            if (aestheticCalls >= AESTHETIC_BUDGET) {
                return lastScoredBox != null ? lastScoredBox : fallback;
            }
            aestheticCalls++;
        }
        int[][] crops = new int[TIGHTNESS.length][];
        Rect[] boxes = new Rect[TIGHTNESS.length];
        for (int i = 0; i < TIGHTNESS.length; i++) {
            float[] variant = {subject[0], subject[1], subject[2] * TIGHTNESS[i]};
            int[] box = cropOf(variant);
            crops[i] = box;
            int left = Math.max(0, Math.round(box[0] * (float) frameW / REF_W));
            int top = Math.max(0, Math.round(box[1] * (float) frameH / REF_H));
            int width = Math.max(8, Math.min(frameW - left,
                Math.round(box[2] * (float) frameW / REF_W)));
            int height = Math.max(8, Math.min(frameH - top,
                Math.round(box[3] * (float) frameH / REF_H)));
            boxes[i] = new Rect(left, top, left + width, top + height);
        }
        // While a subject is locked the tightness is decided once, when the lock is made, and then
        // held. The model's score wobbles by about the margin it needs to win, so re-scoring every
        // frame made the framing trade places with itself (0 -> 2 -> 0 -> 1 in one session) and the
        // viewfinder zoomed in and out on its own. This is also the expensive call in the loop.
        if (pin != null && !Float.isNaN(lastVariantScore)) {
            int[] held = crops[lastVariant];
            if (detections <= 2 || detections % 60 == 0) {
                Log.i(TAG, String.format(Locale.US, "framing held at %d/%.2f during lock",
                    lastVariant, TIGHTNESS[lastVariant]));
            }
            return held != null ? held : fallback;
        }
        float[] picked = AestheticScorer.best(frame, boxes);
        if (picked == null) return fallback;
        int index = Math.max(0, Math.min(crops.length - 1, (int) picked[0]));
        float score = picked[1];
        // Scores wobble a little from frame to frame, so two near-equal framings would trade places
        // and make the box breathe. A challenger has to win by a margin to take over.
        float previous = Float.isNaN(lastVariantScore) ? Float.NEGATIVE_INFINITY : lastVariantScore;
        if (index == lastVariant || score > previous + SCORE_MARGIN) {
            if (index != lastVariant) {
                Log.i(TAG, String.format(Locale.US, "framing %d -> %d (%.3f vs %.3f)",
                    lastVariant, index, score, previous));
            }
            lastVariant = index;
            lastVariantScore = score;
        }
        int[] chosen = crops[lastVariant];
        if (chosen == null) return fallback;
        // Remember what was scored so a still subject skips the next inference entirely.
        lastScoredSubject = subject.clone();
        lastScoredBox = chosen.clone();
        if (detections <= 2 || detections % 30 == 0) {
            Log.i(TAG, String.format(Locale.US, "aesthetic pick %d/%.2f score %.3f zoom %.2f",
                lastVariant, TIGHTNESS[lastVariant], lastVariantScore,
                REF_W / (double) chosen[2]));
        }
        return chosen;
    }

    private static float clamp(float value, float low, float high) {
        if (high < low) return low;
        return value < low ? low : (value > high ? high : value);
    }

    private static String label(int kind) {
        switch (kind) {
            case KIND_OBJECT: return "object";
            case KIND_PLANT: return "plant";
            case KIND_PINNED: return "locked";
            case KIND_LANDSCAPE: return "landscape";
            case KIND_AMBIENT: return "ambient";
            default: return "face";
        }
    }

    private static String jsonOf(int[] box, int kind) {
        double zoom = REF_W / (double) box[2];
        boolean closeUp = zoom >= 2.6;
        String title;
        String desc;
        if (kind == KIND_PINNED) {
            // Says what mode the camera is in and how to leave it, because the box alone looks like
            // any other suggestion and a lock that cannot be recognised cannot be undone.
            title = "跟随构图";
            desc = "主体已锁定，移动时会跟着走；长按取景器任意位置可解除。";
        } else if (kind == KIND_LANDSCAPE) {
            title = "风光构图";
            desc = "地平线落在三分线上，天空与地面分开层次，画面开阔有纵深。";
        } else if (kind == KIND_PLANT) {
            title = closeUp ? "花草特写" : "花草构图";
            desc = closeUp
                ? "花叶收进三分线，突出形状与色彩，背景轻轻化开。"
                : "留住花木周围的枝叶与环境，主体不顶边，画面更舒展。";
        } else if (kind == KIND_AMBIENT) {
            title = "环境构图";
            desc = "画面没有单一主体，收住四周杂边，让光线与层次成为主体。";
        } else if (kind == KIND_OBJECT) {
            title = closeUp ? "近景构图" : "环境构图";
            desc = closeUp
                ? "主体收进三分线，画面紧凑，突出细节与质感。"
                : "保留主体所处环境，主体落在三分线上，留白更自然。";
        } else {
            title = closeUp ? "近景人像" : "环境人像";
            desc = closeUp
                ? "人物收进三分线，突出面部神态与上半身姿态。"
                : "保留人物所处环境，主体落在三分线上，画面有呼吸感。";
        }
        // Say which channel decided this. The panel is the only place the user ever meets this
        // feature, and a long press that silently switched between the model and the device looked
        // like the gesture did nothing -- there is no other label on screen to tell them apart.
        desc = "端侧识别 · " + desc;
        // Two crops, not one: the panel only builds cards for a multi-crop answer. A single-crop
        // answer is applied silently, which is why the local path looked like "no suggestion" even
        // when the box on screen was clearly right. The tighter alternative is the same subject at
        // the ratio the stock answer uses (3.0 / 4.76279 = 1.59).
        int w1 = box[2];
        int h1 = box[3];
        int w2 = Math.max(2, Math.round(w1 / TIGHT_RATIO));
        int h2 = Math.max(2, Math.round(w2 * (h1 / (float) w1)));
        int cx = box[0] + w1 / 2;
        int cy = box[1] + h1 / 2;
        int x2 = Math.max(0, Math.min(REF_W - w2, cx - w2 / 2));
        int y2 = Math.max(0, Math.min(REF_H - h2, cy - h2 / 2));
        double zoom2 = REF_W / (double) w2;
        return String.format(Locale.US,
            "{\"outputNum\":2,\"crops\":["
                + "{\"zoom\":%.7f,\"srcCrop\":[%d,%d,%d,%d],"
                + "\"version\":\"omnicam-follow\",\"confidenceLevel\":90,"
                + "\"compositionDesc\":{\"title\":\"%s\",\"desc1\":\"%s\",\"desc2\":\"\",\"desc3\":\"\"}},"
                + "{\"zoom\":%.7f,\"srcCrop\":[%d,%d,%d,%d],"
                + "\"version\":\"omnicam-follow-tight\",\"confidenceLevel\":90,"
                + "\"compositionDesc\":{\"title\":\"%s\",\"desc1\":\"%s\",\"desc2\":\"\",\"desc3\":\"\"}}]}",
            zoom, box[0], box[1], box[0] + w1, box[1] + h1, title, desc,
            zoom2, x2, y2, x2 + w2, y2 + h2, title, tightDesc(desc));
    }

    /** The same suggestion, closer. Kept as a rewrite of the main line so the two stay in step. */
    private static String tightDesc(String desc) {
        if (desc == null) return "拉近取景，突出主体细节。";
        return desc.endsWith("。") ? desc.substring(0, desc.length() - 1) + "，拉近一档更突出主体。"
            : desc + "，拉近一档更突出主体。";
    }

    /* ---------------------------------------------------------------- diagnostics */

    /** Writes the sampled frame with the chosen crop drawn on it, for eyeballing a bad decision. */
    private static void dump(int[] box) {
        Bitmap out = null;
        try {
            // Copied under the lock: update() recycles the previous sample as soon as it has a new
            // one, so a reference taken outside would be recycled mid-draw.
            synchronized (LOCK) {
                if (lastFrame == null) return;
                out = lastFrame.copy(Bitmap.Config.ARGB_8888, true);
            }
            if (out == null) return;
            BOX.setColor(Color.RED);
            BOX.setStyle(Paint.Style.STROKE);
            BOX.setStrokeWidth(3f);
            float sx = out.getWidth() / (float) REF_W;
            float sy = out.getHeight() / (float) REF_H;
            Canvas canvas = new Canvas(out);
            // Every candidate is drawn thin and blue, the chosen one thick and red: a wrong pick is
            // then a matter of reading the picture, not of guessing which blob won.
            int[][] marks = lastCandidates;
            if (marks != null) {
                Paint other = new Paint();
                other.setColor(Color.BLUE);
                other.setStyle(Paint.Style.STROKE);
                other.setStrokeWidth(2f);
                for (int[] mark : marks) {
                    canvas.drawRect(new RectF(mark[0] * sx, mark[1] * sy,
                        (mark[0] + mark[2]) * sx, (mark[1] + mark[3]) * sy), other);
                }
            }
            canvas.drawRect(new RectF(box[0] * sx, box[1] * sy,
                (box[0] + box[2]) * sx, (box[1] + box[3]) * sy), BOX);
            FileOutputStream file = new FileOutputStream(
                "/data/user/0/com.oplus.camera/files/follow.png");
            out.compress(Bitmap.CompressFormat.PNG, 90, file);
            file.close();
            Log.i(TAG, "dump written");
        } catch (Throwable error) {
            Log.e(TAG, "dump failed", error);
        } finally {
            if (out != null) out.recycle();
        }
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class)
                .invoke(null, key, fallback);
            return String.valueOf(value);
        } catch (Throwable error) {
            return fallback;
        }
    }
}
