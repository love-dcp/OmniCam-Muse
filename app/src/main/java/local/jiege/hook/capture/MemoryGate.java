package local.jiege.hook.capture;

import android.app.Activity;

import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * The camera answers every shutter press with "memory or storage is not enough" while the device
 * reports less than {@code 648 MB} of free RAM.  On this 12 GB build the swap file already holds
 * 8 GB, so the figure sits at 460-620 MB for minutes at a time and captures are refused in bursts
 * even though the capture itself succeeds once it is let through (it only lands 24-154 s later).
 *
 * <p>{@code in.c.m(Activity, long)} is that verdict: it walks the runtime heap floor, the
 * {@code ActivityManager.MemoryInfo} avail/threshold pair and the storage floor, and returns true
 * only when all of them pass.  Answering true keeps the shutter usable.  The real out-of-memory
 * case is not hidden by this -- a capture that cannot fit still fails in the pipeline, it just no
 * longer fails at the button.
 *
 * <p>{@code persist.omnicam.memgate=0} restores the stock answer without a rebuild.
 */
public final class MemoryGate {
    private static final String TAG = "MemGate";
    private static final String CLASS = "in.c";
    private static final String METHOD = "m";
    private static final String PROP = "persist.omnicam.memgate";

    private static final AtomicInteger opened = new AtomicInteger();

    private MemoryGate() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            if ("0".equals(prop(PROP, "1"))) {
                Log.i(TAG, "left as stock, persist.omnicam.memgate=0");
                return;
            }
            XposedHelpers.findAndHookMethod(CLASS, classLoader, METHOD,
                Activity.class, long.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getResult())) return;
                        param.setResult(Boolean.TRUE);
                        if (opened.incrementAndGet() <= 20) {
                            Log.i(TAG, "memory gate opened, stock said not enough");
                        }
                    }
                });
            Log.i(TAG, "installed, stocked threshold no longer blocks the shutter");
        } catch (Throwable error) {
            Log.e(TAG, "unavailable", error);
        }
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class)
                .invoke(null, key, fallback);
            return String.valueOf(value);
        } catch (Throwable error) {
            return fallback;
        }
    }
}
