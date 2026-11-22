package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicLong;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.xpan.XpanLiveFeature;

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
    private static final long REARM_DELAY_MS = 300;

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

    /**
     * Incremented after a re-arm's {@code e(1,2,"common")} has actually completed -- i.e. after
     * {@code Lut3dDataManager.c} was washed back to the palette table. XpanLiveFeature watches
     * this counter: a BasicTone GL texture built from the pre-wash manager.c keeps serving the
     * stale table forever, so its ensure-path rebuilds the texture whenever the counter moves.
     */
    public static final AtomicLong paletteWashes = new AtomicLong();

    /**
     * r424: defensive copy of the verified clean-env XPAN BasicTone table (basicHash
     * -881252736 on r423 probes), published by the re-arm right after e(1,0,"xpan") returns.
     * manager.c is natively re-computed in place (r423 saw it flip between -881252736 and
     * cb792680 within milliseconds), so texture builders must NOT read manager.c live --
     * they snapshot this copy instead.
     */
    public static volatile float[] cleanXpanTable;
    public static volatile int cleanXpanTableSize;

    private GrPreviewStreamRestore() {}

    static boolean inMasterMode() {
        return "professional".equals(currentMode);
    }

    static boolean inXpanMode() {
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
                        if (GrState.isGrMode()) return;
                        if (!(param.args[0] instanceof Integer) || (Integer) param.args[0] != 1) return;
                        if (!(param.args[1] instanceof Integer) || (Integer) param.args[1] != 0) return;
                        String name = param.args[2] == null ? "" : String.valueOf(param.args[2]);
                        boolean xpanRefresh = inXpanMode() && "xpan".equals(name);
                        if (!xpanRefresh) return;
                        /*
                         * r423: the XPAN live attachment is toned by whatever manager.c holds when
                         * the frames render. The verified-good r242 probe measured the clean-env
                         * e(1,0,"xpan") table at basicHash=-881252736 -- that table IS the XPAN
                         * developer. The earlier "palette" theory was a misread of the same probe:
                         * palette (-1504930108, from e(1,2,"common")) tones XPAN frames Log-grey,
                         * and so does any e(1,0,"xpan") recomputed on a GR/master-poisoned env.
                         * So the re-arm no longer ends on palette: it washes the shared env with
                         * e(1,2,"common") (restoring the fresh-process LMT state, r419 evidence)
                         * and immediately re-issues e(1,0,"xpan") so the final manager.c is the
                         * clean-env XPAN table, on every XPAN entry and after GR alike.
                         *
                         * The delay shrank from 1.5 s to 300 ms: a wash landing inside the
                         * recording window (which starts ~2.6 s before the shutter) froze the
                         * stream into the all-green attachment. If frames are already flowing
                         * when the task fires, the wash is skipped entirely -- a toned-wrong
                         * current shot is recoverable, a frozen one is not.
                         */
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
                                if (GrState.isGrMode()) return;
                                if (!armedMode.equals(currentMode) || !inXpanMode()) return;
                                if (armedGeneration != rearmGeneration.get()) return;
                                if (XpanLiveFeature.isXpanLiveFrameFlowing()) {
                                    Log.i(TAG, "xpan env wash skipped, live frames flowing");
                                    return;
                                }
                                rearming.set(Boolean.TRUE);
                                Object washed = XposedHelpers.callMethod(
                                    manager, "e", 1, 2, "common");
                                Object recomputed = XposedHelpers.callMethod(
                                    manager, "e", 1, 0, "xpan");
                                String basicHash = "unknown";
                                try {
                                    Object basic = XposedHelpers.getObjectField(manager, "c");
                                    if (basic instanceof float[]) {
                                        basicHash = String.valueOf(
                                            java.util.Arrays.hashCode((float[]) basic));
                                    }
                                } catch (Throwable ignored) {
                                }
                                // Published only after both e() calls returned, so watchers
                                // rebuilding their textures always read the recomputed
                                // clean-env XPAN table, not the transient palette state.
                                try {
                                    Object basic = XposedHelpers.getObjectField(manager, "c");
                                    if (basic instanceof float[]) {
                                        float[] table = (float[]) basic;
                                        if (table.length > 0) {
                                            float[] snapshot =
                                                java.util.Arrays.copyOf(table, table.length);
                                            cleanXpanTable = snapshot;
                                            cleanXpanTableSize =
                                                XposedHelpers.getIntField(manager, "t");
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                paletteWashes.incrementAndGet();
                                Log.i(TAG, "xpan env washed + xpan table recomputed"
                                    + " after e(1,0,\"" + from + "\")"
                                    + " -> e(1,2,\"common\") = " + washed
                                    + ", e(1,0,\"xpan\") = " + recomputed
                                    + ", basicHash=" + basicHash
                                    + " (clean-env XPAN table expected)");
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

    private static final java.util.concurrent.atomic.AtomicBoolean forcedWashRunning =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * r462: run the XPAN env wash on demand. The armed wash only fires when native
     * happens to call e(1,0,...) in XPAN mode -- at a fresh session's first XPAN entry
     * that can lag the entry by seconds (measured grey 2026-11-20 20:59:52: the entry
     * frames sampled the leftover table because paletteWashes was still 0 and
     * {@link #cleanXpanTable} null, so the repair gate {@code wash <= 0} blocked and
     * field o kept the previous mode's table). Same body as the armed wash:
     * e(1,2,"common") restores the fresh-process LMT state, e(1,0,"xpan") recomputes
     * the clean-env XPAN table into manager.c, snapshot published to cleanXpanTable,
     * then paletteWashes increments so the draw-hook repair can push field o.
     */
    public static void forceXpanEnvWash(Object manager, String reason) {
        if (manager == null) return;
        if (!forcedWashRunning.compareAndSet(false, true)) return;
        try {
            if (GrState.isGrMode() || !inXpanMode()) return;
            if (XpanLiveFeature.isXpanLiveFrameFlowing()) {
                Log.i(TAG, "forced xpan wash skipped, live frames flowing (" + reason + ")");
                return;
            }
            rearming.set(Boolean.TRUE);
            Object washed = XposedHelpers.callMethod(manager, "e", 1, 2, "common");
            Object recomputed = XposedHelpers.callMethod(manager, "e", 1, 0, "xpan");
            String basicHash = "unknown";
            try {
                Object basic = XposedHelpers.getObjectField(manager, "c");
                if (basic instanceof float[] && ((float[]) basic).length > 0) {
                    float[] table = (float[]) basic;
                    cleanXpanTable = java.util.Arrays.copyOf(table, table.length);
                    cleanXpanTableSize = XposedHelpers.getIntField(manager, "t");
                    basicHash = String.valueOf(java.util.Arrays.hashCode(table));
                }
            } catch (Throwable ignored) {
            }
            paletteWashes.incrementAndGet();
            Log.i(TAG, "forced xpan env wash (" + reason + ")"
                + " -> e(1,2,\"common\") = " + washed
                + ", e(1,0,\"xpan\") = " + recomputed
                + ", basicHash=" + basicHash);
        } catch (Throwable error) {
            Log.e(TAG, "forced xpan env wash failed", error);
        } finally {
            rearming.remove();
            forcedWashRunning.set(false);
        }
    }
}
