package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Restores the P010 operation flag for Hasselblad high-pixel live photos on PKJ110.
 *
 * <p>The stock live-photo presenter rebuilds its operation flags in onResume. On this build the
 * CameraConfig entry for {@code com.oplus.camera.livephoto.enable.pack.p010} is false, so the
 * framebuffer is P010 but the encoder path processes it as an unpacked 10-bit buffer. The embedded
 * video then has green/magenta corruption while the still cover remains correct.
 *
 * <p>Only the high-pixel presenter is changed, immediately before M2 creates its live-photo
 * session. The flag remains set through the video capture so packed preview frames are encoded
 * correctly. Once the last capture handle completes it is cleared again, because leaving it set
 * after the video has finished makes the deferred JPEG close path wait for the live-photo session
 * and the live-photo session wait for the deferred JPEG path. The next capture in the same session
 * restores it from the capture-stage callback before frames are encoded.
 */
public final class HighPixelLivePhoto {
    private static final String TAG = "HighPixelLivePhoto";
    private static final String PRESENTER = "gh.e2";
    private static final String SESSION = "gh.i2";
    private static final String EVENT_CONSUMER = "b9.p";
    private static final String FLAGS = "fh.a";
    private static final String ENABLE_METHOD = "M2";
    private static final String MODE_METHOD = "i";
    private static final String SESSION_MODE_METHOD = "g";
    private static final String CAPTURE_STAGE_METHOD = "h";
    private static final String SESSION_CLOSE_METHOD = "d";
    private static final String SESSION_FIELD = "p1";
    private static final String ACTIVE_CAPTURES_FIELD = "c";
    private static final String PENDING_CAPTURES_FIELD = "i";
    private static final String CONSUMER_CASE_FIELD = "a";
    private static final String CONSUMER_TARGET_FIELD = "b";
    private static final String ACCEPT_METHOD = "accept";
    private static final String MODE = "highPixel";
    private static final String SESSION_MODE = "high_pixel_mode";
    private static final int VIDEO_CAPTURE_COMPLETED_CASE = 8;
    private static final int FLAG_ENABLE_PACK_P010 = 8388608;

    private static volatile Class<?> flagsClass;
    private static volatile Class<?> sessionClass;
    private static volatile Class<?> consumerClass;
    private static volatile Object activeSession;
    private static boolean p010OwnedByHook;
    private static volatile String lastApplied = "";
    private static volatile String lastCleared = "";
    private static volatile String lastCompletionProbe = "";

    private HighPixelLivePhoto() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "install", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) {
        Class<?> presenter = XposedHelpers.findClass(PRESENTER, classLoader);
        sessionClass = XposedHelpers.findClass(SESSION, classLoader);
        consumerClass = XposedHelpers.findClass(EVENT_CONSUMER, classLoader);
        flagsClass = XposedHelpers.findClass(FLAGS, classLoader);
        // Resolve the field at install time so an app-build mismatch is reported once at startup.
        XposedHelpers.getStaticIntField(flagsClass, "a");

        XposedBridge.hookAllMethods(presenter, ENABLE_METHOD, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                applyP010(param.thisObject);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                rememberSession(param.thisObject);
            }
        });
        XposedBridge.hookAllMethods(sessionClass, CAPTURE_STAGE_METHOD, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                applyForSession(param.thisObject);
            }
        });
        XposedBridge.hookAllMethods(consumerClass, ACCEPT_METHOD, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                logCompletionProbe("before", param.thisObject);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                clearAfterVideoCaptureCompleted(param.thisObject, param.args);
            }
        });
        XposedBridge.hookAllMethods(sessionClass, SESSION_CLOSE_METHOD, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject == activeSession) {
                    clearP010("session closed");
                    activeSession = null;
                }
            }
        });
        Log.i(TAG, "installed for highPixel live photo with per-capture P010 lifetime");
    }

    private static void applyP010(Object presenter) {
        if (!MODE.equals(XposedHelpers.callMethod(presenter, MODE_METHOD))) return;

        synchronized (HighPixelLivePhoto.class) {
            int before = XposedHelpers.getStaticIntField(flagsClass, "a");
            int after = before | FLAG_ENABLE_PACK_P010;
            if (after != before) {
                XposedHelpers.setStaticIntField(flagsClass, "a", after);
            }
            if ((before & FLAG_ENABLE_PACK_P010) == 0) {
                p010OwnedByHook = true;
            }

            String signature = Integer.toHexString(before) + " -> " + Integer.toHexString(after);
            if (!signature.equals(lastApplied)) {
                lastApplied = signature;
                lastCleared = "";
                Log.i(TAG, "operation flags 0x" + signature
                    + " (P010=" + ((after & FLAG_ENABLE_PACK_P010) != 0) + ")");
            }
        }
    }

    /**
     * M2 applies the flag before creating the session. Once M2 returns, retain the new Session so
     * its capture-stage callback can restore the flag for every later photo in the same session.
     */
    private static void rememberSession(Object presenter) {
        Object session = XposedHelpers.getObjectField(presenter, SESSION_FIELD);
        if (session == null || !sessionClass.isInstance(session)) return;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(session, SESSION_MODE_METHOD))) {
            return;
        }
        activeSession = session;
    }

    private static void applyForSession(Object session) {
        if (session == null || !sessionClass.isInstance(session)) return;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(session, SESSION_MODE_METHOD))) return;
        activeSession = session;
        applyP010ForSession();
    }

    private static void applyP010ForSession() {
        synchronized (HighPixelLivePhoto.class) {
            int before = XposedHelpers.getStaticIntField(flagsClass, "a");
            int after = before | FLAG_ENABLE_PACK_P010;
            if (after != before) {
                XposedHelpers.setStaticIntField(flagsClass, "a", after);
            }
            if ((before & FLAG_ENABLE_PACK_P010) == 0) {
                p010OwnedByHook = true;
            }

            String signature = Integer.toHexString(before) + " -> " + Integer.toHexString(after);
            if (!signature.equals(lastApplied)) {
                lastApplied = signature;
                lastCleared = "";
                Log.i(TAG, "operation flags 0x" + signature
                    + " (P010=" + ((after & FLAG_ENABLE_PACK_P010) != 0) + ", capture stage)");
            }
        }
    }

    /**
     * Case 8 is the Session's onVideoCaptureCompleted dispatch. The callback has already removed
     * the completed handle from both capture sets by the time this runs. Clear only when no other
     * capture is active, which preserves P010 for overlapping/continuous captures.
     */
    private static void logCompletionProbe(String stage, Object consumer) {
        if (consumer == null || !consumerClass.isInstance(consumer)) return;
        if (XposedHelpers.getIntField(consumer, CONSUMER_CASE_FIELD)
            != VIDEO_CAPTURE_COMPLETED_CASE) {
            return;
        }
        Object session = XposedHelpers.getObjectField(consumer, CONSUMER_TARGET_FIELD);
        if (session == null || !sessionClass.isInstance(session)) return;

        boolean p010 = (XposedHelpers.getStaticIntField(flagsClass, "a")
            & FLAG_ENABLE_PACK_P010) != 0;
        String probe = stage
            + " session=" + Integer.toHexString(System.identityHashCode(session))
            + " active=" + (activeSession == null
                ? "null"
                : Integer.toHexString(System.identityHashCode(activeSession)))
            + " match=" + (session == activeSession)
            + " highPixel=" + Boolean.TRUE.equals(
                XposedHelpers.callMethod(session, SESSION_MODE_METHOD))
            + " owned=" + p010OwnedByHook
            + " p010=" + p010
            + " activeHandles=" + collectionSize(session, ACTIVE_CAPTURES_FIELD)
            + " pendingHandles=" + collectionSize(session, PENDING_CAPTURES_FIELD);
        if (!probe.equals(lastCompletionProbe)) {
            lastCompletionProbe = probe;
            Log.i(TAG, probe);
        }
    }

    private static int collectionSize(Object session, String field) {
        Object collection = XposedHelpers.getObjectField(session, field);
        return collection instanceof java.util.Collection<?>
            ? ((java.util.Collection<?>) collection).size()
            : -1;
    }

    private static void clearAfterVideoCaptureCompleted(Object consumer, Object[] args) {
        if (consumer == null || !consumerClass.isInstance(consumer)) return;
        if (XposedHelpers.getIntField(consumer, CONSUMER_CASE_FIELD)
            != VIDEO_CAPTURE_COMPLETED_CASE) {
            return;
        }
        Object session = XposedHelpers.getObjectField(consumer, CONSUMER_TARGET_FIELD);
        if (session == null || !sessionClass.isInstance(session)) return;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(session, SESSION_MODE_METHOD))) {
            return;
        }
        if (collectionSize(session, ACTIVE_CAPTURES_FIELD) != 0
            || collectionSize(session, PENDING_CAPTURES_FIELD) != 0) {
            return;
        }
        activeSession = session;
        clearP010("video capture completed");
    }

    private static void clearP010(String reason) {
        synchronized (HighPixelLivePhoto.class) {
            if (!p010OwnedByHook) return;

            int before = XposedHelpers.getStaticIntField(flagsClass, "a");
            int after = before & ~FLAG_ENABLE_PACK_P010;
            if (after != before) {
                XposedHelpers.setStaticIntField(flagsClass, "a", after);
            }
            p010OwnedByHook = false;

            String signature = Integer.toHexString(before) + " -> " + Integer.toHexString(after);
            if (!signature.equals(lastCleared)) {
                lastCleared = signature;
                Log.i(TAG, "operation flags 0x" + signature + " (" + reason + ")");
            }
        }
    }
}
