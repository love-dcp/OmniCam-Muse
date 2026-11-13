package local.jiege.hook.masterfilter;

import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;
import java.util.LinkedHashMap;
import java.util.Map;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.DiagnosticsContract;

/**
 * 大师 Pro 档的五个拍摄参数（ISO / 快门 / 曝光补偿 / 对焦模式 / 白平衡）跟着预设一起走。
 *
 * <p>相机把它自己的 Pro 参数放在 {@code m9.f} 的静态 preference 常量上（这是一个混淆类，字段名
 * 是实测出来的）：
 * <pre>
 *   y0  ISO          w0  白平衡
 *   A0  快门         z0  曝光补偿
 *   B0  对焦模式     x0  色调（这一轮没用到）
 * </pre>
 * 值一律是<b>字符串</b>，而且格式由相机自己决定（"800"、"1/125"、"5200"…）—— 所以模块不解释、
 * 不换算，只做搬运：设置页那五列写什么就写什么；从相机抄的时候也是原样抄。
 *
 * <p>写完值只是让数据层变新，参数面板还要重新读一次才会显示／生效：已经在 Pro 档时调
 * {@code CaptureParamPresenter.C2(true)}；不在 Pro 档就什么都不做 —— 切换档位由用户自己点，
 * 模块不替他切（以前试过自动切档，结果不对，见 MasterProPresets 的说明）。
 *
 * <p>所有调用都包在 try/catch 里：这台相机上没有这些字段时，预设的其余部分照常应用。
 */
final class MasterProParams {
    private static final String TAG = "MusePreset";
    private static final String[][] FIELDS = {
        {"y0", DiagnosticsContract.KEY_PRESET_ISO},
        {"A0", DiagnosticsContract.KEY_PRESET_SHUTTER},
        {"z0", DiagnosticsContract.KEY_PRESET_EV},
        {"B0", DiagnosticsContract.KEY_PRESET_FOCUS},
        {"w0", DiagnosticsContract.KEY_PRESET_WHITE_BALANCE},
    };
    private MasterProParams() {}

    /** 这次请求里有没有 Pro 参数。 */
    static boolean has(Bundle extras) {
        if (extras == null) return false;
        for (String[] entry : FIELDS) {
            String value = extras.getString(entry[1]);
            if (value != null && !value.trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 把这次请求里的 Pro 参数写进相机，返回写成功的项数。
     *
     * <p>只写请求里带了的项：预设没写的那几项保持相机当前值，不会被打成默认。
     */
    static int apply(Bundle extras) {
        if (!has(extras)) return 0;
        ClassLoader loader = MasterPresetApply.cameraLoader();
        Symbols symbols = Symbols.get();
        int written = 0;
        StringBuilder done = new StringBuilder();
        for (String[] entry : FIELDS) {
            String value = extras.getString(entry[1]);
            if (value == null || value.trim().isEmpty()) continue;
            try {
                DataStore.put(symbols, loader, key(loader, entry[0]), value.trim());
                written++;
                done.append(entry[1]).append('=').append(value.trim()).append(' ');
            } catch (Throwable error) {
                Log.e(TAG, "Pro 参数写入失败：" + entry[1], error);
            }
        }
        if (written > 0) {
            Log.i(TAG, "Pro 参数已写入 " + written + " 项：" + done);
            reloadPanel(loader);
        }
        return written;
    }

    /** 读出相机当前的 Pro 参数（设置页「读取相机 Pro 参数」用），键是设置侧那五个键。 */
    static String read() {
        ClassLoader loader = MasterPresetApply.cameraLoader();
        Symbols symbols = Symbols.get();
        StringBuilder out = new StringBuilder();
        for (String[] entry : FIELDS) {
            String value = "";
            try {
                // DataManager.getOr(key, default)：这里的默认值不能是 null。
                Object raw = DataStore.get(symbols, loader, key(loader, entry[0]), "");
                if (raw != null) value = String.valueOf(raw);
            } catch (Throwable error) {
                Log.e(TAG, "Pro 参数读取失败：" + entry[1], error);
            }
            out.append(entry[1]).append('=').append(value).append('\n');
        }
        return out.toString();
    }

    /** 把读出来的结果解析成 Map（设置页也用它回填）。 */
    static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) return values;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    /**
     * 让 Pro 参数面板重新读一遍。只有已经在这个档位里才有意义，所以先确认档位状态；
     * 拿不到面板对象就跳过 —— 值已经写进数据层，下次进 Pro 档相机会自己读。
     */
    private static void reloadPanel(ClassLoader loader) {
        try {
            Object presenter = MasterPresetApply.proPresenter(loader);
            if (presenter == null) {
                Log.i(TAG, "Pro 面板还没就绪：参数已写入数据层，进 Pro 档后生效");
                return;
            }
            XposedHelpers.callMethod(presenter, "C2", true);
            Log.i(TAG, "Pro 参数面板已重新加载");
        } catch (Throwable error) {
            Log.e(TAG, "Pro 面板刷新失败（值已写入）", error);
        }
    }

    /** m9.f.<字段>：Pro 参数的 preference 键，直接交给 DataManager 读写。 */
    private static Object key(ClassLoader loader, String name) {
        return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("m9.f", loader), name);
    }
}
