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

## 2026-11-13：成片颗粒落地（r330 / r331），以及参考包对照

### 结论先行

这台机器的**静图链路根本不经过 Meishe**。给 `libmsnativefilter.so` 的所有导出（`MsRenderHDRHostBuffer`
等 14 个）都挂了钩，一次大师拍照里 `render entries probed` 会打出来（库确实在这时被 dlopen），
但**没有任何一个渲染入口被调用**。所以：

- 之前 r326 那套对 `libAlgoInterface.so` 的运行时补丁（amount 默认值 / gate / dispatch 三条全部生效）
  当然看不到变化——`setMesheFilterParam` 探针计数为 0，它是这版固件上的死代码。
- 换成参考包（X8U `V3-test160`）里那份 `libmsnativefilter.so` 也一样没有颗粒：成片 `lap var 112.8`，
  和干净图 `88.0` 同一量级（真正的颗粒是 `1358.1`）。

### 成片帧在哪儿

`HDRTransformImpl::processMaster(InputImage* in, Image* out, void* param, void* bufs)`——
`out` 就是直接变成 JPEG 的 NV21 底图（几何信息 `out+0x20` w / `+0x24` h / `+0x28` stride / `+0x2c` slice，
输出 native handle 在 `bufs+0x28`，第一个 fd 是整图的 dma-buf，与 `hdrt_dump.cpp` 同一套偏移）。
7.006 上 `processMasterV2` / `processMasterQuick` 已经不存在，符号只剩 `processMaster`、`process_photo`、
`process`、`process_gallery`、`process_algo`、`process_format_convert`；
三个 HDRTransform 库（Camera / Quick / Portrait）是同一份二进制的副本，各自独立映射，必须每个都挂。

**r330 起**：`grain_overlay.cpp` 在 `processMaster` 返回后 mmap 这块 dma-buf（`PROT_READ|WRITE` +
`DMA_BUF_SYNC_RW`），把颗粒画进 Y 平面，再 `DMA_BUF_SYNC_END` 交还给编码器。
`processMaster` 内部会转调 `process_photo`，所以按 `out` 指针 + 400ms 窗口去重，避免画两遍。

实测：`4096x3072` 与 `3072x4096` 各一次，单张 50–54ms；成片 JPEG 从 7.3MB 涨到 16.8MB，
`lap var 88 → 1358`，1:1 裁剪下是正常的胶片颗粒，无彩色噪点（只动 Y）。

### 参考包 `MUSE-X8U-V3-test160-KSU.zip` 的真相

它的颗粒**不是 native 新写的**，就是本仓库的 `MasterGrain.applyFilmGrain()`：把
`mbFilterGainAmount(-amount/100)`、`mnFilterGrainType=2`、`mFilterColorGrain=1` 等字段写进
`CameraRequestTag`，再由相机自己的滤镜节点出颗粒。差别只有两处：

1. 它的 `x8u-fix.sh` 把 `payload/x8u-fix/libmsnativefilter.so`（10.9MB，sha256 `1468143a…`）bind 到
   `/odm/lib64/libmsnativefilter.so`；
2. 它适配的相机是 **7.013.51**，我们的是 **7.006.123**。

但两版相机的 dex 里**都没有大师颗粒代码**：7.013.51 全 APK 只有 4 处 `grain`，全是水印/贴纸 JSON 的
参数名（`grain_amount` / `grain_highlight` / `grain_roughness` / `grain_size`，和 `gradientColors`、
`gravity`、`grayBitmap` 排在一起）；`mbFilterGainAmount`、`mFilterGrainEnable`、`KEY_FILTER_GRAIN_*`
在两版 APK 里都是 0 次——这些都在 `/product/framework/com.oplus.camera.unit.sdk.jar` 里，
是**系统侧**的，不随相机 APK 变。

我们这台机器的相关哈希与参考包脚本的对照：

| 文件 | 本机 sha256 | 参考包脚本要求 |
|---|---|---|
| `libAlgoInterface.so` | `1e030b2d…` | 必须是 `1e030b2d…` ✅ 完全一致 |
| `libmsnativefilter.so` | `7ce658e8…` | 在 target 白名单第 1 条 ✅ |
| `libBasicTonePhoto.so` | `56ef6991…` | 要求 `a93f40de…` ❌ 不同 |

`/odm/etc/camera/filters_res/film_grain_small.bin` 本来就在，原厂库里也有 `GetNoiseTexture`——
即这套库本来就能出颗粒，只是静图链路走不到那里。**换库实测无效，已 umount 回滚**（md5 恢复，
`/proc/1/mountinfo` 计数 0）。这条路在本机不成立，不必再试。

### 现在的状态

- 颗粒 = `grain_overlay.cpp` 自绘，读 `/data/user/0/com.oplus.camera/files/master_grain`（`<amount> <size>`）。
- 该文件**每张照片拍摄时**由 Java 侧重写（`applyCapture` / `hookStandardCapture` 都调 `writeNative`），
  所以没拖过滑条也会生效，且不会和 Prefs 里的值脱节。
- 权重按原厂颗粒的分布来（原厂 shadows 0.89 / midtones 0.90 / highlights 0.85 近乎均匀），
  本实现取 `0.55 + 0.45·4t(1-t)`；`cell = 1 + round(size·6)`，幅度上限 0.18。
- 开关：`persist.omnicam.aura.ngrain=0` 关掉这一半（日志里 `groups: ... grain=0`）。
- 已知不足：**预览不出颗粒**，只有成片有（预览侧要另做 GL 合成，未做）。
