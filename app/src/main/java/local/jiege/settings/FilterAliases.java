package local.jiege.settings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 原厂滤镜改名对照：预设库里写的名字来自别的 ColorOS 版本，本机可能已经改名。
 *
 * <p>为什么需要：大师预设库（OMaster）里的滤镜名是 ColorOS 16 的叫法，
 * ColorOS 17 把「浓郁」改成了「菲林」（同一个滤镜，LUT 仍是 {@code kodak.cube.rgb.bin}）。
 * 名字对不上时相机不会切滤镜，用户看到的就是「导入成功但找不到这个滤镜」。
 *
 * <p>用户逐个核对过：**改名的只有「浓郁」这一个**。别凭猜测往表里加 —— 加错会把预设绑到
 * 另一个滤镜上，比不绑更难查。表里只放能确认的对应关系。
 *
 * <p>纯 Java、不依赖 Android，可离线测试（tools/offline_tests）。
 */
public final class FilterAliases {
    /** 旧名（预设库里的写法）→ 本机当前的名字。 */
    private static final Map<String, String> RENAMED = new LinkedHashMap<>();
    /**
     * 旧名 → 承载它的 LUT 文件名。滤镜表里的 title 可能被清空（用户改过名/导入时没填），
     * 只按 title 找会漏，这张表按文件名兜底。只有确认过的对应关系才能往这里加。
     */
    private static final Map<String, String> LUT = new LinkedHashMap<>();

    static {
        RENAMED.put("浓郁", "菲林");
        LUT.put("浓郁", "kodak.cube.rgb.bin");
    }

    private FilterAliases() {}

    /** 这个名字对应的 LUT 文件；没有登记返回 null。 */
    public static String lutOf(String name) {
        return name == null ? null : LUT.get(name);
    }

    /** 本机当前该用哪个名字：没有改名记录时原样返回。 */
    public static String current(String name) {
        if (name == null) return null;
        String renamed = RENAMED.get(name);
        return renamed == null ? name : renamed;
    }

    /** 这个名字在本机被改过名（和本机的名字不同）。 */
    public static boolean renamed(String name) {
        if (name == null) return false;
        return RENAMED.containsKey(name);
    }

    /**
     * 滤镜列表里的 id（LUT 文件名）→ 相机预设用的滤镜类型。
     *
     * <p>相机按滤镜类型存每一项的效果值（{@code key_master_mode_effect_saturation_kodak}），
     * 而这个类型就是 LUT 文件名去掉扩展名：{@code kodak.cube.rgb.bin} → {@code kodak}、
     * {@code qing_tou.bin} → {@code qing_tou}、{@code LUT65_A01.cube.rgb.bin} → {@code LUT65_A01}
     * （MMKV 里的键名逐个对得上）。预设 JSON 的 {@code filterType} 用的是这一个，
     * 不是列表里的文件名 —— 写错就会变成另一条不存在的滤镜。
     */
    public static String filterTypeOf(String id) {
        if (id == null || id.isEmpty()) return "default";
        String type = id;
        for (String suffix : new String[]{".cube.rgb.bin", ".bin"}) {
            if (type.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                type = type.substring(0, type.length() - suffix.length());
                break;
            }
        }
        return type;
    }
}
