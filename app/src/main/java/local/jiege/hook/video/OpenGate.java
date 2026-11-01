package local.jiege.hook.video;

import android.media.CamcorderProfile;
import android.util.Size;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Pro video: put 片门全开 (open gate) into the resolution list, as the last entry after 4K / 2K.
 *
 * The build already ships the whole open-gate feature: the option string "video_size_open_gate",
 * the panel label (camera_video_size_open_gate_top_name), the record geometry, the preview region
 * dimen and the "only OIS" hint. What it does not ship is the entry itself, because
 *
 *   CameraSettingsConfig.addVideoBackOptionItem()  gates it behind  o9.h.p()
 *   o9.h.p()  ==  o9.h.c(14, 0) >= 1   // com.oplus.camera.master.video.feature.list[14]
 *
 * and that feature list comes from the platform. Returning true from p() restores the entry, and
 * everything downstream is stock code: rm.e.lk() already switches on "video_size_open_gate" and
 * picks the largest 4:3 size the camera reports (4096x3072 here, read through StreamConfigurationMap
 * at ratio 1.33333), so nothing has to be pinned by hand any more.
 *
 * The lk() / gl() / ca() hooks below only observe; they log what stock decided so a wrong size or
 * a 16:9 viewfinder is visible in logcat without a rebuild.
 */
public final class OpenGate {
    private static final String TAG = "OpenGate";
    private static final String DECISION = "o9.h";
    private static final String MASTER_VIDEO_MODE = "rm.e";
    private static final String VIDEO_MODE = "qm.w0";
    /** The option value the panel and the recorder both know; stock hides it, never removes it. */
    private static final String OPEN_GATE_VALUE = "video_size_open_gate";

    private static volatile String lastSize;
    private static volatile String lastPreview;
    private static volatile String lastProfile;
    private static volatile boolean reported;
    private static volatile String lastInjected;
    private static volatile Class<?> masterVideoMode;

    private OpenGate() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "open gate unavailable", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.OPEN_GATE);
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        Class<?> decision = XposedHelpers.findClass(DECISION, classLoader);
        masterVideoMode = XposedHelpers.findClass(MASTER_VIDEO_MODE, classLoader);
        Class<?> videoMode = XposedHelpers.findClass(VIDEO_MODE, classLoader);

        // The single gate in front of the resolution entry. It is asked once per panel build, so
        // the log line is kept to the first answer of the process.
        XposedBridge.hookAllMethods(decision, "p", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled() || !Boolean.FALSE.equals(param.getResult())) return;
                param.setResult(Boolean.TRUE);
                if (reported) return;
                reported = true;
                Log.i(TAG, "open gate reported as supported");
            }
        });

        // Observation only: the clip size stock ends up with for the current video_size value.
        XposedBridge.hookAllMethods(masterVideoMode, "lk", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled() || !(param.getResult() instanceof Size)) return;
                Size size = (Size) param.getResult();
                String line = describe(param.thisObject) + " -> " + size.getWidth() + "x" + size.getHeight();
                if (line.equals(lastSize)) return;
                lastSize = line;
                Log.i(TAG, "clip size " + line);
            }
        });

        // Observation only: the viewfinder rectangle, so a 16:9 viewfinder on a 4:3 clip shows up.
        XposedBridge.hookAllMethods(masterVideoMode, "ca", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled() || !(param.getResult() instanceof Size)) return;
                Size size = (Size) param.getResult();
                String line = size.getWidth() + "x" + size.getHeight();
                if (line.equals(lastPreview)) return;
                lastPreview = line;
                Log.i(TAG, "preview size " + line);
            }
        });

        // Observation only: the profile the recorder is finally handed.
        XposedBridge.hookAllMethods(videoMode, "fl", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled() || !masterVideoMode.isInstance(param.thisObject)) return;
                if (param.args.length < 1 || !(param.args[0] instanceof CamcorderProfile)) return;
                CamcorderProfile profile = (CamcorderProfile) param.args[0];
                String geometry = profile.videoFrameWidth + "x" + profile.videoFrameHeight
                        + "@" + profile.videoFrameRate;
                if (geometry.equals(lastProfile)) return;
                lastProfile = geometry;
                Log.i(TAG, "recorder profile " + geometry);
            }
        });

        patchSupportedList(classLoader);

        Log.i(TAG, "open gate hook installed");
    }

    /**
     * The resolution row in pro video is filled from CameraSettingsConfig.getSupportedList(); it
     * asks for pref_video_size_key, whose table is [720p, 1080p, 4k]. "video_size_open_gate" has no
     * row of its own there, so it is appended here -- last, right after 4K/2K, which is where the
     * X10 shows it. rm.e.lk() already switches on the value, so the geometry follows on its own.
     */
    private static void patchSupportedList(ClassLoader classLoader) {
        try {
            Class<?> config = XposedHelpers.findClass(
                    "com.oplus.camera.common.config.CameraSettingsConfig", classLoader);
            XposedBridge.hookAllMethods(config, "getSupportedList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || param.args.length < 1 || !(param.args[0] instanceof String)) return;
                    String key = (String) param.args[0];
                    if (!key.startsWith("pref_video_size_key") && !key.startsWith("pref_master_video_size_key")) return;
                    Object result = param.getResult();
                    if (!(result instanceof java.util.List)) return;
                    java.util.List<?> current = (java.util.List<?>) result;
                    if (current.contains(OPEN_GATE_VALUE)) return;
                    java.util.List<Object> extended = new java.util.ArrayList<>(current);
                    extended.add(OPEN_GATE_VALUE);
                    param.setResult(extended);
                    String line = key + " -> " + extended;
                    if (line.equals(lastInjected)) return;
                    lastInjected = line;
                    Log.i(TAG, "resolution row " + line);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "supported list unavailable", error);
        }
    }

    /** "video_size_4kuhd" for the mode the call came from, for readable log lines. */
    private static String describe(Object mode) {
        try {
            Object value = XposedHelpers.callMethod(mode, "h7");
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {
            // A build that renames the lookup just loses the label, not the measurement.
        }
        return "?";
    }
}
