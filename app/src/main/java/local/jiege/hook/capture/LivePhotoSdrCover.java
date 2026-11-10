package local.jiege.hook.capture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import local.jiege.hook.common.Log;

/**
 * Keeps a live photo's still cover on the same SDR path as its embedded video.
 *
 * <p>On PKJ110 the photo and Hasselblad high-pixel modes request Ultra HDR for live captures. The
 * gallery renders that cover through its gain map, while the SDR MotionPhoto attachment has no gain
 * map, so the cover appears brighter than the moving picture. Clearing the flag on the capture tag
 * before APS builds its meta item makes the cover SDR without changing ordinary still photos.
 */
public final class LivePhotoSdrCover {
    private static final String TAG = "LivePhotoSdrCover";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String PHOTO_MODE = "common";
    private static final Set<String> CAPTURE_METHODS = Collections.unmodifiableSet(
        new HashSet<String>(Arrays.asList(
            "startCapture",
            "onPictureMetaStart",
            "addPictureImage",
            "addPictureMeta",
            "fillMetaItemForCapture"
        )));

    private static final Set<String> logged = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());

    private LivePhotoSdrCover() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "install", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(
            SDK + "common.util.CameraRequestTag", classLoader);

        int hooked = 0;
        for (java.lang.reflect.Method method : processor.getDeclaredMethods()) {
            if (!CAPTURE_METHODS.contains(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            int tagIndex = -1;
            for (int i = 0; i < params.length; i++) {
                if (params[i] == tagClass) {
                    tagIndex = i;
                    break;
                }
            }
            if (tagIndex < 0) continue;
            final int tagArgument = tagIndex;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (tagArgument < param.args.length) {
                        apply(param.args[tagArgument], method.getName());
                    }
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            throw new IllegalStateException("ApsProcessor request-tag methods not found");
        }
        Log.i(TAG, "installed on " + hooked + " APS capture methods");
    }

    private static void apply(Object tag, String method) {
        if (tag == null) return;
        try {
            Object modeValue = XposedHelpers.getObjectField(tag, "mCaptureMode");
            String mode = String.valueOf(modeValue);
            // Hasselblad high-pixel live photos already run a separate P010 pipeline. Clearing
            // Ultra HDR here changes that pipeline after capture starts and can leave the defer
            // job without a completion notification on PKJ110.
            if (!PHOTO_MODE.equals(mode)) return;

            boolean live = XposedHelpers.getBooleanField(tag, "mbIsLivePhotoOn");
            boolean ultraHdr = XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable");
            if (!live || !ultraHdr) return;

            XposedHelpers.setBooleanField(tag, "mbUltraHdrEnable", false);
            int id = -1;
            try {
                id = XposedHelpers.getIntField(tag, "mRequestId");
            } catch (Throwable ignored) {
                // The id is diagnostic only.
            }
            String signature = mode + '|' + id + '|' + method;
            if (logged.size() < 64 && logged.add(signature)) {
                Log.i(TAG, "cover SDR mode=" + mode + " id=" + id
                    + " live=" + live + " ultraHdr=true -> false via " + method);
            }
        } catch (Throwable error) {
            String signature = "error|" + method + '|' + error.getClass().getName();
            if (logged.size() < 64 && logged.add(signature)) {
                Log.e(TAG, "capture tag update failed in " + method, error);
            }
        }
    }
}
