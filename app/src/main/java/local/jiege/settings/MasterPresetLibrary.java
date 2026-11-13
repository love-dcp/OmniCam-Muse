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
    /** 用户自己起的名字单独存：内置数据刷新（syncBuiltin/reseed）时按 id 盖回去，不会被冲掉。 */
    public static final String NAMES_FILE = "master_preset_names.txt";
    /**
     * 用户在库里删掉的内置条目 id，一行一个。内置数据随模块升级增补时按它跳过，
     * 否则删掉的内置预设下次启动就自己长回来了。
     */
    public static final String REMOVED_FILE = "master_preset_removed.txt";
    /** 内置数据的格式版本，改格式时递增，旧文件会被重新播种。 */
    private static final String VERSION = "v2";
    private static final String HEADER = "# OmniCam Muse · 大师预设库";

    private final File file;
    private final File namesFile;
    private final File removedFile;
    private final List<Preset> presets = new ArrayList<>();
    /** 被用户删掉的内置条目 id：读盘时过滤，内置数据刷新时跳过。 */
    private final Set<String> removed = new LinkedHashSet<>();
    /** id → 用户起的名字。只记改过名的条目，没改过的用文件里的名字。 */
    private final java.util.Map<String, String> customNames = new java.util.LinkedHashMap<>();
    /**
     * id → 写进相机时用的预设名。
     *
     * <p>和库里的名字分开：库里那条是「这张预设叫什么」（导入来的通常就是图片名），
     * 相机预设条上想显示什么名字是另一回事，导入时在弹窗里定，默认也是图片名。
     */
    private final java.util.Map<String, String> cameraNames = new java.util.LinkedHashMap<>();

    public MasterPresetLibrary(File dir) {
        this.file = new File(dir, FILE);
        this.namesFile = new File(dir, NAMES_FILE);
        this.removedFile = new File(dir, REMOVED_FILE);
        loadRemoved();
        loadNames();
        load();
        applyNames();
        if (!removed.isEmpty()) dropRemoved();
    }

    /** 过滤掉用户删过的内置条目（只在读盘后、内置数据刷新后需要）。 */
    private void dropRemoved() {
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (removed.contains(presets.get(i).id)) presets.remove(i);
        }
    }

    private void loadRemoved() {
        if (!removedFile.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(removedFile.toPath()), StandardCharsets.UTF_8);
            for (String raw : text.split("\r?\n")) {
                String line = raw.trim();
                if (!line.isEmpty() && !line.startsWith("#")) removed.add(line);
            }
        } catch (IOException ignored) {
            // 读不出来就当没删过。
        }
    }

    private void saveRemoved() {
        try {
            StringBuilder out = new StringBuilder();
            out.append("# OmniCam Muse · 删掉的内置预设（内置数据刷新时跳过这些 id）\n");
            for (String id : removed) out.append(id).append('\n');
            Files.write(removedFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            removedFile.setReadable(true, false);
        } catch (IOException ignored) {
            // 写不进去只在本次运行里生效。
        }
    }

    /** 把用户起的名字盖回列表（读盘之后、内置数据刷新之后都要再来一次）。 */
    private void applyNames() {
        for (Preset preset : presets) {
            String name = customNames.get(preset.id);
            if (name != null && !name.isEmpty()) preset.name = name;
            String camera = cameraNames.get(preset.id);
            if (camera != null) preset.cameraName = camera;
        }
    }

    private void loadNames() {
        if (!namesFile.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(namesFile.toPath()), StandardCharsets.UTF_8);
            for (String raw : text.split("\r?\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) continue;
                String id = line.substring(0, tab).trim();
                String rest = line.substring(tab + 1);
                int second = rest.indexOf('\t');
                if (second < 0) {
                    customNames.put(id, unescape(rest));
                    continue;
                }
                customNames.put(id, unescape(rest.substring(0, second)));
                String camera = rest.substring(second + 1);
                if (!camera.isEmpty()) cameraNames.put(id, unescape(camera));
            }
        } catch (IOException ignored) {
            // 名字文件读不出来就当没改过名，主库照常用。
        }
    }

    private void saveNames() {
        try {
            StringBuilder out = new StringBuilder();
            out.append("# OmniCam Muse · 自己起的预设名（id + 制表符 + 库里的名字 + 制表符 + 相机里的名字）\n");
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(customNames.keySet());
            ids.addAll(cameraNames.keySet());
            for (String id : ids) {
                out.append(id).append('\t').append(escape(customNames.getOrDefault(id, "")))
                    .append('\t').append(escape(cameraNames.getOrDefault(id, ""))).append('\n');
            }
            Files.write(namesFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            namesFile.setReadable(true, false);
        } catch (IOException ignored) {
            // 写不进去就只在内存里生效。
        }
    }

    /** 一条预设：8 个大师参数 + 拍摄建议。数值为 null 表示该项没有指定。 */
    public static final class Preset {
        public String id = "";
        public String name = "";
        /**
         * 写进相机时用的预设名（相机预设条上显示的那一个）。空表示沿用 [name]。
         *
         * <p>和库里的名字分开：库里的名字是这张预设在你素材库里的名字；相机里那条叫什么是
         * 导入时在「滤镜名」弹窗里定的（默认也是图片名），两个可以不一样。
         */
        public String cameraName = "";
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
        // 用户把库删空过（removed 里有记录）时不要再塞回去，否则删了跟没删一样。
        if (!presets.isEmpty() || !removed.isEmpty()) return;
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
            if (id.isEmpty() || !id.startsWith("omaster") || removed.contains(id)) continue;
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
        // 内置数据把名字覆盖回原厂名了，用户自己改过的名字在这里补回来。
        applyNames();
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
        // 用户删过的内置条目不要跟着重建回来。
        dropRemoved();
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
        applyNames();
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

    /** 名字长度上限：相机预设条的显示宽度就这么点，太长会被截成「…」。 */
    private static final int MAX_NAME = 24;

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).trim();
    }

    /**
     * 解析并导入一段文本：本模块的行格式，或 OMaster 的 presets.json（由调用方先转成行格式）。
     * 同 id 覆盖，新 id 追加。返回实际导入的条数。
     */
    public int importText(String text) {
        return importText(text, "", "");
    }

    /**
     * 带自定义名字的导入：[namePrefix] 非空时用它替换文件里的名字——只有一条就直接叫这个名字，
     * 多条自动编号（前缀 1、前缀 2…），这样一次导入一堆预设也能全用自己起的名字。
     * 留空则保留文件里的原名。
     */
    public int importText(String text, String namePrefix) {
        return importText(text, namePrefix, "");
    }

    /**
     * 导入并指定封面：照片导入时用这张图当封面，[cover] 是本地图片文件的绝对路径。
     * 空串表示不改封面（文本导入、presets.json 都走这条）。
     */
    public int importText(String text, String namePrefix, String cover) {
        List<Preset> parsed = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || VERSION.equals(line)) continue;
            String[] parts = raw.split("\t", -1);
            if (parts.length < 14) continue;
            Preset preset = parse(parts);
            if (preset == null) continue;
            parsed.add(preset);
        }
        String prefix = cut(clean(namePrefix), MAX_NAME);
        String picture = clean(cover);
        for (int i = 0; i < parsed.size() && !prefix.isEmpty(); i++) {
            Preset preset = parsed.get(i);
            String name = parsed.size() == 1 ? prefix : prefix + " " + (i + 1);
            // 导入时起的名字两头都用上：库里这条卡片显示它，写进相机后预设条也显示它。
            // 之前只写相机那一边，导入完卡片上还是图片名，看着就像「名字没改成功」。
            preset.name = name;
            preset.cameraName = name;
            customNames.put(preset.id, name);
            cameraNames.put(preset.id, name);
        }
        // 照片导入：这张图就是封面。不设的话卡片只能显示渐变色块。
        if (!picture.isEmpty()) {
            for (Preset preset : parsed) {
                preset.cover = picture;
                preset.gallery = "";
            }
        }
        for (Preset preset : parsed) merge(preset);
        applyNames();
        if (!prefix.isEmpty()) saveNames();
        if (!parsed.isEmpty()) save();
        return parsed.size();
    }

    /** 给一条预设改名（改的是库里这条的名字）。空名字、名字没变都返回 false。 */
    public boolean rename(String id, String name) {
        Preset preset = find(id);
        if (preset == null) return false;
        String value = cut(clean(name), MAX_NAME);
        if (value.isEmpty()) return false;
        // 名字单独存一份：内置数据刷新时按 id 盖回来，改过的名字不会被冲掉。
        boolean changed = !value.equals(preset.name) || !value.equals(customNames.get(id));
        // 相机里那条的名字没单独定过（或本来就跟库里同名）时一起跟着改，保持两边一致。
        boolean followCamera = preset.cameraName.isEmpty() || preset.cameraName.equals(preset.name);
        preset.name = value;
        customNames.put(id, value);
        if (followCamera) {
            preset.cameraName = value;
            cameraNames.put(id, value);
        }
        saveNames();
        if (changed) save();
        return changed;
    }

    /** 单独改「写进相机时用的预设名」，空串表示恢复成跟库里同名。 */
    public boolean setCameraName(String id, String value) {
        Preset preset = find(id);
        if (preset == null) return false;
        String name = cut(clean(value), MAX_NAME);
        if (name.equals(preset.cameraName)) return false;
        preset.cameraName = name;
        if (name.isEmpty()) cameraNames.remove(id); else cameraNames.put(id, name);
        saveNames();
        return true;
    }

    /**
     * 从库里删掉一条预设。内置条目（id 以 omaster 开头）会记进黑名单，
     * 内置数据随模块升级增补时不会再把它加回来。
     *
     * <p>只动库：相机里已经写入的那条预设是相机自己的数据，得在相机预设条上删。
     */
    public boolean remove(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean hit = false;
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.get(i).id.equals(id)) {
                presets.remove(i);
                hit = true;
            }
        }
        if (!hit) return false;
        customNames.remove(id);
        cameraNames.remove(id);
        saveNames();
        if (id.startsWith("omaster")) {
            removed.add(id);
            saveRemoved();
        }
        save();
        return true;
    }

    /**
     * 改一条预设的调色参数（详情页「改参数」用）。null 表示这一项不指定，
     * 写进相机时保持相机当前值；[vignette] 只认「开」「关」，其它当没指定。
     */
    public boolean setParams(String id, String filter, String softLight, Integer tone, Integer saturation,
                             Integer warmCool, Integer cyanMagenta, Integer sharpness, String vignette) {
        Preset preset = find(id);
        if (preset == null) return false;
        preset.filter = cut(clean(filter), 60);
        preset.softLight = cut(clean(softLight), 20);
        preset.tone = tone;
        preset.saturation = saturation;
        preset.warmCool = warmCool;
        preset.cyanMagenta = cyanMagenta;
        preset.sharpness = sharpness;
        String corner = clean(vignette);
        preset.vignette = "开".equals(corner) || "关".equals(corner) ? corner : "";
        save();
        return true;
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
            System.out.println("[MusePreset] save ok " + file + " " + presets.size() + " rows");
        } catch (IOException error) {
            // 存储不可写时保持内存状态，UI 仍可用。
            System.out.println("[MusePreset] save FAILED " + file + " " + error);
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
