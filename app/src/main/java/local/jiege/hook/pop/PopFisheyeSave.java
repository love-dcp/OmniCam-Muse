package local.jiege.hook.pop;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import local.jiege.hook.common.Log;

/**
 * POP fish-eye for the saved frame.
 *
 * <p>Everything the camera itself can be told to do (capture metadata, request-tag booleans, the
 * Meishe renderer switch, the IPU filter parameter, the APS node) only reaches the preview: the still
 * is produced by the APS pipeline, whose fish-eye node needs parameters that only the camera's own
 * FISH_EYE cap mode supplies. So the saved picture is warped here instead: the frame's content area
 * is mapped into a circle (barrel distortion, black outside) while the Polaroid border stays as the
 * camera drew it.
 *
 * <p>The trigger is the lens state PopFisheyeSupport already maintains: when a POP capture is
 * delivered while that file says "1", the next JPEG that finishes writing in the camera folder is
 * rewritten. The warp is a radial lookup, r_source = R * (r_out / R) ^ 0.6, which matches the preview's
 * look: the centre keeps its scale and the edge content is squeezed into the circle.
 */
final class PopFisheyeSave {
    private static final String TAG = "PopFishSave";
    private static final String PROCESSOR = "com.oplus.ocs.camera.consumer.ApsProcessor";
    private static final String STATE = "/data/user/0/com.oplus.camera/files/fisheye_lens";

    /** Strength of the barrel: source radius is the output radius raised to this power. */
    private static final double BARREL = 0.6;
    /** A pixel this bright on the outer border counts as the polaroid paper. */
    private static final int PAPER = 235;

    private static volatile long pendingUntil;
    private static volatile boolean armed;

    private PopFisheyeSave() {}

    static void install(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass(PROCESSOR, classLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (lensOn()) pendingUntil = System.currentTimeMillis() + 8000;
                }
            };
            XposedBridge.hookAllMethods(processor, "onJpegReceived", hook);
            XposedBridge.hookAllMethods(processor, "onHeicReceived", hook);
            watchFolder();
            Log.i(TAG, "fish-eye save hook installed");
        } catch (Throwable error) {
            Log.i(TAG, "save hook unavailable: " + error);
        }
    }

    /** Watches the camera folder so the file is rewritten once it is complete. */
    private static void watchFolder() {
        final File dir = new File("/sdcard/DCIM/Camera");
        if (!dir.isDirectory()) {
            Log.i(TAG, "no camera folder at " + dir);
            return;
        }
        armed = true;
        new android.os.FileObserver(dir.getAbsolutePath(), android.os.FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (path == null || !armed) return;
                if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) return;
                if (System.currentTimeMillis() > pendingUntil) return;
                pendingUntil = 0;
                final File file = new File(dir, path);
                new Thread(() -> apply(file), "pop-fisheye-save").start();
            }
        }.startWatching();
        Log.i(TAG, "watching " + dir);
    }

    private static boolean lensOn() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(STATE);
            int c = in.read();
            in.close();
            return c == '1';
        } catch (Throwable error) {
            return false;
        }
    }

    /** Rewrites one file: detect the content area, warp it into a circle, keep the border. */
    private static void apply(File file) {
        try {
            Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (source == null) {
                Log.i(TAG, "decode failed " + file.getName());
                return;
            }
            int[] rect = contentRect(source);
            Bitmap out = warp(source, rect);
            source.recycle();
            if (out == null) return;
            FileOutputStream stream = new FileOutputStream(file);
            out.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            stream.close();
            out.recycle();
            scan(file);
            Log.i(TAG, "fish-eye applied to " + file.getName() + " rect=" + java.util.Arrays.toString(rect));
        } catch (Throwable error) {
            Log.i(TAG, "apply failed: " + error);
        }
    }

    /** The polaroid border is near-white; the content area is everything inside it. */
    private static int[] contentRect(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        int left = 0, top = 0, right = w - 1, bottom = h - 1;
        while (left < w / 4 && rowIsPaper(pixels, w, h, left, true)) left++;
        while (right > w * 3 / 4 && rowIsPaper(pixels, w, h, right, true)) right--;
        while (top < h / 4 && rowIsPaper(pixels, w, h, top, false)) top++;
        while (bottom > h * 3 / 4 && rowIsPaper(pixels, w, h, bottom, false)) bottom--;
        return new int[] {left, top, right, bottom};
    }

    private static boolean rowIsPaper(int[] pixels, int w, int h, int index, boolean column) {
        int step = column ? 1 : 1;
        int count = 0, paper = 0;
        if (column) {
            for (int y = 0; y < h; y += 8) {
                int p = pixels[y * w + index];
                if (brightness(p) >= PAPER) paper++;
                count++;
            }
        } else {
            for (int x = 0; x < w; x += 8) {
                int p = pixels[index * w + x];
                if (brightness(p) >= PAPER) paper++;
                count++;
            }
        }
        return count > 0 && paper * 10 >= count * 9;
    }

    private static int brightness(int colour) {
        int r = (colour >> 16) & 0xFF;
        int g = (colour >> 8) & 0xFF;
        int b = colour & 0xFF;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    /** Maps the rect's pixels into a circle inscribed in its shorter side; black outside. */
    private static Bitmap warp(Bitmap source, int[] rect) {
        int left = rect[0], top = rect[1], right = rect[2], bottom = rect[3];
        int w = source.getWidth();
        int h = source.getHeight();
        int rw = right - left + 1;
        int rh = bottom - top + 1;
        if (rw < 64 || rh < 64) return null;
        int circle = Math.min(rw, rh);
        int[] src = new int[w * h];
        source.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        System.arraycopy(src, 0, dst, 0, src.length);

        float cx = left + rw / 2f;
        float cy = top + rh / 2f;
        float radius = circle / 2f;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                float dx = (x - cx) / radius;
                float dy = (y - cy) / radius;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                if (r > 1f) {
                    dst[y * w + x] = 0xFF000000;
                    continue;
                }
                float scale = r > 1e-4f ? (float) Math.pow(r, BARREL) / r : 0f;
                int sx = Math.round(cx + dx * scale * radius);
                int sy = Math.round(cy + dy * scale * radius);
                sx = Math.max(left, Math.min(right, sx));
                sy = Math.max(top, Math.min(bottom, sy));
                dst[y * w + x] = src[sy * w + sx];
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static void scan(File file) {
        try {
            android.media.MediaScannerConnection.scanFile(
                appContext(), new String[] {file.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {
            // the gallery rescans on its own
        }
    }

    private static android.content.Context appContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) return (android.content.Context) app;
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }
}
