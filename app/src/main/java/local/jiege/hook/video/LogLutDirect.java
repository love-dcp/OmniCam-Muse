package local.jiege.hook.video;

import android.opengl.EGLSurface;
import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.ViewParent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Pro video: try to make the stock "bake the LUT into the recording" switch actually do something.
 *
 * <p>{@code docs/LOG_LUT.md} measured that the switch
 * ({@code pref_log_video_record_effect_switch}, folded into
 * {@code pref_log_video_monitor_state}) only changes what the viewfinder shows: five recordings,
 * two with the switch on and three with it off, came out at the same saturation to within codec
 * noise. The state is read by the panel, the analytics builder and {@code restoreAppConfig}, never
 * written into a capture request.
 *
 * <p>The X9U is reported to bake it. Comparing the two builds
 * ({@code work/x9u_cam/}, X9U OplusCamera 176,098,454 B against the PKJ110 one) the
 * {@code feature.logvideo} package is identical, {@code libCombineLut.so} has the same md5, and the
 * only platform-facing hook is the feature table: {@code rm.e.getSupportFunction(String)} maps a
 * name to an index and reads it through {@code o9.h.c(index, default)}. One of those names is
 * {@code func_master_video_log_custom_lut}.
 *
 * <p>So the first thing worth ruling out is that opening that one slot is enough. This class answers
 * {@code true} for it and logs what happened, plus what the two configuration keys behind it say:
 *
 * <pre>
 *   func_master_video_log_custom_lut  ->  CameraConfig.d(
 *       "com.oplus.camera.preview.hdr.transform.lut.video.support", false, false)
 * </pre>
 *
 * <p>Note what the static side already says: the name appears exactly twice in the whole app --
 * once in the feature table above, and once in {@code rm.e.jk(EventRecordDataBuilder)}, which only
 * fills the analytics fields {@code setLutType} / {@code setPreviewLut}. If the log below shows the
 * query only ever arriving from that builder, then no recording path consults it and the switch is
 * decorative on this build regardless of what we answer.
 *
 * <h2>Measured (r153 / r154, PKJ110)</h2>
 *
 * <p>Both halves of the answer turned out to be "no", and the measurements are in
 * {@code docs/LOG_LUT.md} §6.1:
 *
 * <ul>
 *   <li>Answering the slot true changes nothing. Two clips that differ only in the switch came out
 *       at the same saturation (39.8 vs 40.1), pixel diff 3.61, identical luma percentiles
 *       {@code 15/96/201}. The query is only ever made by {@code rm.e.jk}, i.e. analytics.</li>
 *   <li>The configuration row it reads,
 *       {@code com.oplus.camera.preview.hdr.transform.lut.video.support}, is {@code 1} in the
 *       stock {@code oplus_camera_config} -- it was never the missing piece.</li>
 *   <li>Shipping {@code filters_lut/log_video_sdr_3dlut.cube} (index 9 of the loader's path
 *       table) changes nothing either: {@code Lut3dDataManager.i()} never asks for index 9, 10 or
 *       15, only index 1 at startup. The preview and the encoder both bypass the table.</li>
 * </ul>
 *
 * <p>Those two tests only covered feature[8] and the public LUT loader. A later dex pass found the
 * separate feature[3] strategy switch described above: the stock X8U answers 0 and selects
 * {@code StraightOutOfTone}, whose inherited b() is a stub; feature[3] == 1 selects the shipped
 * {@code MasterToneLutByHal} implementation. This class selects that strategy and also makes
 * {@code vk()} agree with the Log format selected by the UI. Y7 buildSession then sends the
 * direct-output mode and custom LUT name through the stock HAL interface.
 *
 * <h2>Accepted status (2026-11-08, r173)</h2>
 *
 * <p>Device acceptance now passes without diagnostic overrides. With recording direct-output ON,
 * the viewfinder and the recorded file both show the selected custom LUT; with direct-output OFF,
 * the recorded file remains the grey Log baseline. The accepted recording uses the native HAL
 * request {@code mode=2}, the App MediaCodec type-2 recorder, a valid encoder Surface and the
 * {@code nn.p} output renderer with the selected LUT index on every frame. Orientation is also
 * correct at 2160x3840 with zero rotation.
 */
final class LogLutDirect {
    private static final String TAG = "LogLut";

    /** The name the analytics builder and the feature table use for "custom LUT". */
    private static final String FUNC_CUSTOM_LUT = "func_master_video_log_custom_lut";
    /** Preview pipeline asks for the selected LUT through this support flag. */
    private static final String FUNC_PREVIEW_RENDER_LUT = "key_support_preview_render_lut";
    /** The configuration row the custom-LUT slot actually reads. */
    private static final String CFG_HDR_LUT = "com.oplus.camera.preview.hdr.transform.lut.video.support";
    /**
     * MasterVideoDecision.c(3) selects the Log tone/LUT implementation. X8U's stock feature list
     * only has index 0, so this falls through to {@code StraightOutOfTone}; that implementation
     * inherits a stub {@code b(DecisionParam)} which always reports Log LUT mode 0 to the HAL.
     * The build also contains {@code MasterToneLutByHal}, whose b() reports 2 when Log recording
     * and the direct switch are both on. The camera normally turns the direct request (2) into a
     * limited request (1) at 120fps or 8K; Vj is patched below for 120fps.
     */
    private static final int HAL_DIRECT_FEATURE = 3;
    private static final String FORCE_120 = "persist.omnicam.loglut.120";
    /** Emergency fallback: set to 0 to leave the stock recorder selection untouched. */
    private static final String APP_CODEC_PROPERTY = "persist.omnicam.loglut.appcodec";
    /**
     * Diagnostic override for the mode sent through KEY_CONFIGURE_LOG_VIDEO_MODE. A negative
     * value keeps the stock value; 0 isolates whether HAL mode 2 is the source of the color shift.
     */
    private static final String HAL_MODE_OVERRIDE_PROPERTY = "persist.omnicam.loglut.halmode";
    /**
     * Diagnostic bit mask. Clear selected effect flags while Log direct-output and a real LUT are
     * active, so the preview path can be isolated without rebuilding for every experiment.
     */
    private static final String EFFECT_FLAGS_CLEAR_PROPERTY =
            "persist.omnicam.loglut.clearflags";
    /** Keep the LUT monitor badge visible while the stock recording UI hides its small status bar. */
    private static final String KEEP_BADGE_PROPERTY = "persist.omnicam.loglut.keepbadge";
    /**
     * Direct-output changes the AE baseline at 4K60, while this sensor's 4K120 path is darker even
     * with direct-output off. Keep the correction configurable because the final value must be
     * checked against the same scene at all three rates.
     *
     * <p>OplusCamera's CameraHWConfiguration declares
     * {@code AECompensationStepsDenominator=330}, so these values are raw
     * {@code CONTROL_AE_EXPOSURE_COMPENSATION} units: 330 is +1.0 EV and 33 is +0.1 EV.
     */
    private static final String EV_FIX_PROPERTY = "persist.omnicam.loglut.evfix";
    private static final String EV_FIX_60_PROPERTY = "persist.omnicam.loglut.evfix60";
    private static final String EV_FIX_120_PROPERTY = "persist.omnicam.loglut.evfix120";
    private static final String EV_FIX_120_ON_PROPERTY =
            "persist.omnicam.loglut.evfix120on";
    private static final int DEFAULT_EV_FIX_60 = 330;
    private static final int DEFAULT_EV_FIX_120 = 231;
    private static final int MAX_AE_EV_UNITS = 990;

    /** Pro video module. Declares its own final getSupportFunction, so BaseMode alone is not enough. */
    private static final String MODE_PRO_VIDEO = "rm.e";
    /** BaseMode.U0 written by rm.e's constructor. */
    private static final String MODE_PRO_VIDEO_NAME = "masterVideo";
    /** Another module that overrides the same table. */
    private static final String MODE_ALT = "dm.w2";
    /** The shared implementation every other module falls back to. */
    private static final String MODE_BASE = "com.oplus.camera.module.BaseMode";
    /** The configuration reader. */
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    /** The 3D LUT loader: index -> file path, file path -> float[] table. */
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";
    /** The menu writes a display title, while the HAL needs the actual .cube file name. */
    private static final String MENU_VIEW = "com.oplus.camera.feature.logvideo.view.LogLutMenuView";
    /** Exact-name lookup for the imported LUT copies. */
    private static final String LUT_PERSISTENCE = "jh.c";
    /** Master-video preference keys h1 (current) and j1 (last selected). */
    private static final String DATA_KEYS = "n9.d";
    /** Current selection is g1/h1; i1/j1 preserve the last applied selection. */
    private static final String DATA_CURRENT_POSITION = "g1";
    private static final String DATA_CURRENT_TITLE = "h1";
    private static final String DATA_LAST_POSITION = "i1";
    private static final String DATA_LAST_TITLE = "j1";
    /** Session parameter writer used by rm.e.Y7. */
    private static final String SESSION_PARAMS = "mm.o";
    private static final String CAMERA_PARAMETER = "com.oplus.ocs.camera.CameraParameter";
    /** OneCameraImpl.v is the final parameter sink for both the UI and BaseMode.V7. */
    private static final String CAMERA_IMPLEMENTATION = "bb.c1";
    /**
     * Generic {@code Parameter} reader used by RecordProcessor.k(). It is intentionally shared by
     * many subsystems, so the hooks below act only on the three exact recorder key names and only
     * while Log direct-output with an explicit LUT is active.
     */
    private static final String RECORDER_CONFIG = "om.c0";
    /** RecordProcessor; its k() method creates the actual video recorder. */
    private static final String MODE_RECORDER = "om.e0";
    /** RecordProcessor.g supplies the exact recorder selector keys. */
    private static final String RECORDER_KEYS = MODE_RECORDER + "$g";
    /** Recorder wrapper. Field v is its backend type: 1 native, 2 App MediaCodec. */
    private static final String VIDEO_RECORDER = "om.n0";
    /** App MediaCodec implementation whose s() creates the encoder surface. */
    private static final String APP_CODEC = "om.l";
    /** HAL callback that hands the encoder Surface to the App. */
    private static final String ENCODER_SURFACE_CALLBACK = "mm.i$d";
    /** Preview/recording EGL processor. Field A is VideoOutputRenderer (nn.p). */
    private static final String OUTPUT_PROCESSOR = "mn.t";
    /** Effect callback that returns BaseMode.aa(), i.e. the current preview LUT type. */
    private static final String PREVIEW_EFFECT_CALLBACK = "dm.u3b";
    /** Effect graph implementation; its H flags select HDR-transform and preview-LUT passes. */
    private static final String EFFECT_PROCESSOR = "uf.n";
    /** Shared GL canvas that owns both ordinary and synthetic LUTs. */
    private static final String GL_CANVAS = "com.oplus.camera.common.gl.s";
    private static final String LUT_DIRECTORY = "/data/vendor/camera_process/customize_lut/";
    private static final int NO_LUT_INDEX = 0;
    private static final int SDR_LUT_INDEX = 9;
    private static final int HDR_LUT_INDEX = 10;
    private static final int CUSTOM_LUT_INDEX = 15;
    private static final int FLAG_PREVIEW_RENDER_LUT = 1024;
    private static final int FLAG_HDR_TRANSFORM = 16384;
    private static final int FLAG_HDR_TRANSFORM_LUT = 65536;
    /** X0=1 is direct-output OFF; X0=2 is direct-output ON. */
    private static final int DIRECT_OUTPUT_ON = 2;
    private static final String PREVIEW_RENDERER = "nn.l";
    private static final String OUTPUT_RENDERER = "nn.p";

    private static volatile boolean reportedQuery;
    private static volatile boolean reportedConfig;
    private static volatile boolean reportedStrategy;
    private static volatile int reportedDirectMode = -1;
    private static volatile boolean reported120;
    private static volatile boolean strategyInstalled;
    private static volatile int queries;
    private static volatile boolean proVideoModeActive;
    private static volatile String activeCameraMode = "";
    private static volatile java.lang.ref.WeakReference<Object> activeMode =
            new java.lang.ref.WeakReference<>(null);
    private static volatile boolean customLutActive;
    private static volatile int selectedLutPosition = -1;
    private static volatile int selectedLutIndex = NO_LUT_INDEX;
    private static volatile String selectedLutTitle = "";
    private static volatile String selectedLutFileName = "";
    private static volatile String reportedHalLutName = "";
    private static volatile String reportedRestoredSelection = "";
    private static volatile long sessionSerial;
    private static volatile long reportedNameSession = -1;
    private static volatile long reportedModeSession = -1;
    private static volatile long lastOutputTraceAt;
    private static volatile String lastOutputTraceSignature = "";
    private static volatile int outputFrameCount;
    private static volatile boolean reportedDirectOffRender;
    private static volatile int lastDirectMode = -1;
    private static volatile String lastAppCodecProbe = "";
    private static volatile boolean appCodecArmed;
    private static volatile boolean appCodecTargetRecorder;
    /**
     * RecordProcessor.k() constructs the recorder synchronously, but this camera build's stack
     * frames are rewritten to short/native names. Keep an explicit thread-local scope instead of
     * relying on matching "om.e0#k" in StackTraceElement.
     */
    private static final ThreadLocal<Integer> recorderCreationDepth =
            new ThreadLocal<Integer>() {
                @Override protected Integer initialValue() {
                    return 0;
                }
            };
    private static volatile java.lang.ref.WeakReference<Object> targetAppCodec =
            new java.lang.ref.WeakReference<>(null);
    private static volatile String lastRecorderConfigProbe = "";
    private static volatile String lastSyntheticLutProbe = "";
    private static volatile String lastDrawSelectionProbe = "";
    private static volatile String lastRecorderConstruction = "";
    private static volatile String lastCodecStartup = "";
    private static volatile String lastSurfaceAssignment = "";
    private static volatile String lastOrientationFix = "";
    private static volatile String lastEvFixProbe = "";
    private static volatile String lastPreviewEffectFlagsProbe = "";
    private static volatile String lastPreviewEffectProbe = "";
    private static volatile String lastPreviewCacheProbe = "";
    private static volatile long shaderProbeSession = -1;
    private static final java.util.Set<String> reportedShaderProbes =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    /**
     * The OES-to-texture preview pass runs before the ordinary 3D-LUT pass. Restore its canvas LUT
     * index immediately after the draw so the following texture pass still receives the selection.
     */
    private static final ThreadLocal<PreviewPassLutState> pendingPreviewPassLut =
            new ThreadLocal<>();
    private static volatile int lastSupportEffectFlags = -1;
    private static volatile boolean badgeVisibilityHookInstalled;
    private static volatile boolean reportedBadgeKept;
    private static volatile java.lang.ref.WeakReference<Object> previewCallback =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<Object> outputRenderer =
            new java.lang.ref.WeakReference<>(null);
    /**
     * The GL canvas outlives individual camera modes. Remember its current instance so a mode
     * boundary can clear a Pro Video LUT index before the next mode performs its first draw.
     */
    private static volatile java.lang.ref.WeakReference<Object> lastGlCanvas =
            new java.lang.ref.WeakReference<>(null);
    private static volatile String lastStaleCanvasLutProbe = "";
    /** Table indexes already reported, so the log stays one line per index. */
    private static final java.util.Set<Integer> loadedIndexes =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    /** One contract line per state transition, not one repeating line per renderer query. */
    private static final java.util.Set<String> previewLutContractKeys =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private LogLutDirect() {}

    private static final class PreviewPassLutState {
        final int index;
        final boolean synthetic;

        PreviewPassLutState(int index, boolean synthetic) {
            this.index = index;
            this.synthetic = synthetic;
        }
    }

    static void install(ClassLoader classLoader) {
        relabelUnsupportedHint();
        keepLutBadgeVisibleWhileRecording();
        observeActiveCameraMode(classLoader);
        installExposureCompensationFix(classLoader);
        enableHalDirectStrategy(classLoader);
        repairPreviewLutType(classLoader);
        repairSelectedLut(classLoader);
        observeSessionParameters(classLoader);
        installLutRendererHooks(classLoader);
        observeLutRendererState(classLoader);
        observePreviewEffectPipeline(classLoader);
        observeOutputPipeline(classLoader);
        observeEncoderSurface(classLoader);
        installDirectAppCodec(classLoader);
        observeVideoRecorder(classLoader);
        observeRecorderCodec(classLoader);
        answerGetSupportFunction(classLoader, MODE_PRO_VIDEO);
        answerGetSupportFunction(classLoader, MODE_ALT);
        answerGetSupportFunction(classLoader, MODE_BASE);
        observeConfig(classLoader);
        observeLutLoader(classLoader);
    }

    /** The stock label still names 120fps even though Vj now enables it below. */
    private static void relabelUnsupportedHint() {
        if (property(FORCE_120, 1) == 0) return;
        try {
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setText",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
                            String text = param.args[0].toString();
                            if (!text.contains("120fps") || !text.contains("8K")) return;
                            param.args[0] = text.replaceAll("(?i)\\s*120fps\\s*(or|或|/)\\s*", "");
                        }
                    });
        } catch (Throwable error) {
            Log.e(TAG, "unsupported hint relabel failed", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.LOG_LUT);
    }

    /**
     * The stock recorder collapses {@code oplus_setting_bar_left} to the flash button and removes
     * the adjacent LUT badge while recording. The LUT itself remains active, but losing the orange
     * state light makes it look as if monitoring was turned off. Restore only that badge's visible
     * state in Pro Video when a real LUT is selected; its parent and the immersive-view eye button
     * keep their stock behaviour.
     */
    private static void keepLutBadgeVisibleWhileRecording() {
        if (property(KEEP_BADGE_PROPERTY, 1) == 0) {
            Log.i(TAG, "LUT recording badge guard disabled by property");
            return;
        }
        if (badgeVisibilityHookInstalled) return;
        badgeVisibilityHookInstalled = true;
        try {
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive || !hasExplicitLutSelection()) return;
                    if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                    int requested = (Integer) param.args[0];
                    if (requested != View.GONE && requested != View.INVISIBLE) return;
                    if (!(param.thisObject instanceof View)) return;
                    if (!isLogLutBadge((View) param.thisObject)) return;
                    param.args[0] = View.VISIBLE;
                    if (!reportedBadgeKept) {
                        reportedBadgeKept = true;
                        Log.i(TAG, "LUT recording badge kept visible");
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "LUT recording badge guard unavailable", error);
        }
    }

    private static boolean isLogLutBadge(View view) {
        boolean inTopLeftBar = false;
        for (ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            View parentView = (View) parent;
            if ("com.oplus.camera.ui.newsetting.container.CameraMenuContainerTopLeft"
                    .equals(parentView.getClass().getName())) {
                inTopLeftBar = true;
                break;
            }
            int id = parentView.getId();
            if (id == View.NO_ID) continue;
            try {
                if ("oplus_setting_bar_left".equals(
                        parentView.getResources().getResourceEntryName(id))) {
                    inTopLeftBar = true;
                    break;
                }
            } catch (Throwable ignored) {
                // A dynamically inflated or themed container may not expose its resource name.
            }
        }
        if (!inTopLeftBar) return false;
        CharSequence description = view.getContentDescription();
        if (description != null && "rec".equals(description.toString())) return true;
        // The stock view is a custom RotationOptionItemView without an id. While visible it is the
        // second 96px slot in the left bar (left=96), whereas the flash control occupies left=0.
        return view.getLeft() > 0 && view.getWidth() > 0 && view.getHeight() > 0;
    }

    /**
     * The LUT preferences and the GL renderer are process-wide. A persisted Log + direct-output
     * selection therefore survives a switch to photo, portrait or GR mode; without this guard the
     * preview hooks keep forcing index 15 in every mode and produce strongly oversaturated colour.
     * BaseMode.Y7 builds the session for the mode that is actually active, so its U0 field gives
     * us an exact boundary without changing any stock preferences.
     */
    private static void observeActiveCameraMode(ClassLoader classLoader) {
        try {
            Class<?> baseMode = XposedHelpers.findClass(MODE_BASE, classLoader);
            XposedBridge.hookAllMethods(baseMode, "Y7", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    activateCameraMode(
                            classLoader, param.thisObject,
                            modeName(param.thisObject), "session");
                }
            });
            XC_MethodHook inactiveHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !isActiveModeInstance(param.thisObject)) return;
                    activateCameraMode(
                            classLoader, param.thisObject, "", "inactive");
                }
            };
            XposedBridge.hookAllMethods(baseMode, "c", inactiveHook);
            XposedBridge.hookAllMethods(baseMode, "l0", inactiveHook);
        } catch (Throwable error) {
            Log.e(TAG, "active camera mode scope unavailable", error);
        }
    }

    private static synchronized void activateCameraMode(
            ClassLoader classLoader, Object mode, String name, String reason) {
        boolean active = MODE_PRO_VIDEO_NAME.equals(name);
        String previousName = activeCameraMode;
        boolean previouslyActive = proVideoModeActive;
        int previousLutIndex = selectedLutIndex;
        String previousLutFile = selectedLutFileName;
        boolean clearedSharedLut = false;
        activeMode = new java.lang.ref.WeakReference<>(active ? mode : null);
        activeCameraMode = name;
        proVideoModeActive = active;
        if (previouslyActive && !active) {
            clearProVideoScopedLutState(classLoader, previousLutIndex);
            clearedSharedLut = true;
        } else if (active) {
            // Keep the transition diagnostic once per entry into Pro Video.
            lastStaleCanvasLutProbe = "";
        }
        if (previousName.equals(name) && previouslyActive == active) return;
        Log.i(TAG, "camera mode scope reason=" + reason
                + " previous=" + displayValue(previousName)
                + " current=" + displayValue(name)
                + " proVideoActive=" + active
                + " lutBefore=" + previousLutIndex
                + " lutAfter=" + selectedLutIndex
                + " selectedBefore=" + displayValue(previousLutFile)
                + " sharedLutCleared=" + clearedSharedLut);
    }

    /**
     * Pro Video owns LUT indexes 9, 10 and 15. Imported LUT index 15 is also installed into the
     * process-wide {@code Lut3dDataManager} path table, so switching to Master, XPAN or Hasselblad
     * otherwise leaves both the module state and the native-facing path alive. Clear that working
     * state on the mode boundary while preserving the stock Pro Video preferences, which are
     * restored when Pro Video is entered again.
     */
    private static void clearProVideoScopedLutState(
            ClassLoader classLoader, int previousLutIndex) {
        customLutActive = false;
        selectedLutPosition = -1;
        selectedLutIndex = NO_LUT_INDEX;
        selectedLutTitle = "";
        selectedLutFileName = "";
        reportedHalLutName = "";
        reportedRestoredSelection = "";
        lastSyntheticLutProbe = "";
        lastDrawSelectionProbe = "";
        previewLutContractKeys.clear();
        pendingPreviewPassLut.remove();
        previewCallback = new java.lang.ref.WeakReference<>(null);
        outputRenderer = new java.lang.ref.WeakReference<>(null);
        appCodecArmed = false;
        appCodecTargetRecorder = false;
        targetAppCodec = new java.lang.ref.WeakReference<>(null);
        recorderCreationDepth.remove();
        clearStaleProVideoCanvasLut(lastGlCanvas.get(), "mode boundary");

        if (previousLutIndex != CUSTOM_LUT_INDEX) return;
        try {
            Object manager = manager(classLoader);
            String previousPath = lutPath(manager, CUSTOM_LUT_INDEX);
            XposedHelpers.callMethod(manager, "n", new Object[]{null});
            Log.i(TAG, "cleared Pro Video custom LUT path"
                    + " previous=" + previousPath
                    + " current=" + lutPath(manager, CUSTOM_LUT_INDEX));
        } catch (Throwable error) {
            Log.e(TAG, "cleared Pro Video custom LUT path failed", error);
        }
    }

    private static boolean isActiveModeInstance(Object mode) {
        return mode != null && activeMode.get() == mode;
    }

    private static String modeName(Object mode) {
        Object value = getField(mode, "U0");
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Correct the AE request at the same final sink used by both EV controls.
     *
     * <p>BaseMode.V7 writes {@code CONTROL_AE_EXPOSURE_COMPENSATION=0} while assembling every
     * preview request. The stock UI writes the user's value through the same
     * {@code bb.c1.v(...)} method. Adding the correction to each incoming value therefore keeps
     * the user's EV offset: an incoming 0 becomes the mode correction, while an incoming +1 EV
     * becomes +1 EV plus the correction. The value is replaced, not accumulated, so the frequent
     * request rebuilds cannot stack one correction on top of another.
     */
    private static void installExposureCompensationFix(ClassLoader classLoader) {
        try {
            Class<?> camera = XposedHelpers.findClass(CAMERA_IMPLEMENTATION, classLoader);
            XposedBridge.hookAllMethods(camera, "v", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || property(EV_FIX_PROPERTY, 1) == 0
                            || param.args.length < 2
                            || param.args[0] != CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION
                            || !(param.args[1] instanceof Number)) {
                        return;
                    }

                    int fix = exposureCompensationUnits(classLoader);
                    if (fix == 0) return;
                    int original = ((Number) param.args[1]).intValue();
                    int effective = clamp(
                            original + fix, -MAX_AE_EV_UNITS, MAX_AE_EV_UNITS);
                    param.args[1] = Integer.valueOf(effective);
                    logExposureCompensationFix(
                            classLoader, original, fix, effective);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "AE exposure compensation fix unavailable", error);
        }
    }

    private static int exposureCompensationUnits(ClassLoader classLoader) {
        Object mode = activeMode.get();
        if (!proVideoModeActive || mode == null
                || !"log".equalsIgnoreCase(logFormat(classLoader))
                || !hasExplicitLutSelection()
                || !"video_size_4kuhd".equals(videoSize(mode))) {
            return 0;
        }

        int fps = videoFps(classLoader, mode);
        int direct = currentDirectMode(classLoader);
        if (fps >= 120) {
            int base = property(EV_FIX_120_PROPERTY, DEFAULT_EV_FIX_120);
            int directOnly = property(EV_FIX_120_ON_PROPERTY, 0);
            return base + (directOutputEnabled(direct) ? directOnly : 0);
        }
        if (fps == 60 && directOutputEnabled(direct)) {
            return property(EV_FIX_60_PROPERTY, DEFAULT_EV_FIX_60);
        }
        return 0;
    }

    private static String videoSize(Object mode) {
        try {
            return String.valueOf(XposedHelpers.callMethod(mode, "h7"));
        } catch (Throwable error) {
            return "";
        }
    }

    private static int videoFps(ClassLoader classLoader, Object mode) {
        try {
            int fps = intValue(XposedHelpers.callMethod(mode, "ol"), -1);
            if (fps > 0) return fps;
        } catch (Throwable ignored) {
            // Fall back to the public feature data below.
        }
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("jd.a", classLoader), "d");
            return intValue(XposedHelpers.callMethod(manager, "b", key, "30"), -1);
        } catch (Throwable error) {
            return -1;
        }
    }

    private static void logExposureCompensationFix(
            ClassLoader classLoader, int original, int fix, int effective) {
        Object mode = activeMode.get();
        int fps = videoFps(classLoader, mode);
        int direct = currentDirectMode(classLoader);
        String signature = sessionSerial + "|fps=" + fps
                + "|direct=" + direct
                + "|original=" + original
                + "|fix=" + fix
                + "|effective=" + effective;
        if (signature.equals(lastEvFixProbe)) return;
        lastEvFixProbe = signature;
        Log.i(TAG, "AE exposure compensation fix fps=" + fps
                + " direct=" + direct
                + " size=" + videoSize(mode)
                + " original=" + original
                + " fix=" + fix
                + " effective=" + effective
                + " selectedLutIndex=" + selectedLutIndex);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * Select the stock {@code MasterToneLutByHal} strategy instead of {@code StraightOutOfTone}.
     *
     * <p>The strategy instance is chosen once in {@code MasterVideoDecision.a.<clinit>}. Hooking
     * c() makes the normal constructor pick the HAL strategy. Do not touch that singleton here:
     * forcing its initialization during ModuleEntry install blocks the camera UI on a black screen.
     */
    private static void enableHalDirectStrategy(final ClassLoader classLoader) {
        try {
            Class<?> decision = XposedHelpers.findClass("o9.h", classLoader);
            XposedBridge.hookAllMethods(decision, "c", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)
                            || (Integer) param.args[0] != HAL_DIRECT_FEATURE) {
                        return;
                    }
                    Integer stock = param.getResult() instanceof Integer
                            ? (Integer) param.getResult() : 0;
                    if (stock != 1) param.setResult(1);
                    if (!reportedStrategy) {
                        reportedStrategy = true;
                        Log.i(TAG, "Log LUT strategy feature[3] answered 1 (stock " + stock + ")");
                    }
                }
            });

            /*
             * The singleton may already have been initialized before c() was hooked. Replace it
             * only after the Pro Video mode object exists: doing this during ModuleEntry install
             * forces too much camera initialization early and leaves the app on a black screen.
             */
            Class<?> proVideo = XposedHelpers.findClass(MODE_PRO_VIDEO, classLoader);
            XposedBridge.hookAllMethods(proVideo, "Vj", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int mode = intValue(param.getResult(), -1);
                    if (mode >= 0) lastDirectMode = mode;
                    if (!enabled() || property(FORCE_120, 1) == 0) return;
                    if (!(param.getResult() instanceof Integer) || (Integer) param.getResult() != 1) return;
                    try {
                        Object is120 = XposedHelpers.callMethod(param.thisObject, "y");
                        if (Boolean.TRUE.equals(is120)) {
                            param.setResult(2);
                            if (!reported120) {
                                reported120 = true;
                                Log.i(TAG, "120fps direct mode: promoted 1 -> 2");
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "120fps direct decision unavailable", error);
                    }
                }
            });
            XposedBridge.hookAllConstructors(proVideo, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (enabled()) installHalStrategy(classLoader);
                }
            });
            XposedBridge.hookAllMethods(proVideo, "vk", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    String format = logFormat(classLoader);
                    if ("log".equals(format)) param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.hookAllMethods(proVideo, "Y7", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled()) {
                        activateCameraMode(
                                classLoader,
                                param.thisObject,
                                modeName(param.thisObject),
                                "pro video session");
                        sessionSerial++;
                        outputFrameCount = 0;
                        reportedDirectOffRender = false;
                        loadedIndexes.clear();
                        appCodecArmed = false;
                        appCodecTargetRecorder = false;
                        targetAppCodec = new java.lang.ref.WeakReference<>(null);
                        lastAppCodecProbe = "";
                        lastRecorderConfigProbe = "";
                        lastSyntheticLutProbe = "";
                        previewLutContractKeys.clear();
                        lastRecorderConstruction = "";
                        lastCodecStartup = "";
                        lastSurfaceAssignment = "";
                        lastOrientationFix = "";
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !strategyInstalled) return;
                    try {
                        Object decision = XposedHelpers.callMethod(param.thisObject, "Il");
                        Object strategy = XposedHelpers.getObjectField(
                                XposedHelpers.getStaticObjectField(
                                        XposedHelpers.findClass("o9.h$a", classLoader), "a"), "c");
                        int mode = (Integer) XposedHelpers.callMethod(strategy, "b", decision);
                        Log.i(TAG, "HAL decision inputs: lut="
                                + XposedHelpers.getBooleanField(decision, "a")
                                + " hdr=" + XposedHelpers.getBooleanField(decision, "b")
                                + " highRes=" + XposedHelpers.getBooleanField(decision, "c")
                                + " logKind=" + XposedHelpers.getIntField(decision, "d")
                                + " direct=" + XposedHelpers.getIntField(decision, "e")
                                + " format=" + logFormat(classLoader)
                                + " lutName=" + lutPreference(classLoader, "h1")
                                + " lastLutName=" + lutPreference(classLoader, "j1")
                                + " -> mode=" + mode);
                    } catch (Throwable error) {
                        Log.e(TAG, "HAL decision probe failed", error);
                    }
                }
            });

            XposedBridge.hookAllMethods(XposedHelpers.findClass("o9.f", classLoader), "b",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1 || !(param.getResult() instanceof Integer)) return;
                    int mode = (Integer) param.getResult();
                    if (mode <= 0 || mode == reportedDirectMode) return;
                    reportedDirectMode = mode;
                    Log.i(TAG, "HAL Log LUT mode " + mode);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "HAL Log LUT strategy unavailable", error);
        }
    }

    /**
     * The direct-output decision is made before the effect engine is built. X8U's
     * {@code rm.e.d7(0)} maps only Hl=2/4/6 to 9/10/15; the HAL strategy returns Hl=5 for
     * Log+direct, so stock falls through to 0. That makes
     * {@code key_support_preview_render_lut} false and prevents the ordinary preview LUT node
     * from existing at all. Repair the two consumers of that decision, not the later GL draw.
     */
    private static void repairPreviewLutType(final ClassLoader classLoader) {
        try {
            Class<?> proVideo = XposedHelpers.findClass(MODE_PRO_VIDEO, classLoader);
            XposedBridge.hookAllMethods(proVideo, "d7", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)
                            || (Integer) param.args[0] != 0) {
                        return;
                    }
                    try {
                        restorePersistedLutSelection(classLoader, "preview LUT decision");
                        int original = intValue(param.getResult(), NO_LUT_INDEX);
                        int desired = requestedPreviewLutIndex(classLoader, param.thisObject);
                        if (desired == NO_LUT_INDEX) return;
                        if (original != desired) param.setResult(desired);
                        int hl = intValue(
                                XposedHelpers.callMethod(param.thisObject, "Hl"), -1);
                        logPreviewLutContract(
                                "d7(0) original=" + original
                                        + " effective=" + intValue(param.getResult(), -1)
                                        + " Hl=" + hl,
                                classLoader);
                    } catch (Throwable error) {
                        Log.e(TAG, "preview LUT d7 repair failed", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "preview LUT d7 hook unavailable", error);
        }

        try {
            Class<?> baseMode = XposedHelpers.findClass(MODE_BASE, classLoader);
            XposedBridge.hookAllMethods(baseMode, "si", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    try {
                        int original = (Integer) param.args[0];
                        int desired = requestedPreviewLutIndex(classLoader, param.thisObject);
                        if (desired != NO_LUT_INDEX && original != desired) {
                            param.args[0] = desired;
                        }
                        logPreviewLutContract(
                                "BaseMode si original=" + original
                                        + " effective=" + intValue(param.args[0], -1),
                                classLoader);
                    } catch (Throwable error) {
                        Log.e(TAG, "BaseMode si preview repair failed", error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    try {
                        Object value = getField(param.thisObject, "v2");
                        if (value instanceof Integer) {
                            logPreviewLutContract(
                                    "BaseMode v2=" + value, classLoader);
                        }
                    } catch (Throwable ignored) {
                        // The preceding before-hook already reports the effective argument.
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "BaseMode preview LUT contract hook unavailable", error);
        }

        try {
            Class<?> callback = XposedHelpers.findClass(
                    PREVIEW_EFFECT_CALLBACK, classLoader);
            XposedBridge.hookAllMethods(callback, "getPreviewRenderLutType",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled()) return;
                            try {
                                int original = intValue(param.getResult(), NO_LUT_INDEX);
                                int desired = requestedPreviewLutIndex(
                                        classLoader, null);
                                if (desired != NO_LUT_INDEX && original != desired) {
                                    param.setResult(desired);
                                }
                                if (desired != NO_LUT_INDEX) {
                                    logPreviewLutContract(
                                            "preview LUT type original=" + original
                                                    + " effective="
                                                    + intValue(param.getResult(), -1),
                                            classLoader);
                                }
                            } catch (Throwable error) {
                                Log.e(TAG, "preview LUT type repair failed", error);
                            }
                        }
                    });
        } catch (Throwable error) {
            Log.i(TAG, "preview LUT type callback unavailable: " + error);
        }
    }

    /**
     * Return the selected preview LUT only for the exact feature case. The decision object is
     * consulted as a fallback because d7() can run before some DataManager readers are warm.
     */
    private static int requestedPreviewLutIndex(ClassLoader classLoader, Object mode) {
        if (!proVideoModeActive) return NO_LUT_INDEX;
        if (!"log".equals(logFormat(classLoader))) return NO_LUT_INDEX;
        if (!directOutputEnabled(effectiveDirectMode(classLoader, mode))) {
            return NO_LUT_INDEX;
        }
        if (selectedLutPosition < 0) {
            restorePersistedLutSelection(classLoader, "preview LUT query");
        }
        return lutIndexForPosition(selectedLutPosition);
    }

    private static int effectiveDirectMode(ClassLoader classLoader, Object mode) {
        int direct = currentDirectMode(classLoader);
        if (direct == DIRECT_OUTPUT_ON || mode == null) return direct;
        try {
            Object decision = XposedHelpers.callMethod(mode, "Il");
            int decisionDirect = XposedHelpers.getIntField(decision, "e");
            if (decisionDirect == DIRECT_OUTPUT_ON) return decisionDirect;
        } catch (Throwable ignored) {
            // Fall back to the preference value above.
        }
        return direct;
    }

    private static void logPreviewLutContract(String message, ClassLoader classLoader) {
        String signature = sessionSerial + "|" + message
                + "|position=" + selectedLutPosition
                + "|index=" + selectedLutIndex
                + "|custom=" + customLutActive
                + "|direct=" + currentDirectMode(classLoader);
        if (!previewLutContractKeys.add(signature)) return;
        Log.i(TAG, "preview LUT contract " + message
                + " direct=" + currentDirectMode(classLoader)
                + " format=" + logFormat(classLoader)
                + " position=" + selectedLutPosition
                + " selectedLutIndex=" + selectedLutIndex
                + " customLut=" + customLutActive
                + " selected=" + displayValue(selectedLutFileName));
    }

    /**
     * The stock menu stores the display title ("LutRoot") and then looks that exact string up as a
     * file. Imported files are called "LutRoot.cube", so the lookup returns null. Keep the stock
     * title state intact for the UI, but feed the actual path to the preview loader.
     */
    private static void repairSelectedLut(ClassLoader classLoader) {
        try {
            Class<?> menu = XposedHelpers.findClass(MENU_VIEW, classLoader);
            XposedBridge.hookAllMethods(menu, "setSelectedLutPositionAndApply", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    updateSelectedLut(param.thisObject, (Integer) param.args[0], false);
                }
            });
            // z() restores the adapter from h1 after a cold start and does not reload the preview.
            XposedBridge.hookAllMethods(menu, "z", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (enabled() && proVideoModeActive) {
                        updateSelectedLut(param.thisObject, -1, true);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "selected LUT path repair unavailable", error);
        }
    }

    private static void updateSelectedLut(Object menu, int requestedPosition, boolean restored) {
        try {
            Object adapter = XposedHelpers.getObjectField(menu, "h");
            if (adapter == null) return;
            List<?> items = (List<?>) XposedHelpers.getObjectField(adapter, "a");
            int position = requestedPosition;
            if (position < 0) position = XposedHelpers.getIntField(adapter, "b");
            if (items == null || position < 0 || position >= items.size()) return;
            if (position < 3) {
                selectedLutPosition = position;
                selectedLutIndex = lutIndexForPosition(position);
                customLutActive = false;
                selectedLutTitle = "";
                selectedLutFileName = "";
                reloadSelectedLut(menu, restored);
                Log.i(TAG, (restored ? "restored" : "selected")
                        + " LUT position=" + position
                        + " index=" + selectedLutIndex
                        + " custom=false");
                return;
            }

            Object item = items.get(position);
            if (item == null) return;
            String title = String.valueOf(XposedHelpers.callMethod(item, "getTitle"));
            String itemPath = String.valueOf(XposedHelpers.callMethod(item, "getFilePath"));
            if (title.isEmpty() || "null".equals(title)) return;

            String fileName = fileName(itemPath, title);
            String resolvedName = findLutFileName(title, fileName);
            if (resolvedName != null) fileName = resolvedName;
            String path = resolveLutPath(menu, title, fileName, itemPath);

            customLutActive = true;
            selectedLutPosition = position;
            selectedLutIndex = CUSTOM_LUT_INDEX;
            selectedLutTitle = title;
            selectedLutFileName = fileName;
            if (path != null) {
                Object manager = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass(LUT_MANAGER + "$b", menu.getClass().getClassLoader()),
                        "a");
                XposedHelpers.callMethod(manager, "n", path);
                reloadSelectedLut(menu, restored);
            }
            Log.i(TAG, (restored ? "restored" : "selected")
                    + " LUT position=" + position
                    + " index=" + selectedLutIndex
                    + " title=" + title
                    + " filePath=" + path
                    + " bytes=" + lutFileSize(path)
                    + " halName=" + fileName);
        } catch (Throwable error) {
            Log.e(TAG, "selected LUT path repair failed", error);
        }
    }

    /**
     * {@code n(path)} only replaces index 15 and drops its cache. The stock UI never asks the LUT
     * manager to load the replacement while the surface is alive, so selecting another LUT leaves
     * both the viewfinder and the recording renderer on the previous table.
     */
    private static void installLutRendererHooks(ClassLoader classLoader) {
        try {
            Class<?> preview = XposedHelpers.findClass(PREVIEW_RENDERER, classLoader);
            XposedBridge.hookAllMethods(preview, "y0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive) return;
                    // onSurfaceCreated() calls y0() before A0(), so restore here to make the
                    // synthetic-LUT guard aware of the persisted selection in time.
                    restorePersistedLutSelection(classLoader, "synthetic LUT setup");
                    Log.i(TAG, "synthetic LUT setup requested args="
                            + describeArgs(param.args)
                            + " selectedPosition=" + selectedLutPosition
                            + " selectedLutIndex=" + selectedLutIndex
                            + " customLut=" + customLutActive
                            + " direct=" + currentDirectMode(classLoader));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive) return;
                    Log.i(TAG, "synthetic LUT setup returned args="
                            + describeArgs(param.args)
                            + " selectedPosition=" + selectedLutPosition
                            + " selectedLutIndex=" + selectedLutIndex
                            + " synthetic=" + describeSyntheticLut(param.thisObject));
                }
            });
            XposedBridge.hookAllMethods(preview, "A0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    restorePersistedLutSelection(classLoader, "preview surface");
                    int sourceIndex = (Integer) param.args[0];
                    if (customLutActive && sourceIndex != CUSTOM_LUT_INDEX) {
                        param.args[0] = CUSTOM_LUT_INDEX;
                    } else if (selectedLutPosition > 0 && selectedLutPosition < 3) {
                        param.args[0] = selectedLutIndex;
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    int sourceIndex = (Integer) param.args[0];
                    try {
                        Object callback = XposedHelpers.getObjectField(param.thisObject, "K");
                        previewCallback = new java.lang.ref.WeakReference<>(callback);
                        Log.i(TAG, "preview renderer ready, sourceIndex=" + sourceIndex
                                + " selectedLutIndex=" + selectedLutIndex
                                + " customLut=" + customLutActive
                                + " selected=" + displayValue(selectedLutFileName));
                        if (customLutActive) {
                            selectedLutIndex = CUSTOM_LUT_INDEX;
                        } else {
                            selectedLutIndex = sourceIndex;
                            // A0() deliberately skips j(0), leaving the previous table installed.
                            if (sourceIndex == NO_LUT_INDEX) {
                                requestLut(manager(param.thisObject), callback,
                                        NO_LUT_INDEX, "preview clear");
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "preview renderer callback unavailable", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "preview renderer hook unavailable", error);
        }

        try {
            Class<?> output = XposedHelpers.findClass(OUTPUT_RENDERER, classLoader);
            XposedBridge.hookAllMethods(output, "d0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive) return;
                    try {
                        restorePersistedLutSelection(classLoader, "output surface");
                        XposedHelpers.setIntField(
                                param.thisObject, "e", selectedLutIndex);
                    } catch (Throwable error) {
                        Log.e(TAG, "output renderer LUT index unavailable", error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive) return;
                    try {
                        outputRenderer =
                                new java.lang.ref.WeakReference<>(param.thisObject);
                        Object callback = XposedHelpers.getObjectField(param.thisObject, "f");
                        Log.i(TAG, "output renderer ready, index="
                                + XposedHelpers.getIntField(param.thisObject, "e")
                                + " selectedLutIndex=" + selectedLutIndex
                                + " customLut=" + customLutActive
                                + " selected=" + displayValue(selectedLutFileName));
                        if (selectedLutIndex == NO_LUT_INDEX) {
                            requestLut(manager(param.thisObject), callback,
                                    NO_LUT_INDEX, "output clear");
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "output renderer callback unavailable", error);
                    }
                }
            });
            XposedBridge.hookAllMethods(output, "onDrawFrame", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || !proVideoModeActive) return;
                    int count = ++outputFrameCount;
                    if (shouldUseOrdinaryLutDraw(classLoader)) {
                        try {
                            int previousIndex = XposedHelpers.getIntField(
                                    param.thisObject, "e");
                            if (previousIndex != selectedLutIndex) {
                                XposedHelpers.setIntField(
                                        param.thisObject, "e", selectedLutIndex);
                            }
                        } catch (Throwable error) {
                            if (count == 1) {
                                Log.i(TAG, "output renderer LUT index guard unavailable: "
                                        + error);
                            }
                        }
                    }
                    if (count == 1 || count % 300 == 0) {
                        try {
                            Log.i(TAG, "output renderer drew frame=" + count
                                    + " lutIndex=" + XposedHelpers.getIntField(
                                            param.thisObject, "e")
                                    + " selectedLutIndex=" + selectedLutIndex
                                    + " customLut=" + customLutActive);
                        } catch (Throwable error) {
                            Log.i(TAG, "output renderer drew frame=" + count
                                    + " (index unavailable)");
                        }
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "output renderer hook unavailable", error);
        }

        observeLutCallbacks(classLoader, PREVIEW_RENDERER + "$a", "preview");
        observeLutCallbacks(classLoader, OUTPUT_RENDERER + "$a", "output");
    }

    /**
     * Restore the selection that stock persists for the panel. The Pro Video menu writes g1/h1
     * when a LUT is picked, but its restore method only updates the adapter. A surface created by
     * the direct-output path never passes through that method, so its renderer starts at index 0
     * even though the UI still shows the imported LUT.
     */
    private static synchronized void restorePersistedLutSelection(
            ClassLoader classLoader, String reason) {
        if (!enabled() || !proVideoModeActive) return;
        try {
            int position = lutPreferenceInt(
                    classLoader, DATA_CURRENT_POSITION, Integer.MIN_VALUE);
            String title = lutPreference(
                    classLoader, DATA_CURRENT_TITLE, "");
            int lastPosition = lutPreferenceInt(
                    classLoader, DATA_LAST_POSITION, position);
            String lastTitle = lutPreference(
                    classLoader, DATA_LAST_TITLE, title);

            if (position == Integer.MIN_VALUE) {
                position = lastPosition;
                title = lastTitle;
            }

            if (position < 0 || position > 3) {
                Log.i(TAG, "persisted LUT selection unavailable at " + reason
                        + " g1=" + position + " h1=" + displayValue(title)
                        + " i1=" + lastPosition + " j1=" + displayValue(lastTitle));
                return;
            }

            String signature = position + "|" + title + "|" + lastPosition + "|" + lastTitle;
            if (!signature.equals(reportedRestoredSelection)) {
                reportedRestoredSelection = signature;
                Log.i(TAG, "persisted LUT selection at " + reason
                        + " g1=" + position + " h1=\"" + title + "\""
                        + " i1=" + lastPosition + " j1=\"" + lastTitle + "\"");
            }

            selectedLutPosition = position;
            if (position == 0 || position == 1 || position == 2) {
                customLutActive = false;
                selectedLutIndex = lutIndexForPosition(position);
                selectedLutTitle = "";
                selectedLutFileName = "";
                return;
            }

            String resolvedName = findLutFileName(title, title);
            if (resolvedName == null && !title.isEmpty()) {
                resolvedName = findLutFileName(title, title + ".cube");
            }
            if (resolvedName == null) {
                customLutActive = false;
                selectedLutIndex = NO_LUT_INDEX;
                selectedLutFileName = "";
                Log.i(TAG, "persisted custom LUT is missing at " + reason
                        + " h1=\"" + title + "\" directory=" + LUT_DIRECTORY);
                return;
            }

            String path = LUT_DIRECTORY + resolvedName;
            customLutActive = true;
            selectedLutIndex = CUSTOM_LUT_INDEX;
            selectedLutTitle = title;
            selectedLutFileName = resolvedName;

            Object manager = manager(classLoader);
            String installedPath = lutPath(manager, CUSTOM_LUT_INDEX);
            if (!path.equals(installedPath)) {
                XposedHelpers.callMethod(manager, "n", path);
                Log.i(TAG, "restored custom LUT path at " + reason
                        + " path=" + path
                        + " bytes=" + lutFileSize(path)
                        + " previous=" + installedPath);
            }
        } catch (Throwable error) {
            Log.e(TAG, "persisted LUT selection restore failed at " + reason, error);
        }
    }

    private static void observeLutCallbacks(
            ClassLoader classLoader, String className, String renderer) {
        try {
            Class<?> callback = XposedHelpers.findClass(className, classLoader);
            XposedBridge.hookAllMethods(callback, "a", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 2) return;
                    Log.i(TAG, "LUT callback " + renderer + " error"
                            + " index=" + intValue(param.args[0], -1)
                            + " code=" + intValue(param.args[1], -1)
                            + " selectedLutIndex=" + selectedLutIndex);
                }
            });
            XposedBridge.hookAllMethods(callback, "b", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 3) return;
                    Object table = param.args[0];
                    int tableLength = table instanceof float[] ? ((float[]) table).length : -1;
                    Log.i(TAG, "LUT callback " + renderer + " loaded"
                            + " index=" + intValue(param.args[1], -1)
                            + " size=" + intValue(param.args[2], -1)
                            + " floats=" + tableLength
                            + " selectedLutIndex=" + selectedLutIndex
                            + " selected=" + displayValue(selectedLutFileName));
                    disableSyntheticLutAfterLoad(
                            classLoader, renderer, param.thisObject,
                            intValue(param.args[1], -1));
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "LUT callback probe unavailable for " + renderer + ": " + error);
        }
    }

    private static void disableSyntheticLutAfterLoad(
            ClassLoader classLoader, String rendererName, Object callback, int loadedIndex) {
        if (!shouldUseOrdinaryLutDraw(classLoader)) return;
        if (loadedIndex != selectedLutIndex) return;
        try {
            String ownerName = "preview".equals(rendererName)
                    ? PREVIEW_RENDERER : OUTPUT_RENDERER;
            Class<?> ownerClass = XposedHelpers.findClass(ownerName, classLoader);
            Object owner = getFieldByValue(callback, ownerClass);
            Object canvas = findCanvas(owner);
            if (canvas == null) return;
            boolean wasEnabled = getBooleanField(canvas, "q", false);
            if (wasEnabled) {
                XposedHelpers.setBooleanField(canvas, "q", false);
            }
            Log.i(TAG, "ordinary LUT made effective on " + rendererName
                    + " loadedIndex=" + loadedIndex
                    + " selectedLutIndex=" + selectedLutIndex
                    + " syntheticEnabled=" + wasEnabled + "->"
                    + getBooleanField(canvas, "q", false)
                    + " " + describeCanvasLut(canvas));
        } catch (Throwable error) {
            Log.i(TAG, "ordinary LUT visibility guard failed on " + rendererName
                    + ": " + error);
        }
    }

    /**
     * Watch the final GL state instead of inferring success from the loader callback. A table can
     * be present in the ordinary LUT map while the synthetic-LUT branch still wins at draw time.
     */
    private static void observeLutRendererState(ClassLoader classLoader) {
        try {
            Class<?> canvas = XposedHelpers.findClass(GL_CANVAS, classLoader);
            XposedBridge.hookAllMethods(canvas, "e0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    rememberGlCanvas(param.thisObject);
                    clearStaleProVideoCanvasLut(param.thisObject, "ordinary upload");
                    if (param.args.length < 3) return;
                    Object table = param.args[0];
                    int index = intValue(param.args[1], -1);
                    int size = intValue(param.args[2], -1);
                    Log.i(TAG, "GL ordinary LUT uploaded index=" + index
                            + " size=" + size
                            + " floats=" + (table instanceof float[]
                                    ? ((float[]) table).length : -1)
                            + " map=" + describeOrdinaryLutMap(param.thisObject, index)
                            + " " + describeCanvasLut(param.thisObject));
                }
            });

            XposedBridge.hookAllMethods(canvas, "r", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    rememberGlCanvas(param.thisObject);
                    clearStaleProVideoCanvasLut(param.thisObject, "synthetic upload");
                    if (param.args.length < 3) return;
                    boolean requested = Boolean.TRUE.equals(param.args[2]);
                    boolean guarded = requested && shouldUseOrdinaryLutDraw(classLoader);
                    if (guarded) {
                        // The synthetic basic-tone table competes with the selected ordinary LUT
                        // map at draw time. Keep the same ordinary table path that already works
                        // with direct-output OFF; do not upload a second synthetic table.
                        param.args[2] = Boolean.FALSE;
                        XposedHelpers.setBooleanField(param.thisObject, "q", false);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 3) return;
                    int size = intValue(param.args[1], -1);
                    boolean effective = Boolean.TRUE.equals(param.args[2]);
                    boolean guarded = !effective && shouldUseOrdinaryLutDraw(classLoader);
                    String signature = sessionSerial
                            + "|requested=" + effective
                            + "|index=" + selectedLutIndex
                            + "|position=" + selectedLutPosition
                            + "|direct=" + currentDirectMode(classLoader)
                            + "|guarded=" + guarded;
                    if (!signature.equals(lastSyntheticLutProbe)) {
                        lastSyntheticLutProbe = signature;
                        Log.i(TAG, "GL synthetic LUT requested=" + effective
                                + " size=" + size
                                + " tablePresent=" + (param.args[0] != null)
                                + " guarded=" + guarded
                                + " " + describeCanvasLut(param.thisObject));
                    }
                }
            });

            /*
             * nn.l.y0() uploads the synthetic basic-tone table through gl.s.r(). Even after that
             * request is suppressed, gl.s.v can still point at the previous LUT index. y0() uses
             * that field through u0() to choose the ordinary 3D-LUT shader. Pin it to the current
             * Log LUT for the exact direct-output scenario, immediately before the shader choice.
             */
            XposedBridge.hookAllMethods(canvas, "y0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    rememberGlCanvas(param.thisObject);
                    clearStaleProVideoCanvasLut(param.thisObject, "shader choice");
                    if (!shouldUseOrdinaryLutDraw(classLoader)) return;
                    int previousIndex = getIntField(param.thisObject, "v", -1);
                    boolean previousSynthetic = getBooleanField(
                            param.thisObject, "q", false);
                    boolean ordinaryPresent = ordinaryLutPresent(
                            param.thisObject, selectedLutIndex);
                    boolean bypassOesPass = shouldBypassOesPreviewLutPass(
                            param.args, classLoader);
                    if (bypassOesPass) {
                        pendingPreviewPassLut.set(new PreviewPassLutState(
                                previousIndex, previousSynthetic));
                        XposedHelpers.setIntField(
                                param.thisObject, "v", NO_LUT_INDEX);
                    } else if (previousIndex != selectedLutIndex) {
                        XposedHelpers.setIntField(
                                param.thisObject, "v", selectedLutIndex);
                    }
                    if (previousSynthetic) {
                        XposedHelpers.setBooleanField(param.thisObject, "q", false);
                    }
                    String signature = sessionSerial + "|index=" + selectedLutIndex
                            + "|ordinaryPresent=" + ordinaryPresent
                            + "|bypassOes=" + bypassOesPass;
                    if (!signature.equals(lastDrawSelectionProbe)) {
                        lastDrawSelectionProbe = signature;
                        Log.i(TAG, "GL direct LUT draw guard index=" + selectedLutIndex
                                + " previousIndex=" + previousIndex
                                + " ordinaryPresent=" + ordinaryPresent
                                + " bypassOesPreviewLut=" + bypassOesPass
                                + " synthetic=" + previousSynthetic + "->"
                                + getBooleanField(param.thisObject, "q", false)
                                + " " + describeCanvasLut(param.thisObject));
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    PreviewPassLutState pending = pendingPreviewPassLut.get();
                    boolean bypassed = pending != null;
                    boolean passActiveLut = bypassed
                            && activeLutPresent(param.thisObject);
                    if (pending != null) {
                        pendingPreviewPassLut.remove();
                        try {
                            XposedHelpers.setIntField(
                                    param.thisObject, "v", pending.index);
                            XposedHelpers.setBooleanField(
                                    param.thisObject, "q", pending.synthetic);
                        } catch (Throwable error) {
                            Log.e(TAG, "preview pass LUT restore failed", error);
                        }
                    }
                    if (!enabled() || !proVideoModeActive || param.getResult() == null) return;
                    try {
                        String signature = sessionSerial
                                + "|direct=" + currentDirectMode(classLoader)
                                + "|format=" + logFormat(classLoader)
                                + "|index=" + getIntField(param.thisObject, "v", -1)
                                + "|bypassOes=" + bypassed
                                + "|shader=" + shaderName(
                                        param.thisObject, param.getResult())
                                + "|lut=" + activeLutPresent(param.thisObject)
                                + "|" + describeCanvasLut(param.thisObject)
                                + "|input=" + describeInputTexture(param.args);
                        if (shaderProbeSession != sessionSerial) {
                            synchronized (reportedShaderProbes) {
                                reportedShaderProbes.clear();
                                shaderProbeSession = sessionSerial;
                            }
                        }
                        if (!reportedShaderProbes.add(signature)) return;
                        Log.i(TAG, "GL final shader choice direct="
                                + currentDirectMode(classLoader)
                                + " format=" + logFormat(classLoader)
                                + " shader=" + shaderName(
                                        param.thisObject, param.getResult())
                                + " bypassOesPreviewLut=" + bypassed
                                + " passActiveLut=" + passActiveLut
                                + " activeLut=" + activeLutPresent(param.thisObject)
                                + " sRGBToP3=" + booleanResult(param.thisObject, "z0")
                                + " f660y=" + getBooleanField(
                                        param.thisObject, "f660y", false)
                                + " f657s=" + getBooleanField(
                                        param.thisObject, "f657s", false)
                                + " f655p=" + getBooleanField(
                                        param.thisObject, "f655p", false)
                                + " f651l=" + getBooleanField(
                                        param.thisObject, "f651l", false)
                                + " n0=" + getBooleanField(
                                        param.thisObject, "n0", false)
                                + " q0=" + getBooleanField(
                                        param.thisObject, "q0", false)
                                + " " + describeCanvasLut(param.thisObject)
                                + " input=" + describeInputTexture(param.args));
                    } catch (Throwable error) {
                        Log.i(TAG, "GL final shader probe failed: " + error);
                    }
                }
            });

            /*
             * u0() is the final reader used by every shader branch to resolve canvas.v against the
             * ordinary LUT map. Clearing at this point also covers draw paths that bypass y0().
             * The synthetic BasicTone table is left enabled and still supplies portrait processing.
             */
            XposedBridge.hookAllMethods(canvas, "u0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    rememberGlCanvas(param.thisObject);
                    clearStaleProVideoCanvasLut(param.thisObject, "final lookup");
                }
            });

            XposedBridge.hookAllConstructors(canvas, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    rememberGlCanvas(param.thisObject);
                    Log.i(TAG, "GL canvas created state="
                            + describeCanvasLut(param.thisObject));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "GL LUT renderer probe unavailable", error);
        }

        try {
            Class<?> manager = XposedHelpers.findClass(LUT_MANAGER, classLoader);
            XposedBridge.hookAllMethods(manager, "l", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1) return;
                    Log.i(TAG, "LUT manager reset=" + param.args[0]
                            + " selectedPosition=" + selectedLutPosition
                            + " selectedLutIndex=" + selectedLutIndex);
                }
            });
            XposedBridge.hookAllMethods(manager, "e", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 3) return;
                    Log.i(TAG, "LUT manager synthetic build args="
                            + describeArgs(param.args)
                            + " result=" + displayValue(param.getResult())
                            + " selectedPosition=" + selectedLutPosition
                            + " selectedLutIndex=" + selectedLutIndex);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "LUT manager synthetic probe unavailable", error);
        }
    }

    /**
     * Record the actual effect-graph decision. The earlier r170 logs proved that the LUT table was
     * ready, but they did not distinguish the normal preview-LUT copy from the HDR-transform passes.
     * These probes are transition-limited so the camera log stays usable at 30 fps.
     */
    private static void observePreviewEffectPipeline(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass(EFFECT_PROCESSOR, classLoader);
            XposedBridge.hookAllMethods(processor, "getSupportEffectFlags",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled()) return;
                            int original = intValue(param.getResult(), -1);
                            int clear = property(EFFECT_FLAGS_CLEAR_PROPERTY, 0);
                            boolean applies = clear != 0
                                    && proVideoModeActive
                                    && hasExplicitLutSelection()
                                    && directOutputEnabled(
                                            currentDirectMode(classLoader));
                            int effective = applies ? original & ~clear : original;
                            if (effective != original) param.setResult(effective);
                            lastSupportEffectFlags = effective;
                            String signature = sessionSerial + "|direct="
                                    + currentDirectMode(classLoader)
                                    + "|original=" + original
                                    + "|effective=" + effective
                                    + "|clear=" + clear;
                            if (signature.equals(lastPreviewEffectFlagsProbe)) return;
                            lastPreviewEffectFlagsProbe = signature;
                            Log.i(TAG, "preview effect flags original=" + original
                                    + " effective=" + effective
                                    + " clearMask=" + clear
                                    + " applied=" + applies
                                    + " hdr=" + hasFlag(effective, FLAG_HDR_TRANSFORM)
                                    + " hdrLut=" + hasFlag(
                                            effective, FLAG_HDR_TRANSFORM_LUT)
                                    + " previewLut=" + hasFlag(
                                            effective, FLAG_PREVIEW_RENDER_LUT)
                                    + " direct=" + currentDirectMode(classLoader));
                        }
                    });

            XposedBridge.hookAllMethods(processor, "copyCacheTexture",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled()) return;
                            try {
                                boolean cacheNeedsLut = booleanResult(
                                        getField(param.thisObject, "t"),
                                        "isCacheTextureNeedPreviewLut");
                                String signature = sessionSerial
                                        + "|direct=" + currentDirectMode(classLoader)
                                        + "|cacheNeedsLut=" + cacheNeedsLut
                                        + "|" + describeTextureSlots(param.thisObject);
                                if (signature.equals(lastPreviewCacheProbe)) return;
                                lastPreviewCacheProbe = signature;
                                Log.i(TAG, "preview cache copy"
                                        + signature.substring(
                                                signature.indexOf('|'))
                                        + " flags=" + lastSupportEffectFlags);
                            } catch (Throwable error) {
                                Log.i(TAG, "preview cache copy probe failed: " + error);
                            }
                        }
                    });

            XposedBridge.hookAllMethods(processor, "effectProcess",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled() || param.args.length < 1) return;
                            try {
                                Object state = getField(param.thisObject, "H");
                                String signature = sessionSerial
                                        + "|direct=" + currentDirectMode(classLoader)
                                        + "|format=" + logFormat(classLoader)
                                        + "|" + describeEffectState(
                                                param.thisObject, state, classLoader)
                                        + "|" + describeTextureSlots(param.thisObject)
                                        + "|canvas=" + describeCanvasLut(param.args[0]);
                                if (signature.equals(lastPreviewEffectProbe)) return;
                                lastPreviewEffectProbe = signature;
                                Log.i(TAG, "preview effect process"
                                        + signature.substring(
                                                signature.indexOf('|')));
                            } catch (Throwable error) {
                                Log.i(TAG, "preview effect process probe failed: " + error);
                            }
                        }
                    });
        } catch (Throwable error) {
            Log.e(TAG, "preview effect pipeline probe unavailable", error);
        }
    }

    private static String describeEffectState(
            Object owner, Object state, ClassLoader classLoader) {
        Object callback = getField(owner, "t");
        return "hdr=" + getBooleanField(state, "f", false)
                + " hdrLut=" + getBooleanField(state, "g", false)
                + " focus=" + getBooleanField(state, "h", false)
                + " previewLut=" + getBooleanField(state, "i", false)
                + " lutBeforeHdr=" + getBooleanField(state, "j", false)
                + " cacheNeedsLut=" + booleanResult(
                        callback, "isCacheTextureNeedPreviewLut")
                + " basicTone=" + intResult(
                        callback, "getBasicToneCombineType", -1)
                + " previewType=" + intResult(
                        callback, "getPreviewRenderLutType", -1)
                + " supported=" + intResult(
                        callback, "getSupportEffectFlags", lastSupportEffectFlags)
                + " direct=" + currentDirectMode(classLoader);
    }

    private static String describeTextureSlots(Object processor) {
        Object textures = getField(processor, "x");
        return "textureHdr=" + mapContains(textures, FLAG_HDR_TRANSFORM)
                + " textureHdrLut=" + mapContains(textures, FLAG_HDR_TRANSFORM_LUT)
                + " texturePreviewLut=" + mapContains(textures, FLAG_PREVIEW_RENDER_LUT);
    }

    private static boolean mapContains(Object map, int key) {
        return map instanceof java.util.Map
                && ((java.util.Map<?, ?>) map).containsKey(key);
    }

    private static boolean hasFlag(int value, int flag) {
        return (value & flag) != 0;
    }

    private static boolean booleanResult(Object target, String method) {
        try {
            Object result = XposedHelpers.callMethod(target, method);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int intResult(Object target, String method, int fallback) {
        try {
            return intValue(XposedHelpers.callMethod(target, method), fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String shaderName(Object canvas, Object shader) {
        if (shader == null) return "null";
        String[] fields = {
                "h0", "i0", "O", "P", "Q", "R", "S", "T", "U",
                "e0", "f0", "g0", "b0", "c0", "N", "W", "X", "Y", "Z", "a0"
        };
        for (String field : fields) {
            if (shader == getField(canvas, field)) return field;
        }
        return simpleName(shader.getClass());
    }

    private static boolean activeLutPresent(Object canvas) {
        try {
            return XposedHelpers.callMethod(canvas, "u0") != null;
        } catch (Throwable ignored) {
            return ordinaryLutPresent(
                    canvas, getIntField(canvas, "v", -1));
        }
    }

    private static String describeInputTexture(Object[] args) {
        if (args == null || args.length < 1 || args[0] == null) return "null";
        Object input = args[0];
        int format = intResult(input, "f", -1);
        int width = getIntField(input, "a", -1);
        int height = getIntField(input, "b", -1);
        int source = getIntField(input, "j",
                getIntField(input, "f616j", -1));
        int target = getIntField(input, "k",
                getIntField(input, "f617k", -1));
        boolean opaque = booleanResult(input, "isOpaque");
        return "format=" + format
                + " size=" + width + "x" + height
                + " source=" + source
                + " target=" + target
                + " opaque=" + opaque;
    }

    private static String describeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < args.length; index++) {
            if (index > 0) result.append(", ");
            Object value = args[index];
            result.append(value == null ? "null" : value.getClass().getSimpleName())
                    .append('=')
                    .append(displayValue(value));
        }
        return result.append(']').toString();
    }

    private static String describeOrdinaryLutMap(Object canvas, int index) {
        Object map = getField(canvas, "u");
        if (!(map instanceof java.util.Map)) return "unavailable";
        java.util.Map<?, ?> values = (java.util.Map<?, ?>) map;
        return "entries=" + values.size()
                + " index" + index + "="
                + (values.containsKey(index) ? "present" : "missing");
    }

    private static String describeCanvasLut(Object canvas) {
        if (canvas == null) return "canvas=null";
        Object syntheticTable = getFieldAlias(canvas, "o", "f654o");
        Object hdrBlocked = getFieldAlias(canvas, "s", "f657s");
        return "ordinaryEntries=" + mapSize(getField(canvas, "u"))
                + " inputIndex=" + getIntField(canvas, "v", -1)
                + " syntheticEnabled=" + getBooleanField(canvas, "q", false)
                + " syntheticTable=" + (syntheticTable != null)
                + " hdrBlocked="
                + (hdrBlocked instanceof Boolean ? (Boolean) hdrBlocked : false);
    }

    private static String describeSyntheticLut(Object renderer) {
        Object canvas = findCanvas(renderer);
        return describeCanvasLut(canvas);
    }

    private static void rememberGlCanvas(Object canvas) {
        if (canvas != null) {
            lastGlCanvas = new java.lang.ref.WeakReference<>(canvas);
        }
    }

    private static boolean isProVideoLutIndex(int index) {
        return index == SDR_LUT_INDEX
                || index == HDR_LUT_INDEX
                || index == CUSTOM_LUT_INDEX;
    }

    /**
     * Pro Video indexes 9, 10 and 15 are process-wide GL state. Other modes can also allocate a
     * synthetic BasicTone table, but if the old index remains in canvas.v, u0() prefers the
     * ordinary Pro Video table and overrides that mode's own shader. Reset only the stale Pro
     * Video index; do not disable synthetic processing and do not touch ordinary indexes 1..8.
     */
    private static void clearStaleProVideoCanvasLut(Object canvas, String source) {
        if (!enabled() || proVideoModeActive || canvas == null) return;
        try {
            int index = getIntField(canvas, "v", NO_LUT_INDEX);
            if (!isProVideoLutIndex(index)) return;
            XposedHelpers.setIntField(canvas, "v", NO_LUT_INDEX);
            String signature = activeCameraMode + "|" + source + "|index=" + index
                    + "|ordinary=" + ordinaryLutPresent(canvas, index)
                    + "|synthetic=" + getBooleanField(canvas, "q", false);
            if (!signature.equals(lastStaleCanvasLutProbe)) {
                lastStaleCanvasLutProbe = signature;
                Log.i(TAG, "GL cleared stale Pro Video LUT index=" + index
                        + " nonPro=true source=" + displayValue(source)
                        + " ordinaryPresent=" + ordinaryLutPresent(canvas, index)
                        + " synthetic=" + getBooleanField(canvas, "q", false)
                        + " " + describeCanvasLut(canvas));
            }
        } catch (Throwable error) {
            Log.i(TAG, "GL stale Pro Video LUT clear failed at "
                    + displayValue(source) + ": " + error);
        }
    }

    private static int mapSize(Object value) {
        return value instanceof java.util.Map ? ((java.util.Map<?, ?>) value).size() : -1;
    }

    private static Object findCanvas(Object renderer) {
        ClassLoader classLoader = renderer == null
                ? LogLutDirect.class.getClassLoader()
                : renderer.getClass().getClassLoader();
        Class<?> canvas;
        try {
            canvas = XposedHelpers.findClass(GL_CANVAS, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
        return getFieldByValue(renderer, canvas);
    }

    private static boolean hasExplicitLutSelection() {
        return customLutActive
                || selectedLutPosition == 1
                || selectedLutPosition == 2
                || selectedLutIndex == SDR_LUT_INDEX
                || selectedLutIndex == HDR_LUT_INDEX
                || selectedLutIndex == CUSTOM_LUT_INDEX;
    }

    private static boolean directOutputEnabled(int directMode) {
        return directMode == DIRECT_OUTPUT_ON;
    }

    private static boolean shouldUseOrdinaryLutDraw(ClassLoader classLoader) {
        return proVideoModeActive
                && hasExplicitLutSelection()
                && directOutputEnabled(currentDirectMode(classLoader));
    }

    /**
     * Direct-output ON gives both the OES preview pass and the following texture pass the same
     * active LUT. Both i0 and h0 bake the 3D LUT and sRGB-to-P3 matrix, so the first pass applies
     * the conversion twice. The direct-output OFF baseline leaves the OES pass at index 0 and only
     * converts in the texture pass. Mirror that baseline for the large OES frame.
     */
    private static boolean shouldBypassOesPreviewLutPass(
            Object[] args, ClassLoader classLoader) {
        if (!shouldUseOrdinaryLutDraw(classLoader)
                || args == null || args.length < 1 || args[0] == null) {
            return false;
        }
        Object input = args[0];
        int format = intResult(input, "f", -1);
        int width = getIntField(input, "a", -1);
        int height = getIntField(input, "b", -1);
        return format != 3553 && width >= 720 && height >= 720;
    }

    private static boolean ordinaryLutPresent(Object canvas, int index) {
        Object map = getField(canvas, "u");
        return map instanceof java.util.Map
                && ((java.util.Map<?, ?>) map).containsKey(index);
    }

    /**
     * Record which output target owns a valid Surface and whether mn.t actually reaches the
     * VideoOutputRenderer. This is deliberately rate-limited: the method runs once per GL frame.
     */
    private static void observeOutputPipeline(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass(OUTPUT_PROCESSOR, classLoader);
            XposedBridge.hookAllMethods(processor, "M0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1) return;
                    Surface surface = asType(param.args[0], Surface.class);
                    Object target = getField(param.thisObject, "A");
                    String signature = sessionSerial + "|surface="
                            + describeSurface(surface)
                            + "|target=" + describeOutputTarget(target);
                    if (signature.equals(lastSurfaceAssignment)) return;
                    lastSurfaceAssignment = signature;
                    Log.i(TAG, "output surface assignment A="
                            + describeOutputTarget(target)
                            + " argument=" + describeSurface(surface)
                            + " renderer=" + displayValue(getField(target, "d")));
                }
            });
            XposedBridge.hookAllMethods(processor, "D", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length == 0) return;
                    TraceState state = traceState(param.thisObject);
                    Log.i(TAG, "output EGL surface created target="
                            + targetName(param.thisObject, param.args[0])
                            + " surface=" + describeOutputTarget(param.args[0])
                            + " renderer=" + state.renderer
                            + " blocked=" + state.blocked);
                }
            });
            XposedBridge.hookAllMethods(processor, "e", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastOutputTraceAt < 2000) return;
                    lastOutputTraceAt = now;
                    TraceState state = traceState(param.thisObject);
                    if (state.signature.equals(lastOutputTraceSignature)) return;
                    lastOutputTraceSignature = state.signature;
                    Log.i(TAG, "output pipeline " + state.signature
                            + " outputFrames=" + outputFrameCount);
                    if (!state.blocked && state.outputValid && !reportedDirectOffRender) {
                        reportedDirectOffRender = true;
                        Log.i(TAG, "output pipeline has a drawable recording surface");
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "output pipeline probe unavailable", error);
        }
    }

    private static TraceState traceState(Object processor) {
        boolean blocked = getBooleanField(processor, "j", false);
        Object a = getField(processor, "A");
        Object b = getField(processor, "B");
        Object c = getField(processor, "C");
        Object preview = getField(processor, "z");
        OutputTarget output = describeTarget(a);
        OutputTarget aux = describeTarget(b);
        OutputTarget extra = describeTarget(c);
        OutputTarget viewfinder = describeTarget(preview);
        String signature = "jit=" + blocked
                + " A=" + output.compact()
                + " B=" + aux.compact()
                + " C=" + extra.compact()
                + " z=" + viewfinder.compact()
                + " selectedLutIndex=" + selectedLutIndex
                + " customLut=" + customLutActive
                + " outputRendererFrameCount=" + outputFrameCount;
        return new TraceState(
                blocked,
                output.valid,
                getField(a, "d") != null ? getField(a, "d").getClass().getName() : "null",
                signature);
    }

    private static String targetName(Object processor, Object target) {
        if (target == null) return "unknown";
        if (target == getField(processor, "A")) return "A/video-output";
        if (target == getField(processor, "B")) return "B/aux-output";
        if (target == getField(processor, "C")) return "C/extra-output";
        if (target == getField(processor, "z")) return "z/viewfinder";
        return "unknown";
    }

    private static String describeOutputTarget(Object target) {
        return target == null ? "null" : describeTarget(target).compact();
    }

    private static String describeSurface(Surface surface) {
        return surface == null ? "null"
                : (surface.isValid() ? "valid/" : "invalid/") + surface;
    }

    private static OutputTarget describeTarget(Object target) {
        if (target == null) return new OutputTarget(false, 0, 0, false, "null", "null");
        Surface surface = asType(getFieldByType(target, Surface.class), Surface.class);
        EGLSurface eglSurface = asType(
                getFieldByType(target, EGLSurface.class), EGLSurface.class);
        Size size = asType(getFieldByType(target, Size.class), Size.class);
        Object renderer = getField(target, "d");
        String rendererName = renderer == null ? "null" : simpleName(renderer.getClass());
        return new OutputTarget(
                surface != null && surface.isValid(),
                size == null ? 0 : size.getWidth(),
                size == null ? 0 : size.getHeight(),
                eglSurface != null,
                rendererName,
                String.valueOf(getField(target, "a")));
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        try {
            java.lang.reflect.Field field = findField(target.getClass(), name);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * JADX adds numeric prefixes when an obfuscated field collides with another name. Try the
     * runtime field first and the decompiler alias second so diagnostics cannot silently report
     * a false default.
     */
    private static Object getFieldAlias(Object target, String... names) {
        for (String name : names) {
            Object value = getField(target, name);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean getBooleanField(Object target, String name, boolean fallback) {
        Object value = getField(target, name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int getIntField(Object target, String name, int fallback) {
        Object value = getField(target, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Object getFieldByType(Object target, Class<?> type) {
        if (target == null) return null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object getFieldByValue(Object target, Class<?> type) {
        if (target == null || type == null) return null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (type.isInstance(value)) return value;
                } catch (Throwable ignored) {
                    // Keep scanning: one inaccessible field must not hide another canvas field.
                }
            }
        }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep walking the superclass chain.
            }
        }
        return null;
    }

    private static <T> T asType(Object value, Class<T> type) {
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private static String simpleName(Class<?> type) {
        String name = type.getName();
        int split = name.lastIndexOf('.');
        return split < 0 ? name : name.substring(split + 1);
    }

    private static boolean calledFrom(String className, String methodName) {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (className.equals(element.getClassName())
                    && methodName.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static String callerTrace(int limit) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder result = new StringBuilder();
        int appended = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("de.robv.android.xposed")
                    || className.startsWith("java.lang.Thread")) {
                continue;
            }
            if (appended > 0) result.append(" <- ");
            result.append(className).append('#').append(element.getMethodName());
            if (++appended >= limit) break;
        }
        return result.toString();
    }

    private static final class TraceState {
        final boolean blocked;
        final boolean outputValid;
        final String renderer;
        final String signature;

        TraceState(boolean blocked, boolean outputValid, String renderer, String signature) {
            this.blocked = blocked;
            this.outputValid = outputValid;
            this.renderer = renderer;
            this.signature = signature;
        }
    }

    private static final class OutputTarget {
        final boolean valid;
        final int width;
        final int height;
        final boolean egl;
        final String renderer;
        final String surfaceId;

        OutputTarget(
                boolean valid, int width, int height, boolean egl,
                String renderer, String surfaceId) {
            this.valid = valid;
            this.width = width;
            this.height = height;
            this.egl = egl;
            this.renderer = renderer;
            this.surfaceId = surfaceId;
        }

        String compact() {
            return (valid ? "valid" : "none")
                    + (egl ? "+egl" : "")
                    + "@" + width + "x" + height
                    + "/" + renderer;
        }
    }

    /**
     * X8U normally selects recorder type 1, where the HAL feeds the encoder Surface directly and
     * the App's VideoOutputRenderer is left out of the recording path. Type 2 uses the App
     * MediaCodec path instead. Restrict the override to the exact case this feature owns:
     * Log recording, direct-output enabled, and an actual LUT selected. Setting the property to 0
     * restores the stock recorder choice without reinstalling the module.
     */
    private static void installDirectAppCodec(ClassLoader classLoader) {
        try {
            Class<?> keys = XposedHelpers.findClass(RECORDER_KEYS, classLoader);
            for (String field : new String[]{"b", "c", "V"}) {
                Object key = XposedHelpers.getStaticObjectField(keys, field);
                Log.i(TAG, "recorder selector key " + field + "="
                        + recorderKeyName(key));
            }
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(RECORDER_CONFIG, classLoader), "a",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled() || param.args.length != 1) return;
                            String key = recorderKeyName(param.args[0]);
                            if (key == null) return;
                            boolean relevant = "is_use_media_codec".equals(key)
                                    || "is_use_fast_video_recorder".equals(key)
                                    || "video_stream_media_codec".equals(key);
                            if (!relevant) return;

                            boolean wanted = wantsDirectAppCodec(
                                    classLoader, param.getResult());
                            Object stock = param.getResult();
                            if (wanted) {
                                if ("is_use_fast_video_recorder".equals(key)
                                        || "video_stream_media_codec".equals(key)) {
                                    param.setResult(Boolean.FALSE);
                                } else if ("is_use_media_codec".equals(key)) {
                                    param.setResult(Boolean.TRUE);
                                }
                                appCodecArmed = true;
                            }

                            String signature = sessionSerial + "|" + key
                                    + "|stock=" + displayValue(stock)
                                    + "|effective=" + displayValue(param.getResult())
                                    + "|wanted=" + wanted
                                    + "|armed=" + appCodecArmed;
                            if (!signature.equals(lastRecorderConfigProbe)) {
                                lastRecorderConfigProbe = signature;
                                Log.i(TAG, "recorder config read key=" + key
                                        + " stock=" + displayValue(stock)
                                        + " effective=" + displayValue(param.getResult())
                                        + " override=" + wanted
                                        + " selectedPosition=" + selectedLutPosition
                                        + " selectedLutIndex=" + selectedLutIndex
                                        + " armed=" + appCodecArmed);
                            }
                        }
                    });
            Log.i(TAG, "Log LUT App encoder override installed by runtime key inspection"
                    + " (property " + APP_CODEC_PROPERTY + ")");
        } catch (Throwable error) {
            Log.e(TAG, "Log LUT App encoder override unavailable", error);
        }

        try {
            Class<?> builder = XposedHelpers.findClass(RECORDER_CONFIG + "$a", classLoader);
            XposedBridge.hookAllMethods(builder, "b", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 2) return;
                    String key = recorderKeyName(param.args[0]);
                    if (!"is_use_media_codec".equals(key)
                            && !"is_use_fast_video_recorder".equals(key)
                            && !"video_stream_media_codec".equals(key)) {
                        return;
                    }
                    if (!wantsDirectAppCodec(classLoader, param.args[1])) return;
                    Object stock = param.args[1];
                    param.args[1] = "is_use_media_codec".equals(key)
                            ? Boolean.TRUE : Boolean.FALSE;
                    appCodecArmed = true;
                    Log.i(TAG, "recorder builder forced key=" + key
                            + " stock=" + displayValue(stock)
                            + " value=" + displayValue(param.args[1]));
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "recorder builder probe unavailable: " + error);
        }
    }

    private static String recorderKeyName(Object key) {
        if (key == null) return null;
        Object name = getField(key, "a");
        if (name == null) name = getField(key, "f40896a");
        if (name == null) name = getField(key, "name");
        if (name == null) name = getField(key, "key");
        return name == null ? null : String.valueOf(name);
    }

    private static boolean wantsDirectAppCodec(ClassLoader classLoader, Object stockValue) {
        boolean moduleEnabled = enabled();
        int appCodecProperty = property(APP_CODEC_PROPERTY, 1);
        if (moduleEnabled && proVideoModeActive && appCodecProperty != 0) {
            restorePersistedLutSelection(classLoader, "recorder config");
        }
        String format = logFormat(classLoader);
        int direct = currentDirectMode(classLoader);
        boolean lutSelected = selectedLutIndex == SDR_LUT_INDEX
                || selectedLutIndex == HDR_LUT_INDEX
                || selectedLutIndex == CUSTOM_LUT_INDEX;
        boolean wanted = moduleEnabled
                && proVideoModeActive
                && appCodecProperty != 0
                && "log".equals(format)
                && directOutputEnabled(direct)
                && lutSelected;
        String signature = sessionSerial
                + "|enabled=" + moduleEnabled
                + "|proVideoActive=" + proVideoModeActive
                + "|property=" + appCodecProperty
                + "|format=" + format
                + "|direct=" + direct
                + "|position=" + selectedLutPosition
                + "|index=" + selectedLutIndex
                + "|custom=" + customLutActive
                + "|name=" + selectedLutFileName
                + "|stock=" + displayValue(stockValue)
                + "|decision=" + wanted;
        if (!signature.equals(lastAppCodecProbe)) {
            lastAppCodecProbe = signature;
            Log.i(TAG, "recorder appcodec probe" + signature.substring(
                    signature.indexOf('|')));
        }
        return wanted;
    }

    /** Read the same direct-output preference used by rm.e.Vj(), without forcing the UI. */
    private static int currentDirectMode(ClassLoader classLoader) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("n9.e", classLoader), "X0");
            int mode = intValue(
                    XposedHelpers.callMethod(manager, "b", key, 0), -1);
            return mode >= 0 ? mode : lastDirectMode;
        } catch (Throwable error) {
            return lastDirectMode;
        }
    }

    /**
     * Log the concrete backend chosen after the recorder config is consumed. A successful surface
     * hand-off alone is not enough; the expected sequence is type 2, encoder callback, then output
     * renderer frames.
     */
    private static void observeVideoRecorder(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass(MODE_RECORDER, classLoader);
            XposedBridge.hookAllMethods(processor, "k", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    recorderCreationDepth.set(recorderCreationDepth.get() + 1);
                    boolean wanted = wantsDirectAppCodec(classLoader, null);
                    if (wanted) appCodecArmed = true;
                    Log.i(TAG, "recorder creation entered caller=" + MODE_RECORDER + ".k"
                            + " wantedAppCodec=" + wanted
                            + " armed=" + appCodecArmed
                            + " direct=" + currentDirectMode(classLoader)
                            + " format=" + logFormat(classLoader)
                            + " selectedPosition=" + selectedLutPosition
                            + " selectedLutIndex=" + selectedLutIndex);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int depth = recorderCreationDepth.get();
                    recorderCreationDepth.set(depth > 1 ? depth - 1 : 0);
                    if (!enabled()) return;
                    try {
                        Object recorder = getField(param.thisObject, "g");
                        Object type = getField(recorder, "v");
                        Log.i(TAG, "recorder creation finished actual=" + displayValue(type)
                                + " recorder=" + (recorder == null
                                        ? "null" : recorder.getClass().getName())
                                + " armed=" + appCodecArmed);
                    } catch (Throwable error) {
                        Log.i(TAG, "recorder creation finished (type unavailable) armed="
                                + appCodecArmed);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "recorder creation probe unavailable", error);
        }

        try {
            Class<?> recorder = XposedHelpers.findClass(VIDEO_RECORDER, classLoader);
            XposedBridge.hookAllConstructors(recorder, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1) return;
                    boolean wanted = wantsDirectAppCodec(classLoader, null);
                    /*
                     * The three recorder selector keys are not read again for every hot mode
                     * switch. In particular, 4K60 can reuse a recorder configuration assembled
                     * earlier, so appCodecArmed stays false even though this constructor is the
                     * exact om.e0.k() recorder that must render the LUT. The call stack is the
                     * authoritative ownership check; keep appCodecArmed as diagnostic evidence
                     * for whether the configuration keys were observed, not as a hard gate.
                     *
                     * A prior 4K120 recording already completed on type 2 with P0=true, a valid
                     * encoder Surface and output-renderer frames, so this does not add an
                     * unsupported backend. It makes high-frame-rate and hot-switched recordings
                     * consistently use the same App MediaCodec path accepted for 4K30.
                     */
                    boolean targetRecorder = wanted
                            && (recorderCreationDepth.get() > 0
                                    || calledFrom(MODE_RECORDER, "k"));
                    int requested = intValue(param.args[0], -1);
                    if (targetRecorder && requested != 2) {
                        param.args[0] = 2;
                        appCodecArmed = true;
                        appCodecTargetRecorder = true;
                    }
                    String signature = sessionSerial + "|requested=" + requested
                            + "|target=" + targetRecorder + "|wanted=" + wanted;
                    if (!signature.equals(lastRecorderConstruction)) {
                        lastRecorderConstruction = signature;
                        Log.i(TAG, "recorder constructor requested=" + requested
                                + " effective=" + intValue(param.args[0], -1)
                                + " target=" + targetRecorder
                                + " wanted=" + wanted
                                + " armed=" + appCodecArmed
                                + " caller=" + callerTrace(8));
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    try {
                        int actual = intValue(getField(param.thisObject, "v"), -1);
                        Log.i(TAG, "recorder selected actual=" + actual
                                + " direct=" + currentDirectMode(classLoader)
                                + " format=" + logFormat(classLoader)
                                + " selectedLutIndex=" + selectedLutIndex
                                + " armed=" + appCodecArmed);
                        if (actual == 2) {
                            boolean target = recorderCreationDepth.get() > 0
                                    || calledFrom(MODE_RECORDER, "k");
                            if (target) {
                                Object appCodec = getField(param.thisObject, "o");
                                targetAppCodec = new java.lang.ref.WeakReference<>(appCodec);
                                appCodecTargetRecorder = true;
                            }
                            Log.i(TAG, "recorder selected App MediaCodec actual=2"
                                    + " target=" + target
                                    + " appCodec=" + (targetAppCodec.get() == null
                                            ? "null"
                                            : targetAppCodec.get().getClass().getName()));
                        }
                    } catch (Throwable error) {
                        Log.i(TAG, "recorder selected (type unavailable)");
                    }
                }
            });
            /*
             * Type 2 receives frames that VideoOutputRenderer already rotated into a portrait
             * 2160x3840 encoder surface. The stock path still forwards the sensor's 90-degree
             * orientation hint, so players rotate those portrait pixels a second time. Keep the
             * native recorder's metadata untouched and suppress only the duplicate hint on this
             * explicitly selected App-MediaCodec path.
             */
            XposedBridge.hookAllMethods(recorder, "r", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    int recorderType = intValue(getField(param.thisObject, "v"), -1);
                    Object codec = getField(param.thisObject, "o");
                    Object targetCodec = targetAppCodec.get();
                    boolean targetMatched = targetCodec != null && targetCodec == codec;
                    boolean fallbackMatch = targetCodec == null
                            && appCodecTargetRecorder
                            && recorderType == 2;
                    if (recorderType != 2
                            || (!targetMatched && !fallbackMatch)
                            || !wantsDirectAppCodec(classLoader, null)) {
                        return;
                    }
                    int original = intValue(param.args[0], -1);
                    if (original != 0) {
                        param.args[0] = 0;
                    }
                    String signature = sessionSerial + "|original=" + original
                            + "|effective=" + intValue(param.args[0], -1)
                            + "|target=" + targetMatched
                            + "|fallback=" + fallbackMatch;
                    if (signature.equals(lastOrientationFix)) return;
                    lastOrientationFix = signature;
                    Log.i(TAG, "app encoder orientation hint original=" + original
                            + " effective=" + intValue(param.args[0], -1)
                            + " recorderType=" + recorderType
                            + " target=" + targetMatched
                            + " fallback=" + fallbackMatch);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "recorder selection probe unavailable", error);
        }
    }

    /**
     * Type 2 creates a MediaCodec input Surface and only hands it to the output renderer when
     * {@code P0} is true. Stock clears P0 for stream/video LivePhoto modes; this Log+LUT request
     * needs the opposite, so guard that invariant immediately before the encoder starts.
     */
    private static void observeRecorderCodec(ClassLoader classLoader) {
        try {
            Class<?> codec = XposedHelpers.findClass(APP_CODEC, classLoader);
            XposedBridge.hookAllMethods(codec, "s", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    Object callback = getField(param.thisObject, "l0");
                    boolean w0 = getBooleanField(param.thisObject, "W0", true);
                    boolean p0 = getBooleanField(param.thisObject, "P0", true);
                    Object targetCodec = targetAppCodec.get();
                    boolean targetMatched =
                            targetCodec != null && targetCodec == param.thisObject;
                    boolean fallbackMatch = targetCodec == null && appCodecTargetRecorder;
                    boolean protect = targetMatched || fallbackMatch;
                    if (protect && !p0) {
                        XposedHelpers.setBooleanField(param.thisObject, "P0", true);
                    }
                    boolean effectiveP0 = getBooleanField(param.thisObject, "P0", p0);
                    Surface encoderSurface = asType(
                            getField(param.thisObject, "e0"), Surface.class);
                    String signature = sessionSerial
                            + "|W0=" + w0
                            + "|P0=" + effectiveP0
                            + "|callback=" + (callback == null ? "null"
                                    : callback.getClass().getName())
                            + "|protect=" + protect;
                    if (!signature.equals(lastCodecStartup)) {
                        lastCodecStartup = signature;
                        Log.i(TAG, "app encoder startup W0=" + w0
                            + " P0=" + effectiveP0
                            + " originalP0=" + p0
                            + " callback=" + (callback == null
                                    ? "null" : callback.getClass().getName())
                            + " callbackPresent=" + (callback != null)
                            + " encoderSurface=" + describeSurface(encoderSurface)
                            + " targetRecorder=" + appCodecTargetRecorder
                            + " targetCodec=" + targetMatched
                            + " fallbackMatch=" + fallbackMatch
                            + " protected=" + protect
                            + " branch=" + (effectiveP0 ? "l0.c/renderer" : "M0/input"));
                    }
                    if (protect) {
                        appCodecArmed = false;
                        appCodecTargetRecorder = false;
                        targetAppCodec = new java.lang.ref.WeakReference<>(null);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    Surface encoderSurface = asType(
                            getField(param.thisObject, "e0"), Surface.class);
                    Log.i(TAG, "app encoder initialized W0="
                            + getBooleanField(param.thisObject, "W0", true)
                            + " P0=" + getBooleanField(param.thisObject, "P0", true)
                            + " encoderSurface=" + describeSurface(encoderSurface)
                            + " rendererCallback="
                            + (getField(param.thisObject, "l0") != null)
                            + " inputThread="
                            + (getField(param.thisObject, "M0") != null)
                            + " branch="
                            + (getBooleanField(param.thisObject, "P0", true)
                                    ? "l0.c/renderer" : "M0/input"));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "App encoder startup probe unavailable", error);
        }
    }

    /** Observe the moment the HAL gives the encoder Surface back to the App pipeline. */
    private static void observeEncoderSurface(ClassLoader classLoader) {
        try {
            Class<?> callback = XposedHelpers.findClass(ENCODER_SURFACE_CALLBACK, classLoader);
            XposedBridge.hookAllMethods(callback, "c", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 2) return;
                    Surface surface = asType(param.args[0], Surface.class);
                    Object size = param.args[1];
                    Log.i(TAG, "encoder callback surface="
                            + (surface != null && surface.isValid() ? "valid" : "invalid")
                            + " size=" + displayValue(size)
                            + " selectedLutIndex=" + selectedLutIndex
                            + " customLut=" + customLutActive);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "encoder surface probe unavailable", error);
        }
    }

    private static void reloadSelectedLut(Object menu, boolean restored) {
        if (!proVideoModeActive) return;
        try {
            Object manager = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(
                            LUT_MANAGER + "$b", menu.getClass().getClassLoader()),
                    "a");
            Object preview = previewCallback.get();
            if (preview != null) {
                requestLut(manager, preview, selectedLutIndex,
                        restored ? "restored preview" : "preview");
            } else {
                Log.i(TAG, "preview renderer not ready; LUT will load on surface creation");
            }

            Object output = outputRenderer.get();
            if (output != null) {
                XposedHelpers.setIntField(output, "e", selectedLutIndex);
                requestLut(
                        manager,
                        XposedHelpers.getObjectField(output, "f"),
                        selectedLutIndex,
                        restored ? "restored output" : "output");
            }
        } catch (Throwable error) {
            Log.e(TAG, "reload selected LUT failed", error);
        }
    }

    private static Object manager(Object renderer) {
        return XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass(
                        LUT_MANAGER + "$b", renderer.getClass().getClassLoader()),
                "a");
    }

    private static Object manager(ClassLoader classLoader) {
        return XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass(LUT_MANAGER + "$b", classLoader), "a");
    }

    private static int lutIndexForPosition(int position) {
        if (position == 1) return SDR_LUT_INDEX;
        if (position == 2) return HDR_LUT_INDEX;
        if (position >= 3) return CUSTOM_LUT_INDEX;
        return NO_LUT_INDEX;
    }

    private static void requestLut(
            Object manager, Object callback, int index, String renderer) {
        if (manager == null || callback == null) return;
        try {
            XposedHelpers.callMethod(manager, "j", index, callback);
            String path = lutPath(manager, index);
            Log.i(TAG, "requested LUT index " + index + " for " + renderer
                    + " path=" + path
                    + " bytes=" + lutFileSize(path));
        } catch (Throwable error) {
            Log.e(TAG, "LUT load request failed for " + renderer, error);
        }
    }

    private static String lutPath(Object manager, int index) {
        try {
            Object paths = XposedHelpers.getObjectField(manager, "S");
            return String.valueOf(XposedHelpers.callMethod(
                    paths, "get", index));
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static long lutFileSize(String path) {
        if (path == null || path.isEmpty() || "null".equals(path)) return -1;
        java.io.File file = new java.io.File(path);
        return file.isFile() ? file.length() : -1;
    }

    private static String resolveLutPath(
            Object menu, String title, String fileName, String itemPath) {
        try {
            Object context = XposedHelpers.callMethod(menu, "getContext");
            Object persistence = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            LUT_PERSISTENCE, menu.getClass().getClassLoader()),
                    "c", context);
            String vendorPath = (String) XposedHelpers.callMethod(
                    persistence, "b", fileName);
            if (vendorPath != null && !vendorPath.isEmpty()) return vendorPath;
        } catch (Throwable error) {
            Log.e(TAG, "LUT persistence lookup failed", error);
        }

        String matched = findLutFileName(title, fileName);
        if (matched != null) return LUT_DIRECTORY + matched;
        if (itemPath != null && !itemPath.isEmpty() && !"null".equals(itemPath)) return itemPath;
        return null;
    }

    /**
     * Observe the two values Y7 sends to the camera session. Keep the HAL-facing LUT name exactly
     * as the stock session builder wrote it: the native side owns file discovery and may append
     * {@code .cube} itself. The App preview uses the resolved full path separately.
     */
    private static void observeSessionParameters(ClassLoader classLoader) {
        try {
            Class<?> parameter = XposedHelpers.findClass(CAMERA_PARAMETER, classLoader);
            Object lutKey = XposedHelpers.getStaticObjectField(
                    parameter, "KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME");
            Object modeKey = XposedHelpers.getStaticObjectField(
                    parameter, "KEY_CONFIGURE_LOG_VIDEO_MODE");
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(SESSION_PARAMS, classLoader), "a",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!enabled() || param.args.length < 2) return;
                            if (sameParamKey(param.args[0], lutKey)) {
                                observeAndRepairLutName(param);
                            } else if (sameParamKey(param.args[0], modeKey)) {
                                observeLogVideoMode(param, classLoader);
                            }
                        }
                    });
        } catch (Throwable error) {
            Log.e(TAG, "HAL session parameter probe unavailable", error);
        }
    }

    private static void observeAndRepairLutName(XC_MethodHook.MethodHookParam param) {
        Object value = param.args[1];
        if (!(value instanceof byte[])) {
            if (reportedNameSession != sessionSerial) {
                reportedNameSession = sessionSerial;
                Log.i(TAG, sessionPrefix() + " custom LUT name has unexpected type "
                        + valueType(value) + " value=" + displayValue(value));
            }
            return;
        }

        byte[] received = (byte[]) value;
        String raw = new String(received, StandardCharsets.UTF_8);
        if (reportedNameSession != sessionSerial) {
            reportedNameSession = sessionSerial;
            reportedHalLutName = raw;
            Log.i(TAG, sessionPrefix()
                    + " custom LUT name type=byte[]"
                    + " raw=\"" + raw + "\""
                    + " sent=\"" + raw + "\""
                    + " rawBytes=" + valueLength(received)
                    + " sentBytes=" + valueLength(received)
                    + " selected=" + displayValue(selectedLutFileName));
        }
    }

    private static void observeLogVideoMode(
            XC_MethodHook.MethodHookParam param, ClassLoader classLoader) {
        Object value = param.args[1];
        int original = intValue(value, Integer.MIN_VALUE);
        int override = property(HAL_MODE_OVERRIDE_PROPERTY, -1);
        boolean applied = override >= 0 && value instanceof Integer;
        if (applied) param.args[1] = override;
        int sent = intValue(param.args[1], Integer.MIN_VALUE);
        if (reportedModeSession == sessionSerial) return;
        reportedModeSession = sessionSerial;
        Log.i(TAG, sessionPrefix()
                + " log video mode type=" + valueType(value)
                + " value=" + displayValue(value)
                + " int=" + original
                + " override=" + override
                + " applied=" + applied
                + " sent=" + sent
                + " direct=" + currentDirectMode(classLoader)
                + " selectedLutIndex=" + selectedLutIndex
                + " customLut=" + customLutActive);
    }

    private static boolean sameParamKey(Object actual, Object expected) {
        if (actual == expected) return true;
        if (actual == null || expected == null) return false;
        return actual.equals(expected) || actual.toString().equals(expected.toString());
    }

    private static String sessionPrefix() {
        return "HAL session=" + sessionSerial;
    }

    private static int valueLength(Object value) {
        return value instanceof byte[] ? ((byte[]) value).length : -1;
    }

    private static String valueType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String displayValue(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (Throwable ignored) {
                // Fall through to the explicit fallback.
            }
        }
        return fallback;
    }

    private static String lutPreference(ClassLoader classLoader, String field) {
        return lutPreference(classLoader, field, "");
    }

    private static String lutPreference(
            ClassLoader classLoader, String field, String fallback) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(DATA_KEYS, classLoader), field);
            Object value = XposedHelpers.callMethod(manager, "b", key, fallback);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static int lutPreferenceInt(
            ClassLoader classLoader, String field, int fallback) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(DATA_KEYS, classLoader), field);
            return intValue(
                    XposedHelpers.callMethod(manager, "b", key, fallback), fallback);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String fileName(String path, String title) {
        if (path != null && !path.isEmpty() && !"null".equals(path)) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.isEmpty()) return name;
        }
        return hasCubeSuffix(title) ? title : title + ".cube";
    }

    private static String findLutFileName(String title, String preferred) {
        java.io.File[] files = new java.io.File(LUT_DIRECTORY).listFiles();
        if (files == null) return null;
        String preferredBase = withoutCubeSuffix(preferred);
        String titleBase = withoutCubeSuffix(title);
        for (java.io.File file : files) {
            if (!file.isFile() || "lut_list.json".equals(file.getName())) continue;
            if (file.getName().equalsIgnoreCase(preferred)) return file.getName();
        }
        for (java.io.File file : files) {
            if (!file.isFile() || "lut_list.json".equals(file.getName())) continue;
            if (withoutCubeSuffix(file.getName()).equalsIgnoreCase(preferredBase)) {
                return file.getName();
            }
        }
        for (java.io.File file : files) {
            if (!file.isFile() || "lut_list.json".equals(file.getName())) continue;
            if (withoutCubeSuffix(file.getName()).equalsIgnoreCase(titleBase)) {
                return file.getName();
            }
        }
        return null;
    }

    private static boolean hasCubeSuffix(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".cube");
    }

    private static String withoutCubeSuffix(String name) {
        if (name == null) return "";
        return hasCubeSuffix(name) ? name.substring(0, name.length() - 5) : name;
    }

    private static int property(String name, int fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) type.getMethod("get", String.class).invoke(null, name);
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String logFormat(ClassLoader classLoader) {
        try {
            Object manager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.oplus.camera.data.DataManager", classLoader),
                    "getInstance");
            Object key = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("n9.e", classLoader), "K0");
            return String.valueOf(XposedHelpers.callMethod(manager, "b", key, "sdr"));
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static synchronized void installHalStrategy(ClassLoader classLoader) {
        if (strategyInstalled) return;
        try {
            Object singleton = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("o9.h$a", classLoader), "a");
            Object current = XposedHelpers.getObjectField(singleton, "c");
            // R8 removes the stateless strategy's constructor and the stock clinit allocates the
            // object directly. allocateInstance matches that shape and does not need a ctor.
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object hal = unsafeType.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, XposedHelpers.findClass("o9.f", classLoader));
            XposedHelpers.setObjectField(singleton, "c", hal);
            strategyInstalled = true;
            Log.i(TAG, "Log LUT decision strategy: "
                    + XposedHelpers.callMethod(current, "getName") + " -> "
                    + XposedHelpers.callMethod(hal, "getName"));
        } catch (Throwable error) {
            Log.e(TAG, "HAL Log LUT strategy replacement unavailable", error);
        }
    }

    /**
     * Watch the 3D LUT loader, which is where the recording side would have to pick the table up.
     *
     * <p>{@code Lut3dDataManager} keeps a path table filled in {@code d()} (index 9 =
     * {@code filters_lut/log_video_sdr_3dlut.cube}, 10 = the HDR one) and a name table that
     * {@code n(String)} writes index 15 into when the panel changes the selection.
     * {@code i(index, hdr)} turns an index into floats through
     * {@code nativeLoadBinaryLutFile(path, hdr)} and returns null when the path is missing.
     * Observing every index (not just 9 and 10) tells us which one the preview uses and which one,
     * if any, the encoder asks for.
     */
    private static void observeLutLoader(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(LUT_MANAGER, classLoader);
            XposedBridge.hookAllMethods(manager, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) return;
                    int index = (Integer) param.args[0];
                    if (!loadedIndexes.add(index)) return;
                    Object table = param.getResult();
                    int size = (table instanceof float[]) ? ((float[]) table).length : -1;
                    Log.i(TAG, "lut table " + index + " loaded: "
                            + (size < 0 ? "null (no file)" : size + " floats"));
                }
            });
            XposedBridge.hookAllMethods(manager, "n", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1) return;
                    Log.i(TAG, "lut selection set to \"" + param.args[0] + "\"");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "lut loader unavailable", error);
        }
    }

    /**
     * Answer the custom-LUT slot the way the X9U does, and say who asked. The class name in the log
     * is the whole point of the exercise: it tells us whether this is a feature decision or only the
     * analytics builder.
     */
    private static void answerGetSupportFunction(ClassLoader classLoader, String className) {
        try {
            Class<?> type = XposedHelpers.findClass(className, classLoader);
            XposedBridge.hookAllMethods(type, "getSupportFunction", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) return;
                    if (param.args.length != 1 || !(param.args[0] instanceof String)) return;
                    String key = (String) param.args[0];
                    if (FUNC_PREVIEW_RENDER_LUT.equals(key)) {
                        boolean stock = Boolean.TRUE.equals(param.getResult());
                        int desired = requestedPreviewLutIndex(classLoader, null);
                        if (desired != NO_LUT_INDEX && !stock) {
                            param.setResult(Boolean.TRUE);
                        }
                        if (desired != NO_LUT_INDEX) {
                            logPreviewLutContract(
                                    "preview support called by " + className
                                            + " stock=" + stock
                                            + " effective=" + param.getResult(),
                                    classLoader);
                        }
                        return;
                    }
                    if (!FUNC_CUSTOM_LUT.equals(key)) return;
                    boolean stock = Boolean.TRUE.equals(param.getResult());
                    if (!stock) param.setResult(Boolean.TRUE);
                    queries++;
                    if (!reportedQuery) {
                        reportedQuery = true;
                        Log.i(TAG, "custom lut feature queried by " + className
                                + " (stock " + stock + "), answering true");
                    } else if (queries % 20 == 0) {
                        Log.i(TAG, "custom lut feature queried " + queries + " times so far");
                    }
                }
            });
        } catch (Throwable error) {
            // Not every build ships every module; a miss here is not fatal.
            Log.i(TAG, "no getSupportFunction on " + className + ": " + error);
        }
    }

    /**
     * Answer the configuration row the slot reads, and report what stock said first.
     *
     * <p>Same shape as {@code HighPixelSupport}: this platform's {@code CameraConfig} caches every
     * answered boolean and its vendor-tag fallback is dead, so a row that is missing from
     * {@code /odm/etc/camera/config/oplus_camera_config} can only be turned on at
     * {@code CameraConfig} itself. Both entry points are covered because the photo path asks
     * {@code getConfigBooleanValue} while the feature table asks {@code d(key, false, false)}.
     */
    private static void observeConfig(ClassLoader classLoader) {
        try {
            Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof String)) return;
                    if (!CFG_HDR_LUT.equals(param.args[0])) return;
                    boolean stock = Boolean.TRUE.equals(param.getResult());
                    if (!stock) param.setResult(Boolean.TRUE);
                    if (!reportedConfig) {
                        reportedConfig = true;
                        Log.i(TAG, "config " + CFG_HDR_LUT + " answered true (stock " + stock + ")");
                    }
                }
            };
            XposedBridge.hookAllMethods(config, "d", hook);
            XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hook);
        } catch (Throwable error) {
            Log.e(TAG, "camera config unavailable", error);
        }
    }
}
