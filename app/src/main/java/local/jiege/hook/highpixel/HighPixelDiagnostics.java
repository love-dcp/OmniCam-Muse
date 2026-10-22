package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import local.jiege.hook.common.Log;

/**
 * One log line per 哈苏超清 capture request: what the app asked the algorithms for (live photo, picture
 * size, HDR / 10-bit, sensor sizes, palette / filter, algorithm list). Read with the LSPosed module log;
 * it is how live 50MP and live 25MP requests are compared.
 */
final class HighPixelDiagnostics {
    private static final String TAG = "HighPixelRequest";
    private static final String MODE = "high_pixel_mode";
    private static final java.util.Set<String> snap =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private HighPixelDiagnostics() {}

    /** The camera app loader; the request tag comes from the SDK's own loader. */
    private static ClassLoader appLoader;

    static void install(ClassLoader classLoader) throws Throwable {
        appLoader = classLoader;
        Class<?> processor = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.CameraRequestTag", classLoader);
        Class<?> keys = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.ParameterKeys", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(keys, "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object tag = param.args[0];
                    Object mode = XposedHelpers.getObjectField(tag, "mCaptureMode");
                    // Log every mode that carries a filter: 大师 / photo / 哈苏超清 / video, so the
                    // working ones (大师) can be read off the same line as the broken one.
                    // Also log a capture that carries the palette but no filter: master's 50 MP
                    // request is the one that gets the palette into the file, and comparing its
                    // fields with this mode's is how the missing piece has to be found.
                    if (XposedHelpers.getBooleanField(tag, "mbFilterOpen") == false
                        && !MODE.equals(mode)
                        && XposedHelpers.getBooleanField(tag, "mbPaletteEnable") == false) return;
                    Object aps = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                    String algorithms = "?";
                    if (!param.hasThrowable() && param.getResult() != null) {
                        Object list = XposedHelpers.callMethod(param.getResult(), "get", algorithmKey);
                        algorithms = list instanceof String[] ? Arrays.toString((String[]) list) : String.valueOf(list);
                    }
                    Log.i(TAG, "mode=" + mode + " id=" + XposedHelpers.getIntField(tag, "mRequestId")
                        + " camera=" + XposedHelpers.getIntField(tag, "mRearFrontCameraId")
                        + " live=" + XposedHelpers.getBooleanField(tag, "mbIsLivePhotoOn")
                        + " pictureSize=" + XposedHelpers.getObjectField(aps, "mPictureSize")
                        + " highPictureSize=" + XposedHelpers.getBooleanField(aps, "mbHighPictureSizeEnable")
                        + " aiHighPixel=" + XposedHelpers.getBooleanField(aps, "mbAiHighPixelEnable")
                        + " tenBit=" + XposedHelpers.getBooleanField(tag, "mbPhoto10BitsEnable")
                        + " ultraHdr=" + XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable")
                        + " rawSR=" + XposedHelpers.getBooleanField(tag, "mbRawSREnable")
                        + " sensorSizes=" + Arrays.toString((Object[]) XposedHelpers.getObjectField(tag, "mSensorSizes"))
                        + " evList=" + Arrays.toString((int[]) XposedHelpers.getObjectField(tag, "mCaptureEvList"))
                        + " palette=" + XposedHelpers.getBooleanField(tag, "mbPaletteEnable")
                        + " filterOpen=" + XposedHelpers.getBooleanField(tag, "mbFilterOpen")
                        + " filter=" + XposedHelpers.getObjectField(tag, "mFilterType")
                        + " softLight=" + XposedHelpers.getBooleanField(tag, "mbSoftLightOpen") + "/" + XposedHelpers.getObjectField(tag, "mSoftLightType")
                        + " paletteState=" + managerState(appLoader) + " algorithms=" + algorithms);
                    logMetaSizes(mode, param);
                    // One full snapshot of the filter-related fields per mode *and* filter: 和光 and
                    // 反差黑白 each carry their own grain block, so every filter gets its own line.
                    if (snap.add("allFields")) {
                        StringBuilder every = new StringBuilder("tag fields:");
                        for (java.lang.reflect.Field field : tag.getClass().getDeclaredFields()) {
                            every.append(' ').append(field.getName()).append(':')
                                .append(field.getType().getSimpleName());
                        }
                        Log.i(TAG, every.toString());
                    }
                    Object filterType = XposedHelpers.getObjectField(tag, "mFilterType");
                    if (snap.add(String.valueOf(mode) + "|" + filterType)) {
                        StringBuilder text = new StringBuilder("snap mode=").append(mode).append(':');
                        for (java.lang.reflect.Field field : tag.getClass().getDeclaredFields()) {
                            String name = field.getName();
                            String lower = name.toLowerCase();
                            if (!lower.contains("filter") && !lower.contains("palette")
                                && !lower.contains("lut") && !lower.contains("tone")
                                && !lower.contains("satur")) continue;
                            field.setAccessible(true);
                            try { text.append(' ').append(name).append('=').append(field.get(tag)); }
                            catch (Throwable ignored) { }
                        }
                        text.append(" manager=").append(managerState(appLoader));
                        Log.i(TAG, text.toString());
                        StringBuilder apsText = new StringBuilder("aps:");
                        try {
                            Object apsTag = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                            if (apsTag != null) {
                                for (java.lang.reflect.Field field : apsTag.getClass().getDeclaredFields()) {
                                    String lower = field.getName().toLowerCase();
                                    if (!lower.contains("tone") && !lower.contains("lut")
                                        && !lower.contains("palette") && !lower.contains("satur")) continue;
                                    field.setAccessible(true);
                                    try { apsText.append(' ').append(field.getName()).append('=')
                                        .append(field.get(apsTag)); }
                                    catch (Throwable ignored) { }
                                }
                            }
                        } catch (Throwable error) {
                            apsText.append(" unreadable ").append(error.getClass().getSimpleName());
                        }
                        Log.i(TAG, apsText.toString());
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "log", error);
                }
            }
        });
        Log.i(TAG, "installed");
    }

    private static final java.util.Set<Integer> sized = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Sizes of the keys the capture request carries. cameraserver drops the result metadata for the
     * 50 MP request ("result size 1078168, fmq availableToWrite 1048576") and the app then sees
     * {@code onCaptureBufferLost}; naming the biggest keys says which tag has to go.
     */
    private static void logMetaSizes(Object mode, XC_MethodHook.MethodHookParam param) {
        if (!MODE.equals(mode) || param.hasThrowable() || param.getResult() == null) return;
        try {
            Object meta = param.getResult();
            Object tag = param.args[0];
            int id = XposedHelpers.getIntField(tag, "mRequestId");
            if (!sized.add(id) || sized.size() > 64) return;
            long total = 0;
            int count = 0;
            String biggest = "";
            long biggestSize = 0;
            StringBuilder top = new StringBuilder();
            if (meta instanceof java.util.Map) {
                for (Object entryObject : ((java.util.Map<?, ?>) meta).entrySet()) {
                    java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) entryObject;
                    long size = measure(entry.getValue());
                    total += size;
                    count++;
                    if (size > biggestSize) { biggestSize = size; biggest = String.valueOf(entry.getKey()); }
                    if (size >= 4096) top.append(' ').append(entry.getKey()).append('=').append(size);
                }
            } else {
                Object keys;
                try { keys = XposedHelpers.callMethod(meta, "keySet"); }
                catch (Throwable plain) { keys = XposedHelpers.callMethod(meta, "keys"); }
                if (keys instanceof java.util.Collection) {
                    for (Object key : (java.util.Collection<?>) keys) {
                        long size;
                        try { size = measure(XposedHelpers.callMethod(meta, "get", key)); }
                        catch (Throwable ignored) { continue; }
                        total += size;
                        count++;
                        if (size > biggestSize) { biggestSize = size; biggest = String.valueOf(key); }
                        if (size >= 4096) top.append(' ').append(key).append('=').append(size);
                    }
                }
            }
            Log.i(TAG, "meta " + meta.getClass().getName() + " items=" + count + " bytes=" + total
                + " biggest=" + biggest + "/" + biggestSize + " big:" + top);
        } catch (Throwable error) {
            // Diagnostic only, and on this build reading the meta item's keys throws out of the app
            // (NoSuchMethodError in its own adapter). It must not raise the status page's error
            // count, so it is reported once as information.
            if (!sizesFailed) {
                sizesFailed = true;
                Log.i(TAG, "meta sizes unavailable: " + error);
            }
        }
    }

    private static boolean sizesFailed;

    /**
     * The palette the preview is drawing with: the LUT manager's tone/saturation and whether its
     * combined table is up. A capture whose palette differs from these is what "成片和预览不一样"
     * looks like, so the request snapshot carries them side by side.
     */
    private static String managerState(ClassLoader loader) {
        try {
            Class<?> holder = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager$b", loader);
            Object manager = XposedHelpers.getStaticObjectField(holder, "a");
            if (manager == null) return "none";
            return "tone=" + XposedHelpers.getIntField(manager, "w")
                + " saturation=" + XposedHelpers.getIntField(manager, "v")
                + " env=" + XposedHelpers.getBooleanField(manager, "N");
        } catch (Throwable error) {
            return "?" + error.getClass().getSimpleName();
        }
    }

    private static long measure(Object value) {        if (value == null) return 0;
        if (value instanceof String) return ((String) value).length();
        if (value instanceof byte[]) return ((byte[]) value).length;
        if (value instanceof int[]) return ((int[]) value).length * 4L;
        if (value instanceof float[]) return ((float[]) value).length * 4L;
        if (value instanceof Object[]) {
            long sum = 0;
            for (Object item : (Object[]) value) sum += measure(item);
            return sum;
        }
        return String.valueOf(value).length();
    }
}
