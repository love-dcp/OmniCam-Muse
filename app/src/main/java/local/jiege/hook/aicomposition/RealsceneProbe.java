package local.jiege.hook.aicomposition;

import android.hardware.HardwareBuffer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Diagnostic probe for the camera's own (non-cloud) composition pipeline.
 *
 * The shipped camera still carries the whole real-scene path:
 *
 *   vb.c3.F2 (captureInspirationPreviewFrame)
 *     -> new wb.m(r, previewBuffer, afterViewBuffer, extraBuffer, ..., callback)
 *     -> wb.r.f(...)                     11 args, the "run composition" worker
 *     -> new wb.p(...)  -> thread
 *     -> OplusAICompositionHelper.runCompositionStreaming(buffer, callback)
 *
 * runCompositionStreaming only runs when wb.r.w && wb.r.x are both true; otherwise the worker
 * pushes one of two hardcoded demo JSON strings through callback.onEvent(1|2, json). Both flags
 * live behind build config, so on a retail unit the path is silent.
 *
 * This probe only observes: which of the steps run, what the flags say, and which JSON the
 * callback would receive. Nothing is replaced.
 */
public final class RealsceneProbe {
    private static final String TAG = "CompReal";
    private static final String PROP = "persist.omnicam.realprobe";
    private static final int LIMIT = 12;

    private static volatile boolean reportedMissing;
    private static int frames;
    private static int streams;
    private static int events;
    private static int frameCalls;
    private static int injects;
    private static int effects;
    private static int fails;
    private static boolean helperDumped;
    private static boolean trackerInited;
    private static final java.util.concurrent.atomic.AtomicBoolean seed =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean pinned =
        new java.util.concurrent.atomic.AtomicBoolean();

    private RealsceneProbe() {}

    static void install(ClassLoader classLoader) {
        if ("0".equals(prop(PROP, ""))) return;
        if ("1".equals(prop("persist.omnicam.realself", ""))) selfCheck();
        Log.guard(TAG, "sdk", () -> probeSdk(classLoader));
        Log.guard(TAG, "worker", () -> hookWorker(classLoader));
        Log.guard(TAG, "context", AiUnitBridge::capture);
        Log.guard(TAG, "streaming", () -> hookStreaming(classLoader));
        Log.guard(TAG, "callback", () -> hookCallback(classLoader));
        Log.guard(TAG, "frame", () -> hookFrame(classLoader));
        Log.guard(TAG, "queue", () -> hookQueue(classLoader));
        Log.guard(TAG, "mode", () -> hookMode(classLoader));
        Log.guard(TAG, "gates", () -> hookGates(classLoader));
        Log.guard(TAG, "button", () -> hookButton(classLoader));
        Log.guard(TAG, "rearm", RealsceneProbe::startRearm);
        Log.guard(TAG, "shutter", RealsceneProbe::hookShutter);
        Log.guard(TAG, "effect", () -> hookEffectTexture(classLoader));
        Log.guard(TAG, "fail", () -> hookFailure(classLoader));
        Log.guard(TAG, "landing", () -> hookAnswerLanding(classLoader));
        Log.guard(TAG, "aiunit", () -> hookAiunitAvailability(classLoader));
        Log.guard(TAG, "trackstatus", () -> hookTrackStatus(classLoader));
        Log.guard(TAG, "zoomskip", () -> hookZoomSkip(classLoader));
        Log.guard(TAG, "croptrace", () -> hookCropTrace(classLoader));
        Log.guard(TAG, "touch", RealsceneProbe::hookTouch);
        Log.guard(TAG, "pause", RealsceneProbe::hookPause);
        Log.i(TAG, "subject following " + (following() ? "on" : "off"));
        Log.i(TAG, "vision model " + (CloudComposer.enabled() ? "on" : "off")
            + ", aesthetics scorer " + (AestheticScorer.enabled() ? "on" : "off"));
    }

    /**
     * Answers one question the whole rewrite hinges on: does this build of the camera actually
     * carry the composition engine class, and can the helper that owns it even be constructed?
     *
     * OplusAICompositionHelper's constructor does a bare {@code new OplusAIComposition()} with no
     * try/catch and no dynamic loading in front of it, and that class has no class_def in any of
     * the 33 dex of OplusCamera.apk. If it is really absent the constructor throws
     * NoClassDefFoundError, wb.r.a stays null, and runCompositionStreaming can never be invoked --
     * which is exactly the state our hooks have been reporting.
     */
    private static void probeSdk(ClassLoader classLoader) {
        String[] names = {
            "com.oplus.camera.aicomposition.OplusAIComposition",
            "com.oplus.camera.aicomposition.OplusAIComposition$CompositionStreamCallback",
            "com.oplus.ocs.camera.OplusAICompositionHelper",
        };
        for (String name : names) {
            Class<?> c = XposedHelpers.findClassIfExists(name, classLoader);
            if (c == null) {
                Log.i(TAG, "sdk " + name + " = MISSING");
                continue;
            }
            StringBuilder sb = new StringBuilder("sdk " + name + " = OK");
            try {
                Constructor<?>[] ctors = c.getDeclaredConstructors();
                sb.append(" ctors=").append(ctors.length);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().startsWith("runComposition")) continue;
                    sb.append(' ').append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(ps[i].getSimpleName());
                    }
                    sb.append("):").append(m.getReturnType().getSimpleName());
                }
            } catch (Throwable t) {
                sb.append(" describe failed: ").append(t);
            }
            Log.i(TAG, sb.toString());
        }

        Class<?> helper = XposedHelpers.findClassIfExists(
            "com.oplus.ocs.camera.OplusAICompositionHelper", classLoader);
        if (helper == null) return;
        Object instance = null;
        try {
            instance = helper.getDeclaredConstructor().newInstance();
            Log.i(TAG, "sdk helper instantiated ok");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Log.i(TAG, "sdk helper instantiation FAILED: " + cause);
        }
        if (instance != null) {
            try {
                Object inner = XposedHelpers.getObjectField(instance, "mOplusAIComposition");
                Log.i(TAG, "sdk helper.mOplusAIComposition = "
                    + (inner == null ? "null" : inner.getClass().getName()));
            } catch (Throwable t) {
                Log.i(TAG, "sdk helper field read failed: " + t);
            }
        }
    }

    /**
     * One-shot diagnostic: can this process read and load the staged library? The linker
     * folds a denied open into "library not found", so the two cases have to be told apart
     * from inside the process that is being refused.
     */
    private static void selfCheck() {
        String path = "/vendor/lib64/hw/libAITracker.so";
        try {
            java.io.File file = new java.io.File(path);
            Log.i(TAG, "self: exists=" + file.exists() + " canRead=" + file.canRead()
                + " length=" + file.length());
        } catch (Throwable error) {
            Log.i(TAG, "self: stat failed " + error);
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            byte[] head = new byte[16];
            int read = in.read(head);
            in.close();
            Log.i(TAG, "self: read " + read + " bytes, magic=" + new String(head, 0, 4));
        } catch (Throwable error) {
            Log.i(TAG, "self: read failed " + error);
        }
        try {
            System.load(path);
            Log.i(TAG, "self: System.load ok");
        } catch (Throwable error) {
            Log.i(TAG, "self: System.load failed " + error);
        }
    }

    /** vb.c3.F2 decides, per preview frame, whether the composition worker is scheduled. */
    private static void hookFrame(ClassLoader classLoader) {
        Class<?> owner = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        if (owner == null) {
            Log.i(TAG, "vb.c3 not in this build");
            return;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (!"F2".equals(method.getName()) || method.getParameterCount() != 5) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, FRAME);
            Log.i(TAG, "F2 observed");
            return;
        }
        Log.i(TAG, "F2 not found");
    }

    private static final XC_MethodHook FRAME = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Object host = param.thisObject;
            cameraHost = host;
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++frameCalls;
            }
            // Experimental: h4(false) is what the camera itself calls to enter real-scene
            // composition; F2 returns at once while l2 is false, so flip it once and watch.
            if (index == 61 && "1".equals(prop("persist.omnicam.realforce", ""))
                && !bool(host, "l2")) {
                try {
                    XposedHelpers.callMethod(host, "h4", Boolean.FALSE);
                    Log.i(TAG, "h4(false) issued, l2 now " + bool(host, "l2"));
                } catch (Throwable error) {
                    Log.e(TAG, "h4 failed", error);
                }
            }
            // The camera drops l2 as soon as one answer has been rendered, which turns the whole
            // pipeline into a single shot. Holding it up keeps a frame flowing every preview tick,
            // and that is the only way the box can follow anything — but only inside the window a
            // tap on the entry button opened, otherwise a cold start composes by itself.
            // No pinning and no re-arming: both made the camera compose on its own at launch and
            // again after every shutter. The camera decides when to ask; answering is enough.
            if (index % 60 != 1) return;
            Object state = field(host, "M");
            Log.i(TAG, "F2 #" + index
                + " W1=" + bool(host, "W1") + " t2=" + bool(host, "t2")
                + " Q1=" + bool(host, "Q1") + " y2=" + intField(host, "y2")
                + " l2=" + bool(host, "l2") + " m2=" + bool(host, "m2")
                + " N0=" + (field(host, "N0") != null)
                + " state.c=" + bool(state, "c")
                + " phase=" + phaseName(state));
        }
    };

    /** wb.m is the runnable that carries the buffers into wb.r.f. */
    private static void hookQueue(ClassLoader classLoader) {
        Class<?> worker = XposedHelpers.findClassIfExists("wb.m", classLoader);
        if (worker != null) {
            for (Method method : worker.getDeclaredMethods()) {
                if (!"run".equals(method.getName()) || method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "wb.m.run");
                    }
                });
            }
        }
        Class<?> manager = XposedHelpers.findClassIfExists("wb.r", classLoader);
        if (manager != null) {
            for (Method method : manager.getDeclaredMethods()) {
                if (!"b".equals(method.getName()) || method.getParameterCount() != 1) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object arg = param.args[0];
                        Log.i(TAG, "wb.r.b(" + (arg == null ? "null" : arg.getClass().getName()) + ")");
                    }
                });
            }
        }
        Log.i(TAG, "queue hooks done");
    }

    /**
     * The three questions F2 asks the state machine before it schedules another frame
     * (offsets 0x319, 0x32d, 0x347, 0x358 of {@code vb.c3.F2}). Once the camera has applied one
     * suggestion it answers "yes" to at least one of them and F2 returns on the spot, which is
     * why following dies after a single frame. Hooking the getters says which answer it got, and
     * {@code persist.omnicam.gateopen=1} makes them answer "no" so the loop keeps running.
     */
    private static void hookGates(ClassLoader classLoader) {
        Class<?> machine = XposedHelpers.findClassIfExists(
            "com.oplus.camera.feature.aicomposition.state.CompositionStateMachine", classLoader);
        if (machine == null) {
            Log.i(TAG, "state machine not in this build");
            return;
        }
        int hooked = 0;
        for (Method method : machine.getDeclaredMethods()) {
            String name = method.getName();
            int count = method.getParameterCount();
            Class<?>[] types = method.getParameterTypes();
            boolean apply = "c".equals(name) && count == 0 && method.getReturnType() == boolean.class;
            boolean idle = "b".equals(name) && count == 0 && method.getReturnType() == boolean.class;
            boolean zoom = "e".equals(name) && count == 1 && types[0] == float.class
                && method.getReturnType() == boolean.class;
            if (!apply && !idle && !zoom) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, gate(name + (zoom ? "(f)" : "()")));
            hooked++;
        }
        Log.i(TAG, "state gates hooked: " + hooked
            + " force=" + "1".equals(prop("persist.omnicam.gateopen", "")));
    }

    private static final java.util.concurrent.atomic.AtomicInteger gateLogs =
        new java.util.concurrent.atomic.AtomicInteger();

    private static XC_MethodHook gate(final String label) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                boolean value = Boolean.TRUE.equals(param.getResult());
                int index = gateLogs.incrementAndGet();
                if (value && (index <= 20 || index % 200 == 0)) {
                    String arg = param.args.length > 0 && param.args[0] instanceof Float
                        ? " " + param.args[0] : "";
                    Log.i(TAG, "gate " + label + arg + " -> true");
                }
                // GR mode (the one the module relabels "master") has no composition in stock, so
                // the effect-texture pipeline that captureCompositionEffectTexture needs is never
                // built. Force-opening the feature gates makes the camera draw a suggestion and
                // drive the zoom anyway, and the shutter then fails with "invalid zoom result";
                // the photo is dropped with no message and the preview stays stuck on "saving".
                // Answering no to the apply and zoom gates keeps the shutter on the ordinary path.
                // b() is idle detection, not an action, so it is left alone.
                if (value && !label.startsWith("b") && compositionBlocked(label)) {
                    param.setResult(Boolean.FALSE);
                }
                if (gateDumped.compareAndSet(false, true)) dumpMachine(param.thisObject);
            }
        };
    }

    private static final java.util.concurrent.atomic.AtomicBoolean gateDumped =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** One-shot: what the composition state machine holds, so the mode can be read from it. */
    private static void dumpMachine(Object machine) {
        Class<?> type = machine.getClass();
        Log.i(TAG, "machine " + type.getName());
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(machine);
                    String text;
                    if (value == null) text = "null";
                    else if (value instanceof String || value instanceof Number
                        || value instanceof Boolean) text = String.valueOf(value);
                    else text = value.getClass().getName();
                    Log.i(TAG, "  " + field.getName() + " = " + text);
                } catch (Throwable ignored) {
                    // inaccessible field
                }
            }
            type = type.getSuperclass();
        }
    }

    /**
     * True when this mode must not run composition at all.
     *
     * <p>GrSupport.isGrMode() is the camera's own "current mode is GR" answer, so it tracks mode
     * switches without the module having to mirror the mode list itself. Everything else -- photo,
     * portrait, night, video -- keeps whatever stock does.
     */
    /**
     * Modes that must not run composition at all.
     *
     * The general photo modes ship the whole composition pipeline, and it works there: the
     * suggestion is drawn, the zoom follows, and the shutter saves the photo. Master
     * ("professional") and GR ("gr") do not ship it. Opening the feature gates there makes the
     * camera draw a suggestion and drive the zoom anyway, and the shutter then dies in
     * captureCompositionEffectTexture -> "invalid zoom result", dropping the photo with no
     * message. Answering no to the apply and zoom gates keeps those two modes on the ordinary
     * shutter path while every other mode is left exactly as stock.
     */
    private static final java.util.Set<String> NO_COMPOSITION = new java.util.HashSet<>(
        java.util.Arrays.asList("professional", "gr"));

    private static boolean compositionBlocked(String label) {
        if ("1".equals(prop("persist.omnicam.comp.gr", "0"))) return false;
        String mode = modeName;
        if (mode == null || mode.isEmpty() || !NO_COMPOSITION.contains(mode)) return false;
        if (gateBlockedLog.compareAndSet(false, true)) {
            Log.i(TAG, "composition blocked in " + mode + " mode (" + label + ")");
        }
        return true;
    }

    /**
     * Modes that ship no composition pipeline at all, beyond the two that are merely unsafe.
     *
     * <p>Only used to decide whether a tap may <em>open</em> composition; everything else about the
     * feature keeps working as before. The list is a deny-list on purpose: mode names come from the
     * feature presenter and a mode this build invents later must not silently lose the entry.
     */
    private static final java.util.Set<String> NOT_PHOTO = new java.util.HashSet<>(
        java.util.Arrays.asList("professional", "gr", "video", "video_pro", "pro_video",
            "portrait", "panorama", "time_lapse", "slow_shutter", "night", "super_night"));

    /** Is the camera in one of the modes composition runs in? Unknown counts as yes. */
    private static boolean photoMode() {
        String mode = modeName;
        if (mode == null || mode.isEmpty()) return true;
        return !NOT_PHOTO.contains(mode);
    }

    /**
     * Current camera mode name, kept current by the feature presenter itself: "photo" and the
     * other general modes are the ones the composition pipeline supports, "professional" is the
     * mode the module relabels 大师, and "gr" is the stock GR mode. FeaturePresenter is built for
     * every mode and re-pointed through R(int, String) on a switch, so neither a poll nor a view
     * tree walk is needed here - and reading it cannot misfire the way the layout probe did.
     */
    private static volatile String modeName = "";

    private static void hookMode(ClassLoader classLoader) {
        Class<?> presenter;
        try {
            presenter = Symbols.get().cls("FeaturePresenter");
        } catch (Throwable error) {
            Log.i(TAG, "feature presenter unresolved: " + error);
            return;
        }
        if (presenter == null) return;
        XposedBridge.hookAllConstructors(presenter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                adoptMode(param.thisObject);
            }
        });
        int repointed = 0;
        for (Method method : presenter.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"R".equals(method.getName()) || types.length != 2
                || types[0] != int.class || types[1] != String.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    setMode(param.args[1] == null ? null : String.valueOf(param.args[1]));
                }
            });
            repointed++;
        }
        Log.i(TAG, "mode tracker installed (repoint " + repointed + ")");
    }

    private static void adoptMode(Object instance) {
        try {
            Object value = Symbols.get().get("FeaturePresenter.modeName", instance);
            setMode(value == null ? null : String.valueOf(value));
        } catch (Throwable ignored) {
            // presenter built but its mode field is unreadable; keep the last known name
        }
    }

    private static void setMode(String name) {
        if (name == null || name.isEmpty() || name.equals(modeName)) return;
        modeName = name;
        Log.i(TAG, "mode -> " + name);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean gateBlockedLog =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The bouncing icon is the entry button's Lottie clip. {@code setTriggered(true)} restarts it
     * from frame 0 every time the camera receives a composition result, so a box that keeps
     * updating turns the icon into a metronome. Letting one restart through every few seconds keeps
     * the hint alive without the strobe.
     */
    private static void hookButton(ClassLoader classLoader) {
        Class<?> button = XposedHelpers.findClassIfExists(
            "com.oplus.camera.feature.aicomposition.view.AICompositionEnterButton", classLoader);
        if (button == null) {
            Log.i(TAG, "enter button not in this build");
            return;
        }
        Method target = null;
        for (Method method : button.getDeclaredMethods()) {
            if ("setTriggered".equals(method.getName()) && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == boolean.class) {
                target = method;
                break;
            }
        }
        if (target == null) {
            Log.i(TAG, "setTriggered not found");
            return;
        }
        target.setAccessible(true);
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // Once the camera is asked to compose again every couple of seconds, it flips this
                // flag on and off for every attempt and the Lottie clip restarts each time. The
                // clip is decoration; the box on the viewfinder is the actual guidance, so freeze
                // the decoration entirely while following is on.
                if (!following()) return;
                int calls;
                synchronized (RealsceneProbe.class) {
                    calls = ++triggerCalls;
                }
                if (calls <= 8 || calls % 40 == 0) {
                    Log.i(TAG, "setTriggered(" + param.args[0] + ") #" + calls + " ignored");
                }
                param.setResult(null);
            }
        });
        Log.i(TAG, "enter animation frozen");

        // Following must never start on its own: pinning l2 makes the camera believe a composition
        // is already running, so a cold start would jump straight into one and a finished shot
        // would roll into the next. A tap on this button is the one honest "the user wants this"
        // signal available, and it opens a limited window rather than arming forever.
        for (Method method : button.getDeclaredMethods()) {
            if (!"onTouchEvent".equals(method.getName()) || method.getParameterCount() != 1) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object event = param.args[0];
                    if (!(event instanceof android.view.MotionEvent)) return;
                    android.view.MotionEvent motion = (android.view.MotionEvent) event;
                    // Master and GR have no working composition capture path on this build; a tap
                    // here starts an inference the shutter cannot finish. Swallow it so the entry
                    // is inert instead of silently eating the next photo.
                    if (!compositionBlocked("entry")) return;
                    if (motion.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        && buttonBlocked.compareAndSet(false, true)) {
                        Log.i(TAG, "composition entry ignored in " + modeName + " mode");
                    }
                    param.setResult(Boolean.TRUE);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object event = param.args[0];
                    if (!(event instanceof android.view.MotionEvent)) return;
                    android.view.MotionEvent motion = (android.view.MotionEvent) event;
                    if (motion.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        // The one honest signal that this touch belongs to the composition entry.
                        // It has to be taken on DOWN: by the time the UP arrives, the activity-level
                        // hook has already decided the gesture was a tap on the viewfinder and
                        // pinned a subject -- which also switched the whole session to the on-device
                        // detector for two minutes (LOCAL_TTL), so the model was never asked at all.
                        // Every "打开构图按钮默认识别不到主体" report traces back to this ordering.
                        //
                        // Modes whose entry is disabled keep the flag clear, otherwise this shortcut
                        // would reopen composition in exactly the modes the guard exists to protect.
                        if (!compositionBlocked("entry")) {
                            entryDownAt = android.os.SystemClock.uptimeMillis();
                        }
                        return;
                    }
                    if (motion.getActionMasked() != android.view.MotionEvent.ACTION_UP) return;
                    // Kept as a diagnostic only: the camera itself dispatches touches to this
                    // button during start-up, so a touch here is not proof of anything.
                    Log.i(TAG, "button touch device=" + motion.getDeviceId()
                        + " source=0x" + Integer.toHexString(motion.getSource()));
                }
            });
        }
    }

    /** When the composition entry last went down; the activity hook reads it to skip the pin. */
    private static volatile long entryDownAt;

    /** vb.c3 keeps itself reachable for the re-arm loop below. */
    private static volatile Object cameraHost;
    private static volatile Object answered;
    /** Wall-clock stamp of the last tap on the composition entry button, 0 when never tapped. */
    private static volatile long armedAt;
    private static final long startedAt = android.os.SystemClock.uptimeMillis();
    private static final long ARM_WINDOW = 25000L;

    /**
     * How long the viewfinder has to be held to pin a subject. Tap-to-focus lives in the same
     * rectangle, and a finger resting a moment on the preview after a focus tap was enough to fall
     * into follow mode without meaning to -- which then looked like composition restarting on its
     * own. Camera buttons are tapped, never held this long.
     */
    private static final long PIN_HOLD = 800L;

    /** How often the pipeline is nudged for another frame while a subject is locked. */
    private static final long TRACK_REARM = 380L;

    /** With the box unchanged this long, tracking drops to one frame every few seconds. */
    private static final long TRACK_SETTLE = 2500L;
    /** Slowest frame cadence in the backed-off state; the camera redraws on every answer. */
    private static final long TRACK_IDLE = 3000L;

    /**
     * A session is one burst of answers around one user request. It is opened by the camera asking
     * for composition after a pause, and it closes on its own after {@link #SESSION_TTL} or
     * {@link #PASS_MAX} answers.
     *
     * <p>The cap is the whole point. Answering "handled" keeps the camera's composition state
     * machine alive, so a session that is answered without end never ends either: the panel applied
     * one crop, asked again, was answered again, and the user was left with a composition running
     * forever -- one tap and the only way out was to send the camera to the background. The stock
     * engine stops because a real result finishes the request; an invented success never does.
     */
    private static volatile long sessionAt;
    private static int sessionPasses;
    /**
     * Set when a session was closed by the cap. Nothing reopens composition on its own after that;
     * only a fresh gesture from the user does, which is what keeps a runaway pipeline from
     * restarting itself a moment after it was cut.
     */
    private static volatile boolean blockReopen;
    private static final long SESSION_TTL = 8000L;
    private static final int PASS_MAX = 400;
    /**
     * A quieter gap than this between two passes means a new request, not a continuation.
     *
     * <p>The camera re-asks about every two seconds while the panel is open, and 1.5s used to treat
     * every one of those as a new request: the held answer was dropped, a fresh model round trip
     * went out, and the passes in between answered with the full-frame fallback -- on screen that
     * is "no subject detected" flickering against a real suggestion. Five seconds keeps the whole
     * panel session on one answer while still catching a real re-entry.
     */
    private static final long STREAM_GAP = 5000L;

    /**
     * Following is opt-in per shot: the entry button has to be tapped, and the permission expires.
     * Without this the camera composes the moment it launches and again right after every shutter.
     */
    private static boolean armed() {
        if (!following()) return false;
        long now = android.os.SystemClock.uptimeMillis();
        // It also probes once while the preview is still warming up, before any finger could have
        // reached the entry button; answering that puts a box on a camera the user just opened.
        if (now - startedAt < STARTUP_GRACE) return false;
        long at = sessionAt;
        if (at == 0) return false;
        if (now - at > SESSION_TTL || sessionPasses >= PASS_MAX) {
            sessionAt = 0;
            blockReopen = true;
            local.jiege.hook.mastercolor.MasterColor.compositionStop();
            Log.i(TAG, "composition session closed after " + sessionPasses + " passes");
            return false;
        }
        return true;
    }

    /**
     * Is the composition panel up right now? Read-only on purpose -- {@link #armed()} closes an
     * expired session as a side effect, and calling it from a touch handler would let a shutter tap
     * tear down a session the camera is still using.
     *
     * <p>The panel flag ({@code vb.c3.N0}) is the direct answer. The session timestamp is the
     * fallback for the first frames after the panel opens, before that flag is written.
     */
    private static boolean compositionOpen() {
        if (!following()) return false;
        Object host = cameraHost;
        if (host != null && Boolean.TRUE.equals(field(host, "N0"))) return true;
        long at = sessionAt;
        return at != 0 && android.os.SystemClock.uptimeMillis() - at <= SESSION_TTL;
    }

    /**
     * A new request opens a new session; a continuation inside the same burst does not.
     *
     * <p>Deliberately the only place that opens one on its own, and deliberately a check with no
     * side effects anywhere else: {@link #armed()} is polled by the re-arm thread and by every
     * pass, so opening a session there would let the camera's own polling re-open the session it
     * had just closed.
     */
    private static void noteStream() {
        long now = android.os.SystemClock.uptimeMillis();
        long previous = lastPassAt;
        lastPassAt = now;
        if (blockReopen) return;
        if (previous != 0 && now - previous <= STREAM_GAP) return;
        sessionAt = now;
        sessionPasses = 0;
        // A new framing is decided on the frame that is in front of the lens now, not on the
        // answer the last one left behind ("为什么次次都是 1.4X").
        refreshFraming();
        rectSince = 0;
        directDone = false;
        // Composition drives its own white balance, which makes the master preview table rebuild
        // every frame; stop the rebuild for the whole session, not just the save window.
        local.jiege.hook.mastercolor.MasterColor.compositionStart();
        Log.i(TAG, "composition asked for: session open");
    }

    private static volatile long lastPassAt;

    /** A gesture that means "do it again"; the cap is lifted until the next session closes. */
    static void reopenComposition() {
        blockReopen = false;
        sessionAt = android.os.SystemClock.uptimeMillis();
        sessionPasses = 0;
        local.jiege.hook.mastercolor.MasterColor.compositionStart();
    }

    private static final long STARTUP_GRACE = 8000L;

    private static boolean stillWarm() {
        long at = armedAt;
        return at != 0 && android.os.SystemClock.uptimeMillis() - at < ARM_WINDOW;
    }

    /** True while the composition panel itself is on screen -- the one honest "user wants this". */
    private static volatile boolean panelVisible;
    private static boolean panelPrimed;
    private static boolean panelWasOpen;
    private static long panelCheckedAt;
    private static volatile long lastPanelOpen;

    private static boolean panelOpen() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - panelCheckedAt < 400L) return panelVisible;
        panelCheckedAt = now;
        boolean open = false;
        try {
            Object thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null);
            Object records = XposedHelpers.getObjectField(thread, "mActivities");
            if (records instanceof java.util.Map) {
                for (Object record : ((java.util.Map<?, ?>) records).values()) {
                    Object value = XposedHelpers.getObjectField(record, "activity");
                    if (!(value instanceof android.app.Activity)) continue;
                    android.app.Activity activity = (android.app.Activity) value;
                    if (activity.isFinishing()) continue;
                    android.view.View decor = activity.getWindow().getDecorView();
                    if (hasPanel(decor)) {
                        open = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Hidden API churn: fall back to the tap window rather than composing forever.
        }
        panelVisible = open;
        // Single shot: the panel appearing is the user's tap. One composition, then stop until
        // the panel is closed and reopened -- no timer keeps re-arming behind their back.
        if (!panelPrimed) {
            panelPrimed = true;
        } else if (open && !panelWasOpen) {
            armedAt = now;
            // A panel that has just appeared is the user opening composition. It also lifts the cap
            // a previous session set, so the second tap of the session works too.
            if (now - lastPanelOpen > 1200L) {
                lastPanelOpen = now;
                reopenComposition();
            }
            Log.i(TAG, "panel opened: one composition");
        } else if (!open && panelWasOpen) {
            armedAt = 0;
            // Deliberately no clearCloudSession() here: this panel probe runs on every frame and
            // its visibility flickers, so clearing on "closed" threw away a perfectly good model
            // answer and asked the cloud again a moment later. Shutter, pause and a fresh lock are
            // the real session boundaries.
            Log.i(TAG, "panel closed: follow off");
        }
        panelWasOpen = open;
        return open;
    }

    /** One more pass has been answered; the session counts them and closes itself at the cap. */
    static void afterPush() {
        sessionPasses++;
        if (sessionPasses >= PASS_MAX) {
            sessionAt = 0;
            blockReopen = true;
            Log.i(TAG, "composition capped at " + sessionPasses + " passes");
        }
    }

    private static boolean hasPanel(android.view.View view) {
        if (view == null) return false;
        if (view.getClass().getName().endsWith("AICompositionPreviewView")
            && view.getVisibility() == android.view.View.VISIBLE) {
            return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasPanel(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
    private static volatile long lastStreamAt;
    private static long lastTrigger;
    private static int triggerCalls;
    private static final long TRIGGER_GAP = 6000L;

    /**
     * Optional: asks the camera to enter composition again every {@code persist.omnicam.rearm}
     * milliseconds, so a pinned subject can be tracked live.
     *
     * <p>Off by default, and it must stay that way. The lock used to drive this at 380ms on its
     * own, which meant every tap on the preview -- the gesture that pins a subject -- left a thread
     * asking the camera to start composing four times a second for as long as the process lived.
     * That is the "one tap and it never stops" report, and no cap on the answer side can fix a
     * caller that keeps re-entering composition behind the user's back. With it off, composition
     * only runs when the camera itself asks for it.
     */
    private static void startRearm() {
        if (!following()) return;
        long interval;
        try {
            interval = Long.parseLong(prop("persist.omnicam.rearm", "0").trim());
        } catch (Throwable ignored) {
            interval = 0;
        }
        // Off by default, and when it is off there is no loop to run: the thread used to spin
        // every 200ms doing nothing but re-reading a property that would never change, which is a
        // permanent background cost the module paid whether or not tracking was ever used.
        if (interval <= 0) {
            Log.i(TAG, "rearm off: no tracking loop");
            return;
        }
        final long rate = interval;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException stop) {
                    return;
                }
                Object host = cameraHost;
                if (host == null || !armed()) continue;
                if (android.os.SystemClock.uptimeMillis() - lastStreamAt < rate) continue;
                try {
                    XposedHelpers.callMethod(host, "h4", Boolean.FALSE);
                } catch (Throwable error) {
                    Log.e(TAG, "rearm failed", error);
                }
            }
        }, "comp-rearm");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "rearm loop started at " + rate + "ms");
    }

    /**
     * A shot ends the composition session. Preview keeps streaming through {@code
     * setRepeatingRequest}, so a one-off {@code capture} is the shutter; clearing the window there
     * stops the next frame from starting a brand new composition on top of the photo just taken.
     */
    private static void hookShutter() {
        Class<?> session = XposedHelpers.findClassIfExists(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl", null);
        if (session == null) {
            Log.i(TAG, "capture session impl missing");
            return;
        }
        int hooked = 0;
        for (Method method : session.getDeclaredMethods()) {
            if (!"capture".equals(method.getName())) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // Freeze the master preview table for the whole save window. Composition makes
                    // the CCT jump, the preview rebuild bakes a 19MB blend table through the same
                    // native library the capture path is in, and a rebuild that lands mid-save left
                    // the camera stuck: the first shot after composition saved, and every shot
                    // after it silently did nothing until the process was restarted.
                    local.jiege.hook.mastercolor.MasterColor.holdPreview(6000L);
                    // The long press asked for one composition on the device; the shot ends it, so
                    // the next tap goes back to the model without the user having to remember.
                    // Before the armed check on purpose: a shutter should end that request even
                    // when the panel probe never saw the panel open.
                    releaseLocal();
                    if (armedAt == 0 && sessionAt == 0) return;
                    armedAt = 0;
                    // A shot ends the request. No cap is left behind: this hook also fires while a
                    // composition is being prepared for the still, and blocking here would silence
                    // the very framing the shot was taken with.
                    sessionAt = 0;
                    sessionPasses = 0;
                    // Composition is over with the shot; let the master preview table follow again.
                    local.jiege.hook.mastercolor.MasterColor.compositionStop();
                    // A shot ends the session, but it must not throw the model's answer away. It
                    // used to, and the very next composition frame fell back to the stock demo --
                    // which is why the panel came back with the wrong cards (or none) right after
                    // every photo. Keep the answer; only stop the session.
                    if ("1".equals(prop("persist.omnicam.compshot", "0"))) clearCloudSession();
                    Log.i(TAG, "follow disarmed by shutter");
                }
            });
            hooked++;
        }
        Log.i(TAG, "shutter hooks: " + hooked);
    }

    /**
     * A tap on the viewfinder picks the subject.
     *
     * <p>The camera draws a focus box exactly where the finger went down; that box is the one
     * honest statement of what the user wants framed. Composition used to ignore it and guess from
     * the frame, which is how a shot of a can in the middle of a desk kept coming back framed on a
     * file rack at the edge. It then required an 800ms hold to pick a subject, so a tap focused the
     * shot and the guess went on winning.
     *
     * <p>Tapping the same spot again releases the lock and hands the frame back to the detector.
     * Buttons are excluded -- see the two guards below, which is why a plain tap is safe now.
     *
     * <p>Hooked on the activity rather than on the SurfaceView: the camera puts a transparent
     * gesture layer on top of the preview, so the surface itself never sees the touch.
     */
    private static void hookTouch() {
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "dispatchTouchEvent",
                new XC_MethodHook() {
                    private float downX;
                    private float downY;
                    private long downAt;

                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!following()) return;
                        if (!(param.args[0] instanceof android.view.MotionEvent)) return;
                        android.view.MotionEvent motion = (android.view.MotionEvent) param.args[0];
                        float rawX = motion.getRawX();
                        float rawY = motion.getRawY();
                        int action = motion.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            downX = rawX;
                            downY = rawY;
                            downAt = android.os.SystemClock.uptimeMillis();
                            dumpTreeOnce((android.app.Activity) param.thisObject);
                            return;
                        }
                        if (action != android.view.MotionEvent.ACTION_UP) return;
                        // A pinch or a swipe must not move the subject. Duration is not checked:
                        // short presses are still aimed presses, and a minimum hold only made fast
                        // taps disappear.
                        if (Math.abs(rawX - downX) > 40f || Math.abs(rawY - downY) > 40f) return;
                        // The composition entry saw this gesture go down. Its own onTouchEvent runs
                        // after this hook, so without the flag below the tap would be read here first
                        // and pin a subject under the icon -- and pinning also forces the on-device
                        // channel, which is why the model never got asked on a plain entry tap.
                        //
                        // Only the pin is skipped. The entry button opens the panel itself; reopening
                        // it from here as well put a composition panel on screen in modes that do not
                        // support it, on any gesture that happened to reach that button -- "在其他
                        // 模式按一下快门和变焦都会触发构图".
                        long entryAt = entryDownAt;
                        if (entryAt != 0 && android.os.SystemClock.uptimeMillis() - entryAt < 1500L) {
                            entryDownAt = 0;
                            return;
                        }
                        android.app.Activity activity = (android.app.Activity) param.thisObject;
                        android.view.View decor = activity.getWindow().getDecorView();
                        android.view.View preview = findPreview(decor);
                        if (preview == null) return;
                        // The preview surface is the whole screen, so a press on any icon also lands
                        // "inside" it. Without this check, pressing the composition entry pinned the
                        // subject to the point under the icon -- the box jumped to the folder at the
                        // right edge instead of staying on what the camera was aimed at.
                        if (CompositionIconShift.overEntry(rawX, rawY)) {
                            // The icon is how the user asks for composition, so it also lifts the
                            // cap a finished session left behind; without that the second tap of a
                            // visit would open the panel and then answer nothing at all.
                            //
                            // Composition only exists in the photo modes. Everywhere else the icon
                            // is either absent or borrowed by another feature, and reopening here
                            // is what put a composition panel on a video or portrait screen.
                            if (!photoMode()) return;
                            reopenComposition();
                            return;
                        }
                        // Identity, not geometry: the icon is drawn through a translation, so the
                        // rectangle path above can miss it and turn a tap on the icon into a tap on
                        // the viewfinder -- which pinned the subject to the point under the icon.
                        if (CompositionIconShift.isEntry(hitTest(decor, rawX, rawY, 0))) {
                            if (!photoMode()) return;
                            reopenComposition();
                            return;
                        }
                        if (touchesControl(decor, rawX, rawY, preview)) return;
                        // A pick only means something while the composition panel is up. The shutter,
                        // the zoom ladder and the mode strip all sit inside the preview rectangle, so
                        // every one of those taps used to "pick a subject": that pinned a point,
                        // forced the on-device channel for two minutes and reopened composition --
                        // which is the report "在其他模式按一下快门和变焦都会触发构图".
                        if (!compositionOpen()) return;
                        int[] origin = new int[2];
                        preview.getLocationOnScreen(origin);
                        int width = preview.getWidth();
                        int height = preview.getHeight();
                        if (width <= 0 || height <= 0) return;
                        float x = (rawX - origin[0]) / width;
                        float y = (rawY - origin[1]) / height;
                        if (x < 0f || x > 1f || y < 0f || y > 1f) return;
                        float[] at = SubjectLocator.pinned();
                        // Same spot again: the user has changed their mind about the subject, not
                        // asked for it a second time. Asking them to hit a small target twice was
                        // the old way out of a lock and it never worked -- the box follows the
                        // subject, so it is almost never where the finger lands.
                        if (at != null && Math.abs(at[0] - x) < 0.06f && Math.abs(at[1] - y) < 0.06f) {
                            SubjectLocator.clearPin();
                            nudgeIfComposing();
                            return;
                        }
                        SubjectLocator.pinAt(x, y);
                        // Picking a subject is also choosing the channel: the model cannot answer
                        // "frame this exact thing", it answers "what is worth framing here". A tap
                        // means the user has already decided, so the device decides with them.
                        // It is a gesture like any other, so it also reopens a capped session.
                        reopenComposition();
                        forceLocal();
                        nudgeIfComposing();
                    }
                });
            Log.i(TAG, "tap hooks: tap the preview to pick the subject");
        } catch (Throwable error) {
            Log.e(TAG, "tap hook failed", error);
        }
    }

    private static long lastTreeDump;

    /**
     * Diagnostic only: prints the on-screen view tree so the shutter and the composition exit
     * control can be identified by class and id instead of by guessed screen coordinates.
     * Armed by {@code persist.omnicam.viewdump=1}, at most one dump every four seconds so a burst
     * of taps cannot flood the log.
     */
    private static void dumpTreeOnce(android.app.Activity activity) {
        if (!"1".equals(prop("persist.omnicam.viewdump", ""))) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTreeDump < 4000L) return;
        lastTreeDump = now;
        try {
            Log.i(TAG, "=== view tree ===");
            dumpTree(activity.getWindow().getDecorView(), 0);
        } catch (Throwable error) {
            Log.e(TAG, "view dump failed", error);
        }
    }

    private static void dumpTree(android.view.View view, int depth) {
        if (depth > 14) return;
        if (view.getVisibility() != android.view.View.VISIBLE) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return;
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        StringBuilder line = new StringBuilder(96);
        for (int i = 0; i < depth; i++) line.append("  ");
        line.append(view.getClass().getSimpleName())
            .append(" [").append(origin[0]).append(',').append(origin[1])
            .append(' ').append(width).append('x').append(height).append(']');
        int id = view.getId();
        if (id != android.view.View.NO_ID) {
            try {
                line.append(" id=").append(view.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {
                line.append(" id=0x").append(Integer.toHexString(id));
            }
        }
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0) line.append(" text=").append(text);
        }
        CharSequence description = view.getContentDescription();
        if (description != null && description.length() > 0) {
            line.append(" desc=").append(description);
        }
        Log.i(TAG, line.toString());
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child != null) dumpTree(child, depth + 1);
        }
    }

    /**
     * Asks the camera for a composition frame right away. Without this a fresh lock waits for the
     * re-arm tick, which is most of a second of nothing happening after the press.
     */
    private static void kick() {
        Object host = cameraHost;
        if (host == null) return;
        try {
            XposedHelpers.callMethod(host, "h4", Boolean.FALSE);
        } catch (Throwable error) {
            Log.e(TAG, "kick failed", error);
        }
    }

    /**
     * Asks for one more composition frame, but only inside a composition session that is already
     * running.
     *
     * <p>Not merely because the panel is detectable: this used to fire whenever the composition
     * layer was on screen, and waking the camera's composition state machine from the viewfinder is
     * what made a single tap start a composition on its own -- the job the double tap used to do.
     * Outside a session, the pick is still recorded and is what the next composition starts from.
     */
    private static void nudgeIfComposing() {
        if (!armed()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastNudge < NUDGE_GAP) return;
        lastNudge = now;
        kick();
    }

    /** Taps arrive in bursts; one nudge per session step is enough to re-frame. */
    private static final long NUDGE_GAP = 900L;
    private static volatile long lastNudge;

    /** The largest surface on screen: the preview, as opposed to thumbnails or icons. */
    private static final java.util.concurrent.atomic.AtomicBoolean buttonBlocked =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Is the point over a control rather than the viewfinder?
     *
     * <p>Full-screen layers are exempt on purpose: the camera puts a transparent gesture layer over
     * the preview and that one has to keep accepting long presses, otherwise the lock feature dies
     * with the false positives it was meant to fix. A control is a small clickable view -- or one
     * of the composition buttons, which are drawn by the feature and do not set a click listener.
     */
    private static boolean touchesControl(android.view.View decor, float x, float y,
                                          android.view.View preview) {
        android.view.View hit = hitTest(decor, x, y, 0);
        if (hit == null || hit == preview) return false;
        int screenW = decor.getWidth();
        int screenH = decor.getHeight();
        // Large enough to be the viewfinder rather than a button on it.
        boolean layer = hit.getWidth() >= screenW * 0.6f && hit.getHeight() >= screenH * 0.5f;
        String name = hit.getClass().getName();
        if (name.contains("aicomposition")) {
            // Everything in the composition layer -- the preview it frames, the guide rectangle,
            // the surface that closes it -- is drawn by that package, so class name alone says
            // nothing about whether the point is a control. Calling all of it a control is what
            // silently discarded every tap the user made to pick a subject: the pick never reached
            // the detector, and the model's framing stayed on screen, which is exactly "I tapped
            // the can and it still chose something else". Only the small parts count as controls.
            return !layer;
        }
        boolean fullScreen = hit.getWidth() >= screenW * 0.9f && hit.getHeight() >= screenH * 0.9f;
        if (fullScreen) return false;
        return hit.isClickable() || hit instanceof android.widget.ImageView;
    }

    /** Topmost visible view under a screen point; later children draw on top, so walk backwards. */
    private static android.view.View hitTest(android.view.View view, float x, float y, int depth) {
        if (view == null || depth > 24) return null;
        if (view.getVisibility() != android.view.View.VISIBLE) return null;
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        int width = Math.round(view.getWidth() * view.getScaleX());
        int height = Math.round(view.getHeight() * view.getScaleY());
        if (width <= 0 || height <= 0) return null;
        if (x < origin[0] || x >= origin[0] + width) return null;
        if (y < origin[1] || y >= origin[1] + height) return null;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                android.view.View found = hitTest(group.getChildAt(i), x, y, depth + 1);
                if (found != null) return found;
            }
        }
        return view;
    }

    private static android.view.View findPreview(android.view.View view) {
        if (view instanceof android.view.SurfaceView
            && view.getWidth() > 0 && view.getHeight() > 0) {
            return view;
        }
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        android.view.View best = null;
        int bestArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View found = findPreview(group.getChildAt(i));
            if (found == null) continue;
            int area = found.getWidth() * found.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = found;
            }
        }
        return best;
    }

    /**
     * Leaving the camera ends the session. Without this the window survives a trip to the gallery
     * or the home screen in the same process, and coming back starts composing before the user has
     * asked for anything.
     */
    private static void hookPause() {
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // Leaving the camera ends the lock too. It used to survive a pause, so coming
                    // back into the camera started following whatever the last session had pinned.
                    boolean pinned = SubjectLocator.pinned() != null;
                    SubjectLocator.clearPin();
                    clearCloudSession();
                    releaseLocal();
                    if (armedAt == 0 && !pinned && sessionAt == 0) return;
                    armedAt = 0;
                    // Leaving the camera ends the burst; no cap is left behind, because this hook
                    // also fires for the camera's own sub-pages and blocking there would silence
                    // the next composition the user asks for.
                    sessionAt = 0;
                    sessionPasses = 0;
                    local.jiege.hook.mastercolor.MasterColor.compositionStop();
                    Log.i(TAG, "follow disarmed by pause" + (pinned ? ", lock released" : ""));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "pause hook failed", error);
        }
    }

    /** wb.r.f(HardwareBuffer, HardwareBuffer, HardwareBuffer, long, long, float, cb, int, int, int, int) */
    private static void hookWorker(ClassLoader classLoader) {
        Class<?> owner = XposedHelpers.findClassIfExists("wb.r", classLoader);
        if (owner == null) {
            Log.i(TAG, "wb.r not in this build");
            return;
        }
        int hooked = 0;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 11) continue;
            if (types[0] != HardwareBuffer.class || types[1] != HardwareBuffer.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, WORKER);
            hooked++;
        }
        Log.i(TAG, "worker methods hooked: " + hooked);
    }

    private static final XC_MethodHook WORKER = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++frames;
            }
            if (rideMode()) rideWorker(param);
            if (index > LIMIT) return;
            Object host = param.thisObject;
            StringBuilder line = new StringBuilder("worker #" + index
                + " w=" + bool(host, "w") + " x=" + bool(host, "x")
                + " y=" + bool(host, "y") + " z=" + bool(host, "z")
                + " h=" + bool(host, "h") + " enabled=" + bool(host, "B"));
            for (int i = 0; i < 3; i++) {
                Object arg = param.args[i];
                if (!(arg instanceof HardwareBuffer)) {
                    line.append(" buf").append(i).append("=null");
                    continue;
                }
                HardwareBuffer buffer = (HardwareBuffer) arg;
                line.append(" buf").append(i).append('=')
                    .append(buffer.getWidth()).append('x').append(buffer.getHeight())
                    .append("/fmt").append(buffer.getFormat())
                    .append("/use0x").append(Long.toHexString(buffer.getUsage()))
                    .append(buffer.isClosed() ? "(closed)" : "");
            }
            line.append(" cb=").append(param.args[6] == null ? "null" : param.args[6].getClass().getName());
            Log.i(TAG, line.toString());
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (param.getThrowable() != null) {
                Log.i(TAG, "worker threw " + param.getThrowable());
            }
            Object host = param.thisObject;
            Object context = field(host, "b");
            // The worker is the only place holding the camera's own Context, and the AIUnit SDK
            // needs one to bind its service.
            if (context instanceof android.content.Context) {
                AiUnitBridge.attach((android.content.Context) context);
            }
            // The helper is created inside the worker, so probe it here rather than in before.
            if (!"1".equals(prop("persist.omnicam.realtrack", ""))) return;
            Object helper = field(host, "a");
            Log.i(TAG, "helper=" + (helper == null ? "null" : helper.getClass().getName()));
            if (helper == null) return;
            String dir = context instanceof android.content.Context
                ? ((android.content.Context) context).getFilesDir().getAbsolutePath()
                : "/data/user/0/com.oplus.camera/files";
            try {
                XposedHelpers.callMethod(helper, "initTracker", dir);
                Log.i(TAG, "initTracker ok");
            } catch (Throwable error) {
                Log.e(TAG, "initTracker failed", error);
            }
            try {
                XposedHelpers.callMethod(helper, "initComposition");
                Log.i(TAG, "initComposition ok");
            } catch (Throwable error) {
                Log.e(TAG, "initComposition failed", error);
            }
        }
    };

    /**
     * Steers the worker onto its own answer branch ({@code w} is the camera's "let the engine
     * decide" switch) and hands the frame to the model, since the engine call that used to carry it
     * never happens on this build.
     */
    private static void rideWorker(XC_MethodHook.MethodHookParam param) {
        Object host = param.thisObject;
        if (host == null) return;
        if (bool(host, "w")) {
            try {
                XposedHelpers.setBooleanField(host, "w", false);
                if (rideReported.compareAndSet(false, true)) {
                    Log.i(TAG, "composition answered by the camera itself (engine absent in this build)");
                }
            } catch (Throwable error) {
                if (rideReported.compareAndSet(false, true)) {
                    Log.e(TAG, "cannot steer the composition worker", error);
                }
            }
        }
        if (!armed()) return;
        Object callback = param.args.length > 6 ? param.args[6] : null;
        maybeAskCloud(callback, param.args.length > 0 ? param.args[0] : null);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean rideReported =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static void hookStreaming(ClassLoader classLoader) {
        Class<?> helper = XposedHelpers.findClassIfExists(
            "com.oplus.ocs.camera.OplusAICompositionHelper", classLoader);
        if (helper == null) {
            Log.i(TAG, "helper class missing");
            return;
        }
        int hooked = 0;
        for (Method method : helper.getDeclaredMethods()) {
            if (!"runCompositionStreaming".equals(method.getName())) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, STREAMING);
            hooked++;
        }
        Log.i(TAG, "runCompositionStreaming hooked: " + hooked);
    }

    /**
     * The camera renders the composition overlay into an offscreen texture before a capture and
     * hands that texture to the capture pipeline. That renderer lives in the composition engine this
     * build does not ship, so the shader/output fields stay null; the capture path then reports
     * "effect output unavailable", drops the photo and resets composition -- which is exactly the
     * "it works a few times and then refuses to compose" behaviour. Skipping the method when the
     * output is missing leaves the preview alone and lets the capture finish as a plain photo.
     */
    private static void hookEffectTexture(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        if (presenter == null) {
            Log.i(TAG, "composition presenter missing, effect guard off");
            return;
        }
        int hooked = 0;
        for (Method method : presenter.getDeclaredMethods()) {
            if (!"Z1".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 1) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, EFFECT);
            hooked++;
        }
        Log.i(TAG, "effect texture guard: " + hooked);
    }

    private static final XC_MethodHook EFFECT = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            // Both the offscreen shader and its output texture must exist; on this build they never
            // do, and the method's failure branch is worse than doing nothing.
            if (!"1".equals(prop("persist.omnicam.compguard", "0"))) return;
            Object host = param.thisObject;
            if (host == null) return;
            if (field(host, "H0") != null && field(host, "M0") != null) return;
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++effects;
            }
            if (index <= LIMIT) {
                Log.i(TAG, "no effect output, capture left alone #" + index);
            }
            param.setResult(null);
        }
    };

    /**
     * The presenter reports a composition failure through one entry point and, on this build, the
     * "invalid zoom result" reason is the normal outcome of every pass: the engine that should
     * validate the crop is not installed. Keeping the panel alive means not letting that single
     * reason through; real failures ("track failed", "no person detected") still take their course.
     *
     * <p>"track failed" is NOT swallowed here. Swallowing it (r109) only muted the toast while the
     * session still never rendered a frame, because the teardown the toast belongs to is only the
     * surface: the real gate is upstream, in afterEffectProcess, where a bogus TrackStatus (3/6)
     * from the missing tracker funnels into vb.c3.o3(worker, false). That upstream gate is handled
     * by {@link #hookTrackFailed} instead; here we keep only the invalid-zoom branch.
     */
    private static final XC_MethodHook FAIL = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            String reason = String.valueOf(param.args[1]);
            boolean keep = !"0".equals(prop("persist.omnicam.comp.keep", "1"))
                && "invalid zoom result".equals(reason);
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++fails;
            }
            if (index <= LIMIT) Log.i(TAG, "composition failed: " + reason + (keep ? " (kept)" : ""));
            // Which method decided this? The reason travels up from the crop pipeline, and the
            // caller names the gate we have to satisfy.
            if (index <= 3) {
                StringBuilder trace = new StringBuilder();
                StackTraceElement[] frames = new Throwable().getStackTrace();
                for (int i = 0; i < frames.length && i < 14; i++) {
                    trace.append(" <- ").append(frames[i].getClassName()).append('.').append(frames[i].getMethodName());
                }
                Log.i(TAG, "failure reported from:" + trace);
            }
            if (keep) param.setResult(null);
        }
    };

    private static void hookFailure(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        if (presenter == null) {
            Log.i(TAG, "composition presenter missing, failure guard off");
            return;
        }
        int hooked = 0;
        for (Method method : presenter.getDeclaredMethods()) {
            if (!"n3".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 2 || types[0] != int.class || types[1] != String.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, FAIL);
            hooked++;
        }
        Log.i(TAG, "composition failure guard: " + hooked);
    }

    /**
     * Set while {@code vb.c3.k4(int)} runs. That method is the only place that hands a finished
     * answer to the panel, and its first branch reads:
     *
     * <pre>
     *   if (aiunitAvailable && CameraConfig.d("...realscene.support", false, false)) {
     *       i4(); G2(); Z2();
     *       return;                       // no j4 -> nothing is drawn, l2 stays false
     *   }
     *   G2(); j4(false, false);           // j4 hands the answer to the view and sets l2
     * </pre>
     *
     * Real-scene support has to answer TRUE everywhere else (that flag is what puts the
     * "灵感构图 / 灵感推荐" switch on screen at all), but it has to answer FALSE for this one
     * caller, or the panel takes the branch that never draws. A thread-local is exact: k4 runs
     * synchronously on one thread and never nests.
     */
    private static final ThreadLocal<Boolean> ANSWER_LANDING = new ThreadLocal<>();

    static Boolean inAnswerLanding() {
        return ANSWER_LANDING.get();
    }

    private static void hookAnswerLanding(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        if (presenter == null) return;
        int hooked = 0;
        for (Method method : presenter.getDeclaredMethods()) {
            if (!"k4".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 1 || types[0] != int.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if ("1".equals(prop("persist.omnicam.comp.landing", "1"))) {
                        ANSWER_LANDING.set(Boolean.TRUE);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ANSWER_LANDING.remove();
                }
            });
            hooked++;
        }
        Log.i(TAG, "answer landing hook: " + hooked);
    }

    /**
     * Drops every held framing so the next answer is decided on the frame that is in front of the
     * lens right now.
     *
     * <p>Both answers -- the model's and the on-device detector's -- are held for the life of the
     * camera process: {@code cloudGiven} is filled once and reused forever, and the detector's JSON
     * is frozen per session. That is right for the frames inside one composition, and wrong the
     * moment the user frames something new: the panel then keeps asking for the ratio of a subject
     * that left the frame minutes ago. The report was "为什么次次都是 1.4X，手机拉远了也是 1.4X".
     *
     * <p>k4 is the entry point the camera runs when the panel opens, so it is exactly the moment a
     * new framing begins. Throttled because the panel calls it more than once while opening.
     */
    private static void refreshFraming() {
        if (!"1".equals(prop("persist.omnicam.comp.refresh", "1"))) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - refreshedAt < 2500L) return;
        refreshedAt = now;
        clearCloudSession();
        cloudAt = 0;
        SubjectLocator.dropHeld();
        frozenAnswer = null;
        frozenFor = -1L;
        // A new framing gets one launch of its own. The baseline is always 1.0 -- *not* the ratio the
        // previous framing asked for: carrying that over composes, because each answer is measured
        // on a preview the last launch already zoomed, so the base would grow 1.0 -> 1.5 -> 2.2 ->
        // 3.3 -> ... on every entry ("现在又是每次都是3.3X"). The panel is entered from a 1x preview.
        zoomLaunched = false;
        framingBase = 1.0f;
        appliedZoom = 1.0f;
        Log.i(TAG, "framing refreshed: held answer dropped, base " + framingBase);
    }

    private static volatile long refreshedAt;

    /**
     * {@code vb.c3.k4(int)} branches on whether the AIUnit service is reachable at all:
     *
     * <pre>
     *   if (com.oplus.aiunit.core.utils.d.j()) {
     *       if (CameraConfig.d(REAL_SCENE, false, false)) { i4(); G2(); Z2(); return; }  // real scene
     *       G2(); j4(false, false);
     *   }
     *   // aiunit unavailable:
     *   y2 = (arg != -1) ? arg : DataManager.b(ub.a.c, 0);
     *   delegate.p2(C3());
     *   if (y2 == 1 &amp;&amp; C3()) { g4(); return; }
     *   G2(); if (y2 != 1) { i4(); Z2(); } else { j4(false, false); }
     * </pre>
     *
     * On the phone as it ran before the reboot the AIUnit process happened to be dead, so
     * {@code j()} answered false and k4 fell through to the lower half, which ends in
     * {@code j4(false, false)} -- the call that actually hands the crop to the view. After the
     * reboot the service is alive again, k4 returns early and nothing is ever drawn.
     *
     * <p>Answering "unavailable" only while k4 runs therefore reproduces the state in which the
     * box did appear, and leaves every other caller of {@code j()} alone. It must NOT be done by
     * dropping the real-scene gate instead: that gate is what keeps the 灵感构图 tab on screen, and
     * with it false the panel silently turns into 灵感推荐 (still uploads, cloud replies 3111,
     * "未识别到人像").
     */
    private static void hookAiunitAvailability(ClassLoader classLoader) {
        Class<?> utils = XposedHelpers.findClassIfExists("com.oplus.aiunit.core.utils.d", classLoader);
        if (utils == null) {
            Log.i(TAG, "aiunit utils missing, k4 availability left as is");
            return;
        }
        int hooked = 0;
        for (Method method : utils.getDeclaredMethods()) {
            if (!"j".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != boolean.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (ANSWER_LANDING.get() == null) return;
                    if (!"1".equals(prop("persist.omnicam.comp.noaiunit", "1"))) return;
                    if (Boolean.FALSE.equals(param.getResult())) return;
                    param.setResult(Boolean.FALSE);
                    if (aiunitAnswers.incrementAndGet() <= LIMIT) {
                        Log.i(TAG, "k4: aiunit answered unavailable so the answer lands on j4");
                    }
                }
            });
            hooked++;
        }
        Log.i(TAG, "aiunit availability hook: " + hooked);
    }

    private static final java.util.concurrent.atomic.AtomicInteger aiunitAnswers =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The "track failed" teardown is decided inside {@code vb.c3.Z1(EventMessagePreviewPostDraw)}
     * -- the post-draw handler, and the same Z1 that appears in the failure stack. The exact
     * branch, straight from the bytecode at 0x025a..0x02b8:
     *
     * <pre>
     *   item   = trackerResult.c().get(index);        // ub.e$a
     *   status = item.d();
     *   if (3 == status) goto normal;                 // 02b8
     *   if (6 == status) goto normal;                 // 02b8
     *   if (!e1) { o3(worker, false); return; }        // e1 = palette expanded
     *   if (f1) return;  f1 = true;  log "defer track failure while palette expanded";
     *   return;
     *   normal(02b8): if (e1) return;                 // palette open: do nothing
     *                 ... compute the crop and hand it to the view ...
     * </pre>
     *
     * 3 and 6 are the only "good" statuses; anything else is failure. With no composition engine
     * behind the tracker nothing ever produces 3 or 6, so the very first post-draw frame after our
     * answer lands takes the teardown branch -- which is the "scene changed too much" toast, even
     * with the phone bolted to a desk.
     *
     * <p>Answering 3 for the duration of Z1 only (which runs synchronously, on one thread) puts the
     * camera on the path it takes after a genuine successful track, and leaves every other reader
     * of the tracker status untouched. The box itself is still ours: the crop handed to the view is
     * the JSON we push at runCompositionStreaming.
     */
    private static final ThreadLocal<Boolean> IN_POST_DRAW = new ThreadLocal<>();
    private static final java.util.Set<Integer> SEEN_STATUS = java.util.Collections.synchronizedSet(
        new java.util.LinkedHashSet<>());

    private static void hookTrackStatus(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        int watched = 0;
        if (presenter != null) {
            for (Method method : presenter.getDeclaredMethods()) {
                if (!"Z1".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 1) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        IN_POST_DRAW.set(Boolean.TRUE);
                        PHASE_IN.set(phaseName(field(param.thisObject, "M")));
                        skipAlignGuide(param.thisObject);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        skipAlignGuide(param.thisObject);
                        tracePhase(param.thisObject);
                        maybeZoomDirectly(param.thisObject, field(param.thisObject, "M"));
                        PHASE_IN.remove();
                        ALIGN_SKIPPED.remove();
                        IN_POST_DRAW.remove();
                    }
                });
                watched++;
            }
        }

        Class<?> item = XposedHelpers.findClassIfExists("ub.e$a", classLoader);
        if (item == null) {
            Log.i(TAG, "track status item missing, post-draw guard off");
            return;
        }
        int hooked = 0;
        for (Method method : item.getDeclaredMethods()) {
            if (!"d".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != int.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (IN_POST_DRAW.get() == null) return;
                    Object raw = param.getResult();
                    if (!(raw instanceof Integer)) return;
                    int status = (Integer) raw;
                    if (SEEN_STATUS.add(status) && SEEN_STATUS.size() <= LIMIT) {
                        Log.i(TAG, "track status seen: " + status + " (3/6 are the good ones)");
                    }
                    if (!"1".equals(prop("persist.omnicam.comp.trackok", "1"))) return;
                    if (status == 3 || status == 6) return;
                    param.setResult(3);
                    if (trackPromoted.incrementAndGet() <= LIMIT) {
                        Log.i(TAG, "track status " + status + " -> 3 during post-draw");
                    }
                }
            });
            hooked++;
        }
        Log.i(TAG, "post-draw track guard: " + hooked + " (post-draw watchers: " + watched + ")");
    }

    private static final java.util.concurrent.atomic.AtomicInteger trackPromoted =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The align-to-target guide never converges on this device, so step over it.
     *
     * <p>{@code vb.c3.Z1} drives the state machine with a distance computed from the tracker item
     * ({@code ub.e$a.e()/f()} are the tracked point, minus half the frame, hypot of that):
     *
     * <pre>
     *   far  = dist &gt; machine.p        // p starts at 22, drops to 11 once inside
     *   TRACKING    + far  -&gt; APPROACHING    (0x0419)
     *   APPROACHING + far  -&gt; TRACKING       (0x03ed)
     *   APPROACHING + near + machine.f -&gt; RECT_SHOWN   (0x03fe..0x0415)
     *   RECT_SHOWN  + machine.e -&gt; ZOOM_TRIGGERED
     *   ZOOM_TRIGGERED + |i-j| &lt; 0.01 -&gt; FINISHED
     * </pre>
     *
     * That tracked point only moves when a real composition tracker is feeding it. With no tracker
     * in this build it stays where it started, so the distance stays constant and the machine
     * oscillates between TRACKING and APPROACHING forever: the white dot, the circle and the line
     * between them are drawn at fixed positions and nothing the user does with the phone moves
     * them, which is exactly the report ("圆圈和目标点一起动 / 一动不动").
     *
     * <p>While the machine sits in either of those two states, Z1 also raises the
     * "请移动手机，对准目标点" hint ({@code camera_ai_composition_align_guide_point_1}, guard field
     * {@code t0}); leaving them calls {@code CompositionViewDelegate.Q0()}, which only tears that
     * hint and its two guards down ({@code t0 = false}, {@code u0 = false}, remove view) and does
     * not touch the composition rect. So promoting the state to RECT_SHOWN -- "the framing rect is
     * on screen", which is what the user already sees -- drops the dead guide and leaves the
     * session in a state the shutter accepts.
     *
     * <p>Done in both the before and after hook: before, so this frame's own switch runs the
     * RECT_SHOWN arm and never draws the guide again; after, because the same frame can write the
     * state back on its way out.
     */
    private static void skipAlignGuide(Object presenter) {
        if (!"1".equals(prop("persist.omnicam.comp.skipalign", "1"))) return;
        Object machine = field(presenter, "M");
        if (machine == null) return;
        fixZoomTarget(machine);
        Object phase = field(machine, "a");
        String name = phaseName(machine);
        if (!"TRACKING".equals(name) && !"APPROACHING".equals(name)) {
            // These two are only reachable on this device because the promotion above put them
            // there -- the tracker that would raise them natively has nothing feeding it -- so the
            // zoom launch that comes with them is dropped too.
            if ("RECT_SHOWN".equals(name) || "ZOOM_TRIGGERED".equals(name)) {
                ALIGN_SKIPPED.set(Boolean.TRUE);
            }
            return;
        }
        Object shown = stateNamed(phase, "RECT_SHOWN");
        if (shown == null) return;
        try {
            XposedHelpers.setObjectField(machine, "a", shown);
            // RECT_SHOWN's own arm is "if (machine.e) -> ZOOM_TRIGGERED", and that zoom request is
            // queued with a target the engine was supposed to fill in: machine.j is still 0 here
            // (it is written as i * tracker.g(), and tracker.g() has no tracker behind it), so
            // executeZoom runs with targetZoom 0.0 and the camera clamps it to the widest lens --
            // the whole preview drops to 0.6x and the framing box no longer covers anything.
            // Dropping the request leaves the zoom exactly where the answered crop put it, which is
            // what this module did before the state was ever promoted.
            if ("1".equals(prop("persist.omnicam.comp.skipzoom", "0"))) {
                XposedHelpers.setBooleanField(machine, "e", false);
            }
        } catch (Throwable error) {
            return;
        }
        int seen = alignSkips.incrementAndGet();
        ALIGN_SKIPPED.set(Boolean.TRUE);
        if (seen <= LIMIT || seen % 120 == 0) {
            Log.i(TAG, "align guide skipped: " + name + " -> RECT_SHOWN #" + seen
                + " p=" + field(machine, "p") + " q=" + field(machine, "q")
                + " e=" + field(machine, "e") + " f=" + field(machine, "f")
                + " i=" + field(machine, "i") + " j=" + field(machine, "j"));
        }
    }

    /**
     * Gives the queued zoom launch a target, so it stops landing on the widest lens.
     *
     * <p>{@code machine.j} is the target the launch reads ({@code CompositionStateMachine$b}'s
     * first float). Z1 writes it as {@code i * tracker.g()} -- the recommended zoom times the
     * tracker's own scale -- and without a tracker that product is 0.0, which the camera clamps to
     * 0.6x. {@code i} is the recommended zoom the camera would otherwise use, so standing in for
     * the missing tracker scale with 1.0 (j = i) keeps the launch at the zoom the framing was
     * computed for instead of dropping to ultra-wide.
     */
    private static void fixZoomTarget(Object machine) {
        if (!"1".equals(prop("persist.omnicam.comp.fixzoom", "1"))) return;
        Object target = field(machine, "i");
        Object current = field(machine, "j");
        if (!(target instanceof Float) || !(current instanceof Float)) return;
        float recommended = (Float) target;
        float queued = (Float) current;

        // Once the launch has gone out, machine.j holds the ratio the camera was asked for and
        // machine.i is still whatever the camera read from its own crop objects. The state machine
        // calls the launch finished only when the two agree to within 0.01 (Z1 @0x03bf), and F2
        // re-runs its "zoom result does not match" correction whenever they do not. Leaving them
        // apart therefore parks the panel in ZOOM_TRIGGERED and keeps the correction armed, so
        // mirror the target into the recommendation slot to make the pair converge the way a real
        // tracker's scale would have made it converge.
        String phase = phaseName(machine);
        if ("ZOOM_TRIGGERED".equals(phase) || "FINISHED".equals(phase)) {
            // The launch went out and carried this ratio, so the lens is there (or on its way).
            // Remember it: the next framing is measured on the preview it produces, and its own
            // ratio has to be turned back into an absolute one against this.
            zoomLaunched = true;
            if (queued > 0.01f) appliedZoom = queued;
            if (queued <= 0.01f) return;
            if (Math.abs(recommended - queued) <= 0.01f) return;
            try {
                XposedHelpers.setFloatField(machine, "i", queued);
            } catch (Throwable error) {
                return;
            }
            if (zoomFixed.incrementAndGet() <= LIMIT) {
                Log.i(TAG, "zoom converged: i " + recommended + " -> " + queued
                    + " (" + phase + ")");
            }
            return;
        }

        // One launch per session. Every answered crop is measured on the frame the lens is showing
        // right now, so once the camera has moved to 1.6x the next answer reads "this framing needs
        // 1.0x more" and -- if it were applied as an absolute ratio again -- the preview would walk
        // up in steps (observed: 1.4x -> 1.6x -> ...) until the subject is a blur. A suggestion is
        // decided once per framing; only a fresh entry to the panel starts another one.
        if (zoomLaunched) return;
        // Only once the rect is on screen. Before that the session is still COMPOSING and the only
        // answer in hand is the standby one the module pushes while the model is still thinking --
        // writing j then locks the launch to that placeholder's ratio and the real answer, which
        // arrives a couple of seconds later, never gets a say.
        if (!"RECT_SHOWN".equals(phase)) return;
        // The recommended zoom is the one the answered crop was computed for; machine.i only ever
        // reads 1.0 here because the camera fills it from its own crop objects, which our JSON is
        // not part of. Prefer the answered value so the launch lands on the framing being shown.
        float want = recommendZoom > 0.05f ? recommendZoom : recommended;
        if (want <= 0.01f) return;
        if (Math.abs(queued - want) <= 0.01f) return;
        try {
            XposedHelpers.setFloatField(machine, "j", want);
        } catch (Throwable error) {
            return;
        }
        if (zoomFixed.incrementAndGet() <= LIMIT) {
            Log.i(TAG, "zoom target " + queued + " -> " + want + " (" + phase
                + ", tracker scale missing)");
        }
    }

    /**
     * The zoom the last answered crop asked for, read straight out of the JSON we hand the camera.
     * Nothing in the camera publishes it back to us: machine.i is filled from the camera's own crop
     * objects, so it reads 1.0 no matter what we answer.
     */
    private static volatile float recommendZoom = 1.0f;

    private static void noteRecommendZoom(String json) {
        if (json == null) return;
        // The full-frame standby answer ("nothing detected yet, keep this framing") is pushed on
        // every pass that has no suggestion. It is a real answer for the pass, but it is not a
        // framing, so it must not overwrite the ratio a suggestion asked for.
        if (json.indexOf("omnicam-probe") >= 0) return;
        int at = json.indexOf("\"zoom\":");
        if (at < 0) return;
        int end = json.indexOf(',', at);
        if (end < 0) end = json.indexOf('}', at);
        if (end < 0) return;
        try {
            float value = Float.parseFloat(json.substring(at + 7, end).trim());
            if (value > 0.05f && value < 200f) recommendZoom = value;
        } catch (Throwable ignored) {
            // Not a number we understand; keep the previous one.
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger zoomFixed =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Set once the launch for this framing has gone out; cleared when a new framing starts. */
    private static volatile boolean zoomLaunched;

    /**
     * The ratio the lens is believed to be at -- the last one a launch carried.
     *
     * <p>Every answered crop is measured on the preview as it looks right now, so its {@code zoom}
     * is a multiplier over the current ratio, while the camera applies it as an absolute one. At
     * 1.0x the two are the same, which is why this only shows up after the first launch: the panel
     * then asks for 1.0x "more" and the preview snaps back, or for 1.3x and it steps up. Multiplying
     * by this turns the answer back into an absolute ratio.
     */
    private static volatile float appliedZoom = 1.0f;

    /** The ratio {@link #appliedZoom} had when the current framing began. */
    private static volatile float framingBase = 1.0f;

    /**
     * Rewrites our own answers so {@code zoom} means "absolute ratio" rather than "multiplier over
     * what the lens is showing".
     */
    private static String absoluteZoom(String json, float base) {
        if (json == null || base <= 0f || Math.abs(base - 1f) < 0.03f) return json;
        if (json.indexOf("omnicam-") < 0) return json;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray crops = root.optJSONArray("crops");
            if (crops == null) return json;
            for (int i = 0; i < crops.length(); i++) {
                org.json.JSONObject crop = crops.optJSONObject(i);
                if (crop == null) continue;
                double ratio = crop.optDouble("zoom", 0d);
                if (ratio > 0.01d) crop.put("zoom", ratio * base);
            }
            return root.toString();
        } catch (Throwable error) {
            return json;
        }
    }

    /**
     * Drops the zoom launch that RECT_SHOWN queues with an empty target.
     *
     * <p>Z1 hands the presenter a {@code CompositionStateMachine$b} every frame and, when the
     * fourth flag of that bundle is set, calls {@code CompositionViewDelegate.J0()} -- the
     * "executeZoom, currentZoom: X, targetZoom: Y" path. That flag is {@code state != FINISHED},
     * so any state we promote into still fires it, and the target comes from
     * {@code machine.j} which is {@code i * tracker.g()} -- zero without a tracker. The camera then
     * zooms to 0.6x, the widest lens: the preview opens up, the framing rect computed for the
     * tighter crop lands in the wrong place, and the session resets to IDLE a frame later. This is
     * exactly the report "现在好了，直接变小了，变成0.6X".
     *
     * <p>The call is synchronous inside Z1, so a thread flag set while the state was promoted is
     * enough to recognise it. Skipping it leaves the zoom where the answered crop put it, which is
     * the framing the user is looking at.
     */
    private static void hookZoomSkip(ClassLoader classLoader) {
        Class<?> view = XposedHelpers.findClassIfExists(
            "com.oplus.camera.feature.aicomposition.view.CompositionViewDelegate", classLoader);
        if (view == null) {
            Log.i(TAG, "view delegate missing, zoom skip off");
            return;
        }
        int hooked = 0;
        for (Method method : view.getDeclaredMethods()) {
            if (!"J0".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 0) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (ALIGN_SKIPPED.get() == null) return;
                    if (!"1".equals(prop("persist.omnicam.comp.skipzoom", "0"))) return;
                    param.setResult(null);
                    if (zoomSkips.incrementAndGet() <= LIMIT) {
                        Log.i(TAG, "executeZoom dropped: target would have been 0.0x");
                    }
                }
            });
            hooked++;
        }
        Log.i(TAG, "zoom skip hook: " + hooked);
    }

    private static final ThreadLocal<Boolean> ALIGN_SKIPPED = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger zoomSkips =
        new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger alignSkips =
        new java.util.concurrent.atomic.AtomicInteger();

    /** The phase this frame started in, so a transition can be reported with both ends. */
    private static final ThreadLocal<String> PHASE_IN = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger transitions =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * One line per phase change, with the numbers the launch depends on.
     *
     * <p>The framing itself is fine either way, so what has to be checkable from a log is the zoom:
     * RECT_SHOWN -&gt; ZOOM_TRIGGERED means the launch went out (and carries {@code j}, the ratio the
     * camera was asked for), ZOOM_TRIGGERED -&gt; FINISHED means the state machine accepted it. If the
     * first never appears the runnable that arms the launch never ran; if the second never appears
     * the camera is still chasing a target it will not accept.
     */
    private static void tracePhase(Object presenter) {
        String was = PHASE_IN.get();
        String now = phaseName(field(presenter, "M"));
        if (was == null || was.equals(now)) return;
        int seen = transitions.incrementAndGet();
        if (seen > LIMIT && seen % 60 != 0) return;
        Object machine = field(presenter, "M");
        Log.i(TAG, "phase " + was + " -> " + now
            + " i=" + field(machine, "i") + " j=" + field(machine, "j")
            + " e=" + field(machine, "e") + " f=" + field(machine, "f")
            + " recommended=" + recommendZoom);
    }

    private static Object stateNamed(Object phase, String wanted) {
        if (phase == null) return null;
        try {
            return XposedHelpers.callStaticMethod(phase.getClass(), "valueOf", wanted);
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Fallback for the case where the camera never arms its own zoom launch.
     *
     * <p>Normally the panel sits in RECT_SHOWN until a queued runnable sets {@code machine.e}, and
     * the next Z1 pass turns that into ZOOM_TRIGGERED + {@code ko.a.e1(target, true)}. That runnable
     * is gated on three session flags of its own ({@code o1}, {@code y1}, {@code x1}); if any of them
     * is wrong the panel shows the framing and simply never zooms, with nothing on screen to say so.
     * This offers the same call through the same entry point the camera uses, one second after the
     * rect appears, so the switch ({@code persist.omnicam.comp.directzoom}) can carry the behaviour
     * without another build.
     */
    private static void maybeZoomDirectly(Object presenter, Object machine) {
        if (!"1".equals(prop("persist.omnicam.comp.directzoom", "0"))) return;
        if (directDone) return;
        if (!"RECT_SHOWN".equals(phaseName(machine))) return;
        if (Boolean.TRUE.equals(field(machine, "e"))) return;
        float want = recommendZoom;
        if (want <= 1.05f) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (rectSince == 0) {
            rectSince = now;
            return;
        }
        if (now - rectSince < 1200L) return;
        directDone = true;
        try {
            Object container = XposedHelpers.callMethod(field(presenter, "e"), "Pd");
            if (container == null) {
                Log.i(TAG, "direct zoom: no container");
                return;
            }
            XposedHelpers.callMethod(container, "e1", want, true);
            if (directZooms.incrementAndGet() <= LIMIT) {
                Log.i(TAG, "zoom applied directly: " + want);
            }
        } catch (Throwable error) {
            Log.i(TAG, "direct zoom failed: " + error);
        }
    }

    /** When the framing rect first appeared this session; 0 until then. */
    private static volatile long rectSince;
    private static volatile boolean directDone;
    private static final java.util.concurrent.atomic.AtomicInteger directZooms =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The camera parses whatever we push at {@code ub.d.a(String)} and hands the parsed crops to
     * {@code vb.c3$c}, which validates them before anything reaches the screen. Watching those two
     * hand-offs separates "our JSON never parsed" from "it parsed and was then rejected" -- the two
     * cases we could not tell apart from the failure message alone.
     */
    private static void hookCropTrace(ClassLoader classLoader) {
        // Off by default. These hooks were installed unconditionally and the camera hands crops to
        // its presenter several dozen times a second while composition is open -- every one of them
        // went to logcat, from the same threads that were trying to draw the panel. That is the
        // "composition keeps restarting" feel and a steady background load at the same time.
        // persist.omnicam.croptrace=1 brings the whole trace back for a debugging session.
        if (!"1".equals(prop("persist.omnicam.croptrace", "0"))) return;
        Class<?> crop = XposedHelpers.findClassIfExists("ub.d", classLoader);
        if (crop != null) {
            for (Method method : crop.getDeclaredMethods()) {
                if (!"a".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 1 || types[0] != String.class) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object parsed = param.getResult();
                        if (param.getThrowable() != null) {
                            Log.i(TAG, "crop parse threw " + param.getThrowable());
                            return;
                        }
                        if (parsed == null) {
                            Log.i(TAG, "crop parse = null for " + shortJson((String) param.args[0]));
                            return;
                        }
                        Object boxes = field(parsed, "b");
                        int count = boxes == null ? 0 : java.lang.reflect.Array.getLength(boxes);
                        Log.i(TAG, "crop parse ok, boxes=" + count
                            + " input=" + shortJson((String) param.args[0]));
                    }
                });
                Log.i(TAG, "crop parser traced");
            }
        }
        Class<?> consumer = XposedHelpers.findClassIfExists("vb.c3$c", classLoader);
        if (consumer != null) {
            for (Method method : consumer.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"a".equals(method.getName()) || types.length != 2) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object result = param.args[1];
                        Object crops = null;
                        try {
                            crops = result == null ? null : XposedHelpers.callMethod(result, "c");
                        } catch (Throwable ignored) {
                            // a result shape we do not know; the count below reports it
                        }
                        int count = crops instanceof java.util.List ? ((java.util.List<?>) crops).size() : -1;
                        StringBuilder values = new StringBuilder();
                        if (crops instanceof java.util.List) {
                            for (Object item : (java.util.List<?>) crops) {
                                try {
                                    values.append(' ').append(XposedHelpers.callMethod(item, "g"));
                                } catch (Throwable ignored) {
                                    values.append(" ?");
                                }
                            }
                        }
                        boolean live = false;
                        try {
                            Object flag = XposedHelpers.callMethod(param.thisObject, "B3");
                            live = Boolean.TRUE.equals(flag);
                        } catch (Throwable ignored) {
                        }
                        Log.i(TAG, "presenter handed request=" + (param.args[0] == null ? "null" : "ok")
                            + " crops=" + count + " zooms=" + values
                            + " discarding=" + live);
                    }
                });
                Log.i(TAG, "presenter result traced");
            }
            for (Method method : consumer.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"b".equals(method.getName()) || types.length != 1) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "presenter failure callback, crops="
                            + (param.args[0] == null ? "null" : "present"));
                    }
                });
                Log.i(TAG, "presenter failure traced");
            }
        }
        // h4 turns the crop channel on (it is the only writer that sets the flag B3 tests), and T2
        // turns it back off. Seeing both in order says whether a round failed before or after the
        // crops arrived.
        Class<?> presenter = XposedHelpers.findClassIfExists("vb.c3", classLoader);
        if (presenter == null) return;
        for (Method method : presenter.getDeclaredMethods()) {
            if ("h4".equals(method.getName()) && method.getParameterTypes().length == 1) {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "crop channel on, arg=" + param.args[0]);
                    }
                });
                Log.i(TAG, "composition enter traced");
            }
            if ("T2".equals(method.getName()) && method.getParameterTypes().length == 0) {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "crop channel off (T2)");
                    }
                });
                Log.i(TAG, "composition reset traced");
            }
        }
    }

    private static String shortJson(String json) {
        return json == null ? "null" : (json.length() <= 160 ? json : json.substring(0, 160) + "...");
    }

    /**
     * The camera's own canned framing, kept verbatim so the panel has something valid to draw while
     * the model is still thinking. Reusing the stock JSON (rather than a full-frame crop) means the
     * suggestion appears immediately instead of after a two second blank.
     */
    private static final String STOCK_JSON = "{\"outputNum\":2,\"crops\":["
        + "{\"zoom\":3.0,\"srcCrop\":[168,306,424,648],\"version\":\"lite2-f6d1b2d_dirty-20260427_170906\","
        + "\"confidenceLevel\":100,\"compositionDesc\":{\"title\":\"\",\"desc1\":\"保留完整身形与背景环境，呈现真实工作场景。\",\"desc2\":\"\",\"desc3\":\"\"}},"
        + "{\"zoom\":4.76279,\"srcCrop\":[208,349,369,564],\"version\":\"lite2-f6d1b2d_dirty-20260427_170906\","
        + "\"confidenceLevel\":100,\"compositionDesc\":{\"title\":\"\",\"desc1\":\"聚焦人物上半身与面部神态，弱化背景干扰。\",\"desc2\":\"\",\"desc3\":\"\"}}]}";

    private static final java.util.concurrent.atomic.AtomicInteger corrections =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Passes refused because no composition session was open -- the runaway loop, counted. */
    private static final java.util.concurrent.atomic.AtomicInteger idle =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Passes answered by the on-device detector instead of the model. */
    private static int localAnswers;

    /**
     * The framing this session already recommended, plus the {@code sessionAt} it was frozen for.
     * Repeating the same answer every pass keeps the camera's align-to-target guide convergent;
     * recomputing it per frame makes the target walk away from the user. Stamped with the session
     * so a new arming starts from a fresh recommendation without any reset bookkeeping.
     */
    private static volatile String frozenAnswer;
    private static volatile long frozenFor = -1L;

    private static final XC_MethodHook STREAMING = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            lastStreamAt = android.os.SystemClock.uptimeMillis();
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++streams;
            }
            if (index > LIMIT) return;
            Object buffer = param.args[0];
            String size = "null";
            if (buffer instanceof HardwareBuffer) {
                HardwareBuffer hb = (HardwareBuffer) buffer;
                size = hb.getWidth() + "x" + hb.getHeight() + (hb.isClosed() ? "(closed)" : "");
            }
            Log.i(TAG, "streaming #" + index + " t+" + (android.os.SystemClock.uptimeMillis() - startedAt) + "ms buffer=" + size
                + " cb=" + (param.args.length > 1 && param.args[1] != null
                    ? param.args[1].getClass().getName() : "null"));
            // This is the camera's own stream callback -- the object the composition engine would
            // answer into. The worker's own callback parameter is an internal adapter whose shape
            // differs between builds, so the answer is routed through this one instead.
            if (rideMode() && param.args.length > 1 && param.args[1] != null) {
                rideCallback = param.args[1];
            }
            if ("1".equals(prop("persist.omnicam.realtrack2", ""))) probeTracker(param.thisObject, buffer);
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++streams;
            }
            boolean ok = Boolean.TRUE.equals(param.getResult());
            if (index <= LIMIT || index % 120 == 0) {
                Log.i(TAG, "streaming #" + index + " result=" + param.getResult()
                    + " error=" + param.getThrowable());
            }
            Object callback = param.args.length > 1 ? param.args[1] : null;
            if (callback == null) return;
            noteStream();
            if (!ok && holdCropFlag()) {
                if (!armed()) {
                    // No session means the camera is asking outside a request it should have: this
                    // is the runaway case, and answering it is what kept it runaway. Leaving the
                    // call failed is the camera's own "nothing to compose" answer, the one the
                    // stock build produces on this device because the engine is not in it.
                    if (idle.incrementAndGet() <= 3) {
                        Log.i(TAG, "pass ignored: composition session closed");
                    }
                    return;
                }
                // The helper forwards to a composition engine that is not in this build, so this
                // call can only ever answer "not handled". The worker treats a pass as handled only
                // when the call returned true AND the callback's crop flag is set, so answer the
                // callback the way the camera's own canned branch does -- onEvent(1) then
                // onEvent(2), which is what sets that flag -- and report the call as handled.
                if (armed()) maybeAskCloud(callback, param.args[0]);
                // Nothing from the model yet -- or this session is local-only, where it will never
                // arrive. Borrowing the camera's demo answer while the request is in flight is what
                // made the panel snap to its fixed 3.0x / 4.76x pair for two seconds and then jump
                // back when the model landed. Decide on the device instead, and only fall back to
                // the demo answer when the detector has nothing to say either.
                boolean deviceOnly = localNow();
                String json = deviceOnly ? null : cloudGiven;
                // One recommendation per session, not one per frame.
                //
                // The camera runs a native "align to the target point" guide once the state machine
                // reaches TRACKING/APPROACHING (vb.c3.Z1, offset 0x04fa: it shows
                // camera_ai_composition_align_guide_point_1 and paints the white dot). That guide
                // only converges if the suggested framing stays put while the user moves the phone.
                // Recomputing SubjectLocator.follow() on every pass makes the target drift with the
                // frame, so the distance never closes and the panel sits on "请移动手机，对准目标点"
                // forever. Freeze the answer for the life of the arming and keep pushing the same
                // JSON: the push must still happen every pass (cropHandled is a per-pass flag), only
                // the content repeats.
                boolean freeze = "1".equals(prop("persist.omnicam.comp.freeze", "1"));
                if (json == null && freeze && frozenFor == sessionAt && frozenAnswer != null) {
                    json = frozenAnswer;
                }
                if (json == null) {
                    json = param.args[0] instanceof HardwareBuffer
                        ? SubjectLocator.follow((HardwareBuffer) param.args[0]) : null;
                    if (json != null && json.isEmpty()) json = SubjectLocator.current();
                    if (json != null) {
                        if (freeze) {
                            frozenAnswer = json;
                            frozenFor = sessionAt;
                        }
                        int seen;
                        synchronized (RealsceneProbe.class) {
                            seen = ++localAnswers;
                        }
                        if (seen <= LIMIT || seen % 40 == 0) {
                            Log.i(TAG, "device answer #" + seen + " " + json.length() + " chars ("
                                + (deviceOnly ? "tap mode" : "model still thinking") + ")");
                        }
                    }
                }
                // Never the camera's own answer here. That one is a fixed 3.0x / 4.76x pair, and the
                // panel applies the tighter of the two -- which is exactly the "it jumps to 4.7x
                // every time I open composition" report. A full-frame 1.0x answer is valid, marks
                // the pass as handled and leaves the preview exactly where it is.
                if (json == null) json = DEMO_JSON;
                // Logged only for the first few passes. This runs on every composition frame the
                // camera asks for, and the camera asks dozens of times a second while the panel is
                // open: one line each was enough load on its own to make composition stutter.
                if (corrections.get() <= LIMIT || corrections.get() % 120 == 0) {
                    Log.i(TAG, "answering #" + corrections.get() + " with " + json.length() + " chars");
                }
                // Unconditional, never deduped: "handled" is a flag on the callback and
                // it starts out false for every pass, so a skipped push leaves the worker reading
                // cropHandled false and the pass fails no matter what this call returned. The
                // camera's own canned branch answers every pass for exactly that reason.
                boolean pushed = push(callback, json);
                // onEvent(1) sets that flag as its first instruction (wb.p.onEvent, offset 0x58:
                // AtomicBoolean d .set(true)), and the worker reads it a few instructions after this
                // call returns. Clearing it -- which this code used to do on every pass -- is what
                // made every answer fail; it is now behind an explicit switch only, to reproduce it.
                Object flag = field(callback, "d");
                boolean handled = flag instanceof java.util.concurrent.atomic.AtomicBoolean
                    && ((java.util.concurrent.atomic.AtomicBoolean) flag).get();
                if ("1".equals(prop("persist.omnicam.compclear", "0"))
                    && flag instanceof java.util.concurrent.atomic.AtomicBoolean) {
                    ((java.util.concurrent.atomic.AtomicBoolean) flag).set(false);
                    handled = false;
                }
                param.setResult(Boolean.TRUE);
                if (corrections.incrementAndGet() <= LIMIT) {
                    Log.i(TAG, "streaming had no engine, answered locally pushed=" + pushed
                        + (flag == null ? " (no crop flag)" : " cropHandled=" + handled));
                }
                return;
            }
            // The answer now travels through the worker's own callback; a success returned here
            // would describe an engine result that does not exist. Leave the real answer alone.
            if (rideMode()) return;
            if (armed()) {
                // Locking a subject is the user taking over: drop the model's static framing and
                // let the tracker drive, and let a fresh request happen once the lock is released.
                if (SubjectLocator.pinned() != null && cloudGiven != null) {
                    cloudGiven = null;
                    cloudAnswered = null;
                }
                // Kick off the model request as early as possible: it takes seconds, and the local
                // answer below has to stand in for it meanwhile.
                maybeAskCloud(callback, param.args[0]);
                // Once the model has answered, that framing is what the user asked for. Replacing it
                // with the detector's next guess a moment later is what made the panel look like it
                // was re-composing on its own, over and over.
                if (localNow()) {
                    cloudPending = null;
                    cloudGiven = null;
                }
                String given = cloudPending != null ? cloudPending : cloudGiven;
                if (cloudPending != null) {
                    cloudGiven = cloudPending;
                    cloudPending = null;
                }
                if (given != null) {
                    // Every pass, not only the first one for this callback: the crop flag lives on
                    // the callback and is only set by onEvent, so a pass answered any other way is
                    // logged by the camera as "streaming failed" and resets the panel.
                    push(callback, given);
                    answered = callback;
                    param.setResult(Boolean.TRUE);
                    return;
                }
                String json = param.args[0] instanceof HardwareBuffer
                    ? SubjectLocator.follow((HardwareBuffer) param.args[0]) : null;
                if (json == null) {
                    // Staying silent makes the camera fall back to its own canned answer, which is
                    // 3x on this build -- that is the "it suddenly jumps to 3x" report. Answer with
                    // a full-frame crop at zoom 1.0 instead: valid, and it changes nothing on
                    // screen while the detector has nothing to say.
                    push(callback, DEMO_JSON);
                    answered = callback;
                    param.setResult(Boolean.TRUE);
                    return;
                }
                // "" means the box is already on screen and has not moved; the framing still has to
                // be repeated, because the answer is what marks the pass as handled.
                if (json.isEmpty()) {
                    String held = SubjectLocator.current();
                    json = held != null ? held : DEMO_JSON;
                }
                push(callback, json);
                answered = callback;
                param.setResult(Boolean.TRUE);
                return;
            }
            if (ok || !"1".equals(prop("persist.omnicam.demoinject", ""))) return;
            if (push(callback, DEMO_JSON)) Log.i(TAG, "demo crop pushed");
        }
    };

    /**
     * The camera asks for a composition frame again every few seconds on its own. Dropping a repeat
     * answer was tried here and reverted: the crop flag is set by the answer itself, so a dropped
     * repeat leaves the pass unhandled and the camera resets the panel -- a churning panel was the
     * lesser evil, and the camera's own canned branch answers every pass as well.
     */
    private static boolean push(Object callback, String json) {
        try {
            json = absoluteZoom(json, framingBase);
            noteRecommendZoom(json);
            // Event 1 is the crop the panel draws. Event 2 is the "answer delivered" signal, and the
            // camera's own canned path sends exactly this pair before treating the pass as handled
            // (wb.r.f: onEvent(1, json); onEvent(2, json)), so an answer that stops at event 1 is not
            // the protocol -- it is our own invention, and it left the pass waiting forever.
            XposedHelpers.callMethod(callback, "onEvent", 1, json);
            if (!"0".equals(prop("persist.omnicam.comp.twice", "1"))) {
                XposedHelpers.callMethod(callback, "onEvent", 2, json);
            }
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++injects;
            }
            if (index <= 6 || index % 40 == 0) {
                Log.i(TAG, "inject #" + index + " " + json);
            }
            afterPush();
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "inject failed", error);
            return false;
        }
    }

    /** Following is on unless explicitly switched off; predates nothing, it is the new default. */
    private static boolean following() {
        return !"0".equals(prop("persist.omnicam.realfollow", "1"));
    }

    /**
     * Ride the camera's own answer path instead of reporting success for an engine that is absent.
     *
     * <p>{@code OplusAICompositionHelper.runCompositionStreaming} delegates to
     * {@code com.oplus.camera.aicomposition.OplusAIComposition}, a class that is referenced by this
     * build but not shipped in it (no {@code OplusAIComposition} in any of the 33 dex files, while
     * the state machine next to it is there). It can therefore only ever answer "not handled".
     *
     * <p>Reporting true for it anyway -- what the module used to do -- makes the worker skip the
     * camera's own answer branch while the effect output that
     * {@code captureCompositionEffectTexture} needs was never rendered: the shutter then fails with
     * "invalid zoom result" and the photo is dropped, silently, with the preview stuck on
     * "saving, please wait". So do not answer anything: force the worker onto its canned-answer
     * branch, which is the one this build really ships and the one the shutter understands, and
     * substitute our own crop at the callback where that answer is handed over.
     */
    private static boolean rideMode() {
        return "1".equals(prop("persist.omnicam.comp.ride", "0"));
    }

    /* -------------------------------------------------------------- cloud model */

    /**
     * At most one model request per this window; each one leaves the device and costs seconds.
     *
     * <p>Fifteen seconds was the first guess and it made the panel look dead: leaving composition
     * and opening it again a few seconds later -- which is exactly how a user retries after a bad
     * suggestion -- did not ask the model at all, so the answer on screen was whatever the previous
     * request had said about a different framing. Three seconds is still long enough that the
     * camera's own repeated calls cannot stack up, and {@code CloudComposer.busy()} already prevents
     * two requests from being in flight at once.
     */
    private static final long CLOUD_GAP = 3000L;

    private static volatile Object cloudAnswered;
    private static volatile long cloudAt;
    private static volatile String cloudPending;
    /** The model answer currently on screen, held steady so it is not overwritten frame by frame. */
    private static volatile String cloudGiven;
    /** The worker's live answer channel, kept here so the model reply has somewhere to land. */
    private static volatile Object rideCallback;
    /**
     * Long-press on the entry icon: this session decides on the device only and never uploads a
     * frame. Tap stays on the model; long-press is the local mode.
     */
    private static volatile boolean localOnly;
    private static final android.os.Handler MAIN =
        new android.os.Handler(android.os.Looper.getMainLooper());

    static boolean localOnly() {
        return localNow();
    }

    /** When the long press that asked for the device took place; 0 while the model is in charge. */
    private static volatile long localAt;
    /**
     * How long a long press stays in force. It is not really a mode -- it is one request -- and
     * without an expiry a press that never got cancelled would leave the camera deciding on the
     * device for the rest of the process, with no way back that the user can see.
     */
    private static final long LOCAL_TTL = 120000L;

    /** Called before anything reads {@link #localOnly}: an expired request is no request. */
    private static boolean localNow() {
        if (localOnly && android.os.SystemClock.uptimeMillis() - localAt > LOCAL_TTL) {
            localOnly = false;
            localAt = 0;
            Log.i(TAG, "local-only expired");
        }
        return localOnly;
    }

    /**
     * A tap on the viewfinder: decide this composition on the device, about the point tapped.
     *
     * <p>This is the whole channel switch now. Doing nothing leaves the framing to the model, which
     * is what the panel opens with; tapping the frame asks for the on-device detector, and a tap is
     * exactly the gesture that also puts the camera's focus box on the subject. The old rule (tap
     * the icon for the model, hold it for the device) was invisible on screen: the press that
     * switched back drew nothing and opened nothing, so it read as a dead gesture.
     *
     * <p>The pin is deliberately left alone here -- the caller sets it to the tap point.
     */
    static void forceLocal() {
        localOnly = true;
        localAt = android.os.SystemClock.uptimeMillis();
        // Any answer the model already produced describes a different subject; it must not be
        // pushed as if it were the one that was just picked.
        clearCloudSession();
        cloudAt = 0;
        SubjectLocator.dropHeld();
        frozenAnswer = null;
        Log.i(TAG, "local-only mode: the frame stays on the device");
    }

    /** Back to the model. A shot and leaving the camera both end the request. */
    static void releaseLocal() {
        if (!localOnly) return;
        localOnly = false;
        localAt = 0;
        cloudAt = 0;
        SubjectLocator.dropHeld();
        Log.i(TAG, "local-only released: back to the model");
    }

    /**
     * Switches this composition session between the model and the on-device detector.
     *
     * @return the mode now in force: true when the device decides on its own
     */
    static boolean toggleLocalOnly() {
        localOnly = !localOnly;
        if (localOnly) {
            localAt = android.os.SystemClock.uptimeMillis();
            clearCloudSession();
        } else {
            localAt = 0;
            cloudAt = 0;
        }
        // The next pass has to answer differently straight away, so the held answer must go too.
        SubjectLocator.dropHeld();
        frozenAnswer = null;
        Log.i(TAG, localOnly
            ? "local-only mode: the frame stays on the device"
            : "model mode: the next pass may ask the cloud again");
        return localOnly;
    }

    /**
     * Offers the current frame to the vision-aesthetics model behind the gallery's AI inspiration
     * mode. The camera ships a client for that unit and never calls it, so the request is made
     * here and the answer is injected like any other suggestion.
     *
     * <p>Not while a subject is locked: that path is a real-time tracker, and a request that takes
     * seconds would only race it. Not more than once per {@link #CLOUD_GAP} either, since every
     * request uploads the frame.
     */
    /**
     * A shot, a pause or a fresh lock all end the composition session. The model answer belongs to
     * that session, so it goes with it; the next session is free to ask again.
     */
    private static void clearCloudSession() {
        cloudGiven = null;
        cloudPending = null;
        cloudAnswered = null;
    }

    private static void maybeAskCloud(Object callback, Object buffer) {
        if (localNow()) return;
        if (!CloudComposer.enabled() || !(buffer instanceof HardwareBuffer)) return;
        if (SubjectLocator.pinned() != null || CloudComposer.busy()) return;
        // One answer per scene: while a model framing is on screen, re-asking would only produce a
        // different box every few seconds, which reads as the camera composing on its own.
        if (cloudGiven != null) return;
        if (callback == cloudAnswered) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (cloudAt != 0 && now - cloudAt < CLOUD_GAP) return;
        cloudAt = now;
        cloudAnswered = callback;
        android.graphics.Bitmap frame = SubjectLocator.snapshot((HardwareBuffer) buffer);
        if (frame == null) return;
        Log.i(TAG, "asking the vision model");
        try {
            CloudComposer.submit(frame, (json, summary) -> {
                Log.i(TAG, "model answer: " + summary);
                if (json == null) return;
                cloudPending = json;
                if (rideMode()) {
                    // The answer has to arrive on the camera's own callback, at the moment the camera
                    // asks for it. Pushing it into wb.p directly lands on a callback the worker has
                    // already closed, which is why the panel kept saying "no suggestion". Publishing
                    // the framing and asking for one more composition frame lets the camera deliver
                    // it through its normal path, where rideAnswer swaps the stock crop for this one.
                    cloudGiven = json;
                    cloudPending = null;
                    // The answer belongs to the request that asked for it, and by the time a model
                    // reply lands the burst that started it may already have hit its cap. Open a
                    // fresh session for it, otherwise the answer would arrive with nobody to take
                    // it and the panel would keep showing the stock crops.
                    MAIN.post(() -> {
                        reopenComposition();
                        kick();
                    });
                    return;
                }
                // The callback that started this is almost certainly gone by now; ask for one more
                // frame so the answer has somewhere to go.
                reopenComposition();
                kick();
            });
        } finally {
            if (!frame.isRecycled()) frame.recycle();
        }
    }

    /**
     * Fallback answer when no subject was located. It has to be valid, but it must not change
     * anything either: a crop covering the whole frame at zoom 1.0 leaves the preview exactly as
     * it is, where the camera's own canned answer asks for 3x and visibly jumps on entry.
     */
    private static final String DEMO_JSON = "{\"outputNum\":1,\"crops\":[{\"zoom\":1.0,"
        + "\"srcCrop\":[0,0,768,1024],\"version\":\"omnicam-probe\",\"confidenceLevel\":100,"
        + "\"compositionDesc\":{\"title\":\"当前取景\","
        + "\"desc1\":\"暂未识别到明确主体，保持当前取景即可。\","
        + "\"desc2\":\"\",\"desc3\":\"\"}}]}";

    /**
     * The missing half of the pipeline is the decision, not the tracking: libAITracker loads and
     * reports "Tracker Created!", while libai_camera_composition_lite2 only exists in its MediaTek
     * build, so runCompositionStreaming can track a subject but never turns it into a crop.
     * Walking the helper's fields finds the live tracker object, whose track() returns the
     * subject box that the absent library would have consumed.
     */
    private static void probeTracker(Object helper, Object buffer) {
        if (!(buffer instanceof HardwareBuffer) || helper == null) return;
        if (!helperDumped) {
            helperDumped = true;
            for (Field field : helper.getClass().getDeclaredFields()) {
                String value = "?";
                try {
                    field.setAccessible(true);
                    Object held = field.get(helper);
                    value = held == null ? "null" : String.valueOf(held);
                } catch (Throwable error) {
                    value = "unreadable " + error;
                }
                Log.i(TAG, "helper." + field.getName() + " : " + field.getType().getName()
                    + " = " + value);
            }
        }
        HardwareBuffer hb = (HardwareBuffer) buffer;
        Object tracker = findTracker(helper);
        if (tracker == null) {
            Log.i(TAG, "no OplusAIComposition field on the helper");
            return;
        }
        if (!trackerInited) {
            trackerInited = true;
            try {
                XposedHelpers.callMethod(helper, "initTracker",
                    "/data/user/0/com.oplus.camera/files");
                Log.i(TAG, "initTracker issued");
            } catch (Throwable error) {
                Log.i(TAG, "initTracker failed " + error);
            }
        }
        try {
            Object box = XposedHelpers.callMethod(tracker, "track", hb,
                hb.getWidth(), hb.getHeight(), new float[]{1.0f}, 0L);
            Log.i(TAG, "track -> " + describe(box) + " handle=" + longField(tracker, "mTrackerHandle"));
        } catch (Throwable error) {
            Log.i(TAG, "track failed " + error);
        }
    }

    private static Object longField(Object host, String name) {
        if (host == null) return "?";
        try {
            Field field = host.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(host);
        } catch (Throwable error) {
            return "?" + error;
        }
    }

    private static Object findTracker(Object host) {
        for (Field field : host.getClass().getDeclaredFields()) {
            if (!field.getType().getName().contains("OplusAIComposition")) continue;
            try {
                field.setAccessible(true);
                return field.get(host);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String describe(Object value) {
        if (!(value instanceof float[])) return String.valueOf(value);
        float[] data = (float[]) value;
        StringBuilder out = new StringBuilder("float[" + data.length + "] ");
        for (int i = 0; i < data.length && i < 16; i++) {
            if (i > 0) out.append(',');
            out.append(String.format(java.util.Locale.US, "%.3f", data[i]));
        }
        return out.toString();
    }

    /** The callback the worker talks to; onEvent(int, String) carries the JSON the UI renders. */
    private static void hookCallback(ClassLoader classLoader) {
        Class<?> owner = XposedHelpers.findClassIfExists("wb.p", classLoader);
        if (owner == null) {
            Log.i(TAG, "wb.p not in this build");
            return;
        }
        int hooked = 0;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"onEvent".equals(method.getName())) continue;
            if (types.length != 2 || types[0] != int.class || types[1] != String.class) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, CALLBACK);
            hooked++;
        }
        Log.i(TAG, "onEvent hooked: " + hooked);
    }

    private static final XC_MethodHook CALLBACK = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (rideMode()) rideAnswer(param);
            int index;
            synchronized (RealsceneProbe.class) {
                index = ++events;
            }
            if (index > LIMIT) return;
            String json = String.valueOf(param.args[1]);
            if (json.length() > 220) json = json.substring(0, 220) + "...";
            Log.i(TAG, "onEvent(" + param.args[0] + ") " + json);
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (!holdCropFlag()) return;
            Object event = param.args.length > 0 ? param.args[0] : null;
            if (!(event instanceof Integer) || (Integer) event != 1) return;
            // onEvent(1, ..) marks the crop as handled (field d) and the worker reads that very flag
            // a few instructions after the helper returns: "started && handled" is the success
            // condition, and "handled == false" is what it logs as "runComposition, streaming
            // failed" before calling onInvalidZoomResult. This hook used to clear the flag right
            // here, on the theory that the camera was contradicting itself -- it was not: it was
            // reading back what this hook had just written. Clearing it here made every pass fail.
            // The switch below only exists to reproduce that failure for A/B.
            Object flag = field(param.thisObject, "d");
            if (!(flag instanceof java.util.concurrent.atomic.AtomicBoolean)) return;
            java.util.concurrent.atomic.AtomicBoolean crop = (java.util.concurrent.atomic.AtomicBoolean) flag;
            if ("1".equals(prop("persist.omnicam.compclear", "0"))) {
                crop.set(false);
                if (holds.incrementAndGet() <= LIMIT) Log.i(TAG, "crop flag cleared on purpose (compclear=1)");
                return;
            }
            if (kept.incrementAndGet() <= LIMIT) Log.i(TAG, "crop flag kept, cropHandled=" + crop.get());
        }
    };

    private static final java.util.concurrent.atomic.AtomicInteger kept =
        new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger holds =
        new java.util.concurrent.atomic.AtomicInteger();

    /** On unless explicitly switched off; without it composition dies after its canned crops. */
    private static boolean holdCropFlag() {
        return !"0".equals(prop("persist.omnicam.comp.hold", "1"));
    }

    /**
     * Swaps the worker's canned crop for the model's, at the one place the camera hands an answer
     * to its state machine. Everything else about the request -- when it starts, that it is
     * "handled", how the zoom and the effect output are derived -- stays the camera's own, which is
     * why the shutter keeps working.
     */
    private static void rideAnswer(XC_MethodHook.MethodHookParam param) {
        if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
        String given = (String) param.args[1];
        // Only the camera's own crops are replaced; anything else on this callback is not ours.
        if (!given.contains("\"srcCrop\"")) return;
        String ours;
        if (cloudPending != null) {
            ours = cloudPending;
            cloudPending = null;
            cloudGiven = ours;
        } else if (cloudGiven != null) {
            ours = cloudGiven;
        } else {
            // Nothing from the model yet. Leave the camera's own crop alone: blanking it to a
            // full-frame 1.0 makes the panel read as "no suggestion" for the first couple of
            // seconds, which is worse than showing the stock box until the model answers.
            return;
        }
        if (ours.equals(given)) return;
        param.args[1] = ours;
        if (rewrites.incrementAndGet() <= LIMIT) {
            Log.i(TAG, "answer -> " + (ours.length() > 120 ? ours.substring(0, 120) + "..." : ours));
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger rewrites =
        new java.util.concurrent.atomic.AtomicInteger();

    private static boolean bool(Object host, String name) {
        if (host == null) return false;
        try {
            Field field = findField(host.getClass(), name);
            if (field == null) return false;
            field.setAccessible(true);
            return field.getBoolean(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The state machine's current phase ({@code CompositionStateMachine.a}). The align-to-target
     * guide only appears while it reads TRACKING or APPROACHING, so seeing which of the two the
     * panel sits in is what tells "the guide is waiting for the user" apart from "the guide is
     * stuck because the suggestion keeps moving".
     */
    private static String phaseName(Object machine) {
        Object phase = field(machine, "a");
        if (phase == null) return "?";
        String name = String.valueOf(phase);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static Object field(Object host, String name) {
        if (host == null) return null;
        try {
            Field field = findField(host.getClass(), name);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(host);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The obfuscated camera keeps these flags on base classes as often as on the class itself, and
     * {@code getDeclaredField} only looks at one level. Silently missing them made every boolean
     * probe report false, which is how the worker looked as if it were taking its "engine absent"
     * branch while it was really calling an engine that is not in this build at all.
     */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> walk = type; walk != null; walk = walk.getSuperclass()) {
            try {
                return walk.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static int intField(Object host, String name) {
        if (host == null) return -1;
        try {
            Field field = host.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(host);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class)
                .invoke(null, key, fallback);
            return String.valueOf(value);
        } catch (Throwable error) {
            return fallback;
        }
    }
}
