package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Brings the POP fish-eye lens back on PKJ110.
 *
 * <p>The camera ships the whole retro fish-eye path - {@code FUNC_KEY_ID_RETRO_FISH_EYE_LENS},
 * {@code FishEyeTexturePreview}, {@code RetroFishEyeZoomPolicy}, the {@code KEY_FISH_EYE_TYPE_*}
 * request keys and the Meishe {@code CNvFishEyeEdgeBlur} renderer - but the retro page only builds
 * the lens button when the platform answers its support keys true. X8U's config never defines them,
 * so {@code CameraConfig} falls back to false and the entry never appears.
 *
 * <p>Same shape as {@code HighPixelSupport}: answer the keys at {@code CameraConfig} itself, because
 * its answers are cached per key and its vendor-tag fallback is dead on this platform.
 *
 * <p>Disable with {@code setprop persist.omnicam.aura.fisheye 0} (then force-stop the camera). The
 * effect needs the full Meishe renderer (the one with {@code CNvFishEyeEdgeBlur}) bound over
 * {@code /odm/lib64/libmsnativefilter.so}; the stock X8U build has no fish-eye code at all.
 */
public final class PopFisheyeSupport {
    private static final String TAG = "PopFisheye";
    private static final String PROP = "persist.omnicam.aura.fisheye";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";

    /**
     * Keys the retro page asks before it lays the lens button out. The first two are the platform
     * capabilities (missing on X8U, hence false); the last two are the camera's own switches and are
     * answered as well so the request is never gated on a value this port cannot fill in.
     */
    private static final String[] KEYS = {
        "com.oplus.feature.fish.eye.support",
        "com.oplus.fisheye.new.style.support",
        "com.oplus.camera.retro.fisheye.enable",
        "com.oplus.camera.retro.filter.fisheye.enable",
        "com.oplus.support.fish.eye.process.after.filter",
    };

    private static final java.util.Set<String> answeredOnce =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.Set<String> traced =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile boolean on;

    private PopFisheyeSupport() {}

    public static void install(ClassLoader classLoader) {
        Log.guard(TAG, "fish-eye lens", () -> installHooks(classLoader));
    }

    /** True while the module answers the fish-eye keys (the capture hook follows it). */
    public static boolean enabled() {
        return on;
    }

    private static void installHooks(ClassLoader classLoader) throws Throwable {
        if ("0".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + "=0)");
            return;
        }
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                traceKey(param);
                if (!isFishEyeKey(param)) return;
                param.setResult(Boolean.TRUE);
            }
        };
        XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hook);
        XposedBridge.hookAllMethods(config, "d", hook);
        on = true;
        Log.i(TAG, "fish-eye lens: answering " + String.join(", ", KEYS) + " = true");
    }

    /**
     * Logs the capability keys the retro page asks for while the lens button is being laid out, so
     * the next device can see which gate answered false. Only keys that look related are logged.
     */
    private static void traceKey(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) return;
        Object key = param.args[0];
        if (!(key instanceof String)) return;
        String text = (String) key;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("fish") && !lower.contains("lens") && !lower.contains("retro")) return;
        if (traced.add(text)) Log.i(TAG, "asked " + text + " = " + param.getResult());
    }

    private static boolean isFishEyeKey(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) return false;
        Object key = param.args[0];
        if (!(key instanceof String)) return false;
        if (java.util.Arrays.asList(KEYS).indexOf(key) < 0) return false;
        // A vendor tag that is already true keeps its value; answering it again is harmless, but
        // logging once per key is what makes the next device diagnosis readable.
        if (answeredOnce.add(String.valueOf(key))) Log.i(TAG, "answering " + key + " = true");
        return true;
    }

    private static String readProp() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, PROP, "1"));
        } catch (Throwable error) {
            return "1";
        }
    }
}
