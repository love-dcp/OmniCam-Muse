package local.jiege.hook.gr;

import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * GR port: carries the GR image settings (saturation, tone, contrast, clarity, ...) to preview
 * and capture.
 *
 * Preview: the combined-LUT helper is pointed at the GR LMT tree staged by service.sh.
 * Capture: the GR style panel's encoded parameter struct is attached to the capture stage and
 * the SDK request, where the native half (gr_basictone.cpp) and APS read it.
 */
final class GrEffectTransport {
    private static final String TAG = "RicohGrPort";
    private static final String GR_LMT_DIR = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";
    /** Last Lut3d mode / combine-LUT directory / request residue state reported, so each is one line. */
    private static volatile String tracedMode;

    /**
     * Camera id -> the mode its preview LUT was last built for.
     *
     * <p>The live-photo recorder reads a second preview stream on its own camera id (12 on PKJ110).
     * Nothing rebuilds that id's LUT when the shooting mode changes, so after a GR shot it keeps the
     * GR table while the still and the viewfinder move on -- the moving picture then plays back with
     * a look one table away from its own cover. Kept for the replay below.
     */
    private static final java.util.Map<Integer, String> lutModes = new java.util.concurrent.ConcurrentHashMap<>();
    /** Opt-in marker: replays the current LUT onto ids still holding the GR one. */
    private static final java.io.File LUT_REPLAY =
        new java.io.File("/data/user/0/com.oplus.camera/files/jiege/lut_replay");
    private static final ThreadLocal<Boolean> replayingLut = new ThreadLocal<>();

    private static boolean replaying() {
        return Boolean.TRUE.equals(replayingLut.get());
    }

    /** Rebuilds every camera id whose LUT still says "gr" with the mode that is current now. */
    private static void replayLut(Symbols symbols, XC_MethodHook.MethodHookParam param, int id, int arg,
        String mode) {
        Method load;
        try {
            load = symbols.method("Lut3d.load");
        } catch (Throwable ignored) {
            return;
        }
        replayingLut.set(true);
        try {
            for (java.util.Map.Entry<Integer, String> entry : lutModes.entrySet()) {
                int other = entry.getKey();
                if (other == id || !"gr".equals(entry.getValue())) continue;
                Object result;
                try {
                    result = load.invoke(param.thisObject, other, arg, mode);
                } catch (Throwable error) {
                    Log.e(TAG, "Lut3d.replay id=" + other + " failed", error);
                    continue;
                }
                lutModes.put(other, mode);
                Log.i(TAG, "Lut3d.replay id=" + other + " gr -> " + mode + " ret=" + result);
            }
        } finally {
            replayingLut.remove();
        }
    }
    private static volatile String tracedInit;
    private static volatile String tracedResidue;

    private GrEffectTransport() {}

    /**
     * The GR verdict the native half needs, published for it as a one-byte file.
     *
     * <p>GR runs on the professional (master) pipeline on this phone, so a capture cannot be told
     * apart from a master one by its capture mode. The native half used to rely on the APS setting
     * {@code gr_effect_size == 63}; the 7.006.317 build does not carry that key, so every GR capture
     * was graded with the master-colour LMT while the viewfinder showed the GR tree -- "选滤镜后拍
     * 出来的颜色跟预览差别大". The camera's own {@code GrSupport.isGrMode()} is the authoritative
     * answer, and this is where it crosses into native. Written on change only.
     */
    private static final java.io.File GR_FLAG =
        new java.io.File("/data/user/0/com.oplus.camera/files/jiege/gr_mode");
    private static volatile Boolean grFlagPublished;
    private static volatile boolean applicationReady;

    /**
     * {@code GrState.isGrMode()} reaches the camera's preference store, so it may only be asked once
     * the application exists.
     *
     * <p>Module install runs inside {@code ActivityThread.handleBindApplication}, before the
     * Application object is created. Reading a preference from there initialises the store early and
     * the SharedPreferences-to-MMKV migration waits for the application to finish binding -- the
     * migration thread has not started yet, so the main thread parks forever. The camera showed a
     * black screen (main thread in {@code DataMigrateHelper.b} <- {@code PrefKeyLaunchSpecified.<clinit>}
     * <- {@code GrState.isGrMode} <- {@code publishMode} <- {@code install}). Never ask before then.
     */
    private static boolean applicationReady() {
        if (applicationReady) return true;
        try {
            Object thread = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentActivityThread");
            if (thread == null) return false;
            if (XposedHelpers.callMethod(thread, "getApplication") == null) return false;
            applicationReady = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void publishMode() {
        if (!applicationReady()) return;
        boolean gr;
        try {
            gr = GrState.isGrMode();
        } catch (Throwable error) {
            return;
        }
        Boolean was = grFlagPublished;
        if (was != null && was == gr) return;
        grFlagPublished = gr;
        try {
            java.io.File dir = GR_FLAG.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                Log.i(TAG, "GR mode flag: cannot create " + dir);
                return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(GR_FLAG)) {
                out.write(gr ? '1' : '0');
            }
            Log.i(TAG, "GR mode flag -> " + gr);
        } catch (Throwable error) {
            Log.e(TAG, "GR mode flag", error);
        }
    }

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        // No publishMode() here: see applicationReady(). The file is (re)written on the first preview
        // LUT build, which is the earliest point after the application exists and always precedes a
        // capture, so a stale "1" cannot outlive the first camera session.
        Log.guard(TAG, "GR preview LUT", () -> hookPreviewLut(symbols, classLoader));
        Log.guard(TAG, "GR capture stage", () -> hookCaptureStage(symbols, classLoader));
        Log.guard(TAG, "GR SDK request", () -> hookSdkRequest(symbols, classLoader));
    }

    private static void hookPreviewLut(final Symbols symbols, ClassLoader classLoader) {
        final ThreadLocal<Boolean> loadingGrLut = new ThreadLocal<>();
        final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Every preview LUT build is a free occasion to re-check the mode: entering or
                // leaving GR always rebuilds one, and the native half must not be a mode behind.
                publishMode();
                String mode = String.valueOf(param.args[2]);
                int id = param.args[0] instanceof Integer ? (Integer) param.args[0] : -1;
                int arg = param.args[1] instanceof Integer ? (Integer) param.args[1] : -1;
                boolean gr = "gr".equals(mode);
                String previous = lutModes.put(id, mode);
                if (!replaying()) {
                    String key = id + "/" + mode + "/" + GrState.isGrMode();
                    if (!key.equals(tracedMode)) {
                        tracedMode = key;
                        // One line per camera id and mode the LUT manager builds for. A live photo
                        // records from a second preview stream on its own camera id; "was that id
                        // ever rebuilt after GR" is the question this settles.
                        Log.i(TAG, "Lut3d.load id=" + id + " arg=" + arg + " mode=" + mode
                            + " grMode=" + GrState.isGrMode() + " inst="
                            + Integer.toHexString(System.identityHashCode(param.thisObject)));
                    }
                    if (LUT_REPLAY.exists() && !gr && !GrState.isGrMode()) replayLut(symbols, param, id, arg, mode);
                }
                // Entering or leaving GR invalidates the cached LUT (cache keys = -1).
                if (gr != "gr".equals(symbols.get("Lut3d.mode", param.thisObject))) {
                    symbols.setAll("Lut3d.cacheKeys", param.thisObject, -1);
                }
                loadingGrLut.set(gr);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                loadingGrLut.remove();
            }
        });
        try {
            XposedBridge.hookMethod(symbols.method("Lut3d.update"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Lut3d.update before "
                        + describeLutState(param.thisObject)
                        + " caller=" + lutUpdateCaller());
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Lut3d.update after "
                        + describeLutState(param.thisObject));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "Lut3d.update hook failed", error);
        }
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(loadingGrLut.get())) return;
                    if (!GrState.isGrMode()) {
                        // A "gr" load while the camera is in another mode is a leftover: pointing
                        // the preview pipeline at the GR tree from here is what kept the GR look on
                        // the live-photo video after a GR shot. Left on the vendor's own tree; the
                        // next load in GR mode sets it again.
                        if (!"stale".equals(tracedInit)) {
                            tracedInit = "stale";
                            Log.i(TAG, "gr LUT load outside GR mode: preview left on the vendor tree");
                        }
                        return;
                    }
                    param.args[0] = GR_LMT_DIR;
                    if (!"gr".equals(tracedInit)) {
                        tracedInit = "gr";
                        Log.i(TAG, "CombineLut init -> GR tree");
                    }
                }
            });
    }

    /**
     * Capture the state changed by {@code Lut3dDataManager.p()}. This is deliberately read-only:
     * the post-shot grey regression currently coincides with two updates, and the previous probe
     * (instance id only) could not tell whether p() changed the mode memo, the selected table or
     * only the BasicTone float array.
     *
     * <p>Fingerprint rule: no obfuscated field names are hardcoded here. The mode string comes
     * from the resolved {@code Lut3d.mode} symbol, the load cache keys from {@code Lut3d.cacheKeys},
     * and everything else is dumped generically by type (scalars, arrays, String) so the same
     * diagnostics work across camera builds where R8 reshuffles short names.
     */
    private static String describeLutState(Object manager) {
        try {
            StringBuilder out = new StringBuilder("inst=")
                .append(Integer.toHexString(System.identityHashCode(manager)))
                .append(" grMode=").append(GrState.isGrMode());
            Symbols symbols = Symbols.get();
            if (symbols != null && symbols.has("Lut3d.mode")) {
                try {
                    out.append(" mode=").append(symbols.get("Lut3d.mode", manager));
                } catch (Throwable error) {
                    out.append(" mode=?");
                }
            }
            if (symbols != null && symbols.has("Lut3d.cacheKeys")) {
                for (Object entry : symbols.all("Lut3d.cacheKeys")) {
                    java.lang.reflect.Field field = (java.lang.reflect.Field) entry;
                    try {
                        field.setAccessible(true);
                        out.append(' ').append(field.getName()).append('=').append(field.get(manager));
                    } catch (Throwable ignored) {
                        // a single unreadable key must not sink the whole dump
                    }
                }
            }
            // Generic typed dump: scalars, primitive arrays and Strings by declared order.
            int scalars = 0, arrays = 0;
            for (java.lang.reflect.Field field : manager.getClass().getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)) continue;
                Class<?> type = field.getType();
                try {
                    field.setAccessible(true);
                    if (type == int.class || type == boolean.class || type == float.class) {
                        if (scalars++ >= 10) continue;
                        out.append(' ').append(field.getName()).append('=').append(field.get(manager));
                    } else if (type == float[].class) {
                        float[] values = (float[]) field.get(manager);
                        out.append(' ').append(field.getName()).append("Len=")
                            .append(values == null ? -1 : values.length);
                        if (values != null && values.length != 0 && arrays++ < 4) {
                            out.append(' ').append(field.getName()).append("Hash=")
                                .append(Integer.toHexString(java.util.Arrays.hashCode(values)));
                            out.append(' ').append(field.getName()).append("Samples=")
                                .append(sampleFloats(values, 8));
                        }
                    } else if (type == int[].class) {
                        int[] values = (int[]) field.get(manager);
                        int first = values != null && values.length != 0 ? values[0] : -1;
                        out.append(' ').append(field.getName()).append("Len=")
                            .append(values == null ? -1 : values.length)
                            .append(' ').append(field.getName()).append("[0]=").append(first);
                    } else if (type == String.class) {
                        Object value = field.get(manager);
                        out.append(' ').append(field.getName()).append('=')
                            .append(value == null ? "null" : value);
                    }
                } catch (Throwable ignored) {
                    // an unreadable field must not sink the whole dump
                }
            }
            return out.toString();
        } catch (Throwable error) {
            return "inst=" + Integer.toHexString(System.identityHashCode(manager))
                + " grMode=" + GrState.isGrMode()
                + " state=unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static String sampleFloats(float[] values, int count) {
        int end = Math.min(values.length, count);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < end; i++) {
            if (i != 0) out.append(',');
            out.append(String.format(java.util.Locale.US, "%.6f", values[i]));
        }
        if (end < values.length) out.append(",...");
        return out.append(']').toString();
    }

    private static String lutUpdateCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("local.jiege.")
                || name.startsWith("de.robv.android.xposed.")
                || name.startsWith("java.lang.Thread")) {
                continue;
            }
            return name + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }

    private static void hookCaptureStage(final Symbols symbols, final ClassLoader classLoader) {
        final Method putBundle = symbols.method("StageRequest.putBundle");
        XposedBridge.hookMethod(symbols.method("GrMode.captureStage"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                GrState.currentMode = param.thisObject;
                // A capture is the last moment this can be settled: the frame is graded a few
                // milliseconds later, on another thread.
                publishMode();
                if (!GrState.isGrMode()) return;
                try {
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) {
                        Log.i(TAG, "GR effect encoder unavailable");
                        return;
                    }
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", classLoader), "GR_MODE_PARAM");
                    Object request = symbols.get("Stage.request", param.args[0]);
                    if (request == null) {
                        Log.i(TAG, "GR capture stage has no request builder");
                        return;
                    }
                    putBundle.invoke(request, key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect stage attach failed", error);
                }
            }
        });
    }

    private static void hookSdkRequest(final Symbols symbols, final ClassLoader classLoader) {
        Class<?> grCapMode = XposedHelpers.findClass("com.oplus.ocs.camera.producer.mode.GRCapMode", classLoader);
        XposedBridge.hookAllMethods(grCapMode, "updateCaptureRequestTag", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3) return;
                if (!GrState.isGrMode()) { releaseResidue(classLoader, param.args[0], param.args[1], "build"); return; }
                try {
                    Object builder = param.args[0];
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UPreviewKeys", classLoader), "KEY_GR_MODE_PARAM");
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(builder, "containCustomKey", key))) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect != null && effect.length != 0) XposedHelpers.callMethod(builder, "set", key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request injection failed", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 3) return;
                if (!GrState.isGrMode()) { releaseResidue(classLoader, param.args[0], param.args[1], "tag"); return; }
                try {
                    Object tag = param.args[1];
                    Object current = XposedHelpers.getObjectField(tag, "mGrEffectParam");
                    if (current != null && XposedHelpers.callMethod(current, "getGrEffectParams") != null) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) return;
                    Object holder = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.oplus.ocs.camera.consumer.apsAdapter.adapter.GrEffectParam", classLoader));
                    XposedHelpers.callMethod(holder, "setGrEffectParams", new Class<?>[] {byte[].class}, effect);
                    XposedHelpers.setObjectField(tag, "mGrEffectParam", holder);
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request tag attach failed", error);
                }
            }
        });
    }

    /**
     * Outside GR mode the request tag must carry no GR effect.
     *
     * <p>A live photo in master mode records its video from a second stream, configured from the
     * request tag that was current when the session was set up. The GR effect param written here
     * during a GR shot is a field on that tag object, and the camera reuses the object: leaving GR
     * never cleared it, so the video stream kept the GR params (gr_effect_size stays 63 in the HAL
     * settings) while the still of the same shot was processed with the master look. The result was
     * a live photo whose video is GR and whose cover is master colour -- and it survived every
     * later shot until the camera process restarted, because nothing else writes that field.
     *
     * <p>Null is the normal state outside GR (it is what a master-mode request carries when GR has
     * never run), so re-clearing it is a restore, not a new behaviour. Reported once per change.
     */
    private static void releaseResidue(ClassLoader classLoader, Object builder, Object tag, String where) {
        try {
            Object holder = null;
            try {
                holder = XposedHelpers.getObjectField(tag, "mGrEffectParam");
            } catch (Throwable ignored) {
                // A tag without the field means this build never had a GR param: nothing to clear.
            }
            byte[] params = null;
            if (holder != null) {
                Object raw = XposedHelpers.callMethod(holder, "getGrEffectParams");
                if (raw instanceof byte[]) params = (byte[]) raw;
            }
            boolean inBuilder = false;
            try {
                Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UPreviewKeys", classLoader), "KEY_GR_MODE_PARAM");
                inBuilder = Boolean.TRUE.equals(XposedHelpers.callMethod(builder, "containCustomKey", key));
            } catch (Throwable ignored) {}
            boolean residue = holder != null && (params == null || params.length != 0);
            if (residue) XposedHelpers.setObjectField(tag, "mGrEffectParam", null);
            String state = where + " tag=" + (params == null ? (holder == null ? "none" : "?") : params.length + "B")
                + " builder=" + inBuilder + (residue ? " cleared" : "");
            if (state.equals(tracedResidue)) return;
            tracedResidue = state;
            Log.i(TAG, "outside GR request: " + state);
        } catch (Throwable error) {
            Log.e(TAG, "GR residue release failed", error);
        }
    }

    private static Bundle effectBundle(byte[] effect) {
        Bundle bundle = new Bundle();
        bundle.putByteArray("gr.effect.params", effect);
        return bundle;
    }

    /** Encodes the GR style panel state or, if the panel is absent, the default effect table. */
    private static byte[] loadEffectBytes(Symbols symbols) throws Throwable {
        Object presenter = GrModeHooks.captureParams(symbols);
        if (presenter == null) return null;
        Object panel = Reflection.findFieldByType(presenter, symbols.cls("GrStylePanel").getName());
        if (panel != null) {
            byte[] bytes = structBytes(symbols, symbols.call("GrStylePanel.effect", panel));
            traceEffect("panel", bytes);
            return bytes;
        }
        // Silent fallback: with no panel field there is nothing to read the user's axes from, so a
        // freshly built GrEffectTable is used -- whose values are the GR defaults (the positive
        // film ones). That is indistinguishable on screen from "the colour mode is ignored", so it
        // is reported: a capture taken before the panel ever existed would otherwise be blamed on
        // the LUT. Keep this line; it is the only way to tell which source a shot used.
        Class<?> tableType = symbols.cls("GrEffectTable");
        Object table = Reflection.findFieldByType(presenter, tableType.getName());
        String source = table != null ? "presenter-table" : "DEFAULT-table";
        if (table == null) table = XposedHelpers.newInstance(tableType);
        byte[] bytes = structBytes(symbols, XposedHelpers.newInstance(symbols.cls("GrEffect"), table));
        traceEffect(source, bytes);
        return bytes;
    }

    /** One line per capture attempt: which source the effect struct came from, and its payload. */
    private static void traceEffect(String source, byte[] bytes) {
        if (bytes == null) {
            Log.i(TAG, "GR effect source=" + source + " (null)");
            return;
        }
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(12, bytes.length); i++) {
            head.append(String.format("%02x", bytes[i]));
        }
        Log.i(TAG, "GR effect source=" + source + " len=" + bytes.length + " head=" + head);
    }

    private static byte[] structBytes(Symbols symbols, Object effect) throws Throwable {
        Object table = symbols.call("GrEffect.table", effect);
        ByteBuffer buffer = ((ByteBuffer) symbols.call("GrEffectTable.buffer", table)).duplicate();
        buffer.clear();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
