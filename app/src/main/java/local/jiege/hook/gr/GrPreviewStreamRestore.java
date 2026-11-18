package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicLong;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Keeps the live-photo video slot readable after the process has been through GR.
 *
 * <p>The CombineLut env is one native singleton behind {@code Lut3dDataManager.e(combineType,
 * capMode, capModeName)}, and a GR visit poisons it twice over
 * (docs/LIVE_PHOTO_GR_MISMATCH.md &#167;16-21):
 *
 * <ul>
 * <li>Every plain {@code e(1, 0, <mode>)} rebuild (professional, xpan, highPixel...) takes the
 * initV2 branch, whose {@code process()} step recomputes the manager's BasicTone float[]
 * (field {@code c}) <i>from the native env the GR 4-param init left behind</i> -- so after GR the
 * array that XPAN's BasicTone bridge and the live renderers build their GL textures from holds
 * broken tables (probe hash shows GR residue where the stock photo palette belongs).</li>
 * <li>Master's own post-capture refresh ({@code e(1, 0, "professional")}) additionally releases
 * and rebuilds the whole env tableless, so the <i>next</i> master live photo's pre-recorded
 * frames are toned raw ({@code readLMTLut ReadLut:0}) and play back warm and oversaturated.</li>
 * </ul>
 *
 * <p>Photo mode ("common") and the palette branch (initV3) read their tables at once and leave
 * {@code c} correct. XPAN can therefore re-arm {@code e(1, 2, "common")} after its own (1, 0)
 * rebuild:
 *
 * <ol>
 * <li><b>XPAN.</b> XPAN never refreshes its own video slot (a fresh XPAN entry only logs
 * {@code e(1, 0, "xpan")}; no arg=2 load ever follows) -- stock it simply inherits the palette
 * slot photo mode left behind, which is why XPAN lives were good before and green after a GR
 * visit. The same delayed re-arm now runs after XPAN's (1, 0) init, handing XPAN the palette
 * slot it used to inherit. Green frames (chroma planes zeroed, R==B with G high) were XPAN
 * renders built from the poisoned float[].</li>
 * <li><b>Empty-name video rewrite.</b> Master's delayed video-slot rebuild after a direct GR
 * exit arrives with an empty capMode name; it is rewritten to {@code "common"} so it too lands
 * on the palette branch.</li>
 * </ul>
 *
 * <p>Scope: nothing runs before the first GR visit, nothing runs in GR, and only XPAN is
 * considered by the delayed environment hook. r397 suspended the master re-arm because the plain
 * {@code e(1,2,"common")} re-arm left the singleton on {@code SimTool_Dolby / cap=2} and the
 * master preview turned grey 1.5 s after the switch; the r419 "two-step wash" variant was measured
 * on 2026-11-18 to be a no-op on the master table (hash before == hash after) and did not change
 * the post-GR video, so r420 removes the master branch entirely -- the actual post-GR master-live
 * defect turned out to be the stale {@code LutParameters.g} handled in
 * {@link GrMasterLiveLutRestore}. XPAN keeps its earlier single-step re-arm because it has no
 * stock {@code (1, 2)} refresh of its own. A delayed task records both the mode and generation
 * that scheduled it; a newer refresh or any mode switch makes the old task a no-op. The re-arm
 * runs on its own thread with {@link #rearming} set, so its own {@code e()} calls do not
 * schedule a follow-up task.
 */
public final class GrPreviewStreamRestore {
    private static final String TAG = "RicohGrPort";
    private static final long REARM_DELAY_MS = 1500;

    /**
     * Shares {@code persist.omnicam.aura.livetable} with GrMasterLiveLutRestore, opt-in since
     * r418. The video-slot rewrite below ({@code e(1,2,"")} -> {@code "common"}) forces the live
     * video to rebuild with the still pipeline's palette; builds where it was active graded the
     * video 0.01-0.09 warmer than its own cover while the stock camera comes out about 0.02
     * cooler -- and on the current camera build the rewrite never fires at all. Default off means
     * "the module does not grade the live video in any way", which is also what the reference
     * builds (Muse 3.4.1, X8U port) ship.
     */
    private static final boolean VIDEO_SLOT_REWRITE =
        "on".equalsIgnoreCase(prop("persist.omnicam.aura.livetable"));

    private static boolean rewriteSkipLogged;

    private static String prop(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : value.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    /** Set once this camera process has left GR; only then are env re-arms scheduled. */
    private static volatile boolean leftGr;

    /** Internal name of the current camera mode, kept in step by the ModeSwitch hook
     *  ("professional" is master, "xpan" XPAN; "gr"/"highPixel"/"retroCamera" the others). */
    private static volatile String currentMode;

    /**
     * Marks the gr-tone-rearm thread while it re-issues e() calls, so the afterHook below does
     * not treat the re-arm's own e(1,2,"common") as a fresh refresh and schedule itself again.
     */
    private static final ThreadLocal<Boolean> rearming = new ThreadLocal<>();

    /** Invalidates an older master/XPAN task whenever a newer refresh arrives. */
    private static final AtomicLong rearmGeneration = new AtomicLong();

    private GrPreviewStreamRestore() {}

    static boolean inMasterMode() {
        return "professional".equals(currentMode);
    }

    private static boolean inXpanMode() {
        return "xpan".equals(currentMode);
    }

    /** True after this camera process has actually switched out of GR. */
    static boolean hasLeftGr() {
        return leftGr;
    }

    /** Internal name of the mode the camera last switched to, for diagnostics. */
    static String modeName() {
        return currentMode;
    }

    public static void install(final Symbols symbols, final ClassLoader classLoader) {
        try {
            // Do NOT read the holder's static field here: reading it runs Lut3dDataManager's
            // static initialiser too early and hangs the camera launch (the same trap
            // ModeStateSplit documents). The manager is only touched inside the hooks below,
            // by which time the camera is fully up.
            final Class<?> lut = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager", classLoader);
            XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    String from = param.args.length > 0 ? String.valueOf(param.args[0]) : "";
                    String to = param.args.length > 1 ? String.valueOf(param.args[1]) : "";
                    if (!to.isEmpty()) currentMode = to;
                    if (!"gr".equals(from) || "gr".equals(to) || from.equals(to)) return;
                    leftGr = true;
                    Log.i(TAG, "leaving GR -> " + to + "; video-slot re-arms armed");
                }
            });
            hookEnvRefresh(lut);
            Log.i(TAG, "tone env restore installed");
        } catch (Throwable error) {
            Log.e(TAG, "tone env restore unavailable", error);
        }
    }

    /**
     * One hook on {@code Lut3dDataManager.e(I, I, String)}. The master empty-name video rebuild is
     * still normalized to "common" before the native call. XPAN {@code (1, 0)} rebuilds schedule
     * the delayed palette re-arm; the master branch is retained behind an explicit r397 diagnostic
     * disable because that same global re-arm now has direct evidence of turning the preview grey.
     */
    private static void hookEnvRefresh(final Class<?> lut) {
        try {
            XposedHelpers.findAndHookMethod(lut, "e", int.class, int.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!leftGr || GrState.isGrMode() || !inMasterMode()) return;
                        if (!(param.args[0] instanceof Integer) || (Integer) param.args[0] != 1) return;
                        if (!(param.args[1] instanceof Integer) || (Integer) param.args[1] != 2) return;
                        if (param.args[2] != null && !String.valueOf(param.args[2]).isEmpty()) return;
                        if (!VIDEO_SLOT_REWRITE) {
                            if (!rewriteSkipLogged) {
                                rewriteSkipLogged = true;
                                Log.i(TAG, "video slot rebuild left alone"
                                    + " (default since r418; enable with livetable=on)");
                            }
                            return;
                        }
                        param.args[2] = "common";
                        Log.i(TAG, "video slot rebuild: e(1,2,\"\") -> e(1,2,\"common\")");
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(rearming.get())) return;
                        if (!leftGr || GrState.isGrMode()) return;
                        if (!(param.args[0] instanceof Integer) || (Integer) param.args[0] != 1) return;
                        if (!(param.args[1] instanceof Integer) || (Integer) param.args[1] != 0) return;
                        String name = param.args[2] == null ? "" : String.valueOf(param.args[2]);
                        boolean xpanRefresh = inXpanMode() && "xpan".equals(name);
                        if (!xpanRefresh) return;
                        final Object manager = param.thisObject;
                        final String from = name;
                        final String armedMode = currentMode;
                        final long armedGeneration = rearmGeneration.incrementAndGet();
                        final Thread thread = new Thread(() -> {
                            try {
                                Thread.sleep(REARM_DELAY_MS);
                                // The window may have ended in another mode switch: re-check
                                // both mode and generation before touching the env. Only the
                                // mode that scheduled this task may consume the re-arm.
                                if (!leftGr || GrState.isGrMode()) return;
                                if (!armedMode.equals(currentMode) || !inXpanMode()) return;
                                if (armedGeneration != rearmGeneration.get()) return;
                                rearming.set(Boolean.TRUE);
                                Object rearmed = XposedHelpers.callMethod(
                                    manager, "e", 1, 2, "common");
                                Log.i(TAG, armedMode + " video slot re-armed after e(1,0,\"" + from
                                    + "\") -> e(1,2,\"common\") = " + rearmed);
                            } catch (Throwable error) {
                                Log.e(TAG, "video slot re-arm failed", error);
                            } finally {
                                rearming.remove();
                            }
                        }, "gr-tone-rearm");
                        thread.start();
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "env refresh hook failed", error);
        }
    }
}
