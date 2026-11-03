package local.jiege.hook.aicomposition;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Diagnostic: what the inspiration composition hands to the uploader.
 *
 * <p>The camera does not upload the preview. {@code captureInspirationPreviewFrame} builds a
 * {@code CompositionRenderer}, asks it to draw the composition overlay into {@code mComposTexture},
 * then reads that framebuffer back with {@code glReadPixelsToBitmap} and posts the bitmap to
 * {@code InspirationSmallImages}. The renderer is backed by
 * {@code libai_camera_composition_lite2.so}, which this build does not ship, so the draw is a no-op
 * on a texture the caller already cleared with {@code glClearColor(0,0,0,0)} - the readback is then
 * a fully transparent black frame, and the service answers {@code 3111} ("no person detected")
 * for every scene.
 *
 * <p>This probe proves or disproves that without touching the frame: it hooks the upload method
 * ({@code vb.c3.a4(Bitmap,int,boolean)}), counts the pixels and drops a PNG next to the camera's
 * own files. Nothing is replaced, so the feature behaves exactly as stock while it runs.
 *
 * <p>{@code persist.omnicam.compdump=0} disarms it at runtime; without the property it arms on the
 * next camera start. The PNGs land in {@code files/jiege/comp/} of the camera process
 * ({@code /data/user/0/com.oplus.camera/...}), which needs root to pull but no storage permission.
 */
final class CompositionFrameProbe {
    private static final String TAG = "CompFrame";
    /** Camera-side class that owns captureInspirationPreviewFrame and its upload step. */
    private static final String TARGET = "vb.c3";
    private static final String PROP_SWITCH = "persist.omnicam.compdump";
    /** The composition panel retries on its own; a handful of frames is plenty to read. */
    private static final int LIMIT = 40;
    /** 768x1024 is the normal size; refuse anything wildly larger so a bug cannot eat the heap. */
    private static final int MAX_PIXELS = 6 * 1024 * 1024;

    private static int seq;
    private static int previewTraced;
    private static String dirFailure;

    private CompositionFrameProbe() {}

    static void install(ClassLoader classLoader) {
        if ("0".equals(prop(PROP_SWITCH, ""))) {
            Log.i(TAG, "probe disabled by " + PROP_SWITCH);
            return;
        }
        Class<?> target;
        try {
            target = XposedHelpers.findClass(TARGET, classLoader);
        } catch (Throwable error) {
            Log.i(TAG, TARGET + " not in this build, probe skipped");
            return;
        }

        // The upload step carries the frame as (Bitmap, orientation, something). Matching the
        // signature instead of the name keeps this working when the obfuscator renumbers methods.
        int hooked = 0;
        for (Method method : target.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || types[0] != Bitmap.class) continue;
            XposedBridge.hookMethod(method, UPLOAD);
            if (uploadMethod == null) uploadMethod = method;
            hooked++;
        }
        Log.i(TAG, hooked > 0 ? "frame probe armed on " + hooked + " bitmap method(s)" : "no upload method in " + TARGET);

        // Snapshot the capture entry once: its arguments are the real preview texture and the
        // renderer, i.e. everything a later "draw the live frame instead" fix would need.
        hookPreview(target);
        hookTrail(target);
        hookJob(classLoader);
        hookSwitch(target, classLoader);
    }

    /**
     * The composition switch. It branches on the saved preference {@code n9.d.Q}: only when that
     * reads "on" does it post the runnable that raises the frame gate (W1). This build never
     * writes it - the camera has no settings row for the feature, so it stays "off" and every tap
     * takes the "enter the mode" branch instead, which is why the panel is idle forever.
     */
    private static void hookSwitch(Class<?> target, ClassLoader classLoader) {
        try {
            Method method = target.getDeclaredMethod("K3", boolean.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // Resolved on the first tap, never at install time: DataManager pulls the
                    // camera's whole data layer up and must not be woken mid-startup.
                    if (!switchResolved) {
                        switchResolved = true;
                        switchKey = compositionKey(classLoader);
                        switchManager = dataManager(classLoader);
                        Log.i(TAG, "switch state resolved, key=" + switchKey
                            + " manager=" + (switchManager != null));
                    }
                    String pref = "?";
                    if (switchKey != null && switchManager != null) {
                        try {
                            pref = String.valueOf(XposedHelpers.callMethod(switchManager, "b", switchKey, "off"));
                            if (!"0".equals(prop("persist.omnicam.comppref", "1"))) {
                                XposedHelpers.callMethod(switchManager, "g", switchKey, "on");
                            }
                        } catch (Throwable error) {
                            pref = "read failed: " + error;
                        }
                    }
                    Log.i(TAG, "switch click arg=" + param.args[0] + " pref=" + pref
                        + " y2=" + intField(param.thisObject, "y2")
                        + " m2=" + bool(param.thisObject, "m2")
                        + " l2=" + bool(param.thisObject, "l2")
                        + " Q1=" + bool(param.thisObject, "Q1")
                        + " W1=" + bool(param.thisObject, "W1"));
                    push(param.thisObject);
                }
            });
            Log.i(TAG, "switch probe armed on vb.c3.K3");
        } catch (Throwable error) {
            Log.i(TAG, "K3 not in this build, switch probe skipped");
        }
    }

    private static volatile boolean switchResolved;
    private static Object switchKey;
    private static Object switchManager;
    private static Method uploadMethod;
    private static long lastPush;

    /**
     * Pushes a frame on demand.
     *
     * <p>The camera only captures a preview frame while it believes a request is pending, and on
     * this build nothing ever raises that flag, so tapping the panel produces no upload at all -
     * which is why the card stays on "no recommendation" however long it is left running. Grabbing
     * the screen and calling the uploader directly skips that gate.
     */
    private static void push(Object host) {
        if (uploadMethod == null) return;
        if ("0".equals(prop("persist.omnicam.comppush", "1"))) return;
        long now = System.currentTimeMillis();
        if (now - lastPush < 4000) return;
        lastPush = now;
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(600);
                Bitmap shot = grab(host, 768, 1024);
                if (shot == null) {
                    Log.i(TAG, "push: no frame");
                    return;
                }
                uploadMethod.invoke(host, shot, 0, false);
                Log.i(TAG, "push: uploaded " + shot.getWidth() + "x" + shot.getHeight());
            } catch (Throwable error) {
                Log.e(TAG, "push failed", error);
            }
        }, "CompPush");
        thread.setDaemon(true);
        thread.start();
    }

    private static Object compositionKey(ClassLoader classLoader) {
        try {
            Class<?> holder = XposedHelpers.findClass("n9.d", classLoader);
            return XposedHelpers.getStaticObjectField(holder, "Q");
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object dataManager(ClassLoader classLoader) {
        try {
            Class<?> type = XposedHelpers.findClass("com.oplus.camera.data.DataManager", classLoader);
            return XposedHelpers.callStaticMethod(type, "getInstance");
        } catch (Throwable error) {
            return null;
        }
    }

    private static int intField(Object instance, String name) {
        try {
            java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(instance);
        } catch (Throwable error) {
            return -999;
        }
    }

    /**
     * The capture entry runs but the upload method never does, so the next link is the task it
     * posts: {@code ThreadManager.p("InspirationSmallImages", vb.z1(...))}. Its fields say whether
     * the frame was ever produced - a null bitmap means nothing was uploaded at all, which is a
     * different problem from uploading a black one.
     */
    private static void hookJob(ClassLoader classLoader) {
        try {
            Class<?> job = XposedHelpers.findClass("vb.z1", classLoader);
            XposedBridge.hookAllConstructors(job, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (jobLines.get() >= LIMIT) return;
                    jobLines.incrementAndGet();
                    Log.i(TAG, "job created " + fields(param.thisObject));
                }
            });
            XposedBridge.hookAllMethods(job, "run", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (jobLines.get() >= LIMIT) return;
                    jobLines.incrementAndGet();
                    Log.i(TAG, "job run " + fields(param.thisObject));
                }
            });
            Log.i(TAG, "job probe armed on vb.z1");
        } catch (Throwable error) {
            Log.i(TAG, "vb.z1 not in this build, job probe skipped");
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger jobLines =
        new java.util.concurrent.atomic.AtomicInteger();

    private static String fields(Object instance) {
        if (instance == null) return "null";
        StringBuilder line = new StringBuilder(instance.getClass().getName()).append('{');
        for (java.lang.reflect.Field field : instance.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(instance);
            } catch (Throwable error) {
                continue;
            }
            if (line.charAt(line.length() - 1) != '{') line.append(", ");
            line.append(field.getName()).append('=');
            if (value instanceof Bitmap) {
                Bitmap bitmap = (Bitmap) value;
                line.append(bitmap.getWidth()).append('x').append(bitmap.getHeight());
            } else {
                line.append(value);
            }
        }
        return line.append('}').toString();
    }

    /**
     * The upload never showed up on the first run, so the second question is whether the capture
     * entry runs at all and which of its neighbours the panel calls. Obfuscated members are short
     * names; hooking only those keeps the camera's drawing path out of it and the log to one line
     * per distinct method.
     */
    private static void hookTrail(Class<?> target) {
        try {
            int hooked = 0;
            for (Method method : target.getDeclaredMethods()) {
                if (method.getName().length() > 3) continue;
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(method, TRAIL);
                    hooked++;
                } catch (Throwable ignored) {
                    // a method that cannot be hooked is not worth the noise
                }
            }
            Log.i(TAG, "call trail on " + hooked + " short-named method(s)");
        } catch (Throwable error) {
            Log.e(TAG, "call trail unavailable", error);
        }
    }

    private static final java.util.Set<String> seenMethods = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger trailLines = new java.util.concurrent.atomic.AtomicInteger();
    private static final int TRAIL_LIMIT = 300;

    private static final XC_MethodHook TRAIL = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (trailLines.get() >= TRAIL_LIMIT) return;
            String name = ((Method) param.method).getName() + "/" + param.args.length;
            if (!seenMethods.add(name)) return;
            if (trailLines.incrementAndGet() > TRAIL_LIMIT) return;
            Log.i(TAG, "trail " + name);
        }
    };

    private static void hookPreview(Class<?> target) {
        try {
            int hooked = 0;
            for (Method method : target.getDeclaredMethods()) {
                if (!"F2".equals(method.getName())) continue;
                XposedBridge.hookMethod(method, PREVIEW);
                hooked++;
            }
            if (hooked > 0) Log.i(TAG, "preview entry traced on " + hooked + " method(s)");
        } catch (Throwable error) {
            Log.e(TAG, "preview trace unavailable", error);
        }
    }

    private static final XC_MethodHook PREVIEW = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            int index;
            synchronized (CompositionFrameProbe.class) {
                if (previewTraced >= LIMIT) return;
                index = ++previewTraced;
            }
            // 0x0151 of the method returns right away unless W1 is set: it is the "a composition
            // request is pending, take a frame now" flag, and on this build nothing raises it.
            boolean armed = bool(param.thisObject, "W1");
            String extra = "";
            // One frame per request: forcing every preview pass floods the service with uploads
            // and it starts answering 3110 instead of a result.
            if (!armed && force() && System.currentTimeMillis() - lastForce > 3000) {
                lastForce = System.currentTimeMillis();
                extra = setBool(param.thisObject, "W1", true) ? " forced" : " force failed";
            }
            StringBuilder line = new StringBuilder("capture #" + index + " W1=" + armed + extra + " (");
            for (int i = 0; i < param.args.length; i++) {
                if (i > 0) line.append(", ");
                Object arg = param.args[i];
                line.append(arg == null ? "null" : arg.getClass().getSimpleName());
            }
            Log.i(TAG, line.append(") host=").append(param.thisObject.getClass().getName()).toString());

            // The upload is fed by a framebuffer readback, but the preview itself arrives as a
            // HardwareBuffer in these arguments. Wrapping it gives the real frame without going
            // through the renderer, which is what the uploader needs anyway.
            probeBuffers(param.args);
        }
    };

    private static void probeBuffers(Object[] args) {
        if (!argsDescribed) {
            argsDescribed = true;
            StringBuilder line = new StringBuilder("args:");
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                line.append(" [").append(i).append("]=");
                if (arg == null) {
                    line.append("null");
                    continue;
                }
                line.append(arg.getClass().getName()).append('{');
                for (java.lang.reflect.Field field : arg.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    line.append(field.getType().getSimpleName()).append(' ').append(field.getName()).append(';');
                }
                line.append('}');
            }
            Log.i(TAG, line.toString());
        }
        if (bufferLines.get() >= LIMIT) return;
        for (Object arg : args) scan(arg, "arg", 0);
    }

    /** The preview buffer sits two fields down (bb.h -> CameraPreviewResult), so look twice. */
    private static void scan(Object object, String path, int depth) {
        if (object == null || depth > 2 || bufferLines.get() >= LIMIT) return;
        if (object instanceof android.hardware.HardwareBuffer) {
            reportBuffer(path, (android.hardware.HardwareBuffer) object);
            return;
        }
        Class<?> type = object.getClass();
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("android.opengl")) return;
        if (name.startsWith("android.") && !name.startsWith("android.hardware")) return;
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Class<?> fieldType = field.getType();
            if (fieldType.isPrimitive() || fieldType.isArray()) continue;
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(object);
            } catch (Throwable error) {
                continue;
            }
            if (value == null) continue;
            String child = path + "." + field.getName();
            if (value instanceof android.hardware.HardwareBuffer) {
                reportBuffer(child, (android.hardware.HardwareBuffer) value);
            } else if (depth < 2 && (fieldType.getName().startsWith("com.oplus")
                || fieldType.getName().startsWith("bb."))) {
                scan(value, child, depth + 1);
            }
        }
    }

    private static boolean argsDescribed;
    private static long lastForce;

    private static final java.util.concurrent.atomic.AtomicInteger bufferLines =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void reportBuffer(String label, android.hardware.HardwareBuffer buffer) {
        if (buffer == null || buffer.isClosed()) return;
        if (bufferLines.get() >= LIMIT) return;
        int index = bufferLines.incrementAndGet();
        Bitmap wrapped = null;
        try {
            java.lang.reflect.Method wrap = Bitmap.class.getMethod("wrapHardwareBuffer",
                android.hardware.HardwareBuffer.class, android.graphics.ColorSpace.class);
            wrapped = (Bitmap) wrap.invoke(null, buffer, android.graphics.ColorSpace.get(
                android.graphics.ColorSpace.Named.SRGB));
        } catch (Throwable error) {
            Log.e(TAG, "buffer " + index + " " + label + " wrap failed", error);
            return;
        }
        if (wrapped == null) {
            Log.i(TAG, "buffer " + index + " " + label + " wrap returned null");
            return;
        }
        Bitmap soft;
        try {
            soft = wrapped.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable error) {
            Log.e(TAG, "buffer " + index + " " + label + " copy failed", error);
            return;
        }
        if (soft == null) {
            Log.i(TAG, "buffer " + index + " " + label + " copy returned null");
            return;
        }
        report(index, soft, 0, true, "buffer " + label);
        soft.recycle();
    }

    private static boolean bool(Object instance, String name) {
        try {
            java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(instance);
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean setBool(Object instance, String name, boolean value) {
        try {
            java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(instance, value);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** persist.omnicam.compforce=0 keeps the flag alone; anything else lets the probe raise it. */
    private static boolean force() {
        return !"0".equals(prop("persist.omnicam.compforce", "1"));
    }

    private static final XC_MethodHook UPLOAD = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (seq >= LIMIT) return;
            int index;
            synchronized (CompositionFrameProbe.class) {
                index = ++seq;
            }
            if (!(param.args[0] instanceof Bitmap)) {
                Log.i(TAG, "upload #" + index + " frame is " + param.args[0]);
                return;
            }
            Bitmap frame = (Bitmap) param.args[0];
            int orientation = param.args[1] instanceof Integer ? (Integer) param.args[1] : -1;
            boolean flag = Boolean.TRUE.equals(param.args[2]);

            // The readback carries a valid luma but scrambled chroma (the renderer samples the
            // preview as a plain 2D texture), so the service cannot find a subject in it. Hand it
            // the screen instead: same moment, correct colour, no renderer involved.
            if (!"0".equals(prop("persist.omnicam.compshot", "1"))) {
                Bitmap shot = grab(param.thisObject, frame.getWidth(), frame.getHeight());
                if (shot != null) {
                    param.args[0] = shot;
                    frame = shot;
                    Log.i(TAG, "upload #" + index + " replaced with screen");
                } else {
                    Log.i(TAG, "upload #" + index + " screen grab failed, keeping readback");
                }
            }

            try {
                report(index, frame, orientation, flag, "upload");
            } catch (Throwable error) {
                Log.e(TAG, "frame " + index + " could not be read", error);
            }
        }
    };

    /** The presenter extends the camera's base class, whose field {@code f} is the Activity. */
    private static android.app.Activity activityOf(Object host) {
        for (Class<?> type = host.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!android.app.Activity.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(host);
                    if (value instanceof android.app.Activity) return (android.app.Activity) value;
                } catch (Throwable ignored) {
                    // try the next field
                }
            }
        }
        return null;
    }

    private static Bitmap grab(Object host, int width, int height) {
        android.app.Activity activity = activityOf(host);
        if (activity == null || width <= 0 || height <= 0) return null;
        // The preview lives in its own layer; a Window copy comes back with the UI only and a
        // black viewfinder, so grab the surface itself.
        android.view.SurfaceView surface = findSurface(activity.getWindow().getDecorView());
        Bitmap raw = surface != null ? copySurface(surface) : null;
        if (raw == null) {
            raw = copyWindow(activity, width, height);
            if (raw == null) return null;
        }
        Bitmap cropped = cropToRatio(raw, width, height);
        if (cropped != raw) raw.recycle();
        if (cropped.getWidth() == width && cropped.getHeight() == height) return cropped;
        Bitmap scaled;
        try {
            scaled = Bitmap.createScaledBitmap(cropped, width, height, true);
        } catch (Throwable error) {
            return cropped;
        }
        if (scaled != cropped) cropped.recycle();
        return scaled;
    }

    /**
     * The surface spans the whole screen and letterboxes the viewfinder, so the copy carries solid
     * black bars. The service reads the bars as part of the frame, so cut to the target aspect
     * first and only then scale.
     */
    private static Bitmap cropToRatio(Bitmap source, int width, int height) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) return source;
        float target = (float) width / height;
        float current = (float) sourceWidth / sourceHeight;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if (Math.abs(current - target) < 0.01f) return source;
        if (current > target) {
            cropWidth = Math.round(sourceHeight * target);
        } else {
            cropHeight = Math.round(sourceWidth / target);
        }
        if (cropWidth <= 0 || cropHeight <= 0) return source;
        if (cropWidth >= sourceWidth && cropHeight >= sourceHeight) return source;
        int left = (sourceWidth - cropWidth) / 2;
        int top = (sourceHeight - cropHeight) / 2;
        try {
            return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        } catch (Throwable error) {
            return source;
        }
    }

    private static android.view.SurfaceView findSurface(android.view.View view) {
        if (view instanceof android.view.SurfaceView) {
            android.view.SurfaceView surface = (android.view.SurfaceView) view;
            if (surface.getWidth() > 0 && surface.getHeight() > 0) return surface;
            return null;
        }
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        android.view.SurfaceView best = null;
        int bestArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.SurfaceView found = findSurface(group.getChildAt(i));
            if (found == null) continue;
            int area = found.getWidth() * found.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = found;
            }
        }
        return best;
    }

    private static Bitmap copySurface(android.view.SurfaceView surface) {
        int width = surface.getWidth();
        int height = surface.getHeight();
        if (width <= 0 || height <= 0) return null;
        Bitmap target;
        try {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable error) {
            return null;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[1];
        try {
            android.view.PixelCopy.request(surface, target, result -> {
                code[0] = result;
                latch.countDown();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable error) {
            Log.i(TAG, "surface copy request failed: " + error);
            target.recycle();
            return null;
        }
        if (!await(latch)) {
            target.recycle();
            return null;
        }
        if (code[0] != android.view.PixelCopy.SUCCESS) {
            Log.i(TAG, "surface copy result " + code[0] + " (" + width + "x" + height + ")");
            target.recycle();
            return null;
        }
        return target;
    }

    private static Bitmap copyWindow(android.app.Activity activity, int width, int height) {
        Bitmap target;
        try {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable error) {
            return null;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[1];
        try {
            android.view.PixelCopy.request(activity.getWindow(), target, result -> {
                code[0] = result;
                latch.countDown();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable error) {
            Log.e(TAG, "pixel copy request failed", error);
            target.recycle();
            return null;
        }
        if (!await(latch)) {
            target.recycle();
            return null;
        }
        if (code[0] != android.view.PixelCopy.SUCCESS) {
            Log.i(TAG, "pixel copy result " + code[0]);
            target.recycle();
            return null;
        }
        return target;
    }

    private static boolean await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.i(TAG, "pixel copy timed out");
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void report(int index, Bitmap frame, int orientation, boolean flag, String tag) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        String head = tag + " " + index + " " + width + "x" + height + " orient=" + orientation + " flag=" + flag;

        long pixels = (long) width * height;
        if (pixels <= 0 || pixels > MAX_PIXELS) {
            Log.i(TAG, head + " skipped (size)");
            return;
        }

        // getPixels() refuses a HARDWARE bitmap; a software copy both reads and compresses cleanly.
        Bitmap soft = frame;
        try {
            Bitmap copy = frame.getConfig() == Bitmap.Config.HARDWARE
                ? frame.copy(Bitmap.Config.ARGB_8888, false) : null;
            if (copy != null) soft = copy;
        } catch (Throwable ignored) {
            // fall through with the original; getPixels() below reports the failure instead
        }

        int[] argb = new int[(int) pixels];
        long opaque = 0;
        long lit = 0;
        long sum = 0;
        int peak = 0;
        try {
            soft.getPixels(argb, 0, width, 0, 0, width, height);
            for (int color : argb) {
                int alpha = (color >>> 24) & 0xff;
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                if (alpha != 0) opaque++;
                if ((red | green | blue) != 0) lit++;
                int luma = (red * 77 + green * 151 + blue * 28) >> 8;
                sum += luma;
                if (luma > peak) peak = luma;
            }
        } finally {
            if (soft != frame) soft.recycle();
        }

        String saved = save(tag, index, width, height, argb);
        Log.i(TAG, head
            + " opaque=" + opaque + "/" + pixels
            + " lit=" + lit + "/" + pixels
            + " peak=" + peak
            + " mean=" + (pixels == 0 ? 0 : sum / pixels)
            + (saved == null ? " not saved" : " saved=" + saved));
    }

    private static String save(String tag, int index, int width, int height, int[] argb) {
        File dir = outputDir();
        if (dir == null) return null;
        File file = new File(dir, tag + "_" + index + "_" + width + "x" + height + ".png");
        try {
            Bitmap out = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            if (out == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(argb.length > 0 ? argb.length / 4 : 65536);
            out.compress(Bitmap.CompressFormat.PNG, 100, buffer);
            out.recycle();
            FileOutputStream stream = new FileOutputStream(file);
            try {
                stream.write(buffer.toByteArray());
            } finally {
                stream.close();
            }
            return file.getAbsolutePath();
        } catch (Throwable error) {
            Log.e(TAG, "could not write " + file.getName(), error);
            return null;
        }
    }

    /** The camera's own files dir; /data/local/tmp is not writable for the app uid. */
    private static File outputDir() {
        File base = null;
        String source = "absolute";
        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) {
                base = application.getFilesDir();
                source = "app";
            }
        } catch (Throwable error) {
            source = "app failed: " + error;
        }
        if (base == null) base = new File("/data/user/0/com.oplus.camera/files");
        File dir = new File(base, "jiege/comp");
        if (dir.isDirectory()) return dir;
        if (dir.mkdirs()) return dir;
        if (!dir.getAbsolutePath().equals(dirFailure)) {
            dirFailure = dir.getAbsolutePath();
            Log.i(TAG, "cannot create " + dirFailure + " (dir " + source + ", parent writable "
                + dir.getParentFile().canWrite() + ")");
        }
        return null;
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, key, fallback);
            return String.valueOf(value);
        } catch (Throwable error) {
            return fallback;
        }
    }
}
