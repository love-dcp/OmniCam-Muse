package local.jiege.hook.gr;

import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.xpan.XpanLiveFeature;

/**
 * Read-only probes for the master live-photo LUT task after a GR visit.
 *
 * <p>The previous recovery mixed three assumptions in one hook: {@code view.r.a == 0} selected the
 * LUT task, the task needed a replacement watermark processor, and a pending task needed a replayed
 * {@code gh.p3.onInit()}. Decompilation disproved the first assumption: the constructor reached
 * from {@code CaptureParamView.mb()} always stores {@code a=0}, and that branch updates the
 * parameter UI. The switch's target for key 1 is the LUT branch. This class deliberately performs
 * no recovery. It records the real task key, callback type, target pipeline and renderer state so
 * the next observation can distinguish "task ran" from "task changed the live renderer".
 */
final class GrMasterLiveLutDiagnostic {
    private static final String TAG = "RicohGrPort";
    private static final String PIPELINE = "@VideoEffectPipeline";
    private static final String IMAGE_DATA =
        "com.oplus.camera.feature.livephoto.data.ImageData";
    private static final String LIVE_RENDERER = "@WarpRenderer";
    private static final String LUT_MANAGER_HOLDER =
        "com.oplus.camera.common.utils.Lut3dDataManager$b";
    private static final int MASTER_LIVE_LUT_TYPE = 7;

    private static final AtomicLong sessionId = new AtomicLong();
    private static volatile Object armedPipeline;
    private static volatile long armedSession;
    private static volatile long armedAt;
    private static volatile long callbackSession;
    private static volatile long completedTaskSession;
    private static volatile long failedTaskSession;
    private static volatile String lastRenderSignature;
    private static volatile long lastRenderLogAt;
    private static volatile String lastManagerState;
    private static volatile long lastManagerLogAt;
    private static volatile long lastComposeDumpAt;

    private GrMasterLiveLutDiagnostic() {}

    /** Inner class of a resolved outer class ("a" -> Outer$a); null if absent on this build. */
    private static Class<?> inner(Class<?> outer, String simple) {
        for (Class<?> candidate : outer.getDeclaredClasses()) {
            if (candidate.getName().endsWith("$" + simple)) return candidate;
        }
        return null;
    }

    /**
     * First captured reference of the task: an instance of "want" if one exists, else any
     * non-primitive non-table field. Field names re-obfuscate every release; the roles do not.
     */
    private static Object firstRefField(Object task, Class<?> want) {
        Class<?> cls = task.getClass();
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v;
            try { v = f.get(task); } catch (Throwable error) { continue; }
            if (v != null && want != null && want.isInstance(v)) return v;
        }
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v;
            try { v = f.get(task); } catch (Throwable error) { continue; }
            if (v != null && !(v instanceof float[])) return v;
        }
        return null;
    }

    /** First float[] field of the task (the stock table slot, "d" on older builds). */
    private static float[] firstTableField(Object task) {
        for (Field f : task.getClass().getDeclaredFields()) {
            if (f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v;
            try { v = f.get(task); } catch (Throwable error) { continue; }
            if (v instanceof float[]) return (float[]) v;
        }
        return null;
    }

    private static void setTableField(Object task, float[] value) {
        for (Field f : task.getClass().getDeclaredFields()) {
            if (f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v;
            try { v = f.get(task); } catch (Throwable error) { continue; }
            if (v instanceof float[]) {
                try {
                    f.set(task, value);
                } catch (Throwable error) {
                    // inaccessible on this build; the swap is optional
                }
                return;
            }
        }
    }

    /** int field value by name, or -1 when the name drifted to another type (log-only use). */
    private static int intFieldSafe(Object task, String name) {
        try {
            return XposedHelpers.getIntField(task, name);
        } catch (Throwable error) {
            return -1;
        }
    }

    static void install(ClassLoader classLoader) throws Throwable {
        Class<?> pipeline = Symbols.get().cls("VideoEffectPipeline");
        Class<?> imageData = XposedHelpers.findClass(IMAGE_DATA, classLoader);
        Class<?> liveRenderer = Symbols.get().cls("WarpRenderer");
        Class<?> lutCallback = inner(pipeline, "a");
        Class<?> managerHolder = XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);
        Class<?> manager = XposedHelpers.findClass(
            "com.oplus.camera.common.utils.Lut3dDataManager", classLoader);
        /*
         * The capture-param task class carries the stock table (task.d) into the GL thread.
         * Every point release re-obfuscates its name, so resolve it by the fingerprint strings
         * it shares with unrelated compiler-generated wrappers ("multi") and let the hook body
         * gate on the pipeline/callback relation -- wrong-class run() bodies never match.
         */
        java.util.List<Class<?>> lutTasks = new java.util.ArrayList<>();
        for (Object entry : Symbols.get().all("LutTask")) {
            if (entry instanceof Class) lutTasks.add((Class<?>) entry);
        }
        Method onInit = XposedHelpers.findMethodExact(pipeline, "onInit");
        Method renderFrame = XposedHelpers.findMethodExact(pipeline, "r", imageData);

        /*
         * r471: dump the CombineLutHelper parameter state (the m String[] argument pack and
         * the scalar fields) whenever MASTER mode composes a table. Master filters reach the
         * live video through this composition, and the XPAN manager state has all-null names
         * (r467 names=[null x64]) -- the layout captured here is what the XPAN repair needs
         * to inject the active filter into its own composition.
         */
        XposedBridge.hookAllMethods(manager, "p", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object state = param.thisObject;
                if (state == null) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastComposeDumpAt < 30000L) return;
                if (!GrPreviewStreamRestore.inMasterMode()) return;
                lastComposeDumpAt = now;
                StringBuilder names = new StringBuilder();
                Object pack = XposedHelpers.getObjectField(state, "m");
                if (pack instanceof String[]) {
                    String[] entries = (String[]) pack;
                    for (int i = 0; i < entries.length; i++) {
                        if (entries[i] != null) {
                            names.append(i).append('=').append(entries[i]).append(' ');
                        }
                    }
                } else {
                    names.append("not-a-array");
                }
                Log.i(TAG, "master compose params: names=[" + names.toString().trim() + "]"
                    + ", x=" + XposedHelpers.getIntField(state, "x")
                    + ", y=" + XposedHelpers.getIntField(state, "y")
                    + ", v=" + XposedHelpers.getIntField(state, "v")
                    + ", w=" + XposedHelpers.getIntField(state, "w")
                    + ", z=" + XposedHelpers.getFloatField(state, "z")
                    + ", A=" + XposedHelpers.getFloatField(state, "A")
                    + ", B=" + XposedHelpers.getFloatField(state, "B"));
            }
        });

        XposedBridge.hookMethod(onInit, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object instance = param.thisObject;
                /*
                 * Arm for the type-7 live pipeline itself, not only after a GR exit. On a cold
                 * start the mode hook has not published "professional" yet, so gating on
                 * currentMode suppressed the exact direct-master comparison we need.
                 */
                if (!isMasterLivePipeline(instance, liveRenderer, pipeline)) return;
                armedPipeline = instance;
                armedSession = sessionId.incrementAndGet();
                armedAt = SystemClock.elapsedRealtime();
                callbackSession = 0L;
                completedTaskSession = 0L;
                failedTaskSession = 0L;
                lastRenderSignature = null;
                lastRenderLogAt = 0L;
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object instance = param.thisObject;
                if (instance != armedPipeline) return;
                Log.i(TAG, "master live LUT diagnostic init armed"
                    + ", session=" + armedSession
                    + ", " + describePipeline(instance));
            }
        });

        if (lutCallback != null) XposedBridge.hookAllMethods(lutCallback, "b", new XC_MethodHook() {
            /*
             * r426: the r425 delivered-array replacement was reverted. It proved the task
             * data source is manager.c (taskData kept cb792680 after the swap), so the swap
             * never reached the grading table; it only perturbed stock's fallback path and
             * regressed the direct-XPAN capture (washed-out look). The real fight is upstream:
             * stock keeps recomputing e(1,0,"professional") into manager.c after a GR/master
             * exit -- that is redirected in the manager.e hook below.
             */
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object instance = outerPipeline(param.thisObject, pipeline);
                if (instance == null) return;
                boolean target = isTargetMode();
                boolean masterLive = isMasterLivePipeline(instance, liveRenderer, pipeline);
                long currentSession = armedPipeline == instance ? armedSession : 0L;
                if (masterLive) callbackSession = currentSession;
                Log.i(TAG, "master live LUT diagnostic callback"
                    + ", targetMode=" + target
                    + ", masterLive=" + masterLive
                    + ", threw=" + param.hasThrowable()
                    + ", session=" + currentSession
                    + ", callback=" + param.thisObject.getClass().getName()
                    + ", callbackData=" + describeTable(floatArray(param.args, 0))
                    + ", " + describeTables(instance, managerHolder)
                    + ", " + describePipeline(instance));
            }
        });

        XC_MethodHook taskHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object callback = firstRefField(param.thisObject, lutCallback);
                    Object instance = outerPipeline(callback, pipeline);
                    boolean callbackTask = callback != null;
                    if (!callbackTask && instance == null) return;

                    boolean target = isTargetMode();
                    boolean masterLive = instance != null
                        && isMasterLivePipeline(instance, liveRenderer, pipeline);
                    /*
                     * r424: after a GR/master exit into XPAN the delivered table is the generic
                     * color-space one, and stock fills a null renderer.s with it -- every XPAN
                     * live frame then samples the wrong developer and the attachment turns
                     * all-green. r423 logs proved stock never replaces an already-built
                     * renderer.s, so pre-seed the verified clean-env XPAN table right before
                     * the task body runs (same GL thread, valid GL context).
                     */
                    if (instance != null && !masterLive) {
                        XpanLiveFeature.preSeedXpanBasicToneTexture(instance);
                    }
                    /*
                     * r474: the XPAN filter fix. The preview shows the filter as
                     * BasicTone followed by the filter's own 17^3 cube, but the live
                     * video only receives the plain 64^3 table (task.d, generic
                     * 169adb06), so the filter never reaches the video. Compose the
                     * STOCK task.d table (captured here, before any swap) through the
                     * stock cube (/odm/etc/camera/meishe_lut) and put the result back.
                     * r473 proved the base must be task.d itself, NOT manager.c:
                     * composing over manager.c (-881252736) reproduced r427's brightness
                     * regression because -881252736 is not the stock video table. The
                     * masterLive flag is NOT a gate: r472 measured it flipping across
                     * sessions for the same XPAN pipeline; the XPAN check lives in
                     * xpanLiveVideoLookTable (currentCameraMode). The r470 red probe
                     * ("t" mode) keeps precedence for channel diagnosis.
                     */
                    boolean probeTargetsTask = instance != null
                        && XpanLiveFeature.videoChannelProbeTargetsTask();
                    float[] taskTable = firstTableField(param.thisObject);
                    if (instance != null && !probeTargetsTask && taskTable != null) {
                        float[] look = XpanLiveFeature.xpanLiveVideoLookTable(taskTable);
                        if (look != null) {
                            setTableField(param.thisObject, look);
                            Log.i(TAG, "XPAN live video look: task.d swapped to composed"
                                + " filter table, " + XpanLiveFeature.xpanVideoLookState());
                            taskTable = firstTableField(param.thisObject);
                        }
                    }
                    /*
                     * r470: the r468 red-probe matrix (o/n/u1/s) never touched task.d -- the
                     * gh.p3$a table that lands in the ch.g direct attachment. r427's A/B already
                     * proved swapping task.d changes the direct-XPAN video picture (brightness
                     * regression), so "t" targets exactly this channel: red table in, and a red
                     * video proves it is the one the live encoder samples.
                     */
                    if (probeTargetsTask && taskTable != null) {
                        setTableField(param.thisObject, XpanLiveFeature.redProbeTable());
                        Log.i(TAG, "XPAN video channel probe: task.d swapped to red table"
                            + ", mode=" + GrPreviewStreamRestore.modeName());
                    }
                    /*
                     * r428: the r427 task.d replacement is reverted. The r427 A/B proved the
                     * table content is irrelevant to the green covers: all three captures
                     * carried identical manager.c/r/s states, yet one rendered fine and two
                     * stayed green. Worse, forcing r to the XPAN table washed the direct-XPAN
                     * capture out (stock's generic table in r IS the normal direct-XPAN
                     * state). The real green cause is the missing EIS route (see
                     * XpanLiveFeature) -- no EIS route, no frames reach the encoder.
                     */
                    long currentSession = armedPipeline == instance ? armedSession : 0L;
                    param.setObjectExtra("diagnosticTask",
                        new TaskRecord(instance, currentSession, target, masterLive));
                    Log.i(TAG, "master live LUT diagnostic task enter"
                        + ", action=" + intFieldSafe(param.thisObject, "a")
                        + ", index=" + intFieldSafe(param.thisObject, "b")
                        + ", callback=" + className(callback)
                        + ", data=" + className(firstTableField(param.thisObject))
                        + ", targetMode=" + target
                        + ", masterLive=" + masterLive
                        + ", session=" + currentSession
                        + ", taskData=" + describeTable(
                            firstTableField(param.thisObject))
                        + ", " + describeTables(instance, managerHolder)
                        + ", " + describePipeline(instance));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object extra = param.getObjectExtra("diagnosticTask");
                    if (!(extra instanceof TaskRecord)) return;
                    TaskRecord task = (TaskRecord) extra;
                    if (param.hasThrowable()) {
                        if (task.currentSession > 0L) {
                            failedTaskSession = task.currentSession;
                        }
                        Log.e(TAG, "master live LUT diagnostic task failed"
                            + ", action=" + intFieldSafe(param.thisObject, "a")
                            + ", targetMode=" + task.targetMode
                            + ", masterLive=" + task.masterLive
                            + ", session=" + task.currentSession
                            + ", " + describePipeline(task.pipeline),
                            param.getThrowable());
                        return;
                    }
                    if (task.currentSession > 0L) {
                        completedTaskSession = task.currentSession;
                    }
                    Log.i(TAG, "master live LUT diagnostic task complete"
                        + ", action=" + intFieldSafe(param.thisObject, "a")
                        + ", targetMode=" + task.targetMode
                        + ", masterLive=" + task.masterLive
                        + ", session=" + task.currentSession
                        + ", taskData=" + describeTable(
                            firstTableField(param.thisObject))
                        + ", " + describeTables(task.pipeline, managerHolder)
                        + ", " + describePipeline(task.pipeline));
                }
            };
        for (Class<?> lutTask : lutTasks) {
            for (Method run : lutTask.getDeclaredMethods()) {
                if ("run".equals(run.getName()) && run.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(run, taskHook);
                }
            }
        }

        XposedBridge.hookMethod(renderFrame, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object instance = param.thisObject;
                if (instance != armedPipeline) return;
                param.setObjectExtra(
                    "diagnosticMetadata", describeImageMetadata(param.args[0]));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object instance = param.thisObject;
                if (instance != armedPipeline) return;
                Object metadata = param.getObjectExtra("diagnosticMetadata");
                String signature = describePipeline(instance)
                    + "," + (metadata == null ? "metadata=unavailable" : metadata);
                long now = SystemClock.elapsedRealtime();
                String previous = lastRenderSignature;
                if (signature.equals(previous) && now - lastRenderLogAt < 1000L) return;
                lastRenderSignature = signature;
                lastRenderLogAt = now;
                Log.i(TAG, "master live LUT diagnostic render state"
                    + ", callbackSeen=" + (callbackSession == armedSession)
                    + ", taskCompleted=" + (completedTaskSession >= armedSession)
                    + ", taskFailed=" + (failedTaskSession == armedSession)
                    + ", session=" + armedSession
                    + ", " + signature);
            }
        });

        /*
         * cb792680 is the BasicTone table the live renderer actually samples. Whether the cover
         * used the same table cannot be inferred from the LUT callback: that callback only returns
         * 169adb06, the ordinary colour-space table, while lutParams.g=true selects manager.c.
         * Record every environment rebuild and dynamic process() result after a GR exit, without
         * touching either value, so the cover-side table identity becomes observable.
         */
        XposedHelpers.findAndHookMethod(manager,
            Symbols.get().name("@Lut3d.load"),
            int.class, int.class, String.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    /*
                     * r426: after a GR/master exit the LogLut/MasterPalette session scope stays
                     * "professional" and stock keeps recomputing e(1,0,"professional") -- this
                     * fights the XPAN re-arm wash and manager.c flips back to the stale
                     * professional table (cb792680) within milliseconds, so the live frames
                     * grade through the wrong developer and the attachment turns all-green.
                     * While XPAN is active, rename any BasicTone recomputation to "xpan" so
                     * every stock rebuild produces the correct XPAN developer table. The call
                     * itself is not blocked; stock flow, dirty flags and async delivery keep
                     * working exactly as before -- only the table identity changes.
                     */
                    if (param.args.length == 3
                        && Integer.valueOf(1).equals(param.args[0])
                        && Integer.valueOf(0).equals(param.args[1])
                        && "professional".equals(param.args[2])
                        && GrPreviewStreamRestore.inXpanMode()) {
                        param.args[2] = "xpan";
                        Log.i(TAG, "xpan LUT recompute redirected professional -> xpan");
                    }
                    if (!traceManager()) return;
                    param.setObjectExtra("managerBefore", describeManager(param.thisObject));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!traceManager()) return;
                    Log.i(TAG, "master live LUT diagnostic manager e"
                        + ", combine=" + param.args[0]
                        + ", cap=" + param.args[1]
                        + ", name=" + param.args[2]
                        + ", returned=" + param.getResult()
                        + ", threw=" + param.hasThrowable()
                        + ", before={" + param.getObjectExtra("managerBefore") + "}"
                        + ", after={" + describeManager(param.thisObject) + "}");
                }
            });
        try {
            XposedHelpers.findAndHookMethod(manager, "l", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!traceManager()) return;
                    Log.i(TAG, "master live LUT diagnostic manager release"
                        + ", clearTables=" + param.args[0]
                        + ", state={" + describeManager(param.thisObject) + "}");
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "master live LUT diagnostic manager release hook skipped " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(manager, "p", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!traceManager()) return;
                    String state = describeManager(param.thisObject);
                    long now = SystemClock.elapsedRealtime();
                    String previous = lastManagerState;
                    if (state.equals(previous) && now - lastManagerLogAt < 500L) return;
                    lastManagerState = state;
                    lastManagerLogAt = now;
                    Log.i(TAG, "master live LUT diagnostic manager process"
                        + ", threw=" + param.hasThrowable()
                        + ", state={" + state + "}");
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "master live LUT diagnostic manager process hook skipped " + error);
        }

        Log.i(TAG, "master live LUT diagnostic installed");
    }

    private static boolean traceManager() {
        if (GrPreviewStreamRestore.hasLeftGr()
                && !GrState.isGrMode()
                && GrPreviewStreamRestore.inMasterMode()) {
            return true;
        }
        /*
         * The direct-master path never satisfies hasLeftGr(). Keep a bounded trace window after
         * its type-7 live pipeline initialises so the same table/env transition is observable
         * without flooding later camera sessions.
         */
        return armedPipeline != null
            && SystemClock.elapsedRealtime() - armedAt <= 30_000L;
    }

    private static String describeManager(Object manager) {
        if (manager == null) return "manager=null";
        try {
            /* 317 deleted one String field, shifting the middle block: combine M->N,
             * cap O->P, name P->Q, initialized N->O, palette W->X, hdr X->Y.
             * t (size), c (basic table) and l (holder) are unchanged. */
            float[] basic = (float[]) XposedHelpers.getObjectField(manager, "c");
            int[] holder = (int[]) XposedHelpers.getObjectField(manager, "l");
            return "inst=" + Integer.toHexString(System.identityHashCode(manager))
                + ",combine=" + intFieldAny(manager, "M", "N")
                + ",cap=" + intFieldAny(manager, "O", "P")
                + ",name=" + objectFieldAny(manager, "P", "Q")
                + ",initialized=" + boolFieldAny(manager, "N", "O")
                + ",palette=" + boolFieldAny(manager, "W", "X")
                + ",hdr=" + boolFieldAny(manager, "X", "Y")
                + ",size=" + XposedHelpers.getIntField(manager, "t")
                + ",holder=" + (holder != null && holder.length != 0 ? holder[0] : -1)
                + "," + describeTable(basic);
        } catch (Throwable error) {
            return "manager=unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static boolean isTargetMode() {
        return GrPreviewStreamRestore.hasLeftGr()
            && GrPreviewStreamRestore.inMasterMode()
            && !GrState.isGrMode();
    }

    /**
     * Mirrors the stock deciding inputs in {@code gh.p3.r(ImageData)}:
     * {@code g = type == 7 && softlight == "default" && (filter, beauty or retro active)}.
     * These reads make a direct/GR contrast explain whether the BasicTone branch was skipped.
     */
    private static String describeImageMetadata(Object imageData) {
        Object metadata = objectField(imageData, "f4127f");
        Object imageExtra = objectField(imageData, "f16227h");
        Object hdrTransform = objectField(imageData, "f16228i");
        return "softlight=" + metadataString(metadata, "meta_softlight_type")
            + ",filter=" + metadataString(metadata, "meta_filter_type")
            + ",retro=" + metadataBoolean(metadata, "meta_retro_effect")
            + ",beauty=" + metadataInt(metadata, "meta_face_beauty_index")
            + ",watermarkOpen=" + metadataBoolean(metadata, "image_is_watermark_open")
            + ",watermarkModel=" + className(objectField(imageData, "f16232m"))
            + ",imageExtra=" + className(imageExtra)
            + ",imageExtraFlag=" + boolField(imageExtra, "f37507f")
            + ",hdrTransform=" + className(hdrTransform);
    }

    private static Object metadataString(Object metadata, String key) {
        try {
            return XposedHelpers.callMethod(metadata, "h", key);
        } catch (Throwable ignored) {
            return "unreadable";
        }
    }

    private static Object metadataInt(Object metadata, String key) {
        try {
            return XposedHelpers.callMethod(metadata, "e", key);
        } catch (Throwable ignored) {
            return "unreadable";
        }
    }

    private static Object metadataBoolean(Object metadata, String key) {
        try {
            return XposedHelpers.callMethod(metadata, "c", key);
        } catch (Throwable ignored) {
            return "unreadable";
        }
    }

    private static boolean isMasterLivePipeline(
        Object pipeline, Class<?> liveRenderer, Class<?> pipelineClass) {
        if (pipeline == null || !pipelineClass.isInstance(pipeline)) return false;
        try {
            if (XposedHelpers.getIntField(pipeline, "n0") != MASTER_LIVE_LUT_TYPE) return false;
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            return renderer != null
                && liveRenderer.isInstance(renderer)
                && XposedHelpers.getBooleanField(renderer, "o");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object outerPipeline(Object callback, Class<?> pipelineClass) {
        if (callback == null) return null;
        for (Field field : callback.getClass().getDeclaredFields()) {
            if (!pipelineClass.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(callback);
                if (pipelineClass.isInstance(value)) return value;
            } catch (Throwable ignored) {
                // Keep looking in case the callback retains more than one pipeline-typed field.
            }
        }
        return null;
    }

    private static String describePipeline(Object pipeline) {
        if (pipeline == null) {
            return "pipeline=null,targetMode=" + isTargetMode();
        }
        try {
            Object renderer = objectField(pipeline, "w");
            Object glThread = objectField(pipeline, "t");
            Object watermark = objectField(pipeline, "P0");
            Object watermarkLuts = watermark == null ? null : objectField(watermark, "j");
            return "lutType=" + intField(pipeline, "n0")
                + ",renderer=" + className(renderer)
                + ",rendererO=" + boolField(renderer, "o")
                + ",rendererQ=" + boolField(renderer, "q")
                + ",rendererRNull=" + (objectField(renderer, "r") == null)
                + ",rendererSNull=" + (objectField(renderer, "s") == null)
                + ",rendererR=" + describeTextureBrief(objectField(renderer, "r"))
                + ",rendererS=" + describeTextureBrief(objectField(renderer, "s"))
                + ",lutParamsF=" + boolField(objectField(pipeline, "o0"), "f")
                + ",lutParamsG=" + boolField(objectField(pipeline, "o0"), "g")
                + ",glPending=" + boolField(glThread, "e")
                + ",watermarkNull=" + (watermark == null)
                + ",watermarkLutsNull=" + (watermarkLuts == null)
                + ",targetMode=" + isTargetMode();
        } catch (Throwable error) {
            return "pipeline=unreadable:" + error.getClass().getSimpleName()
                + ",targetMode=" + isTargetMode();
        }
    }

    private static String describeTables(Object pipeline, Class<?> managerHolder) {
        Object renderer = objectField(pipeline, "w");
        Object params = objectField(pipeline, "o0");
        String basic = "unreadable";
        try {
            Object manager = XposedHelpers.getStaticObjectField(managerHolder, "a");
            float[] table = manager == null
                ? null : (float[]) XposedHelpers.getObjectField(manager, "c");
            int size = intField(manager, "t");
            basic = "managerBasic{size=" + size + "," + describeTable(table) + "}";
        } catch (Throwable error) {
            basic = "managerBasic{error=" + error.getClass().getSimpleName() + "}";
        }
        return "lutParams{f=" + boolField(params, "f")
            + ",g=" + boolField(params, "g")
            + ",vigSwitch=" + intField(params, "d") + "}"
            + "," + basic
            + ",selectedTable=" + describeTexture(objectField(renderer, "r"))
            + ",basicTable=" + describeTexture(objectField(renderer, "s"));
    }

    private static String describeTextureBrief(Object texture) {
        if (texture == null) return "null";
        Object raw = objectField(texture, "A");
        int rawLength = raw instanceof float[] ? ((float[]) raw).length : -1;
        return texture.getClass().getSimpleName()
            + "@" + Integer.toHexString(System.identityHashCode(texture))
            + "{size=" + intField(texture, "b")
            + ",depth=" + intField(texture, "z")
            + ",rawLength=" + rawLength + "}";
    }

    private static String describeTexture(Object texture) {
        if (texture == null) return "null";
        Object raw = objectField(texture, "A");
        return texture.getClass().getSimpleName()
            + "{size=" + intField(texture, "b")
            + ",depth=" + intField(texture, "z")
            + "," + describeTable(raw) + "}";
    }

    /** First readable int instance field among the candidate names ("?" when none). */
    private static String intFieldAny(Object owner, String... names) {
        for (String name : names) {
            try {
                return String.valueOf(XposedHelpers.getIntField(owner, name));
            } catch (Throwable ignored) {
                /* try the next candidate */
            }
        }
        return "?";
    }

    private static String boolFieldAny(Object owner, String... names) {
        for (String name : names) {
            try {
                return String.valueOf(XposedHelpers.getBooleanField(owner, name));
            } catch (Throwable ignored) {
                /* try the next candidate */
            }
        }
        return "?";
    }

    private static String objectFieldAny(Object owner, String... names) {
        for (String name : names) {
            try {
                Object value = XposedHelpers.getObjectField(owner, name);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {
                /* try the next candidate */
            }
        }
        return "?";
    }

    private static String describeTable(Object value) {
        if (!(value instanceof float[])) {
            return "length=-1,hash=null,head=null";
        }
        float[] table = (float[]) value;
        return "length=" + table.length
            + ",hash=" + Integer.toHexString(Arrays.hashCode(table))
            + ",head=" + sample(table, 8);
    }

    private static String sample(float[] values, int count) {
        int end = Math.min(values.length, count);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < end; i++) {
            if (i != 0) out.append(',');
            out.append(String.format(java.util.Locale.US, "%.6f", values[i]));
        }
        if (end < values.length) out.append(",...");
        return out.append(']').toString();
    }

    private static float[] floatArray(Object[] args, int index) {
        return args != null && index < args.length && args[index] instanceof float[]
            ? (float[]) args[index] : null;
    }

    private static Object objectField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
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

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static final class TaskRecord {
        final Object pipeline;
        final long currentSession;
        final boolean targetMode;
        final boolean masterLive;

        TaskRecord(Object pipeline, long currentSession, boolean targetMode, boolean masterLive) {
            this.pipeline = pipeline;
            this.currentSession = currentSession;
            this.targetMode = targetMode;
            this.masterLive = masterLive;
        }
    }
}
