package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Restores the master live-photo video to the state a fresh master session has.
 *
 * <p><b>r420 root cause (2026-11-18 controlled A/B, same scene, same process):</b> stock master
 * live runs with {@code LutParameters.g == false} -- the recorded frames never bind the BasicTone
 * sampler. A GR visit turns that stock flag on (GR grades through BasicTone), and nothing on the
 * GR -> master switch turns it back off. The very same pipeline then records the master live
 * video with the sampler bound, so those frames get an extra BasicTone pass over the ordinary
 * colour-space table and play back grey and washed-out (+27 V, -26 S against the same-session
 * direct-master reference) while the still cover, graded by its own capture passes, looks right.
 * Every other stage of the chain was verified identical between the two captures: the live LUT
 * callback data (169adb06), the render textures, the ch.c/P010 converter, the HEVC VUI and the
 * capture-time AWB. The flag is the whole difference.</p>
 *
 * <p>The repair is minimal: while the camera is in master (not GR, not XPAN), every draw of the
 * live warp renderer and every capture-frame callback clears {@code g} back to false -- restoring
 * exactly what stock does on a fresh master entry. This runs by default.</p>
 *
 * <p>The older experimental "grade the video through the cover's table" path (r408-r412: upload
 * the cover {@code mb*} table into {@code renderer.s} and raise {@code g}) measured warm/saturated
 * against the stock camera and stays opt-in behind
 * {@code persist.omnicam.aura.livetable=on}; see {@link #VIDEO_LUT}.</p>
 */
final class GrMasterLiveLutRestore {
    private static final String TAG = "RicohGrPort";
    private static final String PIPELINE = "@VideoEffectPipeline";
    private static final String IMAGE_DATA =
        "com.oplus.camera.feature.livephoto.data.ImageData";
    private static final String LIVE_RENDERER = "@WarpRenderer";
    private static final String LUT_MANAGER_HOLDER =
        "com.oplus.camera.common.utils.Lut3dDataManager$b";
    private static final int MASTER_LIVE_LUT_TYPE = 7;

    /**
     * What the renderer's BasicTone texture was last <em>uploaded</em> from, per renderer.
     *
     * <p>This has to be recorded at upload time, not read back off the texture. Stock
     * {@code onInit()} hands the texture the very same {@code float[]} instance that
     * {@code Lut3dDataManager.c} holds, and the native side rewrites that instance in place, so the
     * texture's own {@code A} field always samples identical to {@code manager.c}. Comparing those
     * two answers "same buffer" instead of "same pixels in the GPU texture": after the first
     * upload the check short-circuits forever and the live picture keeps whatever table was
     * uploaded when the pipeline was built -- i.e. the previous mode's (or stock) table, which is
     * exactly the warm, saturated "bare stream" video r405/r406 still showed.
     *
     * <p>Only the coarse fingerprint is kept (~1.5k ints, 6 KiB), so a strong reference is fine;
     * the entry disappears with the renderer. The texture identity is stored alongside because a
     * rebuilt texture must be re-uploaded even when the table did not change.
     */
    private static final Map<Object, Uploaded> uploadedTables = new WeakHashMap<>();

    private static final class Uploaded {
        final int texture;
        final int[] sample;

        Uploaded(int texture, int[] sample) {
            this.texture = texture;
            this.sample = sample;
        }
    }

    /** Stops a failing upload from logging once per frame. */
    private static final int MAX_UPLOAD_ATTEMPTS = 6;
    private static int uploadAttempts;

    /** Distinct tables already written out, so a capture produces one file per table. */
    private static final java.util.Set<Integer> dumpedTables =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    /**
     * The live table of the directory the still pipeline is using, converted to the interleaved
     * layout the renderer texture expects. {@code mastercolor} bakes it planar
     * ({@code raw[index]}, {@code raw[nodes+index]}, {@code raw[2*nodes+index]} with
     * {@code index=(b*n+g)*n+r}), the same convention as the stock LMT files it replaces, while
     * {@code Lut3dDataManager.c} -- what {@code onInit()} hands to the texture -- is interleaved
     * RGB per node. Cached per directory and stamped, because this is read on every live frame.
     */
    private static String liveDir;
    private static long liveStamp = -1;
    private static float[] liveTable;

    private static final java.io.File LIVE_ROOT =
        new java.io.File("/data/user/0/com.oplus.camera/files/jiege/mastercolor/live");

    /**
     * The directory the still/cover pipeline is graded with.
     *
     * <p>{@code mastercolor/live} holds one directory per baked table: {@code p} preview,
     * {@code t} thumbnail, {@code mb} main buffer. r409 measured what the live video was actually
     * sampling: {@code p04000} -- byte-identical to the degenerate lattice that
     * {@code e(1, 0, "professional")} produces, and measurably different from the {@code mb} table
     * the cover is graded with (max channel difference 0.37). The recorded frames therefore have
     * to be pointed at the newest main-buffer table instead of whatever the manager happens to
     * hold.
     */
    static String coverTableDir() {
        String best = null;
        long bestTime = -1;
        String fallback = null;
        long fallbackTime = -1;
        String[] names = LIVE_ROOT.list();
        if (names != null) {
            for (String name : names) {
                boolean main = name.startsWith("mb");
                if (!main && !name.startsWith("t")) continue;
                long time = new java.io.File(LIVE_ROOT, name).lastModified();
                if (main) {
                    if (time > bestTime) {
                        bestTime = time;
                        best = name;
                    }
                } else if (time > fallbackTime) {
                    fallbackTime = time;
                    fallback = name;
                }
            }
        }
        if (best != null) return new java.io.File(LIVE_ROOT, best).getAbsolutePath();
        if (fallback != null) return new java.io.File(LIVE_ROOT, fallback).getAbsolutePath();
        return local.jiege.hook.mastercolor.MasterColor.previewTableDir();
    }

    private static float[] liveTable(int size) {
        String dir = coverTableDir();
        if (dir == null) return null;
        java.io.File file = new java.io.File(dir, "LMTLut0");
        long stamp = file.lastModified() * 31L + file.length();
        if (dir.equals(liveDir) && stamp == liveStamp && liveTable != null) return liveTable;
        final int nodes = size * size * size;
        if (file.length() != nodes * 12L) {
            return null;
        }
        byte[] raw = new byte[nodes * 12];
        try (java.io.FileInputStream stream = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < raw.length) {
                int step = stream.read(raw, read, raw.length - read);
                if (step <= 0) break;
                read += step;
            }
            if (read != raw.length) return null;
        } catch (Throwable error) {
            Log.e(TAG, "live table read failed: " + dir, error);
            return null;
        }
        float[] planar = new float[nodes * 3];
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer().get(planar);
        float[] interleaved = new float[nodes * 3];
        for (int i = 0; i < nodes; i++) {
            interleaved[3 * i] = planar[i];
            interleaved[3 * i + 1] = planar[nodes + i];
            interleaved[3 * i + 2] = planar[2 * nodes + i];
        }
        liveDir = dir;
        liveStamp = stamp;
        liveTable = interleaved;
        Log.i(TAG, "live table loaded from " + dir + " size=" + size
            + " sample=" + describe(sample(interleaved)));
        return interleaved;
    }

    private static void dumpOnce(float[] table, int size) {
        int key = java.util.Arrays.hashCode(sample(table));
        if (!dumpedTables.add(key)) return;
        java.io.File dir = new java.io.File("/data/user/0/com.oplus.camera/files/jiege");
        dir.mkdirs();
        java.io.File out = new java.io.File(dir, "tbl_" + Integer.toHexString(key) + ".bin");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
            .allocate(table.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : table) buffer.putFloat(value);
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(out)) {
            stream.write(buffer.array());
        } catch (Throwable error) {
            Log.e(TAG, "table dump failed", error);
            return;
        }
        Log.i(TAG, "table dumped size=" + size + " len=" + table.length
            + " sample=" + Integer.toHexString(key) + " path=" + out.getAbsolutePath());
    }

    /** One line per distinct skip reason, so a single capture pins down the gate that fired. */
    private static final java.util.Set<String> reported =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** The first few frames are logged in full: they carry the per-frame state we cannot infer. */
    private static final int FRAME_LOG_LIMIT = 3;
    private static int frameLogs;

    /** Same for WarpRenderer.p(); r410 showed it can bail without saying why. */
    private static final int DRAW_LOG_LIMIT = 3;
    private static int drawLogs;

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String flag(Object target, String name) {
        if (target == null) return "?";
        try {
            return String.valueOf(XposedHelpers.getBooleanField(target, name));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * Stock master-live value of {@code LutParameters.g}, learned from the first master render
     * seen before GR or XPAN was ever entered in this camera process (nothing can have left a
     * residue behind at that point). 123 answers false; 317 answers true -- stock master live
     * there keeps the BasicTone sampler on, so forcing the flag off is what turned its live clip
     * grey once the r522 scope fix had removed the log LUT from Master.
     */
    private static volatile Boolean stockMasterGate;
    /** True once XPAN has been entered in this process (its live renders set g on purpose). */
    private static volatile boolean xpanEntered;

    static void trackGateSources(Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if ("xpan".equals(String.valueOf(arg))) {
                            xpanEntered = true;
                            break;
                        }
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "basic tone gate source tracking unavailable", error);
        }
    }

    /**
     * r420: take the GR-era {@code LutParameters.g} residue off a master live render. GR (and the
     * XPAN live renders) need the flag true and leave it behind on the shared pipeline; stock
     * master has its own value for it. "Residue" therefore means "differs from what stock master
     * had", not "is true": restore the learned stock value instead of blindly writing false, and
     * touch nothing while neither source mode has been visited. When the stock value was never
     * observed, fall back to the r420 rule (clear it).
     */
    private static void clearStaleBasicToneGate(Object params, String where) {
        if (params == null) return;
        if (GrState.isGrMode() || !GrPreviewStreamRestore.inMasterMode()) return;
        try {
            boolean current = XposedHelpers.getBooleanField(params, "g");
            Boolean stock = stockMasterGate;
            if (!GrPreviewStreamRestore.hasLeftGr() && !xpanEntered) {
                // Clean state by construction: remember it, never modify it.
                if (stock == null) stockMasterGate = Boolean.valueOf(current);
                return;
            }
            if (stock != null && stock.booleanValue() == current) return;
            boolean target = stock == null ? false : stock.booleanValue();
            XposedHelpers.setBooleanField(params, "g", target);
            reportOnce("post-GR basic tone gate restored (" + where + ")"
                + ": lutParams.g " + current + " -> " + target
                + " (stock master live value " + (stock == null ? "unknown, r420 rule" : stock)
                + ", leftGr=" + GrPreviewStreamRestore.hasLeftGr()
                + ", xpanEntered=" + xpanEntered + ")"
                + ", f=" + flag(params, "f"));
        } catch (Throwable error) {
            reportOnce("gate clear unreadable (" + where + "): " + error);
        }
    }

    private GrMasterLiveLutRestore() {}

    /**
     * Live-photo video grading is opt-in since r418. Uploading the cover's {@code mb*} table into
     * the video renderer's BasicTone texture (r408-r412) was measured to push the video warm and
     * saturated relative to its own cover (R/G +0.012, S +0.013) while the stock camera grades the
     * video slightly cooler and duller than the cover on the same scene -- so the default is to
     * leave the video on the vendor pipeline entirely, matching the reference builds (Muse 3.4.1
     * and the X8U port ship no video hooks at all). Set {@code persist.omnicam.aura.livetable=on}
     * to re-enable the experimental texture sync.
     */
    private static final boolean VIDEO_LUT =
        "on".equalsIgnoreCase(prop("persist.omnicam.aura.livetable"));

    private static String prop(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : value.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    static void install(ClassLoader classLoader) throws Throwable {
        final Class<?> pipeline = Symbols.get().cls("VideoEffectPipeline");
        final Class<?> imageData = XposedHelpers.findClass(IMAGE_DATA, classLoader);
        final Class<?> liveRenderer = Symbols.get().cls("WarpRenderer");
        final Class<?> managerHolder = XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);

        trackGateSources(Symbols.get());

        // The rebuild that produces the picture's BasicTone table runs from e(); capturing it here
        // means one mode switch already yields the table, without waiting for a live capture.
        try {
            final Class<?> manager = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager", classLoader);
            XposedBridge.hookMethod(
                Symbols.get().method("@Lut3d.load"),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!GrPreviewStreamRestore.hasLeftGr()) return;
                        try {
                            Object instance = XposedHelpers.getStaticObjectField(managerHolder, "a");
                            if (instance == null) return;
                            Object lock = XposedHelpers.getObjectField(instance, "a");
                            float[] table;
                            int size;
                            synchronized (lock == null ? instance : lock) {
                                table = (float[]) XposedHelpers.getObjectField(instance, "c");
                                size = XposedHelpers.getIntField(instance, "t");
                            }
                            if (!isValidTable(table, size)) return;
                            dumpOnce(table, size);
                        } catch (Throwable error) {
                            Log.e(TAG, "manager table dump after e() failed", error);
                        }
                    }
                });
            Log.i(TAG, "manager table dump after e() installed");
        } catch (Throwable error) {
            Log.e(TAG, "manager table dump after e() unavailable", error);
        }

        XposedBridge.hookMethod(
            XposedHelpers.findMethodExact(pipeline, "r", imageData),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object instance = param.thisObject;
                    // r420: the pipeline's own LutParameters must be back on the stock master
                    // state before any frame of this capture is rendered.
                    clearStaleBasicToneGate(field(instance, "o0"), "capture frames");
                    if (!VIDEO_LUT) {
                        reportOnce("video lut off (default since r418; enable with persist.omnicam.aura.livetable=on)");
                        return;
                    }
                    if (!isTargetMode()) {
                        reportOnce("mode gate: leftGr=" + GrPreviewStreamRestore.hasLeftGr()
                            + " master=" + GrPreviewStreamRestore.inMasterMode()
                            + " current=" + GrPreviewStreamRestore.modeName()
                            + " gr=" + GrState.isGrMode());
                        return;
                    }
                    try {
                        syncBeforeFrame(instance, pipeline, liveRenderer, managerHolder);
                    } catch (Throwable error) {
                        Log.e(TAG, "master live BasicTone texture sync failed", error);
                    }
                }
            });
        /*
         * gh.p3.r() only covers the capture-time frames. The sustained per-frame path is
         * WarpRenderer.p(..., LutParameters at args[8]) -- the same entry XpanLiveFeature uses,
         * where stock decides from LutParameters.g whether the BasicTone sampler is bound at all.
         * Master leaves that flag cleared, so nothing samples renderer.s no matter what it holds.
         */
        XposedBridge.hookAllMethods(liveRenderer,
            Symbols.get().name("@WarpRenderer.draw"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // r420: every live video frame draws through here, so the stale GR gate is
                // cleared per frame before stock reads it -- covering the pre-shutter frames a
                // capture-time hook would miss. Mode-gated: GR and XPAN renders keep their own g.
                if (param.args.length > 8) {
                    clearStaleBasicToneGate(param.args[8], "draw p()");
                }
                // r468 master comparison probe (before the VIDEO_LUT gate so it works by
                // default): red table into renderer.s for master live video frames.
                if (prop("persist.omnicam.master.videochannel").contains("s")
                    && GrPreviewStreamRestore.inMasterMode()
                    && param.thisObject != null) {
                    try {
                        syncTexture(param.thisObject,
                            local.jiege.hook.xpan.XpanLiveFeature.redProbeTable(), 64);
                        XposedHelpers.setBooleanField(param.thisObject, "p", true);
                        reportOnce("master video probe: red table synced into renderer.s");
                    } catch (Throwable error) {
                        Log.e(TAG, "master video probe failed", error);
                    }
                    return;
                }
                if (!VIDEO_LUT) return;
                if (!isTargetMode()) return;
                Object renderer = param.thisObject;
                Object lutParams = param.args.length > 8 ? param.args[8] : null;
                if (drawLogs < DRAW_LOG_LIMIT) {
                    drawLogs++;
                    Log.i(TAG, "draw p() seen " + drawLogs
                        + ": args=" + param.args.length
                        + " renderer=" + (renderer == null ? "null"
                            : renderer.getClass().getName())
                        + " renderer.p=" + flag(renderer, "p")
                        + " renderer.q=" + flag(renderer, "q")
                        + " lutParams=" + (lutParams == null ? "null"
                            : lutParams.getClass().getName())
                        + " lutParams.g=" + flag(lutParams, "g")
                        + " renderer.s=" + describeTexture(field(renderer, "s")));
                }
                    if (param.args.length != 10 || renderer == null || lutParams == null) return;
                    try {
                    if (!XposedHelpers.getBooleanField(renderer, "p")) return;
                    Object existing = field(renderer, "s");
                    if (existing == null) return;
                    ManagerTable table = preferredTable(managerHolder);
                    if (table == null) {
                        reportOnce("draw gate: no usable table");
                        return;
                    }
                    syncTexture(renderer, table.data, table.size);
                    if (!XposedHelpers.getBooleanField(lutParams, "g")) {
                        XposedHelpers.setBooleanField(lutParams, "g", true);
                        reportOnce("basic tone switched on: draw p() g false -> true"
                            + ", size=" + table.size + ", table=" + describe(sample(table.data)));
                    }
                } catch (Throwable error) {
                    reportOnce("draw gate: p() sync failed: " + error);
                }
            }
        });

        Log.i(TAG, "master live gate restore installed (post-GR lutParams.g -> false)"
            + "; texture sync " + (VIDEO_LUT
                ? "opt-in via persist.omnicam.aura.livetable=on"
                : "off by default"));
    }

    private static final class ManagerTable {
        final float[] data;
        final int size;

        ManagerTable(float[] data, int size) {
            this.data = data;
            this.size = size;
        }
    }

    private static ManagerTable readManagerTable(Class<?> managerHolder) {
        try {
            Object manager = XposedHelpers.getStaticObjectField(managerHolder, "a");
            if (manager == null) return null;
            Object lock = XposedHelpers.getObjectField(manager, "a");
            float[] data;
            int size;
            synchronized (lock == null ? manager : lock) {
                data = (float[]) XposedHelpers.getObjectField(manager, "c");
                size = XposedHelpers.getIntField(manager, "t");
            }
            return isValidTable(data, size) ? new ManagerTable(data, size) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The table the recorded frames should be graded with: the cover's baked table when one is
     * readable, the manager's lattice otherwise.
     */
    private static ManagerTable preferredTable(Class<?> managerHolder) {
        ManagerTable manager = readManagerTable(managerHolder);
        if (manager == null) return null;
        float[] cover = liveTable(manager.size);
        return cover != null ? new ManagerTable(cover, manager.size) : manager;
    }

    private static void syncBeforeFrame(
        Object pipeline,
        Class<?> pipelineClass,
        Class<?> liveRenderer,
        Class<?> managerHolder) throws Throwable {
        if (!isMasterLivePipeline(pipeline, pipelineClass, liveRenderer)) {
            reportOnce("pipeline gate: not a type-7 live renderer");
            return;
        }

        Object params = XposedHelpers.getObjectField(pipeline, "o0");

        ManagerTable preferred = preferredTable(managerHolder);
        if (preferred == null) {
            reportOnce("manager gate: no usable table");
            return;
        }
        final int size = preferred.size;
        final float[] table = preferred.data;
        // Offline evidence beats inference: the live picture samples this array, so whether it is a
        // sane BasicTone lattice has to be read off the numbers, not guessed from a hash.
        dumpOnce(table, size);

        /*
         * LutParameters.g is stock's own "apply BasicTone on this frame" switch -- the flag
         * ch.g.p() reads before binding the LUT sampler (XpanLiveFeature forces it for XPAN the
         * same way). Master's live-photo warp renderer runs with it cleared, so the recorded
         * frames never sample the table at all: every upload of ours was invisible because the
         * shader was skipped, which is why the video stayed raw while the cover was graded.
         *
         * It is only safe to raise once a texture exists: r410 flipped it while renderer.s was
         * still null (the async LUT task builds it a moment later) and the capture lost its video
         * track entirely (mCaptureTimeRanges empty, 3.9 MB still instead of a live photo).
         */
        Object renderer = XposedHelpers.getObjectField(pipeline, "w");
        Object currentTexture = XposedHelpers.getObjectField(renderer, "s");
        if (currentTexture == null) {
            reportOnce("texture gate: renderer.s null");
            return;
        }
        if (frameLogs < FRAME_LOG_LIMIT) {
            frameLogs++;
            Log.i(TAG, "master live frame " + frameLogs
                + ": o0.g=" + flag(params, "g") + " renderer.p=" + flag(renderer, "p")
                + " renderer.q=" + flag(renderer, "q")
                + " texture=" + describeTexture(currentTexture)
                + " table=" + describe(sample(table)));
        }
        syncTexture(renderer, table, size);
        if (params != null) {
            try {
                if (!XposedHelpers.getBooleanField(params, "g")) {
                    XposedHelpers.setBooleanField(params, "g", true);
                    reportOnce("basic tone switched on: o0.g false -> true"
                        + " (texture " + describeTexture(currentTexture) + ")");
                }
            } catch (Throwable error) {
                reportOnce("params gate: o0.g unreadable " + error);
            }
        }
    }

    /*
     * Deliberate colour calibration for the live video track.
     *
     * The video is graded by the VEP branch of the same look and reads warmer and more saturated
     * than the still cover on every build we tried, r232 included -- so there is no regression to
     * find, and the only way to bring the two together is to grade the video side on purpose.
     * Gains are read from properties so a value can be dialled in from adb without a rebuild:
     *   persist.omnicam.aura.live.gainr / gaing / gainb  (default 1.0, multiply that channel)
     *   persist.omnicam.aura.live.sat                   (default 1.0, 1.0 = untouched)
     * Everything defaults to identity, so a phone without the properties behaves as before.
     */
    private static float gainR = 1f;
    private static float gainG = 1f;
    private static float gainB = 1f;
    private static float gainSat = 1f;
    private static long gainReadAt;
    private static float[] gainSource;
    private static float[] gainResult;

    private static synchronized float[] graded(float[] table, int size) {
        long now = System.currentTimeMillis();
        if (now - gainReadAt > 1500L) {
            gainReadAt = now;
            gainR = number(prop("persist.omnicam.aura.live.gainr"), 1f);
            gainG = number(prop("persist.omnicam.aura.live.gaing"), 1f);
            gainB = number(prop("persist.omnicam.aura.live.gainb"), 1f);
            gainSat = number(prop("persist.omnicam.aura.live.sat"), 1f);
        }
        if (gainR == 1f && gainG == 1f && gainB == 1f && gainSat == 1f) return table;
        if (gainResult != null && gainSource == table) return gainResult;
        float[] out = new float[table.length];
        for (int i = 0; i + 2 < table.length; i += 3) {
            float r = table[i];
            float g = table[i + 1];
            float b = table[i + 2];
            if (gainSat != 1f) {
                float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = luma + (r - luma) * gainSat;
                g = luma + (g - luma) * gainSat;
                b = luma + (b - luma) * gainSat;
            }
            out[i] = clamp(r * gainR);
            out[i + 1] = clamp(g * gainG);
            out[i + 2] = clamp(b * gainB);
        }
        gainSource = table;
        gainResult = out;
        reportOnce("video colour correction: r=" + gainR + " g=" + gainG + " b=" + gainB
            + " sat=" + gainSat + " size=" + size);
        return out;
    }

    private static float clamp(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private static float number(String raw, float fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    /**
     * Replaces {@code renderer.s} with a BasicTone texture built from {@code table}, unless that
     * exact content is already what was last uploaded to this texture instance.
     */
    private static void syncTexture(Object renderer, float[] table, int size) throws Throwable {
        Object currentTexture = XposedHelpers.getObjectField(renderer, "s");
        if (currentTexture == null) {
            reportOnce("texture gate: renderer.s null");
            return;
        }
        Object currentTable = XposedHelpers.getObjectField(currentTexture, "A");
        float[] upload = graded(table, size);
        int[] wanted = sample(upload);
        int texture = System.identityHashCode(currentTexture);
        Uploaded last;
        synchronized (uploadedTables) {
            last = uploadedTables.get(renderer);
        }
        if (last != null && last.texture == texture && java.util.Arrays.equals(wanted, last.sample)) {
            reportOnce("already in sync: last upload sample=" + describe(wanted));
            return;
        }
        if (uploadAttempts >= MAX_UPLOAD_ATTEMPTS) return;

        Method replace = findTextureReplaceMethod(renderer.getClass(), currentTexture.getClass());
        if (replace == null) {
            throw new NoSuchMethodException(
                "GLRenderer.g(texture, int, float[]) on " + renderer.getClass().getName());
        }

        Object replacement;
        try {
            replacement = replace.invoke(null, currentTexture, size, upload);
        } catch (java.lang.reflect.InvocationTargetException error) {
            uploadAttempts++;
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        }
        if (replacement == null) {
            uploadAttempts++;
            throw new IllegalStateException("GLRenderer.g returned null");
        }
        uploadAttempts = 0;

        XposedHelpers.setObjectField(renderer, "s", replacement);
        synchronized (uploadedTables) {
            uploadedTables.put(renderer, new Uploaded(System.identityHashCode(replacement), wanted));
        }
        Log.i(TAG, "master live BasicTone texture synced"
            + ", size=" + size
            + ", source=manager"
            + ", old=" + describeTexture(currentTexture) + " sample=" + describe(sample(currentTable))
            + ", new=" + describeTexture(replacement) + " sample=" + describe(wanted)
            + ", shared-array=" + (currentTable == table)
            + ", thread=" + Thread.currentThread().getName());
    }

    /** Fingerprint of whatever the texture object currently references; diagnostics only. */
    private static int[] sample(Object table) {
        return table instanceof float[] ? sample((float[]) table) : null;
    }

    /**
     * Coarse content fingerprint of a table. Sampling avoids hashing 786k floats per frame while
     * still catching the in-place rewrites the native side performs on {@code manager.c}.
     */
    private static int[] sample(float[] table) {
        if (table == null) return null;
        final int stride = 512;
        int count = Math.max(1, table.length / stride);
        int[] out = new int[count + 1];
        out[0] = table.length;
        for (int i = 0; i < count; i++) {
            out[i + 1] = Float.floatToIntBits(table[i * stride]);
        }
        return out;
    }

    private static String describe(int[] sample) {
        return sample == null ? "none" : Integer.toHexString(java.util.Arrays.hashCode(sample));
    }

    private static void reportOnce(String reason) {
        if (reported.add(reason)) Log.i(TAG, "live texture sync skipped: " + reason);
    }

    private static boolean isTargetMode() {
        return GrPreviewStreamRestore.hasLeftGr()
            && GrPreviewStreamRestore.inMasterMode()
            && !GrState.isGrMode();
    }

    private static boolean isMasterLivePipeline(
        Object pipeline, Class<?> pipelineClass, Class<?> liveRenderer) {
        if (pipeline == null || !pipelineClass.isInstance(pipeline)) return false;
        try {
            if (XposedHelpers.getIntField(pipeline, "n0") != MASTER_LIVE_LUT_TYPE) return false;
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            return renderer != null
                && liveRenderer.isInstance(renderer)
                && XposedHelpers.getBooleanField(renderer, "o");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isValidTable(float[] table, int size) {
        long expected = (long) size * size * size * 3L;
        return size > 1 && table != null && table.length == expected;
    }

    /**
     * The real GLRenderer base is supplied by the camera framework and cannot be loaded reliably
     * by name: one app DEX also contains an unrelated R8-generated class with the same descriptor.
     * Walk the renderer's actual inheritance chain and identify the stock static replacement method
     * from its signature instead.
     */
    private static Method findTextureReplaceMethod(
        Class<?> rendererClass, Class<?> textureClass) {
        for (Class<?> type = rendererClass;
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("g")
                    || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 3
                    || !parameterTypes[0].isAssignableFrom(textureClass)
                    || parameterTypes[1] != int.class
                    || parameterTypes[2] != float[].class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // Xposed runs in the app process; accessibility normally remains available.
                }
                return method;
            }
        }
        return null;
    }

    private static String describeTexture(Object texture) {
        if (texture == null) return "null";
        try {
            Object raw = XposedHelpers.getObjectField(texture, "A");
            int length = raw instanceof float[] ? ((float[]) raw).length : -1;
            return texture.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(texture))
                + "{size=" + XposedHelpers.getIntField(texture, "b")
                + ",length=" + length + "}";
        } catch (Throwable error) {
            return texture.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(texture));
        }
    }
}
