package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Pro video: let the build's own "Log LUT" panel appear on Log recordings.
 *
 * <p>The whole feature is compiled in -- {@code LogLutMenuView} (the panel with the preset list and
 * the "apply while recording" switch), {@code LogPreviewView} (the small name label over the
 * viewfinder), {@code LogVideoPresenter}, {@code Lut3dDataManager} and the settings row
 * {@code pref_log_lut_video_key}. None of it ever runs on PKJ110, because the presenter's
 * visibility check needs three answers that this build never gives:
 *
 * <pre>
 *   ih.g.C2(Z)   // checkLogPreviewVisible
 *     v8  = o9.h.m()                      // o9.h.c(4, 0) == 1  -> master.video.feature.list[4]
 *     v9  = "on".equals(hh.a.e())         // pref_log_video_key, defaults to "off"
 *     ...  if (!(v8 &amp;&amp; v9 &amp;&amp; ...)) skip everything
 *   q4.b.f(DataKey) // the presenter's DataObserver: only expands the LUT menu when
 *                   //   o9.h.i() || o9.h.m()     i() == c(8, 0) == 1
 * </pre>
 *
 * <p>{@code o9.h.c()} is the same table {@code OpenGate} fights: it reads
 * {@code com.oplus.camera.master.video.feature.list} from the platform, and every slot this build
 * cares about comes back 0. So on PKJ110 the answer is 0 for both {@code m()} (index 4) and
 * {@code i()} (index 8), and {@code pref_log_video_key} stays "off" because nothing ever turns it
 * on. Answering those three questions the way the newer builds do is enough; the panel, the list,
 * the switch and the recorder side are all stock code.
 *
 * <p>{@code i()} is "support custom LUT": when it is true the presenter skips the simple
 * {@code LogPreviewView} label and drives the full {@code LogLutMenuView} panel instead, which is
 * what we want. {@code m()} is the plain "Log preview" switch and is checked first.
 *
 * <p>Everything here is observation-friendly on purpose: if the panel shows up but looks wrong,
 * the log lines below say which gate was answered, which branch stock took, and whether the LUT
 * table was ever asked for.
 */
public final class LogLut {
    private static final String TAG = "LogLut";

    /** MasterVideoDecision -- same class OpenGate hooks, different answers. */
    private static final String DECISION = "o9.h";
    /** LogVideoModel: e() returns pref_log_video_key, default "off". */
    private static final String LOG_VIDEO_MODEL = "hh.a";
    /** LogVideoPresenter. */
    private static final String PRESENTER = "ih.g";
    /** The LUT panel itself. */
    private static final String MENU_VIEW = "com.oplus.camera.feature.logvideo.view.LogLutMenuView";
    /** 3D LUT loader -- the thing that actually needs log_video_{sdr,hdr}_3dlut.cube. */
    private static final String LUT_MANAGER = "com.oplus.camera.common.utils.Lut3dDataManager";

    private static volatile boolean reportedM;
    private static volatile boolean reportedI;
    private static volatile boolean reportedE;
    private static volatile boolean reportedVisible;
    private static volatile boolean reportedPanel;
    private static volatile boolean reportedLoad;

    private LogLut() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "log lut unavailable", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.LOG_LUT);
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        Class<?> decision = XposedHelpers.findClass(DECISION, classLoader);
        Class<?> model = XposedHelpers.findClass(LOG_VIDEO_MODEL, classLoader);
        Class<?> presenter = XposedHelpers.findClass(PRESENTER, classLoader);

        // Gate 1: "Log preview" master switch, index 4 of the master-video feature list.
        XposedBridge.hookAllMethods(decision, "m", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                boolean stock = Boolean.TRUE.equals(param.getResult());
                if (!stock) param.setResult(Boolean.TRUE);
                if (!reportedM) {
                    reportedM = true;
                    Log.i(TAG, "log preview gate m() answered true (stock " + stock + ")");
                }
            }
        });

        // Gate 2: "custom LUT supported", index 8. True selects the full panel over the name label.
        XposedBridge.hookAllMethods(decision, "i", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                boolean stock = Boolean.TRUE.equals(param.getResult());
                if (!stock) param.setResult(Boolean.TRUE);
                if (!reportedI) {
                    reportedI = true;
                    Log.i(TAG, "custom lut gate i() answered true (stock " + stock + ")");
                }
            }
        });

        // Gate 3: pref_log_video_key, which nothing in this build ever writes. Report it as "on"
        // so the presenter's checkLogPreviewVisible() gets past its second condition.
        XposedBridge.hookAllMethods(model, "e", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled()) return;
                Object stock = param.getResult();
                if (!"on".equals(stock)) param.setResult("on");
                if (!reportedE) {
                    reportedE = true;
                    Log.i(TAG, "pref_log_video_key reported as on (stock " + stock + ")");
                }
            }
        });

        // Observation: which branch checkLogPreviewVisible() takes.
        XposedBridge.hookAllMethods(presenter, "C2", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!enabled() || reportedVisible) return;
                reportedVisible = true;
                Object arg = param.args.length > 0 ? param.args[0] : null;
                Log.i(TAG, "checkLogPreviewVisible ran, arg " + arg);
            }
        });

        observePanel(classLoader);
        observeLutLoad(classLoader);

        Log.i(TAG, "log lut hook installed");
    }

    /** LogLutMenuView.D() is "show the panel"; seeing it means the entry never was missing. */
    private static void observePanel(ClassLoader classLoader) {
        try {
            Class<?> view = XposedHelpers.findClass(MENU_VIEW, classLoader);
            XposedBridge.hookAllMethods(view, "D", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled() || reportedPanel) return;
                    reportedPanel = true;
                    Log.i(TAG, "lut panel shown by stock (D)");
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "lut panel class unavailable", error);
        }
    }

    /**
     * The panel is only useful if Lut3dDataManager can find its table. On PKJ110 neither
     * /odm/etc/camera/filters_lut/log_video_sdr_3dlut.cube nor the HDR one exists, so log the
     * answer instead of letting a silent null float around.
     */
    private static void observeLutLoad(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(LUT_MANAGER, classLoader);
            XposedBridge.hookAllMethods(manager, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || reportedLoad) return;
                    Object index = param.args.length > 0 ? param.args[0] : null;
                    Object table = param.getResult();
                    int length = (table instanceof float[]) ? ((float[]) table).length : -1;
                    // Index 9 / 10 are the Log preview tables (sdr / hdr).
                    if (!(index instanceof Integer)) return;
                    int which = (Integer) index;
                    if (which != 9 && which != 10) return;
                    reportedLoad = true;
                    Log.i(TAG, "lut table " + which + " -> " + (length < 0 ? "missing" : length + " floats"));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "lut manager unavailable", error);
        }
    }
}
