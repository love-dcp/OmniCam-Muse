package local.jiege.hook.masterfilter;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.DiagnosticsContract;

/**
 * 把 OMaster 的一组大师预设参数写进相机的效果栏。
 *
 * <p>数据侧见 MasterPresetLibrary；通道见 DiagnosticsContract.METHOD_APPLY_PRESET
 * （设置 App 调相机导出的 EntryProvider，本类的 {@link #handle} 应答）。
 *
 * <p>做法：EffectMenu 构造时记下每个菜单实例与它的 mode；应用时取大师模式那个菜单，
 * 遍历 {@code EffectMenuBase.items}，用 <b>drawable 资源名</b> 认出「影调 / 饱和度 / 冷暖 /
 * 青品 / 锐度 / 暗角 / 柔光」这几项（OPPO 的资源名带 tone / saturat / temperatur / tint /
 * sharp / vignet / soft 等词，按关键词匹配，不写死 id），写入 {@code EffectItem.value}，
 * 再 refreshItem 刷新显示、dispatch 让相机自己把值送进成像链。
 *
 * <p>为什么用资源名而不是顺序：效果栏的项随机型、固件、以及本模块自己插入的项（例如
 * MasterContrast 插的「对比度」）而变，位置不可靠。识别不到的项直接跳过并在结果里说明，
 * 不乱写别的项。
 *
 * <p>核对：action=dump 返回效果栏的真实组成（mode / 序号 / label / icon / value），
 * 设置页「核对效果栏」按钮用它，映射出错时一眼能看出。
 */
public final class MasterPresetApply {
    private static final String TAG = "MusePreset";

    /** 一个 EffectMenu 实例和它的 mode（构造参数最后一个字符串）。 */
    private static final class Menu {
        final Object instance;
        final String mode;

        Menu(Object instance, String mode) {
            this.instance = instance;
            this.mode = mode;
        }
    }

    private static final List<Menu> menus = new CopyOnWriteArrayList<>();
    /** 一条待写入预设的重试链是否正在跑，避免 restore 反复触发堆起来。 */
    private static volatile boolean scheduling;
    /** 队列文件的有效期：用户点了写入之后，只要在这个时间内进大师模式都会生效。 */
    private static final long TTL_MS = 30 * 60 * 1000L;
    /** 待命轮询的间隔。没有队列文件时只多一次 stat，有队列时才开始认效果栏。 */
    private static final long WATCH_MS = 1000L;
    private static volatile boolean watching;
    private static volatile boolean dumped;
    /**
     * 一次性把效果栏实例的类层次和方法表打进日志。
     *
     * <p>为什么需要：dispatch 在这个实例上找不到（日志 "dispatch not found on if.f1"），
     * 而符号表把 EffectMenu 定位到了另一个类（ff.n$b），两边对不上。方法表是唯一能看清
     * 「相机这一版到底把 dispatch 挪到哪去了」的东西；看一次就够，不用反复重启相机。
     */
    private static void dumpMethodsOnce(Object menu) {
        if (dumped) return;
        dumped = true;
        try {
            StringBuilder out = new StringBuilder("menu=").append(menu.getClass().getName()).append('\n');
            for (Class<?> type = menu.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                out.append("== ").append(type.getName()).append('\n');
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    out.append("  ").append(method.getName()).append('(');
                    Class<?>[] params = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) out.append(',');
                        out.append(params[i].getSimpleName());
                    }
                    out.append(")\n");
                }
            }
            String text = out.toString();
            // Log.i 单条有长度上限：按 3000 字符切段，否则后面全被截掉。
            for (int start = 0; start < text.length(); start += 3000) {
                Log.i(TAG, text.substring(start, Math.min(text.length(), start + 3000)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "method dump failed", error);
        }
    }

    /** 连续几轮一个项都没写进去。 */
    private static int failedRounds;

    /**
     * 探针：把效果栏上所有"可能下发一个值"的方法都 hook 住，打印调用与参数。
     *
     * <p>手动拖滑块能让画面真的变化（饱和度拖到 -99，画面当场变黑白），说明下发路径存在，
     * 只是不知道叫哪个名字。拖一次滑块，日志里出现的那几个方法就是答案。
     * 只在 3 分钟内打印，之后自动哑掉，不留在常驻热路径上。
     */
    private static void probeOnce(Object menu) {
        if (probed) return;
        probed = true;
        probeUntil = System.currentTimeMillis() + 600_000L;
        dumpMethodsOnce(menu);
        // 存储层：拖动时到底哪一步把值写进 MMKV。DataManager.put 就是 Prefs.put 内部转调的
        // g(DataKey, Object)，挂上它就能看到相机自己写的是哪些 key、写的什么值。
        Log.guard(TAG, "probe data manager", () -> {
            Symbols symbols = Symbols.get();
            for (String name : new String[] {"DataManager.put", "DataManager.putSync"}) {
                try {
                    XposedBridge.hookMethod(symbols.method(name), new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (System.currentTimeMillis() > probeUntil) return;
                            Log.i(TAG, "probe " + name + java.util.Arrays.toString(param.args));
                        }
                    });
                } catch (Throwable ignored) {
                    // 这个版本没有 putSync 之类的重载，跳过。
                }
            }
        });
        int hooked = 0;
        for (final java.lang.reflect.Method method : menu.getClass().getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            // 不再筛签名：int / DataKey / float 都试过没命中，索性把整个类的方法都挂上。
            // 拖动一次只有几百条，读一遍就知道哪条是下发。
            try {
                if (params.length == 1 && params[0] == int.class) {
                    if ("W2".equals(method.getName())) selectMethod = method;
                    if ("c0".equals(method.getName())) applyMethod = method;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (System.currentTimeMillis() > probeUntil || probeBudget <= 0) return;
                        probeBudget--;
                        Log.i(TAG, "probe " + method.getName() + java.util.Arrays.toString(param.args));
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // 读值的那个 l2(key, 默认值, "professional") 必须看返回值：
                        // 只有它读到的才是真正送进成像链的数，我们写得对不对全看这一行。
                        if (!"l2".equals(method.getName()) || System.currentTimeMillis() > probeUntil) return;
                        Log.i(TAG, "probe l2 -> " + param.getResult() + " for " + param.args[0]);
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 接口/抽象方法 hook 不上，跳过。
            }
        }
        Log.i(TAG, "probe installed: " + hooked + " method(s) on " + menu.getClass().getName());
    }

    private static volatile boolean probed;
    private static volatile long probeUntil;
    private static volatile int probeBudget = 20000;
    /** 拖动实测抓到的两个方法：先选序号，再灌值。 */
    private static volatile java.lang.reflect.Method selectMethod;
    private static volatile java.lang.reflect.Method applyMethod;

    private MasterPresetApply() {}

    /** 记下每个 EffectMenu：大师模式的效果栏由它持有。 */
    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        captureScaleBars(symbols);
        captureItemViews(symbols);
        Log.guard(TAG, "menu construction", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectMenu"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object mode = param.args.length > 0 ? param.args[param.args.length - 1] : null;
                    if (!(mode instanceof String)) return;
                    for (Menu menu : menus) if (menu.instance == param.thisObject) return;
                    Menu menu = new Menu(param.thisObject, (String) mode);
                    menus.add(menu);
                    Log.i(TAG, "effect menu registered: mode=" + mode + " menus=" + menus.size());
                    probeOnce(param.thisObject);
                    // 不按模式名挑：X8U 上大师模式的效果栏 mode 报的是 professional。
                    // 效果栏内容要等布局完成才有，schedulePending 里再认。
                    schedulePending(menu, 0);
                } catch (Throwable error) {
                    Log.e(TAG, "menu registration failed", error);
                }
            }
        }));
        // 效果栏可能整进程只建一次：每次恢复参数（切进大师、切回前台）都再看一眼队列。
        Log.guard(TAG, "restore", () -> XposedBridge.hookMethod(symbols.method("EffectMenuBase.restore"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Menu menu = menuFor(param.thisObject);
                if (menu != null) {
                    dumpMethodsOnce(menu.instance);
                    schedulePending(menu, 0);
                }
            }
        }));
        // 切换模式的实测里 EffectMenu 既没重建、restore 也没走（一条日志都没有），
        // 所以除了这两个点，还要一直待命：队列文件在，就每秒看一眼大师效果栏建好没有。
        startWatch();
    }

    /** 相机把一次拍摄要用的参数都收在 CaptureParams 里；写完整栏的值要让它重收一次。 */
    public static void installCaptureParams(ClassLoader classLoader) {        try {
            XposedBridge.hookAllConstructors(Symbols.get().cls("CaptureParams"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    captureParams = param.thisObject;
                    Log.i(TAG, "capture params ready: " + param.thisObject.getClass().getName());
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "capture params hook failed", error);
        }
    }

    private static volatile Object captureParams;
    private static volatile Object effectData;
    private static volatile boolean presetDumped;
    /** Pro 参数面板（CaptureParamPresenter df.r）：改完 Pro 参数要让面板重读一次。 */
    private static volatile Object proPresenter;

    /**
     * 抓住 Pro 参数面板实例。
     *
     * <p>相机的档位按钮监听器（df.b）里挂着它（字段类型是 df.r）。只在构造时记一下，
     * 不碰相机的档位逻辑：模块不会替用户切档，只在已经处于 Pro 档时让面板重读参数。
     */
    public static void installProPresenter(ClassLoader classLoader) {
        try {
            final Class<?> presenter = de.robv.android.xposed.XposedHelpers.findClass("df.r", classLoader);
            XposedBridge.hookAllConstructors(de.robv.android.xposed.XposedHelpers.findClass("df.b", classLoader),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            for (java.lang.reflect.Field field : param.thisObject.getClass().getDeclaredFields()) {
                                if (!presenter.isAssignableFrom(field.getType())) continue;
                                field.setAccessible(true);
                                Object found = field.get(param.thisObject);
                                if (found == null) continue;
                                if (proPresenter == null) Log.i(TAG, "Pro 参数面板已捕获");
                                proPresenter = found;
                                return;
                            }
                        } catch (Throwable error) {
                            Log.e(TAG, "取 Pro 参数面板失败", error);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "Pro 参数面板 hook 失败（Pro 参数仍会写进数据层）", error);
        }
    }

    /** 已捕获的 Pro 参数面板；不在 Pro 档或还没建好时返回 null。 */
    static Object proPresenter(ClassLoader loader) {
        Object presenter = proPresenter;
        if (presenter == null) return null;
        try {
            // CaptureParamView 的 k 字段就是「当前是不是 Pro 档」。
            Object view = de.robv.android.xposed.XposedHelpers.getObjectField(presenter, "P");
            boolean pro = de.robv.android.xposed.XposedHelpers.getBooleanField(view, "k");
            return pro ? presenter : null;
        } catch (Throwable error) {
            Log.e(TAG, "判断 Pro 档失败", error);
            return null;
        }
    }

    /** 相机的大师参数存在 EffectData 的当前预设里（EffectPresetDataV2）；记下实例备用。 */
    public static void installEffectData(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllConstructors(Symbols.get().cls("EffectData"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    effectData = param.thisObject;
                    Log.i(TAG, "effect data ready: " + param.thisObject.getClass().getName());
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "effect data hook failed", error);
        }
    }

    /**
     * 把当前预设对象的字段名和值打一次。
     *
     * <p>效果栏那些值只活在界面上：改 item.value + I0 能让数字变，画面一动不动。
     * 相机真正拿去做图的是这个预设对象，字段名不知道就没法写，所以先看一次。
     */
    private static void dumpPresetOnce(Symbols symbols) {
        if (presetDumped) return;
        presetDumped = true;
        dumpPreset(symbols);
    }

    /** 当前预设的全部字段：改一个值再转储一次，就能标出哪个字段是哪一项。 */
    private static void dumpPreset(Symbols symbols) {
        Object data = effectData;
        if (data == null) {
            Log.i(TAG, "effect data 还没出现");
            return;
        }
        try {
            Object preset = symbols.call("EffectData.current", data);
            StringBuilder out = new StringBuilder("preset=").append(preset == null ? "null" : preset.getClass().getName()).append('\n');
            if (preset != null) {
                for (Class<?> type = preset.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            out.append("  ").append(field.getName()).append('=').append(field.get(preset)).append('\n');
                        } catch (Throwable error) {
                            out.append("  ").append(field.getName()).append("=?\n");
                        }
                    }
                }
            }
            String text = out.toString();
            for (int start = 0; start < text.length(); start += 3000) {
                Log.i(TAG, text.substring(start, Math.min(text.length(), start + 3000)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "preset dump failed", error);
        }
    }

    /** 预设列表里的自定义项：键、名字、滤镜，用来核对 UI 显示的名字来自哪里。 */
    private static void dumpPresetList() {
        try {
            java.util.List<Object> presets = MasterPresets.list();
            StringBuilder out = new StringBuilder("preset list: " + presets.size() + "\n");
            for (int i = 0; i < presets.size(); i++) {
                Object preset = presets.get(i);
                String key = MasterPresets.key(preset);
                if (key == null || !key.startsWith("custom_")) continue;
                out.append("  [").append(i).append("] ").append(key).append('=').append(MasterPresets.name(preset))
                    .append(" / ").append(MasterPresets.filterType(preset)).append('\n');
            }
            String text = out.toString();
            for (int start = 0; start < text.length(); start += 3000) {
                Log.i(TAG, text.substring(start, Math.min(text.length(), start + 3000)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "preset list dump failed", error);
        }
    }

    /** 把效果栏的新值重新收进 CaptureParams；这是"值只改了界面、画面没动"的那一步。 */
    private static void refreshEffectEntry(Symbols symbols) {
        Object params = captureParams;
        if (params == null) {
            Log.i(TAG, "capture params 还没出现：值只改了界面");
            return;
        }
        try {
            symbols.call("CaptureParams.refreshEffectEntry", params);
            Log.i(TAG, "effect entry refreshed");
        } catch (Throwable error) {
            Log.e(TAG, "refreshEffectEntry failed", error);
        }
    }

    /** 常驻待命：队列文件在就每秒试一次，写成功即删文件，之后只剩一次 stat。 */
    private static void startWatch() {
        if (watching) return;
        watching = true;
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (pendingExists()) {
                        Bundle request = readPending(null);
                        // action=dumppreset：只把当前预设的字段转储一次，不动效果栏。
                        // 标定字段用——改一个值转储一次，前后一对比就知道哪个短名是哪一项。
                        if (request != null && "dumppreset".equals(request.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
                            dumpPreset(Symbols.get());
                            markApplied(request);
                        } else if (request != null && "dumplist".equals(request.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
                            dumpPresetList();
                            markApplied(request);
                        } else if (request != null && "rename".equals(request.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
                            String key = request.getString(DiagnosticsContract.KEY_PRESET_KEY);
                            String name = request.getString(DiagnosticsContract.KEY_PRESET_NAME);
                            boolean done = MasterPresetCreate.renameStored(key, name);
                            Log.i(TAG, "rename via queue: " + done + " " + key + " -> " + name);
                            markApplied(request);
                            deletePending();
                        } else if (request != null && DiagnosticsContract.ACTION_CREATE.equals(request.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
                            // 只建预设：不需要效果栏，所以放在这一支，相机没打开大师效果栏也能落库。
                            String name = request.getString(DiagnosticsContract.KEY_PRESET_NAME);
                            String key = request.getString(DiagnosticsContract.KEY_PRESET_KEY);
                            if (key != null && !key.isEmpty()) {
                                // 带了 presetKey：只往这个现成的预设里补参数（核对用，不新建）。
                                int wrote = MasterPresetCreate.writeValues(key, name,
                                    request.getString(DiagnosticsContract.KEY_PRESET_FILTER_TYPE), request);
                                Log.i(TAG, "fill via queue: " + key + " " + wrote + " item(s)");
                                markApplied(request);
                                deletePending();
                            } else {
                                boolean created = MasterPresetCreate.create(cameraLoader(), name, request);
                                Log.i(TAG, "create via queue: " + created + " " + name);
                                markApplied(request);
                                deletePending();
                            }
                        } else {
                            Menu menu = masterMenu();
                            // Pro 参数不依赖效果栏：效果栏还没建好时也先写进去（重复写是幂等的）。
                            if (request != null && MasterProParams.has(request)) MasterProParams.apply(request);
                            if (menu != null && request != null) apply(menu, request);
                        }
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "preset watch failed", error);
                }
                handler.postDelayed(this, WATCH_MS);
            }
        }, WATCH_MS);
        Log.i(TAG, "preset watch started");
    }

    /** 相机进程的 ClassLoader：模块自己的 loader 加载不到相机类（ClassNotFoundException）。 */
    static ClassLoader cameraLoader() {
        Object data = effectData;
        if (data != null) return data.getClass().getClassLoader();
        try {
            android.app.Application app = android.app.AndroidAppHelper.currentApplication();
            if (app != null) return app.getClassLoader();
        } catch (Throwable ignored) {
            // 下面兜底
        }
        return Thread.currentThread().getContextClassLoader();
    }

    /** 预设列表的管理对象（EffectData）；相机没开大师效果栏时为 null。 */
    static Object effectDataOrNull() {
        return effectData;
    }

    private static Menu menuFor(Object instance) {
        for (Menu menu : menus) if (menu.instance == instance) return menu;
        return null;
    }

    /**
     * 这一项是不是大师模式的调色项：按 drawable 资源名认（影调 / 饱和度 / 冷暖 / 青品 /
     * 锐度 / 暗角 / 柔光）。不按模式名，也不按位置。
     */
    private static boolean isPresetItem(Menu menu, Symbols symbols, Object item) {
        String icon = iconName(menu, symbols, item).toLowerCase(java.util.Locale.ROOT);
        if (icon.isEmpty()) return false;
        return icon.contains("tone") || icon.contains("saturat") || icon.contains("sharp")
            || icon.contains("vignet") || icon.contains("warm") || icon.contains("cold")
            || icon.contains("cyan") || icon.contains("magenta") || icon.contains("tint")
            || icon.contains("soft") || icon.contains("temperatur");
    }

    /**
     * 设置 App 在相机不在大师模式时留下的待写入预设：等效果栏的项目就绪后自己写一次。
     * 写入成功后在相机自己的数据目录记下 nonce，同一个 nonce 不再重放。
     */
    private static void schedulePending(Menu menu, int attempt) {
        if (attempt == 0) {
            if (scheduling || !pendingExists()) return;
            scheduling = true;
        }
        if (attempt > 40) {
            scheduling = false;
            return;
        }
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                boolean done = false;
                try {
                    if (!items(Symbols.get(), menu.instance).isEmpty()) {
                        done = true;
                        // 只对真的大师效果栏动手：别的模式的效果栏（滤镜、美颜）不碰。
                        if (presetItemCount(menu) >= 4) {
                            Bundle pending = readPending(menu);
                            if (pending != null) apply(menu, pending);
                        }
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "pending preset failed", error);
                    done = true;
                }
                if (done) {
                    scheduling = false;
                    return;
                }
                schedulePending(menu, attempt + 1);
            }, attempt == 0 ? 300L : 250L);
        } catch (Throwable error) {
            scheduling = false;
            Log.e(TAG, "pending preset scheduling failed", error);
        }
    }

    /** 队列文件存在才值得排重试链：restore 会反复调用。 */
    private static boolean pendingExists() {
        try {
            File dir = settingsDir();
            if (dir == null) return false;
            File file = new File(dir, DiagnosticsContract.PENDING_FILE);
            if (!file.isFile()) return false;
            // 过期就删掉：不然每秒都要 stat 一个永远不会生效的文件。
            if (System.currentTimeMillis() - file.lastModified() > TTL_MS) {
                if (file.delete()) Log.i(TAG, "stale pending dropped");
                return false;
            }
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 读设置目录里的待写入预设；没有、太旧、或这次已经写过了就返回 null。 */
    private static Bundle readPending(Menu menu) {        try {
            File dir = settingsDir();
            if (dir == null) return null;
            File file = new File(dir, DiagnosticsContract.PENDING_FILE);
            if (!file.isFile()) return null;
            // TTL 之前的请求视为过期：用户早就换目标了，不该突然把相机改掉。
            if (System.currentTimeMillis() - file.lastModified() > TTL_MS) return null;
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            Bundle extras = new Bundle();
            String nonce = "";
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("nonce".equals(key)) {
                    nonce = value;
                    continue;
                }
                if (DiagnosticsContract.KEY_PRESET_FILTER.equals(key) || DiagnosticsContract.KEY_PRESET_SOFT_LIGHT.equals(key)
                    || DiagnosticsContract.KEY_PRESET_VIGNETTE.equals(key)
                    || DiagnosticsContract.KEY_PRESET_NAME.equals(key)
                    || DiagnosticsContract.KEY_PRESET_KEY.equals(key)
                    || DiagnosticsContract.KEY_PRESET_FILTER_TYPE.equals(key)
                    || DiagnosticsContract.KEY_PRESET_ISO.equals(key)
                    || DiagnosticsContract.KEY_PRESET_SHUTTER.equals(key)
                    || DiagnosticsContract.KEY_PRESET_EV.equals(key)
                    || DiagnosticsContract.KEY_PRESET_FOCUS.equals(key)
                    || DiagnosticsContract.KEY_PRESET_WHITE_BALANCE.equals(key)
                    || DiagnosticsContract.KEY_PRESET_ACTION.equals(key)) {
                    extras.putString(key, value);
                } else if (DiagnosticsContract.KEY_PRESET_CREATE.equals(key)) {
                    extras.putBoolean(key, Boolean.parseBoolean(value));
                } else {
                    try {
                        extras.putInt(key, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        // 不是数字的键忽略。
                    }
                }
            }
            if (nonce.isEmpty()) return null;
            if (nonce.equals(appliedNonce())) return null;
            extras.putString("nonce", nonce);
            Log.i(TAG, "pending preset found: " + nonce);
            return extras;
        } catch (Throwable error) {
            Log.e(TAG, "pending preset unreadable", error);
            return null;
        }
    }

    /** 相机数据目录里记的「已经写过的那次请求」。 */
    private static String appliedNonce() {
        try {
            Context context = appContext();
            if (context == null) return "";
            File file = new File(context.getFilesDir(), "jiege/" + DiagnosticsContract.APPLIED_FILE);
            if (!file.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable error) {
            return "";
        }
    }

    private static void markApplied(Bundle extras) {
        Object nonce = extras == null ? null : extras.getString("nonce");
        if (!(nonce instanceof String) || ((String) nonce).isEmpty()) return;
        try {
            Context context = appContext();
            if (context == null) return;
            File dir = new File(context.getFilesDir(), "jiege");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.nio.file.Files.write(new File(dir, DiagnosticsContract.APPLIED_FILE).toPath(),
                ((String) nonce).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable error) {
            Log.e(TAG, "applied marker not written", error);
        }
    }

    private static Context appContext() {
        try {
            return android.app.AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 设置 App 的偏好目录（xposedsharedprefs 下与 module_settings 同一个目录）。 */
    private static File settingsDir() {
        try {
            File file = new de.robv.android.xposed.XSharedPreferences(DiagnosticsContract.MODULE_PACKAGE,
                local.jiege.settings.ModuleSettings.FILE).getFile();
            return file == null ? null : file.getParentFile();
        } catch (Throwable error) {
            Log.e(TAG, "settings directory unavailable", error);
            return null;
        }
    }

    /** 设置 App 调进来后最多等多久（它自己 6 秒就放弃，这里留一点余量）。 */
    private static final long CALL_WAIT_MS = 4500L;

    /**
     * 应答设置 App 的调用；永远返回 Bundle，失败原因放在 KEY_PRESET_RESULT。
     *
     * <p>必须切到相机主线程再干活：设置页是跨进程调过来的，ContentProvider 的
     * {@code call} 跑在 binder 线程池里，那里碰 {@code EffectItem} / 刻度条这些 View 会抛
     * "Only the original thread that created a view hierarchy can touch its views"。
     * 每项都被打回退路，退路只验证「方法都调到了」，于是回一句「已应用 N 项」而界面一动不动
     * —— 用户看到的就是「点了写入，进大师模式什么都没有」。
     */
    public static Bundle handle(Bundle extras) {
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (android.os.Looper.myLooper() == main) return handleOnMain(extras);
        final Bundle[] slot = new Bundle[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean posted;
        try {
            posted = new android.os.Handler(main).post(new Runnable() {
                @Override public void run() {
                    try {
                        slot[0] = handleOnMain(extras);
                    } catch (Throwable error) {
                        Log.e(TAG, "preset call failed", error);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "preset call not posted", error);
            posted = false;
        }
        if (posted) {
            try {
                latch.await(CALL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (slot[0] != null) return slot[0];
        Bundle out = new Bundle();
        out.putString(DiagnosticsContract.KEY_PRESET_RESULT, "相机正忙，预设已排队，进大师模式后自动写入");
        out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 0);
        return out;
    }

    private static Bundle handleOnMain(Bundle extras) {
        Bundle out = new Bundle();
        String action = extras == null ? DiagnosticsContract.ACTION_DUMP
            : extras.getString(DiagnosticsContract.KEY_PRESET_ACTION, DiagnosticsContract.ACTION_DUMP);
        try {
            // 核对不要求效果栏已经认出来：全部菜单都要能看，否则映射出问题时没处查。
            if (DiagnosticsContract.ACTION_DUMP.equals(action)) {
                out.putString(DiagnosticsContract.KEY_PRESET_ITEMS, dumpAll());
                out.putString(DiagnosticsContract.KEY_PRESET_RESULT, "");
                out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 0);
                return out;
            }
            if (DiagnosticsContract.ACTION_PRO_READ.equals(action)) {
                // 只读相机当前的 Pro 参数：设置页用它把「相机现在的 ISO / 快门 / EV / 对焦 / 白平衡」
                // 抄进预设里，值格式原样保留，模块不解释。
                out.putString(DiagnosticsContract.KEY_PRO_RESULT, MasterProParams.read());
                out.putString(DiagnosticsContract.KEY_PRESET_RESULT, "");
                out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 0);
                return out;
            }
            Menu menu = masterMenu();
            if (DiagnosticsContract.ACTION_CREATE.equals(action)) {
                // 只建预设、不碰效果栏：效果栏没打开时也能核对落库这一段。
                String name = extras.getString(DiagnosticsContract.KEY_PRESET_NAME);
                String key = extras.getString(DiagnosticsContract.KEY_PRESET_KEY);
                if (key != null && !key.isEmpty()) {
                    int wrote = MasterPresetCreate.writeValues(key, name,
                        extras.getString(DiagnosticsContract.KEY_PRESET_FILTER_TYPE), extras);
                    out.putString(DiagnosticsContract.KEY_PRESET_RESULT, "已补参数：" + key + " " + wrote + " 项");
                    out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, wrote);
                    return out;
                }
                boolean created = MasterPresetCreate.create(cameraLoader(), name, extras);
                out.putString(DiagnosticsContract.KEY_PRESET_RESULT,
                    created ? "已新建预设：" + name : "没有新建：" + name);
                out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, created ? 1 : 0);
                return out;
            }
            if (menu == null) {
                // 效果栏没建（相机不在大师模式、或切后台后收起了）也不耽误落预设：
                // 写预设存储本身不需要效果栏，这样用户点完「写入」预设里立刻就有参数，
                // 不用先切回相机把效果栏拉出来。效果栏那一步仍留着排队，进大师模式时补上。
                String createdName = null;
                if (extras.getBoolean(DiagnosticsContract.KEY_PRESET_CREATE, false)
                    && extras.getString(DiagnosticsContract.KEY_PRESET_NAME) != null) {
                    String name = extras.getString(DiagnosticsContract.KEY_PRESET_NAME);
                    if (MasterPresetCreate.create(cameraLoader(), name, extras)) createdName = name;
                }
                // Pro 参数（ISO / 快门 / EV / 对焦 / 白平衡）不依赖效果栏：数据层写进去后，
                // 相机进 Pro 档就会用上，所以这一支也要写。
                if (MasterProParams.has(extras)) MasterProParams.apply(extras);
                // 设置 App 已经把参数排进待写入文件：进大师模式时效果栏一建好就会写进去。
                out.putString(DiagnosticsContract.KEY_PRESET_RESULT,
                    createdName != null
                        ? "预设「" + createdName + "」已写入（带参数）；相机的大师效果栏打开后会自动同步"
                        : DiagnosticsContract.ACTION_APPLY.equals(action)
                            ? "已排队，切到相机的大师模式后自动写入"
                            : "大师效果栏还没建好：先打开相机并切到大师模式，再回来核对");
                // 故意不报 applied：队列要留着，等效果栏建好时把同一套值也写进画面。
                out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 0);
                return out;
            }
            // Pro 参数先写：效果栏那一步失败也不影响它们，用户至少拿得到拍摄参数。
            if (MasterProParams.has(extras)) MasterProParams.apply(extras);
            return apply(menu, extras);
        } catch (Throwable error) {
            Log.e(TAG, "preset call failed", error);
            out.putString(DiagnosticsContract.KEY_PRESET_RESULT, "应用失败：" + error);
            out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 0);
            return out;
        }
    }

    /** 效果栏里认得出的调色项数量：用来判断这个菜单是不是大师效果栏。 */
    private static int presetItemCount(Menu menu) {
        Symbols symbols = Symbols.get();
        int count = 0;
        try {
            for (Object item : items(symbols, menu.instance)) {
                if (isPresetItem(menu, symbols, item)) count++;
            }
        } catch (Throwable error) {
            Log.e(TAG, "effect bar probe failed", error);
        }
        return count;
    }

    /**
     * 大师拍照的效果栏。不认 mode 字符串（X8U 上它报 professional），改成认内容：
     * 取拥有最多调色项的菜单；同一份列表里若有两个都像，取后建的那个（切模式会重建）。
     */
    private static Menu masterMenu() {
        Menu found = null;
        int best = 0;
        for (Menu menu : menus) {
            int count = presetItemCount(menu);
            if (count < 4 || count < best) continue;
            found = menu;
            best = count;
        }
        return found;
    }

    /**
     * 打印刻度条和它监听器的内部数字：换算「参数值 ↔ 刻度索引」靠这组数，不靠猜。
     *
     * <p>为什么要看：{@code L(IZ)} 的第一个参数给的是索引而不是值，条上的总刻度数与
     * 「值域最小值对应哪个索引」决定了负值该换算成多少。条只会把索引钳进范围，钳不进去就
     * 静默丢弃——这正是负值「拖了没反应」的原因。
     */
    private static void dumpBar(Symbols symbols, Menu menu) {
        try {
            Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            Log.i(TAG, "bar " + (bar == null ? "null" : bar.getClass().getName()));
            if (bar != null) dumpFields(bar, "bar");
            Object listener = null;
            synchronized (bars) {
                if (bar != null) listener = bars.get(bar);
            }
            Log.i(TAG, "listener " + (listener == null ? "null" : listener.getClass().getName()));
            if (listener != null) {
                dumpFields(listener, "listener");
                for (java.lang.reflect.Method method : listener.getClass().getDeclaredMethods()) {
                    Log.i(TAG, "listener method " + method.getName()
                        + java.util.Arrays.toString(method.getParameterTypes()));
                }
            }
            List<?> list = items(symbols, menu.instance);
            for (int i = 0; i < list.size(); i++) {
                Log.i(TAG, "item " + i + " " + displayName(menu, symbols, list.get(i))
                    + " = " + symbols.get("EffectItem.value", list.get(i))
                    + " icon=" + iconName(menu, symbols, list.get(i)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "bar dump failed", error);
        }
    }

    /** 把一个对象的标量字段全打出来，按 3000 字符切段（Log.i 单条有长度上限）。 */
    private static void dumpFields(Object owner, String prefix) {
        StringBuilder out = new StringBuilder();
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                Class<?> kind = field.getType();
                if (kind != int.class && kind != float.class && kind != boolean.class
                    && kind != long.class && kind != String.class) continue;
                try {
                    field.setAccessible(true);
                    out.append(prefix).append('.').append(field.getName()).append('=')
                        .append(field.get(owner)).append(' ');
                } catch (Throwable ignored) {
                    // 静态/半初始化字段读不了，跳过。
                }
            }
        }
        String text = out.toString();
        for (int start = 0; start < text.length(); start += 3000) {
            Log.i(TAG, text.substring(start, Math.min(text.length(), start + 3000)));
        }
    }

    /**
     * 标定：把条逐点推到候选索引，记下影调那一格变成的值，反推索引与值的换算。
     *
     * <p>探测值取 0/25/.../200：条能接受的最大索引就是总刻度数，超出会静默丢弃，
     * 打出来的「第几个索引开始不再变化」就是范围右端。
     */
    private static void mapBar(Symbols symbols, Menu menu) {
        Object bar = null;
        Object target = null;
        List<?> list;
        try {
            bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            list = items(symbols, menu.instance);
            if (bar == null || list.size() < 4) {
                Log.i(TAG, "map: no bar or too few items");
                return;
            }
            target = list.get(3);
            Log.i(TAG, "map start: " + displayName(menu, symbols, target)
                + " a=" + intField(bar, "a") + " J0=" + intField(bar, "J0")
                + " n1=" + intField(bar, "n1") + " i1=" + intField(bar, "i1"));
        } catch (Throwable error) {
            Log.e(TAG, "map start failed", error);
            return;
        }
        int[] indexes = {0, 25, 50, 75, 100, 125, 150, 175, 200};
        stepMap(symbols, menu, bar, target, indexes, 0);
    }

    private static void stepMap(final Symbols symbols, final Menu menu, final Object bar,
                                final Object target, final int[] indexes, final int position) {
        if (position >= indexes.length) {
            Log.i(TAG, "map done");
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                // 必须先把「当前选中第几项」钉在标定目标上。监听器 g() 只认 menu.j，
                // 不写它就沿用界面上选中的那一项——实测会一路写到暗角（开关项）上去，
                // 每写一次相机抖一下，看起来就是手机在不停振动。
                java.lang.reflect.Field selected = intFieldNamed(menu.instance, "j");
                if (selected != null) selected.setInt(menu.instance, 3);
                java.lang.reflect.Method bind = findMethod(menu.instance, "W2", 1);
                if (bind != null) bind.invoke(menu.instance, 3);
                java.lang.reflect.Method set = findMethod(bar, "L", 2);
                if (set == null) {
                    Log.i(TAG, "map: no L(int,boolean)");
                    return;
                }
                set.invoke(bar, indexes[position], true);
            } catch (Throwable error) {
                Log.e(TAG, "map push failed", error);
            }
            // L 把通知投给 ThreadManager，异步的；等它跑完再读格子里的值。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Object now = null;
                try {
                    now = symbols.get("EffectItem.value", target);
                } catch (Throwable ignored) {
                    // 读不到就按未知记。
                }
                Log.i(TAG, "map idx " + indexes[position] + " -> item value " + now
                    + " a=" + intField(bar, "a"));
                stepMap(symbols, menu, bar, target, indexes, position + 1);
            }, 280L);
        }, position == 0 ? 0L : 140L);
    }

    private static int intField(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(owner);
                return value instanceof Integer ? (Integer) value : Integer.MIN_VALUE;
            } catch (Throwable ignored) {
                // 这一层没有，继续往上找。
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 一次性标定：把刻度条上所有可能"设一个值"的入口都试一遍，看哪个能把值写进
     * {@code EffectItem.value}。L(int,boolean) 是正解（索引 = 值 + 100），其余入口留作对照。
     */
    private static void sweepBars(Symbols symbols, Menu menu, int index) {
        try {
            Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            List<?> list = items(symbols, menu.instance);
            if (bar == null || index < 0 || index >= list.size()) return;
            Object target = list.get(index);
            java.lang.reflect.Method bind = findMethod(menu.instance, "W2", 1);
            int[] values = {-99, -50, 50};
            for (int value : values) {
                for (String name : new String[] {"L", "G", "K", "N", "r", "u", "w", "x", "y"}) {
                    java.lang.reflect.Method method = findMethod(bar, name, 2);
                    Object[] args = {value, false};
                    if (method == null) {
                        method = findMethod(bar, name, 1);
                        args = new Object[] {value};
                    }
                    if (method == null) continue;
                    if (bind != null) bind.invoke(menu.instance, index);
                    try {
                        if (method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == float.class) {
                            method.invoke(bar, (float) value);
                        } else if (method.getParameterTypes().length == 2
                            && method.getParameterTypes()[0] == float.class) {
                            method.invoke(bar, (float) value, false);
                        } else {
                            method.invoke(bar, args);
                        }
                    } catch (Throwable error) {
                        Log.i(TAG, "sweep " + name + "(" + value + ") threw " + error.getCause());
                        continue;
                    }
                    if (bind != null) bind.invoke(menu.instance, index);
                    Object now = symbols.get("EffectItem.value", target);
                    Log.i(TAG, "sweep " + name + "(" + value + ") -> item value " + now);
                }
                for (String name : new String[] {"p", "z"}) {
                    java.lang.reflect.Method method = findMethod(bar, name, 2);
                    if (method == null) method = findMethod(bar, name, 1);
                    if (method == null) continue;
                    if (bind != null) bind.invoke(menu.instance, index);
                    try {
                        if (method.getParameterTypes().length == 1) method.invoke(bar, (float) value);
                        else method.invoke(bar, (float) value, false);
                    } catch (Throwable error) {
                        Log.i(TAG, "sweep " + name + "(" + value + ") threw " + error.getCause());
                        continue;
                    }
                    if (bind != null) bind.invoke(menu.instance, index);
                    Log.i(TAG, "sweep " + name + "(" + value + ") -> item value "
                        + symbols.get("EffectItem.value", target));
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "sweep failed", error);
        }
    }


    private static Bundle apply(Menu menu, Bundle extras) throws Throwable {
        Symbols symbols = Symbols.get();
        dumpMethodsOnce(menu.instance);
        List<?> items = items(symbols, menu.instance);
        if (extras != null && "barsweep".equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
            sweepBars(symbols, menu, extras.getInt(DiagnosticsContract.KEY_PRESET_SATURATION, 5));
            Bundle done = new Bundle();
            done.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 1);
            return done;
        }
        if (extras != null && "bardump".equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
            dumpBar(symbols, menu);
            Bundle done = new Bundle();
            done.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 1);
            return done;
        }
        if (extras != null && "barprobe".equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
            mapBar(symbols, menu);
            Bundle done = new Bundle();
            done.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 1);
            return done;
        }
        if (extras != null && "barfind".equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
            findBars(symbols, menu);
            markApplied(extras);
            deletePending();
            Bundle done = new Bundle();
            done.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 1);
            return done;
        }
        if (extras != null && "barpush".equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) {
            // 诊断：只走刻度条自己（L(索引,true)），看条的数字 / 指示块 / 格子数字能不能三处一致。
            pushBar(symbols, menu, extras.getInt("item", -1), extras.getInt("index", 100));
            markApplied(extras);
            deletePending();
            Bundle done = new Bundle();
            done.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, 1);
            return done;
        }
        Bundle out = new Bundle();
        int applied = 0;
        StringBuilder missed = new StringBuilder();
        // 界面上此刻选中的是哪一项：只有这一项能安全地同步刻度条（条是共用的一根）。
        int onScreen = intField(menu.instance, "j");
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            Integer value = valueFor(menu, symbols, item, extras);
            // 逐项记账：哪一项认到了、哪一项没认到，调不出来时就看这一行。
            Log.i(TAG, "item " + i + " " + displayName(menu, symbols, item)
                + " icon=" + iconName(menu, symbols, item) + " -> " + value);
            if (value == null) continue;
            if (write(symbols, menu, item, i, value, i == onScreen)) applied++;
            else missed.append(displayName(menu, symbols, item)).append(' ');
        }
        // 别的项写值时会顺带把刻度条重画成「它们自己的值」，于是界面上正显示那一项的条
        // 又变回 0。所以全部写完之后，按这一项的真实值再定位一次（只定位，不再写值）。
        if (onScreen >= 0) resyncBar(symbols, menu, onScreen);
        out.putInt(DiagnosticsContract.KEY_PRESET_APPLIED, applied);
        out.putString(DiagnosticsContract.KEY_PRESET_ITEMS, dump(menu));
        out.putString(DiagnosticsContract.KEY_PRESET_RESULT,
            missed.length() == 0 ? "" : "以下项目没有写入，需在相机里手动调：" + missed.toString().trim());
        if (applied > 0) {
            markApplied(extras);
            refreshEffectEntry(symbols);
            dumpPresetOnce(symbols);
            recheckLater(extras);
            // 这一版相机没有预设里写的那个滤镜：参数已经写到「标准」上了，再落成一个
            // 以预设名命名的自定义预设，用户才能在预设列表里选到它。
            createLater(extras);
            // 写成功即收工：轮询靠这个文件的存在与否决定要不要干活。
            deletePending();
            failedRounds = 0;
        } else if (++failedRounds >= 5) {
            // 连试五轮一个都没写进去，多半是这一版相机的项认不出来：别继续刷。
            deletePending();
            failedRounds = 0;
            Log.e(TAG, "preset not written, queue dropped", new Throwable());
        }
        Log.i(TAG, "preset applied: " + applied + " item(s)" + (missed.length() > 0 ? ", missed " + missed : ""));
        return out;
    }

    /**
     * 写完 1.6 秒后新建自定义预设：必须在补写之后，否则预设里存的是补写前的旧值。
     *
     * <p>只在设置侧标了「本机没有这个滤镜」时做，名字用预设库里的名字。同一台相机上连写两次
     * 不会建出两个同名预设（相机自己也不允许重名，这里先挡一道）。
     */
    private static void createLater(final Bundle extras) {
        if (extras == null) return;
        if (!extras.getBoolean(DiagnosticsContract.KEY_PRESET_CREATE, false)) return;
        final String name = extras.getString(DiagnosticsContract.KEY_PRESET_NAME);
        if (name == null || name.isEmpty()) return;
        final Object data = effectData;
        if (data == null) {
            Log.i(TAG, "effect data 还没出现，跳过新建预设");
            return;
        }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (name.equals(createdName) && System.currentTimeMillis() - createdAt < 8000L) return;
                        // 先落盘（相机重启后靠它重建预设列表），再让当前会话立刻多出这一项。
                        if (MasterPresetCreate.create(data.getClass().getClassLoader(), name, extras)) {
                            createdName = name;
                            createdAt = System.currentTimeMillis();
                            Log.i(TAG, "预设已落库：" + name);
                            // 建完立刻把相机眼里的预设列印一遍：明天核对「建了看不见」时，
                            // 这条日志直接说明是没落库、还是落了但列表没刷新。
                            dumpPresetList();
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "新建预设失败", error);
                    }
                }
            }, 1600L);
        } catch (Throwable error) {
            Log.e(TAG, "新建预设没排上", error);
        }
    }

    private static String createdName;
    private static long createdAt;

    /**
     * 写完 1.2 秒再写一遍。
     *
     * <p>为什么需要：相机冷启动、或从后台切回前台时，{@code EffectMenuBase.restore} 会把
     * 持久化下来的值搬回效果栏。这个动作完全可能落在我们写入之后，把刚写好的每一项盖回去 ——
     * 日志里是漂亮的「preset applied: 7 item(s)」，界面上却一个数都没动。第二遍写在 restore
     * 之后的稳态里，才能把值压住。写本身是幂等的，重复一次不会改坏任何东西。
     */
    private static void recheckLater(final Bundle extras) {
        if (extras == null) return;
        if (!DiagnosticsContract.ACTION_APPLY.equals(extras.getString(DiagnosticsContract.KEY_PRESET_ACTION))) return;
        if ("1".equals(extras.getString("recheck"))) return;
        final Bundle again = new Bundle(extras);
        again.putString("recheck", "1");
        // 队列来的请求：设置 App 主动清了队列就当用户改主意了，不再补。
        final boolean fromQueue = again.getString("nonce") != null;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (fromQueue && !pendingExists()) return;
                        Menu target = masterMenu();
                        if (target == null) return;
                        Log.i(TAG, "recheck: 再写一遍，防 restore 覆盖");
                        apply(target, again);
                    } catch (Throwable error) {
                        Log.e(TAG, "preset recheck failed", error);
                    }
                }
            }, 1200L);
        } catch (Throwable error) {
            Log.e(TAG, "preset recheck not scheduled", error);
        }
    }

    /**
     * 像人一样逐项操作：点中这一格 → 等界面把刻度条绑好 → 把值交给刻度条监听器。
     *
     * <p>为什么必须异步：点击是走 View 的点击链，选中下标要等下一帧才写进去；
     * 同步调监听器会拿到 -1 的下标（日志 "Index -1 out of bounds for length 11"）。
     * 一项接一项地排队，间隔 300ms，效果栏跟着一格一格跳到目标值——和手动拖过去等价。
     */
    private static void uiApply(Menu menu, Bundle extras) {
        final Symbols symbols = Symbols.get();
        final List<Object> pending = new ArrayList<>();
        try {
            for (Object item : items(symbols, menu.instance)) {
                Integer value = valueFor(menu, symbols, item, extras);
                if (value != null) pending.add(new Object[] {item, value});
            }
        } catch (Throwable error) {
            Log.e(TAG, "ui apply listing failed", error);
            return;
        }
        step(menu, pending, 0, extras);
    }

    private static void step(final Menu menu, final List<Object> pending, final int position, final Bundle extras) {
        if (position >= pending.size()) {
            Log.i(TAG, "ui apply finished: " + pending.size() + " item(s)");
            if (!pending.isEmpty()) markApplied(extras);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Symbols symbols = Symbols.get();
                Object[] entry = (Object[]) pending.get(position);
                Object item = entry[0];
                int value = (Integer) entry[1];
                Object view = viewOf(symbols, item);
                if (view instanceof android.view.View) ((android.view.View) view).performClick();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Symbols inner = Symbols.get();
                        Object bar = inner.get("EffectMenuBase.seekBar", menu.instance);
                        Object listener = null;
                        synchronized (bars) {
                            listener = bar == null ? null : bars.get(bar);
                        }
                        java.lang.reflect.Method changed = listener == null ? null : valueChanged(listener);
                        if (changed != null) {
                            changed.invoke(listener, value);
                            Log.i(TAG, "ui apply " + value + " -> item value "
                                + inner.get("EffectItem.value", item));
                        } else {
                            Log.e(TAG, "ui apply: no listener for " + value, null);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "ui apply value failed: " + error.getCause(), error.getCause());
                    }
                    step(menu, pending, position + 1, extras);
                }, 300L);
            } catch (Throwable error) {
                Log.e(TAG, "ui apply step failed", error);
                step(menu, pending, position + 1, extras);
            }
        }, position == 0 ? 0L : 150L);
    }

    /** 把队列文件删掉：写过了就不再重放，也免得轮询每秒 stat 一个死文件。 */
    private static volatile String lastUndeletable;

    private static void deletePending() {
        try {
            File dir = settingsDir();
            if (dir == null) return;
            File file = new File(dir, DiagnosticsContract.PENDING_FILE);
            if (!file.isFile()) return;
            if (file.delete()) return;
            // 相机进程（u0_a158）不是这个 prefs 目录的属主，删不掉是常态；
            // 设置 App 收到「已应用」后会自己 clearQueue，所以这里不当错误刷日志。
            String stamp = file.length() + "@" + file.lastModified();
            if (!stamp.equals(lastUndeletable)) {
                lastUndeletable = stamp;
                Log.i(TAG, "pending file left for the settings app to clear");
            }
        } catch (Throwable error) {
            Log.e(TAG, "pending delete failed", error);
        }
    }

    /** 按资源名把预设字段映射到这一项；返回 null 表示这一项不归预设管。 */
    private static Integer valueFor(String icon, Bundle extras) {
        if (icon.isEmpty()) return null;
        if (icon.contains("tone") && !icon.contains("contrast")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_TONE);
        if (icon.contains("filter_intensity") || icon.contains("intensity")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH);
        if (icon.contains("soft_light") || icon.contains("softlight")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_SOFT_LIGHT);
        if (icon.contains("saturat")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_SATURATION);
        if (icon.contains("temperatur") || icon.contains("warm")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_WARM_COOL);
        if (icon.contains("tint") || icon.contains("magenta") || icon.contains("cyan")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_CYAN_MAGENTA);
        if (icon.contains("sharp")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_SHARPNESS);
        if (icon.contains("vignet")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_VIGNETTE);
        return null;
    }

    /**
     * 按界面文案认项，认不出来再退回资源名。
     *
     * <p>为什么不能只靠资源名：影调的原厂图标资源叫 master_contrast_icon（半黑半白圆），
     * 名字里带的是 contrast 而不是 tone；而模块插进去的对比度用的是 GR 的
     * gr_mode_effect_tone_icon，名字里反而带 tone。只按资源名认，两项会正好对调——
     * 影调被跳过、对比度被当成影调写。中文文案是唯一不会串的标识。
     */
    private static Integer valueFor(Menu menu, Symbols symbols, Object item, Bundle extras) {
        String label = displayName(menu, symbols, item);
        if (label.contains("对比度")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_MUSE_CONTRAST);
        if (label.contains("重置")) return null;
        if (label.contains("滤镜强度")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_FILTER_STRENGTH);
        if (label.contains("柔光")) return softLightLevel(extras);
        if (label.contains("影调")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_TONE);
        if (label.contains("饱和度")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_SATURATION);
        if (label.contains("冷暖") || label.contains("色温")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_WARM_COOL);
        if (label.contains("青品")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_CYAN_MAGENTA);
        if (label.contains("锐度")) return intOrNull(extras, DiagnosticsContract.KEY_PRESET_SHARPNESS);
        if (label.contains("暗角")) return vignetteValue(extras);
        return valueFor(iconName(menu, symbols, item).toLowerCase(java.util.Locale.ROOT), extras);
    }

    /**
     * 柔光不是 0–100 的连续量，是四档：0 无、1 弱（柔美）、2 中（朦胧）、3 强（梦幻）。
     *
     * <p>预设库里存的是文字，所以这里两种都收：文字按档名认，数字按档位认
     * （有人把 0–100 的强度写成数字时，按三等分折算到 1–3，0 仍然是「无」）。
     */
    private static Integer softLightLevel(Bundle extras) {
        if (extras == null || !extras.containsKey(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT)) return null;
        Object raw = extras.get(DiagnosticsContract.KEY_PRESET_SOFT_LIGHT);
        if (raw instanceof Integer) return clampLevel((Integer) raw);
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        if (text.contains("无") || text.equals("0") || text.contains("off") || text.contains("none")) return 0;
        if (text.contains("弱") || text.contains("柔美") || text.contains("low") || text.contains("weak")) return 1;
        if (text.contains("中") || text.contains("朦胧") || text.contains("medium")) return 2;
        if (text.contains("强") || text.contains("梦幻") || text.contains("high") || text.contains("strong")) return 3;
        try {
            return clampLevel(Integer.parseInt(text));
        } catch (NumberFormatException error) {
            Log.i(TAG, "soft light value not understood: " + text);
            return null;
        }
    }

    /** 0–3 原样；更大的数字当作 0–100 的强度三等分。 */
    private static int clampLevel(int value) {
        if (value <= 3) return Math.max(0, value);
        return Math.max(1, Math.min(3, (value + 33) / 34));
    }

    /** 暗角是开关，不是强度：相机里「开」存的是 101，「关」是 0。 */
    private static final int VIGNETTE_ON = 101;

    /**
     * 暗角：界面上只有开 / 关，没有强度。
     *
     * <p>实测（20:28 那次 barprobe 的副产品）：手动点开暗角后，MMKV 里
     * {@code key_master_mode_effect_vignette} 读回来是 <b>101</b>，往这个键写 0 / 25 / 50
     * 都读回 101——写进去的强度值被这个开关语义吃掉了。所以写 90、100 时界面一直显示「关」，
     * 画面也只是噪声级的差异（角/中心 0.913 → 0.869）。
     *
     * <p>预设库里这一项存的是文字「开 / 关」，两种写法都收。
     */
    private static Integer vignetteValue(Bundle extras) {
        if (extras == null || !extras.containsKey(DiagnosticsContract.KEY_PRESET_VIGNETTE)) return null;
        Object raw = extras.get(DiagnosticsContract.KEY_PRESET_VIGNETTE);
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        if (text.contains("关") || text.equalsIgnoreCase("off") || text.equals("0")) return 0;
        if (text.contains("开") || text.equalsIgnoreCase("on")) return VIGNETTE_ON;
        try {
            return Integer.parseInt(text) > 0 ? VIGNETTE_ON : 0;
        } catch (NumberFormatException error) {
            Log.i(TAG, "vignette value not understood: " + text);
            return null;
        }
    }

    /** 没下发的键不下发 null，保持相机原值。 */
    private static Integer intOrNull(Bundle extras, String key) {
        if (extras == null || !extras.containsKey(key)) return null;
        int value = extras.getInt(key);
        return Math.max(-100, Math.min(100, value));
    }

    /**
     * 写一项：value → 刷新显示 → dispatch，让相机自己把值存进预设并送进成像链。
     *
     * <p>dispatch 不走符号表：symbols 把它定位到了 ff.n$b 而手里是 if.f1，反射直接抛
     * "Expected receiver of type ff.n$b, but got if.f1"。改成在这个菜单自己的类层次里找
     * {@code dispatch(int, DataKey)}，拿到的必然是能调的那个。
     */
    private static boolean write(Symbols symbols, Menu menu, Object item, int index, int value, boolean syncBar) {
        try {
            // 和手指一模一样的两步：先把选中项指到这一项，再把值交给刻度条监听器。
            // 只写 EffectItem.value / 只写存储 / 只调 I0 都只改界面数字，画面一动不动。
            if (dragByListener(symbols, menu, item, index, value, syncBar)) return true;
            // 退一步：直接推刻度条，再让菜单写一遍。
            if (driveSeekBar(symbols, menu, index, value)) return true;
            symbols.set("EffectItem.value", item, value);
            try {
                symbols.call("EffectMenuBase.refreshItem", menu.instance, index);
            } catch (Throwable ignored) {
                // 老版本没有 refreshItem：值仍然写进去了，只是图标上的数字要等下一次重绘。
            }
            Object key = symbols.get("EffectItem.key", item);
            // 退路：复刻相机的拖动链。只写 EffectItem.value 会改界面数字，但画面不动；
            // 拖动的完整序列是 W2×2 → 落盘 → I0 → k2 → K，缺哪步都不生效。
            if (replayDrag(menu.instance, index, key, value)) return true;
            Log.e(TAG, "no way to apply " + displayName(menu, symbols, item) + " on " + menu.instance.getClass().getName(), new Throwable());
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "write failed for " + displayName(menu, symbols, item), error);
            return false;
        }
    }

    /**
     * 选中这一项，把值交给刻度条监听器——GR 模式驱动参数用的就是这个套路
     * （{@code GrEffectValues.set}：先 select 走相机自己的点击路径，再 {@code drag} 回调）。
     */
    private static boolean dragByListener(Symbols symbols, Menu menu, Object item, int index, int value, boolean syncBar) {
        try {
            Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            if (bar == null) return false;
            Object listener;
            synchronized (bars) {
                listener = bars.get(bar);
            }
            if (listener == null) return false;
            java.lang.reflect.Method changed = valueChanged(listener);
            if (changed == null) return false;
            // 监听器的入参是刻度索引，不是值：双极项在它内部自己减 100 换成真实值。所以
            // 负值不是「支持不支持」的问题，而是要把值翻译成索引再交过去。
            boolean bipolar = isBipolar(menu.instance, index);
            int arg = bipolar ? value + 100 : value;
            java.lang.reflect.Field selected = intFieldNamed(menu.instance, "j");
            int previous = selected == null ? -1 : selected.getInt(menu.instance);
            if (selected != null) selected.setInt(menu.instance, index);
            // 刻度条是全局共用的一根，档位（J0）和位置（I0）都是「当前选中项」的状态。
            // 给非选中项改档位会反过来把选中项的值冲掉（实测饱和度被冲成 0），所以只有
            // 「这一项正好是界面上选中的那项」时才动条；其余项只写值，条等用户点过去再说。
            int levels = bipolar ? 201 : 101;
            if (syncBar) {
                java.lang.reflect.Method levelNum = findMethod(bar, "setLevelNum", 1);
                if (levelNum != null && intField(bar, "J0") != levels) {
                    try {
                        levelNum.invoke(bar, levels);
                    } catch (Throwable error) {
                        Log.e(TAG, "bar setLevelNum failed", error.getCause());
                    }
                }
                // w(int) 负责「索引 → 像素位置(I0) → 重绘」，但不通知监听器；监听器 g(int)
                // 负责值和数字。两个都调，图标数字 / 条上数字 / 指示块才三处一致。
                java.lang.reflect.Method place = findMethod(bar, "w", 1);
                if (place != null && arg >= 0 && arg < levels) {
                    try {
                        place.invoke(bar, arg);
                    } catch (Throwable error) {
                        Log.e(TAG, "bar place failed", error.getCause());
                    }
                }
            }
            changed.invoke(listener, arg);
            if (selected != null && previous >= 0) selected.setInt(menu.instance, previous);
            Object now = symbols.get("EffectItem.value", item);
            Log.i(TAG, "tap+listener " + value + " as index " + arg + " -> item value " + now
                + " bar=" + syncBar + " J0=" + intField(bar, "J0"));
            return now instanceof Integer && (Integer) now == value;
        } catch (Throwable error) {
            Log.e(TAG, "tap+listener failed: " + error.getCause(), error.getCause());
            return false;
        }
    }

    /** 把刻度条重新对齐到某一项当前的值：只挪指示块，不写值（写值会再触发一轮刷新）。 */
    private static void resyncBar(Symbols symbols, Menu menu, int index) {
        try {
            Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            Object target = item(menu, index);
            if (bar == null || target == null) return;
            Object value = symbols.get("EffectItem.value", target);
            if (!(value instanceof Integer)) return;
            boolean bipolar = isBipolar(menu.instance, index);
            int arg = bipolar ? (Integer) value + 100 : (Integer) value;
            int levels = bipolar ? 201 : 101;
            java.lang.reflect.Method levelNum = findMethod(bar, "setLevelNum", 1);
            if (levelNum != null && intField(bar, "J0") != levels) {
                try {
                    levelNum.invoke(bar, levels);
                } catch (Throwable error) {
                    Log.e(TAG, "resync setLevelNum failed", error.getCause());
                }
            }
            java.lang.reflect.Method place = findMethod(bar, "w", 1);
            if (place == null || arg < 0 || arg >= levels) return;
            place.invoke(bar, arg);
            // 条上方那个大数字是菜单里的描边文字控件（字节码里 if.d.g，类型 StrokeTextView），
            // 别的项写值时会把它改成自己的值，这里一起改回来。
            setBigNumber(menu.instance, (Integer) value);
            Log.i(TAG, "resync item " + index + " value " + value + " -> index " + arg);
        } catch (Throwable error) {
            Log.e(TAG, "resync failed", error);
        }
    }

    /** 用相机自己的数字格式化（菜单上的 {@code h(int)}），拿不到就退化成纯数字。 */
    private static Object formatValue(Object menu, int value) {
        java.lang.reflect.Method format = findMethod(menu, "h", 1);
        if (format != null) {
            try {
                Object result = format.invoke(menu, value);
                if (result instanceof CharSequence) return result;
            } catch (Throwable ignored) {
                // 格式化失败就用纯数字。
            }
        }
        return String.valueOf(value);
    }

    /**
     * 把条上方那个大数字设成指定值。
     *
     * <p>它不是刻度条画的，是菜单上的一个描边文字控件（`StrokeTextView`，字节码里的 `if.d.g`）。
     * 字段名在不同版本会被混淆改掉，所以按**类型**找，不按名字找；也不能按「随便一个 TextView」
     * 找——菜单里还有个写着「效果」的标题栏。
     */
    private static void setBigNumber(Object menu, int value) {
        Object text = formatValue(menu, value);
        for (Class<?> type = menu.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!android.widget.TextView.class.isAssignableFrom(field.getType())) continue;
                if (!field.getType().getName().contains("StrokeTextView")) continue;
                try {
                    field.setAccessible(true);
                    Object view = field.get(menu);
                    if (view instanceof android.widget.TextView) {
                        ((android.widget.TextView) view).setText((CharSequence) text);
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "big number failed", error);
                }
                return;
            }
        }
    }

    /** 给菜单上的某个 TextView 字段设文案；字段不存在就静默跳过。 */
    private static void setText(Object owner, String name, Object text) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!name.equals(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    Object view = field.get(owner);
                    if (view instanceof android.widget.TextView) {
                        ((android.widget.TextView) view).setText((CharSequence) text);
                    }
                } catch (Throwable ignored) {
                    // 有字段但写不了，忽略。
                }
                return;
            }
        }
    }

    /**
     * 把「当前选中第几项」写进菜单，并返回这一项该用的刻度索引。
     *
     * <p>两件事都是从 {@code if.g1.g(int)} 的字节码里读出来的，缺一个就抛
     * {@code Index -1 out of bounds for length 11}：
     * <ul>
     *   <li>监听器不自己记选中项，它读菜单的 {@code j} 字段然后 {@code items.get(j)}；
     *       界面点击是异步写这个字段的，程序里直接写，不赌时序。</li>
     *   <li>值换成索引：{@code W2(j)} 为 true 的项（影调 / 对比度 / 饱和度 / 冷暖 / 青品 /
     *       锐度）走 {@code value = index - 100}，所以 {@code index = value + 100}；为 false
     *       的项（滤镜强度 / 柔光 / 颗粒 / 暗角）索引就是值。</li>
     * </ul>
     */
    private static int markSelected(Object menu, int index, int value) throws Throwable {
        java.lang.reflect.Field selected = intFieldNamed(menu, "j");
        if (selected != null) selected.setInt(menu, index);
        java.lang.reflect.Method w2 = findMethod(menu, "W2", 1);
        boolean bipolar = true;
        if (w2 != null) {
            Object result = w2.invoke(menu, index);
            if (result instanceof Boolean) bipolar = (Boolean) result;
        }
        return bipolar ? value + 100 : value;
    }

    /** 按字段名读一个 boolean 字段；没有就返回 null。 */
    private static Boolean booleanField(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType() != boolean.class || !name.equals(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.getBoolean(owner);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 当前 Activity 的根视图：条目视图抓不到时，从 ActivityThread 的 mActivities 里翻。 */
    private static android.view.View decorView() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field activities = threadClass.getDeclaredField("mActivities");
            activities.setAccessible(true);
            Object map = activities.get(thread);
            java.util.Collection<?> records = (java.util.Collection<?>) ((java.util.Map<?, ?>) map).values();
            for (Object record : records) {
                java.lang.reflect.Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (!(activity instanceof android.app.Activity)) continue;
                android.view.Window window = ((android.app.Activity) activity).getWindow();
                if (window == null) continue;
                android.view.View decor = window.peekDecorView();
                if (decor != null && decor.isShown()) return decor;
            }
        } catch (Throwable error) {
            Log.e(TAG, "decorView failed", error);
        }
        return null;
    }

    /**
     * 找界面上真实存在的刻度条实例，和符号表里那根比对。
     *
     * <p>推了 L(索引,true) 而画面上的指示块纹丝不动，第一嫌疑就是「推的不是屏幕上这根」：
     * 效果栏可能有多根条（每个菜单一份），符号表定位到的是没上屏的那个。
     */
    private static void findBars(Symbols symbols, Menu menu) {
        try {
            Object target = symbols.get("EffectMenuBase.seekBar", menu.instance);
            Log.i(TAG, "findbars: symbols 那根 id=" + System.identityHashCode(target)
                + " J0=" + (target == null ? -1 : intField(target, "J0"))
                + " a=" + (target == null ? -1 : intField(target, "a")));
            android.view.View root = null;
            for (Object view : itemViews) {
                if (!(view instanceof android.view.View)) continue;
                android.view.View walk = (android.view.View) view;
                while (walk.getParent() instanceof android.view.View) walk = (android.view.View) walk.getParent();
                root = walk;
                break;
            }
            if (root == null) root = decorView();
            if (root == null) {
                Log.e(TAG, "findbars: no view root", null);
                return;
            }
            List<android.view.View> queue = new ArrayList<>();
            queue.add(root);
            int seen = 0;
            while (!queue.isEmpty() && seen < 200) {
                android.view.View view = queue.remove(0);
                seen++;
                String name = view.getClass().getName();
                if (name.contains("HSScaleBar") || name.contains("ScaleBar")) {
                    int[] xy = new int[2];
                    view.getLocationOnScreen(xy);
                    Log.i(TAG, "findbars: " + name + " id=" + System.identityHashCode(view)
                        + " visible=" + (view.getVisibility() == android.view.View.VISIBLE)
                        + " at " + xy[0] + "," + xy[1] + " " + view.getWidth() + "x" + view.getHeight()
                        + " J0=" + intField(view, "J0") + " a=" + intField(view, "a")
                        + " C0=" + booleanField(view, "C0")
                        + " same=" + (view == target));
                }
                if (view instanceof android.view.ViewGroup) {
                    android.view.ViewGroup group = (android.view.ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
                }
            }
            Log.i(TAG, "findbars: walked " + seen + " views");
        } catch (Throwable error) {
            Log.e(TAG, "findbars failed", error);
        }
    }

    /**
     * 只走刻度条自己的入口：{@code L(索引, true)} → {@code K(索引)} 校验 → 投线程移动指示块 →
     * {@code ta.o0.run} 里再回调监听器。条的数字、指示块、格子数字都由这一条链带出来。
     *
     * <p>之前为了让负值下去，直接调了监听器 {@code g(索引)}，结果是格子数字变了、条没动——
     * 界面上就是「图标 80 / 刻度尺 0 / 指示线在中间」三处对不上。
     */
    private static void pushBar(Symbols symbols, Menu menu, int itemIndex, int index) {
        try {
            final Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            if (bar == null) {
                Log.e(TAG, "barpush: no seekBar", null);
                return;
            }
            final Object target = item(menu, itemIndex);
            // 条是共享的，档位数由「当前选中项」决定；没点过任何一项时 J0=0，K() 拒绝一切索引。
            // 所以必须先点这一项，让菜单把条重新配好（双极 201 档 / 单极 101 档），再推。
            final Object view = target == null ? null : viewOf(symbols, target);
            if (view instanceof android.view.View) {
                ((android.view.View) view).performClick();
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    java.lang.reflect.Field selected = intFieldNamed(menu.instance, "j");
                    if (selected != null && itemIndex >= 0) selected.setInt(menu.instance, itemIndex);
                    java.lang.reflect.Method accept = findMethod(bar, "K", 1);
                    Object ok = accept == null ? "n/a" : accept.invoke(bar, index);
                    java.lang.reflect.Method push = findMethod(bar, "L", 2);
                    if (push == null) {
                        Log.e(TAG, "barpush: no L(int,boolean)", null);
                        return;
                    }
                    push.invoke(bar, index, true);
                    Log.i(TAG, "barpush item " + itemIndex + " index " + index + " accepted=" + ok
                        + " J0=" + intField(bar, "J0") + " f1=" + intField(bar, "f1"));
                } catch (Throwable error) {
                    Log.e(TAG, "barpush push failed", error);
                }
            }, 400L);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Symbols later = Symbols.get();
                    Object now = target == null ? null : later.get("EffectItem.value", target);
                    Log.i(TAG, "barpush settled: item value " + now
                        + " bar f1=" + intField(bar, "f1")
                        + " a=" + intField(bar, "a")
                        + " J0=" + intField(bar, "J0")
                        + " 条上数字=" + textOf(menu, "g"));
                } catch (Throwable error) {
                    Log.e(TAG, "barpush settle read failed", error);
                }
            }, 1200L);
        } catch (Throwable error) {
            Log.e(TAG, "barpush failed", error);
        }
    }

    /** {@code W2(项下标)} 为 true = 双极项（−100..100，索引要减 100 还原成值）。 */
    private static boolean isBipolar(Object menu, int index) {
        java.lang.reflect.Method w2 = findMethod(menu, "W2", 1);
        if (w2 == null) return true;
        try {
            Object result = w2.invoke(menu, index);
            if (result instanceof Boolean) return (Boolean) result;
        } catch (Throwable ignored) {
            // 查询失败按双极处理：多减 100 的后果比索引越界轻。
        }
        return true;
    }

    /** 读菜单上某个 TextView 字段的文案；字段名来自字节码（{@code if.d.g} 就是条上方那个大数字）。 */
    private static String textOf(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!name.equals(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    Object view = field.get(owner);
                    if (view instanceof android.widget.TextView) {
                        return String.valueOf(((android.widget.TextView) view).getText());
                    }
                } catch (Throwable ignored) {
                    // 有字段但读不了，按没有处理。
                }
            }
        }
        return "?";
    }

    /** 按字段名在类层次里找一个 int 字段（混淆类里短名字是唯一稳定的锚点）。 */
    private static java.lang.reflect.Field intFieldNamed(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType() != int.class || !name.equals(field.getName())) continue;
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /**
     * 效果栏刻度条的监听器：相机把「值变了」这件事整个交给它（ScaleBarListener.g(int)），
     * 拖动时那一整串 W2 → 落盘 → I0 → k2 → K 都在它里面。抓到它就等于拿到了下发入口。
     *
     * <p>符号表里 ScaleBar.setListener 的真名是 setScaleBarValueChangeListener，接收者是那根条
     * 本身，参数是监听器——MasterGrain 加颗粒时用的就是这套。
     */
    private static void captureScaleBars(Symbols symbols) {
        Log.guard(TAG, "scale bar listener", () -> XposedBridge.hookMethod(symbols.method("ScaleBar.setListener"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object listener = param.args.length > 0 ? param.args[0] : null;
                Object bar = param.thisObject;
                if (bar == null || listener == null) return;
                synchronized (bars) {
                    bars.put(bar, listener);
                }
            }
        }));
    }

    private static final Map<Object, Object> bars = new IdentityHashMap<>();
    /** 效果栏每格的视图。选中某一项必须走相机自己的点击路径（EffectItemView.onClick）。 */
    private static final List<Object> itemViews = new CopyOnWriteArrayList<>();

    private static void captureItemViews(Symbols symbols) {
        Log.guard(TAG, "item views", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectItemView"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                itemViews.add(param.thisObject);
            }
        }));
    }

    /** 找到某一项对应的格子视图。 */
    private static Object viewOf(Symbols symbols, Object item) {
        for (Object view : itemViews) {
            try {
                if (symbols.get("EffectItemView.item", view) == item) return view;
            } catch (Throwable ignored) {
                // 这版没有 item 字段，跳过。
            }
        }
        return null;
    }

    /**
     * 选中某一项：走相机自己的点击路径，效果栏和刻度条一起绑到这一项上。
     *
     * <p>GR 模式的参数驱动（GrEffectValues.set）就是这么做的——先 select，再把值交给刻度条
     * 监听器；只给值不选中，值会落到别人身上，或者干脆没人接。
     */
    private static boolean selectItem(Symbols symbols, Menu menu, Object item, int index) {
        Object view = viewOf(symbols, item);
        if (view == null) {
            // 没有视图就走菜单自己的选中入口。
            java.lang.reflect.Method bind = findMethod(menu.instance, "W2", 1);
            return bind != null && invokeQuietly(bind, menu.instance, index);
        }
        try {
            java.lang.reflect.Method onClick = symbols.method("EffectItemView.onClick");
            onClick.setAccessible(true);
            onClick.invoke(view, view);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "item on click failed: " + error.getCause(), error.getCause());
            return false;
        }
    }

    private static boolean invokeQuietly(java.lang.reflect.Method method, Object target, Object value) {
        try {
            method.invoke(target, value);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }


    /** 刻度条监听器上的 g(int)：值变化回调。 */
    private static java.lang.reflect.Method valueChanged(Object listener) {
        for (Class<?> type = listener.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            java.lang.reflect.Method found = named(type.getDeclaredMethods(), "g");
            if (found != null) return found;
            for (Class<?> itf : type.getInterfaces()) {
                found = named(itf.getDeclaredMethods(), "g");
                if (found != null) return found;
            }
        }
        return null;
    }

    private static java.lang.reflect.Method named(java.lang.reflect.Method[] methods, String name) {
        for (java.lang.reflect.Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            if (name.equals(method.getName()) && params.length == 1 && params[0] == int.class) {
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // 接口方法不需要 accessible。
                }
                return method;
            }
        }
        return null;
    }

    /**
     * 把大师效果栏那根刻度条推到指定值——等价于用户手动拖到那。
     *
     * <p>这是唯一真正能改画面的入口：实测拖动能改，而无论写 EffectItem.value、写 MMKV 还是
     * 调 I0/k2/K，画面都一动不动（取景像素饱和度 0.4374 → 0.4379）。差别就在这根条上：
     * 相机把「用户改了值」整件事交给 HSScaleBar 的监听器（ScaleBarListener.g）去做。
     *
     * <p>两个必须照抄的细节，都是从 HSScaleBar 的字节码里读出来的：
     * <ul>
     *   <li>{@code L(int, boolean)} 的第一个参数是<b>刻度索引</b>，不是参数值。索引先交给
     *       {@code K(int)} 校验，越界且不等于特殊索引 {@code n1} 时直接返回 false，整次调用
     *       静默作废——负值「拖了没反应」就是这么来的，条只认 0 开始的索引。</li>
     *   <li>第二个参数是「通知监听器」。{@code L} 把回调投给 {@code ThreadManager} 异步执行，
     *       而 {@code ta.o0.run} 开头就是 {@code if (!accepted || !animate) return}——传 false
     *       时条只把指示块挪过去，监听器根本收不到值。所以主路径必须是 true。</li>
     * </ul>
     *
     * <p>调用顺序照抄真实交互：先选中这一项（W2），再把索引交给条。
     */
    private static boolean driveSeekBar(Symbols symbols, Menu menu, int index, int value) {
        try {
            Object bar = symbols.get("EffectMenuBase.seekBar", menu.instance);
            if (bar == null) return false;
            java.lang.reflect.Method set = findMethod(bar, "L", 2);
            if (set == null) {
                Log.e(TAG, "seekbar L(int,boolean) not found", null);
                return false;
            }
            // 条的索引和监听器的索引是同一套换算，都靠 W2 判断要不要减 100。
            int target = markSelected(menu.instance, index, value);
            // K(int) 是条自己的校验：返回 true 才说明这个索引被接受。用它同步判定，
            // 比调完 L 再读格子里的值可靠——L 的通知是异步的，读早了永远是旧值。
            java.lang.reflect.Method accept = findMethod(bar, "K", 1);
            java.lang.reflect.Method bind = findMethod(menu.instance, "W2", 1);
            Object cell = item(menu, index);
            for (int candidate : indexCandidates(target)) {
                if (accept != null) {
                    Object ok = accept.invoke(bar, candidate);
                    if (!(ok instanceof Boolean) || !(Boolean) ok) {
                        Log.i(TAG, "index " + candidate + " refused for value " + value);
                        continue;
                    }
                }
                if (bind != null) bind.invoke(menu.instance, index);
                set.invoke(bar, candidate, true);
                if (bind != null) bind.invoke(menu.instance, index);
                Log.i(TAG, "seekbar " + value + " via index " + candidate + " on item " + index
                    + ", item now " + (cell == null ? null : symbols.get("EffectItem.value", cell)));
                return true;
            }
            Log.i(TAG, "seekbar " + value + " -> no index accepted");
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "seekbar drive failed", error);
            return false;
        }
    }

    /**
     * 索引的候选：{@code markSelected} 算出来的那个为准，再挂上两个相邻值——条的总刻度
     * 与值域不一定严格对齐（影调 0..200 里能用的可能只有 1..199），越界那一个就地被
     * {@code K(int)} 挡掉。
     */
    private static int[] indexCandidates(int target) {
        if (target <= 0) return new int[] {0, 1};
        return new int[] {target, target + 1, target - 1};
    }

    private static Object item(Menu menu, int index) {
        try {
            List<?> items = items(Symbols.get(), menu.instance);
            return index >= 0 && index < items.size() ? items.get(index) : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 按名字+参数个数在这个菜单的类层次里找方法。
     *
     * <p>这些短名（W2 / I0 / k2 / K）是相机混淆后的名字，只能按「名字 + 有几个参数」认，
     * 不能按参数类型认——同一个名字在不同版本里签名可能变，但名字+个数是稳定的。
     */
    private static java.lang.reflect.Method findMethod(Object instance, String name, int arity) {
        for (Class<?> type = instance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != arity) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // 隐藏 API 不设 accessible 也能调，失败会在 invoke 时报出来。
                }
                return method;
            }
        }
        return null;
    }

    /**
     * 复刻相机自己拖滑块时的调用序列：W2(序号) ×2 → I0(key) → k2(true) → K()。
     *
     * <p>探针实测：手动拖动每一项都会按这个顺序走一遍，之后 MMKV 里就多出一条
     * {@code key_master_mode_effect_xxx}。我们之前只调了 I0，等于漏掉了真正落盘/通知的那几步。
     */
    private static boolean replayDrag(Object menu, int index, Object key, int value) {
        boolean any = false;
        try {
            java.lang.reflect.Method select = findMethod(menu, "W2", 1);
            if (select != null) {
                select.invoke(menu, index);
                select.invoke(menu, index);
                any = true;
            } else {
                Log.e(TAG, "replay: W2 not found", null);
            }
            // 真实的拖动顺序是「先选序号，再落盘」：W2 → DataManager.put → I0 → k2 → K。
            // 落盘夹在中间很关键，提前写进去会被 W2 内部的旧值盖掉。
            if (key != null) {
                try {
                    Symbols.get().call("Prefs.put", null, key, "", Integer.class, value);
                } catch (Throwable error) {
                    Log.e(TAG, "store failed: " + key, error);
                }
            }
            java.lang.reflect.Method apply = findMethod(menu, "I0", 1);
            if (apply != null && key != null) {
                apply.invoke(menu, key);
                any = true;
            } else {
                Log.e(TAG, "replay: I0 not found", null);
            }
            java.lang.reflect.Method mark = findMethod(menu, "k2", 1);
            if (mark != null) mark.invoke(menu, true); else Log.e(TAG, "replay: k2 not found", null);
            java.lang.reflect.Method commit = findMethod(menu, "K", 0);
            if (commit != null) commit.invoke(menu); else Log.e(TAG, "replay: K not found", null);
            // 读回来：如果 l2 拿到的是旧值，说明写进去的分组不是大师模式读的那个分组。
            // 这条退路以前只回「方法都调到了」就算成功，于是 binder 线程上从头到尾一次没写进去
            // 也报「已应用 N 项」。现在以读回值为准：对不上就是没写成，交给上层记账。
            java.lang.reflect.Method read = findMethod(menu, "l2", 3);
            if (read == null || key == null) {
                Log.i(TAG, "replay drag: index=" + index + " steps=" + any);
                return any;
            }
            Object back = read.invoke(menu, key, "", "professional");
            Log.i(TAG, "replay drag: index=" + index + " steps=" + any + " want=" + value + " readback=" + back);
            return back instanceof Integer && (Integer) back == value;
        } catch (Throwable error) {
            Log.e(TAG, "replay drag failed", error);
            return false;
        }
    }

    /** 只吃一个 DataKey 的方法（if.f1 上是 I0），把这一项的当前值下发。 */
    private static java.lang.reflect.Method keyApplier(Object menu) {
        for (Class<?> type = menu.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !params[0].getName().endsWith("DataKey")) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // 同 dispatchOf：隐藏 API 不设 accessible 也能调。
                }
                Log.i(TAG, "按 key 下发用 " + type.getName() + "." + method.getName());
                return method;
            }
        }
        return null;
    }

    /**
     * 这个菜单自己身上的 dispatch：把一值一键送进相机成像链的那个方法。
     *
     * <p>不能只认名字。实测 if.f1 上没有任何叫 dispatch 的方法（点名去找全落空），而相机
     * 这一版很可能把它混淆成了短名。所以先按名字找，再按签名找：参数是 (int, DataKey)
     * 的两参方法就是它——restore 和 refreshItem 的签名都不一样，不会认错。
     */
    private static java.lang.reflect.Method dispatchOf(Object menu) {
        java.lang.reflect.Method bySignature = null;
        for (Class<?> type = menu.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                boolean named = "dispatch".equals(method.getName());
                boolean shaped = params.length == 2 && params[0] == int.class
                    && params[1].getName().endsWith("DataKey");
                if (!named && !shaped) continue;
                if ("restore".equals(method.getName()) || "refreshItem".equals(method.getName())) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // 隐藏 API 名单里的方法不设 accessible 也能调，失败会在 invoke 时报出来。
                }
                if (named && params.length == 2) {
                    Log.i(TAG, "dispatch 取自 " + type.getName() + "." + method.getName() + "（按名字）");
                    return method;
                }
                if (shaped && bySignature == null) {
                    bySignature = method;
                    Log.i(TAG, "dispatch 候选 " + type.getName() + "." + method.getName() + "（按签名 int+DataKey）");
                } else if (named && bySignature == null) {
                    bySignature = method;
                    Log.i(TAG, "dispatch 候选 " + type.getName() + "." + method.getName() + "（按名字）");
                }
            }
        }
        return bySignature;
    }

    @SuppressWarnings("unchecked")
    private static List<?> items(Symbols symbols, Object menu) throws Throwable {
        Object list = symbols.get("EffectMenuBase.items", menu);
        return list instanceof List ? (List<Object>) list : new ArrayList<>();
    }

    /** 进程里所有 EffectMenu 及其项目：核对时全列出来，谁是大师效果栏一眼可见。 */
    private static String dumpAll() {
        Symbols symbols = Symbols.get();
        StringBuilder out = new StringBuilder();
        out.append("menus=").append(menus.size()).append('\n');
        if (menus.isEmpty()) {
            out.append("相机还没构造效果栏：先在相机里切一次大师模式。\n");
            return out.toString();
        }
        for (int m = 0; m < menus.size(); m++) {
            Menu menu = menus.get(m);
            out.append("--- #").append(m)
                .append(" mode=").append(menu.mode)
                .append(" 调色项=").append(presetItemCount(menu))
                .append('\n');
            try {
                List<?> items = items(symbols, menu.instance);
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    out.append("  ").append(i).append(' ')
                        .append(displayName(menu, symbols, item))
                        .append(" | icon=").append(iconName(menu, symbols, item))
                        .append(" | value=").append(symbols.get("EffectItem.value", item))
                        .append('\n');
                }
            } catch (Throwable error) {
                out.append("  读取失败：").append(error).append('\n');
            }
        }
        return out.toString();
    }

    /** 效果栏的真实组成，一行一项，供设置页核对。 */
    private static String dump(Menu menu) {
        Symbols symbols = Symbols.get();
        StringBuilder out = new StringBuilder();
        out.append("mode=").append(menu.mode).append('\n');
        try {
            List<?> items = items(symbols, menu.instance);
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                out.append(i).append('\t')
                    .append("label=").append(displayName(menu, symbols, item)).append('\t')
                    .append("icon=").append(iconName(menu, symbols, item)).append('\t')
                    .append("value=").append(symbols.get("EffectItem.value", item)).append('\t')
                    .append("key=").append(String.valueOf(symbols.get("EffectItem.key", item)))
                    .append('\n');
            }
        } catch (Throwable error) {
            out.append("读取效果栏失败：").append(error).append('\n');
        }
        return out.toString();
    }

    /** drawable 的资源名（master_tone_icon 之类），读不到就返回类名，便于对照。 */
    private static String iconName(Menu menu, Symbols symbols, Object item) {
        try {
            Object icon = symbols.get("EffectItem.icon", item);
            if (icon instanceof Integer && (Integer) icon != 0) {
                Context context = context(menu);
                if (context != null) return context.getResources().getResourceEntryName((Integer) icon);
            }
        } catch (Throwable ignored) {
            // 不是资源 id（或者没有 context），退回空串。
        }
        return "";
    }

    /** 项的显示文字：label 是字符串资源 id 时取文案，否则用资源名。 */
    private static String displayName(Menu menu, Symbols symbols, Object item) {
        try {
            Object label = symbols.get("EffectItem.label", item);
            if (!(label instanceof Integer)) return String.valueOf(label);
            Context context = context(menu);
            if (context == null) return "res:" + label;
            try {
                return context.getString((Integer) label);
            } catch (Throwable ignored) {
                return context.getResources().getResourceEntryName((Integer) label);
            }
        } catch (Throwable error) {
            return "?";
        }
    }

    /**
     * 读资源名要 Context。EffectMenu 不一定是 View（没有 getContext），那时退到应用 Context：
     * 同一个 app 的资源表，取到的名字一样。少了这个回退，资源名全是空串，一项都认不出来。
     */
    private static Context context(Menu menu) {
        try {
            Object context = menu.instance.getClass().getMethod("getContext").invoke(menu.instance);
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {
            // 不是 View：走下面的应用 Context。
        }
        return appContext();
    }
}
