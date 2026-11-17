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
 * gallery renders that cover through its gain map, while the MotionPhoto attachment follows a
 * separate path. Clearing the flag on the capture tag before APS builds its meta item makes photo
 * mode's cover use SDR without changing ordinary still photos.
 *
 * <p>XPAN must keep its native Ultra HDR capture flag. Clearing it for the XPAN capture tag also
 * changes the following preview request from the HDR BasicTone path to the SDR Log-like path; the
 * preview stays gray until the live-photo session is rebuilt. XPAN's cover/playback brightness is
 * handled on its SDR output and BasicTone paths instead.
 */
public final class LivePhotoSdrCover {
    private static final String TAG = "LivePhotoSdrCover";
    private static final String SDK = "com.oplus.ocs.camera.";
    /**
     * Master (professional) live photos have the same mismatch: the cover keeps the Ultra HDR
     * gain map while the embedded video is its own HDR10 track, so the gallery renders the two
     * through different tone mapping and the video reads warmer and more vivid than its cover.
     * XPAN and high-pixel stay out of this set on purpose -- clearing their flag changes the
     * following preview request (XPAN preview goes grey) or starves the defer job (high pixel).
     */
    private static final Set<String> SDR_MODES = Collections.unmodifiableSet(
        new HashSet<String>(Arrays.asList("common")));
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
                // Assignable, not equal: master mode declares its own request-tag subclass, so an
                // exact-match test skipped every one of these methods and the hook never ran.
                if (tagClass.isAssignableFrom(params[i])) {
                    tagIndex = i;
                    if (params[i] != tagClass) {
                        Log.i(TAG, "subclass tag on " + method.getName() + ": "
                            + params[i].getName());
                    }
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
        String tagClass = tag.getClass().getSimpleName();
        try {
            Object modeValue = XposedHelpers.getObjectField(tag, "mCaptureMode");
            String mode = String.valueOf(modeValue);
            // Hasselblad high-pixel live photos already run a separate P010 pipeline. Clearing
            // Ultra HDR here changes that pipeline after capture starts and can leave the defer
            // job without a completion notification on PKJ110.
            if (!SDR_MODES.contains(mode)) {
                // One line per unfamiliar mode: the tag's mode string is the only way to learn
                // whether master reports "professional" or something else.
                String seen = "mode|" + mode;
                if (logged.size() < 64 && logged.add(seen)) {
                    Log.i(TAG, "capture tag mode=" + mode + " class=" + tagClass
                    + " (not SDR-converted) via " + method);
                }
                return;
            }

            boolean live = XposedHelpers.getBooleanField(tag, "mbIsLivePhotoOn");
            boolean ultraHdr = XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable");
            if (!live || !ultraHdr) {
                String seen = "state|" + mode + '|' + live + '|' + ultraHdr;
                if (logged.size() < 64 && logged.add(seen)) {
                    Log.i(TAG, "capture tag mode=" + mode + " class=" + tagClass + " live=" + live
                        + " ultraHdr=" + ultraHdr + " (nothing to clear) via " + method);
                }
                return;
            }

            XposedHelpers.setBooleanField(tag, "mbUltraHdrEnable", false);
            boolean cleared = !XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable");
            int id = -1;
            try {
                id = XposedHelpers.getIntField(tag, "mRequestId");
            } catch (Throwable ignored) {
                // The id is diagnostic only.
            }
            String signature = mode + '|' + id + '|' + method;
            if (logged.size() < 64 && logged.add(signature)) {
                Log.i(TAG, "cover SDR mode=" + mode + " class=" + tagClass + " id=" + id
                    + " live=" + live + " ultraHdr=true -> cleared=" + cleared + " via " + method);
            }
        } catch (Throwable error) {
            String signature = "error|" + method + '|' + error.getClass().getName();
            if (logged.size() < 64 && logged.add(signature)) {
                Log.e(TAG, "capture tag update failed in " + method, error);
            }
        }
    }
}
