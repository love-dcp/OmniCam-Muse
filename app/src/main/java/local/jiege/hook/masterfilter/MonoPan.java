package local.jiege.hook.masterfilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import local.jiege.hook.common.Log;

/**
 * MONOPAN 50 / MONOPAN 50 迫冲 (Leica M3 port): tells the capture pass which of the two filters a
 * shot uses.
 *
 * The look itself is not the app's: the two monochrome LUTs only make the viewfinder grey, the M3
 * tone, the film grain and the vignetting are added by monopan.cpp on the finished capture, through
 * the vendor grain library. That pass cannot read the app's filter state, so this hook writes the
 * variant into files/jiege/monopan/active just before the capture metadata is built, and the pass
 * consumes (deletes) it - a shot with any other filter finds no file and is left alone.
 *
 * Variant numbers are the grain library's own parameter sets: 2 = MONOPAN 50, 1 = 迫冲. See
 * docs/MONOPAN.md for how the two were told apart.
 */
public final class MonoPan {
    private static final String TAG = "MonoPan";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String MONOPAN_DIR = "/data/user/0/com.oplus.camera/files/jiege/monopan";
    private static final String ACTIVE = MONOPAN_DIR + "/active";
    private static final String PLAIN_ID = "JG_MONOPAN50.cube.rgb.bin";
    private static final String PUSH_ID = "JG_MONOPAN50PRO.cube.rgb.bin";
    private static final int PLAIN = 2;
    private static final int PUSH = 1;

    /** Last value written, so a run of shots with the same filter touches the file once. */
    private static volatile String written;

    private MonoPan() {}

    public static void installCamera(ClassLoader classLoader) {
        // The same call the master grain hook watches: it carries the filter type of this shot.
        XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", classLoader, "setPositiveFilterGrain",
            SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        write(variant(param.args[0]));
                    } catch (Throwable error) {
                        Log.e(TAG, "variant not written", error);
                    }
                }
            });
        Log.i(TAG, "monopan capture state installed");
    }

    /** The variant for this shot, or 0 for every other filter. */
    private static int variant(Object tag) throws Throwable {
        if (tag == null) return 0;
        Object type = XposedHelpers.getObjectField(tag, "mFilterType");
        String name = type == null ? "" : type.toString().toUpperCase(java.util.Locale.ROOT);
        // The capture tag carries HDR variants of the type, hence the containment test. Case is not
        // stable across builds (the stock types are lower case), so both sides are folded.
        if (name.contains("MONOPAN50PRO")) return PUSH;
        if (name.contains("MONOPAN50")) return PLAIN;
        return 0;
    }

    /** Writes (or clears) the marker the capture pass reads. Runs inside the camera process. */
    private static synchronized void write(int variant) {
        String value = variant == 0 ? null : String.valueOf(variant);
        if (value == null ? written == null : value.equals(written)) {
            if (value == null || new File(ACTIVE).exists()) return;
        }
        File dir = new File(MONOPAN_DIR);
        if (variant == 0) {
            written = null;
            File file = new File(ACTIVE);
            if (file.exists() && !file.delete()) Log.i(TAG, "active not cleared");
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.i(TAG, "monopan dir missing");
            return;
        }
        try (FileOutputStream out = new FileOutputStream(ACTIVE)) {
            out.write(value.getBytes("US-ASCII"));
            written = value;
            Log.i(TAG, "variant " + value + " for this shot");
        } catch (IOException error) {
            written = null;
            Log.e(TAG, "active not written", error);
        }
    }
}
