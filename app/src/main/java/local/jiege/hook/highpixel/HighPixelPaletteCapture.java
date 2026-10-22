package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Writes the palette dial's tone/saturation into the capture params of a 哈苏超清 shot.
 *
 * <p>The camera fills a 50 MP request through {@code FilterPresenter.U1(EventMessageCaptureRequestStage)}
 * (obfuscated {@code bg.v}), and that method is the only place that writes the palette keys the APS
 * stages read: {@code KEY_MASTER_MODE_CONTRAST} / {@code KEY_MASTER_MODE_SATURATION} plus
 * {@code KEY_PALETTE_CAPTURE_ENABLE}. It takes those values from the <b>filter's</b> recipe
 * ({@code bg.z.b(filter)} / {@code bg.c0.b(filter)}) and only while its own {@code R0}/{@code S0}
 * holders are alive - so on this mode, where the palette has no presenter of its own, the dial's
 * values never reach the request and the tone stage has nothing to apply.
 *
 * <p>The hook lets the camera do its work and then overwrites the three keys with the dial's values
 * (the same ones the preview is drawing with). Switch: {@code persist.omnicam.aura.hppalcapture=0}.
 */
final class HighPixelPaletteCapture {
    private static final String TAG = "HighPixelPalette";
    private static final String PRESENTER = "bg.v";
    private static final String PARAMETER = "com.oplus.ocs.camera.CameraParameter";
    private static final java.util.Set<String> seen =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private HighPixelPaletteCapture() {}

    static void install(ClassLoader loader) {
        try {
            if ("0".equals(prop())) return;
            Class<?> presenter = XposedHelpers.findClass(PRESENTER, loader);
            XposedBridge.hookAllMethods(presenter, "U1", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        stamp(loader, param.args == null || param.args.length == 0 ? null : param.args[0]);
                    } catch (Throwable error) {
                        if (seen.add("stamp")) Log.i(TAG, "capture stamp skipped: " + error);
                    }
                }
            });
            Log.i(TAG, "palette capture stamp installed");
        } catch (Throwable error) {
            Log.i(TAG, "palette capture stamp unavailable: " + error);
        }
    }

    private static void stamp(ClassLoader loader, Object message) {
        if (message == null) return;
        int[] wv = HighPixelFilters.paletteValues();
        if (wv == null || (wv[0] == 0 && wv[1] == 0)) return;
        Object holder = XposedHelpers.getObjectField(message, "D");
        if (holder == null) return;
        Class<?> parameters = XposedHelpers.findClass(PARAMETER, loader);
        Object toneKey = XposedHelpers.getStaticObjectField(parameters, "KEY_MASTER_MODE_CONTRAST");
        Object saturationKey = XposedHelpers.getStaticObjectField(parameters, "KEY_MASTER_MODE_SATURATION");
        Object paletteKey = XposedHelpers.getStaticObjectField(parameters, "KEY_PALETTE_CAPTURE_ENABLE");
        XposedHelpers.callMethod(holder, "e", toneKey, Integer.valueOf(wv[0]));
        XposedHelpers.callMethod(holder, "e", saturationKey, Integer.valueOf(wv[1]));
        // KEY_PALETTE_CAPTURE_ENABLE is deliberately left alone: with it set, the camera takes this
        // mode's shot down a palette-capable pipeline that returns 3244x5132 instead of the 6484x10256
        // the plain request produces (measured 2026-10-23 07:08, chain unchanged between the two).
        if ("1".equals(prop("persist.omnicam.aura.hppalenable", ""))) {
            XposedHelpers.callMethod(holder, "e", paletteKey, Boolean.TRUE);
        }
        if (seen.add("written:" + wv[0] + ":" + wv[1])) {
            Log.i(TAG, "palette on the capture request: tone=" + wv[0] + " saturation=" + wv[1]);
        }
    }

    private static String prop() {
        return prop("persist.omnicam.aura.hppalcapture", "");
    }

    private static String prop(String name, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, name, fallback));
        } catch (Throwable error) {
            return fallback;
        }
    }
}
