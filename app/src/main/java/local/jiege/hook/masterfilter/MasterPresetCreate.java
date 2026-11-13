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
    /** 相机存自定义预设的 MMKV 文件（{@code files/mmkv/mmkv}）。 */
    private static final String MMKV_FILE = "mmkv";
    /** 相机上限是 20 个自定义预设（0..19）。 */
    private static final int MAX_PRESETS = 20;

    private MasterPresetCreate() {}

    /**
     * @param loader     相机进程的 ClassLoader
     * @param name       预设名（预设库里的名字）
     * @param extras     这次写入的参数，用来填预设内容
     * @return 是否成功（已有同名预设时改为更新它的参数，不重复新建）
     */
    static boolean create(ClassLoader loader, String name, Bundle extras) {
        if (loader == null || name == null || name.isEmpty() || extras == null) return false;
        Symbols symbols = Symbols.get();
        String filterType = stringOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_TYPE);

        // 同名预设已经在相机里：直接更新它，别再建一个（以前每次导入都会多出一张同名的空卡）。
        java.util.List<String> keys = findAllByName(symbols, loader, name);
        if (keys.isEmpty()) {
            if (filterType.isEmpty()) filterType = "default";
            String key = register(symbols, loader, name, filterType);
            if (key == null) {
                Log.i(TAG, "没有可用的预设编号，跳过：" + name);
                return false;
            }
            keys.add(key);
        } else if (keys.size() > 1) {
            // 早期版本每次导入都新建一张，同名卡堆了好几张。这里只留一张（写参数用），
            // 其余从登记表里摘掉，相机重启后列表就干净了。
            String keep = keys.get(0);
            int total = keys.size();
            keys = new java.util.ArrayList<>(keys.subList(0, 1));
            Log.i(TAG, "发现 " + total + " 张同名预设，只保留 " + keep);
            dropIds(symbols, loader, name, keep);
        }
        // 光有 JSON 不够：预设卡片点开后读的是 MMKV 里<b>按预设键分开</b>的那一组效果键，
        // 没写就是全 0 —— 用户看到的就是「有这个名字的预设，但里面没有参数」。
        // 同名卡可能不止一张（早期版本重复建过），每一张都写，用户看到的那张才一定有参数。
        for (String key : keys) {
            // 设置页没指定滤镜（本机没有这个滤镜名）时，沿用这张预设原来绑的那个：
            // 以前这里直接填 default，于是已有的「哈苏浓郁」（绑 kodak / 菲林）被一次次冲成「标准」。
            String type = filterType;
            if (type.isEmpty()) {
                type = readString(symbols, loader, "key_master_mode_custom_preset_filter_type_" + key);
            }
            if (type == null || type.isEmpty()) type = "default";
            writeValues(key, name, type, extras);
            persist(loader, name, key, extras);
            Log.i(TAG, "预设已写入：" + name + " -> " + key + "（滤镜 " + type + "）");
        }
        return true;
    }

    // ---- 预设键 ----------------------------------------------------------------------------------

    /**
     * 让相机自己分配编号并登记一条自定义预设，返回它的键。
     *
     * <p>优先走相机自己的 {@code addCustomPreset}：编号、{@code key_master_mode_custom_preset_ids}
     * 这些登记动作都是它做的，我们只补参数。相机不在前台（没有 EffectData）时退回到自己挑编号。
     */
    private static String register(Symbols symbols, ClassLoader loader, String name, String filterType) {
        if (symbols == null) return null;
        java.util.List<Integer> before = customIds(symbols, loader);
        Object data = MasterPresetApply.effectDataOrNull();
        if (data != null) {
            // 编号由相机自己分配：它的 add 会把 presetKey 一起填上（实测我们事先填 custom_1，
            // 它照样改成 custom_0），硬用自己挑的编号，内存里那张卡和落盘那条就对不上了。
            addNow(symbols, data, name, filterType);
            for (Integer id : customIds(symbols, loader)) {
                if (!before.contains(id)) {
                    Log.i(TAG, "相机分配了预设编号 custom_" + id);
                    return "custom_" + id;
                }
            }
            // 相机没动 ids 表时，从列表里按名字找回它刚加的那条。
            for (Object preset : MasterPresets.list()) {
                String key = MasterPresets.key(preset);
                if (key == null || !key.startsWith("custom_")) continue;
                if (!name.equals(MasterPresets.name(preset))) continue;
                Log.i(TAG, "从预设列表取到编号：" + key);
                ensureId(symbols, loader, idOf(key));
                return key;
            }
        }
        int id = firstFreeId(before);
        if (id < 0) return null;
        Log.i(TAG, "相机没分配编号，自己用 custom_" + id + " 并登记");
        appendId(symbols, loader, before, id);
        return "custom_" + id;
    }

    /** 相机自己加进了列表但没写 ids 表时补一笔（否则下次按名字找不到这张卡）。 */
    private static void ensureId(Symbols symbols, ClassLoader loader, int id) {
        if (id < 0) return;
        try {
            java.util.List<Integer> ids = customIds(symbols, loader);
            if (ids.contains(Integer.valueOf(id))) return;
            ids.add(Integer.valueOf(id));
            writeIds(symbols, loader, ids);
        } catch (Throwable error) {
            Log.e(TAG, "补登记预设编号失败：" + id, error);
        }
    }

    /** MMKV 里已登记的自定义预设编号（{@code key_master_mode_custom_preset_ids}，形如 "[0,1,2]"）。 */
    /**
     * 已占用的自定义预设编号：MMKV 的登记表 {@code key_master_mode_custom_preset_ids} 与
     * 存储文件里真实存在的 {@code custom_<n>} 取并集。
     *
     * <p>只看登记表会出事：早期版本自己写编号，表里剩 {@code [0,1]} 而文件里其实是
     * {@code custom_1 / custom_2}，按表挑编号会挑到 2，把用户的「假日清新」直接覆盖掉。
     */
    private static java.util.List<Integer> customIds(Symbols symbols, ClassLoader loader) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        String raw = readString(symbols, loader, "key_master_mode_custom_preset_ids");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(raw);
        while (matcher.find()) ids.add(Integer.valueOf(matcher.group()));
        for (Integer id : storedIds(loader)) if (!ids.contains(id)) ids.add(id);
        return ids;
    }

    /** 存储文件里真实存在的自定义预设编号。读不到就返回空表。 */
    private static java.util.List<Integer> storedIds(ClassLoader loader) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        File file = storageFile(loader);
        if (file == null || !file.isFile()) return ids;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray presets = new JSONObject(text).optJSONArray("presets");
            if (presets == null) return ids;
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset == null) continue;
                int id = idOf(preset.optString("presetKey"));
                if (id >= 0 && !ids.contains(Integer.valueOf(id))) ids.add(Integer.valueOf(id));
            }
        } catch (Throwable error) {
            Log.e(TAG, "读预设存储里的编号失败", error);
        }
        return ids;
    }

    /** 相机里所有同名预设的键（按 MMKV 里的名字找）。 */
    private static java.util.List<String> findAllByName(Symbols symbols, ClassLoader loader, String name) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (symbols == null) return keys;
        for (Integer id : customIds(symbols, loader)) {
            String key = "custom_" + id;
            if (!name.equals(readString(symbols, loader, "key_master_mode_custom_preset_name_" + key))) continue;
            Log.i(TAG, "相机里已有同名预设：" + name + " -> " + key);
            keys.add(key);
        }
        return keys;
    }

    private static String readString(Symbols symbols, ClassLoader loader, String preference) {
        try {
            Object value = DataStore.get(symbols, loader,
                key(symbols, loader, String.class, preference), "");
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable error) {
            Log.e(TAG, "读 " + preference + " 失败", error);
            return "";
        }
    }

    private static int firstFreeId(java.util.List<Integer> used) {
        for (int id = 0; id < MAX_PRESETS; id++) if (!used.contains(Integer.valueOf(id))) return id;
        return -1;
    }

    /** 把新编号写回 ids 列表（相机自己登记失败时的兜底，格式与它自己写的一致："[0,1,2]"）。 */
    private static void appendId(Symbols symbols, ClassLoader loader, java.util.List<Integer> ids, int id) {
        java.util.List<Integer> all = new java.util.ArrayList<>(ids);
        all.add(id);
        writeIds(symbols, loader, all);
    }

    /**
     * 把同名重复卡从登记表里摘掉，只留 {@code keep}。
     *
     * <p>只在读到的编号不少于 2 个时才写：万一读取失败拿到空表，写回去就是「[]」，
     * 用户所有自定义预设会从列表里消失。
     */
    private static void dropIds(Symbols symbols, ClassLoader loader, String name, String keep) {
        try {
            java.util.List<Integer> all = customIds(symbols, loader);
            if (all.size() < 2) {
                Log.i(TAG, "登记表里只有 " + all.size() + " 个编号，跳过清理");
                return;
            }
            java.util.List<Integer> kept = new java.util.ArrayList<>();
            for (Integer id : all) {
                String key = "custom_" + id;
                boolean dup = !key.equals(keep)
                    && name.equals(readString(symbols, loader, "key_master_mode_custom_preset_name_" + key));
                if (dup) {
                    Log.i(TAG, "摘掉重复预设 " + key + "（" + name + "）");
                    continue;
                }
                kept.add(id);
            }
            if (kept.size() == all.size()) return;
            writeIds(symbols, loader, kept);
        } catch (Throwable error) {
            Log.e(TAG, "清理重复预设失败：" + name, error);
        }
    }

    private static void writeIds(Symbols symbols, ClassLoader loader, java.util.List<Integer> ids) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) text.append(',');
            text.append(ids.get(i));
        }
        text.append(']');
        putString(symbols, loader, "key_master_mode_custom_preset_ids", text.toString());
        Log.i(TAG, "登记表改为 " + text);
    }

    // ---- 每个预设自己的效果值 ---------------------------------------------------------------------

    /**
     * 把一组参数写进这个预设键对应的效果值（DataManager → MMKV）。
     *
     * <p>相机把效果栏的每个值按<b>预设键</b>分开存：
     * {@code key_master_mode_effect_<项>_<presetKey>}（例如 saturation → custom_2），
     * 另有一套 {@code ..._custom_base_<n>} 和 {@code key_professional_*_<presetKey>} 的副本。
     * 预设卡片选中时，效果栏就是按这一组键恢复的；只写 master_preset_storage.json 的话，
     * 名字、滤镜都对，参数却全是 0。
     *
     * <p>每一项单独 try：某一项类型不对（例如相机把它当字符串存）不能把整组带崩。
     *
     * @return 写成功的项数
     */
    static int writeValues(String presetKey, String name, String filterType, Bundle extras) {
        if (presetKey == null || presetKey.isEmpty() || extras == null) return 0;
        Symbols symbols = Symbols.get();
        ClassLoader loader = MasterPresetApply.cameraLoader();
        if (symbols == null || loader == null) return 0;

        int intensity = clamp(intOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH, 100), 0, 100);
        int softLight = softLightIndex(extras.getString(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT));
        String softType = softLightType(softLight);
        // 影调在相机里就叫 contrast（模块另插的那个对比度是 muse_contrast，不是这一项）。
        int tone = intOf(extras, DiagnosticsContract.KEY_PRESET_TONE, 0);
        int saturation = intOf(extras, DiagnosticsContract.KEY_PRESET_SATURATION, 0);
        int coldWarm = intOf(extras, DiagnosticsContract.KEY_PRESET_WARM_COOL, 0);
        int cyanMagenta = intOf(extras, DiagnosticsContract.KEY_PRESET_CYAN_MAGENTA, 0);
        int sharpness = intOf(extras, DiagnosticsContract.KEY_PRESET_SHARPNESS, 0);
        int vignette = intOf(extras, DiagnosticsContract.KEY_PRESET_VIGNETTE, 0) >= 50 ? 101 : 0;

        int ok = 0;
        // 主键（预设自己）：名字、滤镜类型 + 9 项效果值。
        if (name != null && !name.isEmpty()) ok += putString(symbols, loader, "key_master_mode_custom_preset_name_" + presetKey, name);
        if (filterType != null && !filterType.isEmpty()) {
            ok += putString(symbols, loader, "key_master_mode_custom_preset_filter_type_" + presetKey, filterType);
        }
        ok += putEffects(symbols, loader, presetKey, intensity, softLight, softType, tone,
            saturation, coldWarm, cyanMagenta, sharpness, vignette);
        // 同一套值在 custom_base_<n> 上也存一份：相机「重置」拿的是这一份，留 0 会把参数清掉。
        if (presetKey.startsWith("custom_")) {
            ok += putEffects(symbols, loader, "custom_base_" + presetKey.substring("custom_".length()),
                intensity, softLight, softType, tone, saturation, coldWarm, cyanMagenta, sharpness, vignette);
        }
        Log.i(TAG, "预设效果值已写入：" + presetKey + " 共 " + ok + " 项"
            + "（影调 " + tone + " 饱和 " + saturation + " 冷暖 " + coldWarm + " 青品 " + cyanMagenta
            + " 锐度 " + sharpness + " 强度 " + intensity + " 柔光 " + softLight + "/" + softType
            + " 暗角 " + vignette + "）");
        return ok;
    }

    /** 一整套效果键（主键或 base 键都长一个样）。 */
    private static int putEffects(Symbols symbols, ClassLoader loader, String presetKey,
                                  int intensity, int softLight, String softType, int tone,
                                  int saturation, int coldWarm, int cyanMagenta,
                                  int sharpness, int vignette) {
        String p = "key_master_mode_effect_";
        String prof = "key_professional_";
        int ok = 0;
        ok += putInt(symbols, loader, p + "filter_intensity_" + presetKey, intensity);
        ok += putInt(symbols, loader, p + "soft_light_" + presetKey, softLight);
        ok += putString(symbols, loader, p + "soft_light_type_" + presetKey, softType);
        ok += putInt(symbols, loader, p + "contrast_" + presetKey, tone);
        ok += putInt(symbols, loader, p + "saturation_" + presetKey, saturation);
        ok += putInt(symbols, loader, p + "cold_warm_" + presetKey, coldWarm);
        ok += putInt(symbols, loader, p + "cyan_magenta_" + presetKey, cyanMagenta);
        ok += putInt(symbols, loader, p + "sharpness_" + presetKey, sharpness);
        ok += putInt(symbols, loader, p + "vignette_" + presetKey, vignette);
        ok += putInt(symbols, loader, prof + "filter_intensity_index_" + presetKey, intensity);
        ok += putInt(symbols, loader, prof + "soft_light_index_" + presetKey, softLight);
        ok += putString(symbols, loader, prof + "soft_light_type_" + presetKey, softType);
        return ok;
    }

    /**
     * 柔光档位 → 相机自己的类型串（MMKV 里实测到两个：柔美 roumei、梦幻 menghuan）。
     * 0 档是 {@code default}；「中」没有单独的类型串，按梦幻的强度档处理。
     */
    private static String softLightType(int level) {
        switch (level) {
            case 1: return "portrait.softlight.roumei";
            case 2: return "portrait.softlight.menghuan";
            case 3: return "portrait.softlight.menghuan";
            default: return "default";
        }
    }

    private static int putInt(Symbols symbols, ClassLoader loader, String preference, int value) {
        return put(symbols, loader, Integer.class, preference, value);
    }

    private static int putString(Symbols symbols, ClassLoader loader, String preference, String value) {
        return put(symbols, loader, String.class, preference, value);
    }

    private static int put(Symbols symbols, ClassLoader loader, Class<?> type, String preference, Object value) {
        try {
            DataStore.put(symbols, loader, key(symbols, loader, type, preference), value);
            return 1;
        } catch (Throwable error) {
            Log.e(TAG, "写 " + preference + " 失败", error);
            return 0;
        }
    }

    // ---- 真正的存储 ----------------------------------------------------------------------------

    private static Class<?> keyClass;
    private static java.lang.reflect.Constructor<?> keyCtor;

    /**
     * 造一个相机认的 DataKey —— <b>文件必须是 {@code mmkv}</b>。
     *
     * <p>相机的自定义预设（名字、滤镜类型、各档参数）全都存在
     * {@code mmkv://com.oplus.camera/mmkv} 这份 MMKV 里（{@code files/mmkv/mmkv}）。
     * {@code DataKeys.prefs} 指向 {@code com.oplus.camera_preferences}、
     * {@code DataKeys.cameraPrefs} 指向 {@code ..._preferences_0}，用它们拼出来的键相机一个都不读 ——
     * 这就是「预设建出来了、名字是『预设 N』、点开没有参数」的根因。
     *
     * <p>DataKey 的构造器是 {@code (包名, 文件名, 键名, 类型, 是否同步, 相机编号)}，其中文件名那一位
     * 直接进 MMKV 的 mmapID。这里拿一个现成的 key 当模板，把包名/同步位/相机编号照抄，只换文件名和键名。
     */
    private static Object key(Symbols symbols, ClassLoader loader, Class<?> type, String name) throws Throwable {
        if (keyClass == null) {
            keyClass = de.robv.android.xposed.XposedHelpers.findClass("com.oplus.camera.data.DataKey", loader);
            keyCtor = keyClass.getConstructor(String.class, String.class, String.class, Class.class,
                boolean.class, Integer.class);
        }
        Object template = symbols.call("DataKeys.prefs", null, type, name);
        String pkg = (String) keyClass.getField("d").get(template);
        boolean local = keyClass.getField("h").getBoolean(template);
        Object index = keyClass.getField("i").get(template);
        return keyCtor.newInstance(pkg, MMKV_FILE, name, type, local, index);
    }

    // ---- 名字 ------------------------------------------------------------------------------------

    /**
     * 改一个自定义预设的名字。
     *
     * <p>预设列表显示的既不是 master_preset_storage.json 里的 {@code presetName}，也不是
     * DataManager 的 {@code ..._preferences} 文件，而是 MMKV 里
     * {@code key_master_mode_custom_preset_name_<presetKey>}（`mmkv` 那份）。写错地方界面上
     * 就一直是默认的「预设 N」。
     */
    static boolean renameStored(String presetKey, String name) {
        if (presetKey == null || presetKey.isEmpty() || name == null || name.isEmpty()) return false;
        Symbols symbols = Symbols.get();
        ClassLoader loader = MasterPresetApply.cameraLoader();
        if (symbols == null || loader == null) return false;
        boolean done = putString(symbols, loader, "key_master_mode_custom_preset_name_" + presetKey, name) > 0;
        Log.i(TAG, "预设名写入" + (done ? "成功" : "失败") + "：" + presetKey + " = " + name);
        return done;
    }

    // ---- 文件 ------------------------------------------------------------------------------------

    /**
     * 读改写 master_preset_storage.json：写入（或替换）{@code presetKey} 这一条，参数取 extras。
     *
     * <p>JSON 是相机启动时重建列表用的镜像，参数也从它读；MMKV 那一组效果键才是日常读写的来源，
     * 两处都要写。同一个键再导入一次就覆盖，不再新增一条同名的。
     *
     * @return 写入的编号，失败 -1
     */
    private static int persist(ClassLoader loader, String name, String presetKey, Bundle extras) {
        File file = storageFile(loader);
        if (file == null || !file.isFile()) {
            Log.i(TAG, "找不到相机预设存储文件，跳过写 JSON");
            return -1;
        }
        int id = idOf(presetKey);
        if (id < 0) return -1;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray presets = root.optJSONArray("presets");
            if (presets == null) {
                presets = new JSONArray();
                root.put("presets", presets);
            }
            JSONObject fresh = content(presets, id, name, extras);
            boolean replaced = false;
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset == null || !presetKey.equals(preset.optString("presetKey"))) continue;
                presets.put(i, fresh);
                replaced = true;
                break;
            }
            if (!replaced) presets.put(fresh);
            // 同名条目只留一个：旧版本留下的重复卡如果只从 MMKV 摘、不删 JSON，
            // 相机下次启动按 JSON 重建列表时又会冒出来。
            for (int i = presets.length() - 1; i >= 0; i--) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset == null) continue;
                String key = preset.optString("presetKey");
                if (!key.startsWith("custom_") || key.equals(presetKey)) continue;
                if (!name.equals(preset.optString("presetName"))) continue;
                presets.remove(i);
                Log.i(TAG, "JSON 删掉重复同名条目：" + key);
            }
            root.put("timestamp", System.currentTimeMillis());
            write(file, root.toString(2));
            Log.i(TAG, "JSON 已" + (replaced ? "更新" : "新增") + "：「" + name + "」" + presetKey);
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
            // 存储里只有自定义预设（原厂那些按滤镜现生成，不落文件），所以模板基本都找不到。
            // 名字资源是拼音独立命名的（菲林=camera_filter_feilin），和 LUT 文件名对不上、猜不出来 ——
            // 改成从内存里「绑同一个滤镜的原厂预设」身上抄，卡片那行才会显示「复古」而不是「标准」。
            preset.put("filterNameResEntry", filterNameResEntryFor(filterType));
        }
        // filterType 是滤镜类型（LUT 文件名去掉扩展名，如 kodak / natural），不是列表里的 id。
        preset.put("filterType", filterType.isEmpty() ? "default" : filterType);
        if (template != null) {
            if (preset.optString("filterNameResEntry").isEmpty()) {
                preset.put("filterNameResEntry", "camera_filter_none");
            }
            Log.i(TAG, "预设「" + name + "」绑到滤镜 " + filterType
                + "（模板 " + template.optString("presetKey") + "）");
        } else if (!filterType.isEmpty() && !"default".equalsIgnoreCase(filterType)) {
            Log.i(TAG, "预设「" + name + "」绑到滤镜 " + filterType + "（无模板，名字资源留空）");
        }
        preset.put("filterIntensity", clamp(intOf(extras, DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH, 100), 0, 100));
        preset.put("softLightIndex", softLightIndex(extras.getString(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT)));
        preset.put("contrast", intOf(extras, DiagnosticsContract.KEY_PRESET_TONE, 0));
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

    /** {@code custom_7} → 7；不是自定义键返回 -1。 */
    private static int idOf(String presetKey) {
        if (presetKey == null || !presetKey.startsWith("custom_")) return -1;
        try {
            return Integer.parseInt(presetKey.substring("custom_".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void write(File file, String text) throws Throwable {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * 清掉存储里「不是自定义预设」的条目。
     *
     * <p>相机的大师预设列表 = 每个滤镜一条内置预设 + 存储里的自定义预设。存储里一旦出现
     * {@code presetKey} 为空、或与 {@code filterType} 相同的条目，相机启动时会把它当成
     * 自定义预设再加载一遍 —— 界面上就是「标准」「清透」各多出一张。这种条目长按删不掉：
     * 相机的删除只处理 {@code custom_<n>} 这种键，找不到对应槽位就什么都不做。
     *
     * <p>只删「内置滤镜的副本」，键以 {@code custom_} 开头的一律保留（那是用户自己的预设）。
     *
     * @return 删掉的条数
     */
    static int cleanStorage(ClassLoader loader) {
        File file = storageFile(loader);
        if (file == null || !file.isFile()) return 0;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray presets = root.optJSONArray("presets");
            if (presets == null || presets.length() == 0) return 0;
            JSONArray kept = new JSONArray();
            int dropped = 0;
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset == null) continue;
                String key = preset.optString("presetKey");
                String type = preset.optString("filterType");
                if (key.isEmpty() || key.equals(type) || !key.startsWith("custom_")) {
                    dropped++;
                    Log.i(TAG, "删掉内置预设的副本：" + key + "/" + type
                        + "（" + preset.optString("filterNameResEntry") + "）");
                    continue;
                }
                kept.put(preset);
            }
            if (dropped == 0) return 0;
            root.put("presets", kept);
            root.put("timestamp", System.currentTimeMillis());
            write(file, root.toString(2));
            Log.i(TAG, "预设存储已清理 " + dropped + " 条内置副本，重启相机后列表就干净了");
            return dropped;
        } catch (Throwable error) {
            Log.e(TAG, "清理预设存储失败", error);
            return 0;
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
     * 调相机的 addCustomPreset 让这次会话立刻出现；落盘由调用方另外做，失败也不影响重启后的结果。
     *
     * <p>编号由相机自己分配（它会把 {@code presetKey} 填上），所以这里不再自己塞键 ——
     * 硬塞的话内存里那张卡是 custom_1、落盘那条是 custom_0，用户会看到一张没参数的重复卡。
     */
    static void addNow(Symbols symbols, Object effectData, String name, String filterType) {
        if (symbols == null || effectData == null || name == null || name.isEmpty()) return;
        if (exists(name)) return;
        try {
            Object current = symbols.call("EffectData.current", effectData);
            if (current == null) return;
            // 模板优先用「绑同一个滤镜的内置预设」：卡片上的滤镜名来自 mFilterNameResEntry，
            // 复制「当前预设」（多半是标准）会让自定义预设全显示成「标准」，和内置标准分不清。
            Object template = current;
            Object matched = builtinWithFilterType(filterType);
            if (matched != null) template = matched;
            Object copy = template.getClass().getDeclaredConstructor().newInstance();
            copyFields(template, copy);
            setString(copy, FIELD_NAME, name);
            String type = filterType == null || filterType.isEmpty() ? "default" : filterType;
            setString(copy, FIELD_FILTER_TYPE, type);
            if (!name.equals(MasterPresets.name(copy))) {
                Log.e(TAG, "预设名字段对不上，放弃写入内存: " + copy, new Throwable());
                return;
            }
            Class<?> repository = Class.forName(REPOSITORY, false, current.getClass().getClassLoader());
            Method add = addMethod(repository, copy.getClass());
            if (add == null) return;
            add.invoke(null, copy);
            Log.i(TAG, "当前会话已加入预设：" + name + "（模板 "
                + (template == current ? "当前预设" : type) + "）" + copy);
        } catch (Throwable error) {
            Log.e(TAG, "写入当前会话失败（不影响重启后生效）", error);
        }
    }

    /** 相机内置预设里绑这个滤镜的那一条（抄它的滤镜名等字段）；没有就返回 null。 */
    private static Object builtinWithFilterType(String filterType) {
        if (filterType == null || filterType.isEmpty() || "default".equals(filterType)) return null;
        for (Object preset : MasterPresets.list()) {
            String key = MasterPresets.key(preset);
            if (key == null || key.startsWith("custom_")) continue;
            if (filterType.equals(MasterPresets.filterType(preset))) return preset;
        }
        return null;
    }

    /**
     * 滤镜类型 → 卡片那行滤镜名的资源名；从内存里同滤镜的原厂预设上抄（拿不到就读上次缓存的）。
     * 抄不到（标准、或相机还没建过预设列表）就用 {@code camera_filter_none}，也就是显示「标准」。
     */
    private static String filterNameResEntryFor(String filterType) {
        if (filterType == null || filterType.isEmpty() || "default".equalsIgnoreCase(filterType)) {
            return "camera_filter_none";
        }
        String res = filterNames().get(filterType);
        if (res == null) {
            // 老版本写的是去掉扩展名的短名（fuji-nc），按短名再认一次。
            String id = filterIdOf(filterType);
            if (!id.equals(filterType)) res = filterNames().get(id);
        }
        if (res == null || res.isEmpty()) {
            Log.i(TAG, "没有滤镜 " + filterType + " 的名字资源，卡片滤镜名按「标准」显示");
            return "camera_filter_none";
        }
        return res;
    }

    /** 短名 / id 都认：把 {@code fuji-nc} 这种补成滤镜列表里的 id（{@code fuji-nc.bin}）。 */
    private static String filterIdOf(String filterType) {
        if (filterType == null || filterType.isEmpty()) return "";
        if (filterType.contains(".")) return filterType;
        for (String id : filterNames().keySet()) {
            if (local.jiege.settings.FilterAliases.filterTypeOf(id).equals(filterType)) return id;
        }
        return filterType;
    }

    /** 滤镜名对照表的缓存：只有相机进程读写，放在相机自己的数据目录。 */
    private static final String NAME_CACHE = "muse_filter_names.txt";

    private static File nameCacheFile() {
        try {
            android.content.Context context = android.app.AndroidAppHelper.currentApplication();
            if (context == null) return null;
            return new File(context.getFilesDir(), NAME_CACHE);
        } catch (Throwable error) {
            return null;
        }
    }

    /** 当前能拿到的「滤镜类型 → 滤镜名资源」：内存里有就用内存的，没有读缓存。 */
    private static java.util.Map<String, String> filterNames() {
        java.util.Map<String, String> names = learnedNames();
        if (!names.isEmpty()) return names;
        File cache = nameCacheFile();
        if (cache == null || !cache.isFile()) return names;
        try {
            String text = new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;
                names.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
            }
        } catch (Throwable error) {
            Log.e(TAG, "读滤镜名缓存失败", error);
        }
        return names;
    }

    /** 从相机当前预设列表里抄一份「滤镜类型 → 滤镜名资源」。列表没建时返回空表。 */
    private static java.util.Map<String, String> learnedNames() {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (Object preset : MasterPresets.list()) {
            String key = MasterPresets.key(preset);
            if (key == null || key.startsWith("custom_")) continue;
            String type = MasterPresets.filterType(preset);
            String res = MasterPresets.filterNameResEntry(preset);
            if (type == null || type.isEmpty() || res == null || res.isEmpty()) continue;
            names.put(type, res);
        }
        return names;
    }

    /**
     * 把「滤镜类型 → 滤镜名资源」存下来，并顺手补正已落库的自定义预设：
     * 设置页写入时相机常常还没进大师模式，那时抄不到名字，卡片滤镜名会落成 {@code camera_filter_none}。
     */
    static void learnFilterNames(ClassLoader loader) {
        java.util.Map<String, String> names = learnedNames();
        if (names.isEmpty()) return;
        File cache = nameCacheFile();
        if (cache != null) {
            StringBuilder text = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : names.entrySet()) {
                text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
            }
            try {
                Files.write(cache.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                Log.e(TAG, "缓存滤镜名失败", error);
            }
        }
        repairNames(loader, names);
    }

    /** 已落库的自定义预设里，滤镜绑定写成短名、或滤镜名资源是空/standard 的，按对照表补上。 */
    private static void repairNames(ClassLoader loader, java.util.Map<String, String> names) {
        Symbols symbols = Symbols.get();
        File file = storageFile(loader);
        if (file == null || !file.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray presets = root.optJSONArray("presets");
            if (presets == null || presets.length() == 0) return;
            int fixed = 0;
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset == null) continue;
                String key = preset.optString("presetKey");
                if (!key.startsWith("custom_")) continue;
                String type = preset.optString("filterType");
                if (type.isEmpty() || "default".equalsIgnoreCase(type)) continue;
                // 短名 → 滤镜列表里的 id（老版本写成 fuji-nc，相机认的是 fuji-nc.bin）。
                String id = filterIdOf(type);
                String res = id.equals(type) ? names.get(type) : names.get(id);
                if (res == null) continue;
                boolean dirty = false;
                if (!id.equals(type)) {
                    preset.put("filterType", id);
                    dirty = true;
                    Log.i(TAG, "补正预设滤镜绑定：" + preset.optString("presetName") + " " + type + " -> " + id);
                }
                // MMKV 那份才是相机运行时读的：卡片那行滤镜名按它去滤镜列表里找，
                // 短名找不到就一路显示「标准」。
                if (symbols != null) {
                    String stored = readString(symbols, loader, "key_master_mode_custom_preset_filter_type_" + key);
                    if (!id.equals(stored)) {
                        putString(symbols, loader, "key_master_mode_custom_preset_filter_type_" + key, id);
                        Log.i(TAG, "补正预设滤镜绑定（MMKV）：" + key + " " + stored + " -> " + id);
                    }
                }
                if (!res.equals(preset.optString("filterNameResEntry"))) {
                    preset.put("filterNameResEntry", res);
                    dirty = true;
                    Log.i(TAG, "补正预设滤镜名：" + preset.optString("presetName") + " " + id + " -> " + res);
                }
                if (dirty) fixed++;
            }
            if (fixed == 0) return;
            root.put("timestamp", System.currentTimeMillis());
            write(file, root.toString(2));
            Log.i(TAG, "已补正 " + fixed + " 条预设的滤镜名");
        } catch (Throwable error) {
            Log.e(TAG, "补正预设滤镜名失败", error);
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
