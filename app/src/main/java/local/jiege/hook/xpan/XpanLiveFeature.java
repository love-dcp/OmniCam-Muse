package local.jiege.hook.xpan;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.graphics.Rect;
import android.media.MediaFormat;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.SuperCall;
import local.jiege.hook.gr.GrPreviewStreamRestore;

/**
 * Restores the camera's original live-photo presenter in XPAN mode.
 *
 * <p>The XPAN feature table has no {@code live_photo} entry, so FeatureManager never creates
 * LivePhotoPresenter and the mode loses both its live-photo button and capture integration. Add
 * the missing feature to XPAN's small table, then allow its unmodified presenter to handle the
 * XPAN preview request stage.
 */
public final class XpanLiveFeature {
    private static final String TAG = "XpanLiveFeature";
    private static final String XPAN_FEATURE = "com.oplus.camera.feature.xpan";
    private static final String LIVE_PHOTO_FEATURE = "com.oplus.camera.feature.live_photo";
    private static final String LIVE_PHOTO_SUPPORT = "pref_live_photo_key";
    private static final String LIVE_PHOTO_PRESENTER = "gh.e2";
    private static final String CAMERA_IMPLEMENTATION = "bb.c1";
    private static final String IMAGE_DATA = "com.oplus.camera.feature.livephoto.data.ImageData$a";
    private static final String VIDEO_IMAGE_DATA =
        "com.oplus.camera.feature.livephoto.data.ImageData";
    private static final String VIDEO_EFFECT_PIPELINE = "gh.p3";
    private static final String GL_RENDERER = "com.oplus.camera.common.gl.a0";
    private static final String GL_SHADER = "com.oplus.camera.common.gl.y0";
    private static final String RAW_3D_TEXTURE = "com.oplus.camera.common.gl.t0";
    private static final String LIVE_WARP_RENDERER = "ch.g";
    private static final String LIVE_VIDEO_ENCODER = "eh.c0";
    private static final String EGL_HELPER = "com.oplus.camera.common.gl.j";
    private static final String EIS_PROCESSOR = "gh.g";
    private static final String EIS_WRAPPER = "gh.v";
    private static final String EIS_GRID_DATA = "bh.j";
    private static final String EIS_METADATA_QUEUE_FIELD = "z";
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";
    private static final String LUT_MANAGER_HOLDER = LUT_MANAGER + "$b";
    private static final String CAMERA_METADATA_KEY = "bb.s";
    private static final String LIVE_PHOTO_FEATURE_FLAGS = "fh.a";
    /** The encoder's watermark compositor (WatermarkProcessor) and the renderer it owns. */
    private static final String WATERMARK_PROCESSOR = "gh.s4";
    private static final String WATERMARK_PROCESSOR_RENDERER = "j";
    /** The GL renderer the watermark processor composites the frame and the bitmap with. */
    private static final String WATERMARK_COMPOSITE = "ch.h";
    private static final String LUT_PARAMETERS = "bh.o";
    private static final String XPAN_LIVE_WATERMARK_PROPERTY = "persist.omnicam.xpan.livewm";
    /**
     * Opt-in for the SDR output relabel. Stock renders the XPAN live attachment HLG end to end
     * (f2/mbPreviewHdr answers true on this build), and the r211-era verified-good files were
     * BT.2020+HLG in both pixels and container labels. Relabeling only the MediaFormat keys to
     * BT.709/SDR leaves HLG pixels under an SDR label, so every player gamma-decodes them and
     * the clip plays back washed-out grey (measured S=21 against the cover's S=95, the same
     * frame regains its colours when HLG-decoded). Default 0 keeps the stock HLG output;
     * set 1 to restore the old relabel behaviour.
     */
    private static final String XPAN_LIVE_SDR_OUTPUT_PROPERTY =
        "persist.omnicam.xpan.sdrout";
    private static final String XPAN_EIS_BYPASS_PROPERTY =
        "persist.omnicam.xpan.eisbypass";
    private static final String XPAN_EIS_GRID_PROPERTY =
        "persist.omnicam.xpan.eisgrid";
    /**
     * r435 A/B proof: healthy XPAN live photos are produced entirely by the native path —
     * every capture-encoder feed carries ImageData handle 0x0 and the ch.e YUV converter
     * never runs. gh.e2.Z2 only runs after a GR/master -> XPAN switch; it wires every
     * preview frame into a fresh ch.e converter whose FBO colour attachment is invalid
     * (0x8cd5 GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT), so every frame it paints encodes as
     * a solid green clip. Default 1 keeps ImageData.l at 0 by skipping ch.e.k/l/b inside
     * XPAN, restoring the exact native path direct XPAN uses. Set 0 to restore wiring.
     */
    private static final String XPAN_NO_CONV_PROPERTY = "persist.omnicam.xpan.noconv";
    /**
     * r437 proof: skipping Z2 outright starves the capture encoder (no video track at all)
     * because Z2 is the ONLY frame producer on the 2D-texture branch — X2/b3 are the
     * else-branch and never run. r435 proof: Z2's frames can never carry content because
     * the event source texture is a dead shell after GR/master -> XPAN (bind texture=0,
     * err=1282 for every frame of the whole session) — the GR filter chain is torn down
     * on the switch and its output texture is deleted, yet preview events still dispatch
     * through it. Fix (r438): redirect the frame to the hardware-buffer producers X2
     * (HDR path) and b3 (plain YUV path) — both consume event.e.b() directly, never
     * touch the dead texture, and are exactly what direct XPAN (which works) uses.
     * Default 1 = redirect. Set 0 to restore stock Z2 conversion.
     */
    private static final String XPAN_SKIP_Z2_PROPERTY = "persist.omnicam.xpan.skipz2";
    private static final String FRAME_SINK = "gh.i2";
    private static final String YUV_CONVERSION_CLASS = "ch.e";
    private static final String XPAN_GYRO_KEY =
        "com.oplus.camera.gyro.value.gyrotoeis";
    private static final String BASE_MODE_NAME_FIELD = "U0";
    private static final String MENU_CONTAINER_BASE =
        "com.oplus.camera.ui.newsetting.container.r";
    private static final String MENU_CONTAINER_TOP_LEFT =
        "com.oplus.camera.ui.newsetting.container.CameraMenuContainerTopLeft";
    private static final String MENU_OPTION_BASE = "jq.h";
    private static final String MENU_OPTION_TOP = "dq.j";
    private static final String CAMERA_SETTING_UI_TOP = "cq.l";
    private static final String XPAN_EXPOSURE_WHEEL =
        "com.oplus.camera.feature.xpan.view.widget.XPanExposureWheel";
    private static final String LIVE_PHOTO_VIEW_ID = "live_photo";
    /**
     * The two stock menu strips that the exposure wheel is drawn over. Their resource ids are the
     * only stable way to find them: the items inside are added and removed per mode.
     */
    private static final String[] TOP_BAR_VIEW_IDS = {
        "oplus_setting_bar_left", "oplus_setting_bar_right"
    };
    private static final String MENU_CAMERA_FIELD = "d";
    private static final String MENU_LIST_FIELD = "h";
    private static final String SHOWN_MENU_LIST_FIELD = "i";
    private static final String MENU_CACHE_FIELD = "j";
    private static final String MENU_ORDER_FIELD = "n";
    private static final String MENU_VALUE_FIELD = "o";
    private static final String OPTION_CAMERA_FIELD = "w";
    private static final String PREVIEW_STAGE =
        "com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlRequest"
            + ".EventMessagePreviewRequestStage";
    private static final String CAPTURE_REQUEST_STAGE =
        "com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlRequest"
            + ".EventMessageCaptureRequestStage";
    private static final String PRESENTER_MODE_METHOD = "i";
    private static final String PRESENTER_MODE_FIELD = "f36506q";
    private static final String XPAN_MODE = "xpan";
    private static final String XPAN_UNIT_MODE = "xpan_mode";
    private static final String PHOTO_UNIT_MODE = "photo_mode";
    private static final String XPAN_LIVE_PHOTO_KEY = "pref_live_photo_key_jiege_xpan";
    private static final String MIGRATION_KEY =
        "pref_live_photo_key_jiege_xpan_native_enabled";
    private static final String MIGRATION_VERSION = "r187";
    /**
     * The XPAN HAL path loses about two stops when the native live-photo request is enabled.
     * Existing Log-LUT exposure compensation uses 330 units per EV on this camera.
     */
    private static final String XPAN_LIVE_EV_PROPERTY =
        "persist.omnicam.xpan.liveev";
    private static final int DEFAULT_XPAN_LIVE_EV_UNITS = 660;
    private static final int MAX_AE_EV_UNITS = 990;
    /**
     * The stock XPAN live clip is tagged BT.2020 / HLG even though the XPAN preview shown to the
     * user is SDR. Gallery decodes those SDR pixel values through the HLG transfer curve, so the
     * moving picture is slightly brighter than the still cover. Keep these legacy DataSpace
     * values explicit because the camera uses the platform-specific packed representation.
     */
    private static final int DATASPACE_BT2020_HLG_LIMITED = 0x12060000;
    private static final int DATASPACE_BT709_LIMITED = 0x10C10000;
    private static final int XPAN_LIVE_WIDTH = 2484;
    private static final int XPAN_LIVE_HEIGHT = 952;
    private static final ThreadLocal<Boolean> XPAN_RESUME_OVERRIDE =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> LIVE_STATE_SYNC =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> EIS_GRID_INIT_ALLOW =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<XpanMainFrame> XPAN_MAIN_FRAME = new ThreadLocal<>();
    private static final ThreadLocal<String> XPAN_RENDER_STAGE = new ThreadLocal<>();
    private static final Set<String> LIVE_EIS_ROUTE_PROBES = new LinkedHashSet<>();
    private static volatile String currentCameraMode = "";
    /**
     * Wall clock of the last XPAN live main-frame pass. While the attachment records, frames
     * arrive roughly every 33 ms, so a recent timestamp means the video is being rendered right
     * now and the shared CombineLut env must not be touched (switching it mid-recording freezes
     * the stream -- the all-green frozen attachment of r421/r422). Exposed for
     * {@code GrPreviewStreamRestore} so its env wash can stay out of the recording window.
     */
    private static volatile long lastMainFrameAt;
    /** True when an XPAN live attachment is rendering frames, i.e. the env is in active use. */
    public static boolean isXpanLiveFrameFlowing() {
        return System.currentTimeMillis() - lastMainFrameAt < 500L;
    }
    private static volatile String exposureFixLogSignature = "";
    private static volatile Object livePhotoSharedDataKey;
    private static volatile String liveStateSyncLogSignature = "";
    private static volatile boolean stateMigrationChecked;
    private static volatile int xpanConversionSkips;
    private static volatile int xpanZ2Skips;
    private static volatile int xpanFrameSinks;
    private static volatile int xpanY1Calls;
    private static volatile WeakReference<Object> xpanLivePresenterRef =
        new WeakReference<>(null);
    /** True between our capture-start stamp and the capture-end (wrapper 104) reset. */
    private static volatile boolean xpanLiveCaptureActive;
    private static volatile boolean previewStageLogged;
    private static volatile boolean captureRequestLivePhotoLogged;
    private static volatile boolean captureStartedLivePhotoLogged;
    private static volatile boolean supportProbeLogged;
    private static volatile boolean eligibilityProbeLogged;
    private static volatile boolean supportOverrideLogged;
    private static volatile boolean eligibilityOverrideLogged;
    private static volatile boolean resumeOverrideLogged;
    private static volatile boolean modeSupportLogged;
    private static volatile boolean menuInjectionLogged;
    private static volatile boolean menuMissingLogged;
    private static volatile boolean menuCreateRequestedLogged;
    private static volatile boolean menuCreatedLogged;
    private static volatile boolean menuCreateFailedLogged;
    private static volatile boolean menuCacheAdoptedLogged;
    private static volatile boolean menuRestoreLogged;
    private static volatile boolean menuVisibilityBlockedLogged;
    private static volatile View exposureWheelGestureTarget;
    private static volatile boolean exposureWheelGestureConsumed;
    private static volatile boolean gyroFallbackLogged;
    private static volatile boolean eisBypassLogged;
    private static volatile boolean eisMetadataQueueRepairLogged;
    private static volatile boolean eisMetadataQueueRepairErrorLogged;
    private static volatile boolean xpanEisBypassActive;
    private static volatile boolean videoCropFallbackLogged;
    private static volatile boolean basicToneParametersLogged;
    private static volatile int basicToneTextureUploadLogs;
    private static volatile boolean basicToneTextureUnavailableLogged;
    private static volatile boolean basicToneBindingLogged;
    private static volatile boolean basicToneBindingErrorLogged;
    private static volatile boolean basicToneDirectProbeLogged;
    private static volatile boolean basicToneDirectShaderLogged;
    private static volatile boolean basicToneDirectFrameLogged;
    private static volatile boolean commonBasicToneBridgeLogged;

    /**
     * Value of {@link GrPreviewStreamRestore#paletteWashes} when the current renderer.s texture
     * was built. The XPAN BasicTone GL texture holds a snapshot of {@code Lut3dDataManager.c};
     * once a re-arm washes that array back to the palette table, any texture built earlier keeps
     * grading frames with the stale initV2/GR table (measured 2026-11-18: Log-grey direct XPAN,
     * green after GR). Watching the wash counter lets the ensure-path rebuild on demand.
     */
    /**
     * r446: per-renderer BasicTone upload tracking. There are TWO warp-renderer instances in a
     * session -- the preview attachment renderer (a0.i common draw) and the video pipeline
     * renderer (gh.p3.w) -- and the r421/r424 staleness checks used a single global wash
     * generation. Measured r445: the video-side ensure consumed the wash generation first, so
     * the preview renderer's stock-filled stale texture (common/master palette, Log-grey on
     * XPAN frames) was never rebuilt -- grey preview after photo->master->XPAN while captures
     * stayed good. Keyed by renderer identity; entries are tiny and per-session.
     */
    private static final java.util.concurrent.ConcurrentHashMap<
        Integer, Object[]> uploadsByRenderer = new java.util.concurrent.ConcurrentHashMap<>();
    /** Object[]{Long washGeneration, Object uploadedTexture} for that renderer instance. */

    /**
     * r445: failure latch for the gate-on stale rebuild. Key = wash generation combined with the
     * current texture identity; when an attempt fails (e.g. no table data yet) the same key is not
     * retried, so a persistent failure cannot turn into a per-frame GL upload storm.
     */
    private static volatile long staleRebuildFailedKey = Long.MIN_VALUE;
    /** r445: per-frame heartbeat counter on the XPAN warp draw entry (diagnostics only). */
    private static volatile int warpDrawProbeCalls;
    /**
     * r447 diagnostics: uniform enable/disable (u0.m) call counter, reset when a new XPAN
     * session opens. Measured r446: the a0.i heartbeat fired only for capture frames, so a
     * grey second-session preview produced no probe data at all. This census watches who
     * raises uNeedBasicTone during plain preview.
     */
    private static final java.util.concurrent.atomic.AtomicLong uniformProbeCalls =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong interestingUniformCalls =
        new java.util.concurrent.atomic.AtomicLong();
    /** r447 diagnostics: per-entry draw census, keyed "class.method#args". */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> drawCensus =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** r424: published for the pre-seed called from GrMasterLiveLutDiagnostic's task hook. */
    private static volatile Class<?> installedGlRenderer;
    private static volatile Class<?> installedManagerHolder;
    private static volatile boolean watermarkCopyLogged;
    private static volatile boolean watermarkCopyErrorLogged;
    private static volatile boolean watermarkCompositeLogged;
    private static volatile boolean watermarkCompositeSkippedLogged;
    private static volatile boolean watermarkCompositeStaleTextureLogged;
    private static volatile int watermarkCompositeProbe;
    /** LutParameters of the frame currently being drawn, reused by the watermark composite. */
    private static volatile Object activeLutParameters;
    /** Pipeline of the frame currently being drawn; its renderer holds the BasicTone texture. */
    private static volatile Object activePipeline;
    /** Set while the stock watermark processor re-composites the frame on the GL thread. */
    private static final ThreadLocal<Boolean> WATERMARK_COMPOSITE_ACTIVE = new ThreadLocal<>();
    private static volatile boolean liveTransformBypassLogged;
    private static volatile boolean liveTransformCaptureProbeLogged;
    private static volatile boolean liveTransformDrawProbeLogged;
    private static volatile boolean liveTransformUploadLogged;
    private static volatile boolean ordinaryLutBindLogged;
    private static volatile boolean basicToneBindLogged;
    private static volatile boolean selectedLutCallbackLogged;
    private static volatile boolean transformCaptureCallLogged;
    private static volatile boolean mainFrameDiagnosticErrorLogged;
    private static volatile boolean commonDrawSignatureLogged;
    private static volatile boolean warpDrawSignatureLogged;
    private static volatile int lastSelectedLutSize = -1;
    private static volatile int lastSelectedLutHash;
    private static volatile int mainFrameSequence;
    private static volatile String lastLivePhotoMenuState = "";
    private static volatile boolean xpanLiveSdrArmed;
    private static volatile boolean xpanLiveSdrFormatLogged;
    private static volatile boolean xpanLiveSdrSurfaceLogged;

    /**
     * Per-frame diagnostic context. It is intentionally recording-only: all values are captured
     * before the existing BasicTone workaround changes lutType or ImageData.i.
     */
    private static final class XpanMainFrame {
        final int sequence;
        final long pictureId;
        final long timestamp;
        final boolean directGridNull;
        final boolean fallbackGridNull;
        final boolean transformNull;
        final boolean watermarkOpen;
        final boolean watermarkStatePresent;
        final int cropWidth;
        final int cropHeight;
        final int pipelineWidth;
        final int pipelineHeight;
        final int lutType;
        final boolean rendererPrepared;
        final boolean rendererHdr;
        final boolean rendererLut;
        final boolean rendererRNull;
        final boolean rendererSNull;
        final boolean eisBypassOn;
        final boolean eisEnabledOn;
        volatile int drawTargetId;
        int commonDraws;
        int warpDraws;
        int subVideoCalls;
        int commonBasicToneBinds;
        int warpBasicToneBinds;
        int subVideoBasicToneBinds;
        int commonOrdinaryLutBinds;
        int warpOrdinaryLutBinds;
        int subVideoOrdinaryLutBinds;
        int commonTransformUploads;
        int warpTransformUploads;
        int subVideoTransformUploads;
        boolean basicToneBindFrameLogged;
        boolean ordinaryLutBindFrameLogged;
        boolean transformUploadFrameLogged;

        XpanMainFrame(
            int sequence,
            long pictureId,
            long timestamp,
            boolean directGridNull,
            boolean fallbackGridNull,
            boolean transformNull,
            boolean watermarkOpen,
            boolean watermarkStatePresent,
            int cropWidth,
            int cropHeight,
            int pipelineWidth,
            int pipelineHeight,
            int lutType,
            boolean rendererPrepared,
            boolean rendererHdr,
            boolean rendererLut,
            boolean rendererRNull,
            boolean rendererSNull,
            boolean eisBypassOn,
            boolean eisEnabledOn
        ) {
            this.sequence = sequence;
            this.pictureId = pictureId;
            this.timestamp = timestamp;
            this.directGridNull = directGridNull;
            this.fallbackGridNull = fallbackGridNull;
            this.transformNull = transformNull;
            this.watermarkOpen = watermarkOpen;
            this.watermarkStatePresent = watermarkStatePresent;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.pipelineWidth = pipelineWidth;
            this.pipelineHeight = pipelineHeight;
            this.lutType = lutType;
            this.rendererPrepared = rendererPrepared;
            this.rendererHdr = rendererHdr;
            this.rendererLut = rendererLut;
            this.rendererRNull = rendererRNull;
            this.rendererSNull = rendererSNull;
            this.eisBypassOn = eisBypassOn;
            this.eisEnabledOn = eisEnabledOn;
        }
    }

    private XpanLiveFeature() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "install", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        hookFeatureList(classLoader);
        hookXpanSupport(classLoader);
        hookXpanModeSupport();
        hookPreviewStage(classLoader);
        try {
            hookCaptureRequestStage(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN capture request live photo hook failed", error);
        }
        try {
            hookExposureCompensationFix(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo exposure fix unavailable", error);
        }
        try {
            hookXpanEisBypass(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live EIS bypass unavailable", error);
        }
        try {
            hookXpanEisGridRouteDiagnostics(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live EIS grid route diagnostics unavailable", error);
        }
        try {
            hookXpanSessionLifecycleProbes(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN session lifecycle probes unavailable", error);
        }
        try {
            hookXpanGyroFallback(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN synthetic gyro fallback unavailable", error);
        }
        try {
            hookXpanVideoCropFallback(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN video crop fallback unavailable", error);
        }
        try {
            hookXpanLiveBasicTone(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live BasicTone binding unavailable", error);
        }
        try {
            hookXpanDrawCensus(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN draw census unavailable", error);
        }
        try {
            hookXpanLiveWatermarkCopy(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live watermark copy unavailable", error);
        }
        try {
            hookXpanLiveLutDiagnostics(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live LUT diagnostics unavailable", error);
        }
        if (propertyStr("persist.omnicam.xpan.filterprobe", "").equals("1")) {
            try {
                hookXpanFilterProbe(classLoader);
            } catch (Throwable error) {
                Log.e(TAG, "XPAN filter probe unavailable", error);
            }
        } else {
            /*
             * r456: the r453 filter probe hooks are opt-in now. The grey-preview rounds
             * started exactly when this probe shipped (r449 = no grey across heavy switch
             * testing, r453 = grey on direct XPAN entry), so the probe itself is a suspect;
             * default it off until the bright-preview regression is closed. Re-enable with
             * `setprop persist.omnicam.xpan.filterprobe 1` + force-stop when chasing the
             * XPAN filter-to-video task.
             */
            Log.i(TAG, "XPAN filter probe left off (persist.omnicam.xpan.filterprobe!=1)");
        }
        try {
            hookXpanLiveSdrOutput(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live SDR output correction unavailable", error);
        }
        try {
            hookMenuVisibility(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN menu visibility hook failed", error);
        }
        try {
            hookExposureWheelTouchYield(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN exposure-wheel touch yield unavailable", error);
        }
        try {
            hookXpanNativeConversion(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN native conversion hook unavailable", error);
        }
        Log.i(TAG, "installed");
    }

    private static void hookFeatureList(ClassLoader classLoader) {
        Class<?> adapter = XposedHelpers.findClass(
            "com.oplus.ocs.camera.config.FeatureConfigureAdapter", classLoader);
        XposedBridge.hookAllMethods(adapter, "getFeatureInfoList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object value = param.getResult();
                if (!(value instanceof Set)) return;
                Set<?> features = (Set<?>) value;
                if (!isXpanFeatureList(features)) return;

                LinkedHashSet<String> restored = new LinkedHashSet<>();
                for (Object feature : features) {
                    if (feature instanceof String) restored.add((String) feature);
                }
                restored.add(LIVE_PHOTO_FEATURE);
                param.setResult(restored);
                Log.i(TAG, "XPAN live_photo added to feature list (" + features.size() + " -> "
                    + restored.size() + "), entry="
                    + (param.args.length > 0 ? param.args[0] : "?"));
            }
        });
    }

    private static void hookXpanModeSupport() {
        final Class<?> baseMode;
        try {
            baseMode = Symbols.get().cls("BaseMode");
        } catch (Throwable error) {
            Log.e(TAG, "XPAN mode support hook unavailable", error);
            return;
        }
        XposedBridge.hookAllMethods(baseMode, "getSupportFunction", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()
                    || param.args.length != 1
                    || !LIVE_PHOTO_SUPPORT.equals(param.args[0])
                    || Boolean.TRUE.equals(param.getResult())
                    || !isXpanMode(param.thisObject)) {
                    return;
                }
                param.setResult(Boolean.TRUE);
                if (!modeSupportLogged) {
                    modeSupportLogged = true;
                    Log.i(TAG, "XPAN mode answers " + LIVE_PHOTO_SUPPORT + " = true");
                }
            }
        });
    }

    /**
     * Stock checks live-photo support before it honors the preview-stage compatibility path.
     * Restrict the override to an XPAN presenter so the other camera modes keep their original
     * support and conflict checks.
     */
    private static void hookXpanSupport(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        XposedBridge.hookAllMethods(presenter, "P2", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!supportProbeLogged) {
                    supportProbeLogged = true;
                    Log.i(TAG, "live photo support probe, mode="
                        + presenterMode(param.thisObject) + ", result=" + param.getResult());
                }
                if (param.hasThrowable()
                    || !(param.getResult() instanceof Boolean)
                    || (Boolean) param.getResult()
                    || !isXpanPresenter(param.thisObject)) {
                    return;
                }
                param.setResult(Boolean.TRUE);
                /* r442: per-event visibility — stock may re-query between captures. */
                Log.i(TAG, "XPAN live photo support gate opened, mode="
                    + presenterMode(param.thisObject));
            }
        });

        XposedBridge.hookAllMethods(presenter, "G2", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!eligibilityProbeLogged) {
                    eligibilityProbeLogged = true;
                    Log.i(TAG, "live photo eligibility probe, mode="
                        + presenterMode(param.thisObject) + ", result=" + param.getResult());
                }
                if (param.hasThrowable()
                    || param.args.length != 1
                    || param.args[0] != null
                    || !(param.getResult() instanceof Boolean)
                    || (Boolean) param.getResult()
                    || !isXpanPresenter(param.thisObject)) {
                    return;
                }
                param.setResult(Boolean.TRUE);
                /* r442: per-event visibility — stock may re-query between captures. */
                Log.i(TAG, "XPAN live photo eligibility gate opened, mode="
                    + presenterMode(param.thisObject)
                    + ", arg=" + param.args[0]);
            }
        });

        XposedBridge.hookAllMethods(presenter, "H3", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(XPAN_RESUME_OVERRIDE.get())
                    && isXpanPresenter(param.thisObject)) {
                    param.setResult(null);
                }
            }
        });

        XposedBridge.hookAllMethods(presenter, "G3", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(XPAN_RESUME_OVERRIDE.get())
                    && isXpanPresenter(param.thisObject)) {
                    param.setResult(null);
                }
            }
        });

        Method onResume = XposedHelpers.findMethodExact(
            presenter, "f2", boolean.class);
        XposedBridge.hookMethod(onResume, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isXpanPresenter(param.thisObject)) {
                    migrateXpanLivePhotoState(classLoader, param.thisObject);
                    XPAN_RESUME_OVERRIDE.set(Boolean.TRUE);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(XPAN_RESUME_OVERRIDE.get())) return;
                XPAN_RESUME_OVERRIDE.set(Boolean.FALSE);
                if (!isLivePhotoStoredOn(param.thisObject)) return;
                try {
                    XposedHelpers.callMethod(param.thisObject, "G3", 0);
                    if (!resumeOverrideLogged) {
                        resumeOverrideLogged = true;
                        Log.i(TAG, "XPAN resume live photo close suppressed");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN resume live photo restore failed", error);
                }
            }
        });
    }

    /**
     * Older module releases left the shared live-photo value off while XPAN also had its own saved
     * value. Login state therefore closed the newly restored presenter at resume. Restore both
     * values once, before the stock onResume method reads the shared value, and record a marker only
     * after both writes have been read back. Once migrated, an "off" chosen by the user remains off.
     */
    private static void migrateXpanLivePhotoState(
        ClassLoader classLoader, Object presenter) {
        if (stateMigrationChecked || !isXpanPresenter(presenter)) return;
        synchronized (XpanLiveFeature.class) {
            if (stateMigrationChecked) return;
            try {
                Symbols symbols = Symbols.get();
                Object markerKey = prefsKey(symbols, MIGRATION_KEY);
                Object marker =
                    DataStore.get(symbols, classLoader, markerKey, "");
                if (MIGRATION_VERSION.equals(marker)) {
                    stateMigrationChecked = true;
                    return;
                }

                Object xpanKey = prefsKey(symbols, XPAN_LIVE_PHOTO_KEY);
                Object sharedKey = livePhotoSharedKey(symbols);
                putSync(symbols, classLoader, xpanKey, "on");
                putSync(symbols, classLoader, sharedKey, "on");

                Object xpanState = DataStore.get(symbols, classLoader, xpanKey, "");
                Object sharedState = DataStore.get(symbols, classLoader, sharedKey, "");
                if (!"on".equals(xpanState) || !"on".equals(sharedState)) {
                    throw new IllegalStateException(
                        "state verification failed, xpan=" + xpanState
                            + ", shared=" + sharedState);
                }

                putSync(symbols, classLoader, markerKey, MIGRATION_VERSION);
                Object persistedMarker =
                    DataStore.get(symbols, classLoader, markerKey, "");
                if (!MIGRATION_VERSION.equals(persistedMarker)) {
                    throw new IllegalStateException(
                        "migration marker verification failed: " + persistedMarker);
                }
                stateMigrationChecked = true;
                Log.i(TAG, "XPAN live photo state migrated to " + MIGRATION_VERSION);
            } catch (Throwable error) {
                Log.e(TAG, "XPAN live photo state migration failed", error);
            }
        }
    }

    private static Object livePhotoSharedKey(Symbols symbols) throws Throwable {
        for (Field field : symbols.<Field>all("ModeSwitch.keys")) {
            Object key = field.get(null);
            if (key != null && Reflection.hasStringField(key, LIVE_PHOTO_SUPPORT)) {
                return key;
            }
        }
        throw new IllegalStateException("DataKey not found: " + LIVE_PHOTO_SUPPORT);
    }

    private static Object prefsKey(Symbols symbols, String name) throws Throwable {
        return symbols.call("DataKeys.prefs", null, String.class, name);
    }

    private static void putSync(
        Symbols symbols, ClassLoader classLoader, Object key, Object value) throws Throwable {
        symbols.call(
            "DataManager.putSync", DataStore.manager(classLoader), key, value, Boolean.FALSE);
    }

    /**
     * LivePhotoPresenter accepts preview stage 4 only for several named camera modes; xpan_mode
     * reaches the same stage but is rejected as "incorrect". Feed the presenter a copy tagged as
     * photo_mode so its unchanged preview-stage and session logic can run. The shared event is not
     * modified, so other presenters still see xpan_mode.
     */
    private static void hookPreviewStage(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        Class<?> stageClass = XposedHelpers.findClass(PREVIEW_STAGE, classLoader);
        Method listener = XposedHelpers.findMethodExact(presenter, "a2", stageClass);
        Constructor<?> copy = XposedHelpers.findConstructorExact(
            stageClass, int.class, int.class, Size.class, Size.class, boolean.class);

        XposedBridge.hookMethod(listener, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args.length != 1) return;
                Object event = param.args[0];
                if (XposedHelpers.getIntField(event, "b") != 4
                    || !XPAN_UNIT_MODE.equals(
                        XposedHelpers.getObjectField(event, "g"))) {
                    return;
                }
                Object pictureSize = XposedHelpers.getObjectField(event, "d");
                if (pictureSize == null) return;

                try {
                    Object normalized = copy.newInstance(
                        XposedHelpers.getIntField(event, "b"),
                        XposedHelpers.getIntField(event, "e"),
                        XposedHelpers.getObjectField(event, "c"),
                        pictureSize,
                        XposedHelpers.getBooleanField(event, "f"));
                    XposedHelpers.setObjectField(normalized, "g", PHOTO_UNIT_MODE);
                    XposedHelpers.setBooleanField(
                        normalized, "h",
                        XposedHelpers.getBooleanField(event, "h"));
                    XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, new Object[] {normalized});
                    if (!previewStageLogged) {
                        previewStageLogged = true;
                        Log.i(TAG, "XPAN preview stage accepted through photo_mode compatibility");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN preview stage compatibility failed", error);
                }
            }
        });
    }

    /**
     * XPAN reaches the restored live-photo presenter, but its capture request is created through
     * the photo-mode producer with {@code CameraRequestTag.t1} still false. The presenter checks
     * that flag before starting its encoder, so the shot is saved as a static JPEG even though the
     * live-photo button and preview metadata are on. Fill only that missing request flag; the stock
     * presenter remains responsible for recording and packaging the dynamic photo.
     */
    private static void hookCaptureRequestStage(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        Class<?> stageClass = XposedHelpers.findClass(CAPTURE_REQUEST_STAGE, classLoader);
        Method prepareRequestTag =
            XposedHelpers.findMethodExact(presenter, "V1", stageClass);
        Method captureStarted =
            XposedHelpers.findMethodExact(presenter, "C1", stageClass);

        XposedBridge.hookMethod(prepareRequestTag, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1
                    || !isXpanPresenter(param.thisObject)
                    || !isLivePhotoStoredOn(param.thisObject)) {
                    return;
                }
                Object event = param.args[0];
                if (event == null) return;
                try {
                    Object requestTag = XposedHelpers.getObjectField(event, "B");
                    if (requestTag == null
                        || XposedHelpers.getBooleanField(requestTag, "t1")) {
                        return;
                    }
                    XposedHelpers.setBooleanField(requestTag, "t1", true);
                    /* r442: per-capture visibility — the once flag blinded shot 2+. */
                    Log.i(TAG, "XPAN capture request live photo t1=false -> true"
                        + ", x=" + XposedHelpers.getBooleanField(requestTag, "x")
                        + ", d1=" + XposedHelpers.getBooleanField(requestTag, "d1")
                        + ", multiBurstIndex="
                        + XposedHelpers.getIntField(requestTag, "V1"));
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN capture request live photo stamp failed", error);
                }
            }
        });

        /*
         * The producer replaces the tag seen by onPrepareRequestTag before the stage-7 callback.
         * Stamp the callback's own tag so the stock branch below the live-photo check sees the
         * user's enabled state and starts the encoder/session.
         */
        XposedBridge.hookMethod(captureStarted, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1
                    || !isXpanPresenter(param.thisObject)
                    || !isLivePhotoStoredOn(param.thisObject)) {
                    return;
                }
                Object event = param.args[0];
                if (event == null) return;
                try {
                    Object requestTag = XposedHelpers.getObjectField(event, "B");
                    if (requestTag == null
                        || XposedHelpers.getBooleanField(requestTag, "t1")) {
                        return;
                    }
                    XposedHelpers.setBooleanField(requestTag, "t1", true);
                    /* r443: fresh frame budget per shot — see resetLivePhotoFrameBudget. */
                    xpanLivePresenterRef = new WeakReference<>(param.thisObject);
                    xpanLiveCaptureActive = true;
                    resetLivePhotoFrameBudget(param.thisObject, "capture-start");
                    /* r442: per-capture visibility — the once flag blinded shot 2+. */
                    Log.i(TAG, "XPAN capture started live photo t1=false -> true"
                        + ", stage=" + XposedHelpers.getIntField(event, "w")
                        + ", x=" + XposedHelpers.getBooleanField(requestTag, "x")
                        + ", d1=" + XposedHelpers.getBooleanField(requestTag, "d1")
                        + ", multiBurstIndex="
                        + XposedHelpers.getIntField(requestTag, "V1"));
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN capture started live photo stamp failed", error);
                }
            }
        });
    }

    /**
     * Native live photo lowers XPAN's AE result from ISO 800 to ISO 200 while keeping the same
     * shutter time, so the saved frame is about two stops darker than the viewfinder. Apply the
     * correction at the final CaptureRequest parameter sink, preserving any user EV already in the
     * incoming value. Scope it to the active XPAN mode and the live-photo preference, and rebuild
     * the value from scratch on every call so repeated requests cannot stack corrections.
     */
    private static void hookExposureCompensationFix(ClassLoader classLoader) {
        final Class<?> baseMode = XposedHelpers.findClass(
            "com.oplus.camera.module.BaseMode", classLoader);
        final Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        final int fix = clamp(
            property(XPAN_LIVE_EV_PROPERTY, DEFAULT_XPAN_LIVE_EV_UNITS),
            -MAX_AE_EV_UNITS, MAX_AE_EV_UNITS);

        XposedBridge.hookAllMethods(baseMode, "V7", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                updateActiveBaseMode(param.thisObject, "preview");
            }
        });

        try {
            XposedBridge.hookMethod(
                Symbols.get().method("ModeSwitch.switch"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length < 2 || !(param.args[1] instanceof String)) {
                            return;
                        }
                        updateCurrentMode((String) param.args[1], "mode-switch");
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN mode switch tracking unavailable", error);
        }

        Class<?> camera = XposedHelpers.findClass(
            CAMERA_IMPLEMENTATION, classLoader);
        XposedBridge.hookAllMethods(camera, "v", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (fix == 0
                    || param.args.length < 2
                    || param.args[0] != CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION
                    || !(param.args[1] instanceof Number)
                    || !XPAN_MODE.equals(currentCameraMode)
                    || !isLivePhotoStoredOn(presenter)) {
                    return;
                }

                int original = ((Number) param.args[1]).intValue();
                int effective = clamp(
                    original + fix, -MAX_AE_EV_UNITS, MAX_AE_EV_UNITS);
                param.args[1] = Integer.valueOf(effective);

                String signature = fix + "|" + original + "|" + effective;
                if (signature.equals(exposureFixLogSignature)) return;
                exposureFixLogSignature = signature;
                Log.i(TAG, "XPAN live exposure correction mode="
                    + currentCameraMode
                    + " original=" + original
                    + " fix=" + fix
                    + " effective=" + effective
                    + " ev=" + (fix / 330.0));
            }
        });
        Log.i(TAG, "XPAN live exposure fix armed, units=" + fix
            + ", ev=" + (fix / 330.0));
    }

    private static void updateActiveBaseMode(Object mode, String source) {
        if (mode == null) return;
        try {
            Object name = XposedHelpers.getObjectField(mode, BASE_MODE_NAME_FIELD);
            if (name instanceof String) {
                updateCurrentMode((String) name, source);
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void updateCurrentMode(String mode, String source) {
        if (mode == null || mode.isEmpty() || mode.equals(currentCameraMode)) return;
        String previous = currentCameraMode;
        currentCameraMode = mode;
        Log.i(TAG, "XPAN exposure scope mode " + previous
            + " -> " + mode + ", source=" + source);
        if (XPAN_MODE.equals(mode) && !XPAN_MODE.equals(previous)) {
            // r457: each XPAN visit may inherit a foreign 64^3 table in gl.s field o
            // (every mode pushes its own via s.r on entry, XPAN does not), so re-arm the
            // wash-time repair regardless of what the last push contained.
            previewRepairHash = 0;
            // r447: per-session diagnostics reset so probes never blind-fold a second visit.
            uniformProbeCalls.set(0);
            interestingUniformCalls.set(0);
            drawCensus.clear();
        }
    }

    private static int property(String name, int fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) type.getMethod("get", String.class).invoke(null, name);
            return value == null || value.isEmpty()
                ? fallback : Integer.parseInt(value.trim());
        } catch (Throwable error) {
            return fallback;
        }
    }

    /** r456: string-valued variant for mode-switching switches without a rebuild. */
    private static String propertyStr(String name, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) type.getMethod("get", String.class).invoke(null, name);
            return value == null || value.isEmpty() ? fallback : value.trim();
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * Stock live photo treats EIS as a binary feature: enabling it also sends the stream through
     * the native EIS processor. That processor rejects XPAN's 1920x864 stream with status 152
     * before the encoder receives any frame. The stock feature-flag helper already has a tested
     * bypass path that drains the same metadata queue and returns real image frames directly.
     *
     * <p>Override only the bypass predicate. Keep the EIS-enabled flag itself untouched so normal
     * live photo, master, high-pixel and other modes continue through the original EIS path.
     */
    private static void hookXpanEisBypass(ClassLoader classLoader) {
        final Class<?> featureFlags =
            XposedHelpers.findClass(LIVE_PHOTO_FEATURE_FLAGS, classLoader);
        final Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        Method isEisBypass = XposedHelpers.findMethodExact(featureFlags, "e");

        XposedBridge.hookMethod(isEisBypass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(EIS_GRID_INIT_ALLOW.get())) {
                    param.setResult(Boolean.FALSE);
                    return;
                }
                /*
                 * r428: split the merged gate so each rejection is individually observable.
                 * The r427 logs were blind here -- a merged condition plus a once log flag
                 * left no way to tell whether the bypass never activated or activated late.
                 */
                if (param.hasThrowable()
                    || !XPAN_MODE.equals(currentCameraMode)
                    || !isLivePhotoStoredOn(presenter)
                    || property(XPAN_EIS_BYPASS_PROPERTY, 1) == 0) {
                    xpanEisBypassActive = false;
                    logLiveEisRouteOnce(
                        "bypass-gate|" + liveEisRouteMode() + "|" + bypassGateReason(param, presenter),
                        "XPAN live EIS bypass gate rejected"
                            + ", reason=" + bypassGateReason(param, presenter)
                            + ", mode=" + liveEisRouteMode());
                    return;
                }
                try {
                    Object eisEnabled =
                        XposedHelpers.callStaticMethod(featureFlags, "f");
                    if (!Boolean.TRUE.equals(eisEnabled)) {
                        xpanEisBypassActive = false;
                        logLiveEisRouteOnce(
                            "bypass-eis-off|" + liveEisRouteMode(),
                            "XPAN live EIS bypass inactive, stock eis disabled (f=false)"
                                + ", mode=" + liveEisRouteMode());
                        return;
                    }
                    boolean stockBypass = Boolean.TRUE.equals(param.getResult());
                    if (!stockBypass) {
                        param.setResult(Boolean.TRUE);
                    }
                    xpanEisBypassActive = true;
                    if (!eisBypassLogged) {
                        eisBypassLogged = true;
                        Log.i(TAG, "XPAN live EIS bypass active, mode="
                            + currentCameraMode + ", nativeStatus=152, stockBypass="
                            + stockBypass);
                    }
                } catch (Throwable error) {
                    xpanEisBypassActive = false;
                    Log.e(TAG, "XPAN live EIS bypass probe failed", error);
                }
            }
        });
        Log.i(TAG, "XPAN live EIS bypass armed");
    }

    /**
     * Keeps XPAN live photos on the native encoding path by neutralising the ch.e YUV
     * converter that gh.e2.Z2 wires in after a GR/master -> XPAN switch. r435 proved the
     * healthy path (direct XPAN) never touches this converter — every capture-encoder
     * ImageData carries handle 0x0 — while the rewired path feeds the encoder frames
     * produced by a converter whose FBO colour attachment is invalid (0x8cd5), which
     * encodes as solid green. Skipping ch.e.k/l/b inside XPAN leaves ImageData.l at 0,
     * exactly like the healthy path. Other modes are untouched.
     */
    private static void hookXpanNativeConversion(ClassLoader classLoader) {
        final Class<?> conversion = XposedHelpers.findClass(
            YUV_CONVERSION_CLASS, classLoader);
        XC_MethodHook skipHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || property(XPAN_NO_CONV_PROPERTY, 1) == 0) {
                    return;
                }
                param.setResult(
                    "b".equals(param.method.getName()) ? Long.valueOf(0L) : null);
                int skips = ++xpanConversionSkips;
                if (skips <= 3 || skips % 120 == 0) {
                    Log.i(TAG, "XPAN YUV converter call skipped"
                        + ", method=" + param.method.getName()
                        + ", mode=" + currentCameraMode
                        + ", skipCount=" + skips
                        + " (set persist.omnicam.xpan.noconv=0 to restore stock)");
                }
            }
        };
        // Exact signatures only: ch.e also has b([I) which must not be touched.
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(conversion, "k",
            XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader)), skipHook);
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(conversion, "l",
            android.hardware.HardwareBuffer.class), skipHook);
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(conversion, "b",
            float.class, float.class, int.class,
            boolean.class, boolean.class, float[].class), skipHook);
        Log.i(TAG, "XPAN native conversion bypass armed (noconv)");

        /*
         * r438: redirect instead of skip. Z2 is the only producer on the 2D-texture
         * branch, so returning null starves the encoder (r437: no video track at all).
         * Replicate Y1's native dispatch for an OES source: X2 only when its gates pass
         * (s3 && !M0 && p1 != null && W0 && fh.a.a&0x800000 — checked here so X2's own
         * failure path, which posts an error runnable per frame, is never entered),
         * otherwise b3. b3 falls back to X2 failure. Both consume event.e.b() directly.
         */
        XC_MethodHook z2SkipHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || property(XPAN_SKIP_Z2_PROPERTY, 1) == 0
                    || param.args.length != 7) {
                    return;
                }
                Object event = param.args[0];
                String bufferState = "n/a";
                try {
                    Object holder = XposedHelpers.getObjectField(event, "e");
                    Object buffer = holder == null
                        ? null : XposedHelpers.callMethod(holder, "b");
                    if (buffer instanceof android.hardware.HardwareBuffer) {
                        android.hardware.HardwareBuffer hb =
                            (android.hardware.HardwareBuffer) buffer;
                        bufferState = hb.getWidth() + "x" + hb.getHeight()
                            + ",fmt=" + hb.getFormat()
                            + (hb.isClosed() ? ",closed" : "");
                    } else {
                        bufferState = "null";
                    }
                } catch (Throwable error) {
                    bufferState = "err:" + error.getClass().getSimpleName();
                }
                Object redirected = null;
                String via = "none";
                if (x2GateOpen(param.thisObject)) {
                    try {
                        redirected = XposedHelpers.callMethod(
                            param.thisObject, "X2", param.args);
                        if (redirected != null) via = "X2";
                    } catch (Throwable error) {
                        Log.i(TAG, "XPAN Z2 redirect X2 failed: " + error);
                    }
                }
                if (redirected == null) {
                    try {
                        redirected = XposedHelpers.callMethod(
                            param.thisObject, "b3", param.args);
                        if (redirected != null) via = "b3";
                    } catch (Throwable error) {
                        Log.i(TAG, "XPAN Z2 redirect b3 failed: " + error);
                    }
                }
                param.setResult(redirected);
                int skips = ++xpanZ2Skips;
                if (skips <= 3 || skips % 120 == 0) {
                    Log.i(TAG, "XPAN Z2 frame path redirected"
                        + ", via=" + via
                        + ", buffer=" + bufferState
                        + ", mode=" + currentCameraMode
                        + ", count=" + skips
                        + " (set persist.omnicam.xpan.skipz2=0 to restore stock)");
                }
            }
        };
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader),
            "Z2", z2SkipHook);
        Log.i(TAG, "XPAN Z2 frame path bypass armed (redirect X2/b3)");

        /*
         * Observe the single downstream fan-out for all three ImageData producers
         * (gh/i2.k). After the Z2 skip this tells us whether X2/b3 really took over the
         * frame source and which handle they carry.
         */
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(FRAME_SINK, classLoader),
            "k", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)
                        || param.args.length != 1
                        || param.args[0] == null) {
                        return;
                    }
                    int sinks = ++xpanFrameSinks;
                    if (sinks <= 3 || sinks % 120 == 0) {
                        Object image = param.args[0];
                        Object handle = "n/a";
                        try {
                            handle = XposedHelpers.getLongField(image, "l");
                        } catch (Throwable ignored) {
                            // not the video ImageData subclass
                        }
                        Log.i(TAG, "XPAN frame sink"
                            + ", image=" + Integer.toHexString(
                                System.identityHashCode(image))
                            + ", l=" + handle
                            + ", sinkCount=" + sinks);
                    }
                }
            });
        Log.i(TAG, "XPAN frame sink probe armed");
    }

    /*
     * Mirror of Y1's dispatch (dex 0x0535-0x055d) plus X2's own internal gate: X2 only
     * produces an ImageData when s3() && p1 != null && W0 && (fh.a.a & 0x800000) != 0;
     * when the gate fails it returns null AND posts an error runnable, so the gate is
     * evaluated here first and b3 (ungated, plain YUV buffer path) handles the rest.
     */
    private static boolean x2GateOpen(Object presenter) {
        try {
            if (!(Boolean) XposedHelpers.callMethod(presenter, "s3")) return false;
            Object p1 = XposedHelpers.getObjectField(presenter, "p1");
            if (p1 == null) return false;
            if (!(Boolean) XposedHelpers.getBooleanField(presenter, "W0")) return false;
            int flags = XposedHelpers.getStaticIntField(
                XposedHelpers.findClass("fh.a", presenter.getClass().getClassLoader()), "a");
            return (flags & 0x800000) != 0;
        } catch (Throwable error) {
            Log.i(TAG, "XPAN X2 gate check failed: " + error);
            return false;
        }
    }

    private static String bypassGateReason(
        XC_MethodHook.MethodHookParam param,
        Object presenter
    ) {
        if (param.hasThrowable()) return "throwable";
        if (!XPAN_MODE.equals(currentCameraMode)) return "mode";
        if (!isLivePhotoStoredOn(presenter)) return "liveOff";
        return "property";
    }

    /** Post-delegation status logging for the guarded EIS processor entry (see f hook). */
    private static void logEisProcessorStatus(
        XC_MethodHook.MethodHookParam param,
        Object result
    ) {
        if (param.args.length != 1) return;
        int status = result instanceof Number
            ? ((Number) result).intValue() : Integer.MIN_VALUE;
        Object grid = safeObjectField(param.thisObject, "x");
        logLiveEisRouteOnce(
            "processor-status|" + liveEisRouteMode() + "|" + status
                + "|gridNull=" + (grid == null),
            "XPAN live EIS route processor"
                + ", mode=" + liveEisRouteMode()
                + ", status=" + status
                + ", gridNull=" + (grid == null)
                + ", input=" + (param.args[0] == null ? "null" : "present"));
    }

    /**
     * Record the stock EIS life cycle without changing it. The decisive test is whether
     * {@code EISProcessor.c(width, height, cameraId)} creates the grid and whether a later
     * {@code ImageData.e(grid)} copies it into {@code ImageData.o}, which is the object consumed
     * by the Warp renderer.
     */
    private static void hookXpanEisGridRouteDiagnostics(ClassLoader classLoader) {
        final Class<?> featureFlags =
            XposedHelpers.findClass(LIVE_PHOTO_FEATURE_FLAGS, classLoader);
        final Class<?> processor =
            XposedHelpers.findClass(EIS_PROCESSOR, classLoader);
        final Class<?> wrapper =
            XposedHelpers.findClass(EIS_WRAPPER, classLoader);
        final Class<?> imageData =
            XposedHelpers.findClass(VIDEO_IMAGE_DATA, classLoader);
        final Class<?> gridData =
            XposedHelpers.findClass(EIS_GRID_DATA, classLoader);

        XposedBridge.hookAllMethods(featureFlags, "e", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                logLiveEisRouteOnce(
                    "flags|" + liveEisRouteMode() + "|result=" + param.getResult(),
                    "XPAN live EIS route flags"
                        + ", mode=" + liveEisRouteMode()
                        + ", bypass=" + param.getResult());
            }
        });

        XposedBridge.hookAllMethods(processor, "c", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3) return;
                if (XPAN_MODE.equals(currentCameraMode)
                    && property(XPAN_EIS_BYPASS_PROPERTY, 1) != 0
                    && property(XPAN_EIS_GRID_PROPERTY, 0) != 0) {
                    EIS_GRID_INIT_ALLOW.set(Boolean.TRUE);
                }
                logEisProcessorState(param.thisObject, "init-before", param.args);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 3) return;
                try {
                    if (!param.hasThrowable()
                        && Boolean.TRUE.equals(EIS_GRID_INIT_ALLOW.get())) {
                        ensureEisMetadataQueue(param.thisObject, "grid-init");
                    }
                } finally {
                    EIS_GRID_INIT_ALLOW.remove();
                    logEisProcessorState(param.thisObject, "init-after", param.args);
                }
            }
        });

        /*
         * r438/r439 crash postmortem: after a GR -> XPAN switch the EIS processor init
         * (gh.g.c) skips mesh allocation when the bypass flag was on, leaving
         * gh.g.x == null. Frame sends take f's early-exit paths (150/101) which never
         * touch the mesh, and the video comes out fine. When the flag flips at capture
         * completion, the next f call reaches the deep path at offset 0x0377 which reads
         * this.x.e unconditionally -> NPE -> EISProcessorThread dies -> camera process
         * killed -> next shutter press dead. r439 skipped the meta send (e8) but the
         * per-frame send (f8) and the VEP pipeline (gh.p3.o) call f as well, so the
         * process still died after the first capture.
         * Replace f with a guarded delegation: natural return values are preserved
         * exactly (f8 dispatches on 150/101/-100), and only an NPE thrown while the
         * mesh is null is downgraded to 101, the wrapper's normal "processed" status.
         */
        final Class<?> imageDataParam =
            XposedHelpers.findClass(IMAGE_DATA, processor.getClassLoader());
        try {
            final Method fMethod = SuperCall.pin(
                processor.getDeclaredMethod("f", imageDataParam));
            XposedBridge.hookMethod(fMethod, new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param)
                        throws Throwable {
                    if (param.args.length == 1
                        && XPAN_MODE.equals(currentCameraMode)
                        && xpanEisBypassActive) {
                        ensureEisMetadataQueue(param.thisObject, "before-frame");
                    }
                    try {
                        Object result = XposedBridge.invokeOriginalMethod(
                            fMethod, param.thisObject, param.args);
                        logEisProcessorStatus(param, result);
                        return result;
                    } catch (Throwable error) {
                        if (error instanceof NullPointerException
                            && XPAN_MODE.equals(currentCameraMode)
                            && safeObjectField(param.thisObject, "x") == null) {
                            logLiveEisRouteOnce(
                                "processor-npe-swallowed",
                                "XPAN live EIS processor NPE swallowed, mesh null"
                                    + " (returned 101, prevents process crash)");
                            return 101;
                        }
                        throw error;
                    }
                }
            });
        } catch (NoSuchMethodException error) {
            Log.e(TAG, "XPAN live EIS processor f pin failed", error);
        }

        XposedBridge.hookAllMethods(wrapper, "f8", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                logEisWrapperState(param, "send");
            }
        });
        XposedBridge.hookAllMethods(wrapper, "e8", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                /*
                 * r441 postmortem (supersedes the r439/r440 skip): "sendMetaToEis" is the
                 * ONLY place that closes the cached CaptureResult handles (offsets 0x015d
                 * and 0x016c), posts the gh/m completion callback (0x019a) and logs
                 * "sendMetaToEis, finish" (0x01b0). Skipping the whole method when the
                 * mesh is null (r439/r440) therefore leaks every cached result handle and
                 * never posts the completion -> the capture session hangs and the next
                 * shutter press is dead (r440 eisgrid=0 test, pid 23375, 07:34). The mesh
                 * itself is only read at 0x0132/0x0134 (ImageData.e(grid)) and that call
                 * tolerates a null argument, and gh.g.f's mesh NPE is contained by the
                 * protected-delegation hook below (swallow -> 101, a status the healthy
                 * meta sequence itself produces). So e8 must always run natively in XPAN.
                 */
                if (!XPAN_MODE.equals(currentCameraMode) || param.args == null) {
                    return;
                }
                Object processor = safeObjectField(param.thisObject, "g");
                if (processor != null && safeObjectField(processor, "x") == null) {
                    logLiveEisRouteOnce(
                        "meta-null-mesh-observed",
                        "XPAN live EIS meta send running with mesh null"
                            + " (native run, f NPE contained by protected delegation)");
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                logEisWrapperState(param, "meta");
                /* r443: 104 = capture finished; release the frame budget again. */
                if (!param.hasThrowable()) {
                    Object status = safeObjectField(param.thisObject, "q");
                    if (status instanceof Number
                        && ((Number) status).intValue() == 104) {
                        xpanLiveCaptureActive = false;
                        resetLivePhotoFrameBudget(
                            xpanLivePresenterRef.get(), "capture-end");
                    }
                }
            }
        });
        XposedBridge.hookAllMethods(wrapper, "c8", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object handle = param.getResult();
                Object output = safeObjectField(handle, "e");
                Object directGrid = safeObjectField(output, "o");
                logLiveEisRouteOnce(
                    "output|" + liveEisRouteMode() + "|gridNull=" + (directGrid == null),
                    "XPAN live EIS route output"
                        + ", mode=" + liveEisRouteMode()
                        + ", handleNull=" + (handle == null)
                        + ", imageNull=" + (output == null)
                        + ", directGridNull=" + (directGrid == null));
            }
        });

        XposedBridge.hookAllMethods(imageData, "e", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1
                    || (param.args[0] != null
                        && !gridData.isInstance(param.args[0]))) {
                    return;
                }
                /*
                 * r441: with the EIS processor bypassed (mesh null) sendMetaToEis still
                 * calls ImageData.e(grid) at offset 0x0134 with whatever gh.g.x holds —
                 * null in XPAN. The stock method is only ever exercised with a real grid
                 * upstream, so guard the null case here instead of risking a second NPE
                 * inside the data class; the ImageData simply keeps its previous grid.
                 */
                if (param.args[0] == null) {
                    param.setResult(null);
                    logLiveEisRouteOnce(
                        "grid-copy-null-skip|" + liveEisRouteMode(),
                        "XPAN live EIS route grid copy skipped, inputGridNull=true"
                            + ", mode=" + liveEisRouteMode());
                    return;
                }
                Object grid = param.args[0];
                Object previousGrid = safeObjectField(param.thisObject, "o");
                String gridState = grid == null ? "null" : "present";
                logLiveEisRouteOnce(
                    "grid-copy-before|" + liveEisRouteMode()
                        + "|gridState=" + gridState,
                    "XPAN live EIS route grid copy"
                        + ", mode=" + liveEisRouteMode()
                        + ", phase=before"
                        + ", inputGridNull=" + (grid == null)
                        + ", gridSize=" + describeEisGridSize(grid)
                        + ", previousDirectGridNull=" + (previousGrid == null));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 1
                    || (param.args[0] != null
                        && !gridData.isInstance(param.args[0]))) {
                    return;
                }
                Object directGrid = safeObjectField(param.thisObject, "o");
                String gridState = param.args[0] == null ? "null" : "present";
                logLiveEisRouteOnce(
                    "grid-copy-after|" + liveEisRouteMode()
                        + "|inputGrid=" + gridState
                        + "|directGrid=" + (directGrid == null ? "null" : "present"),
                    "XPAN live EIS route grid copy"
                        + ", mode=" + liveEisRouteMode()
                        + ", phase=after"
                        + ", inputGridNull=" + (param.args[0] == null)
                        + ", directGridNull=" + (directGrid == null)
                        + ", throwable=" + param.hasThrowable());
            }
        });

        Log.i(TAG, "XPAN live EIS grid route diagnostics armed"
            + ", forceWarp=" + (property(XPAN_EIS_GRID_PROPERTY, 0) != 0));
    }

    /**
     * r442: map the live-photo session lifecycle around multi-capture. Y1's heartbeat tells
     * whether the frame event stream itself dies after the first capture; G3/H3/I3 are the
     * presenter's session close/toggle entries and their post-capture call order shows where
     * stock stops re-arming the XPAN live session (the frame path died after capture 1 in
     * every r438-r441 session while encoder feeds kept running).
     */
    private static void hookXpanSessionLifecycleProbes(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);

        XposedBridge.hookAllMethods(presenter, "Y1", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                xpanLivePresenterRef = new WeakReference<>(param.thisObject);
                int calls = ++xpanY1Calls;
                /*
                 * r443 test follow-up (5/5 shots OK): between captures the pending
                 * counters keep growing (frames are never closed in the redirect path)
                 * and ~12s of idle preview is enough to re-arm the intercept flag
                 * (h1=276, intercept=true at 08:35:57). The next capture-start reset
                 * disarms it again, but while armed Y1 drops preview frames and the
                 * pre-shutter cache starves. Recharge the budget during idle preview
                 * every ~1s so it never approaches the cap outside a capture.
                 */
                if (!xpanLiveCaptureActive && calls % 30 == 0
                    && pendingCount(param.thisObject, "h1") >= 200) {
                    resetLivePhotoFrameBudget(param.thisObject, "preview-idle");
                }
                if (calls <= 3 || calls % 120 == 0) {
                    Log.i(TAG, "XPAN Y1 frame events alive, count=" + calls
                        + ", args=" + param.args.length
                        + ", budget=" + livePhotoFrameBudget(param.thisObject));
                }
            }
        });

        XposedBridge.hookAllMethods(presenter, "G3", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Log.i(TAG, "XPAN presenter G3 called, mode=" + liveEisRouteMode()
                    + ", args=" + java.util.Arrays.toString(param.args));
            }
        });

        XC_MethodHook toggleProbe = new XC_MethodHook() {
            private volatile int calls;

            @Override protected void afterHookedMethod(MethodHookParam param) {
                int n = ++calls;
                if (n <= 3 || n % 60 == 0) {
                    Log.i(TAG, "XPAN presenter session toggle, method="
                        + (param.method != null ? param.method.getName() : "?")
                        + ", args=" + java.util.Arrays.toString(param.args)
                        + ", mode=" + liveEisRouteMode() + ", nth=" + n);
                }
            }
        };
        XposedBridge.hookAllMethods(presenter, "H3", toggleProbe);
        XposedBridge.hookAllMethods(presenter, "I3", toggleProbe);
        Log.i(TAG, "XPAN session lifecycle probes armed");
    }

    /** Pending live-photo frame counts (gh.e2 h1/i1/j1) plus the intercept flag state. */
    private static String livePhotoFrameBudget(Object presenter) {
        if (presenter == null) return "no-presenter";
        try {
            Object intercept = safeObjectField(presenter, "V1");
            int h = pendingCount(presenter, "h1");
            int i = pendingCount(presenter, "i1");
            int j = pendingCount(presenter, "j1");
            int high = XposedHelpers.getIntField(presenter, "Y1");
            return "h1=" + h + ", i1=" + i + ", j1=" + j
                + ", highWater=" + high
                + ", intercept=" + intercept;
        } catch (Throwable error) {
            return "unreadable(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static int pendingCount(Object presenter, String field) {
        Object counter = safeObjectField(presenter, field);
        if (counter == null) return -1;
        Object value = XposedHelpers.callMethod(counter, "get");
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /*
     * r443 root cause (r442 instrumentation, session 412119dd 08:07): Y1's live-photo
     * pending-frame counters gh.e2.h1/i1/j1 are drained by close listeners (gh.m1 /
     * gh.n1 / gh.p1) that fire when the produced ImageData is closed. With the r438
     * Z2->b3 redirect the frames are encoded fine but never closed, so the counters
     * grow monotonically from session creation (08:07:52 totalFrameCount=314 = every
     * frame since session start). Once the sum crosses Y1's intercept cap (275, or 145
     * once armed) during a capture, Y1 enters intercept mode (V1=true) and DROPS frames
     * ("checkInterceptCacheFrame, intercept cap") -> the recording starves (shot 2:
     * 46 frames / 1.865s instead of a full 3.2s), the pipeline wedges and every later
     * shutter press dies in CaptureProcessor.changeCaptureState
     * ("failed after raising event" -> "Interval limit denied"). Until the close path
     * is understood, reset the counters and the intercept flag at every capture
     * start/end so each shot gets a fresh full budget.
     */
    private static void resetLivePhotoFrameBudget(Object presenter, String phase) {
        if (presenter == null) return;
        try {
            int h = pendingCount(presenter, "h1");
            int i = pendingCount(presenter, "i1");
            int j = pendingCount(presenter, "j1");
            if (h == 0 && i == 0 && j == 0
                && !XposedHelpers.getBooleanField(presenter, "V1")) {
                return;
            }
            for (String field : new String[]{"h1", "i1", "j1"}) {
                Object counter = safeObjectField(presenter, field);
                if (counter != null) {
                    XposedHelpers.callMethod(counter, "set", 0);
                }
            }
            XposedHelpers.setIntField(presenter, "Y1", 0);
            XposedHelpers.setBooleanField(presenter, "V1", false);
            XposedHelpers.setIntField(presenter, "l1", 0);
            XposedHelpers.setIntField(presenter, "m1", 0);
            XposedHelpers.setLongField(presenter, "W1", 0);
            Log.i(TAG, "XPAN live photo frame budget reset at " + phase
                + " (was h1=" + h + ", i1=" + i + ", j1=" + j + ")");
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo frame budget reset failed at " + phase, error);
        }
    }

    /**
     * The stock grid-creation branch returns without initializing the metadata queue, because that
     * branch normally runs only while EIS bypass is disabled. XPAN needs the grid while the native
     * processor remains bypassed, so the following frame would otherwise call {@code z.offer()}
     * on null. Initialize only the exact stock field used by the bypass drain path.
     */
    private static void ensureEisMetadataQueue(Object processor, String phase) {
        if (processor == null) return;
        Object queue;
        try {
            queue = XposedHelpers.getObjectField(
                processor, EIS_METADATA_QUEUE_FIELD);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live EIS metadata queue probe failed at " + phase, error);
            return;
        }
        if (queue != null) return;
        try {
            XposedHelpers.setObjectField(
                processor, EIS_METADATA_QUEUE_FIELD, new ArrayDeque<Long>());
        } catch (Throwable error) {
            if (!eisMetadataQueueRepairErrorLogged) {
                eisMetadataQueueRepairErrorLogged = true;
                Log.e(TAG, "XPAN live EIS metadata queue init failed at " + phase, error);
            }
            return;
        }
        if (!eisMetadataQueueRepairLogged) {
            eisMetadataQueueRepairLogged = true;
            Log.i(TAG, "XPAN live EIS metadata queue initialized at " + phase
                + ", mode=" + liveEisRouteMode());
        }
    }

    private static void logEisProcessorState(
        Object processor,
        String phase,
        Object[] args
    ) {
        boolean bypass;
        boolean eisEnabled;
        try {
            Class<?> featureFlags = XposedHelpers.findClass(
                LIVE_PHOTO_FEATURE_FLAGS, processor.getClass().getClassLoader());
            bypass = Boolean.TRUE.equals(
                XposedHelpers.callStaticMethod(featureFlags, "e"));
            eisEnabled = Boolean.TRUE.equals(
                XposedHelpers.callStaticMethod(featureFlags, "f"));
        } catch (Throwable ignored) {
            bypass = false;
            eisEnabled = false;
        }
        Object grid = safeObjectField(processor, "x");
        String signature = "processor-" + phase + "|" + liveEisRouteMode()
            + "|bypass=" + bypass + "|eis=" + eisEnabled
            + "|gridNull=" + (grid == null);
        logLiveEisRouteOnce(
            signature,
            "XPAN live EIS route processor"
                + ", mode=" + liveEisRouteMode()
                + ", phase=" + phase
                + ", size=" + args[0] + "x" + args[1]
                + ", cameraId=" + args[2]
                + ", bypass=" + bypass
                + ", eisEnabled=" + eisEnabled
                + ", gridNull=" + (grid == null));
    }

    private static void logEisWrapperState(
        XC_MethodHook.MethodHookParam param,
        String phase
    ) {
        if (param.hasThrowable()) return;
        Object wrapper = param.thisObject;
        Object processor = safeObjectField(wrapper, "g");
        Object status = safeObjectField(wrapper, "q");
        Object grid = safeObjectField(processor, "x");
        logLiveEisRouteOnce(
            "wrapper-" + phase + "|" + liveEisRouteMode()
                + "|status=" + status + "|gridNull=" + (grid == null),
            "XPAN live EIS route wrapper"
                + ", mode=" + liveEisRouteMode()
                + ", phase=" + phase
                + ", status=" + status
                + ", gridNull=" + (grid == null)
                + ", args=" + param.args.length);
    }

    private static String describeEisGridSize(Object grid) {
        if (grid == null) return "null";
        try {
            Object size = XposedHelpers.getObjectField(grid, "c");
            return size instanceof Size
                ? ((Size) size).getWidth() + "x" + ((Size) size).getHeight()
                : String.valueOf(size);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object safeObjectField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String liveEisRouteMode() {
        String mode = currentCameraMode;
        return mode == null || mode.isEmpty() ? "unknown" : mode;
    }

    private static void logLiveEisRouteOnce(String key, String message) {
        synchronized (LIVE_EIS_ROUTE_PROBES) {
            if (!LIVE_EIS_ROUTE_PROBES.add(key)) return;
        }
        Log.i(TAG, message);
    }

    /**
     * The native live-photo EIS processor requires the private gyrotoeis metadata key on every
     * frame. XPAN's restored live-photo stream carries the camera timestamp but omits that key, so
     * ImageData returns null and the encoder drops the frame with status -100. Supply one neutral
     * gyro sample tied to the frame timestamp. It has no rotational motion, but it lets the
     * unchanged EIS and encoder pipeline consume the real image frames.
     */
    private static void hookXpanGyroFallback(ClassLoader classLoader) {
        Class<?> imageData = XposedHelpers.findClass(IMAGE_DATA, classLoader);
        Class<?> presenter = XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        Object gyroKey = findCaptureResultKey(
            XposedHelpers.findClass(CAMERA_METADATA_KEY, classLoader),
            XPAN_GYRO_KEY);
        Method readMetadata = XposedHelpers.findMethodExact(
            imageData, "b", CaptureResult.Key.class);

        XposedBridge.hookMethod(readMetadata, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()
                    || param.args.length != 1
                    || param.args[0] != gyroKey
                    || param.getResult() != null
                    || !XPAN_MODE.equals(currentCameraMode)
                    || !isLivePhotoStoredOn(presenter)) {
                    return;
                }
                try {
                    int[] gyro = syntheticGyro(param.thisObject);
                    param.setResult(gyro);
                    if (!gyroFallbackLogged) {
                        gyroFallbackLogged = true;
                        Log.i(TAG, "XPAN synthetic gyro injected, frameSOF="
                            + combineTimestamp(gyro[2], gyro[3])
                            + ", sampleTimestamp="
                            + combineTimestamp(gyro[8], gyro[9]));
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN synthetic gyro injection failed", error);
                }
            }
        });
        Log.i(TAG, "XPAN synthetic gyro fallback armed");
    }

    /**
     * XPAN's bypassed EIS path has no stock crop factor, so VideoEffectPipeline passes a null
     * float[] into its frame renderer and crashes while cloning it. The stock value for a neutral
     * crop is a two-element {1.0f, 1.0f} array. Supply that value only for an active XPAN
     * live-photo EIS-bypass stream; every non-null stock value and every other mode is untouched.
     */
    private static void hookXpanVideoCropFallback(ClassLoader classLoader) {
        final Class<?> pipeline =
            XposedHelpers.findClass(VIDEO_EFFECT_PIPELINE, classLoader);
        final Class<?> imageData =
            XposedHelpers.findClass(VIDEO_IMAGE_DATA, classLoader);
        final Class<?> presenter =
            XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        Method renderFrame = XposedHelpers.findMethodExact(
            pipeline,
            "E",
            imageData,
            boolean.class,
            int.class,
            int.class,
            float[].class);

        XposedBridge.hookMethod(renderFrame, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 5
                    || param.args[4] != null
                    || !xpanEisBypassActive
                    || !XPAN_MODE.equals(currentCameraMode)
                    || !isLivePhotoStoredOn(presenter)) {
                    return;
                }
                param.args[4] = new float[] {1.0f, 1.0f};
                if (!videoCropFallbackLogged) {
                    videoCropFallbackLogged = true;
                    Log.i(TAG, "XPAN null video crop fallback, neutral=[1.0, 1.0]"
                        + ", mode=" + currentCameraMode
                        + ", intA=" + param.args[2]
                        + ", intB=" + param.args[3]
                        + ", flag=" + param.args[1]);
                }
            }
        });

        /*
         * E() is also a complete renderer for the live-photo sub-video. It binds BasicTone and
         * the ordinary LUT itself, so those bind probes cannot be attributed to p3.r() without
         * marking this call as a nested sub-video stage.
         */
        XposedBridge.hookAllMethods(pipeline, "E", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 5
                    || !imageData.isInstance(param.args[0])
                    || XPAN_MAIN_FRAME.get() == null) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                frame.subVideoCalls++;
                pushRenderStage(param, "subvideo");
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                popRenderStage(param);
            }
        });
        Log.i(TAG, "XPAN video crop fallback armed");
    }

    private static void beginMainFrame(
        XC_MethodHook.MethodHookParam param,
        Object pipeline,
        Object imageData
    ) {
        if (!XPAN_MODE.equals(currentCameraMode)) return;
        long frameNow = System.currentTimeMillis();
        /*
         * r428: live recording starts ~2.6s before the shutter, so the first frame of a new
         * capture arrives well before the capture-started callback. Detect the frame-flow
         * gap and reset the once-per-capture diagnostic state there: the once log flags
         * otherwise stay consumed after the first capture and every later capture in the
         * same session renders blind (the r423-r427 log analysis trap).
         */
        if (frameNow - lastMainFrameAt > 3000L) {
            resetXpanLiveCaptureDiagnostics();
        }
        lastMainFrameAt = frameNow;
        Object previous = XPAN_MAIN_FRAME.get();
        param.setObjectExtra("xpanMainFramePrevious", previous);
        try {
            Object metadata = XposedHelpers.getObjectField(imageData, "f");
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            Object directGrid = XposedHelpers.getObjectField(imageData, "o");
            boolean eisEnabledOn = readLiveEisEnabled(pipeline);
            XpanMainFrame frame = new XpanMainFrame(
                ++mainFrameSequence,
                readMetadataLong(metadata, "image_picture_id"),
                readMetadataLong(metadata, "meta_timestamp"),
                directGrid == null,
                readFallbackGrid(pipeline) == null,
                XposedHelpers.getObjectField(imageData, "i") == null,
                readMetadataBoolean(metadata, "image_is_watermark_open"),
                XposedHelpers.getObjectField(imageData, "m") != null,
                readMetadataInt(metadata, "image_crop_preview_width"),
                readMetadataInt(metadata, "image_crop_preview_height"),
                XposedHelpers.getIntField(pipeline, "A"),
                XposedHelpers.getIntField(pipeline, "B"),
                XposedHelpers.getIntField(pipeline, "n0"),
                renderer != null && XposedHelpers.getBooleanField(renderer, "p"),
                renderer != null && XposedHelpers.getBooleanField(renderer, "o"),
                renderer != null && XposedHelpers.getBooleanField(renderer, "q"),
                renderer == null || XposedHelpers.getObjectField(renderer, "r") == null,
                renderer == null || XposedHelpers.getObjectField(renderer, "s") == null,
                xpanEisBypassActive,
                eisEnabledOn
            );
            XPAN_MAIN_FRAME.set(frame);
            param.setObjectExtra("xpanMainFrame", frame);
        } catch (Throwable error) {
            XPAN_MAIN_FRAME.remove();
            if (!mainFrameDiagnosticErrorLogged) {
                mainFrameDiagnosticErrorLogged = true;
                Log.e(TAG, "XPAN main frame capture failed", error);
            }
        }
    }

    /**
     * Clear the once-per-capture diagnostic state at the start of every new live capture so
     * later captures in the same session stay observable. UI/menu one-shot flags stay
     * untouched; only capture-scoped probes reset.
     */
    private static void resetXpanLiveCaptureDiagnostics() {
        synchronized (LIVE_EIS_ROUTE_PROBES) {
            LIVE_EIS_ROUTE_PROBES.clear();
        }
        captureRequestLivePhotoLogged = false;
        captureStartedLivePhotoLogged = false;
        supportProbeLogged = false;
        eligibilityProbeLogged = false;
        supportOverrideLogged = false;
        eligibilityOverrideLogged = false;
        resumeOverrideLogged = false;
        gyroFallbackLogged = false;
        eisBypassLogged = false;
        eisMetadataQueueRepairLogged = false;
        eisMetadataQueueRepairErrorLogged = false;
        basicToneParametersLogged = false;
        basicToneTextureUploadLogs = 0;
        basicToneTextureUnavailableLogged = false;
        basicToneBindingLogged = false;
        basicToneBindingErrorLogged = false;
        basicToneDirectProbeLogged = false;
        basicToneDirectShaderLogged = false;
        basicToneDirectFrameLogged = false;
        commonBasicToneBridgeLogged = false;
        /*
         * r428b: these were the remaining once flags missed in the first pass. The r428
         * A/B kept producing phantom differences (selected-LUT-callback "missing",
         * transform-capture "only after GR") that were really first-capture consumption
         * of these flags. Reset every capture-scoped probe so later captures are
         * individually readable.
         */
        videoCropFallbackLogged = false;
        liveTransformBypassLogged = false;
        liveTransformCaptureProbeLogged = false;
        liveTransformDrawProbeLogged = false;
        liveTransformUploadLogged = false;
        ordinaryLutBindLogged = false;
        basicToneBindLogged = false;
        selectedLutCallbackLogged = false;
        transformCaptureCallLogged = false;
        commonDrawSignatureLogged = false;
        warpDrawSignatureLogged = false;
        Log.i(TAG, "XPAN live capture diagnostics reset, mode=" + liveEisRouteMode()
            + ", eisBypassActive=" + xpanEisBypassActive);
    }

    private static boolean readLiveEisEnabled(Object context) {
        try {
            Class<?> featureFlags = XposedHelpers.findClass(
                LIVE_PHOTO_FEATURE_FLAGS, context.getClass().getClassLoader());
            return Boolean.TRUE.equals(
                XposedHelpers.callStaticMethod(featureFlags, "f"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void finishMainFrame(XC_MethodHook.MethodHookParam param) {
        Object frameObject = param.getObjectExtra("xpanMainFrame");
        if (frameObject instanceof XpanMainFrame) {
            XpanMainFrame frame = (XpanMainFrame) frameObject;
            if (shouldLogMainFrame(frame.sequence)) {
                Log.i(TAG, "XPAN main frame path"
                    + ", seq=" + frame.sequence
                    + ", path=" + mainDrawPath(frame)
                    + ", commonDraws=" + frame.commonDraws
                    + ", warpDraws=" + frame.warpDraws
                    + ", subVideoCalls=" + frame.subVideoCalls
                    + ", basicBinds=" + frame.commonBasicToneBinds + "/"
                    + frame.warpBasicToneBinds + "/"
                    + frame.subVideoBasicToneBinds
                    + ", ordinaryBinds=" + frame.commonOrdinaryLutBinds + "/"
                    + frame.warpOrdinaryLutBinds + "/"
                    + frame.subVideoOrdinaryLutBinds
                    + ", transformUploads=" + frame.commonTransformUploads + "/"
                    + frame.warpTransformUploads + "/"
                    + frame.subVideoTransformUploads
                    + ", pictureId=" + frame.pictureId
                    + ", timestamp=" + frame.timestamp
                    + ", directGridNull=" + frame.directGridNull
                    + ", fallbackGridNull=" + frame.fallbackGridNull
                    + ", transformNull=" + frame.transformNull
                    + ", crop=" + frame.cropWidth + "x" + frame.cropHeight
                    + ", pipelineAB=" + frame.pipelineWidth + "x"
                    + frame.pipelineHeight
                    + ", watermarkOpen=" + frame.watermarkOpen
                    + ", watermarkStatePresent=" + frame.watermarkStatePresent
                    + ", lutType=" + frame.lutType
                    + ", rendererPrepared=" + frame.rendererPrepared
                    + ", rendererHdr=" + frame.rendererHdr
                    + ", rendererLut=" + frame.rendererLut
                    + ", rendererRNull=" + frame.rendererRNull
                    + ", rendererSNull=" + frame.rendererSNull
                    + ", eisBypass=" + frame.eisBypassOn
                    + ", eisEnabled=" + frame.eisEnabledOn
                    + ", drawTarget=" + Integer.toHexString(frame.drawTargetId)
                    + ", throwable=" + param.hasThrowable());
            }
        }
        Object previous = param.getObjectExtra("xpanMainFramePrevious");
        if (previous instanceof XpanMainFrame) {
            XPAN_MAIN_FRAME.set((XpanMainFrame) previous);
        } else {
            XPAN_MAIN_FRAME.remove();
        }
    }

    private static boolean shouldLogMainFrame(int sequence) {
        return sequence <= 5 || sequence % 10 == 0;
    }

    private static String mainDrawPath(XpanMainFrame frame) {
        if (frame.commonDraws > 0 && frame.warpDraws > 0) return "mixed";
        if (frame.warpDraws > 0) return "warp";
        if (frame.commonDraws > 0) return "common";
        return "none";
    }

    private static Object readFallbackGrid(Object pipeline) {
        Object gridOwner = XposedHelpers.getObjectField(pipeline, "u");
        if (gridOwner == null) {
            Object graph = XposedHelpers.getObjectField(pipeline, "v");
            if (graph == null) return null;
            gridOwner = XposedHelpers.getObjectField(graph, "g");
        }
        return gridOwner == null
            ? null : XposedHelpers.getObjectField(gridOwner, "x");
    }

    private static void recordOrdinaryLutBind(XpanMainFrame frame) {
        String stage = XPAN_RENDER_STAGE.get();
        if ("common".equals(stage)) {
            frame.commonOrdinaryLutBinds++;
        } else if ("warp".equals(stage)) {
            frame.warpOrdinaryLutBinds++;
        } else if ("subvideo".equals(stage)) {
            frame.subVideoOrdinaryLutBinds++;
        }
    }

    private static void recordBasicToneBind(XpanMainFrame frame) {
        String stage = XPAN_RENDER_STAGE.get();
        if ("common".equals(stage)) {
            frame.commonBasicToneBinds++;
        } else if ("warp".equals(stage)) {
            frame.warpBasicToneBinds++;
        } else if ("subvideo".equals(stage)) {
            frame.subVideoBasicToneBinds++;
        }
    }

    private static void recordTransformUpload(XpanMainFrame frame) {
        String stage = XPAN_RENDER_STAGE.get();
        if ("common".equals(stage)) {
            frame.commonTransformUploads++;
        } else if ("warp".equals(stage)) {
            frame.warpTransformUploads++;
        } else if ("subvideo".equals(stage)) {
            frame.subVideoTransformUploads++;
        }
    }

    private static void pushRenderStage(
        XC_MethodHook.MethodHookParam param,
        String stage
    ) {
        param.setObjectExtra("xpanPreviousRenderStage", XPAN_RENDER_STAGE.get());
        XPAN_RENDER_STAGE.set(stage);
    }

    private static void popRenderStage(XC_MethodHook.MethodHookParam param) {
        Object previous = param.getObjectExtra("xpanPreviousRenderStage");
        if (previous instanceof String) {
            XPAN_RENDER_STAGE.set((String) previous);
        } else {
            XPAN_RENDER_STAGE.remove();
        }
    }

    private static int readMetadataInt(Object metadata, String key) {
        Object value = XposedHelpers.callMethod(metadata, "e", key);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static long readMetadataLong(Object metadata, String key) {
        Object value = XposedHelpers.callMethod(metadata, "f", key);
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static boolean readMetadataBoolean(Object metadata, String key) {
        Object value = XposedHelpers.callMethod(metadata, "c", key);
        return Boolean.TRUE.equals(value);
    }

    /**
     * XPAN's native live-photo presenter reports {@code mLutType=1}. The stock pipeline then
     * selects {@code WarpRenderer}'s BasicTone shader, but the type-1 LUT callback initializes
     * only the ordinary color-space LUT. Unlike the type-7 callback, it never binds the current
     * BasicTone table into {@code a0.s}. The still cover follows another path, while the dynamic
     * attachment therefore remains Log-gray.
     *
     * <p>Bind that missing stock BasicTone texture at the exact point where the live WarpRenderer
     * attempts to upload it. The manager data and texture helper are both stock. Scope remains
     * limited to XPAN live photo, and an already bound texture is never replaced.
     */
    /*
     * r448 diagnostics -- r447 proved the XPAN preview never touches a0's inherited methods
     * (census: zero preview calls, capture frames only), so the per-frame preview draw must be
     * a method declared on ch.g itself. This pass hooks every declared ch.g method too, and
     * moves the uniform probe from u0 (which is the TEXTURE class -- r447 mistake) to y0, the
     * shader class that owns uNeedBasicTone. Census lines now also snapshot the renderer's
     * BasicTone texture identity so a stale grey preview shows up right in the census.
     */
    /*
     * r449 diagnostics -- r448 proved the XPAN preview calls NO method on a0 or ch.g during
     * plain preview (census stayed at the capture frames only), so the preview renderer lives
     * somewhere else entirely. Hook GLES20.glDrawArrays/glDrawElements instead: every Java-side
     * GL draw must pass through them, and a short stack trace names the real preview renderer
     * no matter which class owns it.
     */
    private static void hookXpanDrawCensus(ClassLoader classLoader) {
        final Class<?> gles = android.opengl.GLES20.class;
        for (final String entry : new String[]{"glDrawArrays", "glDrawElements"}) {
            XposedBridge.hookAllMethods(gles, entry, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    long calls = uniformProbeCalls.incrementAndGet();
                    if (calls <= 30) {
                        Log.i(TAG, "XPAN gl draw probe, calls=" + calls
                            + ", fn=" + entry
                            + ", mode=" + (param.args.length > 0 ? param.args[0] : "?")
                            + stackSnippet());
                    } else if (calls % 300 == 0) {
                        Log.i(TAG, "XPAN gl draw probe, calls=" + calls
                            + ", fn=" + entry);
                    }
                }
            });
        }

        final Class<?> shaderClass = XposedHelpers.findClass(GL_SHADER, classLoader);
        XposedBridge.hookAllMethods(shaderClass, "m", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                if (param.args.length != 2
                    || !(param.args[0] instanceof Integer)
                    || !(param.args[1] instanceof String)) {
                    return;
                }
                String name = (String) param.args[1];
                boolean interesting = name.contains("BasicTone")
                    || name.contains("HdrTransform")
                    || name.contains("Vig");
                if (interesting) {
                    long calls = interestingUniformCalls.incrementAndGet();
                    if (calls <= 24 || calls % 240 == 0) {
                        Log.i(TAG, "XPAN uniform probe"
                            + ", icalls=" + calls
                            + ", program=" + describeGlObject(param.thisObject)
                            + ", index=" + param.args[0]
                            + ", name=" + name
                            + stackSnippet());
                    }
                }
            }
        });

        final Class<?> panelRenderer = XposedHelpers.findClass(
            "com.oplus.camera.common.gl.s", classLoader);
        /*
         * r451: gl.s is the XPAN PREVIEW renderer (proven by r449 glDrawArrays stacks:
         * every preview frame runs s.o0 -> s.r0 -> {s.t -> s.g | s.q0 -> s.b}, 3 draws per
         * frame; the other probe chains were filter-menu canvas UI). c0/d0 are its
         * glTexImage2D texture-upload entries taking a gl.c data descriptor; o() builds the
         * draw target. Snapshot the manager BasicTone table beside each so a grey preview
         * can be tied to a stale upload.
         */
        Method[] panelTargets;
        try {
            panelTargets = new Method[]{
                XposedHelpers.findMethodExact(panelRenderer, "o0",
                    shaderClass, int.class, int.class,
                    float.class, float.class, float.class, float.class),
                XposedHelpers.findMethodExact(panelRenderer, "c0",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader),
                    int.class, int.class),
                XposedHelpers.findMethodExact(panelRenderer, "d0",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader),
                    int.class, int.class, int.class),
                XposedHelpers.findMethodExact(panelRenderer, "o",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader)),
            };
        } catch (Throwable error) {
            Log.e(TAG, "XPAN gl.s probe resolve failed", error);
            panelTargets = new Method[0];
        }
        /*
         * r456: watch the canvas's own LUT data uploads. The r456 session measured ZERO
         * e0(slot==1) calls in a grey GR->XPAN session -- but dexdump shows THREE data
         * setters converging on static l0(t0,size,data): e0(data,slot,size) -> HashMap u,
         * Z(data,size,enabled) -> field n, r(data,size,enabled) -> field o. The native
         * side feeds the preview LUT through Z/r, which the r454/r455 trackers never
         * watched. r457 logs ALL of them (any slot) with content hash + caller stack.
         */
        try {
            XposedBridge.hookAllMethods(panelRenderer, "e0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 3
                        && param.args[0] instanceof float[]) {
                        float[] data = (float[]) param.args[0];
                        long hash = tableContentHash(data);
                        int slot = param.args[1] instanceof Number
                            ? ((Number) param.args[1]).intValue() : -1;
                        int size = param.args[2] instanceof Number
                            ? ((Number) param.args[2]).intValue() : -1;
                        logNativeLutUpload("e0", slot, size, hash, data.length);
                        if (slot == 1) {
                            previewLutUploadByCanvas.put(
                                System.identityHashCode(param.thisObject),
                                new long[]{
                                    GrPreviewStreamRestore.paletteWashes.get(),
                                    hash, size});
                            synchronized (previewNativeUploadLock) {
                                previewNativeUploadData =
                                    java.util.Arrays.copyOf(data, data.length);
                                previewNativeUploadHash = hash;
                                previewNativeUploadWash =
                                    GrPreviewStreamRestore.paletteWashes.get();
                            }
                        }
                    }
                }
            });
            XC_MethodHook namedSetterHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 3
                        && param.args[0] instanceof float[]) {
                        float[] data = (float[]) param.args[0];
                        long hash = tableContentHash(data);
                        int size = param.args[1] instanceof Number
                            ? ((Number) param.args[1]).intValue() : -1;
                        boolean enabled = Boolean.TRUE.equals(param.args[2]);
                        logNativeLutUpload(param.method.getName(), enabled ? 1 : 0,
                            size, hash, data.length);
                    }
                }
            };
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(
                panelRenderer, "Z", float[].class, int.class, boolean.class),
                namedSetterHook);
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(
                panelRenderer, "r", float[].class, int.class, boolean.class),
                namedSetterHook);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN preview LUT upload tracker unavailable", error);
        }
        for (final Method target : panelTargets) {
            final String shortName = target.getName();
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    if ("o0".equals(shortName)) {
                        reuploadPreviewBasicToneIfWashed(param.thisObject);
                        long calls = interestingUniformCalls.incrementAndGet();
                        if (calls > 8 && calls % 300 != 0) return;
                        Object shaderArg = param.args.length > 0 ? param.args[0] : null;
                        StringBuilder shaders = new StringBuilder();
                        for (String f : new String[]{"N", "O", "P", "Q", "R", "S", "T"}) {
                            try {
                                Object s = XposedHelpers.getObjectField(
                                    param.thisObject, f);
                                shaders.append(f).append('=')
                                    .append(describeGlObject(s)).append(' ');
                            } catch (Throwable ignored) {
                            }
                        }
                        Log.i(TAG, "XPAN preview draw, calls=" + calls
                            + ", entry=gl.s." + shortName
                            + ", self=" + describeGlObject(param.thisObject)
                            + ", argShader=" + describeGlObject(shaderArg)
                            + ", shaders=[" + shaders.toString().trim() + "]"
                            + ", manager[" + managerBasicSnapshot() + "]"
                            + ", wash=" + GrPreviewStreamRestore.paletteWashes.get());
                    } else {
                        Object data = param.args.length > 0 ? param.args[0] : null;
                        String dims = "?";
                        try {
                            if (data != null) {
                                dims = "f()=" + XposedHelpers.callMethod(data, "f")
                                    + ", c=" + XposedHelpers.getIntField(data, "c")
                                    + ", e=" + XposedHelpers.getIntField(data, "e")
                                    + ", f=" + XposedHelpers.getIntField(data, "f");
                            }
                        } catch (Throwable ignored) {
                        }
                        Log.i(TAG, "XPAN preview upload, entry=gl.s." + shortName
                            + ", self=" + describeGlObject(param.thisObject)
                            + ", data=" + describeGlObject(data) + " (" + dims + ")"
                            + ", manager[" + managerBasicSnapshot() + "]"
                            + ", wash=" + GrPreviewStreamRestore.paletteWashes.get());
                    }
                }
            });
        }

        final Class<?> baseRenderer = XposedHelpers.findClass(GL_RENDERER, classLoader);
        final Class<?> warpRenderer = XposedHelpers.findClass(LIVE_WARP_RENDERER, classLoader);
        Method[][] targets = {baseRenderer.getDeclaredMethods(),
            warpRenderer.getDeclaredMethods()};
        for (Method[] methods : targets) {
            for (Method method : methods) {
                int modifiers = method.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                    || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!XPAN_MODE.equals(currentCameraMode)) return;
                            String key = param.method.getDeclaringClass().getName()
                                + "." + param.method.getName() + "#" + param.args.length;
                            long[] slot = drawCensus.computeIfAbsent(key, k -> new long[1]);
                            long calls = ++slot[0];
                            if (calls <= 2 || calls % 600 == 0) {
                                Object renderer = param.thisObject;
                                Object basicTone = renderer == null
                                    ? null : XposedHelpers.getObjectField(renderer, "s");
                                Log.i(TAG, "XPAN draw census, entry=" + key
                                    + ", calls=" + calls
                                    + ", self=" + describeGlObject(renderer)
                                    + ", sTex=" + describeGlObject(basicTone));
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i(TAG, "XPAN draw census installed (r452 diagnostics)");
    }

    private static String describeGlObject(Object object) {
        if (object == null) return "null";
        return object.getClass().getSimpleName()
            + "@" + Integer.toHexString(System.identityHashCode(object));
    }

    /** r451: manager BasicTone table snapshot for preview probes (cheap, log-only). */
    private static String managerBasicSnapshot() {
        try {
            Object manager = XposedHelpers.getStaticObjectField(
                installedManagerHolder, "a");
            float[] data = (float[]) XposedHelpers.getObjectField(manager, "c");
            int size = XposedHelpers.getIntField(manager, "t");
            return "size=" + size
                + ", hash=" + (data == null ? 0 : Arrays.hashCode(data));
        } catch (Throwable error) {
            return "unavailable(" + error.getClass().getSimpleName() + ")";
        }
    }

    /**
     * r453: order counter of {@code FilterPresenter.select} calls observed in XPAN.
     */
    private static volatile int filterSelectCalls;
    /** r453: dedup key of the last 17^3 preview LUT logged by the filter probe. */
    private static volatile int filterProbeLutHash;

    /**
     * r455: last canvas BasicTone upload per gl.s instance: {wash generation, content hash,
     * size}. Keyed by identity hash; entries are tiny. See the e0 tracker note in
     * {@code hookXpanDrawCensus}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]>
        previewLutUploadByCanvas = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int previewLutReuploadLogged;

    /*
     * r456: capture of the last NATIVE gl.s slot-1 upload. The r455 session proved this
     * content differs from manager.c (the wash push reported contentChanged=true right
     * after a native upload), so manager.c is NOT the preview's correct table. These
     * fields keep the native reference around for a source-selectable repair and the
     * upload log identifies the native caller for the next investigation round.
     */
    private static final Object previewNativeUploadLock = new Object();
    private static volatile float[] previewNativeUploadData;
    private static volatile long previewNativeUploadHash;
    private static volatile long previewNativeUploadWash = -1;
    private static volatile int previewNativeUploadLogged;

    /** r455: cheap content fingerprint of a BasicTone table (length + strided samples). */
    private static long tableContentHash(float[] table) {
        if (table == null) return 0;
        long hash = table.length;
        int stride = Math.max(1, table.length / 256);
        for (int i = 0; i < table.length; i += stride) {
            hash = hash * 1000003 ^ Float.floatToIntBits(table[i]);
        }
        return hash;
    }

    private static final java.util.Set<String> nativeLutUploadSeen =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int nativeLutRepeatSample;

    /**
     * r457: log one native LUT data push into the preview canvas (gl.s.e0/Z/r), deduped
     * by (entry, slot, size, content hash) so steady-state repeats stay silent while
     * every content change is visible. Stack captured for the first sightings only.
     */
    private static void logNativeLutUpload(String entry, int slot, int size,
        long hash, int dataLength) {
        String key = entry + "/" + slot + "/" + size + "/" + hash;
        boolean fresh = nativeLutUploadSeen.add(key);
        if (!fresh && nativeLutRepeatSample++ % 200 != 0) return;
        Log.i(TAG, "XPAN native LUT push"
            + ", entry=gl.s." + entry
            + ", slot=" + slot
            + ", size=" + size
            + ", hash=" + hash
            + ", dataLength=" + dataLength
            + ", wash=" + GrPreviewStreamRestore.paletteWashes.get()
            + ", mode=" + currentCameraMode
            + (fresh ? stackSnippet() : " (repeat)"));
    }

    private static volatile long previewRepairWash = -1;
    private static volatile long previewRepairHash;

    /*
     * r457 wash-time repair, OFF by default ({@code persist.omnicam.xpan.presetsync}).
     * r456 telemetry established the grey mechanism: every capture mode pushes its own
     * 64^3 LUT into gl.s field o via s.r(data,size,true) on entry (common at startup,
     * gr, professional -- all observed), but XPAN never pushes one, so the XPAN preview
     * keeps sampling the previous mode's table (measured: professional's table resident
     * during the grey master->xpan entry; GR's table re-pushed verbatim during a grey
     * gr->xpan entry). The wash fixes manager.c but nothing feeds field o. "r" mode
     * repairs through the SAME door the native mode pushes use: s.r(xpanTable,size,true)
     * once per wash generation. The e0-based r454/r455 pushes went through a different
     * slot (HashMap u[1]) and are kept only as legacy diagnostics: "manager" pushes
     * manager.c via e0, "native" pushes the last natively captured e0(slot1) content.
     */
    private static void reuploadPreviewBasicToneIfWashed(Object canvas) {
        // r457: "r" is the default repair door (same s.r entry the native mode pushes
        // use). Set persist.omnicam.xpan.presetsync=off to disable, =manager/=native for
        // the legacy e0-based diagnostics.
        String mode = propertyStr("persist.omnicam.xpan.presetsync", "r");
        if ("off".equalsIgnoreCase(mode) || canvas == null) return;
        long wash = GrPreviewStreamRestore.paletteWashes.get();
        if (wash <= 0 || wash == previewRepairWash) return;
        try {
            if (isXpanLiveFrameFlowing()) {
                // Skip mid-capture: a stalled stream is worse than a grey preview; after the
                // capture the next frame still sees the stale generation and re-uploads.
                return;
            }
            float[] table;
            int size;
            boolean viaR = "r".equals(mode);
            if ("native".equals(mode)) {
                synchronized (previewNativeUploadLock) {
                    table = previewNativeUploadData;
                }
                size = table == null ? -1 : 64;
            } else {
                Object manager = XposedHelpers.getStaticObjectField(
                    installedManagerHolder, "a");
                size = XposedHelpers.getIntField(manager, "t");
                table = viaR
                    ? chooseXpanBasicToneTable(manager, size)
                    : (float[]) XposedHelpers.getObjectField(manager, "c");
            }
            if (size <= 0 || table == null || table.length == 0) return;
            long hash = tableContentHash(table);
            if (hash == previewRepairHash) {
                // Same content already pushed this visit; only consume the generation.
                previewRepairWash = wash;
                return;
            }
            if (viaR) {
                XposedHelpers.callMethod(canvas, "r", table, size, true);
            } else {
                XposedHelpers.callMethod(canvas, "e0", table, 1, size);
            }
            previewRepairWash = wash;
            previewRepairHash = hash;
            if (previewLutReuploadLogged < 8) {
                previewLutReuploadLogged++;
                Log.i(TAG, "XPAN preview BasicTone re-uploaded after wash"
                    + ", door=gl.s." + (viaR ? "r" : "e0")
                    + ", src=" + mode
                    + ", wash=" + wash
                    + ", canvas=" + System.identityHashCode(canvas)
                    + ", size=" + size
                    + ", dataLength=" + table.length
                    + ", hash=" + hash);
            }
        } catch (Throwable error) {
            // Consume the generation even on failure so one broken push cannot turn into
            // a per-frame retry storm; the next wash retries.
            previewRepairWash = wash;
            Log.e(TAG, "XPAN preview BasicTone re-upload failed", error);
        }
    }

    /*
     * r453: XPAN filter diagnostics. The live video BasicTone bridge always uploads the clean
     * XPAN table, so a selected filter never reaches the video (cover graded, video raw).
     * Before composing the filter into the video table the probe answers three questions:
     *  1) what does FilterPresenter.select deliver and does manager.c change afterwards --
     *     the native path may already compose filter x xpan into the BasicTone slot (r445
     *     log evidence: basicHash cb792680 -> a64c96c4 across a selection event, neither
     *     the clean XPAN -881252736 nor the palette table);
     *  2) does the 17^3 preview LUT callback (nn.l$a.b, size=17 floats=14739) carry a
     *     filter-specific table (dedup by data hash);
     *  3) does the cover path run FilterPresenter.captureRequest with a filter selected.
     */
    private static void hookXpanFilterProbe(ClassLoader classLoader) {
        local.jiege.hook.common.Symbols symbols = local.jiege.hook.common.Symbols.get();
        try {
            Method select = symbols.method("FilterPresenter.select");
            XposedBridge.hookMethod(select, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    filterSelectCalls++;
                    Log.i(TAG, "XPAN filter select #" + filterSelectCalls
                        + ", arg=" + (param.args.length > 0 ? param.args[0] : "?")
                        + ", currentIndexBefore=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Log.i(TAG, "XPAN filter selected #" + filterSelectCalls
                        + ", currentIndexAfter=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                    scheduleFilterManagerSnapshots(param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: select hook unavailable", error);
        }
        try {
            Class<?> callback = XposedHelpers.findClass("nn.l$a", classLoader);
            XposedBridge.hookAllMethods(callback, "b", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    if (param.args.length < 3 || !(param.args[0] instanceof float[])) return;
                    float[] data = (float[]) param.args[0];
                    int hash = Arrays.hashCode(data);
                    if (hash == filterProbeLutHash) return;
                    filterProbeLutHash = hash;
                    Log.i(TAG, "XPAN preview 17^3 LUT changed"
                        + ", index=" + param.args[1]
                        + ", size=" + param.args[2]
                        + ", floats=" + data.length
                        + ", hash=" + hash
                        + ", head=" + floatHead(data, 8)
                        + ", manager[" + managerBasicSnapshot() + "]");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: preview LUT callback hook unavailable", error);
        }
        try {
            Method captureRequest = symbols.method("FilterPresenter.captureRequest");
            XposedBridge.hookMethod(captureRequest, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Log.i(TAG, "XPAN filter captureRequest"
                        + ", currentIndex=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: captureRequest hook unavailable", error);
        }
        Log.i(TAG, "XPAN filter probe installed (r453 diagnostics)");
    }

    private static int currentIndex(Object presenter) {
        try {
            Object value = local.jiege.hook.common.Symbols.get()
                .call("FilterPresenter.currentIndex", presenter);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    /** r453: does the native path recompose manager.c for the selected filter? */
    private static void scheduleFilterManagerSnapshots(Object presenter) {
        for (long delay : new long[]{600, 1500, 3000}) {
            new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    Log.i(TAG, "XPAN filter manager snapshot +" + delay + "ms"
                        + ", currentIndex=" + currentIndex(presenter)
                        + ", manager[" + managerBasicSnapshot() + "]");
                } catch (Throwable ignored) {
                }
            }, "xpan-filter-snap").start();
        }
    }

    private static String floatHead(float[] data, int count) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < count && i < data.length; i++) {
            if (i > 0) text.append(',');
            text.append(String.format(java.util.Locale.US, "%.4f", data[i]));
        }
        return text.append("...]").toString();
    }

    private static String stackSnippet() {
        StringBuilder text = new StringBuilder(", caller=");
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int added = 0;
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.startsWith("local.jiege")
                || className.startsWith("de.robv")
                || className.startsWith("android.opengl")
                || className.startsWith("java.lang.reflect")
                || className.contains("XposedBridge")
                || className.contains("LSPosed")) {
                continue;
            }
            // r450: full class names -- short names are ambiguous across obfuscated packages.
            text.append(className).append('.').append(frame.getMethodName()).append(':')
                .append(frame.getLineNumber()).append(' ');
            if (++added >= 8) break;
        }
        return text.toString();
    }

    private static void hookXpanLiveBasicTone(ClassLoader classLoader) {
        final Class<?> glRenderer =
            XposedHelpers.findClass(GL_RENDERER, classLoader);
        final Class<?> shader =
            XposedHelpers.findClass(GL_SHADER, classLoader);
        final Class<?> managerHolder =
            XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);
        installedGlRenderer = glRenderer;
        installedManagerHolder = managerHolder;
        final Class<?> presenter =
            XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        final Class<?> liveWarpRenderer =
            XposedHelpers.findClass(LIVE_WARP_RENDERER, classLoader);
        final Class<?> videoImageData =
            XposedHelpers.findClass(VIDEO_IMAGE_DATA, classLoader);
        Method applyBasicTone = XposedHelpers.findMethodExact(
            glRenderer, "b", int.class, shader);

        /*
         * gh.p3.r(ImageData) has both a CommonShader fallback and a WarpShader branch. The
         * workaround below only affects code that consumes LutParameters; it does not choose
         * either renderer. Record the untouched entry state first so the next log can prove which
         * branch this frame actually used.
         */
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(VIDEO_EFFECT_PIPELINE, classLoader),
            "r",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1
                        || !videoImageData.isInstance(param.args[0])) {
                        return;
                    }
                    Object pipeline = param.thisObject;
                    beginMainFrame(param, pipeline, param.args[0]);
                    /*
                     * The watermark processor later re-composites this same frame from the raw
                     * input texture. Hand it the LutParameters that the graded draw is about to
                     * use, so the composite can restore BasicTone instead of emitting Log.
                     */
                    activePipeline = pipeline;
                    try {
                        activeLutParameters = XposedHelpers.getObjectField(pipeline, "o0");
                    } catch (Throwable ignored) {
                        activeLutParameters = null;
                    }
                    Object renderer = null;
                    boolean rendererPrepared = false;
                    int lutType = -1;
                    boolean storedOn = false;
                    try {
                        renderer = XposedHelpers.getObjectField(pipeline, "w");
                        lutType = XposedHelpers.getIntField(pipeline, "n0");
                        storedOn = isLivePhotoStoredOn(presenter);
                        rendererPrepared = renderer != null
                            && XposedHelpers.getBooleanField(renderer, "p");
                        if (!basicToneDirectProbeLogged) {
                            basicToneDirectProbeLogged = true;
                            Object parameters = XposedHelpers.getObjectField(pipeline, "o0");
                            Object lutManager = XposedHelpers.getStaticObjectField(
                                managerHolder, "a");
                            float[] basicToneData =
                                (float[]) XposedHelpers.getObjectField(lutManager, "c");
                            Log.i(TAG, "XPAN live BasicTone direct probe"
                                + ", mode=" + currentCameraMode
                                + ", storedOn=" + storedOn
                                + ", lutType=" + lutType
                                + ", renderer=" + (renderer == null
                                    ? "null" : renderer.getClass().getName())
                                + ", rendererPrepared=" + rendererPrepared
                                + ", rendererO=" + (renderer == null ? "?"
                                    : XposedHelpers.getBooleanField(renderer, "o"))
                                + ", rendererQ=" + (renderer == null ? "?"
                                    : XposedHelpers.getBooleanField(renderer, "q"))
                                + ", rendererRNull=" + (renderer == null ? "?"
                                    : XposedHelpers.getObjectField(renderer, "r") == null)
                                + ", rendererSNull=" + (renderer == null ? "?"
                                    : XposedHelpers.getObjectField(renderer, "s") == null)
                                + ", paramF=" + (parameters == null ? "?"
                                    : XposedHelpers.getBooleanField(parameters, "f"))
                                + ", paramG=" + (parameters == null ? "?"
                                    : XposedHelpers.getBooleanField(parameters, "g"))
                                + ", basicSize=" + XposedHelpers.getIntField(
                                    lutManager, "t")
                                + ", basicLength=" + (basicToneData == null
                                    ? -1 : basicToneData.length)
                                + ", basicHash=" + (basicToneData == null
                                    ? 0 : Arrays.hashCode(basicToneData))
                                + ", selectedSize=" + lastSelectedLutSize
                                + ", selectedHash=" + lastSelectedLutHash);
                        }
                    } catch (Throwable error) {
                        logBasicToneBindingError(error);
                    }
                    /*
                     * LivePhotoPresenter.p3() is a static UI preference read. On the GL worker
                     * it can report false even while XPAN is actively producing the attachment,
                     * so it is diagnostic only. ImageData plus the established XPAN mode scope
                     * already identify the exact pipeline this workaround owns.
                     */
                    if (!XPAN_MODE.equals(currentCameraMode)
                        || renderer == null) {
                        return;
                    }
                    if (!ensureXpanLiveBasicToneTexture(
                        renderer,
                        glRenderer,
                        managerHolder,
                        "direct attachment")) {
                        return;
                    }
                    /*
                     * a0.p selects the BasicTone fragment shader. XPAN's native lutType=1
                     * initialization leaves it false even though this attachment renderer can
                     * run the same stock BasicTone program used by ordinary live photos. Enable
                     * it and rebuild the shader on the GL thread before stock draw consumes
                     * LutParameters.g.
                     */
                    if (!rendererPrepared) {
                        XposedHelpers.setBooleanField(renderer, "p", true);
                        XposedHelpers.callMethod(renderer, "m");
                        if (!basicToneDirectShaderLogged) {
                            basicToneDirectShaderLogged = true;
                            Log.i(TAG, "XPAN live BasicTone direct shader prepared"
                                + ", mode=" + currentCameraMode
                                + ", renderer=" + renderer.getClass().getName());
                        }
                    }
                    param.setObjectExtra("xpanBasicToneLutType", lutType);
                    XposedHelpers.setIntField(pipeline, "n0", 7);

                    /*
                     * XPAN inherits the video HDR transform/GTM table even though this live-photo
                     * attachment is already shaded from the SDR BasicTone output. Applying that
                     * second transform compresses the attachment back to the flat Log-like look.
                     * Remove it only for this synchronous XPAN attachment draw, then restore the
                     * original ImageData before stock code can reuse it elsewhere.
                     */
                    Object eventTransform =
                        XposedHelpers.getObjectField(param.args[0], "i");
                    if (eventTransform != null) {
                        param.setObjectExtra(
                            "xpanLiveOriginalTransform", eventTransform);
                        XposedHelpers.setObjectField(param.args[0], "i", null);
                        if (!liveTransformBypassLogged) {
                            liveTransformBypassLogged = true;
                            Log.i(TAG, "XPAN live transform bypassed for attachment"
                                + ", mode=" + currentCameraMode);
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    finishMainFrame(param);
                    Object originalTransform =
                        param.getObjectExtra("xpanLiveOriginalTransform");
                    if (originalTransform != null) {
                        XposedHelpers.setObjectField(
                            param.args[0], "i", originalTransform);
                    }
                    Object original = param.getObjectExtra("xpanBasicToneLutType");
                    if (!(original instanceof Integer)) return;
                    Object pipeline = param.thisObject;
                    XposedHelpers.setIntField(
                        pipeline, "n0", ((Integer) original).intValue());
                    try {
                        Object renderer = XposedHelpers.getObjectField(pipeline, "w");
                        Object parameters = XposedHelpers.getObjectField(pipeline, "o0");
                        boolean enabled = parameters != null
                            && XposedHelpers.getBooleanField(parameters, "g");
                        if (!basicToneDirectFrameLogged) {
                            basicToneDirectFrameLogged = true;
                            Log.i(TAG, "XPAN live BasicTone direct attachment"
                                + ", enabled=" + enabled
                                + ", paramF=" + (parameters == null ? "?"
                                    : XposedHelpers.getBooleanField(parameters, "f"))
                                + ", rendererQ=" + XposedHelpers.getBooleanField(
                                    renderer, "q")
                                + ", rendererRNull="
                                + (XposedHelpers.getObjectField(renderer, "r") == null)
                                + ", rendererSNull="
                                + (XposedHelpers.getObjectField(renderer, "s") == null)
                                + ", originalLutType=" + original
                                + ", mode=" + currentCameraMode);
                        }
                    } catch (Throwable error) {
                        logBasicToneBindingError(error);
                    }
                }
            });

        /*
         * The lutType=1 initializer never reaches GLRenderer.b(), so the old fallback hook alone
         * cannot change the dynamic attachment. WarpRenderer.p() is the actual per-frame draw
         * path: set its LutParameters.g flag and create the stock BasicTone texture before stock
         * code consumes those values.
         */
        XposedBridge.hookAllMethods(liveWarpRenderer, "p", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 10) return;
                Object renderer = param.thisObject;
                Object lutParameters = param.args[8];
                if (renderer == null
                    || lutParameters == null
                    || !XPAN_MODE.equals(currentCameraMode)) {
                    return;
                }
                try {
                    if (!XposedHelpers.getBooleanField(renderer, "p")) return;
                    if (XposedHelpers.getBooleanField(lutParameters, "g")) {
                        /*
                         * r445: a true gate used to early-return here, which froze the
                         * BasicTone texture forever. After a master/GR visit the first
                         * draws race the two-step palette wash: the texture gets built
                         * from the pre-wash (poisoned) manager.c, the wash then publishes
                         * the clean XPAN table and bumps its generation, but the g=true
                         * early-return blocked the r421 rebuild check -- the preview kept
                         * sampling the stale developer and looked Log-grey (r423 measured
                         * exactly that signature). Rebuild the texture when stale or
                         * foreign even with the gate on.
                         */
                        rebuildStaleBasicToneTextureWithGateOn(
                            renderer, glRenderer, managerHolder);
                        return;
                    }
                    if (!ensureXpanLiveBasicToneTexture(
                        renderer,
                        glRenderer,
                        managerHolder,
                        "before draw")) {
                        return;
                    }
                    if (!XposedHelpers.getBooleanField(lutParameters, "g")) {
                        XposedHelpers.setBooleanField(lutParameters, "g", true);
                    }
                    if (!basicToneParametersLogged) {
                        basicToneParametersLogged = true;
                        Log.i(TAG, "XPAN live BasicTone parameters enabled"
                            + ", lutType=1, mode=" + currentCameraMode);
                    }
                } catch (Throwable error) {
                    logBasicToneBindingError(error);
                }
            }
        });

        /*
         * r208 proved that XPAN's main attachment frame enters a0.i(), the CommonShader draw
         * entry. r210 showed why binding BasicTone uniforms there had no visible effect:
         * ch.g.m() rebuilds only its WarpShader in field w, while inherited a0.i still draws
         * with common_shader in field g. Temporarily make a0.i consume the already compiled
         * stock WarpShader BasicTone program. Its vertex shader divides texcoords by a reference
         * size; this common quad passes normalized coordinates, so use a neutral 1x1 reference.
         * The original common program is restored immediately after the draw.
         */
        final Class<?> texture = XposedHelpers.findClass(
            "com.oplus.camera.common.gl.u0", classLoader);
        Method commonDraw = XposedHelpers.findMethodExact(
            glRenderer,
            "i",
            texture,
            int.class,
            int.class,
            int.class,
            int.class,
            int.class,
            float.class,
            float.class,
            boolean.class,
            boolean.class);
        XposedBridge.hookMethod(commonDraw, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object renderer = param.thisObject;
                if (renderer == null
                    || !LIVE_WARP_RENDERER.equals(renderer.getClass().getName())
                    || !XPAN_MODE.equals(currentCameraMode)
                    || XPAN_MAIN_FRAME.get() == null) {
                    return;
                }
                try {
                    if (!XposedHelpers.getBooleanField(renderer, "p")) return;
                    if (!ensureXpanLiveBasicToneTexture(
                        renderer,
                        glRenderer,
                        managerHolder,
                        "common draw")) {
                        return;
                    }
                    Object commonProgram =
                        XposedHelpers.getObjectField(renderer, "g");
                    Object program = XposedHelpers.getObjectField(renderer, "w");
                    if (program == null) return;
                    XposedHelpers.callMethod(program, "s");
                    XposedHelpers.callMethod(
                        program,
                        "q",
                        "uTexcoordsRefSize",
                        new float[]{1.0f, 1.0f});
                    XposedHelpers.callMethod(
                        program, "m", 0, "uNeedHdrTransform");
                    XposedHelpers.callMethod(program, "m", 0, "uVigSwitch");
                    /*
                     * y0.d() assigns and returns the sampler texture unit. The common draw
                     * later assigns its own source and ordinary-LUT units, so a hard-coded
                     * slot can be overwritten before glDrawArrays. Use the stock dynamic
                     * allocator exactly as WarpRenderer.p() does.
                     */
                    int basicSlot = (Integer) XposedHelpers.callMethod(
                        program, "d", "uBasicToneLut3dTexture");
                    XposedHelpers.callMethod(renderer, "b", basicSlot, program);
                    XposedHelpers.callMethod(program, "m", 1, "uNeedBasicTone");
                    XposedHelpers.setObjectField(renderer, "g", program);
                    param.setObjectExtra(
                        "xpanCommonOriginalProgram", commonProgram);
                    param.setObjectExtra("xpanCommonBasicToneProgram", program);
                    if (!commonBasicToneBridgeLogged) {
                        commonBasicToneBridgeLogged = true;
                        Log.i(TAG, "XPAN common renderer BasicTone uniforms bound"
                            + ", basicSlot=" + basicSlot
                            + ", shader=warp-basic"
                            + ", ordinaryLut=preserved"
                            + ", mode=" + currentCameraMode);
                    }
                } catch (Throwable error) {
                    logBasicToneBindingError(error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object program =
                    param.getObjectExtra("xpanCommonBasicToneProgram");
                if (program != null) {
                    try {
                        XposedHelpers.callMethod(program, "s");
                        XposedHelpers.callMethod(program, "m", 0, "uNeedBasicTone");
                        Object original =
                            param.getObjectExtra("xpanCommonOriginalProgram");
                        if (original != null) {
                            XposedHelpers.setObjectField(
                                param.thisObject, "g", original);
                        }
                    } catch (Throwable error) {
                        logBasicToneBindingError(error);
                    }
                }
                /*
                 * r446: preview-side heartbeat (the r445 one sat on ch.g.p, which never fires
                 * for XPAN preview draws -- a0.i common draw is the real entry). Reports the
                 * renderer's BasicTone texture ownership and wash generation so a grey preview
                 * can be attributed to a stale/foreign texture from the logs alone.
                 */
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                int calls = ++warpDrawProbeCalls;
                if (calls <= 3 || calls % 120 == 0) {
                    try {
                        Object renderer = param.thisObject;
                        Object existing = renderer == null
                            ? null : XposedHelpers.getObjectField(renderer, "s");
                        Integer rendererId = renderer == null
                            ? null : System.identityHashCode(renderer);
                        Object[] uploaded = rendererId == null
                            ? null : uploadsByRenderer.get(rendererId);
                        Log.i(TAG, "XPAN preview draw heartbeat"
                            + ", calls=" + calls
                            + ", renderer=" + rendererId
                            + ", tex=" + (existing == null ? "null"
                                : (hasBasicToneData(existing)
                                    ? (uploaded != null && existing == uploaded[1]
                                        ? "ours" : "foreign")
                                    : "invalid"))
                            + ", uploadedWash=" + (uploaded == null
                                ? "none" : uploaded[0])
                            + ", wash=" + GrPreviewStreamRestore.paletteWashes.get());
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        XposedBridge.hookMethod(applyBasicTone, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object renderer = param.thisObject;
                if (renderer == null
                    || !LIVE_WARP_RENDERER.equals(renderer.getClass().getName())
                    || !XPAN_MODE.equals(currentCameraMode)) {
                    return;
                }
                try {
                    if (XposedHelpers.getObjectField(renderer, "s") != null) return;
                    ensureXpanLiveBasicToneTexture(
                        renderer,
                        glRenderer,
                        managerHolder,
                        "apply fallback");
                } catch (Throwable error) {
                    logBasicToneBindingError(error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object renderer = param.thisObject;
                if (param.hasThrowable()
                    || renderer == null
                    || !LIVE_WARP_RENDERER.equals(renderer.getClass().getName())
                    || !XPAN_MODE.equals(currentCameraMode)
                    || basicToneBindingLogged) {
                    return;
                }
                try {
                    if (XposedHelpers.getObjectField(renderer, "s") == null) return;
                    if (!basicToneBindingLogged) {
                        basicToneBindingLogged = true;
                        Log.i(TAG, "XPAN live BasicTone bound, lutType=1"
                            + ", size=" + XposedHelpers.getIntField(
                                XposedHelpers.getStaticObjectField(managerHolder, "a"),
                                "t")
                            + ", mode=" + currentCameraMode);
                    }
                } catch (Throwable error) {
                    logBasicToneBindingError(error);
                }
            }
        });
        Log.i(TAG, "XPAN live BasicTone binding armed");
    }

    /**
     * With the photo watermark open the attachment turns Log-gray again even though the
     * BasicTone workaround above already graded it.
     *
     * <p>The encoder does not stop at the frame {@code gh.p3.r()} draws: when
     * {@code image_is_watermark_open} is set it also calls
     * {@code WatermarkProcessor.b(frame, imageData)}. That copies the <em>raw</em> input texture
     * into its own target ({@code gh.s4.g}) and draws it a second time, together with the
     * watermark bitmaps, through {@code ch.h.p(...)}. Stock hands that composite renderer a
     * {@code null} LutParameters, so {@code uNeedBasicTone} stays 0: the second pass repaints the
     * picture without the tone mapping that makes it look right, and the clip reads as Log again.
     *
     * <p>The composite is a complete draw of the same frame, so the fix is to let it grade exactly
     * like the first pass: while the processor is inside {@code b(...)}, {@code ch.h.p} gets the
     * {@link #activeLutParameters} instance the graded draw already used. BasicTone, its vignette
     * table and the ordinary look LUT then land on the composite as well.
     * Property {@code persist.omnicam.xpan.livewm=0} restores the stock behaviour.
     */
    private static void hookXpanLiveWatermarkCopy(ClassLoader classLoader) {
        final Class<?> processor =
            XposedHelpers.findClass(WATERMARK_PROCESSOR, classLoader);
        final Class<?> composite =
            XposedHelpers.findClass(WATERMARK_COMPOSITE, classLoader);
        final Class<?> lutParameters =
            XposedHelpers.findClass(LUT_PARAMETERS, classLoader);
        XposedBridge.hookAllMethods(processor, "b", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 2
                    || !XPAN_MODE.equals(currentCameraMode)
                    || property(XPAN_LIVE_WATERMARK_PROPERTY, 1) == 0) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame == null || !frame.watermarkOpen) return;
                WATERMARK_COMPOSITE_ACTIVE.set(Boolean.TRUE);
                if (!watermarkCopyLogged) {
                    watermarkCopyLogged = true;
                    Object renderer = null;
                    try {
                        renderer = XposedHelpers.getObjectField(
                            param.thisObject, WATERMARK_PROCESSOR_RENDERER);
                    } catch (Throwable ignored) {
                        // Diagnostics only.
                    }
                    Log.i(TAG, "XPAN live watermark composite armed"
                        + ", renderer=" + (renderer == null
                            ? "null" : renderer.getClass().getName())
                        + ", rendererLut=" + (renderer == null ? "?"
                            : XposedHelpers.getBooleanField(renderer, "q"))
                        + ", gradedParams=" + (activeLutParameters == null
                            ? "null" : "present")
                        + ", mode=" + currentCameraMode);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                WATERMARK_COMPOSITE_ACTIVE.remove();
            }
        });
        XposedBridge.hookAllMethods(composite, "p", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 9
                    || !Boolean.TRUE.equals(WATERMARK_COMPOSITE_ACTIVE.get())
                    || param.method == null
                    || !WATERMARK_COMPOSITE.equals(
                        param.method.getDeclaringClass().getName())) {
                    return;
                }
                try {
                    Object graded = activeLutParameters;
                    if (graded == null || !lutParameters.isInstance(graded)) return;
                    Object stock = param.args[7];
                    if (stock == graded) return;
                    /*
                     * The XPAN LutParameters carries no BasicTone texture of its own: the graded
                     * draw gets one from the renderer field the workaround above fills in. Hand
                     * the same texture to the composite, otherwise uNeedBasicTone=1 samples an
                     * unbound unit and the frame comes out white.
                     *
                     * p3.E() stores that renderer texture in LutParameters.e for the sub-video
                     * path. The field is not cleared when the renderer is destroyed, however, so
                     * the next shot can see a non-null Raw3dTexture whose internal float[] data
                     * has already been released by t0.l(). Passing that shell to t0.k() crashes
                     * at FloatBuffer.wrap(null). Prefer this frame's live renderer texture and
                     * only accept a parameter texture whose backing table is still complete.
                     */
                    Object previousBasicTexture =
                        XposedHelpers.getObjectField(graded, "e");
                    Object rendererBasicTexture = rendererBasicToneTexture();
                    Object basicTexture = hasBasicToneData(rendererBasicTexture)
                        ? rendererBasicTexture : previousBasicTexture;
                    boolean rendererTextureUsable = hasBasicToneData(rendererBasicTexture);
                    boolean parameterTextureUsable = hasBasicToneData(previousBasicTexture);
                    if (!hasBasicToneData(basicTexture)) basicTexture = null;
                    if (basicTexture == null) {
                        if (!watermarkCompositeSkippedLogged) {
                            watermarkCompositeSkippedLogged = true;
                            Log.i(TAG, "XPAN watermark composite left stock, no valid"
                                + " BasicTone texture"
                                + ", rendererValid=" + rendererTextureUsable
                                + ", parameterValid=" + parameterTextureUsable
                                + ", mode=" + currentCameraMode);
                        }
                        return;
                    }
                    if (watermarkCompositeProbe < 40) {
                        watermarkCompositeProbe++;
                        Log.i(TAG, "XPAN watermark composite #" + watermarkCompositeProbe
                            + ", stock=" + (stock == null ? "null" : "present")
                            + ", basicTexture=" + (basicTexture == rendererBasicTexture
                                ? "renderer" : "parameter")
                            + ", rendererValid=" + rendererTextureUsable
                            + ", parameterValid=" + parameterTextureUsable
                            + ", g=" + XposedHelpers.getBooleanField(graded, "g")
                            + ", vigSize=" + XposedHelpers.getIntField(graded, "d")
                            + ", lutSize=" + XposedHelpers.getFloatField(graded, "a")
                            + ", avgLuma=" + XposedHelpers.getFloatField(graded, "b")
                            + ", frameDraws=" + (XPAN_MAIN_FRAME.get() == null
                                ? -1 : XPAN_MAIN_FRAME.get().commonDraws));
                    }
                    if (previousBasicTexture != basicTexture) {
                        if (!rendererTextureUsable && parameterTextureUsable
                            && !watermarkCompositeStaleTextureLogged) {
                            watermarkCompositeStaleTextureLogged = true;
                            Log.i(TAG, "XPAN watermark composite kept parameter BasicTone"
                                + " texture because renderer texture is invalid"
                                + ", mode=" + currentCameraMode);
                        } else if (previousBasicTexture != null
                            && !parameterTextureUsable
                            && !watermarkCompositeStaleTextureLogged) {
                            watermarkCompositeStaleTextureLogged = true;
                            Log.i(TAG, "XPAN watermark composite replaced stale BasicTone"
                                + " texture with this frame's valid texture"
                                + ", mode=" + currentCameraMode);
                        }
                        XposedHelpers.setObjectField(graded, "e", basicTexture);
                        param.setObjectExtra(
                            "xpanCompositePreviousBasicTone", previousBasicTexture);
                        param.setObjectExtra(
                            "xpanCompositeChangedBasicTone", Boolean.TRUE);
                    }
                    param.args[7] = graded;
                    if (!watermarkCompositeLogged) {
                        watermarkCompositeLogged = true;
                        Log.i(TAG, "XPAN watermark composite regraded with the frame's"
                            + " LutParameters, stock=" + (stock == null ? "null" : "present")
                            + ", paramsG=" + XposedHelpers.getBooleanField(graded, "g")
                            + ", rendererLut=" + (param.thisObject == null ? "?"
                                : XposedHelpers.getBooleanField(param.thisObject, "q"))
                            + ", mode=" + currentCameraMode);
                    }
                } catch (Throwable error) {
                    logWatermarkCopyError(error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(
                    param.getObjectExtra("xpanCompositeChangedBasicTone"))) {
                    return;
                }
                try {
                    Object graded = param.args[7];
                    if (graded != null) {
                        XposedHelpers.setObjectField(
                            graded,
                            "e",
                            param.getObjectExtra("xpanCompositePreviousBasicTone"));
                    }
                } catch (Throwable error) {
                    logWatermarkCopyError(error);
                }
            }
        });
        Log.i(TAG, "XPAN live watermark copy armed");
    }

    /** The BasicTone texture the graded draw is using, or null while it is not built yet. */
    private static Object rendererBasicToneTexture() {
        Object pipeline = activePipeline;
        if (pipeline == null) return null;
        try {
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            return renderer == null ? null : XposedHelpers.getObjectField(renderer, "s");
        } catch (Throwable error) {
            return null;
        }
    }

    /** A Raw3dTexture is usable only while its complete packed RGB table is still allocated. */
    private static boolean hasBasicToneData(Object texture) {
        if (texture == null) return false;
        try {
            if (!RAW_3D_TEXTURE.equals(texture.getClass().getName())) return false;
            float[] data = (float[]) XposedHelpers.getObjectField(texture, "A");
            int size = XposedHelpers.getIntField(texture, "z");
            int width = XposedHelpers.getIntField(texture, "a");
            int height = XposedHelpers.getIntField(texture, "b");
            if (data == null || size <= 0 || width != size || height != size) return false;
            long expected = (long) size * size * size * 3L;
            return data.length == expected;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logWatermarkCopyError(Throwable error) {
        if (watermarkCopyErrorLogged) return;
        watermarkCopyErrorLogged = true;
        Log.e(TAG, "XPAN live watermark copy failed", error);
    }

    private static void hookXpanLiveLutDiagnostics(ClassLoader classLoader) {
        final Class<?> glRenderer =
            XposedHelpers.findClass(GL_RENDERER, classLoader);
        final Class<?> shader =
            XposedHelpers.findClass(GL_SHADER, classLoader);
        final Class<?> presenter =
            XposedHelpers.findClass(LIVE_PHOTO_PRESENTER, classLoader);
        final Class<?> liveWarpRenderer =
            XposedHelpers.findClass(LIVE_WARP_RENDERER, classLoader);
        final Class<?> pipeline =
            XposedHelpers.findClass(VIDEO_EFFECT_PIPELINE, classLoader);

        /*
         * p3.r() has two mutually exclusive main-video draw calls. Instrument both entry points
         * independently of the BasicTone workaround so the frame log records the path actually
         * used by this version instead of inferring it from texture state.
         */
        XposedBridge.hookAllMethods(glRenderer, "i", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 10
                    || param.thisObject == null
                    || !LIVE_WARP_RENDERER.equals(
                        param.thisObject.getClass().getName())) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame == null) return;
                frame.commonDraws++;
                frame.drawTargetId = param.args[0] == null
                    ? 0 : System.identityHashCode(param.args[0]);
                pushRenderStage(param, "common");
                if (!commonDrawSignatureLogged) {
                    commonDrawSignatureLogged = true;
                    Log.i(TAG, "XPAN common draw observed"
                        + ", args=" + param.args.length
                        + ", renderer=" + param.thisObject.getClass().getName()
                        + ", output=" + param.args[4] + "x" + param.args[5]
                        + ", mode=" + currentCameraMode);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                popRenderStage(param);
            }
        });

        /*
         * XPAN copies EventMessagePreviewData.k into ImageData.i. That object is later uploaded
         * as uTransformTexture by WarpRenderer. Capture both sides before changing any behaviour,
         * because a valid BasicTone table alone cannot prove that HDR transform/GTM is correct.
         */
        XposedBridge.hookAllMethods(presenter, "Z2", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)) {
                    return;
                }
                Object result = param.getResult();
                if (!transformCaptureCallLogged) {
                    transformCaptureCallLogged = true;
                    Object converter = safeObjectField(param.thisObject, "n1");
                    Log.i(TAG, "XPAN transform capture method observed"
                        + ", args=" + param.args.length
                        + ", throwable=" + param.hasThrowable()
                        + ", result=" + (result == null
                            ? "null" : result.getClass().getName())
                        + ", converter=" + (converter == null
                            ? "null" : Integer.toHexString(
                                System.identityHashCode(converter)))
                        + ", mode=" + currentCameraMode);
                }
                if (liveTransformCaptureProbeLogged
                    || param.args.length != 7
                    || param.hasThrowable()) {
                    return;
                }
                if (result == null
                    || !VIDEO_IMAGE_DATA.equals(result.getClass().getName())) {
                    return;
                }
                liveTransformCaptureProbeLogged = true;
                try {
                    Object event = param.args[0];
                    Object eventTransform = event == null
                        ? null : XposedHelpers.getObjectField(event, "k");
                    Object imageTransform =
                        XposedHelpers.getObjectField(result, "i");
                    Log.i(TAG, "XPAN live transform capture probe"
                        + ", mode=" + currentCameraMode
                        + ", " + describeTransform("eventK", eventTransform)
                        + ", " + describeTransform("imageI", imageTransform)
                        + ", sameObject=" + (eventTransform == imageTransform));
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN live transform capture probe failed", error);
                }
            }
        });

        Method applyOrdinaryLut = XposedHelpers.findMethodExact(
            glRenderer, "a", int.class, shader);
        XposedBridge.hookMethod(applyOrdinaryLut, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || param.thisObject == null
                    || !LIVE_WARP_RENDERER.equals(param.thisObject.getClass().getName())) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame != null) recordOrdinaryLutBind(frame);
                if (ordinaryLutBindLogged) return;
                ordinaryLutBindLogged = true;
                try {
                    Object renderer = param.thisObject;
                    boolean enabled = XposedHelpers.getBooleanField(renderer, "q");
                    boolean textureNull =
                        XposedHelpers.getObjectField(renderer, "r") == null;
                    Log.i(TAG, "XPAN ordinary LUT bind probe"
                        + ", enabled=" + enabled
                        + ", textureNull=" + textureNull
                        + ", selectedSize=" + lastSelectedLutSize
                        + ", selectedHash=" + lastSelectedLutHash
                        + ", shaderSlot=" + param.args[0]
                        + ", mode=" + currentCameraMode);
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN ordinary LUT bind probe failed", error);
                }
            }
        });

        Method applyBasicTone = XposedHelpers.findMethodExact(
            glRenderer, "b", int.class, shader);
        XposedBridge.hookMethod(applyBasicTone, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || param.thisObject == null
                    || !LIVE_WARP_RENDERER.equals(param.thisObject.getClass().getName())) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame != null) recordBasicToneBind(frame);
                if (basicToneBindLogged) return;
                basicToneBindLogged = true;
                try {
                    Object renderer = param.thisObject;
                    Log.i(TAG, "XPAN BasicTone bind probe"
                        + ", textureNull="
                        + (XposedHelpers.getObjectField(renderer, "s") == null)
                        + ", shaderSlot=" + param.args[0]
                        + ", mode=" + currentCameraMode);
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN BasicTone bind probe failed", error);
                }
            }
        });

        XposedBridge.hookAllMethods(liveWarpRenderer, "p", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 10
                    || !XPAN_MODE.equals(currentCameraMode)) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame != null) {
                    frame.warpDraws++;
                    pushRenderStage(param, "warp");
                }
                if (liveTransformDrawProbeLogged) return;
                liveTransformDrawProbeLogged = true;
                try {
                    Object renderer = param.thisObject;
                    Object lutParameters = param.args[8];
                    Object transform = param.args[9];
                    Log.i(TAG, "XPAN live transform draw probe"
                        + ", mode=" + currentCameraMode
                        + ", outOfScreen=" + param.args[7]
                        + ", basicEnabled=" + (lutParameters == null
                            ? "?" : XposedHelpers.getBooleanField(lutParameters, "g"))
                        + ", ordinaryEnabled=" + (lutParameters == null
                            ? "?" : XposedHelpers.getBooleanField(lutParameters, "f"))
                        + ", rendererPrepared="
                        + XposedHelpers.getBooleanField(renderer, "p")
                        + ", basicTextureNull="
                        + (XposedHelpers.getObjectField(renderer, "s") == null)
                        + ", ordinaryTextureNull="
                        + (XposedHelpers.getObjectField(renderer, "r") == null)
                        + ", " + describeTransform("draw", transform));
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN live transform draw probe failed", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                popRenderStage(param);
            }
        });

        XposedBridge.hookAllMethods(glRenderer, "o", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 5
                    || param.thisObject == null
                    || !LIVE_WARP_RENDERER.equals(
                        param.thisObject.getClass().getName())
                    || !XPAN_MODE.equals(currentCameraMode)) {
                    return;
                }
                XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                if (frame != null) recordTransformUpload(frame);
                if (liveTransformUploadLogged) return;
                liveTransformUploadLogged = true;
                try {
                    float[] main = (float[]) param.args[2];
                    float[] gtm = (float[]) param.args[3];
                    Log.i(TAG, "XPAN live transform upload probe"
                        + ", mode=" + currentCameraMode
                        + ", shaderSlot=" + param.args[1]
                        + ", mainLength=" + (main == null ? -1 : main.length)
                        + ", mainHash=" + (main == null
                            ? 0 : Arrays.hashCode(main))
                        + ", gtmLength=" + (gtm == null ? -1 : gtm.length)
                        + ", gtmHash=" + (gtm == null ? 0 : Arrays.hashCode(gtm))
                        + ", gtmRequested=" + param.args[4]);
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN live transform upload probe failed", error);
                }
            }
        });

        final Class<?> callback =
            XposedHelpers.findClass(VIDEO_EFFECT_PIPELINE + "$a", classLoader);
        Method onLutReady = XposedHelpers.findMethodExact(
            callback, "b", float[].class, int.class, int.class);
        XposedBridge.hookMethod(onLutReady, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                float[] data = (float[]) param.args[0];
                int size = (Integer) param.args[2];
                lastSelectedLutSize = size;
                lastSelectedLutHash = data == null ? 0 : Arrays.hashCode(data);
                if (selectedLutCallbackLogged) return;
                selectedLutCallbackLogged = true;
                Log.i(TAG, "XPAN selected LUT callback"
                    + ", size=" + size
                    + ", dataLength=" + (data == null ? -1 : data.length)
                    + ", dataHash=" + lastSelectedLutHash
                    + ", callbackType=" + param.args[1]
                    + ", mode=" + currentCameraMode);
            }
        });

        Log.i(TAG, "XPAN live LUT diagnostics armed");
    }

    /**
     * XPAN's still cover is SDR, while the stock dynamic attachment is configured as
     * BT.2020 / HLG. The encoder receives the surface DataSpace independently of the
     * {@link MediaFormat}, so both have to be corrected to avoid an HLG/SDR luminance mismatch
     * during gallery playback.
     */
    private static void hookXpanLiveSdrOutput(ClassLoader classLoader) {
        if (property(XPAN_LIVE_SDR_OUTPUT_PROPERTY, 0) != 1) {
            Log.i(TAG, "XPAN live SDR output correction off (stock HLG end to end)"
                + ", enable with persist.omnicam.xpan.sdrout=1");
            return;
        }
        final Class<?> encoder = XposedHelpers.findClass(LIVE_VIDEO_ENCODER, classLoader);
        XposedBridge.hookAllMethods(encoder, "q8", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 2
                    || !(param.args[1] instanceof MediaFormat)) {
                    return;
                }
                MediaFormat format = (MediaFormat) param.args[1];
                if (!XPAN_MODE.equals(currentCameraMode)
                    && !isXpanLiveFormat(format)) {
                    return;
                }
                try {
                    int oldStandard = format.getInteger(
                        MediaFormat.KEY_COLOR_STANDARD, Integer.MIN_VALUE);
                    int oldTransfer = format.getInteger(
                        MediaFormat.KEY_COLOR_TRANSFER, Integer.MIN_VALUE);
                    int oldRange = format.getInteger(
                        MediaFormat.KEY_COLOR_RANGE, Integer.MIN_VALUE);
                    format.setInteger(
                        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                    format.setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                    format.setInteger(
                        MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                    xpanLiveSdrArmed = true;
                    if (!xpanLiveSdrFormatLogged) {
                        xpanLiveSdrFormatLogged = true;
                        Log.i(TAG, "XPAN live SDR format corrected"
                            + ", mime=" + format.getString(MediaFormat.KEY_MIME)
                            + ", size=" + format.getInteger(MediaFormat.KEY_WIDTH)
                            + "x" + format.getInteger(MediaFormat.KEY_HEIGHT)
                            + ", color=" + oldRange + "/" + oldStandard + "/" + oldTransfer
                            + " -> " + MediaFormat.COLOR_RANGE_LIMITED
                            + "/" + MediaFormat.COLOR_STANDARD_BT709
                            + "/" + MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN live SDR format correction failed", error);
                }
            }
        });

        final Class<?> eglHelper = XposedHelpers.findClass(EGL_HELPER, classLoader);
        XposedBridge.hookAllMethods(eglHelper, "b", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 5
                    || !(param.args[0] instanceof Surface)
                    || !(param.args[3] instanceof Size)
                    || !(param.args[4] instanceof Integer)) {
                    return;
                }
                int oldDataSpace = (Integer) param.args[4];
                if (oldDataSpace != DATASPACE_BT2020_HLG_LIMITED) return;
                Size size = (Size) param.args[3];
                if (!XPAN_MODE.equals(currentCameraMode)
                    && !xpanLiveSdrArmed
                    && !isXpanLiveSize(size)) {
                    return;
                }
                param.args[4] = DATASPACE_BT709_LIMITED;
                if (!xpanLiveSdrSurfaceLogged) {
                    xpanLiveSdrSurfaceLogged = true;
                    Log.i(TAG, "XPAN live SDR surface corrected, dataSpace=0x"
                        + Integer.toHexString(oldDataSpace) + " -> 0x"
                        + Integer.toHexString(DATASPACE_BT709_LIMITED)
                        + ", size=" + size.getWidth() + "x" + size.getHeight()
                        + ", mode=" + currentCameraMode);
                }
            }
        });

        Log.i(TAG, "XPAN live SDR output correction armed");
    }

    private static boolean isXpanLiveFormat(MediaFormat format) {
        try {
            return isXpanLiveSize(new Size(
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isXpanLiveSize(Size size) {
        return size != null
            && size.getWidth() == XPAN_LIVE_WIDTH
            && size.getHeight() == XPAN_LIVE_HEIGHT;
    }

    private static String describeTransform(String label, Object transform) {
        if (transform == null) {
            return label + "=null";
        }
        try {
            float[] main =
                (float[]) XposedHelpers.getObjectField(transform, "a");
            float[] color =
                (float[]) XposedHelpers.getObjectField(transform, "c");
            float[] gtm =
                (float[]) XposedHelpers.getObjectField(transform, "d");
            return label + "={valid="
                + XposedHelpers.getBooleanField(transform, "b")
                + ",gtmEnabled="
                + XposedHelpers.getBooleanField(transform, "e")
                + ",mainLength=" + (main == null ? -1 : main.length)
                + ",mainHash=" + (main == null ? 0 : Arrays.hashCode(main))
                + ",colorLength=" + (color == null ? -1 : color.length)
                + ",colorHash=" + (color == null ? 0 : Arrays.hashCode(color))
                + ",gtmLength=" + (gtm == null ? -1 : gtm.length)
                + ",gtmHash=" + (gtm == null ? 0 : Arrays.hashCode(gtm))
                + "}";
        } catch (Throwable error) {
            return label + "={probeError=" + error + "}";
        }
    }

    /**
     * r445: on the g=true fast path, rebuild the renderer's BasicTone texture when it is stale
     * (a palette wash happened after the last upload) or foreign (stock filled renderer.s behind
     * our back). Only renderer.s is touched -- the gate flag and sampler binding stay exactly as
     * stock left them. A failed attempt (e.g. no table data yet) latches on
     * (wash generation, texture identity) so a persistent failure cannot turn into a per-frame
     * GL upload storm.
     */
    private static void rebuildStaleBasicToneTextureWithGateOn(
        Object renderer,
        Class<?> glRenderer,
        Class<?> managerHolder
    ) {
        if (glRenderer == null || managerHolder == null || renderer == null) return;
        Object existing = XposedHelpers.getObjectField(renderer, "s");
        boolean existingValid = existing != null && hasBasicToneData(existing);
        if (!existingValid) return;
        Integer rendererId = System.identityHashCode(renderer);
        Object[] uploaded = uploadsByRenderer.get(rendererId);
        long wash = GrPreviewStreamRestore.paletteWashes.get();
        boolean staleByWash = uploaded == null || (Long) uploaded[0] != wash;
        boolean staleByForeign = uploaded == null || existing != uploaded[1];
        if (!staleByWash && !staleByForeign) return;
        long key = (rendererId.hashCode() * 31 + Long.hashCode(wash)) * 31
            ^ System.identityHashCode(existing);
        if (key == staleRebuildFailedKey) return;
        if (!ensureXpanLiveBasicToneTexture(
            renderer, glRenderer, managerHolder, "gate-on stale rebuild")) {
            staleRebuildFailedKey = key;
        }
    }

    private static boolean ensureXpanLiveBasicToneTexture(
        Object renderer,
        Class<?> glRenderer,
        Class<?> managerHolder,
        String source) {
        Object existing = XposedHelpers.getObjectField(renderer, "s");
        Object manager = XposedHelpers.getStaticObjectField(managerHolder, "a");
        int lutSize = XposedHelpers.getIntField(manager, "t");
        float[] lutData = chooseXpanBasicToneTable(manager, lutSize);
        long wash = GrPreviewStreamRestore.paletteWashes.get();
        boolean existingValid = existing != null && hasBasicToneData(existing);
        Integer rendererId = System.identityHashCode(renderer);
        Object[] uploaded = uploadsByRenderer.get(rendererId);
        // r421 + r446: a wash of the palette table invalidates any texture snapshot built
        // before it -- tracked PER RENDERER (see uploadsByRenderer note).
        boolean staleByWash = uploaded == null || (Long) uploaded[0] != wash;
        /*
         * r424 + r446: stock's async LUT task fills renderer.s with whatever table the
         * gh.p3$a callback delivered -- after a GR/master exit that is the generic
         * color-space table (169adb06), and every XPAN live frame bound to BasicTone then
         * samples the wrong developer, turning the whole attachment green (r423) or grey.
         * The r423 logs also proved stock never replaces an already-built renderer.s, so a
         * texture whose reference this module did not upload to THIS renderer is foreign
         * content: re-upload the verified table instead of trusting it.
         */
        boolean staleByForeign = uploaded == null
            ? existingValid
            : existing != uploaded[1];
        if (existingValid && !staleByWash && !staleByForeign) return true;
        if (lutSize <= 0 || lutData == null || lutData.length == 0) {
            if (!basicToneTextureUnavailableLogged) {
                basicToneTextureUnavailableLogged = true;
                Log.i(TAG, "XPAN live BasicTone texture data unavailable"
                    + ", source=" + source
                    + ", existing=" + (existing == null ? "null" : "invalid")
                    + ", size=" + lutSize
                    + ", dataLength=" + (lutData == null ? -1 : lutData.length));
            }
            return existingValid;
        }
        if (existing != null && !basicToneTextureUnavailableLogged && staleByWash) {
            Log.i(TAG, "XPAN live BasicTone texture rebuilt after palette wash"
                + ", source=" + source
                + ", wash=" + wash
                + ", renderer=" + rendererId
                + (uploaded == null ? ", no prior upload"
                    : ", builtAtWash=" + uploaded[0]));
        } else if (existing != null && !basicToneTextureUnavailableLogged && staleByForeign) {
            Log.i(TAG, "XPAN live BasicTone texture re-uploaded over foreign texture"
                + ", source=" + source
                + ", wash=" + wash
                + ", renderer=" + rendererId
                + (uploaded == null ? ", no prior upload"
                    : ", builtAtWash=" + uploaded[0]));
        } else if (existing != null && !basicToneTextureUnavailableLogged) {
            basicToneTextureUnavailableLogged = true;
            Log.i(TAG, "XPAN live BasicTone texture data lost, rebuilding"
                + ", source=" + source
                + ", size=" + lutSize
                + ", dataLength=" + lutData.length);
        }

        Object texture = XposedHelpers.callStaticMethod(
            glRenderer, "g", existing, lutSize, lutData);
        if (texture == null) {
            if (!basicToneTextureUnavailableLogged) {
                basicToneTextureUnavailableLogged = true;
                Log.i(TAG, "XPAN live BasicTone texture creation returned null"
                    + ", source=" + source
                    + ", size=" + lutSize
                    + ", dataLength=" + lutData.length);
            }
            return false;
        }
        XposedHelpers.setObjectField(renderer, "s", texture);
        uploadsByRenderer.put(
            rendererId, new Object[]{wash, texture});
        if (basicToneTextureUploadLogs < 8) {
            basicToneTextureUploadLogs++;
            Log.i(TAG, "XPAN live BasicTone texture uploaded"
                + ", lutType=1"
                + ", size=" + lutSize
                + ", dataLength=" + lutData.length
                + ", source=" + source
                + ", renderer=" + rendererId
                + ", wash=" + wash);
        }
        return true;
    }

    /**
     * r424: the re-armed manager.c is natively recomputed in place and flips between the
     * verified clean-env XPAN table and the GR/master-poisoned one, so prefer the defensive
     * copy published by the re-arm thread; only fall back to a live manager.c read when the
     * copy has not been published yet (no completed re-arm in this process).
     */
    private static float[] chooseXpanBasicToneTable(Object manager, int lutSize) {
        float[] clean = GrPreviewStreamRestore.cleanXpanTable;
        if (clean != null
            && clean.length == lutSize * lutSize * lutSize * 3
            && GrPreviewStreamRestore.cleanXpanTableSize == lutSize) {
            return clean;
        }
        return (float[]) XposedHelpers.getObjectField(manager, "c");
    }

    /**
     * r424: stock's async LUT task (gh.p3$a delivery) fills a null renderer.s with the table
     * the callback carried -- after a GR/master exit that is the generic color-space table,
     * and every XPAN live frame bound to BasicTone samples it, turning the attachment
     * all-green. r423 logs proved stock leaves an already-built renderer.s untouched, so
     * pre-seeding the verified clean-env XPAN table right before the task runs keeps the
     * stock filler out entirely. Runs on the GL thread from the lut task hook.
     */
    public static void preSeedXpanBasicToneTexture(Object pipeline) {
        if (!XPAN_MODE.equals(currentCameraMode)) return;
        if (pipeline == null) return;
        try {
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            if (renderer == null) return;
            if (XposedHelpers.getObjectField(renderer, "s") != null) return;
            Class<?> glRenderer = installedGlRenderer;
            Class<?> managerHolder = installedManagerHolder;
            if (glRenderer == null || managerHolder == null) return;
            if (ensureXpanLiveBasicToneTexture(
                renderer, glRenderer, managerHolder, "pre-seed before stock task")) {
                Log.i(TAG, "XPAN live BasicTone texture pre-seeded before stock task"
                    + ", mode=" + currentCameraMode);
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live BasicTone pre-seed failed", error);
        }
    }

    private static void logBasicToneBindingError(Throwable error) {
        if (basicToneBindingErrorLogged) return;
        basicToneBindingErrorLogged = true;
        Log.e(TAG, "XPAN live BasicTone binding failed", error);
    }

    private static Object findCaptureResultKey(Class<?> metadata, String name) {
        for (Field field : metadata.getDeclaredFields()) {
            if (!CaptureResult.Key.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object key = field.get(null);
                if (key instanceof CaptureResult.Key
                    && name.equals(((CaptureResult.Key<?>) key).getName())) {
                    return key;
                }
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("CaptureResult.Key not found: " + name);
    }

    private static int[] syntheticGyro(Object imageData) {
        Object captureResult = XposedHelpers.getObjectField(imageData, "a");
        if (captureResult == null) {
            throw new IllegalStateException("live-photo ImageData has no capture result");
        }
        Object value = XposedHelpers.callMethod(captureResult, "getTimestamp");
        if (!(value instanceof Number)) {
            throw new IllegalStateException("live-photo capture result has no timestamp");
        }
        long timestamp = ((Number) value).longValue();
        if (timestamp <= 0) {
            throw new IllegalStateException("invalid live-photo timestamp: " + timestamp);
        }

        int[] gyro = new int[10];
        gyro[0] = 1;  // active sample count
        gyro[1] = 0;
        gyro[2] = lowWord(timestamp);
        gyro[3] = highWord(timestamp);
        gyro[4] = 0;  // x
        gyro[5] = 0;  // y
        gyro[6] = 0;  // z
        gyro[7] = 0;
        gyro[8] = lowWord(timestamp);
        gyro[9] = highWord(timestamp);
        return gyro;
    }

    private static int lowWord(long value) {
        return (int) value;
    }

    private static int highWord(long value) {
        return (int) (value >>> 32);
    }

    private static long combineTimestamp(int low, int high) {
        return (((long) low) & 0xffffffffL)
            | ((((long) high) & 0xffffffffL) << 32);
    }

    /**
     * XPAN exposes the original live-photo presenter, but its top-left menu is built from a
     * different option list and the stock menu manager can subsequently hide the item as GONE.
     * Keep the original menu object and let its normal click/state logic run; only make sure the
     * XPAN instance is present and visible.
     */
    private static void hookMenuVisibility(ClassLoader classLoader) throws Throwable {
        Class<?> containerClass = XposedHelpers.findClass(MENU_CONTAINER_BASE, classLoader);
        Class<?> optionClass = XposedHelpers.findClass(MENU_OPTION_BASE, classLoader);
        Class<?> topOptionClass = XposedHelpers.findClass(MENU_OPTION_TOP, classLoader);
        Class<?> settingTopClass = XposedHelpers.findClass(CAMERA_SETTING_UI_TOP, classLoader);

        XposedBridge.hookAllMethods(containerClass, "B", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isXpanMenuContainer(param.thisObject)
                    || param.args.length < 1
                    || !(param.args[0] instanceof List)) {
                    return;
                }
                List<?> requested = (List<?>) param.args[0];
                if (requested.contains(LIVE_PHOTO_SUPPORT)) return;

                ArrayList<String> restored = new ArrayList<>(requested.size() + 1);
                for (Object key : requested) {
                    if (key instanceof String) restored.add((String) key);
                }
                restored.add(LIVE_PHOTO_SUPPORT);
                param.args[0] = restored;
                if (!menuInjectionLogged) {
                    menuInjectionLogged = true;
                    Log.i(TAG, "XPAN top menu list injected " + LIVE_PHOTO_SUPPORT
                        + " (" + requested.size() + " -> " + restored.size() + ")");
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!isXpanMenuContainer(param.thisObject)) return;
                restoreXpanLivePhotoMenu(param.thisObject, "container-build");
            }
        });

        XposedBridge.hookAllMethods(containerClass, "n", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 1
                    || !isXpanLivePhotoMenuOption(param.args[0])
                    || !isXpanMenuContainer(param.thisObject)) {
                    return;
                }
                restoreXpanLivePhotoOption(param.args[0], "container-add");
            }
        });

        XposedBridge.hookAllMethods(settingTopClass, "f1", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object container =
                        XposedHelpers.getObjectField(param.thisObject, "n");
                    restoreXpanLivePhotoMenu(container, "top-layout-before");
                } catch (Throwable ignored) {
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object container =
                        XposedHelpers.getObjectField(param.thisObject, "n");
                    restoreXpanLivePhotoMenu(container, "top-layout-after");
                } catch (Throwable ignored) {
                }
            }
        });

        XposedBridge.hookAllMethods(optionClass, "A", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!isXpanLivePhotoMenuOption(param.thisObject)) return;
                /*
                 * A() is the stock menu-state cleanup path, not just an animation. It must finish
                 * so the live-photo value, callback and icon state all advance. Restore only the
                 * XPAN-specific visibility that stock tries to remove after that work is complete.
                 * r194 moved the override from beforeHookedMethod to this post-cleanup point.
                 */
                restoreXpanLivePhotoOption(param.thisObject, "hide-complete");
            }
        });

        XposedBridge.hookMethod(
            XposedHelpers.findMethodExact(topOptionClass, "onClick", View.class),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isXpanLivePhotoMenuOption(param.thisObject)) {
                        Log.i(TAG, "XPAN live photo click before, "
                            + menuOptionState(param.thisObject));
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!isXpanLivePhotoMenuOption(param.thisObject)) return;
                    syncXpanLivePhotoSharedState(param.thisObject, "menu-click");
                    String state = menuOptionState(param.thisObject);
                    if (!state.equals(lastLivePhotoMenuState)) {
                        lastLivePhotoMenuState = state;
                        Log.i(TAG, "XPAN live photo click after, " + state);
                    }
                }
            });

        XposedBridge.hookAllMethods(optionClass, "d0", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 1
                    || !(param.args[0] instanceof Integer)
                    || ((Integer) param.args[0]) != View.GONE
                    || !isXpanLivePhotoMenuOption(param.thisObject)) {
                    return;
                }
                restoreXpanLivePhotoOption(param.thisObject, "visibility");
                if (!menuVisibilityBlockedLogged) {
                    menuVisibilityBlockedLogged = true;
                    Log.i(TAG, "XPAN live photo menu GONE request restored, "
                        + menuOptionState(param.thisObject));
                }
            }
        });
    }

    /**
     * The stock XPAN exposure wheel is laid out over the top menu strip and consumes every gesture
     * in its rectangle: it returns true unconditionally, so a child underneath never hears the tap.
     * That rectangle is wider than the strip's meaningful area, and stock only survives it because
     * every stock item happens to sit outside the wheel's horizontal span. Restoring the live-photo
     * item widens the left strip from one slot to two, which pushes the stock camera icon
     * ({@code xpan_switch_to_physical_ui}) from x 42..138 into x 138..234 -- inside the wheel's
     * 145..934 span -- and that icon goes dead. So whitelisting the live-photo view alone is not
     * enough: resolve whichever top-strip item is actually under the finger, then forward the whole
     * gesture to it so it performs its normal click handling while the wheel stays put.
     *
     * <p>{@code XPanExposureWheel} declares {@code onTouchEvent}, while {@code dispatchTouchEvent} is
     * inherited from {@code View} and therefore cannot be resolved with findMethodExact.
     */
    private static void hookExposureWheelTouchYield(ClassLoader classLoader) {
        Class<?> wheelClass = XposedHelpers.findClass(XPAN_EXPOSURE_WHEEL, classLoader);
        Method onTouchEvent =
            XposedHelpers.findMethodExact(wheelClass, "onTouchEvent", MotionEvent.class);
        XposedBridge.hookMethod(onTouchEvent, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || param.args.length != 1
                    || !(param.args[0] instanceof MotionEvent)
                    || !(param.thisObject instanceof View)) {
                    return;
                }

                MotionEvent event = (MotionEvent) param.args[0];
                View wheel = (View) param.thisObject;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    exposureWheelGestureTarget = null;
                    exposureWheelGestureConsumed = false;

                    View target = findTopStripTargetUnder(wheel, event);
                    if (!isUsableTouchTarget(target)) return;

                    exposureWheelGestureTarget = target;
                    exposureWheelGestureConsumed = dispatchGestureTo(target, wheel, event);
                    param.setResult(Boolean.TRUE);
                    Log.i(TAG, "XPAN wheel gesture forwarded to top strip item"
                        + ", x=" + (int) event.getRawX()
                        + ", y=" + (int) event.getRawY()
                        + ", wheel=" + visibleBounds(wheel)
                        + ", target=" + target.getClass().getName()
                        + " " + visibleBounds(target)
                        + ", visible=" + target.isShown()
                        + ", clickable=" + target.isClickable()
                        + ", consumed=" + exposureWheelGestureConsumed);
                    return;
                }

                View target = exposureWheelGestureTarget;
                if (target == null) return;

                boolean consumed = dispatchGestureTo(target, wheel, event);
                exposureWheelGestureConsumed |= consumed;
                param.setResult(Boolean.TRUE);

                if (action == MotionEvent.ACTION_UP) {
                    /*
                     * A stock option view normally consumes DOWN and clicks itself on UP. This
                     * fallback only covers a custom child that declines the gesture, and cannot
                     * double-toggle the already handled path.
                     */
                    if (!exposureWheelGestureConsumed
                        && isUsableTouchTarget(target)
                        && containsRawPoint(target, event)) {
                        target.performClick();
                    }
                    Log.i(TAG, "XPAN wheel top strip gesture completed"
                        + ", consumed=" + exposureWheelGestureConsumed
                        + ", endedInside=" + containsRawPoint(target, event));
                    exposureWheelGestureTarget = null;
                    exposureWheelGestureConsumed = false;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    Log.i(TAG, "XPAN wheel top strip gesture completed"
                        + ", consumed=" + exposureWheelGestureConsumed
                        + ", cancelled=true");
                    exposureWheelGestureTarget = null;
                    exposureWheelGestureConsumed = false;
                }
            }
        });
        Log.i(TAG, "XPAN exposure-wheel top strip gesture router armed");
    }

    /**
     * Finds the clickable top-strip item under the finger, ignoring the wheel rectangle that the
     * touch was actually delivered to. The restored live-photo item is used as a fallback for the
     * short window before it is attached to a strip.
     */
    private static View findTopStripTargetUnder(View wheel, MotionEvent event) {
        for (String idName : TOP_BAR_VIEW_IDS) {
            View strip = findViewByResourceName(wheel, idName);
            View hit = findTopmostClickableUnder(strip, event);
            if (hit != null) return hit;
        }
        View live = findLivePhotoView(wheel);
        return isUsableTouchTarget(live) && containsRawPoint(live, event) ? live : null;
    }

    /**
     * Depth-first search for the frontmost usable item containing the raw point. Children are
     * visited back to front because the last child is the one drawn on top and therefore the one
     * that would have received the touch had the wheel not been in the way.
     */
    private static View findTopmostClickableUnder(View view, MotionEvent event) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            View deeper = findTopmostClickableUnder(child, event);
            if (deeper != null) return deeper;
            if (isUsableTouchTarget(child) && containsRawPoint(child, event)) return child;
        }
        return null;
    }

    private static View findViewByResourceName(View anchor, String idName) {
        try {
            int id = anchor.getResources().getIdentifier(
                idName, "id", anchor.getContext().getPackageName());
            return id == 0 ? null : anchor.getRootView().findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean dispatchGestureTo(View target, View wheel, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            /*
             * onTouchEvent receives coordinates in the wheel's local space. The strip item is a
             * sibling inside another parent, so forwarding the event unchanged shifts every touch
             * by the difference between the two view origins.
             */
            int[] wheelLocation = new int[2];
            int[] targetLocation = new int[2];
            wheel.getLocationOnScreen(wheelLocation);
            target.getLocationOnScreen(targetLocation);
            copy.offsetLocation(
                wheelLocation[0] - targetLocation[0],
                wheelLocation[1] - targetLocation[1]);
            return target.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    private static boolean containsRawPoint(View view, MotionEvent event) {
        Rect bounds = visibleBounds(view);
        return bounds != null
            && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    private static Rect visibleBounds(View view) {
        Rect bounds = new Rect();
        return view != null && view.getGlobalVisibleRect(bounds) ? bounds : null;
    }

    private static View findLivePhotoView(View wheel) {
        try {
            int liveId = wheel.getResources().getIdentifier(
                LIVE_PHOTO_VIEW_ID, "id", wheel.getContext().getPackageName());
            return liveId == 0 ? null : wheel.getRootView().findViewById(liveId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUsableTouchTarget(View view) {
        return view != null
            && view.getVisibility() == View.VISIBLE
            && view.isShown()
            && view.isEnabled()
            && view.isClickable()
            && view.getParent() != null;
    }

    private static void restoreXpanLivePhotoMenu(Object container, String source) {
        if (!isXpanMenuContainer(container)) return;
        Object item = findLivePhotoMenuItem(container);
        if (item == null) {
            if (!menuCreateRequestedLogged) {
                menuCreateRequestedLogged = true;
                Log.i(TAG, "XPAN live photo menu requested through stock container helper, "
                    + "source=" + source + ", keys=" + menuKeys(container));
            }
            try {
                XposedHelpers.callMethod(container, "k", LIVE_PHOTO_SUPPORT);
            } catch (Throwable error) {
                if (!menuCreateFailedLogged) {
                    menuCreateFailedLogged = true;
                    Log.e(TAG, "XPAN live photo menu stock helper failed", error);
                }
            }
            item = findLivePhotoMenuItem(container);
            if (item != null && !menuCreatedLogged) {
                menuCreatedLogged = true;
                Log.i(TAG, "XPAN live photo menu created through stock container helper");
            }
        }
        if (item == null) {
            item = findCachedLivePhotoMenuItem(container);
            if (item != null && !menuCacheAdoptedLogged) {
                menuCacheAdoptedLogged = true;
                Log.i(TAG, "XPAN live photo menu cache adopted after " + source);
            }
        }
        if (item == null) {
            if (!menuMissingLogged) {
                menuMissingLogged = true;
                Log.i(TAG, "XPAN top-left menu still has no live photo item after "
                    + source + ", keys=" + menuKeys(container));
            }
            return;
        }

        ensureMenuItemMembership(container, item);
        restoreXpanLivePhotoOption(item, source);
    }

    private static Object findCachedLivePhotoMenuItem(Object container) {
        try {
            Object value = XposedHelpers.getObjectField(container, MENU_CACHE_FIELD);
            if (!(value instanceof Iterable)) return null;
            for (Object item : (Iterable<?>) value) {
                if (isLivePhotoMenuOption(item)) return item;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Stock's on-demand helper updates the cache and display list but cannot update the separate
     * list used by later mode transitions. Keep all three references coherent, and only invoke
     * the stock add method when the option has not been attached to this container yet.
     */
    private static void ensureMenuItemMembership(Object container, Object item) {
        boolean inAll = containsMenuItem(container, MENU_LIST_FIELD, item);
        if (!inAll) {
            addMenuItem(container, MENU_LIST_FIELD, item);
        }

        boolean inShown = containsMenuItem(container, SHOWN_MENU_LIST_FIELD, item);
        if (inShown) return;
        try {
            Object view = XposedHelpers.callMethod(item, "k");
            if (view instanceof View && ((View) view).getParent() == container) {
                addMenuItem(container, SHOWN_MENU_LIST_FIELD, item);
                return;
            }
            XposedHelpers.callMethod(container, "n", item, true, true);
            if (!containsMenuItem(container, SHOWN_MENU_LIST_FIELD, item)) {
                addMenuItem(container, SHOWN_MENU_LIST_FIELD, item);
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo menu membership failed", error);
        }
    }

    private static boolean containsMenuItem(
        Object container, String fieldName, Object target) {
        try {
            Object value = XposedHelpers.getObjectField(container, fieldName);
            if (!(value instanceof Iterable)) return false;
            for (Object item : (Iterable<?>) value) {
                if (item == target) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void addMenuItem(
        Object container, String fieldName, Object item) {
        try {
            Object value = XposedHelpers.getObjectField(container, fieldName);
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) value;
                items.add(item);
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo menu list update failed: " + fieldName, error);
        }
    }

    private static void restoreXpanLivePhotoOption(Object item, String source) {
        if (!isXpanLivePhotoMenuOption(item)) return;
        String before = menuOptionState(item);
        try {
            XposedHelpers.setIntField(item, MENU_ORDER_FIELD, 0);
            XposedHelpers.callMethod(item, "g0");
            XposedHelpers.callMethod(item, "d0", View.VISIBLE);
            Object view = XposedHelpers.callMethod(item, "k");
            if (!(view instanceof View)) return;
            if (((View) view).getVisibility() != View.VISIBLE) {
                ((View) view).setVisibility(View.VISIBLE);
            }
            syncXpanLivePhotoSharedState(item, "restore-" + source);
            if (!menuRestoreLogged) {
                menuRestoreLogged = true;
                Log.i(TAG, "XPAN live photo menu restored after " + source
                    + ", before={" + before + "}, after={"
                    + menuOptionState(item) + "}");
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo menu restore failed after " + source, error);
        }
    }

    /**
     * XPAN's top-left option keeps its current value in {@code jq.h.o}, while the stock
     * LivePhotoPresenter reads the shared {@code n9.e.M} DataKey. The normal XPAN option click
     * updates the former but can leave the latter at "off", so the presenter thinks live photo is
     * disabled and XPAN's AE correction never runs. Mirror only an XPAN option's real on/off value
     * into that shared key; other modes and the separate per-mode state are left untouched.
     */
    private static void syncXpanLivePhotoSharedState(Object item, String source) {
        if (!isXpanLivePhotoMenuOption(item)
            || Boolean.TRUE.equals(LIVE_STATE_SYNC.get())) {
            return;
        }
        Object value;
        try {
            value = XposedHelpers.getObjectField(item, MENU_VALUE_FIELD);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN shared live state read failed", error);
            return;
        }
        if (!"on".equals(value) && !"off".equals(value)) return;

        LIVE_STATE_SYNC.set(Boolean.TRUE);
        try {
            Symbols symbols = Symbols.get();
            ClassLoader classLoader = item.getClass().getClassLoader();
            Object sharedKey = livePhotoSharedDataKey;
            if (sharedKey == null) {
                sharedKey = livePhotoSharedKey(symbols);
                livePhotoSharedDataKey = sharedKey;
            }
            Object stored = DataStore.get(symbols, classLoader, sharedKey, "");
            if (!value.equals(stored)) {
                DataStore.put(symbols, classLoader, sharedKey, value);
            }
            Object readBack = DataStore.get(symbols, classLoader, sharedKey, "");
            String signature = source + "|" + stored + "|" + value + "|" + readBack;
            if (!signature.equals(liveStateSyncLogSignature)) {
                liveStateSyncLogSignature = signature;
                Log.i(TAG, "XPAN shared live state " + stored + " -> " + value
                    + ", readBack=" + readBack + ", source=" + source);
            }
            if (!value.equals(readBack)) {
                Log.i(TAG, "XPAN shared live state verification failed, expected="
                    + value + ", actual=" + readBack);
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN shared live state sync failed, source=" + source, error);
        } finally {
            LIVE_STATE_SYNC.set(Boolean.FALSE);
        }
    }

    private static Object findLivePhotoMenuItem(Object container) {
        for (String fieldName : new String[] {
            MENU_LIST_FIELD, SHOWN_MENU_LIST_FIELD
        }) {
            try {
                Object value = XposedHelpers.getObjectField(container, fieldName);
                if (!(value instanceof Iterable)) continue;
                for (Object item : (Iterable<?>) value) {
                    if (isLivePhotoMenuOption(item)) return item;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isLivePhotoMenuOption(Object item) {
        if (item == null) return false;
        try {
            return LIVE_PHOTO_SUPPORT.equals(XposedHelpers.callMethod(item, "s"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isXpanLivePhotoMenuOption(Object item) {
        if (!isLivePhotoMenuOption(item)) return false;
        try {
            Object camera = XposedHelpers.getObjectField(item, OPTION_CAMERA_FIELD);
            return isXpanCameraContext(camera);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isXpanMenuContainer(Object container) {
        if (container == null
            || !MENU_CONTAINER_TOP_LEFT.equals(container.getClass().getName())) {
            return false;
        }
        try {
            Object camera = XposedHelpers.getObjectField(container, MENU_CAMERA_FIELD);
            return isXpanCameraContext(camera);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isXpanCameraContext(Object camera) {
        if (camera == null) return false;
        try {
            Object cameraUnit = XposedHelpers.callMethod(camera, "c0");
            if (cameraUnit == null) return false;
            Object modeConfig = XposedHelpers.getObjectField(cameraUnit, "a");
            if (modeConfig == null) return false;
            Object mode = XposedHelpers.getObjectField(modeConfig, "a");
            return isXpanMode(mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String menuKeys(Object container) {
        StringBuilder result = new StringBuilder("[");
        for (String fieldName : new String[] {
            MENU_LIST_FIELD, SHOWN_MENU_LIST_FIELD
        }) {
            try {
                Object value = XposedHelpers.getObjectField(container, fieldName);
                if (!(value instanceof Iterable)) continue;
                for (Object item : (Iterable<?>) value) {
                    try {
                        Object key = XposedHelpers.callMethod(item, "s");
                        if (result.length() > 1) result.append(", ");
                        result.append(key);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result.append(']').toString();
    }

    private static String menuOptionState(Object item) {
        try {
            Object view = XposedHelpers.callMethod(item, "k");
            int visibility = view instanceof View
                ? ((View) view).getVisibility()
                : -1;
            boolean attached = view instanceof View
                && ((View) view).getParent() != null;
            boolean selected = view instanceof View && ((View) view).isSelected();
            CharSequence description = view instanceof View
                ? ((View) view).getContentDescription()
                : null;
            Object order = XposedHelpers.getObjectField(item, MENU_ORDER_FIELD);
            Object currentValue = XposedHelpers.getObjectField(item, "o");
            return "visibility=" + visibility
                + ", parent=" + attached
                + ", order=" + order
                + ", selected=" + selected
                + ", current=" + currentValue
                + ", storedOn=" + isSharedLivePhotoOn(item)
                + ", description=" + description;
        } catch (Throwable error) {
            return "state-error=" + error.getClass().getSimpleName();
        }
    }

    private static boolean isXpanFeatureList(Set<?> features) {
        return features.size() <= 32
            && features.contains(XPAN_FEATURE)
            && !features.contains(LIVE_PHOTO_FEATURE);
    }

    private static boolean isXpanPresenter(Object presenter) {
        return XPAN_MODE.equals(presenterMode(presenter));
    }

    private static boolean isXpanMode(Object mode) {
        return XPAN_MODE.equals(presenterMode(mode));
    }

    private static String presenterMode(Object presenter) {
        try {
            Object value = XposedHelpers.callMethod(presenter, PRESENTER_MODE_METHOD);
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {
        }
        try {
            Object value =
                XposedHelpers.getObjectField(presenter, PRESENTER_MODE_FIELD);
            return value instanceof String ? (String) value : "?";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static boolean isLivePhotoStoredOn(Object presenter) {
        try {
            Class<?> type = presenter instanceof Class
                ? (Class<?>) presenter
                : presenter.getClass();
            Object value =
                XposedHelpers.callStaticMethod(type, "p3");
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String isSharedLivePhotoOn(Object context) {
        try {
            Symbols symbols = Symbols.get();
            Object key = livePhotoSharedDataKey;
            if (key == null) {
                key = livePhotoSharedKey(symbols);
                livePhotoSharedDataKey = key;
            }
            Object value = DataStore.get(
                symbols, context.getClass().getClassLoader(), key, "");
            return Boolean.toString("on".equals(value));
        } catch (Throwable error) {
            return "?";
        }
    }
}
