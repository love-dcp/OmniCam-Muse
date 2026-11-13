# 大师模式颗粒不生效（PKJ110）调查

> 2026-11-13。症状：大师模式效果栏里的「颗粒」能拖、数字会变，成片（JPG）没有任何颗粒变化。
> 本文只记录定位结论，修复方案见文末「下一步」。

## 结论：这条链在这版相机上整条都不存在了

颗粒的值从 UI 到成片要过三段，当前设备三段全断（⑤ 是当晚上机实测修正后的状态）：

| 段 | 老实现 | 状态 |
|---|---|---|
| ① UI 条 + 存值 | `MasterGrain.Panel` + `Prefs.put` | 符号断了 → **r321 已修好**，面板能开 |
| ② 拍照请求写入 | hook `BaseMode.setPositiveFilterGrain` / `ApsProcessor.createMetaItemInfo` | 类不存在，且新相机 Java 层**没有颗粒这个概念** |
| ③ native 允许任意滤镜吃颗粒 | `libAlgoInterface.so` 的 grain gate 补丁 | 没打（PKJ110 用原厂 so），且没人给它 amount |

根因一句话：**相机 7.006.123 起，大师模式的参数体系里取消了颗粒**（官方预设格式只有 8 个参数，
dex 里连 `filter_grain_*` 字符串都没有），预览里能看到的那点颗粒是渲染层按滤镜写死的常量。

## ① 为什么 UI 当时被当成是好的

`MasterGrain` 的界面部分全部走 `Symbols` 指纹（`EffectMenu`、`EffectItemView.onClick`、
`ScaleBar.setListener`、`FilterNames.types`、`Prefs.get/put`），这些在 `assets/symbols.json`
里都有，且 `DataKey`(`com.oplus.camera.data.DataKey`，构造 `(String,String,String,Class)V`)
与 `HSScaleBar`(`com.oplus.camera.coui.seekbar.HSScaleBar`) 都是**未混淆的保留名**。
所以条能显示、值能存进每个滤镜一份的 `key_muse_grain_amount_<type>`。

## ② 拍照侧：三个类名都不存在

`MasterGrain.install()` 末尾这三行用的是硬编码名，常量 `SDK = "com.oplus.ocs.camera."`：

```java
XposedHelpers.findClass(SDK + "producer.mode.ProfessionalMode", classLoader)          // 209
XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", ...,
        "setPositiveFilterGrain", SDK + "common.util.CameraRequestTag", ...)          // 210
hookStandardCapture(...)  // 里面还有 consumer.ApsProcessor / common.util.ParameterKeys   // 279-324
```

`hookHalfBars()` 与 `hookStandardCapture()` 排在它们**后面**，所以第一行抛了之后，
后面两段也不会安装。

实测相机 `com.oplus.camera` **7.006.123**（versionCode 60000，33 个 dex）里：

| 硬编码名 | dex 命中 |
|---|---|
| `com/oplus/ocs/camera/producer/mode/*` | **0** |
| `com/oplus/ocs/camera/consumer/ApsProcessor` | **0** |
| `com/oplus/ocs/camera/common/util/ParameterKeys` | **0** |
| `com/oplus/ocs/camera/common/util/CameraRequestTag` | **0** |

真名是另一套（`assets/symbols.json` 里 `BaseMode` 早就记着）：

- `com.oplus.camera.module.BaseMode`（未混淆，`interfaces` 里还挂着 `CaptureProcessor$f`）
- `com.oplus.camera.device.CameraRequestTag`（未混淆，126 个字段）

`Log.guard(TAG, "grain hooks", ...)` 把这个 `ClassNotFoundException` 记成一条
`grain hooks unavailable` 就吞掉了，所以功能静默失效——不会崩、不会报错、UI 照常。

同样的旧常量也被 `MasterNeonGlow`（`SDK + "common.util.CameraRequestTag"`）和
`MasterPortraHalation` 用着，**两条同源功能大概率一起失效**，修颗粒时顺带核对。

## ③ 就算改了类名也对不上：结构已经重构

`CameraRequestTag` 当前只有 **3 个 float** 字段（`E1` / `n1` / `o`），而且
反汇编显示 `n1`、`o` 只在 `<init>` 里被写成 `0`，`E1` 没有任何 `iput`——它们不是颗粒参数。

旧代码 `applyFilmGrain()` 要写 7 个 float：

```
mbFilterGainAmount / Size / ColorRatio / Highlight / Midtones / Shadows / Roughness
```

也就是说 X8U 那版相机把 grain 放在 tag 上，**PKJ110 这版换了承载结构**（tag 上没有，
`CameraParameter` 里也只有 `FILTER_TYPE / FILTER_INTENSITY / FILTER_ENUM_TYPE /
FILTER_WITHVIGNETTE / EXTEND_DATA_MEISHE_FILTER`，没有 GRAIN 键）。

## ④ native 侧也没有补丁

设备 `/odm/lib64/libAlgoInterface.so`：

```
md5 28715a07bac87e3c2d286c9db2ca8f7c   (原厂，60867488 字节)
/proc/self/mountinfo 里没有 libAlgoInterface 的 overlay
```

`tools/patch_grain_gate.py` 的偏移是按 X8U 的 79298968 字节版本写的：

| 项 | X8U（已打） | PKJ110 原厂 |
|---|---|---|
| `setMesheFilterParam` | `OCamFilterMeisheV2::setMesheFilterParam` | `android::setMesheFilterParam` @ `0x195528c`，长 `0x4f30` |
| grain amount 读取 | `sp+0x60` | `sp+0x70`（`0x1955928` movi d0,#0 → `0x1955934` bl float getter） |
| `"filter_grain_amount"` | `0x1DD8DE` | `0x1d9fca`（引用 5 处，含 `0x1955908`） |
| 补丁状态 | `JOIN` 已是 b → cave | `0x1955ec8` 是原指令 `ldrb w8,[x23]`，cave 区为空 |

所以「非原生颗粒滤镜（正片/负片/清透/标准等）也能出颗粒」这条路在 PKJ110 上**从来没通过**。

## ⑤ 实测（2026-11-13 晚，r320 上机）：UI 也没装上

一次 logcat 就把话说明白了：

```
Symbols:  optional EffectItemView.onClick absent: 0 matches []
Symbols:  optional EffectValueView absent: 0 matches []
MasterFilter: grain hooks unavailable (symbol EffectItemView.onClick unavailable)
```

也就是说颗粒连**界面**都没生效：条目是 `EffectContainer` 构造钩子插进去的（在 onClick 之前装，所以插进去了），
但点它不开面板、拖不动、数字永远 0。

### 真身：条目视图换成了内部类

相机 7.006.123 把效果栏从「自定义视图 + 手工布局」改成了「数据条目 `MasterEffectMenuItem` + 内部绘制视图」：

| 符号 | 老写法 | 现在的真身 |
|---|---|---|
| `EffectItemView` | strings `MasterEffectContainer`+`expand`（0 命中） | `if.s0$c`（`MasterEffectContainer$` 内部类） |
| `EffectItemView.bind` | `bind(boolean,boolean)` | `c(ZZ)V` |
| `EffectItemView.onClick` | `onClick` | `onClick(Landroid/view/View;)V`（名字没变） |
| `EffectItemView.item` | `EffectItem` 字段 | 字段 `h`（`if.v0`） |
| `EffectItemView.name/icon` | TextView/ImageView | `e` / `d` |
| `EffectValueView` | `EffectItemView` 子类 + TextView | `if.s0$f`（多一个 TextView `l`） |

`EffectItem`（数据条目）= `if.v0`：`(III,DataKey)` 构造、字段 `g` 是 DataKey —— 所以原来的
`EffectItem.key` 反查还是对的，只有视图这层断了。

**改法**（r321 已发）：`symbols.json` 里把 `EffectItemView` 从「布局字符串」换成结构指纹

```json
"EffectItemView": { "kind": "class", "super": "java.lang.Object",
  "fieldTypes": ["android.widget.ImageView","android.widget.TextView","@EffectItem","@EffectContainer"] }
```

`@EffectContainer` 那一条是必需的：`RetroCamEffectContainer` 的内部类 `if.b2$a` 结构一模一样，
不锁容器会命中两个。改完 `Symbols: camera: 248 resolved, 0 missing`、`MasterGrain: master grain installed`，
点颗粒能开出「强度 / 大小」双条（`panel shown for default`）。

顺带：`MasterPresetApply` 的「按相机自己的路径选中某一项」和 `MasterContrast` 的复位也走这个符号，
之前一起静默失效，现在一并恢复。

## ⑥ 拍照侧不是「换了个类名」，是这条通道没了

老版本（10-13 的 `cam_alog`）里 APS 客户端是这么收参数的：

```
newParameters: [needSimulation, 0, raw_value, none,
                filter_grain_midtones, 0.9, master_mode_cold_warm, 0, ...]
```

`filter_grain_midtones=0.9` 正是 `MasterGrain.FILM_GRAIN` 里的值 —— 说明那时候 Java 侧的注入是通的。

现在这版相机（7.006.123）：

- dex 里 `filter_grain_*` **一个字符串都没有**（只有 IPU 的 `grain_amount/size/highlight/roughness`）；
- `ApsProcessor`、`ParameterKeys` 类不存在，`CameraRequestTag` 只剩 3 个 float 且都是构造函数写的 0；
- 相机自己的大师预设格式（`com.oplus.camera.preset.MasterPresetDeferredImportProvider`）只有 8 个参数：
  `filter_name / filter_intensity / soft_light_name / tonality / saturation / cold_warm / cyan_magenta / sharpness / vignette` —— **没有颗粒**；
- 拍一张照片期间 logcat 里没有任何 `processImage` 调用 → 静图不走 Java 的 IPU 滤镜 SDK（那套只管预览，
  预览的颗粒是 `cg.c.process` 里按滤镜类型写死的 0.12 / 0.15）。

结论：这版相机在 Java 层**不再有颗粒这个概念**。老注入路径不是「改个名字」能救的，要么找到新的下发通道，
要么回到 native。

## ⑦ native 侧（补充）

`libAlgoInterface.so`（60MB 原厂）里 `filter_grain_amount/size/highlight/roughness/type/color_*` 十个 key 都在，
5 处引用**全是读**（`0x193c244`、`0x193df5c`、`0x1947fe8`、`0x1955908`、`0x1963864`，都长这样：
`ldr x0,[x19,#off]` → 按名字取值）：

```
0195593c: str s0, [sp, #0x70]        ; grain amount 落栈
0195594c: ldrb w8, [x24, #0x12]      ; 滤镜对象上的「支持颗粒」标志
01955950: tbz  w8, #0, 0x1955b38     ; 不支持就整个跳过 ← patch_grain_gate.py 撬的就是这里
```

没人往这个 map 里写 `filter_grain_*`，它来自滤镜自己的参数集（预览里那套按滤镜写死的常量就是同一份逻辑）。
所以想让任意滤镜出颗粒，只能改 native：

- 方案 A：patch 掉 `0x1955950` 那个 `tbz`，让「支持颗粒」恒为真 —— 但 amount 仍是 0，画面不会变，得连值一起给；
- 方案 B：在 cave 里放存根，`filter_grain_amount/size` 改成读模块写的文件（`/data/adb/.../grain`），
  这样滑条才真的能动成片。工作量在「找 cave + 写 ARM64 stub + 每台机器重新定位偏移」。

## ⑧ 顺带发现：相机有官方预设导入通道

`com.oplus.camera.preset.MasterPresetDeferredImportProvider`：

- 内容提供者：`content://com.oplus.camera.master_preset_deferred_import/pending`、`/status`
- 队列文件：`deferred_master_preset_pending_queue.json`，字段 `preset_data_uri`、`status_code`、`error_message`
- 预检会报 `filter not supported / soft light not compatible / redundant preset / 上限 20 条`
- 预设内容就是上面那 8 个参数，日志格式 `:name= / /filter= / /intensity= / /contrast= / /saturation= / /coldWarm= / /cyanMagenta= / /sharpness= / /vignette=`

以后写预设可以改走这条官方通道（和 OMaster 一样），比现在「往效果条目里塞值」稳得多。

## 下一步

1. ~~设备实测确认 ②~~ 已完成：UI 符号全断，r321 已修。
2. 颗粒要落到成片，只剩 native 一条路（方案 B）。动手前先确认一件事：**换上打过 gate 补丁的 so 后，
   用固定 amount（比如 0.3）能不能看到颗粒**。能看到再花时间做「从文件读值」的 stub；
   看不到就说明这条链在这版相机上还断在别处，不值得继续。
3. 相机自带的「颗粒」条（`key_gr_mode_effect_particle_amount_<n>` / `..._size_<n>`，只在理光 GR 模式里）
   是目前唯一还有 UI 的原生颗粒，可以考虑「大师模式借它的值」——但那条链也只喂了预览的 `gr_particle_amount`。

## 本地已备好的分析材料

| 文件 | 内容 |
|---|---|
| `work/pkj110_libAlgoInterface_stock.so` | 设备原厂 lib（60MB，md5 28715a07…） |
| `work/pkj110_setMesheFilterParam.asm` | 反汇编 `0x195528c`，5068 行 |
| `work/cam_dex/` | 相机 APK 的 33 个 dex |
| `work/pkj110_c4.txt` / `pkj110_c4_d.txt` | classes4（效果栏所在 dex）类表 / 反汇编 |
| `work/pkj110_c15_d.txt` / `c25_d.txt` | IPU 滤镜 SDK / 适配器反汇编 |
| `work/pkj110_BaseMode.txt` / `pkj110_classes12_d.txt` | BaseMode 签名 / 全量反汇编（62MB） |
| `work/pkj110_reqtag.txt` | CameraRequestTag 全部 126 个字段 |
| `work/pkj110_camparam.txt` | CameraParameter 全部 Key |
