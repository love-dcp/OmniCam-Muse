package local.jiege.hook.aicomposition;

import android.hardware.HardwareBuffer;

/**
 * Bridge to the native buffer reader. The camera's composition frames are RGB_888 with
 * CPU_READ_OFTEN but no USAGE_GPU_SAMPLED_IMAGE, so neither Bitmap.wrapHardwareBuffer nor a GL
 * texture can reach them; see hardware_buffer_read.cpp.
 */
final class NativeFrames {
    private static volatile boolean linked;
    private static volatile int lastError = -100;

    private NativeFrames() {}

    /**
     * Fills {@code out} with ARGB pixels sampled from the buffer, nearest neighbour.
     *
     * @return 0 on success, a negative code otherwise.
     */
    static int read(HardwareBuffer buffer, int[] out, int width, int height) {
        try {
            int result = readPixels(buffer, out, width, height);
            linked = true;
            return result;
        } catch (UnsatisfiedLinkError error) {
            if (!linked) local.jiege.hook.common.Log.i("CompFollow",
                "native reader unavailable: " + error);
            lastError = -101;
            return lastError;
        } catch (Throwable error) {
            lastError = -102;
            return lastError;
        }
    }

    private static native int readPixels(HardwareBuffer buffer, int[] out, int width, int height);
}
