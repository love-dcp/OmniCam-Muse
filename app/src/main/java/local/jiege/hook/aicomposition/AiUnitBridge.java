package local.jiege.hook.aicomposition;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Entry point to the on-device AI platform.
 *
 * <p>The gallery ships the whole AIUnit client SDK compiled into its own APK
 * ({@code com.oplus.aiunit.eraser.*} for composition, {@code vision.client.AestheticsClient} for
 * the aesthetics model), while the AIUnit app itself is only a downloader shell. So the SDK is
 * loaded straight out of the gallery's APK instead of being duplicated here: that copy is the one
 * OPPO keeps in step with the service, and it already carries its own Kotlin runtime.
 *
 * <p>The parent is the system class loader on purpose. The camera has its own older copy of
 * {@code com.oplus.aiunit.core} in its dex, and letting that one win would mix two SDK versions
 * across a binder boundary. Everything the SDK needs from this process is {@code android.*}, which
 * boot resolves.
 */
final class AiUnitBridge {
    private static final String TAG = "CompAiUnit";

    /** Gallery package names across ColorOS/OPPO builds, most specific first. */
    private static final String[] GALLERY_PACKAGES = {
        "com.coloros.gallery3d",
        "com.oplus.gallery3d",
        "com.oplus.gallery",
    };

    private static volatile Context appContext;
    private static volatile ClassLoader sdk;
    private static volatile boolean sdkTried;
    private static volatile boolean contextMissReported;
    private static volatile String sdkError = "";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiUnitBridge() {}

    static void attach(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        if (application != null) appContext = application;
    }

    /**
     * Grabs the process Application the moment the framework creates it, so the AIUnit SDK can be
     * loaded no matter which thread asks first.
     */
    static void capture() {
        try {
            XposedBridge.hookAllMethods(android.app.Instrumentation.class, "callApplicationOnCreate",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Context) {
                            attach((Context) param.args[0]);
                            Log.i(TAG, "application captured");
                        }
                    }
                });
        } catch (Throwable error) {
            Log.i(TAG, "application capture hook failed: " + error);
        }
    }

    static Context context() {
        Context known = appContext;
        if (known != null) return known;
        // ActivityThread is the plain-platform way to reach the process Application; the Xposed
        // helper class is not present under every framework, and depending on it left the whole
        // SDK permanently unavailable ("no callback" on every composition attempt).
        for (String owner : new String[]{"android.app.ActivityThread"}) {
            try {
                Class<?> type = Class.forName(owner);
                Method current = type.getDeclaredMethod("currentApplication");
                current.setAccessible(true);
                Object application = current.invoke(null);
                if (application instanceof Context) {
                    appContext = (Context) application;
                    return appContext;
                }
            } catch (Throwable error) {
                Log.i(TAG, "no application via " + owner + ": " + error);
            }
        }
        try {
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Method current = helper.getMethod("currentApplication");
            Object application = current.invoke(null);
            if (application instanceof Context) {
                appContext = (Context) application;
                return appContext;
            }
        } catch (Throwable error) {
            Log.i(TAG, "no application handle: " + error);
        }
        return null;
    }

    /** Lazily builds (once) the class loader backed by the gallery APK. */
    static ClassLoader sdk() {
        ClassLoader ready = sdk;
        if (ready != null) return ready;
        synchronized (AiUnitBridge.class) {
            if (sdk != null) return sdk;
            if (sdkTried) return null;
            // A missing Context is a timing problem, not a verdict on this device: the first
            // composition request can land before the process has published its Application.
            // Latching here used to disable the SDK for the whole process lifetime.
            Context context = context();
            if (context == null) {
                sdkError = "no context";
                if (!contextMissReported) {
                    contextMissReported = true;
                    Log.i(TAG, "context not ready yet, will retry");
                }
                return null;
            }
            sdkTried = true;
            String apk = galleryApk(context);
            if (apk == null) {
                sdkError = "gallery apk not found";
                Log.i(TAG, sdkError);
                return null;
            }
            try {
                long started = android.os.SystemClock.uptimeMillis();
                sdk = new dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader());
                // Force the dex open now: a broken/updated APK should be reported here and not
                // halfway through a composition attempt inside the camera.
                Class.forName("com.oplus.aiunit.core.AIConnector", false, sdk);
                Log.i(TAG, "sdk ready from " + apk + " in "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                return sdk;
            } catch (Throwable error) {
                sdkError = String.valueOf(error);
                sdk = null;
                Log.e(TAG, "sdk load failed from " + apk, error);
                return null;
            }
        }
    }

    static String status() {
        ClassLoader ready = sdk;
        return ready == null ? ("unavailable: " + sdkError) : "ready";
    }

    /** The installed gallery's base APK, which is what carries the SDK. */
    private static String galleryApk(Context context) {
        PackageManager manager = context.getPackageManager();
        for (String name : GALLERY_PACKAGES) {
            try {
                ApplicationInfo info = manager.getApplicationInfo(name, 0);
                if (info != null && info.sourceDir != null) return info.sourceDir;
            } catch (Throwable ignored) {
                // Not this build; try the next name.
            }
        }
        return null;
    }

    /** Instantiates {@code className(Context, extra)} on the main thread. */
    static Object newClient(String className, String extra) {
        ClassLoader loader = sdk();
        if (loader == null) return null;
        String failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Object[] out = new Object[1];
            String[] err = new String[1];
            Runnable task = () -> {
                try {
                    Context context = context();
                    if (context == null) {
                        err[0] = "no context";
                        return;
                    }
                    Class<?> type = Class.forName(className, true, loader);
                    out[0] = extra == null
                        ? type.getConstructor(Context.class).newInstance(context)
                        : type.getConstructor(Context.class, String.class)
                            .newInstance(context, extra);
                } catch (Throwable error) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    err[0] = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                task.run();
            } else {
                Object lock = new Object();
                boolean[] done = new boolean[1];
                MAIN.post(() -> {
                    task.run();
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                });
                synchronized (lock) {
                    // Binding to the AIUnit service is fast, but it does cross a process boundary.
                    long deadline = android.os.SystemClock.uptimeMillis() + 4000L;
                    while (!done[0]) {
                        long left = deadline - android.os.SystemClock.uptimeMillis();
                        if (left <= 0) break;
                        try {
                            lock.wait(left);
                        } catch (InterruptedException stop) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (out[0] != null) return out[0];
            failure = err[0];
            // A cold AIUnit service can refuse the very first bind; one retry covers it.
            if (attempt == 0 && failure != null && failure.contains("Service")) {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            break;
        }
        Log.i(TAG, "client " + className + " unavailable: " + failure);
        return null;
    }

    /** Runs {@code body} on the main thread and waits a bounded time for it. */
    static Object callOnMain(Method method, Object target, Object[] args, long timeoutMs) {
        Object[] out = new Object[1];
        Throwable[] failure = new Throwable[1];
        Runnable task = () -> {
            try {
                out[0] = method.invoke(target, args);
            } catch (Throwable error) {
                failure[0] = error.getCause() != null ? error.getCause() : error;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            Object lock = new Object();
            boolean[] done = new boolean[1];
            MAIN.post(() -> {
                task.run();
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            });
            synchronized (lock) {
                long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
                while (!done[0]) {
                    long left = deadline - android.os.SystemClock.uptimeMillis();
                    if (left <= 0) break;
                    try {
                        lock.wait(left);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        return out[0];
    }
}
