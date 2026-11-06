package local.jiege.hook.masterfilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import local.jiege.hook.common.Log;
import local.jiege.hook.softlight.SoftLight;

/**
 * MONOPAN 50 / MONOPAN 50 迫冲 (Leica M3 port): tells the capture pass which of the two filters a
 * shot uses.
 *
 * The look itself is not the app's: the two monochrome LUTs only make the viewfinder grey, the M3
 * tone, the film grain and the vignetting are added by monopan.cpp on the finished capture, through
 * the vendor grain library. That pass cannot read the app's filter state, so this hook writes the
 * variant into files/jiege/monopan/active just before the capture metadata is built, and the pass
 * reads it - a shot with any other filter finds no file and is left alone.
 *
 * Variant numbers are the grain library's own parameter sets: 1 = MONOPAN 50, 2 = 迫冲. See
 * docs/MONOPAN.md for how the two were told apart.
 */
public final class MonoPan {
    private static final String TAG = "MonoPan";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String MONOPAN_DIR = "/data/user/0/com.oplus.camera/files/jiege/monopan";
    private static final String ACTIVE = MONOPAN_DIR + "/active";
    private static final int PLAIN = 1;
    private static final int PUSH = 2;

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
        int forced = forced();
        if (forced != 0) return forced;
        if (tag == null) return 0;
        Object type = XposedHelpers.getObjectField(tag, "mFilterType");
        String name = type == null ? "" : type.toString().toUpperCase(java.util.Locale.ROOT);
        // The capture tag carries HDR variants of the type, hence the containment test. Case is not
        // stable across builds (the stock types are lower case), so both sides are folded.
        if (name.contains("MONOPAN50PRO")) return PUSH;
        if (name.contains("MONOPAN50")) return PLAIN;
        return 0;
    }

    /**
     * Debug switch: persist.omnicam.monopan.force = 1 or 2 applies that variant to every shot,
     * whatever filter is selected. A shot's capture route varies with how the camera runs the
     * effect, and exercising those routes should not depend on reaching the filter list in the UI.
     * 0, the default, keys the pass on the filter itself.
     */
    private static int forced() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            String value = (String) type.getMethod("get", String.class).invoke(null, "persist.omnicam.monopan.force");
            if (value == null || value.isEmpty()) return 0;
            int variant = Integer.parseInt(value.trim());
            return variant == PLAIN || variant == PUSH ? variant : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    /**
     * Writes (or clears) the marker the capture pass reads. It is written for every shot on
     * purpose: the pass groups a shot's frames by the marker's timestamp (one shot hands it the
     * 1280x960 thumbnail first and the full frame after it), so a file left from an earlier shot
     * would make the next one look like a leftover. Runs inside the camera process.
     */
    private static synchronized void write(int variant) {
        if (variant == 0) {
            File file = new File(ACTIVE);
            if (file.exists() && !file.delete()) Log.i(TAG, "active not cleared");
            return;
        }
        File dir = new File(MONOPAN_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.i(TAG, "monopan dir missing");
            return;
        }
        try (FileOutputStream out = new FileOutputStream(ACTIVE)) {
            out.write(String.valueOf(variant).getBytes("US-ASCII"));
        } catch (IOException error) {
            Log.e(TAG, "active not written", error);
            return;
        }
        Log.i(TAG, "variant " + variant + " for this shot");
        // From here on the render entries name themselves for a few seconds, so a shot that took a
        // route the grain pass does not cover says so in the log (soft_light.cpp, shot trace).
        SoftLight.noteShot();
    }
}
