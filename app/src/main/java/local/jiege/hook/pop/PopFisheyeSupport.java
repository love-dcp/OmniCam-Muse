package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Keeps the POP fish-eye entry out of the "more" panel on PKJ110.
 *
 * <p>The button itself is stock: {@code op.e} adds a {@code fishEye} tile to the mode panel whenever
 * {@code CameraConfig.getConfigBooleanValue("com.oplus.feature.fish.eye.support")} answers true. X8U
 * has none of the machinery behind it - no {@code fisheye_case} in {@code camera_unit_config}, and no
 * {@code CNvFishEyeEdgeBlur} in {@code /odm/lib64/libmsnativefilter.so} - so tapping the tile asks
 * {@code BaseMode.getUseCaseValues} for a use case that is not configured and then blocks for ~28 s
 * before the session gives up. Measured 2026-11-01 10:01: after the wait the camera ended up in
 * whatever mode it had been sitting in, which reads as "the fish-eye button switches to Hasselblad
 * high resolution".
 *
 * <p>So the default is now to answer those keys <em>false</em> rather than true: with the key false
 * {@code op.e} never appends the tile, and {@code dm.p3} never builds the mode object. Nothing to tap,
 * nothing to hang. This is the opposite of what the class used to do, which is why the history below
 * is kept - if a device with a real fish-eye pipeline ever shows up, {@code
 * setprop persist.omnicam.aura.fisheye 1} restores the old behaviour.
 *
 * <p>Opt in with {@code setprop persist.omnicam.aura.fisheye 1} (then force-stop the camera): the
 * class then answers the support keys true and carries the lens into the still. That path also needs
 * the full Meishe renderer (the one with {@code CNvFishEyeEdgeBlur}) bound over
 * {@code /odm/lib64/libmsnativefilter.so}; the stock X8U build has no fish-eye code at all, so on this
 * device the enabled path only ever produced a preview that never started.
 *
 * <p>The still half, kept for that future device: the preview applies the effect through the texture
 * request, while the saved frame is produced by APS from the capture metadata, so
 * {@link #installCapture} writes {@code KEY_RETRO_FILTER_FISH_EYE_ENALE} into the metadata of a
 * retroCamera capture while the lens is on.
 */
public final class PopFisheyeSupport {
    private static final String TAG = "PopFisheye";
    private static final String PROP = "persist.omnicam.aura.fisheye";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String RETRO_MODE = "retroCamera";
    private static final String FISH_KEY = "KEY_RETRO_FILTER_FISH_EYE_ENALE";
    /** The mode panel's persistence, owned by the camera process this class is running in. */
    private static final String DB_PATH = "/data/user/0/com.oplus.camera/databases/mode_data.db";

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
    /** Last known state of the POP lens toggle (the presenter's f1), kept for the capture hook. */
    private static volatile boolean lensOn;
    /** Every Meishe effect context the camera has set a value on (preview, capture, ...). */
    private static final java.util.Set<Object> effectContexts =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());
    /** The renderer's own id for the fish-eye switch, read from the SDK. */
    private static volatile String fishEyeId;

    private PopFisheyeSupport() {}

    public static void install(ClassLoader classLoader) {
        Log.guard(TAG, "fish-eye lens", () -> installHooks(classLoader));
    }

    /** True while the module answers the fish-eye keys (the capture hook follows it). */
    public static boolean enabled() {
        return on;
    }

    private static void installHooks(ClassLoader classLoader) throws Throwable {
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        if (!optIn()) {
            installHide(config);
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                traceKey(param);
                if (!isFishKey(param)) return;
                if (answeredOnce.add(String.valueOf(param.args[0]))) {
                    Log.i(TAG, "answering " + param.args[0] + " = true");
                }
                param.setResult(Boolean.TRUE);
            }
        };
        XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hook);
        XposedBridge.hookAllMethods(config, "d", hook);
        on = true;
        installEffectBridge(classLoader);
        installCapture(classLoader);
        Log.i(TAG, "fish-eye lens: answering " + String.join(", ", KEYS) + " = true");
    }

    /**
     * The default: answer the fish-eye keys false so the mode panel never lays the tile out.
     *
     * <p>Answering them is not the same as leaving them alone. X8U's config files were searched end
     * to end (2026-11-01) and contain none of these keys, yet the tile showed up after the module
     * stopped answering true - so {@code CameraConfig} resolves at least one of them from somewhere
     * other than the plain-text config (vendor tags, cached table). Saying false explicitly is the
     * only answer that does not depend on where the true came from.
     *
     * <p>Only boolean-returning methods are touched: {@code d} is an obfuscated overload name and
     * other shapes of it exist on the same class.
     */
    private static void installHide(Class<?> config) {
        XC_MethodHook hide = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.method instanceof java.lang.reflect.Method)) return;
                if (((java.lang.reflect.Method) param.method).getReturnType() != boolean.class) return;
                if (!isFishKey(param)) return;
                if (answeredOnce.add("hide:" + param.args[0])) {
                    Log.i(TAG, "answering " + param.args[0] + " = false (entry stays out of the panel)");
                }
                param.setResult(Boolean.FALSE);
            }
        };
        XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hide);
        XposedBridge.hookAllMethods(config, "d", hide);
        dropStalePanelEntry();
        Log.i(TAG, "fish-eye entry hidden: answering " + String.join(", ", KEYS) + " = false");
    }

    /**
     * The mode panel is not built from the config key alone: once a tile has been persisted, {@code
     * op.g} serves it straight out of {@code mode_data.db} / {@code mode_info} and never asks again.
     * The row for fish-eye ({@code mode_id 29}) was written while this class still answered the keys
     * true, so the tile survived even with the config key forced false - measured 2026-11-01: the
     * panel kept showing 150 degrees after the key was false, and dropping the row removed it.
     *
     * <p>The row cannot come back on its own: the insert in {@code op.e} sits inside the same config
     * check, so with the key false a rebuilt database never writes it again. Deleting it here keeps
     * that true for cameras that already carry the row, and re-runs harmlessly once it is gone.
     */
    private static void dropStalePanelEntry() {
        java.io.File file = new java.io.File(DB_PATH);
        if (!file.exists()) return;
        android.database.sqlite.SQLiteDatabase database = null;
        try {
            database = android.database.sqlite.SQLiteDatabase.openDatabase(
                DB_PATH, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            int rows = database.delete("mode_info", "mode_id = '29'", null);
            if (rows > 0) {
                Log.i(TAG, "dropped " + rows + " stale fishEye row from mode_info");
            }
        } catch (Throwable error) {
            Log.i(TAG, "mode_info cleanup skipped: " + error);
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * The still is filtered by the IPU filter process unit, and its parameters carry
     * {@code retro_ccd_fisheye_open} / {@code retro_ccd_fisheye_zoom}. The preview is seen sending
     * them as true in the log; the image pass keeps them false. Forcing the flag on the parameter
     * call is enough for that unit to warp the frame.
     */
    private static void installIpuFilter(ClassLoader classLoader) {
        try {
            Class<?> keys = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.filter.key.FilterProcessKeySDK",
                classLoader);
            Object openKey = staticFieldOrNull(keys, "RETRO_CCD_FISHEYE_OPEN");
            Object zoomKey = staticFieldOrNull(keys, "RETRO_CCD_FISHEYE_ZOOM");
            Class<?> unit = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.BaseProcessUnitSDK", classLoader);
            XposedBridge.hookAllMethods(unit, "setProcessParameter", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 2) return;
                    if (!lensOn && !lensFromPreference()) return;
                    if (param.args[0] == null) return;
                    final String key = String.valueOf(param.args[0]);
                    if (traced.size() < 200 && traced.add("k:" + key)) {
                        Log.i(TAG, "IPU param " + key + " = " + param.args[1]);
                    }
                    if (key.contains("retro_ccd_fisheye_open") || (openKey != null && param.args[0] == openKey)) {
                        param.args[1] = Boolean.TRUE;
                        if (traced.add("ipuOpen")) {
                            Log.i(TAG, "IPU filter retro_ccd_fisheye_open forced true");
                        }
                    } else if (key.contains("retro_ccd_fisheye_zoom") || (zoomKey != null && param.args[0] == zoomKey)) {
                        param.args[1] = Float.valueOf(1.0f);
                    }
                }
            });
            Log.i(TAG, "IPU filter fish-eye keys hooked");
        } catch (Throwable error) {
            Log.i(TAG, "IPU filter hook unavailable: " + error);
        }
    }

    private static Object staticFieldOrNull(Class<?> type, String name) {
        try {
            return XposedHelpers.getStaticObjectField(type, name);
        } catch (Throwable missing) {
            Log.i(TAG, "no " + name + " on " + type.getSimpleName());
            return null;
        }
    }

    /**
     * The Meishe SDK's own switch: {@code MsEffectSdkContextUtils.MsSetEffectBooleanValue(id, value)}
     * reaches {@code nativeMsSetEffectBooleanValue}, which is what the reference module calls "set the
     * fish flag through the renderer API". The camera calls it for grain and the other toggles, so the
     * live context is picked up from there and reused at capture; writing the renderer struct by hand
     * was measured to crash the preview thread.
     */
    private static void installEffectBridge(ClassLoader classLoader) {
        try {
            Class<?> utils = XposedHelpers.findClass("com.meicam.effect.sdk.MsEffectSdkContextUtils",
                classLoader);
            XposedBridge.hookAllMethods(utils, "MsSetEffectBooleanValue", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length == 2) {
                        effectContexts.add(param.thisObject);
                    }
                }
            });
            Object id = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("com.meicam.effect.sdk.SMsEffectParam", classLoader),
                "MS_EFFECT_PARAM_ID_FISH_EYE_ENABLE");
            if (id instanceof String) fishEyeId = (String) id;
            Log.i(TAG, "renderer switch id = " + fishEyeId);
        } catch (Throwable error) {
            Log.i(TAG, "renderer bridge unavailable: " + error);
        }
    }

    /** Turns the Meishe fish-eye pass on for every context the camera has used so far. */
    private static void applyEffectFishEye() {
        String id = fishEyeId;
        if (id == null) return;
        int applied = 0;
        for (Object context : new java.util.ArrayList<>(effectContexts)) {
            try {
                Object result = XposedHelpers.callMethod(context, "MsSetEffectBooleanValue", id,
                    Boolean.TRUE);
                if (Boolean.TRUE.equals(result)) applied++;
            } catch (Throwable error) {
                if (traced.add("effectError")) Log.i(TAG, "renderer fish-eye failed: " + error);
            }
        }
        if (traced.add("effect")) {
            Log.i(TAG, "renderer fish-eye -> " + applied + "/" + effectContexts.size() + " contexts");
        }
    }

    /**
     * Writes the fish-eye flag into the capture metadata of a retroCamera shot. The presenter puts it
     * on the preview tag only, which is why the picture on screen had the effect and the saved frame
     * did not.
     */
    private static void installCapture(ClassLoader classLoader) {
        // The tag is built before the metadata is derived from it, and the APS fish-eye node reads
        // the flag from that tag, so it has to be set as early as possible.
        try {
            Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
            XposedBridge.hookAllMethods(processor, "createRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tag = param.getResult();
                    if (tag == null) return;
                    try {
                        if (!lensOn && !lensFromPreference()) return;
                        XposedHelpers.setBooleanField(tag, "mRetroFilterFishEyeEnable", true);
                        XposedHelpers.setBooleanField(tag, "mbFishEyeEnable", true);
                        writeCaptureStamp();
                        if (traced.add("tagEarly")) {
                            Log.i(TAG, "request tag fish-eye flags set at createRequestTag");
                        }
                    } catch (Throwable error) {
                        if (traced.add("tagEarlyError")) Log.i(TAG, "early tag failed: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "createRequestTag hook unavailable: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(SDK + "consumer.ApsProcessor", classLoader,
                "createMetaItemInfo", SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        // The tag carries the lens as two plain booleans and the capture pipeline
                        // reads them before the metadata is built, so they have to be set first.
                        try {
                            if (!lensOn && !lensFromPreference()) return;
                            Object tag = param.args[0];
                            XposedHelpers.setBooleanField(tag, "mRetroFilterFishEyeEnable", true);
                            XposedHelpers.setBooleanField(tag, "mbFishEyeEnable", true);
                            // The renderer is the half that actually draws the frame.
                            applyEffectFishEye();
                            if (traced.add("tag")) {
                                Log.i(TAG, "capture tag carries mRetroFilterFishEyeEnable/mbFishEyeEnable");
                            }
                        } catch (Throwable error) {
                            if (traced.add("tagError")) Log.i(TAG, "capture tag flag failed: " + error);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || param.getResult() == null) return;
                        try {
                            Object tag = param.args[0];
                            Object request = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                            if (request == null) return;
                            Object mode = XposedHelpers.getObjectField(request, "mModeName");
                            if (traced.add("mode:" + mode)) Log.i(TAG, "capture mode=" + mode);
                            // POP captures through the common config, so the request is not named
                            // retroCamera; the lens signal is what makes this a POP fish-eye shot.
                            if (!lensOn && !lensFromPreference()) return;
                            Object key = XposedHelpers.getStaticObjectField(
                                XposedHelpers.findClass("com.oplus.ocs.camera.common.util.ParameterKeys",
                                    classLoader),
                                "KEY_RETRO_FISH_EYE_ENABLE");
                            // MetaItemInfo inherits setParameter(ApsParameters$Key, Object); the
                            // key is a PreviewKey, so resolve the method by its declared types
                            // instead of letting the runtime pick by the argument's classes.
                            Class<?> keyBase = XposedHelpers.findClass(
                                "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsParameters$Key",
                                classLoader);
                            java.lang.reflect.Method set = param.getResult().getClass()
                                .getMethod("setParameter", keyBase, Object.class);
                            set.invoke(param.getResult(), key, Boolean.TRUE);

                            // The capture engine (libAlgoInterface) carries the renderer's
                            // "Fish Eye Enable" name too, so it reads the request key the CCD
                            // styles use for the same lens. Live photos play back with the effect,
                            // which is the path that fills this key in; the still needs it too.
                            try {
                                Object ccdKey = XposedHelpers.getStaticObjectField(
                                    XposedHelpers.findClass(SDK + "ipusdk.processunit.filter.key"
                                        + ".FilterProcessKeySDK", classLoader),
                                    "RETRO_CCD_FISHEYE_OPEN");
                                XposedHelpers.callMethod(request, "setParameter", ccdKey, Boolean.TRUE);
                                if (traced.add("ccd:" + mode)) {
                                    Log.i(TAG, "capture request carries RETRO_CCD_FISHEYE_OPEN = true");
                                }
                            } catch (Throwable ccdError) {
                                if (traced.add("ccdError")) {
                                    Log.i(TAG, "ccd fisheye key failed: " + ccdError);
                                }
                            }
                            if (traced.add("capture:" + mode)) {
                                Log.i(TAG, "capture metadata carries " + FISH_KEY + " = true");
                            }
                        } catch (Throwable error) {
                            if (traced.add("captureError")) Log.i(TAG, "capture flag failed: " + error);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.i(TAG, "capture hook unavailable: " + error);
        }
        // Track the lens toggle so the capture hook knows whether the user has it on. The texture
        // request is the object the lens button drives, and it is also re-seeded from the saved
        // preference when the page is built, so it is the most reliable signal.
        try {
            Class<?> request = XposedHelpers.findClass(
                "com.oplus.camera.filter.texturepreview.TexturePreviewRequest", classLoader);
            XposedBridge.hookAllMethods(request, "setRetroFishEyeOpen", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof Boolean) {
                        lensOn = (Boolean) param.args[0];
                        writeLensState(lensOn);
                        Log.i(TAG, "lens toggled " + lensOn);
                    }
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "texture request hook unavailable: " + error);
        }
    }

    /**
     * The APS fish-eye node is only wanted for the shot, not for the preview that shares the same
     * engine, so the capture window is stamped here and the native getEnable hook stays quiet
     * outside it.
     */
    private static void writeCaptureStamp() {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                "/data/user/0/com.oplus.camera/files/fisheye_capture", false);
            out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            out.close();
        } catch (Throwable error) {
            Log.i(TAG, "capture stamp write failed: " + error);
        }
    }

    /**
     * The native half (pop_fisheye.cpp) has no JNI handle of its own, so the lens state is handed
     * over as a one-byte file in the app's private directory, which this process owns.
     */
    private static void writeLensState(boolean on) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                "/data/user/0/com.oplus.camera/files/fisheye_lens", false);
            out.write(on ? '1' : '0');
            out.close();
        } catch (Throwable error) {
            Log.i(TAG, "lens state write failed: " + error);
        }
    }

    /** The toggle is persisted per mode; ask the same preference the presenter reads. */
    private static boolean lensFromPreference() {        try {
            Class<?> prefs = XposedHelpers.findClass("ia.s1", null);
            Object value = XposedHelpers.callStaticMethod(prefs, "e", RETRO_MODE);
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            return false;
        }
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

    private static boolean isFishKey(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) return false;
        Object key = param.args[0];
        if (!(key instanceof String)) return false;
        return java.util.Arrays.asList(KEYS).indexOf(key) >= 0;
    }

    /**
     * {@code persist.omnicam.aura.fisheye} has to be an explicit opt-in now: absent, empty, 0 and
     * anything else all mean "hide". Only 1 / on / true bring the old answering-true behaviour back.
     */
    private static boolean optIn() {
        String value = readProp();
        return "1".equals(value) || "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
    }

    private static String readProp() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, PROP, ""));
        } catch (Throwable error) {
            return "";
        }
    }
}
