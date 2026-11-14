package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * After a GR shot the next 大师 live photo plays back much more saturated than its own cover.
 *
 * LivePhotoPresenter queries "is the preview HDR" into one boolean (mbPreviewHdr) on create, on
 * resume and on every mode-switch stage, and hands it to the video encoder: it picks the
 * color-format, the data-space and the HEVC profile of the live photo's video track. Entering GR
 * turns that query off, and leaving GR it stays off -- the presenter is destroyed and created
 * again, and both paths read the same stale answer, so nothing restores it short of restarting
 * the camera. The video is then encoded as SDR while the preview it records from is HLG, which
 * plays back clearly more vivid, while the still cover comes from the capture pipeline and looks
 * right. See docs/LIVE_PHOTO_GR_MISMATCH.md.
 *
 * The repair is narrow on purpose: only when this process has already seen the flag true (so the
 * device does render HDR previews), the camera is not in GR, and the query answered false, the
 * flag is put back to true. That is a stale capability answer being corrected, not HDR being
 * forced on where it does not belong.
 */
final class LivePhotoHdrRestore {
    private static final String TAG = "RicohGrPort";
    /** mbPreviewHdr has been true once in this process: the build does HDR live photo previews. */
    private static volatile boolean sawHdrPreview;
    /** Last repair described, so the log carries one line per change instead of one per stage. */
    private static volatile String traced;
    /** One line on the first call that gets through, so a silent hook is distinguishable from none. */
    private static volatile boolean alive;

    private LivePhotoHdrRestore() {}

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        if (!symbols.has("LivePhotoPresenter.previewHdr", "LivePhotoPresenter.modeStage",
                "LivePhotoPresenter.resume")) {
            Log.i(TAG, "live photo preview HDR restore unavailable (symbols missing)");
            return;
        }
        final Field flag = symbols.field("LivePhotoPresenter.previewHdr");
        guard("mode stage", () -> hook(symbols.method("LivePhotoPresenter.modeStage"), flag, true));
        guard("resume", () -> hook(symbols.method("LivePhotoPresenter.resume"), flag, false));
    }

    /** onResume is f2(true); f2(false) is onPause and leaves the flag alone. */
    private static void hook(Method method, final Field flag, final boolean modeStage) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!modeStage && !Boolean.TRUE.equals(param.args[0])) return;
                Object presenter = param.thisObject;
                try {
                    boolean hdr = flag.getBoolean(presenter);
                    if (!alive) {
                        alive = true;
                        Log.i(TAG, "live photo preview HDR watched (" + method.getName() + ", hdr=" + hdr + ")");
                    }
                    if (hdr) {
                        sawHdrPreview = true;
                        return;
                    }
                    if (!sawHdrPreview || GrState.isGrMode()) return;
                    flag.setBoolean(presenter, true);
                    String why = (modeStage ? "stage " + param.args[0] : "resume") + ", gr=false";
                    if (!why.equals(traced)) {
                        traced = why;
                        Log.i(TAG, "live photo preview HDR restored (" + why + ")");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "live photo preview HDR restore failed", error);
                }
            }
        });
    }

    private static void guard(String part, Runnable installer) {
        try {
            installer.run();
        } catch (Symbols.MissingSymbol unavailable) {
            Log.i(TAG, "live photo preview HDR " + part + " unavailable (" + unavailable.getMessage() + ")");
        } catch (Throwable error) {
            Log.e(TAG, "live photo preview HDR " + part + " failed", error);
        }
    }
}
