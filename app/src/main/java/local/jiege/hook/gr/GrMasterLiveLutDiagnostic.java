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
    private static final String PIPELINE = "gh.p3";
    private static final String IMAGE_DATA =
        "com.oplus.camera.feature.livephoto.data.ImageData";
    private static final String LIVE_RENDERER = "ch.g";
    private static final String LUT_TASK =
        "com.oplus.camera.feature.captureparam.view.r";
    private static final String LUT_CALLBACK = PIPELINE + "$a";
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

    private GrMasterLiveLutDiagnostic() {}

    static void install(ClassLoader classLoader) throws Throwable {
        Class<?> pipeline = XposedHelpers.findClass(PIPELINE, classLoader);
        Class<?> imageData = XposedHelpers.findClass(IMAGE_DATA, classLoader);
        Class<?> liveRenderer = XposedHelpers.findClass(LIVE_RENDERER, classLoader);
        Class<?> lutTask = XposedHelpers.findClass(LUT_TASK, classLoader);
        Class<?> lutCallback = XposedHelpers.findClass(LUT_CALLBACK, classLoader);
        Class<?> managerHolder = XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);
        Class<?> manager = XposedHelpers.findClass(
            "com.oplus.camera.common.utils.Lut3dDataManager", classLoader);
        Method onInit = XposedHelpers.findMethodExact(pipeline, "onInit");
        Method renderFrame = XposedHelpers.findMethodExact(pipeline, "r", imageData);

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

        XposedBridge.hookAllMethods(lutCallback, "b", new XC_MethodHook() {
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

        XposedBridge.hookMethod(XposedHelpers.findMethodExact(lutTask, "run"),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object callback = objectField(param.thisObject, "c");
                    Object instance = outerPipeline(callback, pipeline);
                    boolean callbackTask = callback != null
                        && LUT_CALLBACK.equals(callback.getClass().getName());
                    if (!callbackTask && instance == null) return;

                    boolean target = isTargetMode();
                    boolean masterLive = instance != null
                        && isMasterLivePipeline(instance, liveRenderer, pipeline);
                    long currentSession = armedPipeline == instance ? armedSession : 0L;
                    param.setObjectExtra("diagnosticTask",
                        new TaskRecord(instance, currentSession, target, masterLive));
                    Log.i(TAG, "master live LUT diagnostic task enter"
                        + ", action=" + intField(param.thisObject, "a")
                        + ", index=" + intField(param.thisObject, "b")
                        + ", callback=" + className(callback)
                        + ", data=" + className(objectField(param.thisObject, "d"))
                        + ", targetMode=" + target
                        + ", masterLive=" + masterLive
                        + ", session=" + currentSession
                        + ", taskData=" + describeTable(
                            objectField(param.thisObject, "d"))
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
                            + ", action=" + intField(param.thisObject, "a")
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
                        + ", action=" + intField(param.thisObject, "a")
                        + ", targetMode=" + task.targetMode
                        + ", masterLive=" + task.masterLive
                        + ", session=" + task.currentSession
                        + ", taskData=" + describeTable(
                            objectField(param.thisObject, "d"))
                        + ", " + describeTables(task.pipeline, managerHolder)
                        + ", " + describePipeline(task.pipeline));
                }
            });

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
        XposedHelpers.findAndHookMethod(manager, "e", int.class, int.class, String.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
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
        XposedHelpers.findAndHookMethod(manager, "l", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!traceManager()) return;
                Log.i(TAG, "master live LUT diagnostic manager release"
                    + ", clearTables=" + param.args[0]
                    + ", state={" + describeManager(param.thisObject) + "}");
            }
        });
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
            float[] basic = (float[]) XposedHelpers.getObjectField(manager, "c");
            int[] holder = (int[]) XposedHelpers.getObjectField(manager, "l");
            return "inst=" + Integer.toHexString(System.identityHashCode(manager))
                + ",combine=" + XposedHelpers.getIntField(manager, "M")
                + ",cap=" + XposedHelpers.getIntField(manager, "O")
                + ",name=" + XposedHelpers.getObjectField(manager, "P")
                + ",initialized=" + XposedHelpers.getBooleanField(manager, "N")
                + ",palette=" + XposedHelpers.getBooleanField(manager, "W")
                + ",hdr=" + XposedHelpers.getBooleanField(manager, "X")
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
