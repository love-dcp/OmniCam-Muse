package local.jiege.hook.aicomposition;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Photo: turn on the build's AI composition (灵感构图 / 实景构图) feature gates.
 *
 * Everything the feature needs is already in the camera: the whole
 * com.oplus.camera.feature.aicomposition package, the entry guide, the inspiration card, the
 * image selector and the "forbid inspiration now" hint. What is missing is the answer of three
 * gates, all of which end up asking the same protobuf feature table:
 *
 *   FeatureConfigureAdapter.getFeatureInfoList(int)  -> Set   add com.oplus.camera.feature.ai_composition
 *   FeatureConfigureAdapter.hasConfigFeature(String) -> true  for realscene / inspiration
 *   CameraConfig.d(String, boolean, boolean)         -> true  for the same two keys
 *
 * Inference is not done in the camera: libaiunit_sdk_core.so is a thin client and the work runs
 * in the system AIUnit service (com.oplus.aiunit), whose model is not present on this build.
 * So the gates are opened here and the outcome is whatever stock does next -- the point of this
 * hook is to find out, not to reimplement the engine.
 */
public final class AiComposition {
    private static final String TAG = "AiComposition";
    private static final String FEATURE_ADAPTER = "com.oplus.ocs.camera.config.FeatureConfigureAdapter";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";

    /** Pseudo feature name the camera's own module list is looking for. */
    private static final String FEATURE = "com.oplus.camera.feature.ai_composition";
    /** Real-scene composition: draw a suggested frame over the viewfinder. */
    private static final String REAL_SCENE = "com.oplus.feature.aicomposition.realscene.support";
    /** Inspiration composition: pick a reference shot and match it. */
    private static final String INSPIRATION = "com.oplus.feature.aicomposition.inspiration.support";

    private static volatile boolean reportedFeature;
    private static volatile String lastQuery;

    private AiComposition() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "ai composition unavailable", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.AI_COMPOSITION);
    }

    private static boolean interesting(Object key) {
        return REAL_SCENE.equals(key) || INSPIRATION.equals(key);
    }

    /**
     * Composition is a photo-mode feature, and the mode is enforced where it is visible -- on the
     * entry icon ({@link CompositionIconShift}) -- rather than on these gates.
     *
     * <p>Answering no here for the other modes was tried and it removed composition from 照片 too:
     * the feature list is built once per process, in whatever mode the camera launched in. The
     * gates therefore keep answering yes for every mode, exactly as they always did.
     */

    private static void install(ClassLoader classLoader) throws Throwable {
        Class<?> adapter = XposedHelpers.findClass(FEATURE_ADAPTER, classLoader);

        // The module list the camera builds for an opmode. It is a Set<String> built from the
        // feature table; this one name is what makes the composition module load at all.
        //
        // This one is deliberately NOT gated by mode. The list is built once per process, when the
        // camera opens whichever mode it happened to launch in, so skipping the name while that
        // mode was 理光 GR / 视频 took composition away from 照片 as well -- the module is simply
        // never loaded, and switching back to 照片 changed nothing ("全部模式构图都不见了").
        // Modes that must not offer composition are handled where the entry is drawn instead:
        // CompositionIconShift hides the icon, and the state gates refuse to run.
        XposedBridge.hookAllMethods(adapter, "getFeatureInfoList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                Object result = param.getResult();
                if (!(result instanceof java.util.Set)) return;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Set<Object> names = (java.util.Set<Object>) result;
                    if (!names.add(FEATURE)) return;
                    if (reportedFeature) return;
                    reportedFeature = true;
                    Log.i(TAG, "composition feature added (opmode " + (param.args.length > 0 ? param.args[0] : "?") + ")");
                } catch (Throwable error) {
                    Log.e(TAG, "feature set is not writable", error);
                }
            }
        });

        XposedBridge.hookAllMethods(adapter, "hasConfigFeature", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                if (param.args.length < 1 || !interesting(param.args[0])) return;
                if (Boolean.TRUE.equals(param.getResult())) return;
                param.setResult(Boolean.TRUE);
                logQuery("hasConfigFeature", param.args[0]);
            }
        });

        Class<?> cameraConfig = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        XposedBridge.hookAllMethods(cameraConfig, "d", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                if (param.args.length < 3 || !interesting(param.args[0])) return;
                if (!(param.args[1] instanceof Boolean) || !(param.args[2] instanceof Boolean)) return;
                // Real-scene support must answer TRUE. vb.c3.k4(int) branches on it, and the two
                // sides are NOT "draw / don't draw" -- they are two different features:
                //
                //   true  -> i4(); G2(); Z2();   real-scene composition (box over the viewfinder)
                //   false -> G2(); j4(false,false)  inspiration recommendation (uploads stills to
                //                                   the cloud and waits for reference shots)
                //
                // Answering false here is what made the panel hop to 灵感推荐 on every open: the
                // upload then came back 3111 and surfaced as "未识别到人像". Keep it true.
                if (REAL_SCENE.equals(param.args[0])
                    && "1".equals(prop("persist.omnicam.comp.k4invert", "0"))) {
                    param.setResult(Boolean.FALSE);
                    return;
                }
                if (Boolean.TRUE.equals(param.getResult())) return;
                param.setResult(Boolean.TRUE);
                logQuery("CameraConfig.d", param.args[0]);
            }
        });

        Log.guard(TAG, "frame probe", () -> CompositionFrameProbe.install(classLoader));
        Log.guard(TAG, "real scene probe", () -> RealsceneProbe.install(classLoader));
        Log.guard(TAG, "icon shift", () -> CompositionIconShift.install(classLoader));

        Log.i(TAG, "ai composition hook installed");
    }

    /** One line per distinct key, so a camera start does not flood logcat. */
    private static synchronized void logQuery(String from, Object key) {
        String line = from + " " + key;
        if (line.equals(lastQuery)) return;
        lastQuery = line;
        Log.i(TAG, "gate opened " + line);
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
