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

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "GR preview LUT", () -> hookPreviewLut(symbols, classLoader));
        Log.guard(TAG, "GR capture stage", () -> hookCaptureStage(symbols, classLoader));
        Log.guard(TAG, "GR SDK request", () -> hookSdkRequest(symbols, classLoader));
    }

    private static void hookPreviewLut(final Symbols symbols, ClassLoader classLoader) {
        final ThreadLocal<Boolean> loadingGrLut = new ThreadLocal<>();
        final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
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
                // Entering or leaving GR invalidates the cached LUT (index = -1).
                if (gr != "gr".equals(symbols.get("Lut3d.mode", param.thisObject))) {
                    symbols.set("Lut3d.index", param.thisObject, -1);
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
     */
    private static String describeLutState(Object manager) {
        try {
            float[] basic = (float[]) XposedHelpers.getObjectField(manager, "c");
            int[] sizeHolder = (int[]) XposedHelpers.getObjectField(manager, "l");
            String selected = String.valueOf(XposedHelpers.getObjectField(manager, "P"));
            int combine = XposedHelpers.getIntField(manager, "M");
            int capMode = XposedHelpers.getIntField(manager, "O");
            int selectedIndex = XposedHelpers.getIntField(manager, "Q");
            int lutSize = XposedHelpers.getIntField(manager, "t");
            boolean initialized = XposedHelpers.getBooleanField(manager, "N");
            boolean palette = XposedHelpers.getBooleanField(manager, "W");
            boolean hdr = XposedHelpers.getBooleanField(manager, "X");
            String hash = basic == null ? "null" : Integer.toHexString(java.util.Arrays.hashCode(basic));
            String samples = basic == null ? "null" : sampleFloats(basic, 8);
            int holder = sizeHolder != null && sizeHolder.length != 0 ? sizeHolder[0] : -1;
            return "inst=" + Integer.toHexString(System.identityHashCode(manager))
                + " grMode=" + GrState.isGrMode()
                + " selected=" + selected
                + " combine=" + combine
                + " cap=" + capMode
                + " selectedIndex=" + selectedIndex
                + " initialized=" + initialized
                + " palette=" + palette
                + " hdr=" + hdr
                + " lutSize=" + lutSize
                + " holderSize=" + holder
                + " basicLen=" + (basic == null ? -1 : basic.length)
                + " basicHash=" + hash
                + " basicSamples=" + samples;
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
