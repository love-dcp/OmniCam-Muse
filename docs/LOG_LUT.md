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
