package local.jiege.settings;

/**
 * 由一张照片估算大师模式的调色参数。
 *
 * <p>【为什么要它】OMaster 那套预设是靠人一条条调的；用户手里常常只有一张喜欢的照片，
 * 没有配套的预设文件。这里按画面统计量反推一组「最接近这张照片观感」的参数：
 * 影调看亮度分布的离散程度、饱和度看 HSV 的 S、冷暖看 R 与 B 的失衡、
 * 青品看 (R+B)/2 与 G 的失衡、锐度看相邻像素梯度、暗角看四角与中心的亮度差、
 * 柔光看高光占比。
 *
 * <p>【边界】这是<b>估算</b>不是还原：照片本身已经过相机/修图软件的调色，
 * 反推只能做到「方向对、量级对」，细节要在设置页「改调色参数」里微调。
 * 滤镜不猜 —— 本机有哪些滤镜、每支的颜色倾向都不同，硬猜只会更离谱，
 * 所以生成的行里滤镜留空，写入时绑「标准」，用户想要滤镜自己在详情页填。
 *
 * <p>纯 Java、不依赖 Android（像素由调用方解码成 int[]），可离线测试。
 */
public final class PicturePresetExtract {

    private PicturePresetExtract() {
    }

    /** 估算结果。数值为 null / 空串表示这一项不指定，写入时保持相机当前值。 */
    public static final class Params {
        public Integer tone;
        public Integer saturation;
        public Integer warmCool;
        public Integer cyanMagenta;
        public Integer sharpness;
        /** 柔光：空表示不指定，否则 无 / 柔美 / 梦幻。 */
        public String softLight = "";
        /** 暗角：空表示不指定，否则 开 / 关。 */
        public String vignette = "";
        /** 这张图的平均亮度、饱和度、对比度，用在说明文字里。 */
        public int meanLuma;
        public int meanSaturation;

        /** 给用户看的一句参数摘要，例如「影调 +14 · 饱和 -8 · 冷暖 +22」。 */
        public String describe() {
            StringBuilder out = new StringBuilder();
            append(out, "影调", tone);
            append(out, "饱和", saturation);
            append(out, "冷暖", warmCool);
            append(out, "青品", cyanMagenta);
            append(out, "锐度", sharpness);
            if (!softLight.isEmpty()) appendText(out, "柔光", softLight);
            if (!vignette.isEmpty()) appendText(out, "暗角", vignette);
            return out.length() == 0 ? "没有明显偏向" : out.toString();
        }

        /**
         * 生成一行预设文本（本模块的行格式），交给 MasterPresetLibrary.importText。
         * [id] 要以字母开头且只含字母数字与 ._-，[name] 是库里显示的名字。
         */
        public String row(String id, String name) {
            String author = "照片估算";
            String filter = "";
            String vignetteText = vignette;
            String tips = "由照片画面估算：" + describe()
                + "。滤镜没有猜，写入相机后绑定的是「标准」；想要滤镜（或觉得哪项不对）"
                + "可以在这条预设的详情页点「改调色参数」。";
            StringBuilder line = new StringBuilder("p");
            line.append('\t').append(id);
            line.append('\t').append(safe(name));
            line.append('\t').append(safe(author));
            line.append('\t').append(filter);
            line.append('\t').append(safe(softLight));
            line.append('\t').append(value(tone));
            line.append('\t').append(value(saturation));
            line.append('\t').append(value(warmCool));
            line.append('\t').append(value(cyanMagenta));
            line.append('\t').append(value(sharpness));
            line.append('\t').append(vignetteText);
            line.append('\t').append(safe(tips));
            line.append('\t').append("照片");
            line.append('\t').append('0');
            line.append('\t').append("");
            line.append('\t').append("");
            return line.toString();
        }
    }

    /**
     * 从像素估算参数。[pixels] 是 ARGB_8888，长边建议缩到 300~500 ——
     * 统计量在这个尺寸上已经稳定，再大只是变慢。
     */
    public static Params fromPixels(int[] pixels, int width, int height) {
        Params out = new Params();
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height) return out;

        int count = 0;
        double sumL = 0;
        double sumL2 = 0;
        double sumS = 0;
        double sumR = 0;
        double sumG = 0;
        double sumB = 0;
        int bright = 0;
        for (int i = 0; i < width * height; i++) {
            int argb = pixels[i];
            if ((argb >>> 24) == 0) continue;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            double l = luma(r, g, b);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            sumS += max == 0 ? 0 : (max - min) / (double) max;
            sumL += l;
            sumL2 += l * l;
            sumR += r;
            sumG += g;
            sumB += b;
            if (l > 220) bright++;
            count++;
        }
        if (count == 0) return out;

        double mean = sumL / count;
        double variance = Math.max(0, sumL2 / count - mean * mean);
        double deviation = Math.sqrt(variance);
        double avgS = sumS / count;
        double avgR = sumR / count;
        double avgG = sumG / count;
        double avgB = sumB / count;
        double gray = Math.max(1, (avgR + avgG + avgB) / 3.0);
        out.meanLuma = (int) Math.round(mean);
        out.meanSaturation = (int) Math.round(avgS * 100);

        // 影调：亮度分布的离散程度。自然照片的标准差多在 45~65，
        // 取 55 当「不加不减」，偏离 10 大约对应 15 的影调值。
        out.tone = clamp((int) Math.round((deviation - 55) * 1.5), -65, 65);

        // 饱和度：HSV 的 S 平均值。自然照片多在 0.30 上下，偏离 0.1 约对应 22；
        // 系数别太猛 —— 相机里 -60 已经接近「几乎不上色」，很容易过度。
        out.saturation = clamp((int) Math.round((avgS - 0.30) * 220), -60, 60);

        // 冷暖：以灰世界为基准看 R 与 B 谁多。自然光下两者接近 0。
        out.warmCool = clamp((int) Math.round((avgR - avgB) / gray * 55), -70, 70);

        // 青品：(R+B)/2 高于 G 偏品红，低偏青。
        out.cyanMagenta = clamp((int) Math.round((((avgR + avgB) / 2.0 - avgG) / gray) * 55), -70, 70);

        // 锐度：相邻像素亮度差的平均值。缩略图上 12 左右算「正常清晰」。
        double grad = gradient(pixels, width, height);
        int sharp = (int) Math.round((grad - 12) * 2.0);
        // 缩略图本身会削弱锐度，只在明显偏软/偏硬时给值，免得每张图都被压锐度。
        out.sharpness = Math.abs(sharp) < 8 ? null : clamp(sharp, -45, 45);

        // 暗角：四角明显比中心暗才算。自然照片角落常有天空/地面差异，阈值放低一点。
        double ratio = vignetteRatio(pixels, width, height);
        if (ratio > 0 && ratio < 0.78) out.vignette = "开";
        else if (ratio > 1.18) out.vignette = "关";

        // 柔光：高光占比高、整体又不硬（低对比）更像加了柔光。
        double brightShare = bright / (double) count;
        if (brightShare > 0.14 && deviation < 34) out.softLight = "梦幻";
        else if (brightShare > 0.08 && deviation < 46) out.softLight = "柔美";
        return out;
    }

    private static double luma(int r, int g, int b) {
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    /** 隔行采样算相邻像素的亮度梯度均值，2 像素一跳，够快也够稳。 */
    private static double gradient(int[] pixels, int width, int height) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < height - 1; y += 2) {
            for (int x = 0; x < width - 1; x += 2) {
                int argb = pixels[y * width + x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                double base = luma(r, g, b);
                int right = pixels[y * width + x + 1];
                int below = pixels[(y + 1) * width + x];
                sum += Math.abs(luma((right >> 16) & 0xFF, (right >> 8) & 0xFF, right & 0xFF) - base);
                sum += Math.abs(luma((below >> 16) & 0xFF, (below >> 8) & 0xFF, below & 0xFF) - base);
                count += 2;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** 四角亮度均值 ÷ 中心亮度均值；中心为 0（纯黑）时返回 0 表示没法判断。 */
    private static double vignetteRatio(int[] pixels, int width, int height) {
        int thirdW = Math.max(1, width / 3);
        int thirdH = Math.max(1, height / 3);
        double center = blockMean(pixels, width, thirdW, thirdH, thirdW, thirdH);
        if (center < 1) return 0;
        int cornerW = Math.max(1, width / 6);
        int cornerH = Math.max(1, height / 6);
        double topLeft = blockMean(pixels, width, 0, 0, cornerW, cornerH);
        double topRight = blockMean(pixels, width, width - cornerW, 0, cornerW, cornerH);
        double bottomLeft = blockMean(pixels, width, 0, height - cornerH, cornerW, cornerH);
        double bottomRight = blockMean(pixels, width, width - cornerW, height - cornerH, cornerW, cornerH);
        double corners = (topLeft + topRight + bottomLeft + bottomRight) / 4.0;
        return corners / center;
    }

    private static double blockMean(int[] pixels, int width, int x0, int y0, int w, int h) {
        double sum = 0;
        int count = 0;
        for (int y = y0; y < y0 + h; y++) {
            int row = y * width;
            for (int x = x0; x < x0 + w; x++) {
                int argb = pixels[row + x];
                sum += luma((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private static void append(StringBuilder out, String label, Integer value) {
        if (value == null) return;
        if (out.length() > 0) out.append(" · ");
        out.append(label).append(value > 0 ? " +" : " ").append(value);
    }

    private static void appendText(StringBuilder out, String label, String value) {
        if (out.length() > 0) out.append(" · ");
        out.append(label).append(' ').append(value);
    }

    private static String value(Integer number) {
        return number == null ? "" : String.valueOf(number);
    }

    /** 行格式是制表符分隔，字段里的制表符/换行会把行拆坏。 */
    private static String safe(String text) {
        if (text == null) return "";
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
