package local.jiege.hook.photozoom;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Starts each portrait page at 1x.
 *
 * <p>PKJ110 selects a portrait default from several configuration and saved-state branches. The
 * encrypted default key can be answered as 1x, but the first zoom commit still follows one of the
 * later branches. The portrait mode's {@code onStart} therefore arms a one-shot correction and the
 * zoom presenter's first commit is redirected to 1x. The flag is consumed immediately, so a later
 * manual 3x selection is not touched.
 */
public final class PortraitDefaultZoom {
    private static final String TAG = "PortraitDefaultZoom";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    private static final String PORTRAIT_MODE = "fm.o1";
    private static final String ZOOM_PRESENTER = "il.h0";
    private static final String DEFAULT_ZOOM_KEY = "com.oplus.portrait.zoom.value.default";
    private static final float DEFAULT_ZOOM = 1.0f;
    private static final long ARM_WINDOW_NANOS = 5_000_000_000L;

    private static volatile boolean reported;
    private static final java.util.concurrent.atomic.AtomicLong ARMED_UNTIL =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean MODE_PROBE_FAILED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private PortraitDefaultZoom() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "portrait initial zoom", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) {
        installConfigDefault(classLoader);
        installOneShotInitialZoom(classLoader);
        Log.i(TAG, "portrait initial 1x control installed");
    }

    private static void installConfigDefault(ClassLoader classLoader) {
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                if (!DEFAULT_ZOOM_KEY.equals(param.args[0])) return;

                float stock = param.getResult() instanceof Float
                        ? (Float) param.getResult()
                        : Float.NaN;
                param.setResult(DEFAULT_ZOOM);
                if (!reported) {
                    reported = true;
                    Log.i(TAG, "portrait config default " + DEFAULT_ZOOM
                            + " applied (stock " + stock + ", key=" + DEFAULT_ZOOM_KEY + ")");
                }
            }
        };

        int hooked = 0;
        for (java.lang.reflect.Method method : config.getDeclaredMethods()) {
            if (!"j".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != String.class) continue;
            if (method.getReturnType() != float.class) continue;
            XposedBridge.hookMethod(method, hook);
            hooked++;
        }
        if (hooked == 0) {
            throw new IllegalStateException("CameraConfig float getter not found");
        }
        Log.i(TAG, "portrait config installed on " + hooked + " float getter(s)");
    }

    private static void installOneShotInitialZoom(ClassLoader classLoader) {
        Class<?> portraitMode = XposedHelpers.findClass(PORTRAIT_MODE, classLoader);
        Class<?> zoomPresenter = XposedHelpers.findClass(ZOOM_PRESENTER, classLoader);

        XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(portraitMode, "onStart"),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        ARMED_UNTIL.set(System.nanoTime() + ARM_WINDOW_NANOS);
                        Log.i(TAG, "portrait initial 1x armed");
                    }
                });

        XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(
                        zoomPresenter, "y4", float.class, boolean.class),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 2
                                || !(param.args[0] instanceof Float)
                                || !isPortrait(param.thisObject)) {
                            return;
                        }

                        long deadline = ARMED_UNTIL.get();
                        long now = System.nanoTime();
                        if (deadline == 0L || now > deadline) {
                            if (deadline != 0L) ARMED_UNTIL.compareAndSet(deadline, 0L);
                            return;
                        }
                        if (!ARMED_UNTIL.compareAndSet(deadline, 0L)) return;

                        float stock = (Float) param.args[0];
                        param.args[0] = DEFAULT_ZOOM;
                        Log.i(TAG, "portrait initial 1x request " + stock
                                + " -> " + DEFAULT_ZOOM);
                    }
                });
    }

    private static boolean isPortrait(Object zoomPresenter) {
        try {
            return "portrait".equals(XposedHelpers.getObjectField(zoomPresenter, "q"));
        } catch (Throwable throwable) {
            if (MODE_PROBE_FAILED.compareAndSet(false, true)) {
                Log.i(TAG, "portrait mode probe failed " + throwable);
            }
            return false;
        }
    }
}
