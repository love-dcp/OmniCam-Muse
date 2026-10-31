package local.jiege.hook.highpixel;

import android.app.Application;
import android.app.AndroidAppHelper;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import local.jiege.hook.common.Log;

/**
 * 哈苏超清's palette-only capture route through the filter stage.
 *
 * <p>Without a filter (or a soft light) nothing in this mode initialises the meicam engine, so
 * {@code libmsnativefilter.so} is never loaded, {@code MsRenderHDRHostBuffer} is never called, and
 * the native palette hook (soft_light.cpp, which applies {@code files/jiege/palette_lut.bin} on
 * the capture render) has no entry: the dial reaches the preview and the quick image, but the
 * 50 MP full pipeline drops it (verified 2026-10-23: the same shot with a real filter picked
 * loads meicam at 20:03, passes host at 1280x960 and 8192x6144, and the palette lands in the
 * 50 MP file).
 *
 * <p>This class gives the palette-only shot the same ride minus the look: an <b>identity</b> LUT
 * (every lattice point maps to itself) in the camera's own .MS-LUT format is staged under
 * {@code files/jiege/lut}, where the native path remap serves reads of
 * {@code /odm/etc/camera/meishe_lut/<name>} (pop_path_remap.cpp, the same mechanism the imported
 * master filters use), and its capture filter ID is borrowed from 大师童话
 * ({@code Radiance.cube.rgb.bin}, the donor every module LUT uses). Stamping it on the request
 * puts {@code aps_algo_filter} in the chain, the stage loads meicam for the LUT, and the palette
 * is applied natively on the same buffer it always was - while the identity LUT itself changes
 * nothing (identity is channel-order-proof, so the container's axis order cannot break it).
 *
 * <p>Switch: {@code persist.omnicam.aura.hppalroute=0} disarms the route (back to the stock
 * palette-less 50 MP capture).
 */
final class HighPixelPaletteRoute {
    private static final String TAG = "HighPixelPalette";
    /** The identity LUT's file name; the camera loads filter LUTs by this name. */
    static final String IDENTITY = "jiege_palette_route.cube.rgb.bin";
    /** Capture ID donor (大师童话), the same donor every module LUT borrows. */
    private static final String DONOR = "Radiance.cube.rgb.bin";
    private static final String PROP_ROUTE = "persist.omnicam.aura.hppalroute";

    private static final int N = 32;
    private static final int HEADER = 176;
    private static final int SIZE = HEADER + N * N * N * 3;

    /**
     * The 176-byte .MS-LUT header every module LUT shares (tools/bake_master_luts.py, the same
     * bytes as assets/mslut_header.bin): 32^3 RGB u8, R fastest.
     */
    private static final String HEADER_HEX = ""
        + "2e4d532d4c5554200100000020000000"
        + "00000000010000000100000000000000"
        + "3000000000000000b000000000000000"
        + "000000000821043d0821843d8c31c63d"
        + "0821043e4a29253e8c31463ece39673e"
        + "0821843e29a5943e4a29a53e6badb53e"
        + "8c31c63eadb5d63ece39e73eefbdf73e"
        + "0821043f18630c3f29a5143f3ae71c3f"
        + "4a29253f5a6b2d3f6bad353f7cef3d3f"
        + "8c31463f9c734e3fadb5563fbef75e3f"
        + "ce39673fde7b6f3fefbd773f0000803f";

    private static final Set<String> seen =
        Collections.synchronizedSet(new java.util.HashSet<>());
    private static boolean staged;
    private static boolean mapped;

    private HighPixelPaletteRoute() {}

    /**
     * True when a palette-only shot should ride the filter stage: the dial is off-centre and both
     * halves of the route are in place (identity LUT staged, capture ID mapped). Safe to call on
     * any capture path; everything is idempotent and retried on the next call after a failure.
     */
    static boolean arm() {
        if ("0".equals(prop(PROP_ROUTE))) return false;
        if (!HighPixelFilters.paletteActive()) return false;
        return stage() && map();
    }

    /** Writes the identity LUT into the camera's data (files/jiege/lut) once. */
    private static boolean stage() {
        if (staged) return true;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return false;
        File dir = new File(app.getFilesDir(), "jiege/lut");
        File file = new File(dir, IDENTITY);
        if (file.isFile() && file.length() == SIZE) {
            staged = true;
            return true;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File temp = new File(dir, IDENTITY + ".part");
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(identityLut());
            }
            if (!temp.renameTo(file)) {
                temp.delete();
                return false;
            }
            staged = true;
            Log.i(TAG, "identity LUT staged: " + file + " (" + SIZE + " bytes)");
        } catch (Throwable error) {
            Log.e(TAG, "identity LUT staging failed", error);
            return false;
        }
        return true;
    }

    /** Lends the identity LUT the donor's capture ID in FilterHelper's map. */
    private static boolean map() {
        if (mapped) return true;
        ClassLoader loader = HighPixelFilters.appLoader();
        if (loader == null) return false;
        try {
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", loader);
            @SuppressWarnings("unchecked")
            Map<String, Integer> ids = (Map<String, Integer>)
                XposedHelpers.getStaticObjectField(helper, "sFilterPathConvertMap");
            Integer donor = ids == null ? null : ids.get(DONOR);
            if (donor == null) {
                if (seen.add("noDonor")) Log.i(TAG, DONOR + " has no capture ID yet, route idle");
                return false;
            }
            ids.put(IDENTITY, donor);
            mapped = true;
            Log.i(TAG, "palette route armed: " + IDENTITY + " -> capture ID " + donor);
        } catch (Throwable error) {
            Log.e(TAG, "capture ID mapping failed", error);
            return false;
        }
        return true;
    }

    /** The whole .MS-LUT file: the shared header plus an identity 32^3 RGB u8 payload. */
    static byte[] identityLut() {
        byte[] out = new byte[SIZE];
        for (int i = 0; i < HEADER; i++) {
            out[i] = (byte) Integer.parseInt(HEADER_HEX.substring(i * 2, i * 2 + 2), 16);
        }
        int at = HEADER;
        for (int b = 0; b < N; b++) {
            for (int g = 0; g < N; g++) {
                for (int r = 0; r < N; r++) {
                    // Identity in the encoded domain: every lattice point maps to its own
                    // coordinate, so the interpolation between lattice points is the identity as
                    // well - whatever colour space the runtime samples the cube in.
                    out[at++] = (byte) Math.round(r * 255.0 / (N - 1));
                    out[at++] = (byte) Math.round(g * 255.0 / (N - 1));
                    out[at++] = (byte) Math.round(b * 255.0 / (N - 1));
                }
            }
        }
        return out;
    }

    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, name, ""));
        } catch (Throwable error) {
            return "";
        }
    }
}
