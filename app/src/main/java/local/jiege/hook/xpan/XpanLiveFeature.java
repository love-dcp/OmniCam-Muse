package local.jiege.hook.xpan;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.graphics.Rect;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

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
    private static final String LIVE_WARP_RENDERER = "ch.g";
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";
    private static final String LUT_MANAGER_HOLDER = LUT_MANAGER + "$b";
    private static final String CAMERA_METADATA_KEY = "bb.s";
    private static final String LIVE_PHOTO_FEATURE_FLAGS = "fh.a";
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
    private static final ThreadLocal<Boolean> XPAN_RESUME_OVERRIDE =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> LIVE_STATE_SYNC =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<XpanMainFrame> XPAN_MAIN_FRAME = new ThreadLocal<>();
    private static final ThreadLocal<String> XPAN_RENDER_STAGE = new ThreadLocal<>();
    private static volatile String currentCameraMode = "";
    private static volatile String exposureFixLogSignature = "";
    private static volatile Object livePhotoSharedDataKey;
    private static volatile String liveStateSyncLogSignature = "";
    private static volatile boolean stateMigrationChecked;
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
    private static volatile boolean xpanEisBypassActive;
    private static volatile boolean videoCropFallbackLogged;
    private static volatile boolean basicToneParametersLogged;
    private static volatile boolean basicToneTextureLogged;
    private static volatile boolean basicToneTextureUnavailableLogged;
    private static volatile boolean basicToneBindingLogged;
    private static volatile boolean basicToneBindingErrorLogged;
    private static volatile boolean basicToneDirectProbeLogged;
    private static volatile boolean basicToneDirectShaderLogged;
    private static volatile boolean basicToneDirectFrameLogged;
    private static volatile boolean commonBasicToneBridgeLogged;
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
            boolean rendererSNull
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
            hookXpanLiveLutDiagnostics(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "XPAN live LUT diagnostics unavailable", error);
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
                if (!supportOverrideLogged) {
                    supportOverrideLogged = true;
                    Log.i(TAG, "XPAN live photo support gate opened");
                }
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
                if (!eligibilityOverrideLogged) {
                    eligibilityOverrideLogged = true;
                    Log.i(TAG, "XPAN live photo eligibility gate opened");
                }
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
                    if (!captureRequestLivePhotoLogged) {
                        captureRequestLivePhotoLogged = true;
                        Log.i(TAG, "XPAN capture request live photo t1=false -> true"
                            + ", x=" + XposedHelpers.getBooleanField(requestTag, "x")
                            + ", d1=" + XposedHelpers.getBooleanField(requestTag, "d1")
                            + ", multiBurstIndex="
                            + XposedHelpers.getIntField(requestTag, "V1"));
                    }
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
                    if (!captureStartedLivePhotoLogged) {
                        captureStartedLivePhotoLogged = true;
                        Log.i(TAG, "XPAN capture started live photo t1=false -> true"
                            + ", stage=" + XposedHelpers.getIntField(event, "w")
                            + ", x=" + XposedHelpers.getBooleanField(requestTag, "x")
                            + ", d1=" + XposedHelpers.getBooleanField(requestTag, "d1")
                            + ", multiBurstIndex="
                            + XposedHelpers.getIntField(requestTag, "V1"));
                    }
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
                if (param.hasThrowable()
                    || !XPAN_MODE.equals(currentCameraMode)
                    || !isLivePhotoStoredOn(presenter)) {
                    xpanEisBypassActive = false;
                    return;
                }
                try {
                    Object eisEnabled =
                        XposedHelpers.callStaticMethod(featureFlags, "f");
                    if (!Boolean.TRUE.equals(eisEnabled)) {
                        xpanEisBypassActive = false;
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
        Object previous = XPAN_MAIN_FRAME.get();
        param.setObjectExtra("xpanMainFramePrevious", previous);
        try {
            Object metadata = XposedHelpers.getObjectField(imageData, "f");
            Object renderer = XposedHelpers.getObjectField(pipeline, "w");
            Object directGrid = XposedHelpers.getObjectField(imageData, "o");
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
                renderer == null || XposedHelpers.getObjectField(renderer, "s") == null
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
    private static void hookXpanLiveBasicTone(ClassLoader classLoader) {
        final Class<?> glRenderer =
            XposedHelpers.findClass(GL_RENDERER, classLoader);
        final Class<?> shader =
            XposedHelpers.findClass(GL_SHADER, classLoader);
        final Class<?> managerHolder =
            XposedHelpers.findClass(LUT_MANAGER_HOLDER, classLoader);
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
                    if (XposedHelpers.getBooleanField(lutParameters, "g")) return;
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
                if (program == null) return;
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
                    Log.i(TAG, "XPAN transform capture method observed"
                        + ", args=" + param.args.length
                        + ", throwable=" + param.hasThrowable()
                        + ", result=" + (result == null
                            ? "null" : result.getClass().getName())
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

    private static boolean ensureXpanLiveBasicToneTexture(
        Object renderer,
        Class<?> glRenderer,
        Class<?> managerHolder,
        String source) {
        Object existing = XposedHelpers.getObjectField(renderer, "s");
        if (existing != null) return true;

        Object manager = XposedHelpers.getStaticObjectField(managerHolder, "a");
        int lutSize = XposedHelpers.getIntField(manager, "t");
        float[] lutData = (float[]) XposedHelpers.getObjectField(manager, "c");
        if (lutSize <= 0 || lutData == null || lutData.length == 0) {
            if (!basicToneTextureUnavailableLogged) {
                basicToneTextureUnavailableLogged = true;
                Log.i(TAG, "XPAN live BasicTone texture data unavailable"
                    + ", source=" + source
                    + ", size=" + lutSize
                    + ", dataLength=" + (lutData == null ? -1 : lutData.length));
            }
            return false;
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
        if (!basicToneTextureLogged) {
            basicToneTextureLogged = true;
            Log.i(TAG, "XPAN live BasicTone texture created"
                + ", lutType=1"
                + ", size=" + lutSize
                + ", dataLength=" + lutData.length
                + ", source=" + source);
        }
        return true;
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
