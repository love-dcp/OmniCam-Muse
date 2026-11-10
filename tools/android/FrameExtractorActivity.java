import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 通过一个临时未界面 Activity 调用系统媒体解码器；用于本地验证成片色彩和方向。
 */
public final class FrameExtractorActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String input = getIntent().getStringExtra("input");
        String output = getIntent().getStringExtra("output");
        double seconds = Double.parseDouble(
                getIntent().getStringExtra("seconds") == null
                        ? "1.5"
                        : getIntent().getStringExtra("seconds"));

        new Thread(() -> {
            try {
                extract(input, output, seconds);
            } catch (Throwable error) {
                android.util.Log.e("CodexFrameExtractor", "extract failed", error);
            } finally {
                runOnUiThread(this::finish);
            }
        }, "codex-frame-extractor").start();
    }

    private static void extract(String input, String output, double seconds)
            throws Exception {
        File target = new File(output);
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input);
            long timeUs = Math.round(seconds * 1_000_000.0);
            Bitmap bitmap = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (bitmap == null) {
                throw new IllegalStateException("decoder returned no frame");
            }
            try (FileOutputStream stream = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                    throw new IllegalStateException("JPEG compression failed");
                }
            } finally {
                bitmap.recycle();
            }
            android.util.Log.i(
                    "CodexFrameExtractor",
                    "rotation="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            + " width="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            + " height="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            + " path="
                            + target);
        } finally {
            retriever.release();
        }
    }
}
