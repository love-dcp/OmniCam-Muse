package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import local.jiege.hook.common.Log;

/**
 * Read-only probe for the POP delivery path. On the X8 Ultra every retro capture except the
 * Polaroid (PLD) one comes out black or split into false colour, and the APS log says
 * {@code libHeifEncoderWrapper.so dlopen failed} - a 10-bit buffer is delivered to a consumer that
 * reads it as 8-bit.
 *
 * <p>The class chain lives in {@code /product/framework/com.oplus.camera.unit.sdk.jar}, not in
 * {@code OplusCamera.apk}, which is why it cannot be found by looking at the app:
 * {@code ApsProcessor.onHeicReceived(ApsResult)} only wraps {@code result.getHwBuffer()} in a
 * {@code CameraImage} and hands it to {@code IConsumer.onApsJpegArrived}. Every field a conversion
 * would need - {@code mBufferType}, {@code mCopyBuffer}, {@code mDataSize}, {@code mWidth},
 * {@code mHeight}, {@code mStride}, {@code mScanline}, {@code mbHeifProcessInAps} - belongs to
 * {@code ApsResult}, and {@code mDataSize} is a {@code long}.
 *
 * <p>Both deliveries are dumped so a POP capture can be compared against an ordinary JPEG one
 * (which is the shape the conversion has to produce). Switch: {@code persist.omnicam.aura.popheif}
 * ({@code 0} disables the whole POP 10-bit path).
 */
final class PopHeifProbe {
    private static final String TAG = "PopHeif";
    private static final String PROP = "persist.omnicam.aura.popheif";
    private static final String PROCESSOR = "com.oplus.ocs.camera.consumer.ApsProcessor";
    /** Delivery payload, the class that actually carries the buffer. */
    private static final String RESULT = "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsResult";

    private static final String[] FIELDS = {
        "mBufferType", "mWidth", "mHeight", "mStride", "mScanline", "mDataSize",
        "mbHeifProcessInAps", "mMessageType", "mCaptureMode", "mDeferPictureFormat",
        "mCaptureJobType", "mCopyBuffer", "mHwbuffer", "mImage", "mIdentity", "mFrameId"
    };

    private static int budget = 12;

    private PopHeifProbe() {}

    static boolean enabled() {
        return !"0".equals(readProp(PROP));
    }

    /**
     * Neither channel that normally works was usable here: LSPosed's own log dropped every
     * installer line in this process (the callbacks themselves still fired), and {@code Log.i} can
     * be optimised out. So the probe appends to a file in the camera's own data dir - the same
     * place POP's staged assets live - which is read back over adb.
     */
    private static void say(String message) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(logPath(), true);
            try {
                out.write((System.currentTimeMillis() + " " + message + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // fall through to the module log
        }
        Log.i(TAG, message);
    }

    /**
     * {@code ActivityThread} is a hidden API, so it is reached reflectively. At installer time the
     * Application may not exist yet, and both log channels turned out to be unusable here, so the
     * fallback is the camera's own private directory - already the uid writing this file.
     */
    private static String logPath() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                java.io.File dir = ((android.content.Context) app).getFilesDir();
                if (dir != null) return new java.io.File(dir, "pop_heif.txt").getAbsolutePath();
            }
        } catch (Throwable ignored) {
            // fall through to the fixed path
        }
        return "/data/user/0/com.oplus.camera/pop_heif.txt";
    }

    static void install(ClassLoader classLoader) throws Throwable {
        say("install start enabled=" + enabled());
        if (!enabled()) return;
        final Class<?> processor = XposedHelpers.findClass(PROCESSOR, classLoader);
        say("class " + processor.getName() + " fields=" + processor.getDeclaredFields().length);
        for (Method method : processor.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!(name.contains("heic") || name.contains("jpeg") || name.contains("receive"))) continue;
            say("  method " + method.getName() + "(" + types(method.getParameterTypes()) + ")");
        }
        hook(processor, "onHeicReceived");
        hook(processor, "onJpegReceived");
        say("probe installed");
    }

    private static void hook(Class<?> processor, final String name) {
        XposedBridge.hookAllMethods(processor, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (budget <= 0) return;
                    budget--;
                    Object result = param.args == null || param.args.length == 0 ? null : param.args[0];
                    say(name + " " + describe(result));
                } catch (Throwable error) {
                    Log.e(TAG, "probe", error);
                }
            }
        });
    }

    private static String describe(Object result) {
        if (result == null) return "payload=null";
        Class<?> type = result.getClass();
        StringBuilder sb = new StringBuilder("payload=").append(type.getSimpleName());
        for (String name : FIELDS) {
            Object value;
            try {
                Field field = XposedHelpers.findField(type, name);
                field.setAccessible(true);
                value = field.get(result);
            } catch (Throwable missing) {
                continue;
            }
            sb.append(' ').append(name).append('=').append(summary(value));
        }
        return sb.toString();
    }

    private static String summary(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder sb = new StringBuilder("byte[").append(bytes.length).append(']');
            int head = Math.min(8, bytes.length);
            if (head > 0) {
                sb.append(' ');
                for (int i = 0; i < head; i++) sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        }
        if (value instanceof android.media.Image) {
            android.media.Image image = (android.media.Image) value;
            return "Image(fmt=" + image.getFormat() + "," + image.getWidth() + "x" + image.getHeight() + ")";
        }
        if (value instanceof android.hardware.HardwareBuffer) {
            android.hardware.HardwareBuffer buffer = (android.hardware.HardwareBuffer) value;
            return "HardwareBuffer(fmt=" + buffer.getFormat() + "," + buffer.getWidth() + "x"
                + buffer.getHeight() + ")";
        }
        String text = String.valueOf(value);
        return text.length() > 48 ? text.substring(0, 48) + "..." : text;
    }

    private static String types(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private static String readProp(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, name, ""));
        } catch (Throwable error) {
            return "";
        }
    }
}
