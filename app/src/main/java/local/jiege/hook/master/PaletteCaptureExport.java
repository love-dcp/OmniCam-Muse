package local.jiege.hook.master;

import java.io.File;
import java.io.FileOutputStream;
import local.jiege.hook.common.Log;

/**
 * Hands the palette's combined colour table to the native capture hook.
 *
 * <p>哈苏超清's 50 MP shot is produced by upscaling a 4096x3072 render, and that render passes
 * through the Meicam capture entry (`MsRenderHDRHostBuffer`) - the same one the soft light uses.
 * The APS pipeline of this mode has no palette stage and stamping its capture keys drops the
 * upscale (3244x5132 instead of 6484x10256), so the dial is applied natively instead: this class
 * writes the table and the on/off state where {@code palette_capture.cpp} reads them.
 *
 * <p>Two files under {@code files/jiege/}:
 * <ul>
 *   <li>{@code palette_state} - {@code 0} or {@code 1}, one byte of text;</li>
 *   <li>{@code palette_lut.bin} - 64&sup3; RGB triples, float32 little endian, the same table the
 *       preview is drawn with.</li>
 * </ul>
 */
final class PaletteCaptureExport {
    private static final String TAG = "PaletteCapture";
    private static final String DIR = "jiege";
    private static long lastWrite;
    private static boolean lastEnabled;
    private static long lastHash;

    private PaletteCaptureExport() {}

    /** Writes the current state, throttled: the dial flushes several times per gesture. */
    static void publish(File filesDir, boolean enabled, float[] lut) {
        if (filesDir == null) return;
        long now = System.currentTimeMillis();
        if (now - lastWrite < 200) return;
        lastWrite = now;
        File dir = new File(filesDir, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try {
            if (!enabled) {
                if (lastEnabled || lastHash != 0) {
                    write(new File(dir, "palette_state"), "0".getBytes("UTF-8"));
                    lastEnabled = false;
                    lastHash = 0;
                    Log.i(TAG, "palette off for the capture");
                }
                return;
            }
            if (lut == null || lut.length < 64 * 64 * 64 * 3) return;
            long hash = hash(lut);
            if (hash == lastHash && lastEnabled) return;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(lut.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (float value : lut) buffer.putFloat(value);
            write(new File(dir, "palette_lut.bin"), buffer.array());
            write(new File(dir, "palette_state"), "1".getBytes("UTF-8"));
            lastHash = hash;
            lastEnabled = true;
            Log.i(TAG, "palette handed to the capture (" + lut.length + " floats)");
        } catch (Throwable error) {
            Log.e(TAG, "publish failed", error);
        }
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes);
        }
    }

    private static long hash(float[] values) {
        long hash = 1469598103934665603L;
        for (int i = 0; i < values.length; i += 97) {
            hash = (hash ^ Float.floatToIntBits(values[i])) * 1099511628211L;
        }
        return hash;
    }
}
