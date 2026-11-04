package local.jiege.hook.aicomposition;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The on-device image aesthetics model ({@code iqa_v2.0.0}, unit {@code image_aesthetics}).
 *
 * <p>It is a scorer, not a generator: it answers "how good does this frame look" with a single
 * number. That makes it useless on its own but exactly right for choosing between candidates the
 * local heuristics propose, which is how the rule-based path stops picking whatever is most
 * saturated and starts picking whatever is best composed.
 *
 * <p>Fully local, no account, no upload. Called off the camera's frame thread.
 */
final class AestheticScorer {
    private static final String TAG = "CompAesthetic";
    private static final String CLIENT = "com.oplus.aiunit.vision.client.AestheticsClient";
    private static final long CALL_TIMEOUT = 1500L;

    private static volatile Object client;
    private static volatile boolean dead;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile int calls;
    private static volatile float lastScore = Float.NaN;

    /** Reused crop buffer and the canvas that fills it with the candidate region. */
    private static Bitmap cropFrame;
    private static Canvas cropCanvas;
    private static final Paint CROP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Rect SOURCE = new Rect();
    private static final Rect TARGET = new Rect();

    private AestheticScorer() {}

    /**
     * Off by default: measured on the PKJ110 it costs the camera process ~276 MB of native heap.
     *
     * <p>{@code process()} loads the iqa model into the calling process and keeps it there -- with
     * the scorer running, {@code dumpsys meminfo} shows native heap climbing 264 MB -> 390 MB in
     * three steps and staying there; with this switch off it holds at 114 MB, the same figure as
     * with the whole follow loop disabled. What it buys is a choice between three tightness
     * variants of a crop the detector already decided, which is a refinement, not a subject.
     * {@code persist.omnicam.aesthetic=1} turns it back on; the pass is also capped per session
     * (see {@code SubjectLocator.AESTHETIC_BUDGET}) so the cost cannot repeat.
     */
    static boolean enabled() {
        return "1".equals(prop("persist.omnicam.aesthetic", "0"));
    }

    static String status() {
        if (dead) return "unavailable";
        float score = lastScore;
        return (client == null ? "cold" : "ready")
            + " calls=" + calls
            + (Float.isNaN(score) ? "" : String.format(Locale.US, " last=%.3f", score));
    }

    /** @return the score in roughly 0..1, or NaN when the model cannot answer. */
    static float score(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || dead || !enabled()) return Float.NaN;
        // One scoring pass at a time: the preview thread must never queue up behind the model.
        if (!BUSY.compareAndSet(false, true)) return Float.NaN;
        try {
            Object target = client();
            if (target == null) return Float.NaN;
            // The SDK rejects process() from the main thread; callers of score() are always on the
            // detection thread, so it is invoked directly here.
            Method process = target.getClass().getMethod("process", Bitmap.class);
            Object value = process.invoke(target, bitmap);
            calls++;
            if (value instanceof Number) {
                float result = ((Number) value).floatValue();
                lastScore = result;
                if (calls <= 6 || calls % 50 == 0) {
                    Log.i(TAG, "score " + String.format(Locale.US, "%.3f", result) + " #" + calls);
                }
                return result;
            }
            Log.i(TAG, "odd score type " + value);
            return Float.NaN;
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            Log.i(TAG, "score failed: " + cause);
            // A missing unit stays missing; do not retry on every frame.
            if (String.valueOf(cause).contains("Service") || String.valueOf(cause).contains("null")) {
                dead = true;
            }
            return Float.NaN;
        } finally {
            BUSY.set(false);
        }
    }

    /**
     * Scores every candidate on the same frame.
     *
     * @param candidates crop boxes in pixel coordinates of {@code frame}, all frame-shaped
     * @return {@code {index, score}}, or null when the model is unavailable or every candidate
     *         failed
     */
    static float[] best(Bitmap frame, Rect[] candidates) {
        if (frame == null || candidates == null || candidates.length < 2) return null;
        if (!enabled() || dead) return null;
        float bestScore = Float.NEGATIVE_INFINITY;
        int bestIndex = -1;
        for (int i = 0; i < candidates.length; i++) {
            Rect box = candidates[i];
            if (box == null || box.width() < 8 || box.height() < 8) continue;
            try {
                // One reused crop buffer. A fresh createBitmap per candidate per frame was another
                // 0.2-0.6MB of malloc churn every 220ms, which glibc holds on to (see
                // SubjectLocator.sampleFrame for the full story).
                if (cropFrame == null || cropFrame.isRecycled()
                    || cropFrame.getWidth() != box.width()
                    || cropFrame.getHeight() != box.height()) {
                    cropFrame = Bitmap.createBitmap(box.width(), box.height(),
                        Bitmap.Config.ARGB_8888);
                    cropCanvas = new Canvas(cropFrame);
                }
                if (cropCanvas == null) cropCanvas = new Canvas(cropFrame);
                SOURCE.set(box.left, box.top, box.right, box.bottom);
                TARGET.set(0, 0, box.width(), box.height());
                cropCanvas.drawBitmap(frame, SOURCE, TARGET, CROP_PAINT);
                float value = score(cropFrame);
                if (Float.isNaN(value)) continue;
                if (value > bestScore) {
                    bestScore = value;
                    bestIndex = i;
                }
            } catch (Throwable error) {
                Log.i(TAG, "candidate " + i + " failed: " + error);
            }
        }
        if (bestIndex >= 0 && calls <= 12) {
            Log.i(TAG, "picked candidate " + bestIndex + "/" + candidates.length
                + String.format(Locale.US, " at %.3f", bestScore));
        }
        return bestIndex < 0 ? null : new float[]{bestIndex, bestScore};
    }

    private static Object client() {
        if (dead) return null;
        Object ready = client;
        if (ready != null) return ready;
        synchronized (AestheticScorer.class) {
            if (client != null) return client;
            if (dead) return null;
            Object made = AiUnitBridge.newClient(CLIENT, null);
            if (made == null) {
                dead = true;
                Log.i(TAG, "aesthetics client unavailable");
                return null;
            }
            client = made;
            Log.i(TAG, "aesthetics client ready");
            return made;
        }
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> system = Class.forName("android.os.SystemProperties");
            Method get = system.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value == null ? fallback : String.valueOf(value).trim();
        } catch (Throwable error) {
            return fallback;
        }
    }
}
