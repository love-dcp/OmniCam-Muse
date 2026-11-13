package local.jiege.settings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 大师预设库：把 OMaster 的调色预设落成相机大师模式可用的参数组。
 *
 * <p>【来源与致谢】预设内容来自 OMaster —— 大师模式调色参数库
 * （https://github.com/iCurrer/OMaster），原作者 Silas（@iCurrer）与 Luminary（@fengyec2），
 * 以及各条预设的署名作者。原项目以 CC BY-NC-SA 4.0 发布，本模块为免费非商业项目，
 * 按该许可保留署名并以相同方式共享本数据文件；参数数值与拍摄建议的著作权归原作者所有。
 *
 * <p>存储：一个纯文本文件（{@code master_presets.txt}），与 MasterFilterStore 放在同一个
 * LSPosed 偏好目录，相机进程可直接读，不需要先启动设置 App。
 *
 * <pre>
 * v2
 * p &lt;id&gt; &lt;名称&gt; &lt;作者&gt; &lt;滤镜&gt; &lt;柔光&gt; &lt;影调&gt; &lt;饱和度&gt; &lt;冷暖&gt; &lt;青品&gt; &lt;锐度&gt; &lt;暗角&gt; &lt;建议&gt; &lt;标签&gt; &lt;收藏&gt; &lt;封面&gt; &lt;画廊&gt; [&lt;ISO&gt; &lt;快门&gt; &lt;EV&gt; &lt;AF&gt; &lt;WB&gt;]
 * </pre>
 *
 * 制表符分隔，空字段表示「不设置」（应用到相机时保持原值）。建议里的换行写成 {@code \n}。
 * 末尾五列是大师 Pro 参数，缺列/空值都表示「这条预设不管这几项」；值是相机自己的字符串，
 * 模块不解释它（可以用设置页的「读取相机 Pro 参数」直接从相机抄一份）。
 * 纯 Java、不依赖 Android，可离线测试（tools/offline_tests）。
 */
public final class MasterPresetLibrary {
    public static final String FILE = "master_presets.txt";
    /** 内置数据的格式版本，改格式时递增，旧文件会被重新播种。 */
    private static final String VERSION = "v2";
    private static final String HEADER = "# OmniCam Muse · 大师预设库";

    private final File file;
    private final List<Preset> presets = new ArrayList<>();

    public MasterPresetLibrary(File dir) {
        this.file = new File(dir, FILE);
        load();
    }

    /** 一条预设：8 个大师参数 + 拍摄建议。数值为 null 表示该项没有指定。 */
    public static final class Preset {
        public String id = "";
        public String name = "";
        public String author = "";
        /** 原厂滤镜名 + 强度，例如「复古 75%」；空表示不指定。 */
        public String filter = "";
        /** 柔光：无 / 柔美 / 梦幻。 */
        public String softLight = "";
        public Integer tone;
        public Integer saturation;
        public Integer warmCool;
        public Integer cyanMagenta;
        public Integer sharpness;
        /** 暗角：开 / 关。 */
        public String vignette = "";
        /**
         * 大师 Pro 参数（ISO、快门、曝光补偿、对焦模式、白平衡），存<b>相机自己的字符串</b>
         * （例如 ISO 存 "800"、快门存 "1/125"、白平衡存 "5200"）——格式由相机决定，这里原样搬运，
         * 不猜单位。空表示这条预设不管这一项（保持相机当前值）。
         */
        public String iso = "";
        public String shutter = "";
        public String exposureCompensation = "";
        public String focus = "";
        public String whiteBalance = "";
        /** 拍摄建议：环境建议 / 场景推荐 / 拍摄要点。 */
        public String tips = "";
        public String tags = "";
        public boolean favorite;
        /** 封面图在 assets 里的路径，例如 master_presets/omaster02_a.webp；空表示没有封面。 */
        public String cover = "";
        /** 详情页可左右翻的图，逗号分隔的 assets 路径。 */
        public String gallery = "";

        /** 详情页的图：封面在前，后面接画廊。 */
        public List<String> images() {
            List<String> out = new ArrayList<>();
            if (!cover.isEmpty()) out.add(cover);
            for (String path : gallery.split(",")) {
                String item = path.trim();
                if (!item.isEmpty() && !out.contains(item)) out.add(item);
            }
            return out;
        }

        /** 滤镜名去掉强度，例如「复古 75%」→「复古」。 */
        public String filterName() {
            String value = filter.trim();
            int space = value.lastIndexOf(' ');
            if (space > 0 && value.endsWith("%")) return value.substring(0, space).trim();
            return value;
        }

        /** 滤镜强度 0-100，缺省 100（「标准 0%」是 0）。 */
        public int filterStrength() {
            String value = filter.trim();
            if (!value.endsWith("%")) return 100;
            int space = value.lastIndexOf(' ');
            if (space < 0) return 100;
            try {
                return clamp(Integer.parseInt(value.substring(space + 1, value.length() - 1).trim()), 0, 100);
            } catch (NumberFormatException error) {
                return 100;
            }
        }

        public boolean vignetteOn() {
            return "开".equals(vignette.trim());
        }

        /** 有没有 Pro 参数（ISO / 快门 / EV / 对焦 / 白平衡）。 */
        public boolean hasPro() {
            return !iso.isEmpty() || !shutter.isEmpty() || !exposureCompensation.isEmpty()
                || !focus.isEmpty() || !whiteBalance.isEmpty();
        }

        /** Pro 参数摘要，例如「ISO 800 · 1/125 · EV -0.3 · AF AF-C · WB 5200」。 */
        public String proSummary() {
            StringBuilder out = new StringBuilder();
            append(out, "ISO", iso.isEmpty() ? null : iso);
            append(out, "快门", shutter.isEmpty() ? null : shutter);
            append(out, "EV", exposureCompensation.isEmpty() ? null : exposureCompensation);
            append(out, "AF", focus.isEmpty() ? null : focus);
            append(out, "WB", whiteBalance.isEmpty() ? null : whiteBalance);
            return out.toString();
        }

        /** 一行参数摘要，用于列表：影调 -35 · 饱和 +19 · 冷暖 -5 … */
        public String summary() {
            StringBuilder out = new StringBuilder();
            append(out, "影调", tone);
            append(out, "饱和", saturation);
            append(out, "冷暖", warmCool);
            append(out, "青品", cyanMagenta);
            append(out, "锐度", sharpness);
            if (!filter.isEmpty()) append(out, "滤镜", filter);
            if (!softLight.isEmpty()) append(out, "柔光", softLight);
            if (!vignette.isEmpty()) append(out, "暗角", vignette);
            return out.toString();
        }

        private static void append(StringBuilder out, String label, Object value) {
            if (value == null) return;
            if (out.length() > 0) out.append(" · ");
            if (value instanceof Integer) {
                int number = (Integer) value;
                out.append(label).append(' ').append(number > 0 ? "+" + number : Integer.toString(number));
            } else {
                out.append(label).append(' ').append(value);
            }
        }

        /** 建议按【环境建议】【场景推荐】【拍摄要点】拆成多行。 */
        public String[] tipLines() {
            return tips.replace("\\n", "\n").split("\n");
        }
    }

    /** 全部预设：收藏在前，其余按名称。 */
    public List<Preset> presets() {
        List<Preset> out = new ArrayList<>(presets);
        out.sort(Comparator.comparing((Preset p) -> !p.favorite).thenComparing(p -> p.name));
        return out;
    }

    public Preset find(String id) {
        for (Preset preset : presets) if (preset.id.equals(id)) return preset;
        return null;
    }

    public int size() {
        return presets.size();
    }

    /**
     * 首次启动时把内置数据写进来。已有数据时不再动，避免覆盖用户的收藏与导入。
     */
    public void seedIfNeeded(String builtinText) {
        if (!presets.isEmpty()) return;
        int added = importText(builtinText);
        if (added > 0) save();
    }

    /**
     * 内置数据随模块升级增补：内置条目（id 以 omaster 开头）按新数据整体刷新——补上新条目、
     * 补上新封面与修正后的参数；用户的收藏标记保留。用户自己导入的条目一律不动。
     * 返回新增的条数。
     */
    public int syncBuiltin(String builtinText) {
        int added = 0;
        int updated = 0;
        for (String raw : builtinText.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || VERSION.equals(line)) continue;
            String[] parts = raw.split("\t", -1);
            if (parts.length < 14) continue;
            String id = parts[1].trim();
            if (id.isEmpty() || !id.startsWith("omaster")) continue;
            Preset preset = parse(parts);
            if (preset == null) continue;
            Preset existing = find(id);
            if (existing == null) {
                presets.add(preset);
                added++;
                continue;
            }
            preset.favorite = existing.favorite;
            // Pro 参数是用户在相机里抄回来的，内置数据里没有这一项 —— 刷新内置数据时保留。
            preset.iso = existing.iso;
            preset.shutter = existing.shutter;
            preset.exposureCompensation = existing.exposureCompensation;
            preset.focus = existing.focus;
            preset.whiteBalance = existing.whiteBalance;
            if (!row(existing).equals(row(preset))) updated++;
            presets.set(presets.indexOf(existing), preset);
        }
        if (added > 0 || updated > 0) save();
        return added;
    }

    /**
     * 首次启动或格式版本升级：清掉内置条目后按新数据重建，收藏状态按 id 还原。
     * 用户导入的条目（id 不是 omaster 开头）保留。
     */
    public void reseed(String builtinText) {
        List<String> favorites = new ArrayList<>();
        // 用户在相机里抄回来的 Pro 参数不在内置数据里，重建时按 id 还原。
        java.util.Map<String, String[]> pro = new java.util.HashMap<>();
        for (Preset preset : presets) {
            if (preset.favorite) favorites.add(preset.id);
            if (preset.hasPro()) {
                pro.put(preset.id, new String[]{preset.iso, preset.shutter, preset.exposureCompensation,
                    preset.focus, preset.whiteBalance});
            }
        }
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.get(i).id.startsWith("omaster")) presets.remove(i);
        }
        importText(builtinText);
        for (Preset preset : presets) {
            preset.favorite = favorites.contains(preset.id);
            String[] kept = pro.get(preset.id);
            if (kept == null) continue;
            preset.iso = kept[0];
            preset.shutter = kept[1];
            preset.exposureCompensation = kept[2];
            preset.focus = kept[3];
            preset.whiteBalance = kept[4];
        }
        save();
    }

    /** 收藏状态是用户的，和预设内容分开保存，重播内置数据不会丢。 */
    public void setFavorite(String id, boolean on) {
        Preset preset = find(id);
        if (preset == null || preset.favorite == on) return;
        preset.favorite = on;
        save();
    }

    /**
     * 把一组 Pro 参数写进某条预设（设置页「读取相机 Pro 参数」抄回来时用）。
     * 值是相机自己的字符串，这里只存不解释。
     */
    public void setPro(String id, String iso, String shutter, String ev, String focus, String whiteBalance) {
        Preset preset = find(id);
        if (preset == null) return;
        preset.iso = clean(iso);
        preset.shutter = clean(shutter);
        preset.exposureCompensation = clean(ev);
        preset.focus = clean(focus);
        preset.whiteBalance = clean(whiteBalance);
        save();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').trim();
    }

    /**
     * 解析并导入一段文本：本模块的行格式，或 OMaster 的 presets.json（由调用方先转成行格式）。
     * 同 id 覆盖，新 id 追加。返回实际导入的条数。
     */
    public int importText(String text) {
        int added = 0;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || VERSION.equals(line)) continue;
            String[] parts = raw.split("\t", -1);
            if (parts.length < 14) continue;
            Preset preset = parse(parts);
            if (preset == null) continue;
            merge(preset);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    private void merge(Preset incoming) {
        for (int i = 0; i < presets.size(); i++) {
            if (!presets.get(i).id.equals(incoming.id)) continue;
            // 重播内置数据时保留用户的收藏标记。
            if (incoming.author.isEmpty() && !presets.get(i).author.isEmpty()) incoming.author = presets.get(i).author;
            // 导入的 JSON 没有封面时不把已有的封面冲掉。
            if (incoming.cover.isEmpty()) incoming.cover = presets.get(i).cover;
            if (incoming.gallery.isEmpty()) incoming.gallery = presets.get(i).gallery;
            incoming.favorite |= presets.get(i).favorite;
            presets.set(i, incoming);
            return;
        }
        presets.add(incoming);
    }

    /** 老版本写下的数据缺少封面（或格式落后），需要按新的内置数据重建。 */
    public boolean needsReseed() {
        if (presets.isEmpty()) return true;
        for (Preset preset : presets) {
            if (preset.id.startsWith("omaster") && preset.cover.isEmpty()) return true;
        }
        return false;
    }

    public String exportText() {
        StringBuilder out = new StringBuilder();
        out.append(HEADER).append('\n');
        out.append("# 导出时间 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .format(new java.util.Date())).append('\n');
        out.append("# 预设内容来自 OMaster（https://github.com/iCurrer/OMaster），CC BY-NC-SA 4.0\n");
        out.append(VERSION).append('\n');
        for (Preset preset : presets) out.append(row(preset)).append('\n');
        return out.toString();
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<Preset> keep = new ArrayList<>(presets);
            presets.clear();
            int dropped = 0;
            for (String raw : text.split("\r?\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || VERSION.equals(line)) continue;
                String[] parts = raw.split("\t", -1);
                if (parts.length < 14) {
                    dropped++;
                    continue;
                }
                Preset preset = parse(parts);
                if (preset == null) {
                    dropped++;
                    continue;
                }
                presets.add(preset);
            }
            if (presets.isEmpty()) presets.addAll(keep);
            // 文件里混进了坏行（导入图片/压缩包留下的乱码）：顺手写回去，把坏行清掉。
            if (dropped > 0) save();
        } catch (IOException ignored) {
            // 读不出来就当空库，下一次保存会重建。
        }
    }

    public void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            StringBuilder out = new StringBuilder();
            out.append(HEADER).append('\n');
            out.append("# 预设内容来自 OMaster（https://github.com/iCurrer/OMaster），CC BY-NC-SA 4.0\n");
            out.append(VERSION).append('\n');
            for (Preset preset : presets) out.append(row(preset)).append('\n');
            Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            file.setReadable(true, false);
        } catch (IOException ignored) {
            // 存储不可写时保持内存状态，UI 仍可用。
        }
    }

    public File file() {
        return file;
    }

    /** 一行拆出来的字段还原成预设；id 为空或内容不像预设时返回 null。 */
    private static Preset parse(String[] parts) {
        if (parts.length < 14) return null;
        Preset preset = new Preset();
        preset.id = parts[1].trim();
        if (!validId(preset.id)) return null;
        preset.name = unescape(parts[2]);
        preset.author = unescape(parts[3]);
        preset.filter = unescape(parts[4]);
        preset.softLight = unescape(parts[5]);
        preset.tone = number(parts[6]);
        preset.saturation = number(parts[7]);
        preset.warmCool = number(parts[8]);
        preset.cyanMagenta = number(parts[9]);
        preset.sharpness = number(parts[10]);
        preset.vignette = unescape(parts[11]);
        preset.tips = unescape(parts[12]);
        preset.tags = unescape(parts[13]);
        preset.favorite = parts.length > 14 && "1".equals(parts[14].trim());
        preset.cover = parts.length > 15 ? unescape(parts[15]) : "";
        preset.gallery = parts.length > 16 ? unescape(parts[16]) : "";
        // 第 18 列起是 Pro 参数（老数据没有这几列，读成空就是「不管这几项」）。
        preset.iso = parts.length > 17 ? unescape(parts[17]).trim() : "";
        preset.shutter = parts.length > 18 ? unescape(parts[18]).trim() : "";
        preset.exposureCompensation = parts.length > 19 ? unescape(parts[19]).trim() : "";
        preset.focus = parts.length > 20 ? unescape(parts[20]).trim() : "";
        preset.whiteBalance = parts.length > 21 ? unescape(parts[21]).trim() : "";
        // 导入图片/压缩包时，二进制里的制表符会被当成分隔符拆出一堆乱码行；
        // 这里按「名字是人看得懂的文字」筛掉，顺带清掉以前写进文件的坏行。
        if (!validText(preset.name, 80) || preset.name.isEmpty()) return null;
        if (!validText(preset.author, 80) || !validText(preset.filter, 60) || !validText(preset.softLight, 20)) return null;
        if (!validText(preset.vignette, 20) || !validText(preset.tags, 120) || !validText(preset.tips, 4000)) return null;
        return preset;
    }

    /** id 只允许字母数字和 ._-：乱码行通常在这里就过不去。 */
    private static boolean validId(String id) {
        if (id.isEmpty() || id.length() > 48) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
            if (!allowed) return false;
        }
        return true;
    }

    /** 控制字符和替换字符（U+FFFD，解码失败留下的）说明这一行不是正常文本。 */
    private static boolean validText(String value, int max) {
        if (value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t') continue;
            if (c < 0x20 || c == 0x7f) return false;
            if (c == '\uFFFD') return false;
        }
        return true;
    }

    private static String row(Preset preset) {
        // 末尾五列是 Pro 参数：老版本读的时候看不到它们（列数少就当没有），新版本原样保存。
        return String.join("\t",
            "p", preset.id, escape(preset.name), escape(preset.author), escape(preset.filter), escape(preset.softLight),
            text(preset.tone), text(preset.saturation), text(preset.warmCool), text(preset.cyanMagenta), text(preset.sharpness),
            escape(preset.vignette), escape(preset.tips), escape(preset.tags), preset.favorite ? "1" : "0",
            escape(preset.cover), escape(preset.gallery),
            escape(preset.iso), escape(preset.shutter), escape(preset.exposureCompensation),
            escape(preset.focus), escape(preset.whiteBalance));
    }

    private static String text(Integer value) {
        return value == null ? "" : Integer.toString(clamp(value, -100, 100));
    }

    private static Integer number(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return null;
        try {
            return clamp(Integer.parseInt(value), -100, 100);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", " ").replace("\r", "").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') out.append('\n');
                else out.append(next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
