package local.jiege.hook.diag;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Map;
import local.jiege.hook.common.Log;

/**
 * Records what the camera's main thread is doing when the UI stops answering.
 *
 * <p>Two X8U freezes could not be traced from logcat alone: 哈苏超清 and XPAN both freeze the
 * viewfinder while the process stays alive and keeps logging from its worker threads, so the only
 * visible trace was a gap in main-thread output (XPAN, 2026-10-20 22:24:08 "release xpan drawer"
 * followed by 55 s of silence). Guessing which hook is involved costs a device session per
 * candidate; one stack dump ends the guessing.
 *
 * <p>The probe is a message per second on the main queue, which is far cheaper than any of the
 * camera's own frame callbacks and never touches a frame path. When the main thread has not
 * answered for {@link #STUCK_MS}, it is genuinely blocked, and the Java frames of the main thread
 * plus the viewfinder GL thread are written to the module log once per {@link #QUIET_MS}.
 *
 * <p>Switched off with {@code persist.omnicam.aura.mainwatch=0}.
 */
public final class MainThreadWatchdog {
    private static final String TAG = "MainWatch";
    private static final String PROP = "persist.omnicam.aura.mainwatch";
    private static final long PROBE_MS = 1000L;
    /** A main thread that has not drained a one-line Runnable for this long is blocked, not busy. */
    private static final long STUCK_MS = 4000L;
    /** Minimum spacing between two dumps, so one freeze cannot flood the log. */
    private static final long QUIET_MS = 30_000L;
    private static final String[] WATCHED = {"PreviewGLThread", "CameraHandlerThread", "previewManagerRoutine"};

    private static volatile long acked;
    private static volatile long lastDump;
    private static volatile boolean started;

    private MainThreadWatchdog() {}

    public static void install() {
        if (started || !enabled()) return;
        started = true;
        acked = SystemClock.uptimeMillis();
        try {
            final Handler main = new Handler(Looper.getMainLooper());
            final Runnable tick = () -> acked = SystemClock.uptimeMillis();
            Thread worker = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(PROBE_MS);
                        main.post(tick);
                        long now = SystemClock.uptimeMillis();
                        if (now - acked > STUCK_MS && now - lastDump > QUIET_MS) {
                            lastDump = now;
                            dump(now - acked);
                        }
                    } catch (Throwable error) {
                        // A diagnostic must never take the camera down with it.
                        return;
                    }
                }
            }, "MuseMainWatchdog");
            worker.setDaemon(true);
            worker.start();
            Log.i(TAG, "main thread watchdog on, stuck after " + STUCK_MS + " ms");
        } catch (Throwable error) {
            Log.e(TAG, "watchdog unavailable", error);
        }
    }

    private static boolean enabled() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, PROP, "");
            return !"0".equals(String.valueOf(value));
        } catch (Throwable error) {
            return true;
        }
    }

    private static void dump(long stuckFor) {
        try {
            Thread main = Looper.getMainLooper().getThread();
            Log.i(TAG, "main thread blocked for " + stuckFor + " ms, state " + main.getState());
            StackTraceElement[] frames = main.getStackTrace();
            for (int index = 0; index < frames.length && index < 40; index++) {
                Log.i(TAG, "  main " + index + ": " + frames[index]);
            }
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (String wanted : WATCHED) {
                for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
                    Thread thread = entry.getKey();
                    if (thread == null || thread.getName() == null || !thread.getName().contains(wanted)) continue;
                    StackTraceElement[] stack = entry.getValue();
                    Log.i(TAG, "  " + thread.getName() + " state " + thread.getState() + " frames " + stack.length);
                    for (int index = 0; index < stack.length && index < 20; index++) {
                        Log.i(TAG, "  " + thread.getName() + " " + index + ": " + stack[index]);
                    }
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "dump failed", error);
        }
    }
}
