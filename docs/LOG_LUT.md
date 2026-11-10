# Log LUT 还原监看（PKJ110 / X8U）

> 定案于 2026-11-05，r152。设备：PKJ110（OP5DD3L1 / sun / SM8750），相机 7.006.123。

## 1. 一句话结论

相机包里**整套「LUT 还原监看」都是现成的**：面板、三档（无 / SDR Rec.709 / HDR HLG）、
自定义 `.cube` 导入、以及 `LogLutMenuView` / `LogPreviewView` / `LogVideoPresenter` 三个类。
它在这台机器上从不出现，只是因为**三个"是否支持"询问全都答 0/off**。把这三个答案改掉，
入口自己就出来了，面板、列表、导入和监看全是原厂代码。

2026-11-08 的最终边界：

- 自定义 LUT 只能走面板导入，往 `/odm/etc/camera/filters_lut/` 塞文件没用（§5）；
- r173 已在 X8U 实机通过验收：打开「录制效果直出」后，预览和成片都会出现所选 LUT
  的色调；关闭直出则保留灰色 Log 基线；
- 正式样片使用原生 HAL `mode=2`，没有人工覆盖；成片方向为 `2160×3840`、旋转 0°；
- 从专业视频切到人像、再切回照片后，画面正常，模块没有在普通照片模式强制 LUT；
- 历史章节仍保留失败过程和误判原因，便于后续维护，最终证据见 §6.9。

> 2026-11-07 更正：本文 §6.2 至 §6.5 中曾把 `mode=2`、8-bit 编码和 RGB 均值差异当作
> “LUT 已烘入成片”的证据。2026-11-07 的实机复看否定了当时结论。那些测量只说明编码路径
> 发生变化，不能证明色彩变换发生；相关失败状态最终由 r173 修复，见 §6.9。

## 2. 三道门

`ih.g`（`LogVideoPresenter`）的可见性检查 `C2(Z)`（日志里叫 `checkLogPreviewVisible`）：

```java
v8 = o9.h.m();                    // master.video.feature.list[4] == 1
v9 = "on".equals(hh.a.e());       // pref_log_video_key，默认 "off"
...
if (!(v8 && v9 && v12 && v16)) { 移除 LogPreviewView; return; }
if (o9.h.i()) {                   // master.video.feature.list[8] == 1，= isSupportCustomLut
    // 有自定义 LUT 支持 -> 跳过简化标签，走完整 LogLutMenuView 面板
}
```

`o9.h` 是 `MasterVideoDecision`，`c(idx, def)` 读
`com.oplus.camera.master.video.feature.list` —— **和「片门全开」抢的是同一张表**
（见 `video/OpenGate.java`：`o9.h.p()` = `c(14,0) >= 1`）。本机这张表相关槽位全是 0。

`hh.a` = `LogVideoModel`，`e()` = `DataManager.get(pref_log_video_key, "off")`，
而**这台机器上没有任何代码写这个 key**，所以它永远是 "off"。

实测日志（r152，依次命中）：

```
LogLut: custom lut gate i() answered true (stock false)
LogLut: pref_log_video_key reported as on (stock off)
LogLut: log preview gate m() answered true (stock false)
LogLut: checkLogPreviewVisible ran, arg false
```

## 3. 入口在哪

入口不是设置页，是**专业视频取景器右侧的六角图标**（眼睛图标下方，约 1080×2376 下 (981,1209)）。
点了弹出面板：

```
LUT 还原监看                      (i)   ⋮
  无
  SDR (Rec.709)
  HDR (HLG)
  ref                            ˅        <- 用户导入的 .cube，名字取自文件名
  录制效果直出     [开关]   暂不支持 120fps 或 8K
```

选中后取景器左上角出现 `LUT` 小标签，徽标三态可当状态灯用：

| 徽标 | 含义 |
|---|---|
| `LUT` 带斜杠 | 选了「无」，LUT 关闭 |
| 白色 `LUT` | 监看开，录制效果直出**关** |
| 橙色 `LUT` | 监看开，录制效果直出**开** |

「录制效果直出」那张卡片**只在选了 LUT（SDR / HDR / ref）时才出现**，选「无」时整卡消失。

实测坐标（1080×2376，专业视频）：LUT 入口 (140,210)、无 (230,346)、SDR (230,472)、
HDR (230,595)、ref (200,720)、直出开关 (698,872)、录制键 (532,1971)。
注意面板会随行数上下浮动十几像素，每次点之前先 `screencap` 复核，别连点。

设置页的「视频」tab 只有锁定镜头 / 锁定白平衡 / 自动调整帧率三项，
`pref_log_lut_video_key`（设置项在 `l0.c` 的 `generateSettingsForLauncherEntry` 里无条件添加）
在这一版**没有渲染进任何 tab**，所以从设置里找不到它。

## 4. 三档实测差异

同一场景、同一机位，Professional video / 1080·30·Log，取景器区域统计：

| 选项 | 亮度 | std | 饱和度 | R / G / B |
|---|---|---|---|---|
| 无 | 102.1 | 55.3 | 55.2 | — |
| SDR (Rec.709) | 95.6 | 52.0 | 52.5 | 104 / 97 / 86 |
| HDR (HLG) | 100.4 | 54.5 | 60.3 | 118 / 105 / 78 |

三档都能测出差别，HDR 最暖最饱和，SDR 最平。**注意**：这台机器上即使选「无」也不是
"原始 Log 码直出"的灰平画面，预览链本身已经做过基本解码，三档差别是色调而不是"灰/彩"。

## 5. 自定义 LUT：走面板导入有效，往 filters_lut 塞文件无效

这两件事必须分开看，2026-11-05 晚更正。

### 5.1 有效：面板 .cube 导入（用户的 ref 就是这么来的）

面板右上角 `⋮` 提供导入入口，选 `/sdcard/Download/ref.cube` 后：

- 列表里多出一行 `ref`（**名字＝文件名去扩展名**），可选中、可监看；
- `/sdcard/.lutThumbnail/` 出现 `olog_ref_0_gallery+camera_0_v01.png`
  （命名含 `gallery+camera` 作用域，说明登记在相册/相机共用的 LUT 库里）；
- 源 `.cube` **不在相机私有目录**里留副本：`find /data/data/com.oplus.camera -iname '*.cube'` 为空，
  `/sdcard/Download/ref.cube` 仍是唯一实体（820,617 B）。

### 5.1.1 新增：从任意路径选择或整文件夹导入（r164）

点面板右上角 `⋮` 里的「导入 LUT」后，现在会先显示两个入口：

- **选择 LUT 文件（可多选）**：打开系统文件选择器，可进入 SD 卡、Download、Documents
  或其他系统文件提供器，并一次选择多个 `.cube`；
- **导入整个文件夹**：打开系统文件夹选择器，递归查找所选目录及其子目录中的 `.cube`。

选中后，模块先把文件复制到相机进程可读的缓存目录，再按原厂格式生成
`select_lut_list_json`（字段为 `title`、`filePath`），最后交给原相机继续执行复制、
合并、持久化和面板刷新。原厂自定义 LUT 总上限是 20 个，因此文件夹里超过剩余名额时只导入
前面的文件，并提示实际导入数量。

实现位于 `app/src/main/java/local/jiege/hook/video/LogLutImport.java`，随
`ModuleSettings.LOG_LUT` 开关一起启用。关闭“Log LUT”后恢复原相册选择器。

### 5.2 无效：往 `/odm/etc/camera/filters_lut/` 补文件

代码里 `Lut3dDataManager` 有一条 9 / 10 号表：

```java
Y = ModelStorage.MODEL_PATH + "filters_lut/"      // = /odm/etc/camera/filters_lut/
T.put(9,  Y + "log_video_sdr_3dlut.cube");
T.put(10, Y + "log_video_hdr_3dlut.cube");
static float[] i(int index, boolean hdr) { return nativeLoadBinaryLutFile(T.get(index), hdr); }
```

看起来补文件就能换 LUT，实测**不成立**：

1. 把官方 O-Log v1 → Rec709（17³ ASCII，与目录里其它 `.cube` 同格式）放进
   `payload/filters_lut/`，`bind_merged_dir` 挂载成功、文件可读（132,720B），
   **画面无任何变化**。
2. 再换成"全绿"探针（4913 个点全是 `0 1 0`），切「无」→「SDR」强制重选，
   **画面仍然不变**。
3. 全程 `Lut3dDataManager.i(9/10)` **一次都没被调用**
   （hook 了 `i(II)` 并只在 index 9/10 时打日志，日志为空）。

⇒ 内置三档（无 / SDR / HDR）由 **native 渲染层内置**提供，不由 `/odm/etc/camera/filters_lut/`
的外部 `.cube` 驱动；`Lut3dDataManager` 那两条 9/10 只服务滤镜 / palette 那条链。
**要换 LUT 就走 5.1 的导入入口，别改 payload。** 原文件已从 `module/payload/filters_lut/` 移除。

## 6. 「录制效果直出」定位与验收

### 6.0 2026-11-07 失败对照（已由 r173 修复）

当时实机测试与用户肉眼结果一致：

| 直出开关 | 自定义 LUT 预览 | 成片 | 判断 |
|---|---|---|---|
| 关 | 变色 | 灰色 Log | 预览修复有效，仍未直出 |
| 开 | **不变色** | **灰色 Log** | 直出失败 |

本地样本：

```text
output/lut_preview_fix/direct_off_2383lumix1.mp4
output/lut_preview_fix/direct_on_2383lumix1.mp4
```

两段视频的编码信息只能说明路线变化：`direct_on` 是 HEVC `yuv420p` 8-bit BT.709，
`direct_off` 是 HEVC `yuv420p10le` 10-bit BT.2020/BT.709 transfer。二者都不是“LUT 已应用”
的证据。

当时列出三件事等待证伪：

1. `mm.o.a` 是否真的收到正确的 LUT 文件名；
2. 同一会话是否真的收到整数模式值 `2`；
3. 打开直出后，录像 Surface 是否仍经过 App 的 `nn.p` 输出渲染器。

为此代码当时增加了逐会话一次性参数日志，以及限频的输出链日志。当时计划在晚间复测中
同时抓 App 与 HAL logcat，并按以下顺序检索：

```text
LogLut: HAL session=
LogLut: output EGL surface created
LogLut: output pipeline
LogLut: output renderer drew frame=
com.oplus.log.video.mode
KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME
```

**验收只看取景器和成片是否出现 LUT 色调变化。** 只要画面仍是灰色 Log，就不能宣布成功。
该失败状态最终由 r173 修复。

以下为历史实验记录，含已作废的误判，保留是为了避免重复走同一条路。

### 6.0.1 历史记录：最初认为该开关是空开关

四段对照，同一机位、Professional video / 1080·30·Log、HEVC 8bit、约 15 Mbps。
测法：PyAV 解码，取中心 1/2 区域逐帧平均，比 `meanRGB` 与通道差（饱和度代理）。

| 段 | LUT | 直出 | 时长 | meanR | meanG | meanB | sat | 录制时刻 |
|---|---|---|---|---|---|---|---|---|
| A | SDR | **关** | 17.4s | 100.6 | 90.9 | 60.5 | 40.1 | 15:26 |
| B | SDR | **开** | 18.2s | 100.1 | 90.5 | 60.1 | 40.0 | 15:27 |
| C | ref | **开** | 19.9s | 102.0 | 87.3 | 57.0 | 45.0 | 15:33 |
| D | ref | **关** | 21.5s | 103.1 | 87.4 | 57.1 | 45.9 | 15:34 |
| E | 无 | —（无开关） | 3.7s | 101.4 | 86.3 | 57.4 | 44.1 | 15:35 |

- **A vs B**（同分钟，只动开关）：ΔR 0.5 / ΔG 0.4 / ΔB 0.4 / Δsat 0.1 ⇒ 无差别。
- **C vs D**（同分钟，自定义 LUT，只动开关）：ΔR 1.1 / ΔG 0.1 / ΔB 0.1 / Δsat 0.9 ⇒ 无差别。
- **E 作对照**（不选 LUT）：与 C/D 同量级（sat 44.1 vs 45.0/45.9）⇒ 连"选哪个 LUT"都不进文件。
- 逐像素复核 A/B 同一时刻帧：`mean|diff| = 2.55`（HEVC 噪声量级），Y 分位 p1/p50/p99
  两段分别是 14/97/194 与 14/98/194，同一分布。

当时得到的结论是：这些样片都录到原始 Log 流，打开与关闭直出没有可见色彩差别。这个测量
只能证明那一轮没有成功直出；当时自定义 LUT 的预览路径也尚未修好，因此不能用它解释
2026-11-07 关闭直出后预览会变色、打开后反而不变色的现象。

在 r173 修复前，用这些历史版本交付带 LUT 的成片只能后期套（或用 §5.1 的 LUT 在剪辑里
还原）；r173 已支持所选 LUT 的预览和直出。

### 6.1 「X9U 能直出，能不能补给 X8U」——两条路都验死了（r153 / r154）

用户反馈 X9U 的那个开关是真直出，于是按 X9U 反编译出两条可能的落差逐条补，都在设备上实测：

**路一：答案 `func_master_video_log_custom_lut` = true（r153，`LogLutDirect`）**

`rm.e.getSupportFunction(String)` 是 33 项「名字 → 索引」表，末尾统一 `invoke-super`。
这个功能名在整份 APK 里**只出现两次**：一次在表里，一次在
`rm.e.jk(EventRecordDataBuilder)`——那是埋点构建器，只用来填 `setLutType` /
`setPreviewLut` 两个统计字段。所以答案改成 true 也只能改统计，不改录制。

实测（1080·30·Log，同一机位，只差开关）：

| 段 | LUT | 直出 | meanRGB | sat |
|---|---|---|---|---|
| A | SDR | 开 | 94.8 / 82.8 / 55.0 | 39.8 |
| B | SDR | 关 | 95.2 / 82.9 / 55.1 | 40.1 |

逐像素 `mean|diff| = 3.61`，Y 分位两段同为 `15 / 96 / 201`（同一分布）。
日志坐实唯一调用方：`custom lut feature queried by rm.e (stock false), answering true`。

**顺带纠正一条老结论**：这个槽位背后的配置行
`com.oplus.camera.preview.hdr.transform.lut.video.support` **原厂就是 1**
（`oplus_camera_config` 解密后 585 条里有它，值 `1`；日志也报 `stock true`）。
所以 X8U 不是「配置没开」，配置从来就是开的。

**路二：把 X9U 的 LUT 文件补进来（r154）**

`Lut3dDataManager` 的路径表（`d()`）里索引 **9 = `filters_lut/log_video_sdr_3dlut.cube`**、
**10 = HDR 那个**，`i(index, hdr)` 走 `nativeLoadBinaryLutFile(path, hdr)`。
把官方 O-Log v1 → Rec.709（17³，132,720 B）放进 payload 的 `filters_lut/`，
确认已挂到 `/odm/etc/camera/filters_lut/log_video_sdr_3dlut.cube`，再录 A/B：
Δsat **0.2**、ΔRGB ≤ 0.9，仍是同一分布。

日志给了原因——`Lut3dDataManager.i(int,boolean)` 全索引观测（不只 9/10）：

```
lut table 1 loaded: null (no file)      ← 相机启动时唯一一次，之后再无调用
```

**索引 9 / 10 / 15 一次都没被加载。** 也就是说 Log 预览与录制都不走这张表：
面板选档时 `LogLutMenuView.setSelectedLutPositionAndApply` 确实调了
`Lut3dDataManager.n(name)`（写索引 15），但没有任何代码再去取索引 15 的数据。

**⇒ 结论：X8U 的落差在 HAL/编码管线，不在 Java 侧，也不在数据文件。**

支撑证据（两台 app 逐项对照，dex 在 `work/x9u_cam/`）：`feature.logvideo` 包 28 个类一致、
`LogLutMenuView` 方法数 90 vs 89、`libCombineLut.so` md5 相同、`LogVideoSDRBt709` 字符串
两边都有（X8U 是 `classes9/27.dex`）。**同一份代码、同一个库、同一份配置，只有平台不同**
（X9U = 高通 SM8850，X8U = MTK）。这和「6X 预览」「片门全开」那种只差一个开关的情形不同：
这次差的是实现。

留档代码：`app/src/main/java/local/jiege/hook/video/LogLutDirect.java`（路一的答案 + 两条观测）。
cube 已从 payload 移除，避免留死数据。

### 6.2 历史实验：`master.video.feature.list[3]` 只证明请求进入 HAL（r162）

前两轮测试漏了一个与“是否支持自定义 LUT”不同的门：`MasterVideoDecision.c(3, 0)`。
它不控制 UI，而是决定 `MasterVideoDecision` 用哪套 Log 直出实现：

| feature[3] | 实现 | 发给 HAL 的 `KEY_LOG_VIDEO_MODE` |
|---|---|---|
| 0（X8U 原厂） | `StraightOutOfTone` | 继承基类的常量 `0` |
| 1 | `MasterToneLutByHal` | 正常直出请求为 `2`，受限请求加防抖时走 `1` |

X8U 的 `com.oplus.camera.master.video.feature.list` 原厂只有 1 项（值为 `1`），所以索引 3
落到默认值 0。`MasterToneLutByHal.b(DecisionParam)` 才是原厂真正的 HAL 直出判定：

```java
if (logVideo && directMode == 2) return 2;
if (logVideo && directMode == 1 && stabilization) return 1;
return 0;
```

Pro 视频构建相机会话时，`rm.e.Y7()` 会把两项值送进 HAL：

```java
KEY_CONFIGURE_MASTER_VIDEO_CUSTOM_LUT_NAME = <当前自定义 LUT 名>
KEY_CONFIGURE_LOG_VIDEO_MODE = decision.b(Il())
```

还有一个容易漏掉的内部状态：UI 选择 Log 后，Presenter 的 `hh.a.e()` 会被监看钩子回答为
`on`，但 `MasterVideoMode.vk()` 仍直接读 `pref_log_video_key`，所以最初实际下发的仍是 0。
最终实现必须同时让 `vk()` 在“当前格式为 log”时回答真。

最终实现方式不是改编码文件，也不是继续观察 `Lut3dDataManager.i()`，而是：

1. 让 `o9.h.c(3, 0)` 返回 1；
2. 在 Pro Video 对象创建后，将已初始化的 `o9.h$a.a.c` 从 `StraightOutOfTone`
   替换为 `MasterToneLutByHal`。不能在模块安装阶段提前读取单例，否则相机会黑屏；
3. 让 `rm.e.vk()` 在 `n9.e.K0 == "log"` 时回答真，SDR/HDR 分支保持原样；
4. 保持现有监看 LUT 选择和“录制效果直出”开关。

PKJ110 实测（1080p30、Log、自定义 `ref.cube`、同一机位，各约 3.6 秒）：

| 样片 | 直出 | HAL 模式 | 像素格式 | mean RGB | 饱和度代理 |
|---|---:|---:|---|---|---|
| A | 开 | 2 | yuv420p | 104.6 / 98.3 / 76.8 | 27.8 |
| B | 关 | 0 | yuv420p10le | 104.6 / 101.3 / 83.1 | 21.4 |

日志同时显示 `com.oplus.log.video.mode: 2`、`lut=true`、`format=log` 和
`mFeatureName=logVideo`。这些只能证明会话请求和编码形式发生了变化。2026-11-07 复看否定了
“LUT 已进入编码文件”的结论：`mode=2` 不证明 HAL 应用了 LUT，8-bit 也不证明画面被变换。
当时打开直出后本机仍输出灰色 Log，因此 r162 单独不能算完成；后续 r173 才补齐了预览
契约和编码链。

### 6.3 历史实验：分辨率 / 帧率 / 监看模式矩阵（r162）

2026-11-06 在 PKJ110 上，专业视频、自定义 `ref` LUT、录制效果直出开启，逐档录制并读取
HEVC 实际像素格式。`yuv420p` 表示 8-bit HAL 直出路径；`yuv420p10le` 表示仍为 10-bit
Log 编码。

| 分辨率 | 帧率 | 录制格式 / 监看 LUT | 当时观察到的编码形式 | 当时判断 |
|---|---:|---|---|---|
| 1080p | 30 | Log / ref | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 1080p | 60 | Log / ref | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 1080p | 120 | Log / ref | `yuv420p` 8-bit | 当时由 `Vj()` 强制请求 |
| 4K | 30 | Log / ref | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 4K | 60 | Log / ref | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 4K | 120 | Log / ref | `yuv420p` 8-bit | 当时由 `Vj()` 强制请求 |
| 1080p | 30 | Log / SDR Rec.709 | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 1080p | 30 | Log / HDR HLG | `yuv420p10le` 10-bit | 没有可靠直出证据 |
| 4K | 30 | Log / SDR Rec.709 | `yuv420p` 8-bit | 不能证明 LUT 生效 |
| 4K | 30 | Log / HDR HLG | `yuv420p10le` 10-bit | 没有可靠直出证据 |

这里的“SDR / HDR 监看 LUT”指录制格式仍为 **Log**，只是 LUT 面板选择
`SDR (Rec.709)` 或 `HDR (HLG)`。两者不是一回事：

* 4K30 SDR Rec.709 当时测得开关前后中心区域饱和度变化 `+3.79`，RGB 均值变化
  `-1.52 / -2.88 / -4.13`，首帧平均像素差 `5.2`，并且像素格式从 10-bit 变为 8-bit。
  这些统计只能说明两个编码路线不同，不能单凭它们确认 LUT 色调进入了成片。
* HDR HLG 开关虽然可点亮，HAL 在 1080p30 决策日志里也返回 `mode=2`，但输出仍是
  `yuv420p10le`，开关前后首帧平均像素差只有 `1.71`（p50=1），4K30 更是 `1.45`，
  与 HEVC 编码噪声同量级。当时只能判断这条 HAL 路径没有得到可验收的 HLG 直出结果，
  不能据此确认任何一侧真正应用了 LUT。

若把录像格式直接切到 `SDR` 或 `HDR`，直出也不是可用方案：

| 录制格式 | UI | HAL 决策 |
|---|---|---|
| SDR | LUT 面板仍在，但没有“录制效果直出”卡片 | `format=sdr, lut=false -> mode=0` |
| HDR | LUT 入口和直出卡片都不显示 | `format=hdr, lut=false -> mode=0` |

因此这张矩阵不能再作为“可用范围”。它只保留为编码路线记录；在 r173 之前，实机验收仍未
通过，不能用分辨率、帧率或像素格式推断成片已经带上 LUT。

### 6.4 历史实验：r163 只强制了 120fps 的 HAL 请求

`rm.e.Vj()` 读取直出设置时，如果当前是 120fps 或 8K 且设置为 2，会把值降为 1。
`MasterToneLutByHal.b()` 只有在 `directMode == 2` 时返回 HAL 模式 2，所以 120fps 最终
变成了 `mode=0`。r163 在 `Vj()` 返回后只对 120fps 把 1 恢复成 2，8K 保持原厂限制。

PKJ110 当时观察到的结果：

| 规格 | HAL 请求 | 编码形式 | 实际帧率 | 可解码 |
|---|---|---:|---:|---|
| 1080p120 | `com.oplus.log.video.mode=2` | `yuv420p` 8-bit | 119.7fps | 是 |
| 4K120 | `com.oplus.log.video.mode=2` | `yuv420p` 8-bit | 119.7fps | 是 |

“可解码”和“收到 mode=2”都不等于 LUT 直出成功；这两档没有留下足以通过最终验收标准的
色彩对照，结论作废。

### 6.5 历史实验：HDR HLG 没有得到可验收的直出结果

r163 也验证了强制 HLG 的路径：把 `MasterToneLutByHal.b()` 分别强制为 1 和 2，并把
`VIDEO_DATASPACE` 从原厂 BT.2020 改成 `BT2020_HLG`。两种组合都能产出可解码文件，
当时根据统计量判断画面发生了变化，但该判断没有经过肉眼成片验收，现已作废。编码器当时
都回落到：

```
pix_fmt = yuv420p (8-bit)
color_primaries = BT.709
color_trc = BT.709
colorspace = BT.709
range = limited
```

关闭直出则保留 10-bit BT.2020。可以保留的事实只有：强制 HLG 实验产出了 8-bit BT.709
编码形式，没有得到真正的 HLG 10-bit 输出。至于 LUT 是否实际烘入，仍按最终验收标准视为
未证实。该实验分支没有保留，避免把 SDR 文件误标成 HDR。

### 6.6 r164 候选方案：把 Log+LUT 录像切到 App 输出渲染器

2026-11-08 重新对照 X8U 与 X9U 的反编译代码后，录像器选择公式两边一致：

```text
is_use_fast_video_recorder == true  -> type 3
is_use_media_codec         == true  -> type 2
video_stream_media_codec   == true  -> type 4
以上都不成立                         -> type 1
```

对应关系如下：

| 作用 | X8U | X9U |
|---|---|---|
| 录像处理器 | `om.e0` | `pm.i0` |
| 录像器包装 | `om.n0` | `pm.s0` |
| App MediaCodec | `om.l` | `pm.l` |

X8U 旧日志已经抓到 `OCAM_VideoRecorder: init, mRecorderType: 1`。type 1 由 HAL 直接向
原生录像 Surface 输出，App 的 `nn.p` 输出渲染器不在录像链路上。这样即使 HAL 收到了
`com.oplus.log.video.mode=2`，也不能保证 LUT 被写入；反过来，只要把实际画面送进 App
输出渲染器并给 `nn.p` 设置 LUT 索引，就有一段可由 App 自己保证的色彩处理链。

X9U 的 `pm.l.s()` 与 X8U 的 `om.l.s()` 结构相同：

```java
Surface input = encoder.createInputSurface();
if (P0) {
    l0.c(input, videoSize);   // 交给输出渲染器
} else {
    M0 = new InputThread(input);
}
```

因此 r164 的方案不是继续相信 HAL 的 `mode=2`，而是只在以下条件全部成立时精确改选
type 2：

```text
Log 格式
录制效果直出 = 2（开启）
明确选中 SDR / HDR / 自定义 LUT
当前调用来自 om.e0.k() 创建录像器
```

同时保护 `om.l.P0=true`，让编码输入 Surface 必须交给 `nn.p` 输出渲染器。`direct=1`
明确表示关闭，不能再按旧逻辑当成开启。关闭直出、未选 LUT、非 Log 和预建录像器都不会
触发覆盖。

最终构建进一步把 `P0` 保护绑定到 `om.e0.k()` 实际创建并持有 type 2 编码器的那个录像器
实例，避免旁路或预建的 App MediaCodec 误触发。`nn.p.onDrawFrame()` 每帧还会校验
`e` 字段；只要直出与普通 LUT 条件仍成立，但索引偏离当前选择，就立即纠正并保留日志。
这样可以覆盖“表面创建后用户又切换 LUT”的情况。

预览侧也做了同场景限制。`gl.s` 同时保存普通 3D LUT 映射和合成基础色调 LUT：

- 普通表在 `gl.s.u`，当前索引在 `gl.s.v`；
- 合成表由 `gl.s.f654o`、开关 `gl.s.q` 控制；
- 打开直出后，合成表可能抢先覆盖已加载的 SDR / 自定义 LUT。

r164 只在该场景下：

1. 阻止合成表重新开启；
2. 把普通 LUT 输入索引固定为当前选择；
3. 继续让普通 LUT 走关闭直出时已经验证有效的 GL 路径。

这里当时仍有一个边界：X9U 文件子集只能证明两边代码结构相同，不能证明 X9U 在对应配置下
一定选择 type 2。r164 是依据线路差异做出的 App 侧兜底；这条线路后续并入 r173，并由
实机画面完成验收。

### 6.7 r164 构建记录（失败对照）

本轮源码已重新构建并通过：

```text
APK：Muse 4.4.4-r164（versionCode 607）
APK SHA-256：8665BC27E9D6933FE4F00B0D515DB8C20178D78E98B43667BE00218483BD9F89
ZIP SHA-256：37A03C71A4C32A9DACEB0EF381E597901DEE550C555D92E8372DA8C61063BCCE
构建探针：13 条直出日志字符串全部存在
```

当时计划次日先看预览，再看录像：

1. 专业视频、4K30、Log，选择 SDR Rec.709，打开录制效果直出；
2. 预览立即出现 Rec.709 色调后，再录 3～5 秒；
3. 关闭直出作基线，录同样的机位；
4. 改用自定义 LUT 重复；
5. 只生成成片中间帧的压缩预览做前后对比。

预览成功时应看到：

```text
GL synthetic LUT requested=false ... guarded=true
GL direct LUT draw guard index=9 ... ordinaryPresent=true synthetic=true->false
ordinary LUT made effective on preview ...
```

录像成功时至少应看到完整序列：

```text
recorder config read key=is_use_media_codec ... effective=true
recorder config read key=is_use_fast_video_recorder ... effective=false
recorder config read key=video_stream_media_codec ... effective=false
recorder creation entered ... wantedAppCodec=true
recorder selected actual=2
recorder selected App MediaCodec actual=2 target=true appCodec=om.l
app encoder startup ... P0=true ... targetCodec=true ... branch=l0.c/renderer
encoder callback surface=valid
output surface assignment A=valid ...
output renderer drew frame=... lutIndex=9
```

失败时按以下顺序定位：

- 没有三条 `recorder config read key=`：运行时键名或 Hook 安装有问题；
- `recorder selected actual=1`：没有命中真正的 `om.e0.k()` 构造；
- `actual=2` 但 `branch=M0/input`：`P0` 保护没有生效；
- `actual=2`、`P0=true` 但没有编码 Surface：检查 Surface 回调和输出目标 `A`；
- 预览没有 `GL direct LUT draw guard`：先确认 `direct=2`，不要继续看编码器；
- `ordinaryPresent=false`：普通 LUT 没有上传，检查选择索引和 `Lut3dDataManager` 回调；
- `ordinaryPresent=true` 但画面仍无色：继续查 `gl.s.y0()` 最终选中的 shader 和纹理采样。

### 6.8 r165：修复直出开启后缺失的预览 LUT 契约

r164 实机仍无色后，进一步沿相机会话初始化链路检查：

```text
rm.e.Y7()
  -> BaseMode.Y7()
  -> si(d7(0))
  -> BaseMode.v2
```

X8U 的 `rm.e.d7(0)` 只处理 `Hl()` 返回 2、4、6 的普通 SDR、HDR、自定义 LUT 场景：

```text
Hl=2 -> 9   SDR
Hl=4 -> 10  HDR
Hl=6 -> 15  自定义
```

直出开启时，HAL 的 `MasterToneLutByHal.b()` 仍返回 `mode=2`，但另一决策策略在
`logKind=2,direct=2` 等组合下让 `Hl()` 返回 5。`d7(0)` 对 5 没有映射，最终返回 0：

```text
BaseMode.v2=0
key_support_preview_render_lut=false
getPreviewRenderLutType()=0
TexturePreview.FLAG_PREVIEW_RENDER_LUT 不创建
普通预览 LUT 分支不执行
```

因此 r164 在 GL 绘制末段强制普通 LUT 太晚，无法补出一个在效果图创建阶段就没有建立的
节点。r165 改为在会话决策层修复同一契约，只在以下条件同时成立时覆盖：

```text
Log LUT 模块启用
+ Log 格式
+ 录制效果直出=2（开启）
+ 已选择 SDR / HDR / 自定义 LUT
```

覆盖点如下：

1. `rm.e.d7(0)` 返回 0 时改为 9、10 或 15；
2. `BaseMode.si()` 在写入 `v2` 前再次校正；
3. `dm.u3b.getPreviewRenderLutType()` 返回当前选择；
4. `getSupportFunction("key_support_preview_render_lut")` 返回 true；
5. 冷启动或 Surface 创建前先恢复持久化的 LUT 选择。

关闭直出、未选 LUT、非 Log 或其他模式不会触发上述覆盖。r164 的 App MediaCodec type 2
路线继续保留，用于解决预览恢复后成片仍可能走 HAL 原生 Surface 的问题。

X9U 对照结果：`work/x9u_classes2_jadx/sources/sm/e.java` 的 `sm.e.d7(0)` 也采用相同
`2/4/6 -> 9/10/15` 映射，默认决策策略同样没有给 `Hl=5` 提供预览 LUT 类型。所以不能靠
复制一份 X9U 配置修复 X8U；r165 是在 X8U 会话初始化层补齐缺失的契约。

r165 构建产物：

```text
APK：Muse 4.4.4-r165（versionCode 608）
APK：E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r165.apk
ZIP：E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r165-KSU.zip

APK SHA-256：
984C9CEC6F4B5F02315EF9C10055F4DFFFBCB698C6EBC16D66C673A43E5C567D

ZIP SHA-256：
35A3E9BAAB5EEBCD12C70AC23AFA9CD86AD3C90D2CA75C5996358CC930FCA74C
```

APK 使用 v3 签名验证通过；ZIP 内的 r165 APK 与上面的 APK SHA-256 完全一致。构建包含
19 条直出日志探针，Gradle 结果为 `BUILD SUCCESSFUL`。

当时计划次日先在**不录像**的情况下检查预览：

1. 专业视频、4K30、Log，固定机位；
2. 选择 SDR Rec.709，打开录制效果直出；
3. 立即观察预览，不能因为日志出现 `mode=2` 就判定成功；
4. 预览出现 Rec.709 色调后才录 3～5 秒；
5. 关闭直出，录制同样机位的 Log 基线；
6. 选择自定义 LUT，再重复直出开关对照；
7. 只生成中间帧的压缩预览做肉眼对比。

直出开启且选择 SDR 时，预览侧应出现：

```text
preview LUT contract d7(0) original=0 effective=9 Hl=5 ...
preview LUT contract BaseMode si original=0 effective=9 ...
preview LUT contract BaseMode v2=9 ...
preview LUT contract preview LUT type original=0 effective=9 ...
preview LUT contract preview support called by ... stock=false effective=true ...
GL synthetic LUT requested=false ... guarded=true
GL direct LUT draw guard index=9 ... ordinaryPresent=true synthetic=true->false
ordinary LUT made effective on preview ...
```

如果预览仍无色，按以下边界分流：

- 没有 `d7(0)` 或 `BaseMode v2`：会话初始化 Hook 没有装进相机进程；
- `d7(0)` 和 `v2` 都是 9，但 `getPreviewRenderLutType()` 仍是 0：回调修复没有命中；
- 三条契约日志都是 9，但没有 `GL direct LUT draw guard`：`TexturePreview` 或 GL 状态没有
  把索引传到绘制阶段；
- 出现 draw guard，但 `ordinaryPresent=false`：LUT 表没有上传，检查选择路径与
  `Lut3dDataManager` 回调；
- 出现 draw guard 且 `ordinaryPresent=true`、`synthetic=false`，预览仍是灰色：问题已经
  不再是会话契约，继续检查 `gl.s.y0()` 选中的 shader 和纹理采样。

如果预览恢复颜色而成片仍是灰色，则说明 r165 已修复预览节点。当时据此把下一步限定到编码
链，检查是否出现 `recorder selected actual=2`、`P0=true`、编码 Surface 有效和
`output renderer drew frame=... lutIndex=9`。该排查方向最终由 r173 完成；此时仍不能仅凭
编码格式或像素均值宣布成功，必须看实际成片颜色。

### 6.9 r173 最终实机验收

2026-11-08 在 PKJ110 上完成正式验收。测试条件为专业视频、4K30、Log、自定义
`2383lumix1` LUT。诊断覆盖属性均已清空：

```text
persist.omnicam.loglut.halmode =
persist.omnicam.loglut.clearflags =
```

因此本轮不是通过人工修改 HAL 模式得到的偶然结果。会话日志显示原生请求仍为 2：

```text
HAL session=31 ... value=2 int=2 override=-1 applied=false sent=2 direct=2
selectedLutIndex=15 customLut=true
```

正式 12.166 秒样片的录制从 15:41:53.124 开始，编码链完整：

```text
recorder selected actual=2 direct=2 format=log selectedLutIndex=15 armed=true
recorder selected App MediaCodec actual=2 target=true appCodec=om.l
app encoder startup ... P0=true ... targetCodec=true protected=true branch=l0.c/renderer
encoder callback surface=valid size=2160x3840 selectedLutIndex=15 customLut=true
output EGL surface created target=A/video-output surface=valid+egl@2160x3840/p renderer=nn.p
output renderer drew frame=300 lutIndex=15 selectedLutIndex=15 customLut=true
output pipeline ... outputRendererFrameCount=365 outputFrames=365
```

预览侧同一会话持续使用普通 LUT，没有被合成 LUT 抢回：

```text
GL final shader choice direct=2 format=log shader=h0
bypassOesPreviewLut=false activeLut=true inputIndex=15
syntheticEnabled=false syntheticTable=false
```

正式样片：

```text
output/lut_preview_fix/r174_native_on_154153.mp4
124,086,835 字节，12.166 秒，hvc1 2160×3840，旋转 0°
SHA-256 BBD09F369E95BA99AFC6C148194DA66FD793C9C87B48E1EEABF5E0EB0B77A39E
```

中间帧和 A/B 对比只保留为本地文件，不把原尺寸媒体直接放进会话：

```text
output/lut_preview_fix/r174_native_on_mid.png
output/lut_preview_fix/r174_native_video_ab_preview.jpg
output/lut_preview_fix/r174_native_video_ab_detail_preview.jpg
output/lut_preview_fix/r174_native_video_ab_metrics.json
```

中央区域指标中，直出关闭时平均饱和度为 23.35%，开启时为 50.79%；平均 ΔE76 为
27.34，中位数 25.11。视觉上开启态具有所选 LUT 的暖色、高反差和高饱和，关闭态仍是
灰色 Log 基线。用户肉眼确认成片颜色正常。

跨模式复核也已通过。15:46:57 切入人像时日志为
`camera mode scope ... current=portrait proVideoActive=false`，`BaseMode v2=0`；
15:47:24 切回普通照片时为 `current=common proVideoActive=false`、`BaseMode v2=7`，
没有 r173 的强制 LUT 契约覆盖。人像 1.5×和普通照片 1×均为正常色彩。截图保存在：

```text
output/lut_preview_fix/omnicam_portrait_1x.png
output/lut_preview_fix/omnicam_back_photo.png
output/lut_preview_fix/r174_mode_retest.log
```

最终交付包：

```text
APK：Muse 4.4.4-r173，versionCode 616
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r173.apk
SHA-256 A290D3B79F3F807CEA46B518A06E761170D6889B9D250D511E855F79DF678D47

ZIP：E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r173-KSU.zip
SHA-256 2E2E99BED406D1773B5FE8E8C5C5240EBA7B4F26F80E198966948F2E622CD765
```

APK 版本和 v3 签名已验证；ZIP 内 APK 的大小为 5,201,651 字节，SHA-256 与 APK 文件
完全一致。`python tools/verify_port_artifacts.py` 六组检查全部通过。

## 7. 复现

```bash
# 装 r152（含 LogLut hook，默认开）
adb push OmniCam-Muse-*.zip /data/local/tmp/r.zip
adb shell su -c 'ksud module install /data/local/tmp/r.zip'

# 相机 -> 视频 -> 专业视频 -> 格式选 Log
# 点取景器左上角那颗「斜杠星形 + LUT」药丸（1080x2376 下约 140,210）

# 看三道门的实际答案
adb shell su -c 'grep -h LogLut /data/adb/lspd/log/modules_*.log | tail'
```

开关：`ModuleSettings.LOG_LUT`（`log_lut`，默认 true）。关掉即恢复原样（面板消失）。

## 8. 相关类与键（混淆名）

| real | obfuscated | 备注 |
|---|---|---|
| `LogVideoPresenter` | `ih.g` | 字段 `X` = LogLutMenuView，`P` = LogPreviewView |
| `MasterVideoDecision` | `o9.h` | `m()`=c(4), `i()`=c(8), `p()`=c(14) |
| `LogVideoModel` | `hh.a` | `e()` 读 `pref_log_video_key` |
| `Lut3dDataManager` | 同名 | `i(IZ)` 里调 `nativeLoadBinaryLutFile` |
| `LogLutMenuView` | 同名 | `D()` 显示面板，`I2()` 是 presenter 侧的再显示 |

`n9/c` = `MemKeyLocal`：`n` = `pref_is_log_preview_expanded`、`l` = `pref_second_level_panel_key`
`n9/e`：`W0` = `pref_log_video_key`、`X0` = `pref_log_video_monitor_state`
（`1`=直出关闭，`2`=直出开启）、`c0` = `pref_subsetting_key`
