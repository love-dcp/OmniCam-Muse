package local.jiege.hook.masterfilter;

import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.DiagnosticsContract;

/**
 * 在相机里新建一个自定义预设，用来承载「本机没有这个滤镜」的预设参数。
 *
 * <p>为什么需要：预设库里有些条目写的是别的 ColorOS 版本的滤镜名（例如「浓郁」在 ColorOS 17
 * 已改名「菲林」）。名字对不上时相机不会切滤镜，参数写进去也没用 —— 用户看到的是
 * 「导入成功但找不到这个滤镜」。这时改成：用「标准」滤镜承载这套参数，再落成一个自定义预设，
 * 预设名就用预设库里的名字，用户在预设列表里就能直接选到。
 *
 * <p>存两处，缺一不可：
 * <ul>
 *   <li><b>文件</b> {@code <相机数据>/camera_preset/master_preset_storage.json}：相机启动时按它
 *       重建预设列表，是持久化的唯一来源。它的格式（实测）：
 *       <pre>
 * { "androidVersionCode":37, "appVersion":"…", "colorosVersion":"V17.0.0", …, "formatVersion":1,
 *   "presets":[{"presetKey":"custom_0","presetName":"预设 1","filterType":"default",
 *     "filterNameResEntry":"camera_filter_none","filterIntensity":100,"softLightIndex":0,
 *     "contrast":0,"saturation":5,"coldWarm":10,"cyanMagenta":6,"sharpness":0,
 *     "vignette":101,"version":2}],
 *   "storageState":0, "timestamp":… }
 *       </pre>
 *       注意这里没有「影调」：相机自己的预设本来就不存它。</li>
 *   <li><b>内存</b> {@code PresetDataRepository.addCustomPreset(EffectPresetDataV2)}：只写 DataManager，
 *       让当前这次会话立刻能看到；不落盘，重启就没了（实测：只调它，重启后相机仍只有原来那几个预设）。</li>
 * </ul>
 *
 * <p>落库方法按签名找（静态、只收一个预设参数），存储路径也按签名找（静态、无参、返回 File），
 * 都不依赖混淆后的方法名；名字/滤镜类型字段写完后用相机自己的 toString 核对，对不上就整单放弃。
 */
final class MasterPresetCreate {
    private static final String TAG = "MusePreset";
    /** mPresetName */
    private static final String FIELD_NAME = "b";
    /** mFilterType */
    private static final String FIELD_FILTER_TYPE = "e";
    private static final String REPOSITORY = "com.oplus.camera.feature.captureparam.preset.PresetDataRepository";
    /** 相机上限是 20 个自定义预设（0..19）。 */
    private static final int MAX_PRESETS = 20;

    private MasterPresetCreate() {}

    /**
     * @param loader     相机进程的 ClassLoader
     * @param name       预设名（预设库里的名字）
     * @param extras     这次写入的参数，用来填预设内容
     * @return 是否新建成功（已有同名预设时返回 false，不重复建）
     */
    static boolean create(ClassLoader loader, String name, Bundle extras) {
        if (loader == null || name == null || name.isEmpty() || extras == null) return false;
        int id = persist(loader, name, extras);
        if (id < 0) return false;
        // 名字也要写进 DataManager，否则预设列表上显示的还是这个键以前的名字。
        renameStored("custom_" + id, name);
        Log.i(TAG, "预设已写入存储：" + name);
        return true;
    }

    // ---- 名字 ------------------------------------------------------------------------------------

    /**
     * 改一个自定义预设的名字。
     *
     * <p>相机把自定义预设名单独记在 DataManager 的
     * {@code key_master_mode_custom_preset_name_<presetKey>} 上；预设列表 UI 显示的就是这一份，
     * 不是 master_preset_storage.json 里的 presetName（实测：JSON 写「哈苏浓郁」、DataManager 里
     * 还是「预设 1」，界面上就一直是「标准 - 预设 1」）。所以名字必须两处都写。
     */
    static boolean renameStored(String presetKey, String name) {
        if (presetKey == null || presetKey.isEmpty() || name == null || name.isEmpty()) return false;
        try {
            Symbols symbols = Symbols.get();
            ClassLoader loader = MasterPresetApply.cameraLoader();
            Object key = symbols.call("DataKeys.prefs", null, String.class,
                "key_master_mode_custom_preset_name_" + presetKey);
            DataStore.put(symbols, loader, key, name);
            Log.i(TAG, "预设名已写入 DataManager：" + presetKey + " = " + name);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "改预设名失败：" + presetKey + " -> " + name, error);
            return false;
        }
    }

    // ---- 文件 ------------------------------------------------------------------------------------

    /** 读改写 master_preset_storage.json：追加一个绑标准滤镜、参数取 extras 的预设。返回编号，失败 -1。 */
    private static int persist(ClassLoader loader, String name, Bundle extras) {
        File file = storageFile(loader);
        if (file == null || !file.isFile()) {
            Log.i(TAG, "找不到相机预设存储文件，跳过新建");
            return -1;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray presets = root.optJSONArray("presets");
            if (presets == null) {
                presets = new JSONArray();
                root.put("presets", presets);
            }
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset != null && name.equals(preset.optString("presetName"))) {
                    Log.i(TAG, "预设已存在，不再新建：" + name);
                    return -1;
                }
            }
            int id = nextId(presets);
            if (id < 0) {
                Log.i(TAG, "自定义预设已满 " + MAX_PRESETS + " 个，跳过新建");
                return -1;
            }
            presets.put(content(presets, id, name, extras));
            root.put("timestamp", System.currentTimeMillis());
            write(file, root.toString(2));
            String bound = stringOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_TYPE);
            Log.i(TAG, "已新建预设「" + name + "」custom_" + id
                + "（滤镜 " + (bound.isEmpty() ? "default" : bound) + "）");
            return id;
        } catch (Throwable error) {
            Log.e(TAG, "写预设存储失败：" + name, error);
            return -1;
        }
    }

    /**
     * 一个预设对象，字段名与相机自己写出的一致。
     *
     * <p>能绑滤镜（设置侧传了 {@link DiagnosticsContract#KEY_PRESET_FILTER_TYPE}）时，
     * 以存储里**同滤镜的原厂预设**为模板复制一份再改参数：{@code filterNameResEntry} 这类
     * 资源名没法凭空猜，复制现成的才不会写错；找不到同滤镜的现成预设就退回「标准」。
     */
    private static JSONObject content(JSONArray presets, int id, String name, Bundle extras) throws Throwable {
        String filterType = stringOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_TYPE);
        JSONObject template = templateFor(presets, filterType);
        JSONObject preset = template == null ? new JSONObject() : new JSONObject(template.toString());
        preset.put("presetKey", "custom_" + id);
        preset.put("presetName", name);
        if (template == null) {
            preset.put("filterType", "default");
            preset.put("filterNameResEntry", "camera_filter_none");
        } else {
            preset.put("filterType", filterType);
            if (preset.optString("filterNameResEntry").isEmpty()) {
                preset.put("filterNameResEntry", "camera_filter_none");
            }
            Log.i(TAG, "预设「" + name + "」绑到滤镜 " + filterType
                + "（模板 " + template.optString("presetKey") + "）");
        }
        preset.put("filterIntensity", clamp(intOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH, 100), 0, 100));
        preset.put("softLightIndex", softLightIndex(extras.getString(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT)));
        preset.put("contrast", 0);
        preset.put("saturation", intOf(extras, DiagnosticsContract.KEY_PRESET_SATURATION, 0));
        preset.put("coldWarm", intOf(extras, DiagnosticsContract.KEY_PRESET_WARM_COOL, 0));
        preset.put("cyanMagenta", intOf(extras, DiagnosticsContract.KEY_PRESET_CYAN_MAGENTA, 0));
        preset.put("sharpness", intOf(extras, DiagnosticsContract.KEY_PRESET_SHARPNESS, 0));
        // 暗角是开关量：存储里 101 是开、0 是关（写 1..100 的强度无效）。
        preset.put("vignette", intOf(extras, DiagnosticsContract.KEY_PRESET_VIGNETTE, 0) >= 50 ? 101 : 0);
        preset.put("version", 2);
        return preset;
    }

    /**
     * 存储里第一条绑这个滤镜、且不是自定义的预设。相机自己的预设列表是「自定义在前，
     * 之后每个滤镜一条原厂预设」，所以只要有这个滤镜，就能找到一条可以直接抄的。
     */
    private static JSONObject templateFor(JSONArray presets, String filterType) {
        if (filterType == null || filterType.isEmpty() || "default".equalsIgnoreCase(filterType)) return null;
        for (int i = 0; i < presets.length(); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset == null) continue;
            if (preset.optString("presetKey").startsWith("custom_")) continue;
            if (!filterType.equalsIgnoreCase(preset.optString("filterType"))) continue;
            return preset;
        }
        Log.i(TAG, "存储里没有滤镜 " + filterType + " 的现成预设，改绑「标准」");
        return null;
    }

    /** 取字符串：缺失或空串都当没传。 */
    private static String stringOf(Bundle extras, String key) {
        if (extras == null) return "";
        Object value = extras.get(key);
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    /** 柔光四档：无 / 弱（柔美）/ 中（朦胧）/ 强（梦幻）。 */
    private static int softLightIndex(String softLight) {
        if (softLight == null) return 0;
        switch (softLight.trim()) {
            case "弱":
            case "柔美": return 1;
            case "中":
            case "朦胧": return 2;
            case "强":
            case "梦幻": return 3;
            default: return 0;
        }
    }

    /** 0..19 里第一个没被占用的编号，都占了返回 -1。 */
    private static int nextId(JSONArray presets) {
        boolean[] used = new boolean[MAX_PRESETS];
        for (int i = 0; i < presets.length(); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset == null) continue;
            String key = preset.optString("presetKey");
            if (!key.startsWith("custom_")) continue;
            try {
                int id = Integer.parseInt(key.substring("custom_".length()));
                if (id >= 0 && id < MAX_PRESETS) used[id] = true;
            } catch (NumberFormatException ignored) {
                // 不是我们认识的键，跳过
            }
        }
        for (int id = 0; id < MAX_PRESETS; id++) if (!used[id]) return id;
        return -1;
    }

    private static void write(File file, String text) throws Throwable {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** 存储文件：PresetDataRepository 里那个静态、无参、返回 File 的方法。 */
    private static File storageFile(ClassLoader loader) {
        try {
            Class<?> repository = Class.forName(REPOSITORY, false, loader);
            for (Method method : repository.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterTypes().length != 0) continue;
                if (!File.class.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                Object file = method.invoke(null);
                if (file instanceof File) return (File) file;
            }
        } catch (Throwable error) {
            Log.e(TAG, "取预设存储路径失败", error);
        }
        return null;
    }

    /** 取整数值：设置侧直接给 int，排队文件里则可能是一条文本（暗角就是这样）。 */
    @SuppressWarnings("deprecation")
    private static int intOf(Bundle extras, String key, int fallback) {
        Object value = extras.get(key);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // 落到兜底
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- 当前会话（可选） --------------------------------------------------------------------------

    /**
     * 调相机的 addCustomPreset 让这次会话立刻出现；落盘已经做过，失败也不影响重启后的结果。
     * 它的参数来自当前预设对象，字段值未必等于这次写入的值，所以只当「让列表多一项」用。
     */
    static void addNow(Symbols symbols, Object effectData, String name, String filterType) {
        if (symbols == null || effectData == null || name == null || name.isEmpty()) return;
        if (exists(name)) return;
        try {
            Object current = symbols.call("EffectData.current", effectData);
            if (current == null) return;
            Object copy = current.getClass().getDeclaredConstructor().newInstance();
            copyFields(current, copy);
            setString(copy, FIELD_NAME, name);
            String type = filterType == null ? "default" : filterType;
            setString(copy, FIELD_FILTER_TYPE, type);
            if (!name.equals(MasterPresets.name(copy))) {
                Log.e(TAG, "预设名字段对不上，放弃写入内存: " + copy, new Throwable());
                return;
            }
            Class<?> repository = Class.forName(REPOSITORY, false, current.getClass().getClassLoader());
            Method add = addMethod(repository, copy.getClass());
            if (add == null) return;
            add.invoke(null, copy);
            Log.i(TAG, "当前会话已加入预设：" + name);
        } catch (Throwable error) {
            Log.e(TAG, "写入当前会话失败（不影响重启后生效）", error);
        }
    }

    /** 相机里已经有同名的自定义预设。 */
    private static boolean exists(String name) {
        for (Object preset : MasterPresets.list()) {
            String key = MasterPresets.key(preset);
            if (key == null || !key.startsWith("custom_")) continue;
            if (name.equals(MasterPresets.name(preset))) return true;
        }
        return false;
    }

    /** 落库方法：静态、只收一个参数，且参数就是这个预设类的方法。不依赖混淆后的方法名。 */
    private static Method addMethod(Class<?> repository, Class<?> presetClass) {
        for (Method method : repository.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterTypes().length != 1) continue;
            if (!method.getParameterTypes()[0].isAssignableFrom(presetClass)) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static void copyFields(Object from, Object to) throws Throwable {
        for (Class<?> type = from.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(to, field.get(from));
            }
        }
    }

    private static void setString(Object target, String field, String value) {
        try {
            Field target_field = findField(target.getClass(), field);
            if (target_field != null) {
                target_field.setAccessible(true);
                target_field.set(target, value);
            }
        } catch (Throwable error) {
            Log.e(TAG, "写字段 " + field + " 失败", error);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
