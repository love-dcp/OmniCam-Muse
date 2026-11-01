package local.jiege.hook.filters;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * Video half of the master filters: the raw table the video renderer reads.
 *
 * <p>The two LUT directories hold the SAME lattices in two containers:
 * <ul>
 *   <li>{@code /odm/etc/camera/meishe_lut/<id>} — Meishe container: {@code ".MS-LUT "} + 176 bytes
 *       of header, then 32^3 RGB u8. This is what photo and master mode read (meicam).</li>
 *   <li>{@code /odm/etc/camera/filters_lut/<id>} — bare table: 32^3 RGBA u8, alpha byte 0. This is
 *       what the video filter path reads.</li>
 * </ul>
 * Measured on the X8U (2026-11-01): {@code filters_lut/Radiance.cube.rgb.bin} is byte for byte the
 * body of {@code meishe_lut/Radiance.cube.rgb.bin} expanded RGB -> RGBA with a zero alpha byte.
 * So a master LUT becomes video-ready by dropping the container header and inserting that byte;
 * no re-baking and no colour maths.
 *
 * <p>The converted copies go under the camera's private files (jiege/lut_raw), and the native path
 * remap (pop_path_remap.cpp) serves reads of {@code /odm/etc/camera/filters_lut/<id>} from there
 * when the name exists — the ODM directory is never touched, and stock video LUTs keep winning
 * because their names are not in this directory.
 *
 * <p>Only the fixed 32^3 layouts are converted (the 27 module LUTs: 旧版经典, 徕卡, 富士, 理光,
 * 胶片, imported). The Polaroid layers and the two X10 tables have other lattice sizes and are
 * skipped: they stay photo-only rather than showing a video entry that renders nothing.
 */
public final class VideoFilterLuts {
    private static final String TAG = "VideoFilterLuts";
    /** Where the camera reads a filter's table for photo/master mode. */
    private static final String SOURCE_DIR = "/odm/etc/camera/meishe_lut/";
    /** Converted tables, served in place of filters_lut reads by the native remap. */
    public static final String RAW_DIR = "jiege/lut_raw";
    private static final byte[] MAGIC = {'.', 'M', 'S', '-', 'L', 'U', 'T', ' '};
    private static final int HEADER = 176;
    private static final int TRIANGLES = 32 * 32 * 32;
    private static final int RGB = TRIANGLES * 3;
    private static final int RGBA = TRIANGLES * 4;

    private static volatile boolean prepared;

    private VideoFilterLuts() {}

    /** Converts the video scope's LUTs once per process, off the main thread. */
    public static void prepare(Context context) {
        if (prepared || context == null) return;
        prepared = true;
        final File source = new File(SOURCE_DIR);
        final File target = new File(context.getFilesDir(), RAW_DIR);
        final List<FilterEntry> entries = FilterCatalog.get(FilterScope.VIDEO).entries();
        new Thread(() -> convert(entries, source, target), "JiegeVideoLut").start();
    }

    private static void convert(List<FilterEntry> entries, File source, File target) {
        target.mkdirs();
        if (!source.isDirectory()) {
            Log.i(TAG, "no LUT source at " + source + ", video filters keep the camera's tables");
            return;
        }
        int made = 0;
        int kept = 0;
        int skipped = 0;
        for (FilterEntry entry : entries) {
            File out = new File(target, entry.id);
            File in = new File(source, entry.id);
            if (!in.isFile()) {
                skipped++;
                continue;
            }
            if (out.isFile() && out.length() == RGBA) {
                kept++;
                continue;
            }
            if (convertOne(in, out)) made++;
            else skipped++;
        }
        FilterCatalog video = FilterCatalog.get(FilterScope.VIDEO);
        File[] files = target.listFiles();
        for (File file : files == null ? new File[0] : files) {
            String name = file.getName();
            if (name.endsWith(".part") || video.find(name) == null) file.delete();
        }
        Log.i(TAG, "video tables: " + made + " converted, " + kept + " already there, "
            + skipped + " skipped, " + entries.size() + " entries");
    }

    private static boolean convertOne(File in, File out) {
        byte[] all;
        try (FileInputStream stream = new FileInputStream(in)) {
            long size = in.length();
            if (size != HEADER + RGB) return false;
            all = new byte[(int) size];
            int read = 0;
            while (read < all.length) {
                int n = stream.read(all, read, all.length - read);
                if (n <= 0) return false;
                read += n;
            }
        } catch (Throwable error) {
            Log.e(TAG, "read failed " + in.getName(), error);
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) if (all[i] != MAGIC[i]) return false;
        byte[] table = new byte[RGBA];
        for (int src = HEADER, dst = 0; src < all.length; src += 3, dst += 4) {
            table[dst] = all[src];
            table[dst + 1] = all[src + 1];
            table[dst + 2] = all[src + 2];
            table[dst + 3] = 0;
        }
        File temp = new File(out.getParentFile(), out.getName() + ".part");
        try (OutputStream stream = new FileOutputStream(temp)) {
            stream.write(table);
        } catch (Throwable error) {
            // Keep a previous good copy; a half-written table would show as a colour cast.
            temp.delete();
            Log.e(TAG, "write failed " + out.getName(), error);
            return false;
        }
        if (!temp.renameTo(out)) {
            temp.delete();
            return false;
        }
        return true;
    }
}
