import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import local.jiege.settings.MasterPresetLibrary;
import local.jiege.settings.PicturePresetExtract;

/**
 * 照片估算预设的离线测试：合成几种典型画面，检查反推出来的参数落在合理区间，
 * 以及生成的那一行能被 MasterPresetLibrary 正常解析成一条预设。
 *
 * <p>断言用区间不用精确值 —— 这套系数是按常见照片校准的经验值，改系数时只要大方向
 * 还对（暖图给暖、暗角给开、平淡的图给负影调）就该过。
 */
public final class PicturePresetOfflineTest {
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        natural();
        warmPicture();
        flatPicture();
        vividPicture();
        vignettePicture();
        rowParses();
        if (failed > 0) {
            System.out.println("FAILED: " + failed);
            System.exit(1);
        }
        System.out.println("PicturePresetOfflineTest OK");
    }

    /** 常见自然照片：亮度散布较宽、色度很低 —— 各项都该接近 0。 */
    private static void natural() {
        int w = 200;
        int h = 150;
        Random random = new Random(7);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int v = 40 + random.nextInt(150);
            // R/B 各自带一点独立的色噪声：平均色偏仍是 0，但饱和度落在自然照片的区间。
            pixels[i] = argb(v + random.nextInt(91) - 45, v, v + random.nextInt(91) - 45);
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        check(p.tone != null && Math.abs(p.tone) <= 40, "自然照片影调应在 ±40 内，实际 " + p.tone);
        check(p.saturation != null && Math.abs(p.saturation) <= 40, "自然照片饱和度应在 ±40 内，实际 " + p.saturation);
        check(p.warmCool != null && Math.abs(p.warmCool) <= 25, "轻微偏暖不该给出大值，实际 " + p.warmCool);
        check(p.vignette.isEmpty() || "关".equals(p.vignette), "自然照片不该判定为加了暗角：" + p.vignette);
    }

    /** 暖调照片（日落那种）：冷暖轴该是正的、而且不小。 */
    private static void warmPicture() {
        int w = 160;
        int h = 120;
        Random random = new Random(11);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int base = 90 + random.nextInt(110);
            pixels[i] = argb(clamp(base + 60), clamp(base + 10), clamp(base - 40));
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        check(p.warmCool != null && p.warmCool >= 20, "暖图冷暖应为正且 >= 20，实际 " + p.warmCool);
        check(p.cyanMagenta != null && p.cyanMagenta >= 0, "暖图不应判成偏青，实际 " + p.cyanMagenta);
    }

    /** 灰蒙蒙的平淡画面：影调该是负的（相机里就是「降对比」）。 */
    private static void flatPicture() {
        int w = 160;
        int h = 120;
        Random random = new Random(13);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int v = 110 + random.nextInt(22);
            pixels[i] = argb(v, v, v);
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        check(p.tone != null && p.tone < -10, "平淡画面影调应为负，实际 " + p.tone);

        // 同尺寸的全黑/全白图不该崩，也不该给出 NaN 之类的东西。
        PicturePresetExtract.Params black = PicturePresetExtract.fromPixels(solid(w, h, 0, 0, 0), w, h);
        check(black.describe() != null && !black.describe().contains("NaN"), "全黑图说明文字异常：" + black.describe());
    }

    /** 很艳的画面：饱和度该明显为正。 */
    private static void vividPicture() {
        int w = 140;
        int h = 100;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            boolean red = (i % 2) == 0;
            pixels[i] = red ? argb(230, 20, 30) : argb(20, 60, 220);
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        check(p.saturation != null && p.saturation >= 25, "高饱和画面饱和度应 >= 25，实际 " + p.saturation);
    }

    /** 四角压暗：该判成「开」。 */
    private static void vignettePicture() {
        int w = 180;
        int h = 180;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x - w / 2;
                int dy = y - h / 2;
                double distance = Math.sqrt(dx * dx + dy * dy) / (w / 2.0);
                int v = clamp((int) Math.round(210 - distance * 110));
                pixels[y * w + x] = argb(v, v, v);
            }
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        check("开".equals(p.vignette), "四角压暗应判成暗角开，实际 " + p.vignette);
    }

    /** 生成的那一行要能被预设库解析成一条完整预设（滤镜留空、名字与数值都在）。 */
    private static void rowParses() throws Exception {
        int w = 120;
        int h = 90;
        Random random = new Random(19);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int base = 60 + random.nextInt(150);
            pixels[i] = argb(clamp(base + 30), base, clamp(base - 20));
        }
        PicturePresetExtract.Params p = PicturePresetExtract.fromPixels(pixels, w, h);
        String text = "v2\n" + p.row("picture_abc123", "海边那张");

        java.io.File dir = Files.createTempDirectory("muse-picture-preset").toFile();
        MasterPresetLibrary library = new MasterPresetLibrary(dir);
        int added = library.importText(text);
        check(added == 1, "应导入 1 条，实际 " + added);

        List<MasterPresetLibrary.Preset> presets = library.presets();
        check(presets.size() == 1, "库里应有 1 条，实际 " + presets.size());
        MasterPresetLibrary.Preset preset = presets.get(0);
        check("海边那张".equals(preset.name), "名字不对：" + preset.name);
        check(preset.filter.isEmpty(), "照片估算不该带滤镜，实际 " + preset.filter);
        check(preset.hasPro() == false, "照片估算不该带 Pro 参数");
        check("照片".equals(preset.tags), "标签不对：" + preset.tags);
        // 再写一次同 id：应覆盖而不是新增。
        check(library.importText(text) == 1 && library.presets().size() == 1, "同 id 重复导入应覆盖");
    }

    private static int[] solid(int width, int height, int r, int g, int b) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb(r, g, b));
        return pixels;
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void check(boolean condition, String message) {
        if (condition) return;
        failed++;
        System.out.println("FAIL: " + message);
    }
}
