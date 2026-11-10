import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.FileOutputStream;

/**
 * 在 Android 自带的媒体解码器上抽取一帧，避免把原尺寸视频送入对话。
 *
 * 参数：输入视频、输出 JPEG、抽帧时间（秒）。
 */
public final class FrameExtractor {
    private FrameExtractor() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: FrameExtractor INPUT OUTPUT SECONDS");
            System.exit(2);
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(args[0]);
            long timeUs = Math.round(Double.parseDouble(args[2]) * 1_000_000.0);
            Bitmap bitmap = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (bitmap == null) {
                throw new IllegalStateException("decoder returned no frame");
            }

            try (FileOutputStream output = new FileOutputStream(args[1])) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw new IllegalStateException("JPEG compression failed");
                }
            } finally {
                bitmap.recycle();
            }

            System.out.println(
                    "rotation="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            + " width="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            + " height="
                            + retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        } finally {
            retriever.release();
        }
    }
}
