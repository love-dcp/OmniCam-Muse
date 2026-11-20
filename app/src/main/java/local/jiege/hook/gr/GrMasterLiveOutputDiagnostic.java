package local.jiege.hook.gr;

import android.app.Activity;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLES30;
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
    private static final java.util.concurrent.atomic.AtomicInteger createSequence =
        new java.util.concurrent.atomic.AtomicInteger();

    /*
     * r430: after a GR/master exit the stock recreates the whole live-photo recording
     * pipeline (new converter at XPAN entry, new Z2 wiring, green encoded output), while
     * a common-mode round trip reuses everything and records fine. Track the wiring
     * identities (GL source, output HardwareBuffer, native handle, encoder feed) so a
     * good capture and a green capture can be compared link by link instead of guessed.
     */
    private static final Map<Object, Object> wiredSources = new WeakHashMap<>();
    private static final Map<Object, Object> wiredBuffers = new WeakHashMap<>();
    private static final Map<Object, Boolean> loggedEncoderFeeds = new WeakHashMap<>();

    /*
     * r431: r430 proved the green video is a GL_INVALID_OPERATION (1282) raised on every
     * frame inside ch.e.b right after the post-GR/master converter recreation, while the
     * pipeline keeps flowing (binds/handles/feeds all present). ia.y1.c(II)I is the app's
     * glBindTexture wrapper, so the failing ops are texture binds feeding the conversion
     * draw. glBindTexture 1282 = "texture was previously created with a different target":
     * after GR exit the source object reports a stale target while the XPAN source texture
     * is an EXTERNAL_OES SurfaceTexture. Log every bind during conversion (target/texture/
     * error) and attempt a corrective bind under the other canonical target; a healthy
     * bind returns 0 so the retry only ever fires in the broken state.
     */
    /*
     * r432: r431 proved the failing binds are NOT texture binds — every real bind returns
     * 0; only the first checkpoint of each frame (the unbind) reports the 1282. GL errors
     * are queued per context, so that unbind merely reports an error left by an EARLIER
     * call of the same frame. ch.e.b per-frame sequence (from dex): e(FFI) vertex setup →
     * y0 program → glGenFramebuffers → glBindFramebuffer → new gl.m0 + m0.I(HardwareBuffer)
     * (EGLImage) → glFramebufferTexture2D → y1.c binds → draw. Locate the generating call
     * by draining the queue at fixed points: b() entry (stale from previous frame),
     * m0.I exit (EGLImage creation), the first bind checkpoint (FBO attach gap), and b()
     * exit (the draw). Also fix the r431 logEncoderFeed NPE (Boolean unboxing) and use
     * EGL native handles instead of wrapper identityHashCode in state dumps.
     */
    private static final ThreadLocal<Object> activeConversion = new ThreadLocal<>();
    private static final ThreadLocal<Integer> activeHeight = new ThreadLocal<>();
    private static final Map<Object, int[]> conversionFrames = new WeakHashMap<>();
    private static final Map<Object, int[]> errorFrameCounts = new WeakHashMap<>();
    private static final Map<Object, Boolean> loggedStates = new WeakHashMap<>();

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
                        + ", seq=" + createSequence.incrementAndGet()
                        + ", id=" + Integer.toHexString(System.identityHashCode(converter))
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

        hookConverterWiring(conversionBase, classLoader);
        hookEncoderFeed(classLoader);
        hookGlBindTexture(classLoader);
        hookConversionCheckpoints(conversionBase);
        hookConversionGlRegions(classLoader);
        Log.i(TAG, "master live YUV output diagnostic installed");
    }

    /*
     * r433: r432 localized the per-frame GL_INVALID_OPERATION to the segment between the
     * m0.I drain (err 0) and the first bind checkpoint (err 1282). That segment contains:
     * glFramebufferTexture2D → glViewport → glClearColor → glClear → y0.s() (program use)
     * → program uniforms → new u0 upload texture + B() + F(FloatBuffer upload) →
     * glActiveTexture. Bisect it with drains at y0.s() (covers attach+clear), u0.F entry
     * (covers program uniforms + upload-texture creation) and u0.F exit (the upload
     * itself), plus a framebuffer-status probe at the failing bind checkpoint.
     */
    /*
     * r433 localized the per-frame GL_INVALID_OPERATION to the segment between the m0.I
     * drain (err 0) and the first bind checkpoint (err 1282, FBO status 0x8cd5 =
     * INCOMPLETE_ATTACHMENT). That segment (dex): glFramebufferTexture2D(m0.A, m0.c) →
     * glViewport → glClearColor(0) → glClear → y0.s() → uniforms → glActiveTexture →
     * y1.c(w.f(), w.d()) — the failing bind carries texture 0, i.e. the u0 upload-texture
     * field w whose texture was never created.
     *
     * r434: ch.e.m(String) is the app's own labeled checkpoint ("convert
     * glGenFramebuffers failed", "convert glBindFramebuffer failed", "convert
     * glFramebufferTexture2D failed"). Hook it and drain the error queue at every
     * checkpoint: a non-zero drain at "glFramebufferTexture2D failed" convicts that call
     * (m0.c would be an invalid EGLImage texture); a clean drain there moves the culprit
     * to the uniform/activeTexture segment. Also dump m0's target (A/f()) and texture
     * name (c/d()) at the EGLImage probe, and the w wrapper state at the failing bind.
     */
    private static final Map<Object, int[]> checkpointDrainCounts = new WeakHashMap<>();

    private static void hookConversionCheckpoints(Class<?> conversionBase) {
        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(conversionBase, "m", String.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object converter = param.thisObject;
                        if (converter == null || activeConversion.get() == null) return;
                        int error = drainGlErrors();
                        Object label = param.args.length > 0 ? param.args[0] : "?";
                        int[] frames = conversionFrames.get(converter);
                        int frame = (frames == null ? 0 : frames[0]) + 1;
                        if (error != 0) {
                            int[] counts = checkpointDrainCounts.get(converter);
                            if (counts == null) {
                                counts = new int[1];
                                checkpointDrainCounts.put(converter, counts);
                            }
                            counts[0]++;
                            if (counts[0] <= 20 || counts[0] % 50 == 0) {
                                Log.i(TAG, "live YUV checkpoint error"
                                    + ", converter=" + idOf(converter)
                                    + ", frame=" + frame
                                    + ", at=" + label
                                    + ", err=0x" + Integer.toHexString(error)
                                    + ", errorCount=" + counts[0]
                                    + ", fboStatus=0x" + Integer.toHexString(
                                        GLES20.glCheckFramebufferStatus(
                                            GLES20.GL_FRAMEBUFFER)));
                            }
                        } else if (frame <= 3) {
                            Log.i(TAG, "live YUV checkpoint clean"
                                + ", converter=" + idOf(converter)
                                + ", frame=" + frame
                                + ", at=" + label
                                + ", fboStatus=0x" + Integer.toHexString(
                                    GLES20.glCheckFramebufferStatus(
                                        GLES20.GL_FRAMEBUFFER)));
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV checkpoint probe failed", error);
        }
    }

    private static void hookConversionGlRegions(ClassLoader classLoader) {
        try {
            Class<?> program = XposedHelpers.findClass(
                "com.oplus.camera.common.gl.y0", classLoader);
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(program, "s"),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (activeConversion.get() == null) return;
                        int error = drainGlErrors();
                        logRegion("program-use", error);
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV program-use probe failed", error);
        }
        try {
            Class<?> upload = XposedHelpers.findClass(
                "com.oplus.camera.common.gl.u0", classLoader);
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(
                    upload, "F", java.nio.FloatBuffer.class,
                    int.class, int.class, int.class),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (activeConversion.get() == null) return;
                        int error = drainGlErrors();
                        logRegion("upload-entry", error);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (activeConversion.get() == null) return;
                        int error = drainGlErrors();
                        logRegion("upload-exit", error);
                    }
                });
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(upload, "B"),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (activeConversion.get() == null) return;
                        Object texture = param.thisObject;
                        Log.i(TAG, "live YUV upload texture ensure"
                            + ", texture=" + idOf(texture)
                            + ", name=" + safeCall(texture, "d")
                            + ", target=" + safeCall(texture, "f"));
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV upload probe failed", error);
        }
    }

    private static void logRegion(String region, int error) {
        if (error == 0) return;
        Object converter = activeConversion.get();
        int[] counts = converter == null ? null : errorFrameCounts.get(converter);
        if (counts == null) return;
        int count = ++counts[0];
        if (count > 20 && count % 50 != 0) return;
        int[] frames = conversionFrames.get(converter);
        Log.i(TAG, "live YUV region error"
            + ", converter=" + idOf(converter)
            + ", frame=" + (frames == null ? 0 : frames[0] + 1)
            + ", region=" + region
            + ", err=0x" + Integer.toHexString(error)
            + ", errorCount=" + count);
    }

    /*
     * Z2 wires per capture: k(previewData.d) sets the GL source, l(imageData.b()) sets the
     * output HardwareBuffer, b(...) creates the native handle stored in ImageData.l. Log
     * whenever the wired identity CHANGES for a given converter (rewire = new capture) and
     * every handle creation, so good and green captures can be diffed link by link.
     */
    private static void hookConverterWiring(Class<?> conversionBase, ClassLoader classLoader) {
        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(conversionBase, "k",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader)),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object converter = param.thisObject;
                        Object source = param.args.length > 0 ? param.args[0] : null;
                        Object previous = wiredSources.put(converter, source);
                        if (previous == source) return;
                        Log.i(TAG, "live YUV conversion source wired"
                            + ", converter=" + idOf(converter)
                            + ", source=" + idOf(source)
                            + ", previous=" + idOf(previous)
                            + ", changed=" + (previous != null));
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV conversion source hook failed", error);
        }
        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(conversionBase, "l",
                    android.hardware.HardwareBuffer.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object converter = param.thisObject;
                        Object buffer = param.args.length > 0 ? param.args[0] : null;
                        Object previous = wiredBuffers.put(converter, buffer);
                        if (previous == buffer) return;
                        Log.i(TAG, "live YUV conversion buffer wired"
                            + ", converter=" + idOf(converter)
                            + ", buffer=" + idOf(buffer)
                            + ", previous=" + idOf(previous)
                            + ", size=" + describeBuffer(buffer)
                            + ", format=" + describeBufferFormat(buffer)
                            + ", usage=" + describeBufferUsage(buffer)
                            + ", changed=" + (previous != null));
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV conversion buffer hook failed", error);
        }
        try {
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(conversionBase, "b",
                    float.class, float.class, int.class,
                    boolean.class, boolean.class, float[].class),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object converter = param.thisObject;
                        activeConversion.set(converter);
                        activeHeight.set(intField(converter, "o"));
                        int stale = drainGlErrors();
                        if (stale != 0 && converter != null) {
                            int[] frames = conversionFrames.get(converter);
                            Log.i(TAG, "live YUV stale error at b() entry"
                                + ", converter=" + idOf(converter)
                                + ", frame=" + (frames == null ? 0 : frames[0] + 1)
                                + ", drained=0x" + Integer.toHexString(stale));
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object converter = param.thisObject;
                        int inside = drainGlErrors();
                        activeConversion.remove();
                        activeHeight.remove();
                        if (converter == null) return;
                        int[] frames = conversionFrames.get(converter);
                        if (frames == null) {
                            frames = new int[1];
                            conversionFrames.put(converter, frames);
                        }
                        int frame = ++frames[0];
                        Log.i(TAG, "live YUV conversion handle created"
                            + ", converter=" + idOf(converter)
                            + ", frame=" + frame
                            + ", handle=" + (param.getResult() instanceof Long
                                ? param.getResult() : "n/a")
                            + ", mirror=" + (param.args.length > 2 ? param.args[2] : "?")
                            + ", errorsInside=0x" + Integer.toHexString(inside)
                            + ", threw=" + param.hasThrowable());
                        if (frame <= 3 || frame % 150 == 0) {
                            dumpConversionState(converter, frame);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "live YUV conversion handle hook failed", error);
        }
        hookEglImageAttach(classLoader);
    }

    /*
     * gl.m0.I(HardwareBuffer)I creates the output EGLImage (m0 is instantiated fresh
     * inside every ch.e.b call). Drain the queue right after it: a non-zero error here
     * localizes the culprit to EGLImage creation from the output buffer.
     */
    private static void hookEglImageAttach(ClassLoader classLoader) {
        try {
            Class<?> imageWrapper =
                XposedHelpers.findClass("com.oplus.camera.common.gl.m0", classLoader);
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(
                    imageWrapper, "I", android.hardware.HardwareBuffer.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (activeConversion.get() == null) return;
                        Object imageWrapper = param.thisObject;
                        Object buffer = param.args.length > 0 ? param.args[0] : null;
                        int error = drainGlErrors();
                        int[] frames = conversionFrames.get(activeConversion.get());
                        Log.i(TAG, "live YUV eglimage attach"
                            + ", converter=" + idOf(activeConversion.get())
                            + ", frame=" + (frames == null ? 0 : frames[0] + 1)
                            + ", buffer=" + describeBuffer(buffer)
                            + ", bufferUsage=" + describeBufferUsage(buffer)
                            + ", result=" + param.getResult()
                            + ", m0Target=" + safeCall(imageWrapper, "f")
                            + ", m0Texture=" + safeCall(imageWrapper, "d")
                            + ", err=0x" + Integer.toHexString(error));
                    }
                });
            Log.i(TAG, "live YUV eglimage probe installed");
        } catch (Throwable error) {
            Log.e(TAG, "live YUV eglimage probe failed", error);
        }
    }

    private static int drainGlErrors() {
        int first = 0;
        for (int i = 0; i < 8; i++) {
            int error = GLES20.glGetError();
            if (error == 0) break;
            if (first == 0) first = error;
        }
        return first;
    }

    /*
     * Per-converter GL state snapshot: the source object's reported target (f()) and
     * texture id (d()), the shader program validity, the VAO, and the current EGL
     * context. Comparing a healthy (direct XPAN) converter against a post-GR one shows
     * which link carries the wrong target into the binds.
     */
    private static void dumpConversionState(Object converter, int frame) {
        Object source = getField(converter, "n");
        Object context = getField(converter, "g");
        Object program = getField(converter, "d");
        Object vertexArrays = getField(converter, "a");
        Object uploadTexture = getField(converter, "w");
        Log.i(TAG, "live YUV conv state"
            + ", id=" + idOf(converter)
            + ", frame=" + frame
            + ", source=" + describeGlObject(source)
            + ", context=" + describeGlObject(context)
            + ", program=" + (program == null ? "null"
                : idOf(program) + ", valid=" + safeCall(program, "g"))
            + ", vao=" + describeVao(vertexArrays)
            + ", uploadTex=" + (uploadTexture == null ? "null"
                : idOf(uploadTexture) + "/f=" + safeCall(uploadTexture, "f"))
            + ", eglCtx=" + nativeHandle(EGL14.eglGetCurrentContext())
            + ", eglSurface=" + nativeHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
            + ", eglDisplay=" + nativeHandle(EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY))
            + ", onThread=" + (Thread.currentThread() == getField(converter, "c")));
        loggedStates.put(converter, Boolean.TRUE);
    }

    private static String nativeHandle(Object eglObject) {
        if (eglObject == null) return "null";
        try {
            Object handle = XposedHelpers.callMethod(eglObject, "getNativeHandle");
            return "0x" + Long.toHexString(((Number) handle).longValue());
        } catch (Throwable error) {
            return "id:" + idOf(eglObject);
        }
    }

    private static String describeGlObject(Object value) {
        if (value == null) return "null";
        return idOf(value) + "/" + shortName(value.getClass().getName())
            + "/f=" + safeCall(value, "f") + "/d=" + safeCall(value, "d");
    }

    private static String describeVao(Object arrays) {
        if (!(arrays instanceof int[]) || ((int[]) arrays).length == 0) return "n/a";
        int id = ((int[]) arrays)[0];
        return id + "/valid=" + GLES30.glIsVertexArray(id);
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /*
     * Trim a StackTrace to the frames between the bind wrapper and its caller: the first
     * frame is inside the diagnostic, the next few identify the exact call site inside the
     * conversion (ch.e.b offset vs a different method entirely).
     */
    private static String trimStack(StackTraceElement[] trace) {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement element : trace) {
            String name = element.getClassName();
            if (name.startsWith(GrMasterLiveOutputDiagnostic.class.getName())
                || name.startsWith("de.robv.android.xposed")
                || name.startsWith("local.jiege.hook.common")) continue;
            if (kept++ > 0) out.append(" <- ");
            out.append(shortName(name)).append('.').append(element.getMethodName())
                .append(':').append(element.getLineNumber());
            if (kept >= 8) break;
        }
        return out.toString();
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object safeCall(Object target, String method) {
        if (target == null) return "n/a";
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable error) {
            return "err:" + shortName(error.getClass().getName());
        }
    }

    /*
     * ia.y1.c(II)I is the app's glBindTexture wrapper (binds, then reads the error via
     * ia.y1.a and returns it). r431 proved real binds return 0 — the reported error is
     * the one queued by an earlier call of the same frame. With the drains at b() entry
     * and m0.I exit, an error surfacing at the FIRST bind checkpoint localizes the
     * culprit to glFramebufferTexture2D / FBO setup (between m0.I and the binds), and
     * one surfacing at a LATER checkpoint localizes it to the draw. Rate-limit the
     * failed-bind logs per converter.
     */
    private static void hookGlBindTexture(ClassLoader classLoader) {
        try {
            Class<?> wrapper = XposedHelpers.findClass("ia.y1", classLoader);
            XposedBridge.hookMethod(
                XposedHelpers.findMethodExact(wrapper, "c", int.class, int.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object converter = activeConversion.get();
                        if (converter == null) return;
                        int target = (Integer) param.args[0];
                        int texture = (Integer) param.args[1];
                        int error = param.getResult() instanceof Integer
                            ? (Integer) param.getResult() : -1;
                        if (error == 0) return;
                        int[] counts = errorFrameCounts.get(converter);
                        if (counts == null) {
                            counts = new int[1];
                            errorFrameCounts.put(converter, counts);
                        }
                        counts[0]++;
                        if (counts[0] > 20 && counts[0] % 50 != 0) return;
                        int[] frames = conversionFrames.get(converter);
                        Object uploadTexture = getField(converter, "w");
                        Log.i(TAG, "live YUV bind reported error"
                            + ", converter=" + idOf(converter)
                            + ", frame=" + (frames == null ? 0 : frames[0] + 1)
                            + ", target=0x" + Integer.toHexString(target)
                            + ", texture=" + texture
                            + ", err=" + error
                            + (texture == 0 ? " (upload-tex empty)" : " (bind)")
                            + ", errorCount=" + counts[0]
                            + ", fboStatus=0x" + Integer.toHexString(
                                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
                            + ", isTexture=" + GLES20.glIsTexture(texture)
                            + ", uploadTex=" + describeGlObject(uploadTexture));
                        if (counts[0] <= 3) {
                            Log.i(TAG, "live YUV bind error stack, converter="
                                + idOf(converter) + ", stack="
                                + trimStack(new Throwable().getStackTrace()));
                        }
                    }
                });
            Log.i(TAG, "live YUV bind probe installed");
        } catch (Throwable error) {
            Log.e(TAG, "live YUV bind probe failed", error);
        }
    }

    /*
     * eh.c0 is the live-photo encoder; B8/I8/k1/C8 consume the per-capture ImageData whose
     * fields carry the converter handle and buffer. Log each feed method once per ImageData
     * so the encoder side of the chain is visible next to the converter side.
     */
    private static void hookEncoderFeed(ClassLoader classLoader) {
        Class<?> encoder;
        Class<?> imageData;
        try {
            encoder = XposedHelpers.findClass("eh.c0", classLoader);
            imageData = XposedHelpers.findClass(
                "com.oplus.camera.feature.livephoto.data.ImageData", classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "encoder feed classes unavailable", error);
            return;
        }
        for (final String method : new String[] {"B8", "I8", "k1", "C8"}) {
            try {
                XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(encoder, method, imageData, boolean.class),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            logEncoderFeed(method, param.thisObject, param.args[0]);
                        }
                    });
            } catch (Throwable methodError) {
                try {
                    XposedBridge.hookAllMethods(encoder, method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length < 1) return;
                            logEncoderFeed(method, param.thisObject, param.args[0]);
                        }
                    });
                } catch (Throwable allError) {
                    Log.e(TAG, "encoder feed hook failed, method=" + method, allError);
                }
            }
        }
    }

    private static void logEncoderFeed(String method, Object encoder, Object image) {
        if (image == null) return;
        if (loggedEncoderFeeds.put(image, Boolean.TRUE) != null) return;
        Log.i(TAG, "live encoder feed"
            + ", method=" + method
            + ", encoder=" + idOf(encoder)
            + ", image=" + idOf(image)
            + ", handle=" + longField(image, "l")
            + ", threw=false");
    }

    private static String longField(Object target, String name) {
        if (target == null) return "null";
        try {
            return "0x" + Long.toHexString(
                XposedHelpers.getLongField(target, name));
        } catch (Throwable error) {
            return "n/a";
        }
    }

    private static String describeBuffer(Object buffer) {
        if (buffer == null) return "null";
        try {
            return XposedHelpers.callMethod(buffer, "getWidth") + "x"
                + XposedHelpers.callMethod(buffer, "getHeight");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String describeBufferFormat(Object buffer) {
        if (buffer == null) return "n/a";
        try {
            return String.valueOf(XposedHelpers.callMethod(buffer, "getFormat"));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String describeBufferUsage(Object buffer) {
        if (buffer == null) return "n/a";
        try {
            Object usage = XposedHelpers.callMethod(buffer, "getUsage");
            return "0x" + Long.toHexString(((Number) usage).longValue());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String idOf(Object value) {
        return value == null
            ? "null"
            : Integer.toHexString(System.identityHashCode(value));
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
