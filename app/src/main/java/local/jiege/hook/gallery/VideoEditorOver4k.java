package local.jiege.hook.gallery;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery video editor: a clip longer than 3840 px on its long edge is dropped from the list the
 * editor is started with, and the editor then closes with "格式不支持" (videoeditor_notif_not_supported).
 * A pro-video open gate recording is 4096x3072, so it always lands on the wrong side of that check.
 *
 * The decision is one boolean on TranscodingManager, set once when the detector is set up from two
 * config reads (one of them the camera's 8K capability). No other code reads it: with the flag on,
 * a clip over 3840 is logged as "unsupported over 4K video" and skipped; with it off, the same clip
 * takes the ordinary path a 3840-wide 4K clip takes, which hands Meicam a downscaled source. The
 * flag is cleared before each detect pass, so an edit opens on the downscale path, not refused.
 *
 * Everything past the 3840 comparison is the stock code: the target of the downscale, the source
 * dimensions and the frame rate stay as reported, so the timeline geometry is unaffected.
 */
public final class VideoEditorOver4k {
    private static final String TAG = "VideoEditorOver4k";

    private VideoEditorOver4k() {}

    /** In the gallery process, before anything opens the video editor. */
    public static void installGallery(ClassLoader classLoader) {
        Log.guard(TAG, "over-4K clips take the downscale path", () -> {
            Class<?> managerClass = Symbols.get().cls("VideoTranscode");
            Field limit = Symbols.get().field("VideoTranscode.limit");
            Method detect = Symbols.get().method("VideoTranscode.detect");
            XposedBridge.hookMethod(detect, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object manager = manager(param.thisObject, managerClass);
                    if (manager == null) {
                        Log.i(TAG, "no transcode manager on " + param.thisObject.getClass().getName());
                        return;
                    }
                    try {
                        boolean was = limit.getBoolean(manager);
                        Log.i(TAG, "detect pass over " + size(param.args[0]) + " item(s), over-4K limit " + was);
                        if (!was) return;
                        limit.setBoolean(manager, false);
                    } catch (Throwable error) {
                        Log.e(TAG, "could not lift the over-4K limit", error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) {
                        Log.e(TAG, "detect pass failed", param.getThrowable());
                        return;
                    }
                    Log.i(TAG, "detect pass returned " + size(param.getResult()) + " entr(y|ies)");
                }
            });
        });
    }

    /** The TranscodingManager instance the detector keeps -- looked up by type, not by field name. */
    private static Object manager(Object detector, Class<?> managerClass) {
        for (Field field : detector.getClass().getDeclaredFields()) {
            if (field.getType() != managerClass) continue;
            try {
                field.setAccessible(true);
                return field.get(detector);
            } catch (Throwable error) {
                Log.e(TAG, "manager field " + field.getName() + " unreadable", error);
            }
        }
        return null;
    }

    /** Entry count of a list argument or result, for the one line that says the item was kept. */
    private static String size(Object value) {
        return value instanceof List ? String.valueOf(((List<?>) value).size()) : "?";
    }
}
