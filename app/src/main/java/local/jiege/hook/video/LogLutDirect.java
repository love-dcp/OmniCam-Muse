package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

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
 * <p>Those two tests only covered feature[8] and the public LUT loader. A later dex pass found the
 * separate feature[3] strategy switch described above: the stock X8U answers 0 and selects
 * {@code StraightOutOfTone}, whose inherited b() is a stub; feature[3] == 1 selects the shipped
 * {@code MasterToneLutByHal} implementation. This class selects that strategy and also makes
 * {@code vk()} agree with the Log format selected by the UI. Y7 buildSession then sends the
 * direct-output mode and custom LUT name through the stock HAL interface.
 *
 * <p>Measured on PKJ110 at 1080p30 Log with the custom {@code ref} LUT: direct on produced
 * {@code com.oplus.log.video.mode=2}; direct off produced 0. A 3.6s A/B pair differed from
 * saturation 27.8 to 21.4 and mean RGB (104.6, 98.3, 76.8) to (104.6, 101.3, 83.1), so the LUT is
 * in the encoded file rather than the viewfinder only.
 */
final class LogLutDirect {
    private static final String TAG = "LogLut";

    /** The name the analytics builder and the feature table use for "custom LUT". */
    private static final String FUNC_CUSTOM_LUT = "func_master_video_log_custom_lut";
    /** The configuration row the custom-LUT slot actually reads. */
    private static final String CFG_HDR_LUT = "com.oplus.camera.preview.hdr.transform.lut.video.support";
    /**
     * MasterVideoDecision.c(3) selects the Log tone/LUT implementation. X8U's stock feature list
     * only has index 0, so this falls through to {@code StraightOutOfTone}; that implementation
     * inherits a stub {@code b(DecisionParam)} which always reports Log LUT mode 0 to the HAL.
     * The build also contains {@code MasterToneLutByHal}, whose b() reports 2 when Log recording
     * and the direct switch are both on. The camera normally turns the direct request (2) into a
     * limited request (1) at 120fps or 8K; Vj is patched below for 120fps.
     */
    private static final int HAL_DIRECT_FEATURE = 3;
    private static final String FORCE_120 = "persist.omnicam.loglut.120";

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
    private static volatile boolean reportedStrategy;
    private static volatile int reportedDirectMode = -1;
    private static volatile boolean reported120;
    private static volatile boolean strategyInstalled;
    private static volatile int queries;
    /** Table indexes already reported, so the log stays one line per index. */
    private static final java.util.Set<Integer> loadedIndexes =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private LogLutDirect() {}

    static void install(ClassLoader classLoader) {
        relabelUnsupportedHint();
        enableHalDirectStrategy(classLoader);
        answerGetSupportFunction(classLoader, MODE_PRO_VIDEO);
        answerGetSupportFunction(classLoader, MODE_ALT);
        answerGetSupportFunction(classLoader, MODE_BASE);
        observeConfig(classLoader);
        observeLutLoader(classLoader);
    }

    /** The stock label still names 120fps even though Vj now enables it below. */
    private static void relabelUnsupportedHint() {
        if (property(FORCE_120, 1) == 0) return;
        try {
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setText",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
                            String text = param.args[0].toString();
                            if (!text.contains("120fps") || !text.contains("8K")) return;
                            param.args[0] = text.replaceAll("(?i)\\s*120fps\\s*(or|或|/)\\s*", "");
                        }
                    });
        } catch (Throwable error) {
            Log.e(TAG, "unsupported hint relabel failed", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.LOG_LUT);
    }

    /**
     * Select the stock {@code MasterToneLutByHal} strategy instead of {@code StraightOutOfTone}.
     *
     * <p>The strategy instance is chosen once in {@code MasterVideoDecision.a.<clinit>}. Hooking
     * c() makes the normal constructor pick the HAL strategy. Do not touch that singleton here:
     * forcing its initialization during ModuleEntry install blocks the camera UI on a black screen.
     */
    private static void enableHalDirectStrategy(final ClassLoader classLoader) {
        try {
            Class<?> decision = XposedHelpers.findClass("o9.h", classLoader);
            XposedBridge.hookAllMethods(decision, "c", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)
                            || (Integer) param.args[0] != HAL_DIRECT_FEATURE) {
                        return;
                    }
                    Integer stock = param.getResult() instanceof Integer
                            ? (Integer) param.getResult() : 0;
                    if (stock != 1) param.setResult(1);
                    if (!reportedStrategy) {
                        reportedStrategy = true;
                        Log.i(TAG, "Log LUT strategy feature[3] answered 1 (stock " + stock + ")");
                    }
                }
            });

            /*
             * The singleton may already have been initialized before c() was hooked. Replace it
             * only after the Pro Video mode object exists: doing this during ModuleEntry install
             * forces too much camera initialization early and leaves the app on a black screen.
             */
            Class<?> proVideo = XposedHelpers.findClass(MODE_PRO_VIDEO, classLoader);
            XposedBridge.hookAllMethods(proVideo, "Vj", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || property(FORCE_120, 1) == 0) return;
                    if (!(param.getResult() instanceof Integer) || (Integer) param.getResult() != 1) return;
                    try {
                        Object is120 = XposedHelpers.callMethod(param.thisObject, "y");
                        if (Boolean.TRUE.equals(is120)) {
                            param.setResult(2);
                            if (!reported120) {
                                reported120 = true;
                                Log.i(TAG, "120fps direct mode: promoted 1 -> 2");
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "120fps direct decision unavailable", error);
                    }
                }
            });
            XposedBridge.hookAllConstructors(proVideo, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (enabled()) installHalStrategy(classLoader);
                }
            });
            XposedBridge.hookAllMethods(proVideo, "vk", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    String format = logFormat(classLoader);
                    if ("log".equals(format)) param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.hookAllMethods(proVideo, "Y7", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !strategyInstalled) return;
                    try {
                        Object decision = XposedHelpers.callMethod(param.thisObject, "Il");
                        Object strategy = XposedHelpers.getObjectField(
                                XposedHelpers.getStaticObjectField(
                                        XposedHelpers.findClass("o9.h$a", classLoader), "a"), "c");
                        int mode = (Integer) XposedHelpers.callMethod(strategy, "b", decision);
                        Log.i(TAG, "HAL decision inputs: lut="
                                + XposedHelpers.getBooleanField(decision, "a")
                                + " hdr=" + XposedHelpers.getBooleanField(decision, "b")
                                + " highRes=" + XposedHelpers.getBooleanField(decision, "c")
                                + " logKind=" + XposedHelpers.getIntField(decision, "d")
                                + " direct=" + XposedHelpers.getIntField(decision, "e")
                                + " format=" + logFormat(classLoader)
                                + " -> mode=" + mode);
                    } catch (Throwable error) {
                        Log.e(TAG, "HAL decision probe failed", error);
                    }
                }
            });

            XposedBridge.hookAllMethods(XposedHelpers.findClass("o9.f", classLoader), "b",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1 || !(param.getResult() instanceof Integer)) return;
                    int mode = (Integer) param.getResult();
                    if (mode <= 0 || mode == reportedDirectMode) return;
                    reportedDirectMode = mode;
                    Log.i(TAG, "HAL Log LUT mode " + mode);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "HAL Log LUT strategy unavailable", error);
        }
    }

    private static int property(String name, int fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) type.getMethod("get", String.class).invoke(null, name);
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String logFormat(ClassLoader classLoader) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("n9.e", classLoader), "K0");
            return String.valueOf(XposedHelpers.callMethod(manager, "b", key, "sdr"));
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static synchronized void installHalStrategy(ClassLoader classLoader) {
        if (strategyInstalled) return;
        try {
            Object singleton = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("o9.h$a", classLoader), "a");
            Object current = XposedHelpers.getObjectField(singleton, "c");
            // R8 removes the stateless strategy's constructor and the stock clinit allocates the
            // object directly. allocateInstance matches that shape and does not need a ctor.
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object hal = unsafeType.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, XposedHelpers.findClass("o9.f", classLoader));
            XposedHelpers.setObjectField(singleton, "c", hal);
            strategyInstalled = true;
            Log.i(TAG, "Log LUT decision strategy: "
                    + XposedHelpers.callMethod(current, "getName") + " -> "
                    + XposedHelpers.callMethod(hal, "getName"));
        } catch (Throwable error) {
            Log.e(TAG, "HAL Log LUT strategy replacement unavailable", error);
        }
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
                    if (!enabled()) return;
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
