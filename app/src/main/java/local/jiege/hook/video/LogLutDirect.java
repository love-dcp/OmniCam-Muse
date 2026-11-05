package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Pro video: try to make the stock "bake the LUT into the recording" switch actually do something.
 *
 * <p>{@code docs/LOG_LUT.md} measured that the switch
 * ({@code pref_log_video_record_effect_switch}, folded into
 * {@code pref_log_video_monitor_state}) only changes what the viewfinder shows: five recordings,
 * two with the switch on and three with it off, came out at the same saturation to within codec
 * noise. The state is read by the panel, the analytics builder and {@code restoreAppConfig}, never
 * written into a capture request.
 *
 * <p>The X9U is reported to bake it. Comparing the two builds
 * ({@code work/x9u_cam/}, X9U OplusCamera 176,098,454 B against the PKJ110 one) the
 * {@code feature.logvideo} package is identical, {@code libCombineLut.so} has the same md5, and the
 * only platform-facing hook is the feature table: {@code rm.e.getSupportFunction(String)} maps a
 * name to an index and reads it through {@code o9.h.c(index, default)}. One of those names is
 * {@code func_master_video_log_custom_lut}.
 *
 * <p>So the first thing worth ruling out is that opening that one slot is enough. This class answers
 * {@code true} for it and logs what happened, plus what the two configuration keys behind it say:
 *
 * <pre>
 *   func_master_video_log_custom_lut  ->  CameraConfig.d(
 *       "com.oplus.camera.preview.hdr.transform.lut.video.support", false, false)
 * </pre>
 *
 * <p>Note what the static side already says: the name appears exactly twice in the whole app --
 * once in the feature table above, and once in {@code rm.e.jk(EventRecordDataBuilder)}, which only
 * fills the analytics fields {@code setLutType} / {@code setPreviewLut}. If the log below shows the
 * query only ever arriving from that builder, then no recording path consults it and the switch is
 * decorative on this build regardless of what we answer.
 *
 * <h2>Measured (r153 / r154, PKJ110)</h2>
 *
 * <p>Both halves of the answer turned out to be "no", and the measurements are in
 * {@code docs/LOG_LUT.md} §6.1:
 *
 * <ul>
 *   <li>Answering the slot true changes nothing. Two clips that differ only in the switch came out
 *       at the same saturation (39.8 vs 40.1), pixel diff 3.61, identical luma percentiles
 *       {@code 15/96/201}. The query is only ever made by {@code rm.e.jk}, i.e. analytics.</li>
 *   <li>The configuration row it reads,
 *       {@code com.oplus.camera.preview.hdr.transform.lut.video.support}, is {@code 1} in the
 *       stock {@code oplus_camera_config} -- it was never the missing piece.</li>
 *   <li>Shipping {@code filters_lut/log_video_sdr_3dlut.cube} (index 9 of the loader's path
 *       table) changes nothing either: {@code Lut3dDataManager.i()} never asks for index 9, 10 or
 *       15, only index 1 at startup. The preview and the encoder both bypass the table.</li>
 * </ul>
 *
 * <p>So the hook stays as a probe, not as a fix: on this platform nothing in Java reaches the
 * encoder's LUT stage, and the difference from the X9U is in the HAL. Keeping it costs one string
 * compare per feature query and it reports the state if a future firmware changes the picture.
 */
final class LogLutDirect {
    private static final String TAG = "LogLut";

    /** The name the analytics builder and the feature table use for "custom LUT". */
    private static final String FUNC_CUSTOM_LUT = "func_master_video_log_custom_lut";
    /** The configuration row the custom-LUT slot actually reads. */
    private static final String CFG_HDR_LUT = "com.oplus.camera.preview.hdr.transform.lut.video.support";

    /** Pro video module. Declares its own final getSupportFunction, so BaseMode alone is not enough. */
    private static final String MODE_PRO_VIDEO = "rm.e";
    /** Another module that overrides the same table. */
    private static final String MODE_ALT = "dm.w2";
    /** The shared implementation every other module falls back to. */
    private static final String MODE_BASE = "com.oplus.camera.module.BaseMode";
    /** The configuration reader. */
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    /** The 3D LUT loader: index -> file path, file path -> float[] table. */
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";

    private static volatile boolean reportedQuery;
    private static volatile boolean reportedConfig;
    private static volatile int queries;
    /** Table indexes already reported, so the log stays one line per index. */
    private static final java.util.Set<Integer> loadedIndexes =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private LogLutDirect() {}

    static void install(ClassLoader classLoader) {
        answerGetSupportFunction(classLoader, MODE_PRO_VIDEO);
        answerGetSupportFunction(classLoader, MODE_ALT);
        answerGetSupportFunction(classLoader, MODE_BASE);
        observeConfig(classLoader);
        observeLutLoader(classLoader);
    }

    /**
     * Watch the 3D LUT loader, which is where the recording side would have to pick the table up.
     *
     * <p>{@code Lut3dDataManager} keeps a path table filled in {@code d()} (index 9 =
     * {@code filters_lut/log_video_sdr_3dlut.cube}, 10 = the HDR one) and a name table that
     * {@code n(String)} writes index 15 into when the panel changes the selection.
     * {@code i(index, hdr)} turns an index into floats through
     * {@code nativeLoadBinaryLutFile(path, hdr)} and returns null when the path is missing.
     * Observing every index (not just 9 and 10) tells us which one the preview uses and which one,
     * if any, the encoder asks for.
     */
    private static void observeLutLoader(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(LUT_MANAGER, classLoader);
            XposedBridge.hookAllMethods(manager, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) return;
                    int index = (Integer) param.args[0];
                    if (!loadedIndexes.add(index)) return;
                    Object table = param.getResult();
                    int size = (table instanceof float[]) ? ((float[]) table).length : -1;
                    Log.i(TAG, "lut table " + index + " loaded: "
                            + (size < 0 ? "null (no file)" : size + " floats"));
                }
            });
            XposedBridge.hookAllMethods(manager, "n", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1) return;
                    Log.i(TAG, "lut selection set to \"" + param.args[0] + "\"");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "lut loader unavailable", error);
        }
    }

    /**
     * Answer the custom-LUT slot the way the X9U does, and say who asked. The class name in the log
     * is the whole point of the exercise: it tells us whether this is a feature decision or only the
     * analytics builder.
     */
    private static void answerGetSupportFunction(ClassLoader classLoader, String className) {
        try {
            Class<?> type = XposedHelpers.findClass(className, classLoader);
            XposedBridge.hookAllMethods(type, "getSupportFunction", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || !(param.args[0] instanceof String)) return;
                    if (!FUNC_CUSTOM_LUT.equals(param.args[0])) return;
                    boolean stock = Boolean.TRUE.equals(param.getResult());
                    if (!stock) param.setResult(Boolean.TRUE);
                    queries++;
                    if (!reportedQuery) {
                        reportedQuery = true;
                        Log.i(TAG, "custom lut feature queried by " + className
                                + " (stock " + stock + "), answering true");
                    } else if (queries % 20 == 0) {
                        Log.i(TAG, "custom lut feature queried " + queries + " times so far");
                    }
                }
            });
        } catch (Throwable error) {
            // Not every build ships every module; a miss here is not fatal.
            Log.i(TAG, "no getSupportFunction on " + className + ": " + error);
        }
    }

    /**
     * Answer the configuration row the slot reads, and report what stock said first.
     *
     * <p>Same shape as {@code HighPixelSupport}: this platform's {@code CameraConfig} caches every
     * answered boolean and its vendor-tag fallback is dead, so a row that is missing from
     * {@code /odm/etc/camera/config/oplus_camera_config} can only be turned on at
     * {@code CameraConfig} itself. Both entry points are covered because the photo path asks
     * {@code getConfigBooleanValue} while the feature table asks {@code d(key, false, false)}.
     */
    private static void observeConfig(ClassLoader classLoader) {
        try {
            Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof String)) return;
                    if (!CFG_HDR_LUT.equals(param.args[0])) return;
                    boolean stock = Boolean.TRUE.equals(param.getResult());
                    if (!stock) param.setResult(Boolean.TRUE);
                    if (!reportedConfig) {
                        reportedConfig = true;
                        Log.i(TAG, "config " + CFG_HDR_LUT + " answered true (stock " + stock + ")");
                    }
                }
            };
            XposedBridge.hookAllMethods(config, "d", hook);
            XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hook);
        } catch (Throwable error) {
            Log.e(TAG, "camera config unavailable", error);
        }
    }
}
