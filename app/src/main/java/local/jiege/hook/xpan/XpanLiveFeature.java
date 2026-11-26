package local.jiege.hook.xpan;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.opengl.GLES20;
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

    /** Resolve a symbol id ("@Xxx") to its runtime class via the fingerprint table. */
    private static Class<?> cls(String id) {
        return id.startsWith("@") ? Symbols.get().cls(id.substring(1))
            : XposedHelpers.findClass(id, Symbols.get().loader());
    }

    /** Resolved runtime name of a symbol id. */
    private static String member(String id) {
        return Symbols.get().name(id.startsWith("@") ? id.substring(1) : id);
    }

    /** Inner class of a resolved class ("a" -> Outer$a); null if absent on this build. */
    private static Class<?> innerOf(Class<?> outer, String simple) {
        for (Class<?> candidate : outer.getDeclaredClasses()) {
            if (candidate.getName().endsWith("$" + simple)) return candidate;
        }
        return null;
    }

    private static final String XPAN_FEATURE = "com.oplus.camera.feature.xpan";
    private static final String LIVE_PHOTO_FEATURE = "com.oplus.camera.feature.live_photo";
    private static final String LIVE_PHOTO_SUPPORT = "pref_live_photo_key";
    private static final String LIVE_PHOTO_PRESENTER = "@LivePhotoPresenter";
    private static final String CAMERA_IMPLEMENTATION = "@CameraImplementation";
    private static final String IMAGE_DATA = "com.oplus.camera.feature.livephoto.data.ImageData$a";
    private static final String VIDEO_IMAGE_DATA =
        "com.oplus.camera.feature.livephoto.data.ImageData";
    private static final String VIDEO_EFFECT_PIPELINE = "@VideoEffectPipeline";
    private static final String GL_RENDERER = "@CommonShader";
    private static final String GL_SHADER = "com.oplus.camera.common.gl.y0";
    private static final String RAW_3D_TEXTURE = "@Raw3dTexture";
    private static final String LIVE_WARP_RENDERER = "@WarpRenderer";
    private static final String LIVE_VIDEO_ENCODER = "@LiveVideoEncoder";
    private static final String EGL_HELPER = "@EglHelper";
    private static final String EIS_PROCESSOR = "@LivePhotoEisProcessor";
    private static final String EIS_WRAPPER = "@LivePhotoEisWrapper";
    private static final String EIS_GRID_DATA = "@EisGridData";
    private static final String EIS_METADATA_QUEUE_FIELD = "z";
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";
    private static final String LUT_MANAGER_HOLDER = LUT_MANAGER + "$b";
    private static final String CAMERA_METADATA_KEY = "@CameraMetadataKey";
    private static final String LIVE_PHOTO_FEATURE_FLAGS = "@LivePhotoFeatureFlags";
    /** The encoder's watermark compositor (WatermarkProcessor) and the renderer it owns. */
    private static final String WATERMARK_PROCESSOR = "@WatermarkProcessor";
    private static final String WATERMARK_PROCESSOR_RENDERER = "j";
    /** The GL renderer the watermark processor composites the frame and the bitmap with. */
    private static final String WATERMARK_COMPOSITE = "@WatermarkRenderer";
    /**
     * r579: the second WatermarkRenderer.composite caller. The 317 dump shows
     * WatermarkProcessor.b(t0, ImageData) AND the VideoEffectPipeline's own
     * drawWatermark(ImageData, Z, I, I, [F) both invoke the composite; the filtered
     * capture fired b probes yet produced zero composite logs, so the filtered branch
     * is suspected of moving through this pipeline instead.
     */
    private static final String VIDEO_EFFECT_PROCESSOR = "@VideoEffectPipeline";
    /**
     * r579 A/B: r568/r569 force the YUV-converter skip and the Z2 redirect whenever an
     * XPAN filter is active (the converter was measured raising GL 1282 under a filter),
     * and the filtered capture then feeds the encoder through b3 hardware buffers, which
     * play back Log-dark. Setting 0 lets the stock Z2+converter path through with a
     * filter as an A/B probe: solid-colour clip = GL 1282 confirmed, bright clip = the
     * forced bypass is the darkness culprit. Default 1 keeps the forced bypass.
     */
    private static final String XPAN_FILTER_BYPASS_PROPERTY =
        "persist.omnicam.xpan.filterbypass";
    private static final String LUT_PARAMETERS = "@LutParameters";
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
    private static final String FRAME_SINK = "@LivePhotoSession";
    private static final String YUV_CONVERSION_CLASS = "@Rgba2YuvConversion";
    private static final String XPAN_GYRO_KEY =
        "com.oplus.camera.gyro.value.gyrotoeis";
    private static final String BASE_MODE_NAME_FIELD = "U0";
    private static final String MENU_CONTAINER_BASE = "@MenuContainer";
    private static final String MENU_CONTAINER_TOP_LEFT = "@MenuContainerTopLeft";
    private static final String MENU_OPTION_BASE = "@MenuOptionBase";
    private static final String MENU_OPTION_TOP = "@MenuOptionTop";
    private static final String CAMERA_SETTING_UI_TOP = "@CameraSettingUiTop";
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
    /** r491: true only while FilterPresenter.captureRequest builds the cover request. */
    private static volatile boolean sCoverCaptureActive;
    /** r492: dedup set for the EV-set call-stack probe (thread|value). */
    private static final java.util.Set<String> evScopeProbeSeen =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    /*
     * r565: 7.006.317/352 refactored the XPAN preview canvas. The 123 build's renderer
     * com.oplus.camera.common.gl.s lost its draw/float[] entries (18 methods, none of them
     * o0/r/Z/e0), so every hook installed by the canvas layer below silently matched nothing
     * and the read-back self-heal never ran -- a contaminated BasicTone table then stayed on
     * the preview forever ("预览又变灰了"). The successor is com.oplus.camera.common.gl.n:
     * the FIELD NAMES survived (n / o / u) and only the entry points were re-obfuscated:
     *
     *   123 gl.s                    317/352 gl.n
     *   r([F,I,Z) -> field o        w([F,I,Z)  -> field o     (XPAN BasicTone)
     *   Z([F,I,Z) -> field n        e0([F,I,Z) -> field n
     *   e0([F,I,I) -> map u         j0([F,I,I) -> map u       (17^3 slots)
     *   data in t0.A (float[])      data in r0.z (float[])
     *
     * The holder changed with it: field o is a gl.r0 (fields y:int, z:float[]) instead of a
     * gl.t0, which is also why the old t0.A hash always returned -1 on those builds.
     */
    private static volatile Object modernCanvas;
    private static volatile long modernRepairAt;
    private static volatile int modernRepairCount;
    private static volatile boolean modernCanvasLogged;
    private static volatile boolean watermarkCopyLogged;
    private static volatile boolean watermarkCopyErrorLogged;
    private static volatile boolean watermarkCompositeLogged;
    private static volatile boolean watermarkCompositeSkippedLogged;
    /** Once-per-capture latch for the composite BasicTone gate force diagnostics. */
    private static volatile boolean watermarkGateForcedLogged;
    private static volatile boolean watermarkCompositeStaleTextureLogged;
    private static volatile int watermarkCompositeProbe;
    /** LutParameters of the frame currently being drawn, reused by the watermark composite. */
    private static volatile Object activeLutParameters;
    /** Pipeline of the frame currently being drawn; its renderer holds the BasicTone texture. */
    private static volatile Object activePipeline;
    /** Set while the stock watermark processor re-composites the frame on the GL thread. */
    private static final ThreadLocal<Boolean> WATERMARK_COMPOSITE_ACTIVE = new ThreadLocal<>();
    /**
     * r576: static mirror of the latest XPAN main frame's watermark state. The composite
     * arm used to read the XPAN_MAIN_FRAME ThreadLocal inside WatermarkProcessor.b, but the
     * encoder calls b() OUTSIDE the gh.p3.r draw scope where that ThreadLocal is already
     * cleared -- the arm silently never fired (r575 log: watermarked capture 21:45:39-42
     * produced no "composite armed", no composite probes, no skip logs) and every
     * watermarked clip was repainted by the stock composite with a null LutParameters,
     * i.e. without BasicTone tone mapping: the clip reads Log, ~2 stops dark (measured
     * meanV 29.5 watermarked vs 121.9 clean on the same scene).
     */
    private static volatile boolean lastMainWatermarkOpen;
    /** Once-per-capture latch for the composite gate-closed diagnostics. */
    private static volatile boolean watermarkCompositeGateLogged;
    /** Once-per-capture counter for the WatermarkProcessor.b shape probes. */
    private static volatile int watermarkProcessorProbe;
    /**
     * r579: the filtered XPAN capture (23:19 window) fired the WatermarkProcessor.b
     * probes but NEVER reached WatermarkRenderer.composite, while the clean capture
     * (23:15) armed and regraded every frame. y3.b has no early return that skips the
     * composite call (verified against the 317 authoritative dump -- the only branch
     * past the q() invoke is the exception handler), so these counters exist to catch
     * the filtered branch wherever it actually goes.
     */
    private static volatile int watermarkProcessorDoneProbe;
    private static volatile int watermarkCompositeCensus;
    private static volatile int videoEffectWatermarkProbe;
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
        try {
            hookXpanFilterState(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter state tracker unavailable", error);
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
        try {
            hookXpan4kPreview(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN 4K preview hook unavailable", error);
        }
        try {
            hookLiveVideoSharpen(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live video sharpen hook unavailable", error);
        }
        Log.i(TAG, "installed");
    }

    private static volatile boolean sPreview4kLogged;

    /**
     * r486: 4K live experiment (user request "参照哈苏超清的4K实况"). Stock XPAN caps the
     * preview stream -- which IS the live video's frame source (ImageReader 1920x864,
     * SurfacePool key rear_main&surface_key_preview&54&1920x864) -- at 1080p-class,
     * while Hasselblad ultra live captures through a dedicated 3840x2880 stream
     * (14:15:12 log, mbNeedChangePreviewSize=false). The sharpness ceiling is the
     * STREAM, not the encoder, so two points must be bumped together when
     * persist.omnicam.xpan.preview4k=1:
     *   1. SurfacePool.createImageReader: the wrapper's mHalSurfaceSize is what the
     *      HAL receives (buildStreamSurface surfaceSizeList); override preview
     *      wrappers to 3840x1728 (2x stock, same 2.22 aspect).
     *   2. gh.v2.m (live video size chooser feeding gh/i2.x = encoder
     *      mCropPreviewSize): scale the chosen size x2 so the encoder consumes the
     *      full 4K frame instead of downscaling it.
     * If the HAL rejects the size the session configure fails (black preview / no
     * capture); recovery is setprop persist.omnicam.xpan.preview4k 0 + force-stop.
     */
    private static void hookXpan4kPreview(ClassLoader classLoader) {
        Class<?> pool = XposedHelpers.findClassIfExists(
            "com.oplus.ocs.camera.common.surface.SurfacePool", classLoader);
        if (pool == null) {
            Log.i(TAG, "XPAN 4K preview: SurfacePool not found in app classloader");
            return;
        }
        XposedBridge.hookAllMethods(pool, "createImageReader", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!"1".equals(propertyStr("persist.omnicam.xpan.preview4k", ""))) return;
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Object wrapper = param.args.length > 0 ? param.args[0] : null;
                    if (wrapper == null) return;
                    Object usage = XposedHelpers.callMethod(wrapper, "getSurfaceUsage");
                    if (!"surface_key_preview".equals(usage)) return;
                    Size want = new Size(3840, 1728);
                    XposedHelpers.setObjectField(wrapper, "mHalSurfaceSize", want);
                    XposedHelpers.setObjectField(wrapper, "mAppSurfaceSize", want);
                    if (!sPreview4kLogged) {
                        sPreview4kLogged = true;
                        Log.i(TAG, "XPAN 4K preview reader overridden, hal/app size -> 3840x1728");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN 4K preview reader override failed", error);
                }
            }
        });
        try {
            Class<?> v2 = cls("@LiveVideoSizeChooser");
            XposedBridge.hookAllMethods(v2, "m", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!"1".equals(propertyStr("persist.omnicam.xpan.preview4k", ""))) return;
                        if (!XPAN_MODE.equals(currentCameraMode)) return;
                        Object result = param.getResult();
                        if (!(result instanceof Size)) return;
                        Size stock = (Size) result;
                        if (stock.getWidth() >= 3840) return;
                        Size scaled = new Size(stock.getWidth() * 2, stock.getHeight() * 2);
                        param.setResult(scaled);
                        Log.i(TAG, "XPAN 4K video size " + stock + " -> " + scaled);
                    } catch (Throwable error) {
                        Log.e(TAG, "XPAN 4K video size override failed", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.i(TAG, "XPAN 4K video size hook unavailable: " + error);
        }
        Log.i(TAG, "XPAN 4K preview hooks armed (persist.omnicam.xpan.preview4k)");
    }

    private static volatile boolean sVideoSharpenLogged;
    private static volatile boolean sVideoSharpenSkippedLogged;
    private static final Set<String> sShaderProbeSeen = new java.util.HashSet<>();

    /**
     * r487/r489: software sharpening for the XPAN live video. The frame source is
     * the 1920x864 preview stream (the 4K experiment failed: XPAN HAL opmode
     * 0x80AC accepts but never emits frames above that size), which measures ~3x
     * softer than the cover downscaled to the same resolution (offline laplacian
     * 96 vs 283, gradient p50 3.6 vs 12.4; bitrate is fine at 27 Mbps, FOV
     * identical). All shaders compile through GLES20.glShaderSource in ia/i3 --
     * hook that call and inject a 5-tap USM into the shaders that actually render
     * XPAN live video pixels (probe evidence r488: at capture time in XPAN only
     * four shaders compile, two of them are the EIS-warp render family):
     *   1. eis_warp_renderer_frag_3dlut (res/84s.glsl, 6160B): input read ->
     *      BasicTone LUT -> vig -> SDR->HLG + GTM -> gamut LUT. Inject between the
     *      input read and the BasicTone pass (SDR domain, before every LUT).
     *   2. the lighter variant (res/Y08.glsl, 3300B): input read -> HLG/GTM ->
     *      gamut LUT. Inject right after the input if/else.
     * The RGBA->P010 pack converter (res/iLK.glsl, "layout (yuv)" + uColorMode)
     * never compiles in the XPAN capture (probe evidence), kept for the master
     * live path.
     * Controls (setprop + force-stop com.oplus.camera):
     *   persist.omnicam.xpan.sharp          USM amount, default 0 = off, clamp 0..3
     *   persist.omnicam.xpan.sharp.size     warp output size, default 1920x864
     * Mode gate: injected only while currentCameraMode == XPAN at compile time.
     */
    private static void hookLiveVideoSharpen(ClassLoader classLoader) {
        Method source = XposedHelpers.findMethodExact(
            GLES20.class, "glShaderSource", int.class, String.class);
        XposedBridge.hookMethod(source, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object arg = param.args.length > 1 ? param.args[1] : null;
                    if (!(arg instanceof String)) return;
                    String shader = (String) arg;
                    if (shader != null && shader.length() > 20 && sShaderProbeSeen.add(shader)) {
                        String head = shader.substring(0, Math.min(shader.length(), 70));
                        Log.i(TAG, "XPAN shader compile probe, mode=" + currentCameraMode
                            + ", len=" + shader.length()
                            + ", head=" + head.replace("\n", " "));
                    }
                    float amount = videoSharpenAmount();
                    if (amount <= 0f) return;
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    if (shader.contains("omnicam-sharp-v1")) return;
                    float[] texel = videoSharpenTexel();
                    // r489: EIS warp renderer with BasicTone+vignette (res/84s.glsl)
                    String anchor84 = "    vec4 tempColor = apply3DLut(uBasicToneLut3dTexture,"
                        + " uBasicToneLutSize, textureColor, uNeedBasicTone);";
                    if (shader.contains(anchor84)) {
                        String block = "\n    // omnicam-sharp-v1 (module injected 5-tap USM)\n"
                            + warpSharpenBlock(amount, texel, "textureColor", true);
                        param.args[1] = shader.replace(anchor84, block + anchor84);
                        Log.i(TAG, "XPAN video sharpen injected into warp(basicTone), amount="
                            + amount);
                        return;
                    }
                    // r489: lighter warp renderer (res/Y08.glsl)
                    String anchorY08 = "        vFragColor = texture(uTexture2D, vTexCoords);\n    }";
                    if (shader.contains(anchorY08)) {
                        String block = anchorY08 + "\n"
                            + warpSharpenBlock(amount, texel, "vFragColor", false);
                        param.args[1] = shader.replace(anchorY08, block);
                        Log.i(TAG, "XPAN video sharpen injected into warp(lut), amount="
                            + amount);
                        return;
                    }
                    // r487: RGBA->P010 pack converter (res/iLK.glsl, master-live path)
                    if (!shader.contains("layout (yuv)") || !shader.contains("uColorMode")) return;
                    if (!sVideoSharpenSkippedLogged) {
                        sVideoSharpenSkippedLogged = true;
                        Log.i(TAG, "XPAN video sharpen skipped, compile-time mode="
                            + currentCameraMode);
                    }
                    String anchor = "    vec3 rgb = texture(uTexture, vSharedTexCoord).rgb;";
                    if (!shader.contains(anchor)) return;
                    String inject = "\n    // omnicam-sharp-v1 (module injected 5-tap USM)\n"
                        + "    {\n"
                        + "        vec2 oTexel = vec2(" + texel[0] + ", " + texel[1] + ");\n"
                        + "        vec3 oBlur = texture(uTexture, vSharedTexCoord + vec2(oTexel.x, 0.0)).rgb * 0.25\n"
                        + "                  + texture(uTexture, vSharedTexCoord - vec2(oTexel.x, 0.0)).rgb * 0.25\n"
                        + "                  + texture(uTexture, vSharedTexCoord + vec2(0.0, oTexel.y)).rgb * 0.25\n"
                        + "                  + texture(uTexture, vSharedTexCoord - vec2(0.0, oTexel.y)).rgb * 0.25;\n"
                        + "        rgb = clamp(rgb + " + amount + " * (rgb - oBlur), vec3(0.0), vec3(1.0));\n"
                        + "    }\n";
                    param.args[1] = shader.replace(anchor, anchor + inject);
                    Log.i(TAG, "XPAN video sharpen injected into p010 converter, amount="
                        + amount);
                } catch (Throwable error) {
                    Log.e(TAG, "XPAN video sharpen injection failed", error);
                }
            }
        });
        Log.i(TAG, "XPAN live video sharpen armed (persist.omnicam.xpan.sharp)");
    }

    /**
     * r489: GLSL block that sharpens {@code target}.rgb with a 5-tap USM around
     * vTexCoords. For OES input the neighbours are converted from BT.601 YUV
     * first ({@code withYcc} path, 84s.glsl has the bt601Ycc2Rgb matrix in
     * scope); the 2D path samples rgb directly. XPAN uploads the preview frames
     * as GL_TEXTURE_2D (probe f()=3553), so the 2D branch is the live one; the
     * OES branch keeps the block correct for other pipelines.
     */
    private static String warpSharpenBlock(float amount, float[] texel, String target,
            boolean withYcc) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("        vec2 oTexel = vec2(").append(texel[0]).append(", ")
            .append(texel[1]).append(");\n");
        sb.append("        vec2 cTexel = oTexel * 2.5;\n");
        sb.append("        vec3 oBlur = vec3(0.0);\n");
        sb.append("        vec3 cBlur = vec3(0.0);\n");
        sb.append("        if (uIsOesTexture) {\n");
        if (withYcc) {
            sb.append("            vec3 o1 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords + vec2(oTexel.x, 0.0)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 o2 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords - vec2(oTexel.x, 0.0)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 o3 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords + vec2(0.0, oTexel.y)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 o4 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords - vec2(0.0, oTexel.y)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            oBlur = (o1 + o2 + o3 + o4) * 0.25;\n");
            sb.append("            vec3 c1 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords + vec2(cTexel.x, 0.0)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 c2 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords - vec2(cTexel.x, 0.0)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 c3 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords + vec2(0.0, cTexel.y)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            vec3 c4 = bt601Ycc2Rgb * (texture(uTextureOES, vTexCoords - vec2(0.0, cTexel.y)).xyz - vec3(0.0, 0.5, 0.5));\n");
            sb.append("            cBlur = (c1 + c2 + c3 + c4) * 0.25;\n");
        } else {
            sb.append("            yuvCscStandardEXT oConv = itu_601_full_range;\n");
            sb.append("            vec4 o1t = texture(uTextureOES, vTexCoords + vec2(oTexel.x, 0.0));\n");
            sb.append("            vec4 o2t = texture(uTextureOES, vTexCoords - vec2(oTexel.x, 0.0));\n");
            sb.append("            vec4 o3t = texture(uTextureOES, vTexCoords + vec2(0.0, oTexel.y));\n");
            sb.append("            vec4 o4t = texture(uTextureOES, vTexCoords - vec2(0.0, oTexel.y));\n");
            sb.append("            oBlur = (yuv_2_rgb(mix(o1t.xyz, o1t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(o2t.xyz, o2t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(o3t.xyz, o3t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(o4t.xyz, o4t.xzy, float(uReverseUV)), oConv)) * 0.25;\n");
            sb.append("            vec4 c1t = texture(uTextureOES, vTexCoords + vec2(cTexel.x, 0.0));\n");
            sb.append("            vec4 c2t = texture(uTextureOES, vTexCoords - vec2(cTexel.x, 0.0));\n");
            sb.append("            vec4 c3t = texture(uTextureOES, vTexCoords + vec2(0.0, cTexel.y));\n");
            sb.append("            vec4 c4t = texture(uTextureOES, vTexCoords - vec2(0.0, cTexel.y));\n");
            sb.append("            cBlur = (yuv_2_rgb(mix(c1t.xyz, c1t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(c2t.xyz, c2t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(c3t.xyz, c3t.xzy, float(uReverseUV)), oConv)\n");
            sb.append("                  + yuv_2_rgb(mix(c4t.xyz, c4t.xzy, float(uReverseUV)), oConv)) * 0.25;\n");
        }
        sb.append("        } else {\n");
        sb.append("            oBlur = texture(uTexture2D, vTexCoords + vec2(oTexel.x, 0.0)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords - vec2(oTexel.x, 0.0)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords + vec2(0.0, oTexel.y)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords - vec2(0.0, oTexel.y)).rgb * 0.25;\n");
        sb.append("            cBlur = texture(uTexture2D, vTexCoords + vec2(cTexel.x, 0.0)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords - vec2(cTexel.x, 0.0)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords + vec2(0.0, cTexel.y)).rgb * 0.25\n");
        sb.append("                  + texture(uTexture2D, vTexCoords - vec2(0.0, cTexel.y)).rgb * 0.25;\n");
        sb.append("        }\n");
        // fine-scale USM, then coarse-scale USM (matches offline usm(usm(x,1.5,0.8),1.0,2.5))
        sb.append("        ").append(target).append(".rgb = clamp(").append(target)
            .append(".rgb + ").append(amount).append(" * (").append(target)
            .append(".rgb - oBlur), vec3(0.0), vec3(1.0));\n");
        sb.append("        ").append(target).append(".rgb = clamp(").append(target)
            .append(".rgb + ").append(amount * 0.5f).append(" * (").append(target)
            .append(".rgb - cBlur), vec3(0.0), vec3(1.0));\n");
        sb.append("    }\n");
        return sb.toString();
    }

    /** r487: USM amount for the injected P010-converter sharpening; 0 disables. */
    static float videoSharpenAmount() {
        try {
            float amount = Float.parseFloat(
                propertyStr("persist.omnicam.xpan.sharp", "0"));
            if (amount < 0f) return 0f;
            if (amount > 3f) return 3f;
            return amount;
        } catch (NumberFormatException badValue) {
            return 0f;
        }
    }

    /** r487: texel step for the USM, derived from the conversion surface size. */
    static float[] videoSharpenTexel() {
        float w = 1920f, h = 864f;
        try {
            String size = propertyStr("persist.omnicam.xpan.sharp.size", "1920x864");
            int x = size.indexOf('x');
            if (x > 0) {
                w = Float.parseFloat(size.substring(0, x));
                h = Float.parseFloat(size.substring(x + 1));
            }
        } catch (NumberFormatException badValue) {
            w = 1920f; h = 864f;
        }
        if (w < 16f || h < 16f || w > 16384f || h > 16384f) { w = 1920f; h = 864f; }
        return new float[] { 1f / w, 1f / h };
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
        Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.support"), new XC_MethodHook() {
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

        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.eligibility"), new XC_MethodHook() {
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

        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.dataOff"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(XPAN_RESUME_OVERRIDE.get())
                    && isXpanPresenter(param.thisObject)) {
                    param.setResult(null);
                }
            }
        });

        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.closeReason"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(XPAN_RESUME_OVERRIDE.get())
                    && isXpanPresenter(param.thisObject)) {
                    param.setResult(null);
                }
            }
        });

        Method onResume = XposedHelpers.findMethodExact(
            presenter, member("@LivePhotoPresenter.masterModeClose"), boolean.class);
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
                    XposedHelpers.callMethod(param.thisObject, member("@LivePhotoPresenter.closeReason"), 0);
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
        Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
        Class<?> stageClass = XposedHelpers.findClass(PREVIEW_STAGE, classLoader);
        Method listener = XposedHelpers.findMethodExact(presenter, member("@LivePhotoPresenter.previewStage"), stageClass);
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
        Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
        Class<?> stageClass = XposedHelpers.findClass(CAPTURE_REQUEST_STAGE, classLoader);
        Method prepareRequestTag =
            XposedHelpers.findMethodExact(presenter, member("@LivePhotoPresenter.captureStagePrepare"), stageClass);
        Method captureStarted =
            XposedHelpers.findMethodExact(presenter, member("@LivePhotoPresenter.captureStageStart"), stageClass);

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
        final Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
        final int fix = clamp(
            property(XPAN_LIVE_EV_PROPERTY, DEFAULT_XPAN_LIVE_EV_UNITS),
            -MAX_AE_EV_UNITS, MAX_AE_EV_UNITS);

        XposedBridge.hookAllMethods(baseMode, member("@BaseMode.beforePreview"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                updateActiveBaseMode(param.thisObject, "preview");
            }
        });

        // r491: scope the correction to the COVER request only. The stock XPAN AE runs
        // ~2 stops under on every stream; lifting the whole request stream fixed the
        // dark cover but over-exposed the video stream (the long-standing "veil"). The
        // FilterPresenter.captureRequest call builds the cover request, so arm the
        // flag around it and let the video stream run the stock AE (the video table
        // compensates with its own midtone curve, persist.omnicam.xpan.videolook.mid).
        try {
            Method coverCapture = local.jiege.hook.common.Symbols.get()
                .method("FilterPresenter.captureRequest");
            XposedBridge.hookMethod(coverCapture, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sCoverCaptureActive = XPAN_MODE.equals(currentCameraMode);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sCoverCaptureActive = false;
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN cover capture EV scope unavailable", error);
        }

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

        Class<?> camera = cls(CAMERA_IMPLEMENTATION);
        /*
         * 317 renamed the two-argument CaptureRequest setter (123: v, 317: b) and reused the old
         * name for an unrelated v(boolean). Hooking the literal "v" therefore matched nothing at
         * all on 317 -- the +2EV never landed, while the video table's own mid/lift/gain
         * compensation stayed active, which is exactly the "cover looks too dark" report.
         */
        final String evSetter;
        try {
            evSetter = Symbols.get().name("@CameraImplementation.ev");
        } catch (Throwable error) {
            Log.e(TAG, "XPAN AE setter symbol unavailable", error);
            return;
        }
        Log.i(TAG, "XPAN live exposure setter = " + evSetter);
        /*
         * 317 declares three setters that take a CaptureRequest key plus a value:
         *   b(Key, Object)      -- the 123-era entry
         *   v(Key, Object, Z)   -- same, with a "reapply" flag
         *   q(String, Key, Object)
         * Only hooking b() left the +2EV unapplied on this build (no probe ever fired in XPAN),
         * which is why the cover came out dark while the video table kept its own compensation.
         * Hook every key-taking setter and apply the correction to whichever one the build uses.
         */
        final java.util.List<String> evSetters = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : camera.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            boolean takesKey = false;
            boolean takesValue = false;
            for (Class<?> type : types) {
                if (type == CaptureRequest.Key.class) takesKey = true;
                if (type == Object.class) takesValue = true;
            }
            if (takesKey && takesValue) evSetters.add(m.getName());
        }
        if (!evSetters.contains(evSetter)) evSetters.add(evSetter);
        Log.i(TAG, "XPAN AE setter candidates = " + evSetters);
        for (String setter : new java.util.HashSet<>(evSetters)) {
            XposedBridge.hookAllMethods(camera, setter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int keyIndex = -1;
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof CaptureRequest.Key) {
                            keyIndex = i;
                            break;
                        }
                    }
                    if (keyIndex < 0 || keyIndex + 1 >= param.args.length
                        || param.args[keyIndex] != CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION
                        || !(param.args[keyIndex + 1] instanceof Number)
                        || !XPAN_MODE.equals(currentCameraMode)) {
                        return;
                    }
                    int original = ((Number) param.args[keyIndex + 1]).intValue();
                    // r492 diagnostic: the cover-only window (sCoverCaptureActive) never
                    // matched -- the cover request's EV is set OUTSIDE
                    // FilterPresenter.captureRequest. Capture the call stacks of every EV
                    // set in XPAN mode (deduped by thread+value) to find the cover path.
                    String probeKey = Thread.currentThread().getName() + "|" + original;
                    if (evScopeProbeSeen.add(probeKey)) {
                        Log.i(TAG, "XPAN EV set probe, thread=" + Thread.currentThread().getName()
                            + ", setter=" + setter
                            + ", original=" + original
                            + ", inCoverWindow=" + sCoverCaptureActive
                            + evScopeStack());
                    }
                    if (fix == 0 || !isLivePhotoStoredOn(presenter)) return;

                    int effective = clamp(
                        original + fix, -MAX_AE_EV_UNITS, MAX_AE_EV_UNITS);
                    param.args[keyIndex + 1] = Integer.valueOf(effective);

                    String signature = fix + "|" + original + "|" + effective;
                    if (signature.equals(exposureFixLogSignature)) return;
                    exposureFixLogSignature = signature;
                    Log.i(TAG, "XPAN live exposure correction (global, video counteracted by"
                        + " table) mode=" + currentCameraMode
                        + " setter=" + setter
                        + " original=" + original
                        + " fix=" + fix
                        + " effective=" + effective
                        + " ev=" + (fix / 330.0));
                }
            });
        }
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
        // r481: the XPAN filter selection LEAKS into the next mode. FilterPresenter.apply
        // bakes the filter into the process-wide manager.c in place (measured 2026-11-21
        // 09:17 and 10:39: standard -1504930108 -> fuji-nc -881252736), and leaving XPAN
        // with a filter active keeps that table resident: the Hasselblad-super mode's
        // "standard" filter then renders with the XPAN filter's tint (user report
        // 2026-11-22). Reset the presenter to the standard filter (index 0) right after
        // the switch so the native re-bakes manager.c into the clean table.
        if (XPAN_MODE.equals(previous) && !XPAN_MODE.equals(mode)
            && xpanFilterType != null) {
            scheduleXpanFilterReset();
        }
        if (XPAN_MODE.equals(mode) && !XPAN_MODE.equals(previous)) {
            // r457: each XPAN visit may inherit a foreign 64^3 table in gl.s field o
            // (every mode pushes its own via s.r on entry, XPAN does not), so re-arm the
            // wash-time repair regardless of what the last push contained.
            previewRepairHash = 0;
            // r461: also re-arm the wash generation. Measured grey 2026-11-20 20:48:59
            // (live-photo playback -> mode switches -> xpan re-entry): the first preview
            // frames sampled the leftover table because the pending wash had not fired
            // yet (wash still == the consumed generation from the previous visit), and
            // the r458 repair only triggers on a generation CHANGE. Re-arming makes the
            // repair push the clean XPAN table on the first draw of the visit, closing
            // the pre-wash grey window. Content is the module clean table == what native
            // itself pushes in xpan mode, so a redundant push is a no-op visually.
            previewRepairWash = -1;
            // r462: at a fresh session's first XPAN entry no wash has run yet
            // (paletteWashes==0, cleanXpanTable==null), so the r461 re-arm cannot push a
            // clean table and the entry frames sample the leftover one (measured grey
            // 2026-11-20 20:59:52). Run the wash on demand instead of waiting for native
            // to happen to call e(1,0,...): it recomputes manager.c into the clean XPAN
            // table, publishes cleanXpanTable and bumps paletteWashes, which un-blocks
            // the repair on the first draws.
            if (GrPreviewStreamRestore.cleanXpanTable == null
                && installedManagerHolder != null) {
                Object managerCandidate = null;
                try {
                    managerCandidate = XposedHelpers.getStaticObjectField(
                        installedManagerHolder, "a");
                } catch (Throwable ignored) {
                }
                if (managerCandidate != null) {
                    final Object manager = managerCandidate;
                    new Thread(() -> {
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ignored) {
                        }
                        GrPreviewStreamRestore.forceXpanEnvWash(manager, "xpan-entry");
                    }, "xpan-entry-wash").start();
                }
            }
            // r447: per-session diagnostics reset so probes never blind-fold a second visit.
            uniformProbeCalls.set(0);
            interestingUniformCalls.set(0);
            drawCensus.clear();
        }
    }

    /**
     * r481: leaving XPAN with a filter active bakes that filter into the shared
     * manager.c and the next mode's "standard" filter renders tinted. Mirror the
     * user tapping the standard filter: invoke the SAME FilterPresenter.apply with
     * index 0 so the native re-bakes the clean table. Delayed a moment so the mode
     * switch settles first; runs on a worker thread -- apply touches GL-adjacent
     * state we must not block a binder thread on.
     */
    private static void scheduleXpanFilterReset() {
        final Object presenter = xpanFilterPresenter;
        xpanFilterType = null;
        Log.i(TAG, "XPAN filter reset scheduled on mode exit, presenter="
            + (presenter == null
                ? "null" : Integer.toHexString(System.identityHashCode(presenter))));
        if (presenter == null) return;
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
            try {
                // r482: resolve via Symbols -- the same accessor the hooks use, so it is
                // GUARANTEED to find the method (getClass().getMethods() missed it: the
                // r481 field run logged "apply(int,int,int,int) not found", likely
                // package-private or a non-int signature).
                java.lang.reflect.Method apply =
                    local.jiege.hook.common.Symbols.get().method("FilterPresenter.apply");
                apply.setAccessible(true);
                Object[] args = new Object[apply.getParameterTypes().length];
                for (int i = 0; i < args.length; i++) {
                    Class<?> t = apply.getParameterTypes()[i];
                    if (t == int.class) args[i] = (i == 0) ? Integer.valueOf(0)
                        : (i == 2) ? Integer.valueOf(100) : Integer.valueOf(0);
                    else if (t == boolean.class) args[i] = Boolean.FALSE;
                    else if (t == float.class) args[i] = Float.valueOf(0f);
                    else if (t == long.class) args[i] = Long.valueOf(0L);
                    else if (t == double.class) args[i] = Double.valueOf(0d);
                    else args[i] = null;
                }
                apply.invoke(presenter, args);
                Log.i(TAG, "XPAN filter reset applied (index 0, "
                    + apply.getParameterTypes().length + " args)");
            } catch (Throwable error) {
                Log.e(TAG, "XPAN filter reset failed", error);
            }
        }, "JiegeXpanFilterReset");
        worker.setDaemon(true);
        worker.start();
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
            cls(LIVE_PHOTO_FEATURE_FLAGS);
        final Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
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
        final Class<?> conversion = cls(YUV_CONVERSION_CLASS);
        final String convertName = member("@Rgba2YuvConversion.convert");
        final String hardwareBufferName = member("@Rgba2YuvConversion.hardwareBuffer");
        XC_MethodHook skipHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                /*
                 * r568: with an XPAN filter active the stock converter cannot be let
                 * through even when the operator asks for it. Measured on 352 (and
                 * identical on 317 -- dh.e is byte-identical between the two builds):
                 * every filtered preview frame makes dh.e.b raise GL_INVALID_OPERATION
                 * (GL error 1282) on its glDrawArrays, so the encoder receives solid
                 * colour frames. The routed path (skipz2 -> X2/b3) is the one that
                 * keeps working, and the filter still reaches the video through the
                 * module's task.d composition, so nothing is lost by forcing the skip.
                 */
                boolean filterActive = xpanFilterType != null;
                boolean filterForced = filterActive
                    && property(XPAN_FILTER_BYPASS_PROPERTY, 1) != 0;
                if (!filterForced && property(XPAN_NO_CONV_PROPERTY, 1) == 0) {
                    return;
                }
                param.setResult(
                    convertName.equals(param.method.getName())
                        ? Long.valueOf(0L) : null);
                int skips = ++xpanConversionSkips;
                if (skips <= 3 || skips % 120 == 0) {
                    Log.i(TAG, "XPAN YUV converter call skipped"
                        + ", method=" + param.method.getName()
                        + ", mode=" + currentCameraMode
                        + ", filter=" + xpanFilterType
                        + ", skipCount=" + skips
                        + (filterForced
                            ? " (forced: stock converter fails with GL 1282 under a filter)"
                            : " (set persist.omnicam.xpan.noconv=0 to restore stock)"));
                }
            }
        };
        // Convert has a (F,F,I,Z,Z,[F)J shape on older builds and gains a trailing
        // boolean on newer ones -- match by name and parameter prefix, never by name alone
        // (the OES variant folded into it changed the arity between builds).
        for (java.lang.reflect.Method m : conversion.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(convertName)
                && p.length >= 6
                && p[0] == float.class && p[1] == float.class && p[2] == int.class
                && p[3] == boolean.class && p[4] == boolean.class
                && p[5] == float[].class) {
                XposedBridge.hookMethod(m, skipHook);
            }
        }
        // Hardware-buffer intake (l on older builds, m on newer ones).
        for (java.lang.reflect.Method m : conversion.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(hardwareBufferName) && p.length == 1
                && p[0] == android.hardware.HardwareBuffer.class) {
                XposedBridge.hookMethod(m, skipHook);
            }
        }
        // OES-source variant: separate method on older builds, folded into convert on
        // newer ones (the symbol resolves there and stays missing here by design).
        if (Symbols.get().has("@Rgba2YuvConversion.oesSource")) {
            XposedBridge.hookMethod(
                Symbols.get().method("@Rgba2YuvConversion.oesSource"), skipHook);
        }
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
                /*
                 * r569: same reasoning as the converter skip above -- with an XPAN filter
                 * active the stock Z2 producer is never trustworthy (it feeds the encoder
                 * through the converter that raises GL 1282), so the redirect is forced on
                 * regardless of the debug property. Without a filter the property decides.
                 */
                if (!XPAN_MODE.equals(currentCameraMode)
                    || param.args.length != 7) {
                    return;
                }
                boolean filterForced = xpanFilterType != null
                    && property(XPAN_FILTER_BYPASS_PROPERTY, 1) != 0;
                if (!filterForced && property(XPAN_SKIP_Z2_PROPERTY, 1) == 0) {
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
                        redirected = XposedHelpers.callMethod(param.thisObject,
                            member("@LivePhotoPresenter.x2Dispatch"), param.args);
                        if (redirected != null) via = "X2";
                    } catch (Throwable error) {
                        Log.i(TAG, "XPAN Z2 redirect X2 failed: " + error);
                    }
                }
                if (redirected == null) {
                    try {
                        redirected = XposedHelpers.callMethod(param.thisObject,
                            member("@LivePhotoPresenter.yuvFallback"), param.args);
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
        /*
         * Arm the redirect only when both dispatch targets resolve AND differ from the
         * hooked frameGen itself: on builds where the YUV producer became the main
         * frameGen the fingerprint would resolve to the same method and redirecting
         * into it would recurse forever.
         */
        if (Symbols.get().has("@LivePhotoPresenter.x2Dispatch",
                "@LivePhotoPresenter.yuvFallback",
                "@LivePhotoPresenter.photoGate",
                "@LivePhotoPresenter.xpanGate",
                "@LivePhotoPresenter.session")) {
            java.lang.reflect.Method frameGen = Symbols.get()
                .method("@LivePhotoPresenter.frameGen");
            java.lang.reflect.Method x2 = Symbols.get()
                .method("@LivePhotoPresenter.x2Dispatch");
            java.lang.reflect.Method yuv = Symbols.get()
                .method("@LivePhotoPresenter.yuvFallback");
            if (!frameGen.equals(x2) && !frameGen.equals(yuv)) {
                XposedBridge.hookAllMethods(
                    cls(LIVE_PHOTO_PRESENTER),
                    member("@LivePhotoPresenter.frameGen"), z2SkipHook);
                Log.i(TAG, "XPAN Z2 frame path bypass armed (redirect X2/b3)");
            } else {
                Log.i(TAG, "XPAN Z2 redirect not armed: dispatch targets"
                    + " alias the frameGen on this build");
            }
        } else {
            Log.i(TAG, "XPAN Z2 redirect not armed: dispatch symbols"
                + " unresolved on this build");
        }

        /*
         * Observe the single downstream fan-out for all three ImageData producers
         * (gh/i2.k). After the Z2 skip this tells us whether X2/b3 really took over the
         * frame source and which handle they carry.
         */
        XposedBridge.hookAllMethods(
            cls(FRAME_SINK),
            member("@LivePhotoSession.frameSink"), new XC_MethodHook() {
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

        /*
         * r518: 317 restructured the whole live-photo video path. The 123 anchors we relied on
         * (LivePhotoFeatureFlags.e as the EIS-bypass predicate, LivePhotoEisProcessor.c as the
         * warp stage) are either dead code or never reached in XPAN on this build: the flags
         * predicate has ZERO call sites in 317's dex and no processor log ever appears in XPAN,
         * while frames keep flowing into LivePhotoSession.frameSink with a null video handle.
         * Trace every entry point of the session and of the EIS processor so the next capture
         * shows exactly where the chain stops instead of guessing again.
         */
        hookLiveVideoChainTrace();
    }

    /** One-shot, low-volume trace of the 317 live-photo video chain (r518 diagnosis). */
    private static void hookLiveVideoChainTrace() {
        if ("0".equals(propertyStr("persist.omnicam.xpan.chaintrace", "1"))) {
            Log.i(TAG, "XPAN live video chain trace disabled");
            return;
        }
        try {
            final Class<?> session = cls(FRAME_SINK);
            for (java.lang.reflect.Constructor<?> ctor : session.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "live chain: session created mode=" + liveEisRouteMode()
                            + " params=" + param.args.length);
                    }
                });
            }
            Log.i(TAG, "live chain: session ctors hooked");
        } catch (Throwable error) {
            Log.i(TAG, "live chain: session ctors unavailable " + error);
        }
        try {
            final Class<?> processor = cls(EIS_PROCESSOR);
            int hooked = 0;
            for (Method method : processor.getDeclaredMethods()) {
                if (method.isSynthetic()) continue;
                method.setAccessible(true);
                final String name = method.getName();
                final String descriptor = java.util.Arrays.toString(method.getParameterTypes());
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int n = eisTraceCalls.incrementAndGet();
                        if (n > 60) return;
                        Log.i(TAG, "live chain: eis " + name + descriptor
                            + " mode=" + liveEisRouteMode() + " args=" + param.args.length);
                    }
                });
                hooked++;
            }
            Log.i(TAG, "live chain: eis processor methods hooked " + hooked);
        } catch (Throwable error) {
            Log.i(TAG, "live chain: eis processor unavailable " + error);
        }
        try {
            XposedBridge.hookAllMethods(
                cls(LIVE_PHOTO_PRESENTER), member("@LivePhotoPresenter.enable"),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "live chain: presenter.enable mode=" + liveEisRouteMode());
                    }
                });
            Log.i(TAG, "live chain: presenter enable hooked");
        } catch (Throwable error) {
            Log.i(TAG, "live chain: presenter enable unavailable " + error);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger eisTraceCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    /*
     * Mirror of Y1's dispatch (dex 0x0535-0x055d) plus X2's own internal gate: X2 only
     * produces an ImageData when s3() && p1 != null && W0 && (fh.a.a & 0x800000) != 0;
     * when the gate fails it returns null AND posts an error runnable, so the gate is
     * evaluated here first and b3 (ungated, plain YUV buffer path) handles the rest.
     */
    private static boolean x2GateOpen(Object presenter) {
        try {
            if (!(Boolean) XposedHelpers.callMethod(
                presenter, member("@LivePhotoPresenter.photoGate"))) return false;
            Object session = XposedHelpers.getObjectField(
                presenter, member("@LivePhotoPresenter.session"));
            if (session == null) return false;
            if (!(Boolean) XposedHelpers.getBooleanField(
                presenter, member("@LivePhotoPresenter.xpanGate"))) return false;
            int flags = XposedHelpers.getStaticIntField(
                cls(LIVE_PHOTO_FEATURE_FLAGS), "a");
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
            cls(LIVE_PHOTO_FEATURE_FLAGS);
        final Class<?> processor =
            cls(EIS_PROCESSOR);
        final Class<?> wrapper =
            cls(EIS_WRAPPER);
        final Class<?> imageData =
            XposedHelpers.findClass(VIDEO_IMAGE_DATA, classLoader);
        final Class<?> gridData =
            cls(EIS_GRID_DATA);

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

        XposedBridge.hookAllMethods(wrapper, member("@LivePhotoEisWrapper.sendFrame"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                logEisWrapperState(param, "send");
            }
        });
        XposedBridge.hookAllMethods(wrapper, member("@LivePhotoEisWrapper.sendMeta"), new XC_MethodHook() {
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
        XposedBridge.hookAllMethods(wrapper, member("@LivePhotoEisWrapper.acquire"), new XC_MethodHook() {
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
        Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);

        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.onPreviewDrawn"), new XC_MethodHook() {
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
                    && pendingCount(param.thisObject) >= 200) {
                    resetLivePhotoFrameBudget(param.thisObject, "preview-idle");
                }
                if (calls <= 3 || calls % 120 == 0) {
                    Log.i(TAG, "XPAN Y1 frame events alive, count=" + calls
                        + ", args=" + param.args.length
                        + ", budget=" + livePhotoFrameBudget(param.thisObject));
                }
                /*
                 * r462 watchdog: measured grey 2026-11-20 21:00 -- after a live capture
                 * the preview gl.s.o0 draws STOPPED (last draw 21:00:07, PreviewGLThread
                 * idle in Object.wait) while presenter frames kept flowing (Y1 alive,
                 * h1 counting ~25fps): the preview freezes on its last composited
                 * frame. Flag the split as soon as it happens so the next session can
                 * capture the presenter state at the exact stall moment.
                 */
                long now = android.os.SystemClock.uptimeMillis();
                long lastDraw = lastPreviewDrawUptime;
                if (!xpanLiveCaptureActive && lastDraw > 0 && now - lastDraw > 2500
                    && now - previewStallLogged > 5000) {
                    previewStallLogged = now;
                    Log.i(TAG, "XPAN preview GL stall suspected, y1Frames=" + calls
                        + ", noDrawForMs=" + (now - lastDraw)
                        + ", budget=" + livePhotoFrameBudget(param.thisObject));
                }
            }
        });

        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.closeReason"), new XC_MethodHook() {
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
        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.dataOff"), toggleProbe);
        XposedBridge.hookAllMethods(presenter, member("@LivePhotoPresenter.dataOn"), toggleProbe);
        Log.i(TAG, "XPAN session lifecycle probes armed");
    }

    /** Pending live-photo frame counts (the @LivePhotoPresenter.pendingCounters set) plus the intercept flag. */
    private static String livePhotoFrameBudget(Object presenter) {
        if (presenter == null) return "no-presenter";
        try {
            Object intercept = safeObjectField(presenter,
                Symbols.get().name("@LivePhotoPresenter.interceptFlag"));
            int sum = 0;
            StringBuilder detail = new StringBuilder();
            for (java.lang.reflect.Field counter : Symbols.get().<java.lang.reflect.Field>all("@LivePhotoPresenter.pendingCounters")) {
                int value = ((Number) counter.get(presenter)).intValue();
                sum += value;
                if (detail.length() > 0) detail.append('+');
                detail.append(value);
            }
            return "pending=" + detail + " (sum " + sum + ")"
                + ", intercept=" + intercept;
        } catch (Throwable error) {
            return "unreadable(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static int pendingCount(Object presenter) {
        int sum = 0;
        try {
            for (java.lang.reflect.Field counter : Symbols.get().<java.lang.reflect.Field>all("@LivePhotoPresenter.pendingCounters")) {
                sum += ((Number) counter.get(presenter)).intValue();
            }
        } catch (Throwable ignored) {
            return -1;
        }
        return sum;
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
            String flagField = Symbols.get().name("@LivePhotoPresenter.interceptFlag");
            int pending = pendingCount(presenter);
            boolean intercept = XposedHelpers.getBooleanField(presenter, flagField);
            if (pending == 0 && !intercept) {
                return;
            }
            /* r512: zero the pending counters (multi symbol) and the intercept flag. The other
             * budget fields the 123 build reset (high-water mark, per-counter snapshots, the
             * long timestamp) have no cross-version fingerprints and are not needed: the
             * starvation comes from pending > cap flipping the intercept flag, which is reset. */
            for (java.lang.reflect.Field counter : Symbols.get().<java.lang.reflect.Field>all("@LivePhotoPresenter.pendingCounters")) {
                ((java.util.concurrent.atomic.AtomicInteger) counter.get(presenter)).set(0);
            }
            XposedHelpers.setBooleanField(presenter, flagField, false);
            Log.i(TAG, "XPAN live photo frame budget reset at " + phase
                + " (was pending=" + pending + ", intercept=" + intercept + ")");
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
        Class<?> presenter = cls(LIVE_PHOTO_PRESENTER);
        Object gyroKey = findCaptureResultKey(
            cls(CAMERA_METADATA_KEY),
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
            cls(VIDEO_EFFECT_PIPELINE);
        final Class<?> imageData =
            XposedHelpers.findClass(VIDEO_IMAGE_DATA, classLoader);
        final Class<?> presenter =
            cls(LIVE_PHOTO_PRESENTER);
        Method renderFrame = XposedHelpers.findMethodExact(
            pipeline,
            member("@VideoEffectPipeline.renderSubVideo"),
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
        XposedBridge.hookAllMethods(pipeline, member("@VideoEffectPipeline.renderSubVideo"), new XC_MethodHook() {
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
            // r576: unconditional mirror -- the composite hook on the encoder thread has
            // no ThreadLocal to read, and a stale true from the previous watermarked
            // capture must not leak into a clean capture (freshness covers the rest).
            lastMainWatermarkOpen = frame.watermarkOpen;
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
        // r576: watermark composite probes must be readable on every capture, not only
        // the first one in the process.
        watermarkCompositeGateLogged = false;
        watermarkCompositeSkippedLogged = false;
        watermarkGateForcedLogged = false;
        watermarkProcessorProbe = 0;
        watermarkProcessorDoneProbe = 0;
        watermarkCompositeCensus = 0;
        videoEffectWatermarkProbe = 0;
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
                    // r565: this is the only per-frame entry the 317/352 canvas exposes, so the
                    // refactored-canvas repair rides on it (~40 frames at 3 draws each).
                    if (calls % 120 == 0) repairModernCanvasIfWashed();
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
        Method[] panelTargets = new Method[0];
        Method panelDraw = null;
        try {
            panelDraw = XposedHelpers.findMethodExact(panelRenderer, "o0",
                shaderClass, int.class, int.class,
                float.class, float.class, float.class, float.class);
        } catch (Throwable ignored) {
            // Not an error by itself: newer builds refactored the canvas renderer away.
        }
        if (panelDraw == null) {
            // 317/352 gutted gl.s (18 methods, no draw or float[] entries left), so the whole
            // 123-only canvas tracker set below cannot resolve. r565: the refactored renderer
            // (common.gl.n) is hooked instead so the preview self-heal keeps working -- without
            // it a contaminated BasicTone table stayed on screen as a grey preview forever,
            // because the only repair entry point was the missing o0 draw.
            Log.i(TAG, "XPAN gl.s canvas layer unavailable on this build (renderer "
                + "refactored; installing the gl.n layer instead)");
            Log.guard(TAG, "XPAN gl.n canvas layer", () -> installModernCanvasLayer(classLoader));
        } else {
            panelTargets = new Method[]{
                panelDraw,
                XposedHelpers.findMethodExact(panelRenderer, "c0",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader),
                    int.class, int.class),
                XposedHelpers.findMethodExact(panelRenderer, "d0",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader),
                    int.class, int.class, int.class),
                XposedHelpers.findMethodExact(panelRenderer, "o",
                    XposedHelpers.findClass("com.oplus.camera.common.gl.c", classLoader)),
            };
        }
        /*
         * r456: watch the canvas's own LUT data uploads. The r456 session measured ZERO
         * e0(slot==1) calls in a grey GR->XPAN session -- but dexdump shows THREE data
         * setters converging on static l0(t0,size,data): e0(data,slot,size) -> HashMap u,
         * Z(data,size,enabled) -> field n, r(data,size,enabled) -> field o. The native
         * side feeds the preview LUT through Z/r, which the r454/r455 trackers never
         * watched. r457 logs ALL of them (any slot) with content hash + caller stack.
         */
        // The whole tracker below is 123-only (see the panelDraw note above).
        if (panelDraw != null) try {
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
                        // r463: capture XPAN's own 17^3 preview tables per slot so the
                        // wash-time repair can re-push them after a canvas rebuild that
                        // the native does not follow with a fresh delivery.
                        if (slot > 0 && size == 17
                            && XPAN_MODE.equals(currentCameraMode)
                            && xpan17Captures.size() < 8) {
                            xpan17Captures.put(slot,
                                java.util.Arrays.copyOf(data, data.length));
                        }
                        if (slot == 1) {
                            // r466: in XPAN a 64^3 e0(slot=1) push is the filter-composited
                            // preview table (measured 385ms after the 800t apply); capture a
                            // defensive copy for the live video bridge. r479: data.length
                            // must be the full 64^3 table -- r479 field test showed an
                            // undersized/degenerate push can clobber a good capture.
                            if (size == 64 && data.length == 64 * 64 * 64 * 3
                                && XPAN_MODE.equals(currentCameraMode)
                                && hash != xpanFilterCompositeHash) {
                                xpanFilterCompositeTable =
                                    java.util.Arrays.copyOf(data, data.length);
                                xpanFilterCompositeHash = hash;
                                xpanFilterCompositeFilterType = xpanTypeForCurrentIndex();
                                Log.i(TAG, "XPAN filter composite table captured"
                                    + ", hash=" + hash
                                    + ", floats=" + data.length
                                    + ", tag=" + xpanFilterCompositeFilterType);
                            }
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
                        // r475: the native's own preview composite arrives on r(data, 64,
                        // true) ~6s after a filter is selected (17:24:16.841 push, hash
                        // 2323..., mode=xpan) -- this IS the table the preview renders, so
                        // capture it and feed it to task.d verbatim instead of composing
                        // cube-over-stock ourselves (r474's compose proved close but not
                        // identical to the cover). Generic no-filter pushes are captured
                        // with filter=null and never used by the video bridge.
                        if ("r".equals(param.method.getName()) && enabled
                            && data.length == 64 * 64 * 64 * 3
                            && XPAN_MODE.equals(currentCameraMode)
                            && hash != xpanFilterCompositeHash) {
                            xpanFilterCompositeTable =
                                java.util.Arrays.copyOf(data, data.length);
                            xpanFilterCompositeHash = hash;
                            xpanFilterCompositeFilterType = xpanTypeForCurrentIndex();
                            Log.i(TAG, "XPAN native preview composite captured"
                                + ", hash=" + hash
                                + ", floats=" + data.length
                                + ", tag=" + xpanFilterCompositeFilterType
                                + ", cl=" + Integer.toHexString(
                                    System.identityHashCode(
                                        XpanLiveFeature.class.getClassLoader())));
                            // r481: the composite often arrives AFTER the first compose
                            // already dumped the stock table -- retry the composite dump
                            // here so the offline cross-domain experiment gets both.
                            dumpCompositeOnce();
                        }
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
                        // r463: whenever the wash generation changes, log what the canvas
                        // actually holds (field n / field o / e0 slots) -- this is the
                        // ground truth for "clean manager but grey preview" sessions.
                        long washNow = GrPreviewStreamRestore.paletteWashes.get();
                        if (washNow != canvasLutStateWash && canvasLutStateLogged < 24) {
                            canvasLutStateWash = washNow;
                            canvasLutStateLogged++;
                            Log.i(TAG, "XPAN canvas LUT state, wash=" + washNow
                                + ", " + describeCanvasLutState(param.thisObject)
                                + ", manager[" + managerBasicSnapshot() + "]");
                        }
                        // r462 watchdog: timestamp every draw (cheap volatile write) so the
                        // Y1 probe can flag "frames flow but the preview GL stopped".
                        lastPreviewDrawUptime = android.os.SystemClock.uptimeMillis();
                        long calls = interestingUniformCalls.incrementAndGet();
                        // r464: periodic self-heal. Native-driven env flaps
                        // (e(1,2,"common") seen mid-XPAN at 08:06:08.866) can re-contaminate
                        // field o without bumping paletteWashes, so the wash-gated repair
                        // never fires. Every 40 draws compare the canvas's actual content
                        // and re-push only real mismatches (hash cost ~786K floats, fine).
                        if (calls % 40 == 0 && !isXpanLiveFrameFlowing()) {
                            String syncMode = propertyStr(
                                "persist.omnicam.xpan.presetsync", "r");
                            if (!"off".equalsIgnoreCase(syncMode)) {
                                try {
                                    ensureXpanPreviewTables(param.thisObject, syncMode,
                                        washNow, false);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        // r468: after the self-heal so the probe table wins on this frame.
                        applyVideoChannelProbe(param.thisObject);
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

        final Class<?> baseRenderer = cls(GL_RENDERER);
        final Class<?> warpRenderer = cls(LIVE_WARP_RENDERER);
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
    /*
     * r466: the measured filter delivery channel (2026-11-20 20:46:05, 800t selected):
     * ~385ms after FilterPresenter.apply the native pushes the FILTER-COMPOSITED 64^3
     * table through gl.s.e0(slot=1) (hash -8384517229819880239, 786432 floats), while
     * field o keeps the clean XPAN table via s.r. Resetting to "default" pushes only
     * the clean s.r table -- no e0(slot=1). The live video bridge must sample the
     * composited table while a filter is active, or the video loses the filter that
     * the cover keeps.
     */
    private static volatile float[] xpanFilterCompositeTable;
    private static volatile long xpanFilterCompositeHash;
    private static volatile String xpanFilterCompositeFilterType;
    private static volatile String xpanFilterType;

    /**
     * r477: the filter presenter instance seen most recently by any filter hook, kept
     * so the composite capture (GL thread, no presenter argument) can read the strip
     * index at push time and tag the composite with a learned filter type.
     */
    private static volatile Object xpanFilterPresenter;

    /**
     * r477: learned XPAN strip-index -> filter-type pairs. xpanFilterType only updates
     * when a capture request fires, which is always AFTER the composite push, so
     * tagging at push time needs this map. Pairs come from the previewRequest hook
     * (currentIndex + mCurrFilterType seen together) and persist across sessions in
     * the camera's own files dir. Seeded with the two pairs observed in logs.
     */
    private static final java.util.HashMap<Integer, String> xpanIndexTypes =
        new java.util.HashMap<>();
    private static volatile boolean xpanIndexTypesLoaded;
    private static final java.io.File XPAN_INDEX_MAP_FILE = new java.io.File(
        "/data/user/0/com.oplus.camera/files/jiege/xpan_filter_map.txt");

    private static void loadXpanIndexTypes() {
        if (xpanIndexTypesLoaded) return;
        synchronized (xpanIndexTypes) {
            if (xpanIndexTypesLoaded) return;
            xpanIndexTypesLoaded = true;
            xpanIndexTypes.put(1, "blackandwhite_HC_master.bin");
            xpanIndexTypes.put(5, "fuji-nc.bin");
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(XPAN_INDEX_MAP_FILE))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    try {
                        xpanIndexTypes.put(Integer.valueOf(line.substring(0, eq).trim()),
                            line.substring(eq + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "XPAN index->type map: " + xpanIndexTypes);
        }
    }

    private static void learnXpanIndexType(int index, String type) {
        if (index < 0 || type == null || type.isEmpty()
            || "default".equals(type)) return;
        synchronized (xpanIndexTypes) {
            if (type.equals(xpanIndexTypes.get(index))) return;
            xpanIndexTypes.put(index, type);
            try (java.io.Writer w = new java.io.FileWriter(XPAN_INDEX_MAP_FILE)) {
                for (java.util.Map.Entry<Integer, String> e : xpanIndexTypes.entrySet()) {
                    w.write(e.getKey() + "=" + e.getValue() + "\n");
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "XPAN index->type learned: " + index + "=" + type);
        }
    }

    /** r477: filter type for the strip index active at composite-push time. */
    private static String xpanTypeForCurrentIndex() {
        Object presenter = xpanFilterPresenter;
        if (presenter == null) return null;
        int index = currentIndex(presenter);
        loadXpanIndexTypes();
        synchronized (xpanIndexTypes) {
            return xpanIndexTypes.get(index);
        }
    }
    /* r467: set while a background CombineLutHelper.process call is in flight. */
    private static final java.util.concurrent.atomic.AtomicBoolean
        xpanFilterComposePending = new java.util.concurrent.atomic.AtomicBoolean();
    /* r467: the filter type the cached composite was composed for (dedup key). */
    private static volatile String xpanFilterComposedForType;
    private static volatile long filterPreviewRequestLogged;
    private static volatile String lastLutPath;

    private static String safeField(Object owner, String name) {
        try {
            return String.valueOf(XposedHelpers.getObjectField(owner, name));
        } catch (Throwable error) {
            return "?";
        }
    }

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
    // r463: XPAN's own 17^3 preview LUT tables captured from the native delivery points
    // (gl.s.e0 pushes and the nn.l$a callback), keyed by slot/index. Measured 2026-11-21
    // 07:29:35: on a GR->XPAN re-entry the native NEVER re-delivers the 17^3 table (its
    // dirty-check dedup considers it unchanged) while the canvas rebuild drops the old
    // texture -- the preview stays grey even after the 64^3 repair, and the LogLut
    // callback fired only in the fresh-entry sessions (07:27:57.641, 07:28:03.227),
    // never in the grey one.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, float[]>
        xpan17Captures = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long canvasLutStateWash = -1;
    private static volatile int canvasLutStateLogged;
    /** r462 watchdog: last gl.s.o0 draw (uptime ms) and last stall diagnostic. */
    private static volatile long lastPreviewDrawUptime;
    private static volatile long previewStallLogged;

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
    /*
     * r468 video-channel probe. Nothing else changed the recorded video, so determine
     * empirically which LUT channel the XPAN live-video encoder samples: push an
     * unmistakable red-biased 64^3 table into one channel per property value and let
     * the user record a short clip. persist.omnicam.xpan.videochannel =
     * o (canvas field o via s.r) | n (canvas field n via Z) | u1 (e0 slot 1) |
     * s (renderer.s via the video bridge override) | all.
     */
    private static volatile float[] redProbeTable;
    private static volatile long redProbeLoggedUptime;

    /**
     * r470: true when persist.omnicam.xpan.videochannel=t -- the red probe then targets the
     * gh.p3$a task.d table (ch.g direct attachment) from GrMasterLiveLutDiagnostic, the one
     * channel r427 already proved the XPAN live video encoder samples.
     */
    public static boolean videoChannelProbeTargetsTask() {
        return "t".equals(propertyStr("persist.omnicam.xpan.videochannel", ""));
    }

    /**
     * r480: source table for the XPAN live-video filter fix. The stock task.d array
     * (captured by the diagnostic hook before any swap) is composed through the
     * filter's 17^3 cube from /odm/etc/camera/meishe_lut and the result swapped into
     * task.d. r479's field test killed the alternative: the "native preview
     * composite" captured on gl.s.r is content-identical to manager.c (hex hash
     * cb792680 == decimal -881252736), because FilterPresenter.apply bakes the
     * filter into manager.c in place (r466: -1504930108 -> -881252736) -- feeding
     * manager.c to the video domain is the r427 brightness regression verbatim
     * (user re-confirmed "brighter, same as the original bug"). The stock-table
     * guard (baseHash == 379247366) also blocks composing over a non-stock task.d
     * (r479 shot 1 saw manager.c arrive as the task base).
     * Filter-off sessions keep stock's generic task.d untouched.
     *
     * @param stockTable the original task.d array captured by the diagnostic hook
     *                   before any swap (null-safe: returns null without it)
     */
    public static float[] xpanLiveVideoLookTable(float[] stockTable) {
        if (!XPAN_MODE.equals(currentCameraMode)) return null;
        String type = xpanFilterType;
        // r497: after a process restart the stock presenter restores the remembered
        // filter UI but leaves request.mCurrFilterType empty until the user retaps a
        // filter (field-proven: 17:51 shoot logged "filter type=null" while the strip
        // showed the filter selected -- the compose silently skipped and the video ran
        // the stock table, which is exactly the old r479 "first shot has no effect").
        // Fall back to the last type this process family actually composed with; the
        // stock restore IS that same filter in the normal single-filter workflow.
        if (type == null) {
            String restored = restoreLastVideoLookFilter();
            if (restored != null) {
                type = restored;
                Log.i(TAG, "XPAN video look filter type restored from last session: "
                    + restored + " (live mCurrFilterType empty)");
            }
        }
        if (type == null || "default".equals(type)) {
            // r497: the STANDARD filter is a legitimate user choice, not "no filter
            // state" -- the user tests brightness on it (17:51 field note), and the
            // brightness corrections (mid/lift/gain) must apply there too. Fall
            // through to the no-cube correction path; only a truly-unknown state
            // (null without a persisted fallback) skips the compose.
            if (!"default".equals(type)) {
                logVideoLookSkipOnce("filter type=" + xpanFilterType);
                return null;
            }
        }
        if ("off".equalsIgnoreCase(propertyStr("persist.omnicam.xpan.videolook", ""))) {
            logVideoLookSkipOnce("videolook property off");
            return null;
        }
        try {
            if (stockTable == null || stockTable.length != 64 * 64 * 64 * 3) {
                logVideoLookSkipOnce("stock table null/short, len="
                    + (stockTable == null ? -1 : stockTable.length));
                return null;
            }
            // r480: the r475 "native preview composite" path is RETIRED. r479 field test
            // + hash arithmetic proved the captured composite (gl.s.r push, 64-bit hash
            // 2323079684790431971) is CONTENT-IDENTICAL to manager.c: describeTable's
            // hex hash of the swapped task.d came out cb792680 == decimal -881252736 ==
            // manager.c. That is expected -- FilterPresenter.apply bakes the filter into
            // manager.c in place (r466 measured -1504930108 -> -881252736), so the
            // "preview composite" IS manager.c. Swapping it into task.d is a verbatim
            // replay of the r427 brightness regression (user: "brighter, same as the
            // original bright bug"; the preview-domain table is wrong for the video
            // domain). The compose below -- STOCK task.d through the odm filter cube --
            // stays the only source (r474 field result: filter visible, no regression).
            long baseHash = Arrays.hashCode(stockTable);
            // r480: only compose on the stock video table. r479 shot 1 showed task.d
            // can arrive as manager.c content (-881252736); composing a cube over THAT
            // was r473's "brighter, no filter" mistake. When task.d is not the stock
            // table, skip rather than guess.
            if (baseHash != 379247366L /* 0x169adb06 stock video table */) {
                Log.i(TAG, "XPAN video look skipped: task.d is not the stock table"
                    + ", baseHash=" + baseHash + ", filter=" + type);
                return null;
            }
            CacheKey key = new CacheKey(type, baseHash);
            if (key.equals(videoLookCacheKey)) return videoLookCacheTable;
            float[] out;
            int cubePoints = 0;
            if ("default".equals(type)) {
                // r497: standard filter -- corrections only (domain/gain/lift/mid),
                // no filter cube. Same correction pipeline as the cube compose.
                out = composeTableCorrectionsOnly(stockTable);
            } else {
                float[] cube = loadFilterCube(type);
                if (cube == null) {
                    logVideoLookSkipOnce("cube load failed for " + type);
                    return null;
                }
                cubePoints = cube.length / 3;
                out = composeTableThroughCube(stockTable, cube);
            }
            if (out == null) {
                logVideoLookSkipOnce("compose returned null for " + type);
                return null;
            }
            videoLookCacheKey = key;
            videoLookCacheTable = out;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : stockTable) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            Log.i(TAG, "XPAN video look composed, filter=" + type
                + (cubePoints > 0 ? ", cube=" + cubePoints + " lattice points" : ", no cube")
                + ", domain=" + propertyStr("persist.omnicam.xpan.videolook.domain", "linear")
                + ", gain=" + String.format("%.2f", videoLookGain())
                + ", lift=" + String.format("%.3f", videoLookLift())
                + ", mid=" + String.format("%.2f", videoLookMid())
                + ", cubegain=" + String.format("%.2f", videoLookCubeGain())
                + ", baseHash=" + baseHash
                + ", base[min=" + String.format("%.4f", min) + ",max=" + String.format("%.4f", max)
                + "], outHash=" + Arrays.hashCode(out));
            dumpVideoTablesOnce(stockTable);
            return out;
        } catch (Throwable error) {
            Log.e(TAG, "XPAN video look compose failed", error);
            return null;
        }
    }

    /**
     * r474: one-shot offline-analysis dump of the stock video table (little-endian
     * float32, 64^3 x 3) into the camera's own files dir so it can be pulled with su
     * and inspected without another build cycle.
     */
    private static void dumpVideoTablesOnce(float[] stockTable) {
        if (!videoTablesDumped) {
            videoTablesDumped = true;
            lastStockTableForDump = java.util.Arrays.copyOf(
                stockTable, stockTable.length);
            try {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(
                    stockTable.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (float v : stockTable) buf.putFloat(v);
                java.io.File out = new java.io.File(
                    "/data/user/0/com.oplus.camera/files/jiege/taskd_stock.bin");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(buf.array());
                }
                Log.i(TAG, "XPAN video look dump written: " + out.getAbsolutePath()
                    + " (" + out.length() + " bytes)");
            } catch (Throwable error) {
                Log.e(TAG, "XPAN video look dump failed", error);
            }
        }
        dumpCompositeOnce();
    }

    /**
     * r481: persist the captured preview composite (== manager.c, the filter baked
     * BasicTone table). The offline cross-domain experiment needs BOTH tables:
     * transfer T = composite ∘ stock⁻¹ maps video input through the stock inverse
     * into the preview input domain and applies the filter-baked BasicTone -- the
     * result is the preview look expressed on stock's own input domain, with no
     * cube or color-space assumptions at all. Runs until the composite arrives,
     * even if the stock dump happened on an earlier capture (the native pushes the
     * composite ~6s AFTER the filter is selected; the first compose usually fires
     * before that and would otherwise leave us composite-less).
     */
    private static void dumpCompositeOnce() {
        if (compositeDumped) return;
        float[] stock = lastStockTableForDump;
        float[] composite = xpanFilterCompositeTable;
        if (stock == null || composite == null || composite.length != stock.length) {
            return;
        }
        try {
            java.nio.ByteBuffer cbuf = java.nio.ByteBuffer.allocate(
                composite.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (float v : composite) cbuf.putFloat(v);
            java.io.File cout = new java.io.File(
                "/data/user/0/com.oplus.camera/files/jiege/preview_composite.bin");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cout)) {
                fos.write(cbuf.array());
            }
            compositeDumped = true;
            Log.i(TAG, "XPAN composite dump written: " + cout.getAbsolutePath()
                + " (" + cout.length() + " bytes)");
        } catch (Throwable error) {
            Log.e(TAG, "XPAN composite dump failed", error);
        }
    }

    private static volatile boolean videoTablesDumped;
    private static volatile boolean compositeDumped;
    private static volatile float[] lastStockTableForDump;

    private static final class CacheKey {
        final String type;
        final long baseHash;

        CacheKey(String type, long baseHash) {
            this.type = type;
            this.baseHash = baseHash;
        }

        @Override public boolean equals(Object other) {
            return other instanceof CacheKey
                && ((CacheKey) other).type.equals(type)
                && ((CacheKey) other).baseHash == baseHash;
        }

        @Override public int hashCode() {
            return type.hashCode() * 31 + (int) baseHash;
        }
    }

    private static volatile CacheKey videoLookCacheKey;
    private static volatile float[] videoLookCacheTable;

    /** r496: deduped diagnostics for the silent-return paths that hid the r495 no-compose. */
    private static final java.util.Set<String> videoLookSkipSeen =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void logVideoLookSkipOnce(String reason) {
        if (videoLookSkipSeen.add(reason)) {
            Log.i(TAG, "XPAN video look skipped, " + reason + ", mode=" + currentCameraMode);
        }
    }

    /**
     * r497: persist the last filter type the live-video look was composed with so a
     * process restart can restore it (the stock presenter remembers the filter UI
     * but leaves request.mCurrFilterType empty until the user retaps). Written only
     * on change; read back in xpanLiveVideoLookTable when the live field is null.
     */
    private static final java.io.File LAST_FILTER_FILE = new java.io.File(
        "/data/user/0/com.oplus.camera/files/jiege/last_filter_type.txt");
    private static volatile String lastPersistedFilterType;

    private static void persistLastVideoLookFilter(String type) {
        if (type == null || type.equals(lastPersistedFilterType)) return;
        try {
            LAST_FILTER_FILE.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(LAST_FILTER_FILE)) {
                fos.write(type.getBytes("UTF-8"));
            }
            lastPersistedFilterType = type;
        } catch (Throwable error) {
            Log.e(TAG, "XPAN last filter persist failed", error);
        }
    }

    private static String restoreLastVideoLookFilter() {
        try {
            if (!LAST_FILTER_FILE.exists()) return null;
            byte[] data = java.nio.file.Files.readAllBytes(LAST_FILTER_FILE.toPath());
            String type = new String(data, "UTF-8").trim();
            return type.isEmpty() ? null : type;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Loads and parses a stock filter cube (.MS-LUT container). Layout decoded from
     * fuji-nc.bin (2026-11-21): 48-byte fixed header (0x0C = lattice N, 0x20 = domain
     * table offset), then N float domain samples (0..1 linear), then N^3 RGB u8. The
     * lattice index is (r*N + g)*N + b -- B varies fastest -- verified by all three
     * axis ramps staying hue-preserving (blue->blue, red->red, green->green) while the
     * other two orderings map axes onto each other. Triplets are stored (r, g, b).
     */
    private static float[] loadFilterCube(String type) {
        byte[] bin = readDeviceLut("/odm/etc/camera/meishe_lut/" + type);
        if (bin == null) {
            int dot = type.lastIndexOf('.');
            String stem = dot > 0 ? type.substring(0, dot) : type;
            for (String suffix : new String[]{"_sdr", "_normal", "_hdr"}) {
                bin = readDeviceLut("/odm/etc/camera/meishe_lut/" + stem + suffix + ".bin");
                if (bin != null) {
                    Log.i(TAG, "XPAN video look cube fallback" + suffix + " for " + type);
                    break;
                }
            }
        }
        if (bin == null || bin.length < 64) return null;
        int n = (bin[12] & 0xFF) | ((bin[13] & 0xFF) << 8)
            | ((bin[14] & 0xFF) << 16) | ((bin[15] & 0xFF) << 24);
        if (n < 2 || n > 64) return null;
        int domOff = (bin[32] & 0xFF) | ((bin[33] & 0xFF) << 8)
            | ((bin[34] & 0xFF) << 16) | ((bin[35] & 0xFF) << 24);
        int payload = domOff > 0 && domOff + n * 4 <= bin.length ? domOff + n * 4 : -1;
        if (payload < 0 || payload + n * n * n * 3 > bin.length) return null;
        int points = n * n * n;
        float[] cube = new float[points * 3];
        int index = 0;
        for (int i = 0; i < points; i++) {
            int p = payload + i * 3;
            cube[index++] = (bin[p] & 0xFF) / 255f;
            cube[index++] = (bin[p + 1] & 0xFF) / 255f;
            cube[index++] = (bin[p + 2] & 0xFF) / 255f;
        }
        return cube;
    }

    private static byte[] readDeviceLut(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            byte[] data = new byte[(int) new java.io.File(path).length()];
            int read = 0;
            while (read < data.length) {
                int got = in.read(data, read, data.length - read);
                if (got < 0) break;
                read += got;
            }
            return read == data.length ? data : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Pushes every 64^3 BasicTone entry through the filter cube (trilinear, u8 lattice
     * index (r*N + g)*N + b, N derived from the cube length). The result is the preview
     * appearance -- BasicTone first, then the filter -- baked into one 64^3 table.
     */
    /**
     * r497: correction-only compose for the STANDARD filter (type "default").
     * Identical pipeline to composeTableThroughCube minus the cube sampling:
     * stock table -> EOTF(domain) -> gain (linear domain) -> OETF(domain)
     * -> lift (display) -> mid (display). Keeps the brightness/de-veil
     * corrections active when the user shoots with no filter color.
     */
    private static float[] composeTableCorrectionsOnly(float[] base) {
        String domain = propertyStr("persist.omnicam.xpan.videolook.domain", "linear");
        float gain = videoLookGain();
        float lift = videoLookLift();
        float mid = videoLookMid();
        float[] out = new float[base.length];
        float[] rgb = new float[3];
        for (int i = 0; i < base.length; i += 3) {
            for (int c = 0; c < 3; c++) {
                rgb[c] = Math.min(1f, Math.max(0f, base[i + c]));
            }
            transfer(rgb, domain, true);
            if (gain != 1f) {
                for (int c = 0; c < 3; c++) {
                    rgb[c] = Math.min(1f, Math.max(0f, rgb[c] * gain));
                }
            }
            transfer(rgb, domain, false);
            if (lift != 0f) {
                for (int c = 0; c < 3; c++) {
                    rgb[c] = Math.max(0f, rgb[c] - lift);
                }
            }
            if (mid != 0f) {
                for (int c = 0; c < 3; c++) {
                    float v = rgb[c];
                    rgb[c] = Math.min(1f, Math.max(0f, v + mid * v * (1f - v)));
                }
            }
            System.arraycopy(rgb, 0, out, i, 3);
        }
        return out;
    }

    private static float[] composeTableThroughCube(float[] base, float[] cube) {
        int points = cube.length / 3;
        int n = (int) Math.round(Math.cbrt(points));
        if (n < 2 || n * n * n != points) return null;
        // r483: DOMAIN FIX. The r480 field test reported "brighter + pink/red shift"
        // vs the cover. Offline numeric check matches EXACTLY what feeding DISPLAY-domain
        // values straight into the cube does: gray 0.5 -> V 50->61 (+11) hue 304 (magenta)
        // -- the cube was clearly designed for a NEAR-LINEAR domain (the preview feeds it
        // with manager.c outputs whose own gray line sits at V 0.8 where the cube barely
        // changes V, measured 81->84). Select the transfer with
        // persist.omnicam.xpan.videolook.domain: "linear" (sRGB 2.4, default),
        // "pq" (ST 2084), "gamma" (r480 behavior, kept for A/B).
        String domain = propertyStr("persist.omnicam.xpan.videolook.domain", "linear");
        // r484: residual exposure trim. r483 (sRGB roundtrip) fixed the magenta/bright
        // shift but the 11-22 field test still reports slight over-brightness vs the
        // cover. Offline simulation on taskd_stock x fuji-nc shows the linear-domain
        // compose is neutral at V~0.5 and +11~12 at V0.6-0.8, so a plain linear-domain
        // multiplier before the OETF is the right trim axis. Tunable without rebuild:
        //   adb shell setprop persist.omnicam.xpan.videolook.gain 0.85
        //   adb shell am force-stop com.oplus.camera
        // (display-domain effect is gain^(1/2.4): 0.85 -> -6.5%, 0.9 -> -4.3%.)
        float gain = videoLookGain();
        // r485: DE-VEIL. The 11-22 field tests ("still bright at gain 0.70", "the video
        // looks like it is covered by a layer of white light", user: this haze has
        // ALWAYS been there, even in stock) show the residual is not exposure but a
        // LIFTED BLACK POINT / compressed contrast inherited from the stock video path
        // (frame stats: video p5-p95 range is half the cover's, std 0.111 vs 0.160).
        // A linear gain cannot fix that -- it only crushes blacks further under the
        // veil. Counter it in the table OUTPUT (display) domain: subtract a constant
        // offset so shadows fall back to the cover's black floor while highlights
        // barely move. Tunable without rebuild:
        //   adb shell su -c 'setprop persist.omnicam.xpan.videolook.lift 0.05'
        //   adb shell am force-stop com.oplus.camera
        float lift = videoLookLift();
        // r491: MIDTONE lift, display domain, after gain/lift. out = in + e*in*(1-in):
        // 0 and 1 stay pinned, brightness rises around the middle. Rationale: with the
        // +2EV capture-side correction scoped to the cover request only (r491 split),
        // the video stream runs the stock XPAN AE, which measures about two stops
        // under (16:45 field shot: ISO 160, cover p50 8/255 vs 97/255 with the old
        // global correction). A plain linear gain would lift the recovered black
        // point back up (the old veil), so lift the middle instead. Tunable:
        //   adb shell su -c 'setprop persist.omnicam.xpan.videolook.mid 0.7'
        //   adb shell am force-stop com.oplus.camera
        float mid = videoLookMid();
        float step = 1f / (n - 1);
        float[] out = new float[base.length];
        float[] rgb = new float[3];
        float[] t = new float[3];
        for (int i = 0; i < base.length; i += 3) {
            for (int c = 0; c < 3; c++) {
                rgb[c] = Math.min(1f, Math.max(0f, base[i + c]));
            }
            transfer(rgb, domain, true);
            float r = rgb[0], g = rgb[1], b = rgb[2];
            float fx = r / step, fy = g / step, fz = b / step;
            int x0 = Math.min(n - 1, (int) fx), y0 = Math.min(n - 1, (int) fy);
            int z0 = Math.min(n - 1, (int) fz);
            int x1 = Math.min(n - 1, x0 + 1), y1 = Math.min(n - 1, y0 + 1);
            int z1 = Math.min(n - 1, z0 + 1);
            float dx = fx - x0, dy = fy - y0, dz = fz - z0;
            // r498: CUBE STRENGTH overdrive. Field test 11-22 evening: color filters
            // are barely distinguishable in the video while monochrome ones (which
            // crush saturation to near zero) are obvious -- the cover shows the same
            // filters clearly. The LUT deviations of the color cubes are small in
            // absolute terms (offline: 800t mean |diff| ~0.04-0.12) and the HDR video
            // path compresses them further. Blend the sampled cube output AWAY FROM
            // the input by an overdrive factor: out = in + k*(cube(in)-in), k>1
            // extrapolates the look (monochrome stays monochrome, saturation clamps).
            // Tunable without rebuild:
            //   adb shell su -c 'setprop persist.omnicam.xpan.videolook.cubegain 1.8'
            //   adb shell am force-stop com.oplus.camera
            float cubeGain = videoLookCubeGain();
            int i000 = (x0 * n + y0) * n + z0;
            int i100 = (x1 * n + y0) * n + z0;
            int i010 = (x0 * n + y1) * n + z0;
            int i110 = (x1 * n + y1) * n + z0;
            int i001 = (x0 * n + y0) * n + z1;
            int i101 = (x1 * n + y0) * n + z1;
            int i011 = (x0 * n + y1) * n + z1;
            int i111 = (x1 * n + y1) * n + z1;
            for (int c = 0; c < 3; c++) {
                float c00 = cube[i000 * 3 + c] * (1 - dx) + cube[i100 * 3 + c] * dx;
                float c10 = cube[i010 * 3 + c] * (1 - dx) + cube[i110 * 3 + c] * dx;
                float c01 = cube[i001 * 3 + c] * (1 - dx) + cube[i101 * 3 + c] * dx;
                float c11 = cube[i011 * 3 + c] * (1 - dx) + cube[i111 * 3 + c] * dx;
                float c0 = c00 * (1 - dy) + c10 * dy;
                float c1 = c01 * (1 - dy) + c11 * dy;
                t[c] = c0 * (1 - dz) + c1 * dz;
            }
            if (cubeGain != 1f) {
                // r498 guard (offline simulation): linear extrapolation flips a
                // monochrome cube's saturation THROUGH ZERO back into color
                // (k=2 restores the full input saturation). Keep k=1 on any point
                // where the cube desaturates noticeably -- those filters are already
                // obvious to the eye; only weak color shifts need the boost.
                float satIn = Math.max(rgb[0], Math.max(rgb[1], rgb[2]))
                    - Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
                float satCube = Math.max(t[0], Math.max(t[1], t[2]))
                    - Math.min(t[0], Math.min(t[1], t[2]));
                float k = satCube < satIn - 0.10f ? 1f : cubeGain;
                if (k != 1f) {
                    for (int c = 0; c < 3; c++) {
                        t[c] = Math.min(1f, Math.max(0f,
                            rgb[c] + (t[c] - rgb[c]) * k));
                    }
                }
            }
            System.arraycopy(t, 0, rgb, 0, 3);
            if (gain != 1f) {
                for (int c = 0; c < 3; c++) {
                    rgb[c] = Math.min(1f, Math.max(0f, rgb[c] * gain));
                }
            }
            transfer(rgb, domain, false);
            if (lift != 0f) {
                for (int c = 0; c < 3; c++) {
                    rgb[c] = Math.max(0f, rgb[c] - lift);
                }
            }
            if (mid != 0f) {
                for (int c = 0; c < 3; c++) {
                    float v = rgb[c];
                    rgb[c] = Math.min(1f, Math.max(0f, v + mid * v * (1f - v)));
                }
            }
            System.arraycopy(rgb, 0, out, i, 3);
        }
        return out;
    }

    /** r484: linear-domain exposure trim, default 0.85 (-6.5% display brightness). */
    static float videoLookGain() {
        try {
            float gain = Float.parseFloat(
                propertyStr("persist.omnicam.xpan.videolook.gain", "0.85"));
            if (gain < 0.1f) return 0.1f;
            if (gain > 2f) return 2f;
            return gain;
        } catch (NumberFormatException badValue) {
            return 0.85f;
        }
    }

    /**
     * r498: cube strength overdrive for the video-domain filter compose.
     * out = in + k*(cube(in)-in); 1.0 = exact filter, >1 exaggerates the color
     * look so the weak color cubes stay visible through the HDR video path.
     */
    static float videoLookCubeGain() {
        try {
            float k = Float.parseFloat(
                propertyStr("persist.omnicam.xpan.videolook.cubegain", "1.0"));
            if (k < 1f) return 1f;
            if (k > 3f) return 3f;
            return k;
        } catch (NumberFormatException badValue) {
            return 1f;
        }
    }

    /**
     * r491: midtone lift amount for the composed video table (display domain).
     * out = in + e*in*(1-in); 0 disables. Positive e brightens the middle (for the
     * EV-uncompensated video stream); r493 probe showed the AE compensation is a
     * SESSION-level EV (one value shared by preview and cover requests, set once at
     * session start, long before FilterPresenter.captureRequest), so request-level
     * splitting is impossible -- instead the global +2EV correction stays and this
     * curve runs NEGATIVE (r494) to pull the over-exposed video middle back down
     * while lift handles the black point. The two axes are independent, unlike the
     * old global-lift-only tuning.
     */
    static float videoLookMid() {
        try {
            float e = Float.parseFloat(
                propertyStr("persist.omnicam.xpan.videolook.mid", "0"));
            if (e < -2f) return -2f;
            if (e > 2f) return 2f;
            return e;
        } catch (NumberFormatException badValue) {
            return 0f;
        }
    }

    /**
     * r485: de-veil offset subtracted in the table OUTPUT (display) domain.
     * Positive values lower the video's black point toward the cover's; the stock
     * video path has always lifted shadows relative to the still path.
     */
    static float videoLookLift() {
        try {
            float lift = Float.parseFloat(
                propertyStr("persist.omnicam.xpan.videolook.lift", "0.05"));
            if (lift < -0.1f) return -0.1f;
            if (lift > 0.25f) return 0.25f;
            return lift;
        } catch (NumberFormatException badValue) {
            return 0.05f;
        }
    }

    /** r483: color-domain transfer for the cube compose ("linear"/"pq"/"gamma"). */
    private static void transfer(float[] rgb, String domain, boolean toLinear) {
        if ("gamma".equals(domain)) return;
        for (int c = 0; c < 3; c++) {
            float v = Math.min(1f, Math.max(0f, rgb[c]));
            if ("pq".equals(domain)) {
                rgb[c] = toLinear ? pqEotf(v) : pqOetf(v);
            } else {
                // sRGB piecewise (2.4 exponent), the closest match to a shader's
                // gamma-decode/encode around an sRGB-space LUT.
                rgb[c] = toLinear ? srgbEotf(v) : srgbOetf(v);
            }
        }
    }

    private static float srgbEotf(float v) {
        return v <= 0.04045f ? v / 12.92f
            : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static float srgbOetf(float v) {
        return v <= 0.0031308f ? v * 12.92f
            : (float) (1.055 * Math.pow(Math.max(0f, v), 1 / 2.4) - 0.055);
    }

    private static float pqEotf(float p) {
        final float m1 = 2610f / 16384f, m2 = 2523f / 4096f * 128f;
        final float c1 = 3424f / 4096f, c2 = 2413f / 4096f * 32f,
            c3 = 2392f / 4096f * 32f;
        p = Math.min(1f, Math.max(1e-6f, p));
        float p12 = (float) Math.pow(p, 1f / m2);
        float x = Math.max(0f, p12 - c1) / (c2 - c3 * p12);
        return (float) Math.pow(x, 1f / m1);
    }

    private static float pqOetf(float y) {
        final float m1 = 2610f / 16384f, m2 = 2523f / 4096f * 128f;
        final float c1 = 3424f / 4096f, c2 = 2413f / 4096f * 32f,
            c3 = 2392f / 4096f * 32f;
        y = Math.min(1f, Math.max(1e-9f, y));
        float ym = (float) Math.pow(y, m1);
        return (float) Math.pow((c1 + c2 * ym) / (1 + c3 * ym), m2);
    }

    /** r472: short state line for the video-look swap log. */
    public static String xpanVideoLookState() {
        return "filter=" + xpanFilterType
            + ", manager[" + managerBasicSnapshot() + "]";
    }

    /** r468 probe table: unmistakable red-biased 64^3, shared with the master probe. */
    public static float[] redProbeTable() {        float[] table = redProbeTable;
        if (table != null) return table;
        final int size = 64, depth = 3;
        float[] data = new float[size * size * size * depth];
        int index = 0;
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    data[index++] = Math.min(1f, 0.55f + r / (float) (size - 1) * 0.45f);
                    data[index++] = g / (float) (size - 1) * 0.15f;
                    data[index++] = b / (float) (size - 1) * 0.15f;
                }
            }
        }
        redProbeTable = data;
        return data;
    }

    private static void applyVideoChannelProbe(Object canvas) {
        String mode = propertyStr("persist.omnicam.xpan.videochannel", "");
        if (mode.isEmpty() || canvas == null) return;
        try {
            float[] table = redProbeTable();
            boolean pushed = false;
            if (mode.contains("o") || "all".equals(mode)) {
                XposedHelpers.callMethod(canvas, "r", table, 64, true);
                pushed = true;
            }
            if (mode.contains("n") && !mode.contains("none") || "all".equals(mode)) {
                XposedHelpers.callMethod(canvas, "Z", table, 64, true);
                pushed = true;
            }
            if (mode.contains("u1") || "all".equals(mode)) {
                XposedHelpers.callMethod(canvas, "e0", table, 1, 64);
                pushed = true;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - redProbeLoggedUptime > 5000) {
                redProbeLoggedUptime = now;
                Log.i(TAG, "XPAN video channel probe active, mode=" + mode
                    + ", pushedCanvas=" + pushed
                    + ", hash=" + Arrays.hashCode(table));
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN video channel probe failed, mode=" + mode, error);
        }
    }

    /**
     * r565: canvas layer for 7.006.317/352, where {@code common.gl.s} no longer declares a draw
     * or a float[] entry. See the {@code modernCanvas} field comment for the name mapping. Only
     * the XPAN BasicTone door ({@code w} -> field o) and the 17^3 slot door ({@code j0} -> map u)
     * are installed; everything else stays stock.
     *
     * <p>No canvas override of the filter composite is taken here on purpose: on this build the
     * {@code w} push is also how the native republishes its own palette, so capturing it for the
     * video bridge without a field test would risk feeding the clip the wrong table. The task.d
     * bridge keeps using the table captured on the 123 build.
     */
    private static void installModernCanvasLayer(ClassLoader classLoader) {
        final Class<?> canvasClass;
        try {
            canvasClass = XposedHelpers.findClass(
                "com.oplus.camera.common.gl.n", classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN gl.n canvas class unavailable", error);
            return;
        }
        Method pushBasicTone = null;
        Method pushSlot = null;
        Method pushFieldN = null;
        try {
            pushBasicTone = XposedHelpers.findMethodExact(canvasClass, "w",
                float[].class, int.class, boolean.class);
        } catch (Throwable ignored) {
        }
        try {
            pushSlot = XposedHelpers.findMethodExact(canvasClass, "j0",
                float[].class, int.class, int.class);
        } catch (Throwable ignored) {
        }
        try {
            pushFieldN = XposedHelpers.findMethodExact(canvasClass, "e0",
                float[].class, int.class, boolean.class);
        } catch (Throwable ignored) {
        }
        if (pushBasicTone == null) {
            Log.i(TAG, "XPAN gl.n layer: BasicTone door missing, preview self-heal stays off");
            return;
        }
        XposedBridge.hookMethod(pushBasicTone, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                modernCanvas = param.thisObject;
                if (!(param.args[0] instanceof float[])) return;
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                float[] data = (float[]) param.args[0];
                int size = param.args[1] instanceof Number
                    ? ((Number) param.args[1]).intValue() : -1;
                boolean enabled = Boolean.TRUE.equals(param.args[2]);
                if (!modernCanvasLogged) {
                    modernCanvasLogged = true;
                    Log.i(TAG, "XPAN gl.n canvas captured, size=" + size
                        + ", enabled=" + enabled
                        + ", floats=" + data.length
                        + ", canvas=" + Integer.toHexString(
                            System.identityHashCode(param.thisObject)));
                }
            }
        });
        if (pushSlot != null) {
            XposedBridge.hookMethod(pushSlot, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof float[])) return;
                    modernCanvas = param.thisObject;
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    int slot = param.args[1] instanceof Number
                        ? ((Number) param.args[1]).intValue() : -1;
                    int size = param.args[2] instanceof Number
                        ? ((Number) param.args[2]).intValue() : -1;
                    if (slot > 0 && size == 17 && xpan17Captures.size() < 8) {
                        float[] data = (float[]) param.args[0];
                        xpan17Captures.put(slot,
                            java.util.Arrays.copyOf(data, data.length));
                    }
                }
            });
        }
        if (pushFieldN != null) {
            XposedBridge.hookMethod(pushFieldN, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    modernCanvas = param.thisObject;
                }
            });
        }
        Log.i(TAG, "XPAN gl.n canvas layer armed, basicTone=" + (pushBasicTone != null)
            + ", slot=" + (pushSlot != null) + ", fieldN=" + (pushFieldN != null));
    }

    /**
     * r565: read-back repair for the refactored canvas. Mirrors the r464 logic: compare what the
     * canvas actually holds in field o ({@code gl.r0.z}) with the clean XPAN table and re-push
     * only a real mismatch, through the {@code w} door. Runs from the GL draw counter, because
     * this build exposes no per-frame preview draw we could hook directly.
     *
     * <p>Guarded three ways: the clean table must exist (otherwise there is no authoritative
     * answer to push), a stream that is currently flowing is never disturbed, and repairs are
     * rate limited so a flap cannot turn into a per-frame re-upload storm.
     */
    private static void repairModernCanvasIfWashed() {
        if (!XPAN_MODE.equals(currentCameraMode)) return;
        Object canvas = modernCanvas;
        if (canvas == null || installedManagerHolder == null) return;
        if (isXpanLiveFrameFlowing()) return;
        float[] clean = GrPreviewStreamRestore.cleanXpanTable;
        if (clean == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - modernRepairAt < 1500L) return;
        Object manager;
        int size;
        try {
            manager = XposedHelpers.getStaticObjectField(installedManagerHolder, "a");
            size = XposedHelpers.getIntField(manager, "t");
        } catch (Throwable error) {
            return;
        }
        if (size <= 0) return;
        float[] table = chooseXpanBasicToneTable(manager, size);
        if (table == null || table.length != size * size * size * 3) return;
        float[] have = modernCanvasTable(canvas);
        if (have == null) return;
        long want = tableContentHash(table);
        if (have.length == table.length && tableContentHash(have) == want) return;
        modernRepairAt = now;
        try {
            XposedHelpers.callMethod(canvas, "w", table, size, true);
            if (modernRepairCount < 16) {
                modernRepairCount++;
                Log.i(TAG, "XPAN preview BasicTone re-pushed via gl.n.w"
                    + ", wash=" + GrPreviewStreamRestore.paletteWashes.get()
                    + ", was=" + (have.length == table.length
                        ? String.valueOf(tableContentHash(have)) : "len=" + have.length)
                    + ", want=" + want
                    + ", canvas=" + Integer.toHexString(
                        System.identityHashCode(canvas)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN gl.n BasicTone re-push failed", error);
        }
    }

    /** r565: field o of the refactored canvas is a {@code gl.r0}; its data lives in {@code z}. */
    private static float[] modernCanvasTable(Object canvas) {
        try {
            Object slot = XposedHelpers.getObjectField(canvas, "o");
            if (slot == null) return null;
            Object raw = XposedHelpers.getObjectField(slot, "z");
            if (raw instanceof float[]) return (float[]) raw;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** True when the s-channel override (renderer.s) is requested by the probe. */
    private static boolean redProbeWantsRenderer() {
        return propertyStr("persist.omnicam.xpan.videochannel", "").contains("s");
    }

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
            ensureXpanPreviewTables(canvas, mode, wash, true);
            previewRepairWash = wash;
        } catch (Throwable error) {
            // Consume the generation even on failure so one broken push cannot turn into
            // a per-frame retry storm; the next wash retries.
            previewRepairWash = wash;
            Log.e(TAG, "XPAN preview BasicTone re-upload failed", error);
        }
    }

    /*
     * r464: read-back based repair. The r463 content dedup (what WE pushed last) was
     * defeated on 2026-11-21 08:06:03/08:06:18: the wash's own e(1,2,"common") step
     * makes the native re-push the COMMON table into gl.s field o, the follow-up
     * e(1,0,"xpan") is skipped by the native's dirty-check dedup, and our repair then
     * saw "desired == last pushed" and did nothing -- field o stayed on the common
     * table and the preview stayed grey until the next mode switch. The fix compares
     * the canvas's ACTUAL field o / u-slot content (t0.A float hash) with the desired
     * tables and only pushes real mismatches, so re-contamination of any origin
     * (native flap, wash side effect, canvas rebuild) self-heals.
     */
    private static void ensureXpanPreviewTables(
        Object canvas, String mode, long wash, boolean washDriven) {
        boolean viaR = "r".equals(mode);
        Object manager = XposedHelpers.getStaticObjectField(
            installedManagerHolder, "a");
        int size = XposedHelpers.getIntField(manager, "t");
        float[] table = viaR
            ? chooseXpanBasicToneTable(manager, size)
            : (float[]) XposedHelpers.getObjectField(manager, "c");
        if (size <= 0 || table == null || table.length == 0) return;
        long want = tableContentHash(table);
        long have = viaR
            ? t0ContentHash(XposedHelpers.getObjectField(canvas, "o"))
            : previewRepairHash;
        boolean basicPushed = false;
        if (have != want) {
            if (viaR) {
                XposedHelpers.callMethod(canvas, "r", table, size, true);
            } else {
                XposedHelpers.callMethod(canvas, "e0", table, 1, size);
            }
            previewRepairHash = want;
            basicPushed = true;
        }
        int slots17 = viaR ? ensureXpan17Captures(canvas) : 0;
        if ((basicPushed || slots17 > 0) && previewLutReuploadLogged < 16) {
            previewLutReuploadLogged++;
            Log.i(TAG, "XPAN preview tables re-pushed"
                + ", door=gl.s." + (viaR ? "r" : "e0")
                + ", " + (washDriven ? "wash=" + wash : "periodic")
                + ", basicPushed=" + basicPushed
                + ", slots17=" + slots17
                + ", fieldOWas=" + have
                + ", want=" + want
                + ", canvas=" + System.identityHashCode(canvas));
        }
    }

    private static long t0ContentHash(Object texture) {
        if (texture == null) return -1;
        try {
            Object raw = XposedHelpers.getObjectField(texture, "A");
            if (raw instanceof float[]) return tableContentHash((float[]) raw);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /*
     * r463/r464: re-push the captured XPAN 17^3 preview tables (e0 slot / nn.l callback
     * index). The native delivers these only on a fresh XPAN activation; on a re-entry
     * (GR -> XPAN measured 2026-11-21 07:29:35) its dirty-check dedup skips them while
     * the canvas rebuild dropped the textures, leaving the preview grey no matter how
     * clean the 64^3 table is. r464: dedup by the canvas's ACTUAL u-slot content, not
     * by what we pushed last -- a wash's common step or a rebuild can re-break the
     * slots at any time.
     */
    private static int ensureXpan17Captures(Object canvas) {
        int pushed = 0;
        Object u = null;
        try {
            u = XposedHelpers.getObjectField(canvas, "u");
        } catch (Throwable ignored) {
        }
        for (java.util.Map.Entry<Integer, float[]> entry : xpan17Captures.entrySet()) {
            int slot = entry.getKey();
            float[] data = entry.getValue();
            if (slot <= 0 || data == null || data.length == 0) continue;
            long want = tableContentHash(data);
            long have = -1;
            if (u instanceof java.util.Map) {
                Object holder = ((java.util.Map<?, ?>) u).get(Integer.valueOf(slot));
                have = t0ContentHash(holder);
            }
            if (have == want) continue;
            try {
                XposedHelpers.callMethod(canvas, "e0", data, slot, 17);
                pushed++;
            } catch (Throwable error) {
                Log.e(TAG, "XPAN 17^3 slot re-push failed, slot=" + slot, error);
            }
        }
        return pushed;
    }

    /*
     * r463: read back what the canvas actually holds at draw time -- field n (Z entry
     * target), field o (r entry target, the 64^3 BasicTone) and the e0 HashMap u. t0
     * holders expose b=size, z=depth and A=raw float[] data. This closes the loop on
     * "we pushed it" vs "the canvas still samples something else".
     */
    private static String describeCanvasLutState(Object canvas) {
        try {
            StringBuilder sb = new StringBuilder("fieldN=");
            sb.append(describeT0(XposedHelpers.getObjectField(canvas, "n")));
            sb.append(" fieldO=").append(
                describeT0(XposedHelpers.getObjectField(canvas, "o")));
            sb.append(" u={");
            Object u = XposedHelpers.getObjectField(canvas, "u");
            if (u instanceof java.util.Map) {
                int shown = 0;
                for (java.util.Map.Entry<?, ?> e
                    : ((java.util.Map<?, ?>) u).entrySet()) {
                    if (shown++ >= 10) break;
                    sb.append(e.getKey()).append(':')
                        .append(describeT0(e.getValue())).append(',');
                }
            }
            sb.append('}');
            return sb.toString();
        } catch (Throwable error) {
            return "state=unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static String describeT0(Object texture) {
        if (texture == null) return "null";
        try {
            Object raw = XposedHelpers.getObjectField(texture, "A");
            long hash = raw instanceof float[]
                ? tableContentHash((float[]) raw) : -1;
            return "t0{b=" + XposedHelpers.getIntField(texture, "b")
                + ",z=" + XposedHelpers.getIntField(texture, "z")
                + ",len=" + (raw instanceof float[] ? ((float[]) raw).length : -1)
                + ",hash=" + hash + "}";
        } catch (Throwable error) {
            return "t0{unreadable:" + error.getClass().getSimpleName() + "}";
        }
    }

    /*
     * r466: always-on filter STATE tracker. The live video bridge picks the
     * filter-composited BasicTone table whenever a filter is active, so this must run
     * in production -- only the verbose r453 probe logging stays behind
     * persist.omnicam.xpan.filterprobe. One reflection read per previewRequest, no
     * logging.
     */
    private static void hookXpanFilterState(ClassLoader classLoader) {
        try {
            Method previewRequest = local.jiege.hook.common.Symbols.get()
                .method("FilterPresenter.previewRequest");
            XposedBridge.hookMethod(previewRequest, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Object request = param.getResult();
                    if (request == null) return;
                    String currType = safeField(request, "mCurrFilterType");
                    String tracked = (currType == null
                        || "null".equals(currType) || "?".equals(currType)
                        || currType.isEmpty() || "default".equals(currType))
                        ? null : currType;
                    xpanFilterType = tracked;
                    // r497: remember the last real filter for the restart fallback.
                    // An explicit "default" OVERWRITES the file, so switching back to
                    // the standard filter does not resurrect the previous color filter.
                    if (tracked != null) {
                        persistLastVideoLookFilter(tracked);
                    } else if ("default".equals(currType)) {
                        persistLastVideoLookFilter("default");
                    }
                    // r479: the r467 native compose path is RETIRED. Reflection into
                    // CombineLutHelper.process corrupted the native context (r467 already
                    // measured names=[null x64], output degenerate), and on release the
                    // helper then free()d a clobbered pointer -- native crash SIGABRT
                    // "Pointer tag ... truncated" in libCombineLut.so lutFree, reached
                    // through our Method.invoke (dropbox system_app_native_crash 07:41).
                    // The r-entrance capture (gl.s.r push of the 64^3 preview composite)
                    // is the only composite source now: it delivers the exact table the
                    // preview renders, with zero reconstruction.
                }
            });
            Log.i(TAG, "XPAN filter state tracker installed (r466)");
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter state tracker: previewRequest unavailable", error);
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
                    // r477: NO invalidation here. r476 field test: the user swiped the
                    // strip through every index back to fuji-nc within 4s and shot -- the
                    // wipe threw away a still-correct composite and the native never
                    // re-pushed (content unchanged -> dedup). Staleness is handled by the
                    // learned index->type tag at capture time and the tag match at use
                    // time instead.
                    xpanFilterPresenter = param.thisObject;
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
            /* The preview renderer class is symbol-resolved (@PreviewRenderer); its callback
             * inner class keeps the positional "$a" name on both builds (fingerprint-verified). */
            Class<?> callback = XposedHelpers.findClass(
                    cls("@PreviewRenderer").getName() + "$a", classLoader);
            XposedBridge.hookAllMethods(callback, "b", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    if (param.args.length < 3 || !(param.args[0] instanceof float[])) return;
                    float[] data = (float[]) param.args[0];
                    int hash = Arrays.hashCode(data);
                    // r463: capture the 17^3 preview LUT the native hands to the canvas,
                    // keyed by the callback index (measured to match the e0 slot). Capture
                    // BEFORE the change-dedup return so an unchanged re-delivery still
                    // refreshes the capture store.
                    int cbIndex = param.args[1] instanceof Number
                        ? ((Number) param.args[1]).intValue() : -1;
                    int cbSize = param.args[2] instanceof Number
                        ? ((Number) param.args[2]).intValue() : -1;
                    if (cbIndex > 0 && cbSize == 17 && xpan17Captures.size() < 8) {
                        xpan17Captures.put(cbIndex,
                            java.util.Arrays.copyOf(data, data.length));
                    }
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
                    xpanFilterPresenter = param.thisObject;
                    Log.i(TAG, "XPAN filter captureRequest"
                        + ", currentIndex=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: captureRequest hook unavailable", error);
        }
        try {
            Method apply = symbols.method("FilterPresenter.apply");
            XposedBridge.hookMethod(apply, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Log.i(TAG, "XPAN filter apply BEFORE"
                        + ", args=" + Arrays.toString(param.args)
                        + ", currentIndex=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    // r477: keep the presenter reference for index->type tagging; no
                    // invalidation (see the select hook comment).
                    xpanFilterPresenter = param.thisObject;
                    Log.i(TAG, "XPAN filter apply AFTER"
                        + ", currentIndex=" + currentIndex(param.thisObject)
                        + ", manager[" + managerBasicSnapshot() + "]");
                    scheduleFilterManagerSnapshots(param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: apply hook unavailable", error);
        }
        try {
            Method previewRequest = symbols.method("FilterPresenter.previewRequest");
            XposedBridge.hookMethod(previewRequest, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    Object request = param.getResult();
                    if (request == null) return;
                    // r477: keep the presenter and learn the strip-index -> type pair --
                    // the composite capture happens on the GL thread and can only tag
                    // itself through this map.
                    xpanFilterPresenter = param.thisObject;
                    // r466: track the active filter type BEFORE the log dedup -- the video
                    // bridge needs this even when the log line is suppressed.
                    String currType = safeField(request, "mCurrFilterType");
                    boolean typeValid = currType != null && !"null".equals(currType)
                        && !"?".equals(currType) && !currType.isEmpty()
                        && !"default".equals(currType);
                    xpanFilterType = typeValid ? currType : null;
                    learnXpanIndexType(currentIndex(param.thisObject),
                        typeValid ? currType : null);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - filterPreviewRequestLogged < 1500) return;
                    filterPreviewRequestLogged = now;
                    Log.i(TAG, "XPAN filter previewRequest -> "
                        + request.getClass().getSimpleName()
                        + ", currentIndex=" + currentIndex(param.thisObject)
                        + ", currType=" + currType
                        + ", currIntensity=" + safeField(request, "mCurrFilterIntensity")
                        + ", pendingType=" + safeField(request, "mPendingFilterType")
                        + ", capMode=" + safeField(request, "mCapModeName"));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: previewRequest hook unavailable", error);
        }
        try {
            Class<?> filterConstant = XposedHelpers.findClass(
                "com.oplus.camera.filter.FilterConstant", classLoader);
            XposedBridge.hookAllMethods(filterConstant, "buildLutPath",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!XPAN_MODE.equals(currentCameraMode)) return;
                        String path = String.valueOf(param.getResult());
                        if (path.equals(lastLutPath) || "null".equals(path)) return;
                        lastLutPath = path;
                        Log.i(TAG, "XPAN filter buildLutPath"
                            + (param.args.length > 0 ? "(" + param.args[0] + ")" : "()")
                            + " -> " + path
                            + ", caller=" + (new Throwable().getStackTrace().length > 2
                                ? new Throwable().getStackTrace()[2].getClassName()
                                    + "." + new Throwable().getStackTrace()[2].getMethodName()
                                : "?"));
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: buildLutPath hook unavailable", error);
        }
        try {
            Class<?> helper = XposedHelpers.findClass(
                "com.oplus.camera.filter.FilterHelper", classLoader);
            XposedBridge.hookAllMethods(helper, "getFilterTypeByIndex",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!XPAN_MODE.equals(currentCameraMode)) return;
                        Log.i(TAG, "XPAN filter getFilterTypeByIndex"
                            + "(" + (param.args.length > 0 ? param.args[0] : "?")
                            + ") -> " + param.getResult()
                            + ", caller=" + (new Throwable().getStackTrace().length > 2
                                ? new Throwable().getStackTrace()[2].getClassName()
                                    + "." + new Throwable().getStackTrace()[2].getMethodName()
                                : "?"));
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "XPAN filter probe: FilterHelper hook unavailable", error);
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

    /** r492: deep, noise-filtered stack for the EV-scope probe (20 app frames). */
    private static String evScopeStack() {
        StringBuilder text = new StringBuilder(", stack=");
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int added = 0;
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("local.jiege")
                || cls.startsWith("de.robv")
                || cls.startsWith("java.lang.reflect")
                || cls.contains("XposedBridge")
                || cls.contains("LSPosed")
                || cls.contains(".k.") && frame.getMethodName().startsWith("proceed")
                || frame.getMethodName().equals("intercept")) {
                continue;
            }
            text.append(cls).append('.').append(frame.getMethodName()).append(':')
                .append(frame.getLineNumber()).append('\n');
            if (++added >= 20) break;
        }
        return text.toString();
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
            cls(GL_RENDERER);
        final Class<?> shader =
            XposedHelpers.findClass(GL_SHADER, classLoader);
        final Class<?> managerHolder =
            XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);
        installedGlRenderer = glRenderer;
        installedManagerHolder = managerHolder;
        final Class<?> presenter =
            cls(LIVE_PHOTO_PRESENTER);
        final Class<?> liveWarpRenderer =
            cls(LIVE_WARP_RENDERER);
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
            cls(VIDEO_EFFECT_PIPELINE),
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
                            + ", o0Hash=" + (parameters == null ? -1
                                : System.identityHashCode(parameters))
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
                                + ", paramG=" + (parameters == null ? "?"
                                    : XposedHelpers.getBooleanField(parameters, "g"))
                                + ", o0Hash=" + (parameters == null ? -1
                                    : System.identityHashCode(parameters))
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
        XposedBridge.hookAllMethods(liveWarpRenderer,
            member("@WarpRenderer.draw"), new XC_MethodHook() {
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
         *
         * r525: this bridge IS the r199 "Log grey" fix. The fingerprint migration turned
         * LIVE_WARP_RENDERER into the symbol id ("@WarpRenderer"), so the old
         * id.equals(class.getName()) guards could never match and the bridge was dead on
         * 7.006.317: the stock task.d table is a plain colour-space LUT (grey axis = identity,
         * measured on the dumped table), so without this program swap the clip is never
         * developed and plays back desaturated (~27% of the cover saturation). Compare the
         * resolved class, never the raw id.
         */
        // The draw entry's first parameter IS the texture type; its short name drifts
        // between builds, so derive the class from the resolved symbol instead of a
        // hardcoded descriptor.
        Method commonDraw = Symbols.get().method("@CommonShader.commonDraw");
        final Class<?> texture = commonDraw.getParameterTypes()[0];
        XposedBridge.hookMethod(commonDraw, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object renderer = param.thisObject;
                if (renderer == null
                    || !cls(LIVE_WARP_RENDERER).equals(renderer.getClass())
                    || !XPAN_MODE.equals(currentCameraMode)
                    || XPAN_MAIN_FRAME.get() == null) {
                    return;
                }
                try {
                    // r565 A/B switch. On 7.006.317/352 the main frame reaches this draw as
                    // "common" (warpDraws=0 in the frame log), unlike the 123 build this bridge
                    // was written for. Swapping the common program for warp-basic there is the
                    // prime suspect for the all-white XPAN cover, so it can be turned off
                    // without a rebuild: setprop persist.omnicam.xpan.commonbridge 0.
                    if ("0".equals(propertyStr("persist.omnicam.xpan.commonbridge", "1"))) {
                        return;
                    }
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
                    || !cls(LIVE_WARP_RENDERER).equals(renderer.getClass())
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
                    || !cls(LIVE_WARP_RENDERER).equals(renderer.getClass())
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
            cls(WATERMARK_PROCESSOR);
        final Class<?> composite =
            cls(WATERMARK_COMPOSITE);
        final Class<?> lutParameters =
            cls(LUT_PARAMETERS);
        /*
         * r576: WatermarkProcessor.b is no longer the only arm source. The encoder calls
         * it from OUTSIDE the gh.p3.r draw scope, so the XPAN_MAIN_FRAME ThreadLocal read
         * here used to be null and the arm silently never fired (r575 field log: the
         * watermarked XPAN live capture at 21:45:39-42 produced no "composite armed", no
         * composite probes and no skip logs -- the hook was dead, and the clip came out
         * Log / 2 stops dark). The hook now (a) probes every b() shape seen in XPAN so a
         * signature drift is visible in the log, and (b) feeds the legacy thread-local
         * signal that the composite hook still honours as one of its two arm paths.
         */
        XposedBridge.hookAllMethods(processor, "b", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)) return;
                if (watermarkProcessorProbe < 12) {
                    watermarkProcessorProbe++;
                    StringBuilder shape = new StringBuilder();
                    for (int i = 0; i < Math.min(param.args.length, 3); i++) {
                        shape.append(", arg").append(i).append('=')
                            .append(param.args[i] == null ? "null"
                                : param.args[i].getClass().getName());
                    }
                    XpanMainFrame frame = XPAN_MAIN_FRAME.get();
                    Log.i(TAG, "XPAN watermark processor b probe #"
                        + watermarkProcessorProbe
                        + ", args=" + param.args.length + shape
                        + ", threadFrame=" + (frame == null
                            ? "none" : String.valueOf(frame.watermarkOpen))
                        + ", lastMainWatermarkOpen=" + lastMainWatermarkOpen);
                }
                if (param.args.length == 2) {
                    WATERMARK_COMPOSITE_ACTIVE.set(Boolean.TRUE);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 2) {
                    WATERMARK_COMPOSITE_ACTIVE.remove();
                    /*
                     * r579: y3.b has no early return that skips the composite invoke
                     * (verified on the 317 dump -- the only branch past it is the
                     * exception handler), yet the filtered capture produced zero
                     * composite logs. Record completion and the processor's u/r/s
                     * state flags so a throw or a blocked shot-info wait shows up.
                     */
                    if (watermarkProcessorDoneProbe < 6) {
                        watermarkProcessorDoneProbe++;
                        Throwable thrown = param.getThrowable();
                        Object self = param.thisObject;
                        String flags;
                        try {
                            flags = "u=" + XposedHelpers.getBooleanField(self, "u")
                                + ", r=" + XposedHelpers.getBooleanField(self, "r")
                                + ", s=" + XposedHelpers.getBooleanField(self, "s");
                        } catch (Throwable ignored) {
                            flags = "u/r/s unreadable";
                        }
                        Log.i(TAG, "XPAN watermark processor b "
                            + (thrown == null ? "done" : "threw")
                            + " #" + watermarkProcessorDoneProbe
                            + ", " + flags
                            + ", filter=" + xpanFilterType
                            + (thrown == null ? "" : ", throwable=" + thrown));
                    }
                }
            }
        });
        XposedBridge.hookAllMethods(composite,
            member("@WatermarkRenderer.composite"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 9
                    || param.method == null
                    || !cls(WATERMARK_COMPOSITE).equals(
                        param.method.getDeclaringClass())) {
                    /*
                     * r579: never silent here again. The filtered capture fired b
                     * probes yet produced zero composite logs -- either the composite
                     * is reached with a different signature/declaring class, or it is
                     * not reached at all. Census any shape that passes the hook.
                     */
                    if (param.method != null && watermarkCompositeCensus < 12) {
                        watermarkCompositeCensus++;
                        StringBuilder shape = new StringBuilder();
                        for (int i = 0; i < Math.min(param.args.length, 4); i++) {
                            shape.append(", arg").append(i).append('=')
                                .append(param.args[i] == null ? "null"
                                    : param.args[i].getClass().getName());
                        }
                        Log.i(TAG, "XPAN watermark composite census #"
                            + watermarkCompositeCensus
                            + ", method=" + param.method.getDeclaringClass()
                                .getName() + "." + param.method.getName()
                            + ", args=" + param.args.length + shape
                            + ", filter=" + xpanFilterType);
                    }
                    return;
                }
                if (!XPAN_MODE.equals(currentCameraMode)
                    || property(XPAN_LIVE_WATERMARK_PROPERTY, 1) == 0) {
                    return;
                }
                /*
                 * r576: two independent arm paths. The legacy thread-local (set by the
                 * WatermarkProcessor.b probe above) still works when the composite runs
                 * nested in b(); the frame-freshness path covers the encoder-thread calls
                 * where the thread-local is gone: while the attachment records, watermarked
                 * main frames keep lastMainWatermarkOpen true and refresh lastMainFrameAt
                 * every ~33 ms, so a composite within 500 ms of a watermarked frame belongs
                 * to this clip's watermark pass (isXpanLiveFrameFlowing is the same 500 ms
                 * window the env-wash guard already trusts).
                 */
                boolean inProcessor =
                    Boolean.TRUE.equals(WATERMARK_COMPOSITE_ACTIVE.get());
                boolean frameFresh = lastMainWatermarkOpen && isXpanLiveFrameFlowing();
                if (!inProcessor && !frameFresh) {
                    if (!watermarkCompositeGateLogged) {
                        watermarkCompositeGateLogged = true;
                        Log.i(TAG, "XPAN watermark composite gate closed"
                            + ", inProcessor=" + inProcessor
                            + ", lastMainWatermarkOpen=" + lastMainWatermarkOpen
                            + ", frameFlowing=" + isXpanLiveFrameFlowing()
                            + ", mode=" + currentCameraMode);
                    }
                    return;
                }
                if (!watermarkCopyLogged) {
                    watermarkCopyLogged = true;
                    Log.i(TAG, "XPAN live watermark composite armed"
                        + ", arm=" + (inProcessor ? "processor-b" : "frame-freshness")
                        + ", gradedParams=" + (activeLutParameters == null
                            ? "null" : "present")
                        + ", mode=" + currentCameraMode);
                }
                try {
                    Object graded = activeLutParameters;
                    if (graded == null || !lutParameters.isInstance(graded)) {
                        if (!watermarkCompositeSkippedLogged) {
                            watermarkCompositeSkippedLogged = true;
                            Log.i(TAG, "XPAN watermark composite left stock, no graded"
                                + " LutParameters, graded=" + (graded == null
                                    ? "null" : graded.getClass().getName())
                                + ", arm=" + (inProcessor
                                    ? "processor-b" : "frame-freshness")
                                + ", mode=" + currentCameraMode);
                        }
                        return;
                    }
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
                            Object cached = lastBasicToneTexture;
                            Log.i(TAG, "XPAN watermark composite left stock, no valid"
                                + " BasicTone texture"
                                + ", rendererValid=" + rendererTextureUsable
                                + ", parameterValid=" + parameterTextureUsable
                                + ", cached=" + (cached == null ? "null"
                                    : "hash=" + System.identityHashCode(cached)
                                        + ",data=" + basicToneDataShape(cached))
                                + ", pipeline=" + (activePipeline == null ? "null" : "present")
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
                    /*
                     * r580: the BasicTone gate on the graded parameters is normally raised by
                     * the WarpRenderer.draw hook during the stock preview pass. With a filter
                     * selected the r568/r569 bypass redirects those frames, the hook never
                     * runs, and the gate stays false -- the composite then repaints the frame
                     * with uNeedBasicTone=0 and the clip plays back Log-dark (measured
                     * 05:23:27 window: g=true without a filter, g=false with one, same chain,
                     * same texture). Raise it here: the texture gate above already guarantees
                     * graded.e carries a complete BasicTone table, so uNeedBasicTone=1 cannot
                     * sample an unbound unit.
                     */
                    if (!XposedHelpers.getBooleanField(graded, "g")
                        && "0".equals(propertyStr(
                            "persist.omnicam.xpan.wmgforce", "1"))) {
                        if (!watermarkGateForcedLogged) {
                            watermarkGateForcedLogged = true;
                            Log.i(TAG, "XPAN watermark composite left the BasicTone gate"
                                + " off by property, filter=" + xpanFilterType
                                + ", mode=" + currentCameraMode);
                        }
                    } else if (!XposedHelpers.getBooleanField(graded, "g")) {
                        XposedHelpers.setBooleanField(graded, "g", true);
                        if (!watermarkGateForcedLogged) {
                            watermarkGateForcedLogged = true;
                            Log.i(TAG, "XPAN watermark composite forced the BasicTone"
                                + " gate on, filter=" + xpanFilterType
                                + ", mode=" + currentCameraMode);
                        }
                    }
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
        /*
         * r579: the VideoEffectPipeline owns a second watermark draw that also calls
         * WatermarkRenderer.composite. Census it in XPAN so the filtered branch, which
         * never reached the processor's composite, becomes visible in the log.
         */
        if (Symbols.get().has(VIDEO_EFFECT_PROCESSOR)) {
            XposedBridge.hookAllMethods(cls(VIDEO_EFFECT_PROCESSOR), "w",
                new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!XPAN_MODE.equals(currentCameraMode)) return;
                    if (videoEffectWatermarkProbe < 8) {
                        videoEffectWatermarkProbe++;
                        StringBuilder shape = new StringBuilder();
                        for (int i = 0; i < Math.min(param.args.length, 3); i++) {
                            shape.append(", arg").append(i).append('=')
                                .append(param.args[i] == null ? "null"
                                    : param.args[i].getClass().getName());
                        }
                        Log.i(TAG, "XPAN video effect watermark probe #"
                            + videoEffectWatermarkProbe
                            + ", args=" + param.args.length + shape
                            + ", filter=" + xpanFilterType
                            + ", lastMainWatermarkOpen=" + lastMainWatermarkOpen);
                    }
                }
            });
            Log.i(TAG, "XPAN video effect watermark census armed");
        }
        Log.i(TAG, "XPAN live watermark copy armed");
    }

    /**
     * The BasicTone texture the graded draw is using, or null while it is not built yet.
     *
     * <p>The last valid one is kept as a fallback. The watermark composite runs on the clip path,
     * where the frame's pipeline can hold no renderer at all: the 352 log line is
     * {@code "XPAN watermark composite left stock, no valid BasicTone texture, rendererValid=false,
     * parameterValid=false"} and the still comes out white. The table is a 64^3 packed RGB LUT that
     * this module uploads itself ({@code dataLength=786432}, hash -881252736), so a texture from an
     * earlier frame carries the same table; {@link #hasBasicToneData} re-checks on every use that its
     * backing float[] is still allocated, which is what the field-not-cleared trap needs.
     */
    private static Object rendererBasicToneTexture() {
        Object texture = null;
        Object pipeline = activePipeline;
        if (pipeline != null) {
            try {
                Object renderer = XposedHelpers.getObjectField(pipeline, "w");
                texture = renderer == null ? null : XposedHelpers.getObjectField(renderer, "s");
            } catch (Throwable error) {
                texture = null;
            }
        }
        if (hasBasicToneData(texture)) {
            lastBasicToneTexture = texture;
            return texture;
        }
        Object cached = lastBasicToneTexture;
        return hasBasicToneData(cached) ? cached : texture;
    }

    /** Last BasicTone texture seen with a complete table; the clip-side composite has no renderer. */
    private static volatile Object lastBasicToneTexture;

    /** Diagnostic: "null" or "size/data/len" of a texture's packed table, without touching it. */
    private static String basicToneDataShape(Object texture) {
        if (texture == null) return "null";
        try {
            int size = -1;
            try {
                size = XposedHelpers.getIntField(texture, "z");
            } catch (Throwable ignored) {
                // Field names untrustworthy on 317/352; report what we can.
            }
            float[] data = largestFloatArrayField(texture);
            return size + "/" + (data == null ? -1 : data.length);
        } catch (Throwable error) {
            return "unreadable";
        }
    }

    /**
     * r577: largest float[] field on the object, or null. The old checks trusted the
     * {@code @Raw3dTexture} fingerprint class plus hardcoded field names ("A"/"z"/"a"/"b");
     * on 317/352 at least one of those no longer matches, so EVERY texture -- including
     * ones this module had just uploaded -- failed validation. Field evidence: the r577
     * capture shows per-frame "BasicTone texture uploaded" yet the watermark composite
     * always read "no valid BasicTone texture, cached=null", so the composite stayed stock
     * and the clip remained Log / 2 stops dark.
     */
    private static float[] largestFloatArrayField(Object texture) {
        float[] data = null;
        for (Class<?> c = texture.getClass();
            c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != float[].class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(texture);
                    if (v instanceof float[]) {
                        float[] arr = (float[]) v;
                        if (arr.length > 0 && (data == null || arr.length > data.length)) {
                            data = arr;
                        }
                    }
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
        }
        return data;
    }

    /**
     * r577: a texture is usable only while its complete packed RGB table is still
     * allocated. Reflection-based so it survives the 317/352 obfuscation drift: find the
     * largest float[] field (the packed table) and require its length to match
     * {@code size^3 * 3}, or 786432 (64^3 x 3, the XPAN BasicTone table) when the size
     * field is unreadable. Released-texture shells (data freed by t0.l()) still read as
     * null/empty, so the FloatBuffer.wrap(null) crash protection is preserved.
     */
    private static boolean hasBasicToneData(Object texture) {
        if (texture == null) return false;
        try {
            int size = -1;
            try {
                size = XposedHelpers.getIntField(texture, "z");
            } catch (Throwable ignored) {
                // Field names untrustworthy on this build; expected falls back below.
            }
            long expected = size > 0
                ? (long) size * size * size * 3L
                : 786432L;
            float[] data = largestFloatArrayField(texture);
            return data != null && data.length == expected;
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
            cls(GL_RENDERER);
        final Class<?> shader =
            XposedHelpers.findClass(GL_SHADER, classLoader);
        final Class<?> presenter =
            cls(LIVE_PHOTO_PRESENTER);
        final Class<?> liveWarpRenderer =
            cls(LIVE_WARP_RENDERER);
        final Class<?> pipeline =
            cls(VIDEO_EFFECT_PIPELINE);

        /*
         * p3.r() has two mutually exclusive main-video draw calls. Instrument both entry points
         * independently of the BasicTone workaround so the frame log records the path actually
         * used by this version instead of inferring it from texture state.
         */
        XposedBridge.hookAllMethods(glRenderer,
            member("@CommonShader.commonDraw"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 10
                    || param.thisObject == null
                    || !cls(LIVE_WARP_RENDERER).equals(
                        param.thisObject.getClass())) {
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
        XposedBridge.hookAllMethods(presenter,
            member("@LivePhotoPresenter.frameGen"), new XC_MethodHook() {
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
        /*
         * r524: the 123 code compared the HARDCODED class name ("ch.g") here; the
         * fingerprint migration turned LIVE_WARP_RENDERER into the symbol id
         * "@WarpRenderer", so these census guards silently never matched and
         * basicBinds/ordinaryBinds read 0/0/0 on 317. Compare the resolved Class
         * instead so the counters (and the verification signature) come back.
         */
        final Class<?> warpRendererClass = cls(LIVE_WARP_RENDERER);
        XposedBridge.hookMethod(applyOrdinaryLut, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!XPAN_MODE.equals(currentCameraMode)
                    || param.thisObject == null
                    || !warpRendererClass.equals(param.thisObject.getClass())) {
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
                    || !warpRendererClass.equals(param.thisObject.getClass())) {
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

        XposedBridge.hookAllMethods(liveWarpRenderer,
            member("@WarpRenderer.draw"), new XC_MethodHook() {
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

        /*
         * Transform upload probe. The entry has a 5-parameter shape on older builds and
         * gains a trailing boolean on newer ones; hook whichever resolves.
         */
        java.util.List<Method> uploadEntries = new ArrayList<>();
        if (Symbols.get().has("@CommonShader.transformUpload")) {
            uploadEntries.add(Symbols.get().method("@CommonShader.transformUpload"));
        }
        if (Symbols.get().has("@CommonShader.transformUpload2")) {
            uploadEntries.add(Symbols.get().method("@CommonShader.transformUpload2"));
        }
        for (Method uploadEntry : uploadEntries) {
            XposedBridge.hookMethod(uploadEntry, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 5
                    || param.thisObject == null
                    || !cls(LIVE_WARP_RENDERER).equals(
                        param.thisObject.getClass())
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
        }

        try {
            final Class<?> callback = innerOf(cls(VIDEO_EFFECT_PIPELINE), "a");
            if (callback != null) {
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
            }
        } catch (Throwable error) {
            Log.e(TAG, "XPAN selected LUT callback probe unavailable", error);
        }

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
        final Class<?> encoder = cls(LIVE_VIDEO_ENCODER);
        XposedBridge.hookAllMethods(encoder,
            member("@LiveVideoEncoder.configureFormat"), new XC_MethodHook() {
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

        final Class<?> eglHelper = cls(EGL_HELPER);
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
        float[] lutData = chooseXpanVideoBasicToneTable(manager, lutSize);
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
        // r466: the chosen table is tracked by reference (composite copies and the clean
        // snapshot are stable defensive arrays; manager.c is mutated in place so its
        // reference stays and content changes ride on the wash generation). A filter
        // toggle swaps the reference, forcing a re-upload of renderer.s.
        boolean staleByTable = uploaded == null || uploaded.length < 3
            || uploaded[2] != lutData;
        if (existingValid && !staleByWash && !staleByForeign && !staleByTable) {
            rememberBasicToneTexture(existing);
            return true;
        }
        if (existingValid && staleByTable && !staleByWash && !staleByForeign
            && basicToneTextureUploadLogs < 8) {
            basicToneTextureUploadLogs++;
            Log.i(TAG, "XPAN live BasicTone table switched"
                + (xpanFilterType != null
                    ? ", filter=" + xpanFilterType : ", filter cleared")
                + ", source=" + source
                + ", renderer=" + rendererId
                + ", size=" + lutSize
                + ", dataLength=" + lutData.length);
        }
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
            rendererId, new Object[]{wash, texture, lutData});
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
        rememberBasicToneTexture(texture);
        return true;
    }

    /** Keeps the freshest uploaded texture for the clip-side watermark composite, which has no renderer. */
    private static void rememberBasicToneTexture(Object texture) {
        if (hasBasicToneData(texture)) {
            lastBasicToneTexture = texture;
            if (!basicToneCachedLogged) {
                basicToneCachedLogged = true;
                Log.i(TAG, "BasicTone texture cached for the watermark composite"
                    + ", hash=" + System.identityHashCode(texture));
            }
        }
    }

    private static volatile boolean basicToneCachedLogged;

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
     * r466: table selector for the live VIDEO bridge. While an XPAN filter is active the
     * native preview pipeline receives the filter-composited 64^3 table through
     * gl.s.e0(slot=1) (measured 2026-11-20: 385ms after the 800t apply, hash
     * -8384517229819880239) and the cover picks the filter up through the capture
     * request -- only the video renderer kept sampling the clean table, which is why
     * the cover showed the filter and the video did not. Prefer the captured composite;
     * fall back to the clean XPAN table when no filter is active or no composite has
     * been delivered yet.
     */
    private static float[] chooseXpanVideoBasicToneTable(Object manager, int lutSize) {
        // r468 probe: red table into renderer.s when the s channel is selected.
        if (redProbeWantsRenderer() && lutSize == 64) {
            return redProbeTable();
        }
        if (xpanFilterType != null) {
            float[] composite = xpanFilterCompositeTable;
            if (composite != null
                && composite.length == lutSize * lutSize * lutSize * 3) {
                return composite;
            }
        }
        return chooseXpanBasicToneTable(manager, lutSize);
    }

    /*
     * r467: compose the active XPAN filter into a 64^3 BasicTone table using the native
     * pipeline itself. Lut3dDataManager.p() (updateBasicToneData) composes through
     * CombineLutHelper.process(x, y, v, w, z, A, B, m, l) with every argument taken from
     * the manager's own fields -- we mirror that exact call on the manager's OWN helper
     * instance (field L, already initialized by stock) under the manager's own monitor
     * (field a). When the manager state carries the selected filter (field m), the
     * output is the same composite the stock preview pipeline uses; if the state does
     * not carry it, the output degenerates to the plain table and the video bridge
     * simply uploads that (no worse than the clean table). Failures log and leave the
     * captured-composite / clean-table fallbacks in place.
     */
    private static void composeXpanFilterTableNative() {
        String type = xpanFilterType;
        if (type == null || !XPAN_MODE.equals(currentCameraMode)) return;
        Class<?> holder = installedManagerHolder;
        if (holder == null) {
            Log.i(TAG, "XPAN native filter composition skipped, no manager holder");
            return;
        }
        try {
            Object manager = XposedHelpers.getStaticObjectField(holder, "a");
            if (manager == null) {
                Log.i(TAG, "XPAN native filter composition skipped, manager null");
                return;
            }
            Object helper = XposedHelpers.getObjectField(manager,
                member("@Lut3dDataManager.combineHelper"));
            if (helper == null) {
                Log.i(TAG, "XPAN native filter composition skipped, CombineLutHelper null");
                return;
            }
            String[] names = (String[]) XposedHelpers.getObjectField(manager, "m");
            int[] sizes = (int[]) XposedHelpers.getObjectField(manager, "l");
            Object monitor = XposedHelpers.getObjectField(manager, "a");
            float[] out = (float[]) XposedHelpers.callMethod(helper, "process",
                XposedHelpers.getIntField(manager, "x"),
                XposedHelpers.getIntField(manager, "y"),
                XposedHelpers.getIntField(manager, "v"),
                XposedHelpers.getIntField(manager, "w"),
                XposedHelpers.getFloatField(manager, "z"),
                XposedHelpers.getFloatField(manager, "A"),
                XposedHelpers.getFloatField(manager, "B"),
                names, sizes);
            // r479: guard must pin the EXPECTED size -- the old form
            // (out.length != outSize^3*3) passed an empty table when outSize was 0
            // (0 == 0), which then clobbered the good captured composite.
            int outSize = sizes != null && sizes.length > 0 ? sizes[0] : 64;
            if (out == null || outSize != 64 || out.length != 64 * 64 * 64 * 3) {
                Log.i(TAG, "XPAN native filter composition returned invalid table"
                    + ", filter=" + type
                    + ", names=" + (names == null ? "null" : java.util.Arrays.toString(names))
                    + ", sizes=" + (sizes == null ? "null" : java.util.Arrays.toString(sizes))
                    + ", outSize=" + outSize
                    + ", floats=" + (out == null ? -1 : out.length));
                return;
            }
            long hash = tableContentHash(out);
            boolean replaced = hash != xpanFilterCompositeHash;
            if (replaced) {
                xpanFilterCompositeTable = out;
                xpanFilterCompositeHash = hash;
            }
            xpanFilterComposedForType = type;
            Log.i(TAG, "XPAN filter table composed via native CombineLutHelper"
                + ", filter=" + type
                + ", names=" + (names == null ? "null" : java.util.Arrays.toString(names))
                + ", sizes=" + (sizes == null ? "null" : java.util.Arrays.toString(sizes))
                + ", outSize=" + outSize
                + ", floats=" + out.length
                + ", hash=" + hash
                + ", replacedPrior=" + replaced);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN native filter composition failed", error);
        }
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
        Class<?> containerClass = cls(MENU_CONTAINER_BASE);
        Class<?> optionClass = cls(MENU_OPTION_BASE);
        Class<?> topOptionClass = cls(MENU_OPTION_TOP);
        Class<?> settingTopClass = cls(CAMERA_SETTING_UI_TOP);

        XposedBridge.hookAllMethods(containerClass, member("@MenuContainer.injectList"), new XC_MethodHook() {
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
            || !container.getClass().getName().equals(cls(MENU_CONTAINER_TOP_LEFT).getName())) {
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
            Object cameraUnit = XposedHelpers.callMethod(
                camera, member("@Camera.getCameraUnit"));
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
            Object value = XposedHelpers.callMethod(
                presenter, member("@PresenterBase.mode"));
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
        Class<?> type = presenter instanceof Class
            ? (Class<?>) presenter
            : presenter == null ? null : presenter.getClass();
        if (type == null) return false;
        try {
            Object value = XposedHelpers.callStaticMethod(type, "p3");
            if (value instanceof Boolean) return (Boolean) value;
            return Boolean.TRUE.equals(value);
        } catch (Throwable missing) {
            // 317 renamed the static getter (no p3()Z on hh.p1; the static ()Z methods are
            // m3/n3/q3/u3), so the old lookup threw and this gate answered "off" for every
            // caller: no live session, no EIS route, no +2EV -- a dark cover and no clip.
            // Read the same switch through the shared data key the menu check already uses.
            return livePhotoSwitchFromSharedKey(type.getClassLoader(), missing);
        }
    }

    private static boolean livePhotoSwitchFromSharedKey(ClassLoader loader, Throwable reason) {
        try {
            Symbols symbols = Symbols.get();
            Object key = livePhotoSharedDataKey;
            if (key == null) {
                key = livePhotoSharedKey(symbols);
                livePhotoSharedDataKey = key;
            }
            Object value = DataStore.get(symbols, loader, key, "");
            return "on".equals(value);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live photo switch unreadable (p3: " + reason + ")", error);
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
