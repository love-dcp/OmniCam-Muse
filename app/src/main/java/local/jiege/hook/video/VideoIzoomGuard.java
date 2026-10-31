package local.jiege.hook.video;

import android.app.Activity;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Stabilised 1080p30 video in the main sensor's in-sensor-zoom window (2x to under 3x) deadlocks
 * the X8U firmware when the session is re-created after returning from the gallery:
 * RealtimeDefault1_0_cam_0 stream-on times out (2900 ms, CSL error 9) and the provider aborts in
 * the stock chi-override (ExtensionModule::SignalSystemEvent, 2026-10-29 tombstone_16). Stabilisation
 * off, 60 fps and 3x+ (tele camera) do not hit it; the same stream-on failure was seen on 10-21 in
 * XPAN / high pixel, six days before any native patch of ours existed, so it is upstream.
 *
 * The guard resets the zoom to 1x through the camera's own zoom presenter (updateZoomValue, the
 * choke point every zoom change runs through) when such a camera pauses or tears the session down
 * (DeviceProcessor.closeCamera covers the gallery hand-over and the recording stop alike; the
 * activity's onPause alone proved unreliable on this build), so the session that is re-created on
 * return starts outside the deadlocked combination. Cost: the zoom is back at 1x after returning
 * from the gallery, only in this combination.
 */
public final class VideoIzoomGuard {
    private static final String TAG = "VideoIzoomGuard";
    private static final String CAMERA_ACTIVITY = "com.oplus.camera.Camera";
    /** In-sensor-zoom window of the main sensor, slightly widened at the low end for chip rounding. */
    private static final float IZOOM_MIN = 1.95f;
    private static final float IZOOM_MAX = 3.0f;

    private static Symbols symbols;
    private static Object fpsKey;
    private static Object stabKey;
    private static Object superStabKey;
    /** The zoom presenter the last updateZoomValue ran on, and the zoom it was last given. */
    private static volatile Object zoomPresenter;
    private static volatile float lastZoom = 1.0f;
    /** The zoom panel (ZoomUIManager) and its changeZoom(float, float), resolved lazily. */
    private static volatile Object zoomPanel;
    private static volatile Method changeZoomMethod;
    /** The ui container updateZoom paints the ratio label through, resolved on first use. */
    private static volatile Object zoomContainer;
    /** The zoom seek bar, resolved from the panel when the label has to be pushed manually. */
    private static volatile Object zoomSeekBar;
    /** Dedup for the hierarchy walk: several onPause declarations can fire for one pause. */
    private static volatile long lastPauseSeen;

    private VideoIzoomGuard() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "video izoom guard", () -> install(classLoader));
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.VIDEO_IZOOM_GUARD);
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        symbols = Symbols.get();
        Class<?> keys = symbols.cls("FeatureKeys");
        Class<?> keyType = symbols.cls("FeatureKey");
        fpsKey = Reflection.findFeatureKey(keys, keyType, "getVideoFpsFromSp");
        stabKey = Reflection.findFeatureKey(keys, keyType, "isEISOpen");
        superStabKey = Reflection.findFeatureKey(keys, keyType, "isSuperEISOpen");

        // Every zoom change runs through this one method (state holder update + ZOOM_RATIO submit);
        // it is also the guard's way of setting the zoom back to 1x.
        XposedBridge.hookMethod(symbols.method("Zoom.updateZoom"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                zoomPresenter = param.thisObject;
                if (param.args.length == 2 && param.args[0] instanceof Float) lastZoom = (Float) param.args[0];
            }
        });

        // Hook onPause wherever the camera declares it. Hooking Activity.onPause alone failed on
        // this device (measured 2026-10-29: the gallery hand-over paused the camera without the
        // base-class hook ever firing, i.e. some override in the chain does not call super), so
        // walk the concrete activity's hierarchy and hook every declared onPause below Activity.
        // Several levels can fire for one pause (override + super call); dedup by timestamp.
        XC_MethodHook pauseHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPauseSeen < 500) return;
                lastPauseSeen = now;
                // The camera's own callbacks are on the same thread; a failure here must never
                // break the pause (and with it the gallery hand-over).
                try {
                    onCameraPause();
                } catch (Throwable error) {
                    Log.e(TAG, "izoom guard failed", error);
                }
            }
        };
        Class<?> activityClass = XposedHelpers.findClass(CAMERA_ACTIVITY, classLoader);
        int sites = 0;
        for (Class<?> c = activityClass; c != null && c != Activity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("onPause")) {
                    XposedBridge.hookMethod(method, pauseHook);
                    sites++;
                }
            }
        }
        if (sites == 0) {
            // Fallback: no camera class declares onPause, so the base method runs directly.
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", pauseHook);
        }
        Log.i(TAG, "installed (pause hook sites: " + sites + ")");

        // Belt and braces: hook the session-teardown owner (OCAM_DeviceProcessor.closeCamera, real
        // class lm.f, obfuscated k(ZZ)V) as well. onPause proved unreliable on this build (measured
        // 2026-10-29: two of three gallery hand-overs in the same process never fired the pause
        // hooks, while closeCamera logged every time), and closeCamera also covers the recording
        // stop path, which pauses nothing yet re-creates the session at 2x and deadlocks all the
        // same. The guard is idempotent: after an earlier pause reset the zoom is already 1x and
        // this hook passes; without it this is the last point before teardown.
        XposedBridge.hookMethod(symbols.method("DeviceProcessor.closeCamera"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    onCameraPause();
                } catch (Throwable error) {
                    Log.e(TAG, "izoom guard failed in closeCamera", error);
                }
            }
        });
    }

    /**
     * Zoom ratio the presenter was last handed, i.e. what the app currently shows. Read by the
     * gallery-return path so it can restore whatever the user had before the hand-over.
     */
    public static float currentZoom() {
        return lastZoom;
    }

    /**
     * Applies a zoom through the same choke point a user zoom goes through (presenter update plus
     * ZOOM_RATIO submit), so the app's own state and the viewfinder agree afterwards.
     *
     * @return false when no presenter has been seen yet, i.e. the camera never set a zoom.
     */
    public static boolean applyZoom(float ratio) {
        Object presenter = zoomPresenter;
        if (presenter == null || symbols == null) return false;
        try {
            symbols.call("Zoom.updateZoom", presenter, Float.valueOf(ratio), Boolean.TRUE);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "applyZoom failed", error);
            return false;
        }
    }

    /**
     * Pushes the viewfinder's own zoom state (the ratio label and the highlighted chip) to
     * {@code ratio}.
     *
     * <p>{@link #applyZoom} already carries the panel update, and it is enough for a zoom the user
     * makes while the session is running. After a rebuilt session it is not: measured 2026-10-31
     * 17:12, the restore ran at 19.497, the HAL saw {@code controlZoomRatio from App: 2.505900} at
     * 22.713, and the frame ended up at 2.5x while the label stayed on the 1x the session had been
     * configured with - the session repaints the label from its own ratio once it streams, after
     * the restore has gone through. changeZoom is what a chip tap calls, so this puts the panel in
     * the state a tap would have left it in.
     */
    public static boolean syncZoomUi(float ratio) {
        Object presenter = zoomPresenter;
        if (presenter == null) return false;
        boolean pushed = false;
        try {
            Object container = resolveZoomContainer(presenter);
            if (container != null) {
                XposedHelpers.callMethod(container, "la", Float.valueOf(ratio));
                pushed = true;
            }
        } catch (Throwable error) {
            Log.e(TAG, "syncZoomUi: container push failed", error);
        }
        // la()/y2() only feed the face box and the portrait blur; the ratio label and the chip
        // highlight are drawn by ZoomSeekBar, which updateZoom reaches through the panel.
        try {
            pushed |= pushSeekBarZoom(presenter, ratio);
        } catch (Throwable error) {
            Log.e(TAG, "syncZoomUi: seek bar push failed", error);
        }
        if (!pushed) {
            try {
                pushed = syncZoomUiThroughPanel(ratio);
            } catch (Throwable error) {
                Log.e(TAG, "syncZoomUi: panel fallback failed", error);
            }
        }
        return pushed;
    }

    /**
     * Drives the zoom seek bar itself, which is what draws the ratio label and the highlighted
     * chip. Cg(float, boolean) is the bar's own "external zoom set" entry (it ends in
     * setZoomRate); the boolean only decides whether the bar collapses afterwards, so false keeps
     * it open without side effects.
     */
    private static boolean pushSeekBarZoom(Object presenter, float ratio) throws Throwable {
        Object panel = resolvePanelInstance(presenter);
        if (panel == null) return false;
        Object bar = zoomSeekBar;
        if (bar == null) {
            Field field = findField(panel.getClass(), "com.oplus.camera.feature.zoom.view.e");
            if (field == null) {
                Log.i(TAG, "no seek bar field on " + panel.getClass().getName());
                return false;
            }
            bar = field.get(panel);
            if (bar == null) {
                Log.i(TAG, "seek bar field " + field.getName() + " is null");
                return false;
            }
            zoomSeekBar = bar;
            Log.i(TAG, "seek bar resolved: " + bar.getClass().getName()
                + " (field " + field.getName() + ")");
        }
        XposedHelpers.callMethod(bar, "Cg", Float.valueOf(ratio), Boolean.FALSE);
        Log.i(TAG, "seek bar zoom pushed: " + ratio);
        return true;
    }

    private static Object resolvePanelInstance(Object presenter) throws IllegalAccessException {
        resolveZoomPanel(presenter);
        return zoomPanel;
    }

    /**
     * The ui container that ZoomPresenter.updateZoom updates last ({@code e.a8().la(ratio)}), the
     * step that repaints the ratio label. updateZoom itself skips it when the presenter still
     * considers the camera stopped: it submits ZOOM_RATIO to the camera (so the frame does zoom)
     * and then returns early after a different call, which is why the frame showed 2.5x with a 1x
     * label after a rebuilt session.
     */
    private static Object resolveZoomContainer(Object presenter) throws Throwable {
        Object cached = zoomContainer;
        if (cached != null) return cached;
        Field field = findField(presenter.getClass(), "com.oplus.camera.protocal.ui.a");
        if (field == null) {
            Log.i(TAG, "no ui container field on " + presenter.getClass().getName());
            return null;
        }
        Object ui = field.get(presenter);
        if (ui == null) return null;
        Object container = XposedHelpers.callMethod(ui, "a8");
        if (container == null) return null;
        zoomContainer = container;
        Log.i(TAG, "zoom container resolved: " + container.getClass().getName()
            + " (field " + field.getName() + ")");
        return container;
    }

    private static Field findField(Class<?> type, String typeName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    /** Fallback: the zoom panel's own changeZoom(float, float), i.e. what a chip tap calls. */
    private static boolean syncZoomUiThroughPanel(float ratio) throws Throwable {
        Object presenter = zoomPresenter;
        if (presenter == null) return false;
        resolveZoomPanel(presenter);
        Object panel = zoomPanel;
        Method method = changeZoomMethod;
        if (panel == null || method == null) return false;
        method.invoke(panel, Float.valueOf(ratio), Float.valueOf(ratio));
        return true;
    }

    /** The panel is held by the presenter; find the field whose type owns changeZoom(float, float). */
    private static void resolveZoomPanel(Object presenter) throws IllegalAccessException {
        if (zoomPanel != null && changeZoomMethod != null) return;
        for (Field field : presenter.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!type.getName().startsWith("com.oplus.camera.feature.zoom.ui.")) continue;
            Method candidate = findChangeZoom(type);
            if (candidate == null) continue;
            field.setAccessible(true);
            Object value = field.get(presenter);
            if (value == null) continue;
            changeZoomMethod = candidate;
            zoomPanel = value;
            Log.i(TAG, "zoom panel resolved: " + type.getName() + "." + candidate.getName()
                + " (field " + field.getName() + ")");
            return;
        }
    }

    /**
     * The panel's changeZoom, obfuscated n(float, float) in 7.006.123
     * (com.oplus.camera.feature.zoom.ui.p2; the two other float,float methods only move the
     * panel's end-point fields, which is why picking the first match did nothing).
     */
    private static Method findChangeZoom(Class<?> type) {
        try {
            Method exact = type.getDeclaredMethod("n", float.class, float.class);
            exact.setAccessible(true);
            return exact;
        } catch (NoSuchMethodException missing) {
            Log.i(TAG, "changeZoom n(float, float) not found in " + type.getName());
            return null;
        }
    }

    private static void onCameraPause() throws Throwable {
        Object presenter = zoomPresenter;
        if (presenter == null || !enabled()) return;
        float zoom = lastZoom;
        if (zoom < IZOOM_MIN || zoom >= IZOOM_MAX) return;
        String mode = String.valueOf(symbols.get("FeaturePresenter.modeName", presenter));
        // Video-capable modes: "commonVideo", "masterVideo", ... and "professional" (pro photo +
        // video share the mode; the stabilisation read below is what keeps photo out).
        boolean video = mode != null && (mode.endsWith("Video") || "professional".equals(mode));
        // Both stabilisers ride the main sensor EIS pipeline in the izoom window; the crash was
        // measured with the standard one, but the super one crops harder and cannot be safer.
        Object stab = symbols.call("FeaturePresenter.feature", presenter,
            "com.oplus.configure.video.stabilization", stabKey, Boolean.FALSE);
        Object superStab = symbols.call("FeaturePresenter.feature", presenter,
            "com.oplus.configure.video.stabilization", superStabKey, Boolean.FALSE);
        Object fpsValue = symbols.call("FeaturePresenter.feature", presenter,
            "com.oplus.configure.video.fps", fpsKey, Integer.valueOf(30));
        int fps = fpsValue instanceof Integer ? (Integer) fpsValue : 30;
        // 60 fps (and above) measured safe; anything lower, including an unreadable default,
        // keeps the guard on so an unknown frame rate cannot bring the crash back.
        boolean stabilised = Boolean.TRUE.equals(stab) || Boolean.TRUE.equals(superStab);
        if (!video || !stabilised || fps >= 60) {
            Log.i(TAG, "izoom window passed on pause: mode=" + mode + " zoom=" + zoom
                + " stab=" + stab + "/" + superStab + " fps=" + fps + " (no reset)");
            return;
        }
        symbols.call("Zoom.updateZoom", presenter, Float.valueOf(1.0f), Boolean.TRUE);
        Log.i(TAG, "izoom guard: " + mode + " zoom=" + zoom + " fps=" + fps
            + " stabilised, reset to 1x before session re-create");
    }
}
