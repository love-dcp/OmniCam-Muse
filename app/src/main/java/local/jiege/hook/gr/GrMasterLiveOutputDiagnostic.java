package local.jiege.hook.gr;

import android.app.Activity;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;

/**
 * Read-only diagnostics for the RGBA-to-YUV converter used by live-photo video.
 *
 * <p>The encoder declares BT.2020, HLG and limited range, while one P010 shader path still
 * converts with BT.601 full range. Before changing a shader, record which concrete converter and
 * resource were selected, together with {@code uColorMode}, so direct-master and GR-to-master
 * runs can be compared from their actual output path rather than inferred from mode logs.
 */
final class GrMasterLiveOutputDiagnostic {
    private static final String TAG = "RicohGrPort";
    private static final String CONVERSION_BASE = "ch.e";
    private static final String NV21_CONVERSION = "ch.b";
    private static final String P010_CONVERSION = "ch.c";
    private static final String RAW_RESOURCES =
        "com.oplus.camera.feature.livephoto.R$raw";
    private static final int YUV_FORMAT_NV21 = 17;
    private static final int YUV_FORMAT_NV21_ALTERNATE = 35;
    private static final int YUV_FORMAT_P010 = 54;

    private static final Map<Object, Boolean> loggedCreates = new WeakHashMap<>();
    private static final Map<Object, Boolean> loggedShaders = new WeakHashMap<>();
    private static volatile Map<Integer, String> shaderNames;

    private GrMasterLiveOutputDiagnostic() {}

    static void install(ClassLoader classLoader) throws Throwable {
        Class<?> conversionBase = XposedHelpers.findClass(CONVERSION_BASE, classLoader);
        XposedBridge.hookMethod(
            XposedHelpers.findMethodExact(
                conversionBase,
                "c",
                Activity.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class),
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object converter = param.getResult();
                    if (converter == null || !markOnce(loggedCreates, converter)) return;
                    Log.i(TAG, "live YUV conversion created"
                        + ", class=" + converter.getClass().getName()
                        + ", width=" + param.args[1]
                        + ", height=" + param.args[2]
                        + ", yuvFormat=" + param.args[3]
                        + ", yuvName=" + yuvName((Integer) param.args[3])
                        + ", isFrontCamera=" + param.args[4]
                        + ", reverseUV=" + param.args[5]
                        + ", fieldT=" + boolField(converter, "t")
                        + ", fieldU=" + boolField(converter, "u")
                        + ", uColorMode=" + intField(converter, "v")
                        + ", threw=" + param.hasThrowable());
                }
            });

        hookShaderSelection(
            XposedHelpers.findClass(NV21_CONVERSION, classLoader), classLoader);
        hookShaderSelection(
            XposedHelpers.findClass(P010_CONVERSION, classLoader), classLoader);
        Log.i(TAG, "master live YUV output diagnostic installed");
    }

    private static void hookShaderSelection(
        Class<?> conversion, final ClassLoader classLoader) {
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(conversion, "h"),
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object converter = param.thisObject;
                    if (converter == null || !markOnce(loggedShaders, converter)) return;
                    int resource = param.getResult() instanceof Integer
                        ? (Integer) param.getResult() : -1;
                    Log.i(TAG, "live YUV shader selected"
                        + ", class=" + converter.getClass().getName()
                        + ", resource=" + resource
                        + ", shader=" + shaderName(resource, classLoader)
                        + ", yuvFormat=" + intField(converter, "f")
                        + ", width=" + intField(converter, "r")
                        + ", outputWidth=" + intField(converter, "p")
                        + ", outputHeight=" + intField(converter, "o")
                        + ", reverseUV=" + boolField(converter, "t")
                        + ", fieldU=" + boolField(converter, "u")
                        + ", uColorMode=" + intField(converter, "v"));
                }
            });
    }

    private static boolean markOnce(Map<Object, Boolean> logged, Object value) {
        synchronized (logged) {
            if (logged.containsKey(value)) return false;
            logged.put(value, Boolean.TRUE);
            return true;
        }
    }

    private static String yuvName(int format) {
        if (format == YUV_FORMAT_NV21 || format == YUV_FORMAT_NV21_ALTERNATE) return "NV21";
        if (format == YUV_FORMAT_P010) return "P010";
        return "UNKNOWN";
    }

    private static String shaderName(int resource, ClassLoader classLoader) {
        Map<Integer, String> names = shaderNames;
        if (names == null) {
            names = loadShaderNames(classLoader);
            shaderNames = names;
        }
        String name = names.get(resource);
        return name == null ? "unknown" : name;
    }

    private static Map<Integer, String> loadShaderNames(ClassLoader classLoader) {
        String[] fields = {
            "rgba2p010_frag",
            "rgba2p010_layout_yuv_frag",
            "rgba2nv21_frag",
            "rgba2nv21_frag_oes",
            "rgba2nv21_layout_yuv_frag",
            "rgba2nv21_layout_yuv_frag_oes"
        };
        Map<Integer, String> names = new HashMap<>();
        try {
            Class<?> resources = XposedHelpers.findClass(RAW_RESOURCES, classLoader);
            for (String field : fields) {
                try {
                    names.put(XposedHelpers.getStaticIntField(resources, field), field);
                } catch (Throwable ignored) {
                    // Resource names vary between camera releases; keep the numeric ID visible.
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "live YUV shader resource map unavailable", error);
        }
        return Collections.unmodifiableMap(names);
    }

    private static int intField(Object target, String name) {
        if (target == null) return Integer.MIN_VALUE;
        try {
            return XposedHelpers.getIntField(target, name);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean boolField(Object target, String name) {
        if (target == null) return false;
        try {
            return XposedHelpers.getBooleanField(target, name);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
