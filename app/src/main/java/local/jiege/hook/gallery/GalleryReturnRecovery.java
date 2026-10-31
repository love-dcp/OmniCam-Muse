package local.jiege.hook.gallery;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.video.VideoIzoomGuard;
import local.jiege.settings.ModuleSettings;

/**
 * Coming back from the gallery leaves the viewfinder grey in a video session.
 *
 * <p>The thumbnail opens the gallery through an embedded task (OPPO FlexibleTaskView) inside the
 * camera activity as often as through a separate activity, and the two paths behave differently:
 * the separate activity pauses the camera, so the return runs the normal onResume and the session
 * is created again; the embedded task never pauses it, so the camera has already torn its session
 * down before the task view attached (measured 2026-10-31 16:35:03: closeCamera at .743,
 * TaskView attach at .754, task view destroyed at 16:35:06.576 with no openCamera for the next 54
 * seconds). The activity stays resumed the whole time, the preview surface comes back empty and
 * the UI keeps its 2.5x label over a grey frame.
 *
 * <p>Two separate failures were traced on 2026-10-31, both after "record a clip -> open gallery ->
 * come back", both in stock firmware with every native patch removed, and both only in ordinary
 * video (pro video, operation mode 0x830b, never failed):
 *
 * <ol>
 *   <li>16:35 - the session is closed and never reopened at all (this class's replay covers it).
 *   <li>16:49 - the app reopens 2.5 s after closing, the session configures fine, but
 *       {@code WaitForConfigDone() Stream On for pipeline[RealtimeDefault1_0_cam_0] timed
 *       out(2900)} fires; StreamOn is retried every ~3.2 s, still fails at attempt 15 forty-six
 *       seconds later, and the chi extension module raises SIGABRT. The four SAT sensors had not
 *       settled after the video-mode teardown - {@code ProducerImpl: close, cannot block,
 *       currentMode: VideoMode} shows the close returned without waiting.
 * </ol>
 *
 * <p>So this does two things, both only inside an embedded gallery round trip: it remembers the
 * device processor that closed the session and, when an embedded task view is torn down while the
 * camera device is still closed, asks the processor for the camera again through its own
 * openCamera(int, boolean) - the same call the regular resume path makes, so the session is
 * rebuilt by the app's own state machine rather than behind its back; and it holds any reopen
 * that arrives sooner than {@link #MIN_REOPEN_GAP_MS} after the close, giving the sensors time to
 * settle before the harness runs out of StreamOn retries.
 *
 * <p>Separate-activity hand-overs never attach a task view, so they keep their normal path; a
 * camera that is already open (device field non-null) is never touched.
 */
public final class GalleryReturnRecovery {
    private static final String TAG = "GalleryReturnRecovery";
    /** AOSP task view, then the OPPO wrapper the log tag "FlexibleTaskView" comes from. */
    private static final String[] TASK_VIEW_CLASSES = {
        "android.window.TaskView",
        "com.oplus.flexiblewindow.FlexibleTaskView",
    };
    /** Let the window and its surface settle before the session is asked for again. */
    private static final long REPLAY_DELAY_MS = 300L;
    /** A close older than this is no longer the round trip being recovered. */
    private static final long TOKEN_TTL_MS = 90_000L;
    /**
     * Minimum spacing between the close and the reopen of an embedded gallery round trip. Short:
     * the deadlock is not a timing race, it is the remembered zoom ratio (see below), so this only
     * keeps the reopen from landing in the middle of the teardown.
     */
    private static final long MIN_REOPEN_GAP_MS = 500L;
    /**
     * How long the 1x session gets to stream before the remembered zoom is pushed back. Measured
     * 16:49:27 on a healthy reopen: the pipeline logs StreamingOn 417 ms after openCamera, so the
     * session is already live well inside this window; the rest is slack for a slow first frame.
     */
    private static final long ZOOM_RESTORE_DELAY_MS = 900L;
    /**
     * First label pass right after the restore. The rebuilt session repaints the ratio from the 1x
     * it was configured with once it starts streaming (measured 2026-10-31 17:12: restore at
     * 19.497, HAL applied it at 22.713), so the label is told once the restore is accepted and
     * again by {@link #ZOOM_LABEL_SYNC_LATE_MS} in case that repaint landed on top of it.
     */
    private static final long ZOOM_LABEL_SYNC_DELAY_MS = 600L;
    private static final long ZOOM_LABEL_SYNC_LATE_MS = 1_600L;
    /** Ratios below this are 「already wide」, nothing to hold and nothing to restore. */
    private static final float ZOOM_EPSILON = 1.05f;

    private static Symbols symbols;
    private static ClassLoader appLoader;
    private static Handler mainHandler;

    /** Processor whose session was closed last; the one a replay must go through. */
    private static volatile Object deviceProcessor;
    private static volatile long closedAt;
    private static volatile Field cameraDeviceField;
    private static volatile Method openCameraMethod;
    private static volatile boolean replaying;

    /** Set when a task view attaches after a close, i.e. the gallery opened embedded. */
    private static volatile boolean embeddedTrip;
    private static volatile long embeddedTripAt;
    /** Zoom the user had before the hand-over, held at 1x while the session is rebuilt. */
    private static volatile float heldZoom;

    private GalleryReturnRecovery() {}

    public static void installCamera(ClassLoader classLoader) {
        if (!Settings.get(ModuleSettings.GALLERY_RETURN_RECOVERY)) {
            Log.i(TAG, "off by settings, embedded gallery return left alone");
            return;
        }
        Log.guard(TAG, "embedded gallery return", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        symbols = Symbols.get();
        appLoader = classLoader;
        mainHandler = new Handler(Looper.getMainLooper());

        hookCloseCamera();
        hookOpenCamera();
        hookTaskViews(classLoader);
    }

    /**
     * closeCamera is the last point both hand-over styles share: it always runs before the gallery
     * -embedded or not- takes the screen, and it carries the processor instance the replay needs.
     */
    private static void hookCloseCamera() throws Throwable {
        Method close = symbols.method("DeviceProcessor.closeCamera");
        Class<?> processor = close.getDeclaringClass();
        cameraDeviceField = findCameraDeviceField(processor);
        openCameraMethod = findOpenCamera(processor);

        XposedBridge.hookMethod(close, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                deviceProcessor = param.thisObject;
                closedAt = SystemClock.elapsedRealtime();
                embeddedTrip = false;
                Log.i(TAG, "session closed, armed for an embedded gallery return");
            }
        });
        Log.i(TAG, "installed on " + processor.getName()
            + " (device field: " + (cameraDeviceField != null ? cameraDeviceField.getName() : "?")
            + ", openCamera: " + (openCameraMethod != null ? openCameraMethod.getName() : "?") + ")");
    }

    /**
     * Holds a reopen that races the close. Runs on the processor's own camera worker thread (not
     * the UI thread), so sleeping here delays only the reopen, not the interface.
     */
    private static void hookOpenCamera() {
        Method open = openCameraMethod;
        if (open == null) {
            Log.i(TAG, "openCamera not resolved, reopen pacing not armed");
            return;
        }
        XposedBridge.hookMethod(open, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInsideEmbeddedTrip()) return;
                holdZoomForReopen();
                long gap = SystemClock.elapsedRealtime() - closedAt;
                if (gap < MIN_REOPEN_GAP_MS) {
                    long hold = MIN_REOPEN_GAP_MS - gap;
                    Log.i(TAG, "reopen only " + gap + " ms after close, holding " + hold + " ms");
                    try {
                        Thread.sleep(hold);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!isInsideEmbeddedTrip()) return;
                boolean opened = Boolean.TRUE.equals(param.getResult());
                Log.i(TAG, "reopen returned " + opened + " after the embedded gallery round trip");
                if (opened) {
                    closedAt = 0L;
                    embeddedTrip = false;
                    restoreHeldZoom();
                }
            }
        });
        Log.i(TAG, "openCamera paced at " + MIN_REOPEN_GAP_MS + " ms and held at 1x after an"
            + " embedded gallery close");
    }

    /**
     * The remembered ratio is the deadlock, not the timing. Measured 16:58:58: the app configures
     * the SAT session with {@code com.oplus.original.zoomRatio = 2.5776} at .323, CONFIG End at
     * .427, and MCTFE answers {@code ErrorCode:1047 CamxResultExtIFEEPRFail} 67 ms later - the
     * session is bad before StreamOn is even attempted, which then times out at 2900 ms, retries
     * every 3.2 s for 15 attempts and ends in SIGABRT. Configuring at 1x keeps MCTFE out of the
     * multi-camera fusion path, so the session starts streaming and the ratio can go back up
     * through the ordinary runtime zoom switch afterwards.
     */
    private static void holdZoomForReopen() {
        float zoom = VideoIzoomGuard.currentZoom();
        if (zoom <= ZOOM_EPSILON) return;
        heldZoom = zoom;
        boolean applied = VideoIzoomGuard.applyZoom(1.0f);
        Log.i(TAG, "zoom " + zoom + " held at 1x for the reopen (applied=" + applied + ")");
    }

    /** Pushes the remembered zoom back once the 1x session has had time to start streaming. */
    private static void restoreHeldZoom() {
        final float restore = heldZoom;
        if (restore <= ZOOM_EPSILON) return;
        heldZoom = 0f;
        Log.i(TAG, "zoom " + restore + " will be restored in " + ZOOM_RESTORE_DELAY_MS + " ms");
        mainHandler.postDelayed(() -> {
            boolean applied = VideoIzoomGuard.applyZoom(restore);
            Log.i(TAG, "zoom restored to " + restore + " (applied=" + applied + ")");
            mainHandler.postDelayed(() -> syncZoomLabel(restore, "early"), ZOOM_LABEL_SYNC_DELAY_MS);
            mainHandler.postDelayed(() -> syncZoomLabel(restore, "late"), ZOOM_LABEL_SYNC_LATE_MS);
        }, ZOOM_RESTORE_DELAY_MS);
    }

    /**
     * The rebuilt session repaints the ratio label from the 1x it was configured with just after it
     * streams, so the label has to be set again once that repaint is done. Skipped when the user
     * moved the zoom in the meantime: then their change owns the label, not us.
     */
    private static void syncZoomLabel(float restore, String pass) {
        float current = VideoIzoomGuard.currentZoom();
        if (Math.abs(current - restore) > 0.01f) {
            Log.i(TAG, "zoom moved to " + current + " since the restore, " + pass + " label pass"
                + " left alone");
            return;
        }
        boolean synced = VideoIzoomGuard.syncZoomUi(restore);
        Log.i(TAG, "zoom label synced to " + restore + " (" + pass + ", synced=" + synced + ")");
    }

    /** True while a close is still fresh and a task view was attached since, i.e. embedded trip. */
    private static boolean isInsideEmbeddedTrip() {
        long closed = closedAt;
        if (closed == 0L || !embeddedTrip) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - closed > TOKEN_TTL_MS) return false;
        return now - embeddedTripAt <= TOKEN_TTL_MS;
    }

    /** The processor's own camera device handle; null means no session is open. */
    private static Field findCameraDeviceField(Class<?> processor) {
        for (Field field : processor.getDeclaredFields()) {
            if (field.getType().getName().equals("com.oplus.ocs.camera.CameraDevice")) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** The obfuscated openCamera(int, boolean) -> boolean the resume path calls. */
    private static Method findOpenCamera(Class<?> processor) {
        for (Method method : processor.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == int.class && params[1] == boolean.class
                && method.getReturnType() == boolean.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void hookTaskViews(ClassLoader classLoader) {
        XC_MethodHook attached = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                onTaskViewAttached(param.thisObject);
            }
        };
        XC_MethodHook gone = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                onTaskViewGone(param.thisObject);
            }
        };
        int sites = 0;
        for (String name : TASK_VIEW_CLASSES) {
            Class<?> cls = tryFind(name, classLoader);
            if (cls == null) continue;
            sites += hookDeclared(cls, "onAttachedToWindow", new Class<?>[0], attached);
            sites += hookDeclared(cls, "onDetachedFromWindow", new Class<?>[0], gone);
            sites += hookDeclared(cls, "surfaceDestroyed", new Class<?>[] {SurfaceHolder.class}, gone);
        }
        if (sites == 0) {
            Log.i(TAG, "no task view class found, embedded hand-over not covered");
        } else {
            Log.i(TAG, "task view hooked (" + sites + " sites)");
        }
    }

    private static Class<?> tryFind(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable missing) {
            try {
                return XposedHelpers.findClass(name, null);
            } catch (Throwable alsoMissing) {
                return null;
            }
        }
    }

    /**
     * Only the class's own declaration: hooking the inherited View.onDetachedFromWindow would fire
     * for every view in the process.
     */
    private static int hookDeclared(Class<?> cls, String name, Class<?>[] params, XC_MethodHook hook) {
        try {
            XposedBridge.hookMethod(cls.getDeclaredMethod(name, params), hook);
            return 1;
        } catch (Throwable missing) {
            return 0;
        }
    }

    /** A task view only attaches after the gallery takeover, so it proves the embedded path. */
    private static void onTaskViewAttached(Object view) {
        if (closedAt == 0L || !(view instanceof View)) return;
        embeddedTrip = true;
        embeddedTripAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "embedded task view attached after the close, reopen will be paced");
    }

    private static void onTaskViewGone(Object view) {
        Object processor = deviceProcessor;
        long closed = closedAt;
        if (processor == null || closed == 0L) return;
        if (SystemClock.elapsedRealtime() - closed > TOKEN_TTL_MS) return;
        if (!(view instanceof View)) return;
        synchronized (GalleryReturnRecovery.class) {
            if (replaying) return;
            replaying = true;
        }
        Log.i(TAG, "embedded task view gone, replaying in " + REPLAY_DELAY_MS + " ms if still closed");
        mainHandler.postDelayed(GalleryReturnRecovery::replay, REPLAY_DELAY_MS);
    }

    private static void replay() {
        Object processor = deviceProcessor;
        try {
            if (processor == null) return;
            Object device = cameraDeviceField != null ? cameraDeviceField.get(processor) : null;
            if (device != null) {
                Log.i(TAG, "camera device present, nothing to replay");
                return;
            }
            if (openCameraMethod == null) {
                Log.i(TAG, "openCamera not resolved, cannot replay");
                return;
            }
            // The replay decides its own timing, so it must not be paced again by the hook above.
            holdZoomForReopen();
            closedAt = 0L;
            openCameraMethod.invoke(processor, 0, false);
            Log.i(TAG, "reopened the camera after the embedded gallery returned");
            restoreHeldZoom();
        } catch (Throwable error) {
            Log.e(TAG, "reopen after embedded gallery failed", error);
        } finally {
            replaying = false;
            closedAt = 0L;
            embeddedTrip = false;
        }
    }
}
