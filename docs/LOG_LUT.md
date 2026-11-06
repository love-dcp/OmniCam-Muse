# Log LUT 还原监看（PKJ110 / X8U）

> 定案于 2026-11-05，r152。设备：PKJ110（OP5DD3L1 / sun / SM8750），相机 7.006.123。

## 1. 一句话结论

相机包里**整套「LUT 还原监看」都是现成的**：面板、三档（无 / SDR Rec.709 / HDR HLG）、
自定义 `.cube` 导入、以及 `LogLutMenuView` / `LogPreviewView` / `LogVideoPresenter` 三个类。
它在这台机器上从不出现，只是因为**三个"是否支持"询问全都答 0/off**。把这三个答案改掉，
入口自己就出来了，面板、列表、导入和监看全是原厂代码。

两点实测边界（下文各有专节）：

- 自定义 LUT 只能走面板导入，往 `/odm/etc/camera/filters_lut/` 塞文件没用（§5）；
- 「录制效果直出」开关在本机不接编码链路，开与关录到的文件等价，LUT 只作用于取景器（§6）。

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

## 6. 「录制效果直出」在本机是空开关（实测，别再花时间）

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

结论：**录到的永远是原始 Log 流，LUT 只作用于取景器显示**；「录制效果直出」开关在本机
（X8U / MTK）没有接到编码链路上，打开与关闭得到的文件等价。副标题"暂不支持 120fps 或 8K"
只是 UI 文案，真实情况是 1080p30 也不起作用。

要交付带 LUT 的成片，只能后期套（或用 §5.1 的 LUT 在剪辑里还原）。

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

### 6.2 已解决：`master.video.feature.list[3]` 选择 HAL 直出策略（r162 实测）

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
`mFeatureName=logVideo`。因此直出确实进入编码文件，不只是监看。开启直出后本机输出为 8-bit
HEVC；关闭时仍为 10-bit HEVC，这是当前 HAL LUT 路径的格式限制。

### 6.3 分辨率 / 帧率 / 监看模式矩阵（r162 实测）

2026-11-06 在 PKJ110 上，专业视频、自定义 `ref` LUT、录制效果直出开启，逐档录制并读取
HEVC 实际像素格式。`yuv420p` 表示 8-bit HAL 直出路径；`yuv420p10le` 表示仍为 10-bit
Log 编码。

| 分辨率 | 帧率 | 录制格式 / 监看 LUT | 实际像素格式 | 直出 |
|---|---:|---|---|---|
| 1080p | 30 | Log / ref | `yuv420p` | 是 |
| 1080p | 60 | Log / ref | `yuv420p` | 是 |
| 1080p | 120 | Log / ref | `yuv420p` | 是（r163 强制 Vj） |
| 4K | 30 | Log / ref | `yuv420p` | 是 |
| 4K | 60 | Log / ref | `yuv420p` | 是 |
| 4K | 120 | Log / ref | `yuv420p` | 是（r163 强制 Vj） |
| 1080p | 30 | Log / SDR Rec.709 | `yuv420p` | 是 |
| 1080p | 30 | Log / HDR HLG | `yuv420p10le` | 实测未写入，见下 |
| 4K | 30 | Log / SDR Rec.709 | `yuv420p` | 是 |
| 4K | 30 | Log / HDR HLG | `yuv420p10le` | 实测未写入，见下 |

这里的“SDR / HDR 监看 LUT”指录制格式仍为 **Log**，只是 LUT 面板选择
`SDR (Rec.709)` 或 `HDR (HLG)`。两者不是一回事：

* SDR Rec.709 直出可用。4K30 开关前后中心区域饱和度变化 `+3.79`，RGB 均值变化
  `-1.52 / -2.88 / -4.13`，首帧平均像素差 `5.2`，并且像素格式从 10-bit 变为 8-bit。
* HDR HLG 开关虽然可点亮，HAL 在 1080p30 决策日志里也返回 `mode=2`，但输出仍是
  `yuv420p10le`，开关前后首帧平均像素差只有 `1.71`（p50=1），4K30 更是 `1.45`，
  与 HEVC 编码噪声同量级。结论：当前 HAL 路径没有把 HLG LUT 烘进编码文件。

若把录像格式直接切到 `SDR` 或 `HDR`，直出也不是可用方案：

| 录制格式 | UI | HAL 决策 |
|---|---|---|
| SDR | LUT 面板仍在，但没有“录制效果直出”卡片 | `format=sdr, lut=false -> mode=0` |
| HDR | LUT 入口和直出卡片都不显示 | `format=hdr, lut=false -> mode=0` |

因此目前可用的范围是：**Log 录制 + ref 或 SDR Rec.709 监看 LUT，1080p/4K 的 30、
60、120 fps 可直出；HDR HLG 尚未真正落地。**

### 6.4 r163：120fps 强制直出

`rm.e.Vj()` 读取直出设置时，如果当前是 120fps 或 8K 且设置为 2，会把值降为 1。
`MasterToneLutByHal.b()` 只有在 `directMode == 2` 时返回 HAL 模式 2，所以 120fps 最终
变成了 `mode=0`。r163 在 `Vj()` 返回后只对 120fps 把 1 恢复成 2，8K 保持原厂限制。

PKJ110 实测：

| 规格 | HAL | 编码 | 实际帧率 | 可解码 |
|---|---|---:|---:|---|
| 1080p120 | `com.oplus.log.video.mode=2` | `yuv420p` 8-bit | 119.7fps | 是 |
| 4K120 | `com.oplus.log.video.mode=2` | `yuv420p` 8-bit | 119.7fps | 是 |

### 6.5 HDR HLG 直出：当前 HAL 不支持

r163 也验证了强制 HLG 的路径：把 `MasterToneLutByHal.b()` 分别强制为 1 和 2，并把
`VIDEO_DATASPACE` 从原厂 BT.2020 改成 `BT2020_HLG`。两种组合都能产出可解码文件，
且 LUT 确实改变了画面，但编码器都回落到：

```
pix_fmt = yuv420p (8-bit)
color_primaries = BT.709
color_trc = BT.709
colorspace = BT.709
range = limited
```

关闭直出则保留 10-bit BT.2020。也就是说，这台 PKJ110 的 HAL 直出 LUT 通路实际固定为
BT.709 8-bit，无法同时满足“LUT 烘入”和真正的 HLG 10-bit 输出。该实验分支没有保留，
避免把 SDR 文件误标成 HDR。


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
`n9/e`：`W0` = `pref_log_video_key`、`X0` = `pref_log_video_monitor_state`、`c0` = `pref_subsetting_key`
