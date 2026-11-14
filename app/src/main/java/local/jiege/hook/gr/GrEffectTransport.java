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
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String mode = String.valueOf(param.args[2]);
                if (!mode.equals(tracedMode)) {
                    tracedMode = mode;
                    // One line per mode the preview pipeline loads: a live photo records from a
                    // second stream, and "which mode did that stream load" is the question that
                    // decides whether GR can still be bound while the camera is in master mode.
                    Log.i(TAG, "Lut3d.load mode=" + mode + " grMode=" + GrState.isGrMode());
                }
                boolean gr = "gr".equals(param.args[2]);
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
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(loadingGrLut.get())) return;
                    if (!GrState.isGrMode()) {
                        // A "gr" load while the camera is in another mode is a leftover: pointing
                        // the preview pipeline at the GR tree from here is what kept the GR look on
                        // the live-photo video after a GR shot. Left on the vendor's own tree; the
                        // next load in GR mode sets it again.
                        if (!tracedInit.equals("stale")) {
                            tracedInit = "stale";
                            Log.i(TAG, "gr LUT load outside GR mode: preview left on the vendor tree");
                        }
                        return;
                    }
                    param.args[0] = GR_LMT_DIR;
                    if (!tracedInit.equals("gr")) {
                        tracedInit = "gr";
                        Log.i(TAG, "CombineLut init -> GR tree");
                    }
                }
            });
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
