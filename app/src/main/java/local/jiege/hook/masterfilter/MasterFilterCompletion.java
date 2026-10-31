package local.jiege.hook.masterfilter;

import local.jiege.hook.common.Log;

/**
 * Master filter completion: extra master-mode LUTs, and the normal-mode effects that the master
 * versions of 霓虹 and 和光 lack.
 */
public final class MasterFilterCompletion {
    private static final String TAG = "MasterFilter";

    private MasterFilterCompletion() {}

    public static void installCamera(ClassLoader classLoader) {
        // Log.guard: a MissingSymbol (app-build difference) is logged at info level, so it does
        // not raise the status page's error count; every other failure still does.
        Log.guard(TAG, "master LUT hooks", () -> MasterFilmLuts.install(classLoader));
        Log.guard(TAG, "neon glow hooks", () -> MasterNeonGlow.install(classLoader));
        Log.guard(TAG, "Portra halation hook", () -> MasterPortraHalation.install(classLoader));
        Log.guard(TAG, "grain hooks", () -> MasterGrain.install(classLoader));
        Log.guard(TAG, "preset list sync hook", () -> MasterPresetListSync.install(classLoader));
        // MasterProPresets (Pro gear parameters kept with master presets) is not installed:
        // shelved until its behaviour is worked out on the device.
    }
}
