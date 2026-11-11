package local.jiege.hook.gallery;

import android.util.Size;
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
 *
 * <p>The editor then works on a resized copy, so a 4096x3072 open gate clip exports as 2160x2880:
 * the 4K cap is 3840x2160 and for a 4:3 source it is applied to the short edge. Opening the gate
 * above only gets the clip accepted; the second hook here keeps its size.
 */
public final class VideoEditorOver4k {
    private static final String TAG = "VideoEditorOver4k";

    /** Long edge of 16:9 4K; anything up to this is taken as is by the stock code. */
    private static final int LONG_EDGE_4K = 3840;
    /** Long edge of DCI/open-gate 4K: the top of the range the stock code calls open gate. */
    private static final int LONG_EDGE_OPEN_GATE = 4096;

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

        // The editor sizes its working copy, and later its export, through this call. A 4:3 source
        // is handed back at 2160x2880 (the 4K cap applied to the short edge), so an export can never
        // be larger than that. Two callers matter: the input pass passes the source size, the export
        // pass passes the size the editor already holds plus the clip's own size as the last
        // argument. Either way the open-gate size wins, which is what the stock code would do if the
        // camera advertised the open-gate feature.
        Log.guard(TAG, "open-gate clips keep their own size", () -> {
            Method target = Symbols.get().method("VideoResolution.target");
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) return;
                    Size openGate = openGateSize(param.args);
                    Object requested = param.args[2];
                    Object computed = param.getResult();
                    if (computed instanceof Size) {
                        Size scaled = (Size) computed;
                        Log.i(TAG, "resolution " + scaled.getWidth() + "x" + scaled.getHeight()
                                + " from " + describe(param.args) + " key=" + requested);
                    }
                    if (openGate == null || !(computed instanceof Size)) return;
                    if (!is4k(requested)) return;
                    if (openGate.equals(computed)) return;
                    Size scaled = (Size) computed;
                    Log.i(TAG, "open-gate clip keeps its size: " + scaled.getWidth() + "x" + scaled.getHeight()
                            + " -> " + openGate.getWidth() + "x" + openGate.getHeight());
                    param.setResult(openGate);
                }
            });
        });
    }

    /** The largest of the size arguments that sits in the open-gate band, or null. */
    private static Size openGateSize(Object[] args) {
        Size found = null;
        for (Object arg : args) {
            if (!(arg instanceof Size)) continue;
            Size size = (Size) arg;
            int longEdge = Math.max(size.getWidth(), size.getHeight());
            if (longEdge <= LONG_EDGE_4K || longEdge > LONG_EDGE_OPEN_GATE) continue;
            if (found == null || longEdge > Math.max(found.getWidth(), found.getHeight())) found = size;
        }
        return found;
    }

    private static boolean is4k(Object key) {
        return key instanceof String && ((String) key).toUpperCase().startsWith("4K");
    }

    /** Every size argument, so one line says which branch of the caller fired. */
    private static String describe(Object[] args) {
        StringBuilder out = new StringBuilder();
        for (Object arg : args) {
            if (!(arg instanceof Size)) continue;
            if (out.length() > 0) out.append('/');
            out.append(((Size) arg).getWidth()).append('x').append(((Size) arg).getHeight());
        }
        return out.length() == 0 ? "no size" : out.toString();
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
