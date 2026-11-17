# 大师模式实况照片：播放比封面鲜艳（GR profile 中途切换）

## 现象

大师模式拍实况照片，相册里点开播放，画面比封面明显更鲜艳（封面正常）。
2026-11-14 复现，用户指出的样张是 `IMG20261114165042.jpg`。

## 结论

**不是 HDR 编码问题，也不是大师色彩（look）选哪一项的问题，是理光 GR 效果在实况录制中途被清掉，
造成同一次拍摄里「视频用 GR 的 LMT、封面用大师色彩的 LMT」。**

## 证据

### 1. 五张样张的 HDR 元数据完全一致

| 文件 | 时间 | 视频 colr | 封面 hdrgm | 分辨率 | 判定 |
|---|---|---|---|---|---|
| IMG20261114165042 | 16:50 | prim9/trc18(HLG)/matrix9 | 12 | 2576x1428 | **异常** |
| IMG20261114165331 | 16:53 | 同上 | 12 | 2576x1428 | **异常** |
| IMG20261114165515 | 16:55 | 同上 | 12 | 2576x1428 | 正常 |
| IMG20261114165605 | 16:56 | 同上 | 12 | 5720x3176 | 正常 |
| IMG20261114170346 | 17:03 | 同上 | 12 | 2576x1428 | 正常 |
| IMG20261114170419 | 17:04 | 同上 | 12 | 2576x1428 | 正常 |

全部是 HEVC 10bit (yuv420p10le) + HLG/BT.2020，都没有 clli/mdcv 亮度元数据。
所以「HLG 被播放器放大」这类解释不成立 —— 编解码参数一模一样。

### 2. 差异在像素层，可用饱和度量出来

解出内嵌 MP4 的中间帧，与封面算 HSV 平均饱和度：

```
IMD20261114165042  封面 0.320  视频 0.370  +0.049   ← 异常：视频比封面更艳
IMG20261114165331  封面 0.327  视频 0.381  +0.055   ← 异常
IMG20261114165515  封面 0.302  视频 0.249  -0.054   ← 正常
IMG20261114165605  封面 0.306  视频 0.255  -0.051   ← 正常
IMG20261114170346  封面 0.417  视频 0.307  -0.110   ← 正常
IMG20261114170419  封面 0.329  视频 0.267  -0.062   ← 正常
```

正常的一组，视频本来就比封面略淡（-0.05 左右）；异常那张反过来，多出 +0.05。
正负号翻转就是用户看到的「播放更鲜艳」。

### 3. look 不是变量

读 `.../mastercolor/live/<dir>/key.bin`：

- `p04000_0fb6…`（16:56，**正常**）= `MCOLKEY8 x8u main23`
- `p04000_4677…`（17:03，**正常**）= `MCOLKEY8 hncs main23`

正常样本里 x8u 和 hncs 都有，而异常样本（16:51-16:53）也是 `look=x8u`。
**所以「大师色彩选机型 X8U / HNCS / 原厂」与这个现象无关。**

### 4. 真正的时间线：GR 在实况录制中被清掉

```
16:53:19.153  GR effect source=presenter-table len=63     ← GR 效果好着
16:53:19.550  BasicTone profile=GR path=.../ricoh_gr/lmt
16:53:32.244  gr_effect_size=0 gr=0                        ← GR 被清成 0
16:53:32.317  BasicTone profile=master-colour path=.../live/t04089…
16:53:35.692  BasicTone profile=master-colour path=.../live/mb04089…
```

实况照片的快门在 16:53:29–31，即**录制先按 GR 的 LMT 开始，1–3 秒后 GR 被清掉，
封面/主图改用大师色彩的 LMT**。视频是录制时的像素，于是带着 GR 的鲜艳，封面是大师色彩 —— 不一致。

`gr_basictone.cpp` 里 profile 是**每次拍摄按 `gr_effect_size` APS 设置现判**的（`size==63` → GR），
不做会话级缓存，所以效果开关一抖，同一次实况的两半就会分到两个 profile。

16:55 之后日志里再没有 GR 相关行（`gr_effect_size=0`），全部走 master-colour，因此一直正常。

## 规避

- 用理光 GR 预设/GR 效果之后，**拍实况前重启一次相机**（force-stop 再开），或等 GR 效果彻底关掉再拍。
- 换「预设管理」也会连带清 GR 效果；清完当次拍摄仍可能残留，下一张起才稳定。

## 可选修复（未实施）

让 profile 在**一次 capture 会话内锁定**：capture 开始时取一次 `gr_effect_size`，结束前不再重读，
这样同一次实况的视频和封面必然同 profile。代价是 GR 效果切换要下一次拍摄才生效。

更保守的做法：把 profile 缓存到进程生命周期（跟大师色彩一样，改设置需重启相机），实现简单、
不会碰到录制中途的时序，但会牺牲 GR 效果的即时性。

---

## 5. r361 复核：三处堵点都在，但现象仍在（2026-11-14 17:46）

r361 加了三处防线后按同样流程复现（GR 一张 17:46:00 → 切大师 → 17:46:04 拍实况），
用户反馈「还是鲜艳」。逐项核对日志后，**可以确认三条 GR 通道这次都没碰上**：

```
17:45:56.866  Lut3d.load mode=gr grMode=true            ← 进 GR
17:46:00.736  GR effect source=presenter-table len=63   ← GR 拍摄
17:46:02.516  Lut3d.load mode=retroCamera grMode=false  ← 离开 GR，预览不再指向 GR 树
17:46:03.561  IPU filter capture_mode=professional filter_type=default   ← 预览滤镜已回大师
17:46:04.457  APS 请求：capture_mode=master, retro_camera_mode=false,
              gr_mode_particle_size=0, ultra_hdr_enable=1               ← 请求里没有 GR
17:46:05.37   BasicTone profile=master-colour（封面）
17:46:07.17   BasicTone profile=master-colour（主图）
```

`Lut3d.load`、`CombineLutHelper.init`、`GRCapMode.updateCaptureRequestTag` 三条路都没有在
非 GR 状态下被调用（后两条在本版相机里根本没被调用过：dex 里没有 `mGrEffectParam` 字段，
`updateCaptureRequestTag` 也不是原名，`hookAllMethods` 装了个空壳）。也就是说
**a. GR 的 LMT 树、b. 预览 CombineLut、c. 请求 tag 上的 GR 参数，这三条都不是这次的元凶。**

### 5.1 实况视频到底从哪来

```
17:45:55.769  OCAM_LivePhoto.Session[244c5e35] 建立（相机启动时）
17:45:55.770  OCAM_LivePhoto.VideoEncoder[...] setCameraId 0 => 12
17:46:03.565  OCAM_LivePhoto.Rgba2YuvConversion, width 1920 height 1440
17:46:03.565  OCAM_LivePhoto.Session[244c7bcf] genImageDataPool format 54 1920x1440
```

实况的「动态部分」是**把预览的 RGBA 纹理转成 YUV 再编码**（`Rgba2YuvConversion`，
固定 1920x1440），不是第二条成片管线。所以视频的色彩＝**预览画面在转 YUV 那一刻的样子**。
整段录制期间再没有任何 `IPU_FilterProcessUnitSDK.processImage` 参数变化、也没有第二次
`BasicTone`，即视频不会单独再过一次大师 LMT —— 它继承预览。

### 5.2 唯一还没被排除的开关：`mbPreviewHdr`

`OCAM_LivePhotoPresenter.onResume` 只在日志里出现过两次，而且是这次复现的关键：

```
17:45:55.501  onResume, mbPreviewHdr: true,  mLutType: 7    ← 启动时（大师）
17:46:02.503  onResume, mbPreviewHdr: false, mLutType: 7    ← 正在从 GR 切回大师
```

**GR → 大师 这次切换把 LivePhoto presenter 的预览 HDR 关掉了，之后再没回到 true。**
大师色彩（MasterColor）的 LMT 是挂在 HDR 预览链上的：预览一旦不是 HDR，转给视频编码器的
RGBA 就是**没过大仕 LMT 的原厂 professional 画面**（原厂比大师更艳），而封面走静态成片管线，
永远带大师 LMT —— 于是「视频鲜艳、封面正常」，且只有大师实况中招（其它模式的 LMT 不走这条路），
并且除重启相机（presenter 重新 onResume）之外不会自己恢复。

这条能同时解释：只有大师实况、只有 GR 之后、重启相机就好、换预设管理/重启也顺带好（会重建 presenter）。

### 5.3 像素比对为什么给不出结论（别再走这条路）

实况视频是 BT.2020 / HLG 10bit，封面是 SDR 基底 + gain map（`hdrgm:Version="1.0"`）。
用任何没做 HLG 反变换的管线去比，视频彩度都会是封面的 ~2 倍 —— 这在**所有**样本上都一样
（坏样本 Lab C=28.6 / 封面 12.3，正常样本 28.3 / 12.4），差异全落在噪点里。
`work/lp/hdr.py` 做了 HLG 反 OETF + OOTF + 9020→709 + Reinhard，仍受白点与 tone map 选择影响，
只能用作「同一场景 A/B」的辅助，不能当判据。

**结论：这个问题的判据只能是「相册里播放 vs 封面」的人眼，或者让视频重新带上大师 LMT。**

### 5.4 下一步（未实施）

1. 先取证：在 `ModeStateSplit` 或新增钩子里，每次拍摄时打印 LivePhoto presenter 的
   `mbPreviewHdr` / `mLutType`，确认「GR 之后 = false」是不是稳定规律（本次只有一条样本）。
2. 再修：GR→非 GR 切换后把预览 HDR 重新打开（或重建 presenter），让视频重新继承大师 LMT。
   落点候选：`LivePhotoPresenter.onResume` 的 `mbPreviewHdr` 赋值处，或 mode switch 完成回调。
3. 对照实验（最省事）：**同一次开机里，先强停相机 → 直接大师拍实况（不进 GR）**，看是否也鲜艳。
   若也鲜艳，说明跟 GR 无关，是「大师实况视频不带 LMT」的固有行为，修法完全不同。

本地留档（2026-11-14 18:00 拉取）：`work/lp/logs/logcat_full_1759.txt`（本进程 34MB）、
`logcat_all_1759.txt`（全 buffer 355MB）、`work/lp/dcim/*.jpg`（当天 24 张）、
`work/lp/dcim/*.mp4`（2 段视频）、`module_settings.xml`、`getprop.txt`、`media_camera.txt`。

---

## 6. 对照实验确认 + 真因与修复（r362，2026-11-14 19:13）

用户做的对照实验：**重启相机、不进 GR，直接在大师模式拍实况** → 相册播放颜色正常。
这条把范围收死了：不是「大师实况视频天生不带 LMT」，而是**进过一次 GR 之后**才坏。
和 §5 的推断一致，`mbPreviewHdr` 就是那个开关。

### 6.1 反汇编确认：W0 是实况视频的编码器色彩配置

`classes8.dex` 里 `gh/e2`（日志 tag `OCAM_LivePhotoPresenter-`，栈里叫 `LivePhotoPresenter.java`）
的字段 `W0` 就是 `mbPreviewHdr`。`buildPreview` 构造视频 `MediaFormat` 时依次读它：

```smali
089f: iget-boolean W0            ; color-format: W0 → 0x36(54), 否则 U0 → 0x15(21), 再否则 0x7f420888
08c3: iget-boolean W0            ; dataspace:   W0 → com.oplus.camera.preview.hdr.livephoto.video.dataspace
                                 ;              否则 com.oplus.camera.livephoto.color.dataspace.value
08f3: iget-boolean W0            ; W0 时补 profile=2 / level=16384（HEVC Main10）
```

也就是说 **W0 决定这段视频按 HDR 还是 SDR 编码**。

`W0` 的写点只有四处（`grep iput-boolean Lgh/e2;.W0`）：

| 位置 | 方法 | 取值 |
| --- | --- | --- |
| `gh.e2.<init>` | 构造器 | 常量 false |
| `gh.e2.I1(Bundle)` | onCreate | 查 `Lzg/a`（相机能力） |
| `gh.e2.R1(EventMessageSwitcherStage)` | onModeSwitchStageChanged | 查 `Lzg/a` |
| `gh.e2.f2(Z)` | onResume / onPause | 查 `Lzg/a` |

四处都不缓存「上一次的结果」，每次重新查 `Lzg/a`。进 GR 时它答 false，退出 GR 时
presenter 被 onPause+onDestroy 再 onCreate（17:45:56.841 → 17:46:02.503），新实例同样查回 false，
**于是这个 false 会一直留到相机重启**。

### 6.2 为什么表现是「视频更艳」

W0=false 时视频按 SDR 8bit / SDR dataspace 编码，但喂给编码器的 RGBA 是 HLG 预览
（`OCAM_LivePhoto.Rgba2YuvConversion`，1920x1440）。HLG 信号被当成 SDR 解释，中间调抬升，
看起来就是明显更亮更艳；封面走静态成片管线，不受这个标志影响，所以正常。

### 6.3 修复（r362）

新增 `app/src/main/java/local/jiege/hook/gr/LivePhotoHdrRestore.java`，挂在 GR port 上：

- hook `LivePhotoPresenter.modeStage`（`R1`）与 `LivePhotoPresenter.resume`（`f2`，仅 `Z=true`）；
- 见到 true → 记 `sawHdrPreview`（说明这台机器确实做 HDR 实况预览）；
- 见到 false 且 `sawHdrPreview` 且 `GrState.isGrMode()==false` → 把标志补回 true，打一行
  `live photo preview HDR restored (...)`。

条件这么窄是为了不误伤：只有「本进程见过 true」才补（不支持 HDR 预览的机型不碰），
GR 模式内不补（GR 自己有它的取值），且只补这个被查询污染的标志，不动预览本身。

符号（`symbols.json`，源在 `tools/symbols/sym_camera.py`）：

```json
"LivePhotoPresenter"              : strings ["onModeSwitchStageChanged, mbPreviewHdr: "]
"LivePhotoPresenter.create"       : in @LivePhotoPresenter, params [android.os.Bundle],  strings ["onCreate, presenter: "]
"LivePhotoPresenter.modeStage"    : in @LivePhotoPresenter, params [EventMessageModeSwitcherStage], strings ["onModeSwitchStageChanged, mbPreviewHdr: "]
"LivePhotoPresenter.resume"       : in @LivePhotoPresenter, params [boolean], strings ["onResume, presenter: "]
"LivePhotoPresenter.previewHdr"   : in @LivePhotoPresenter, type boolean,
                                    writtenBy [create, modeStage, resume]
```

字段不用名字定位：`gh.e2` 里 boolean 字段中**同时被这三个方法写**的只有 `W0`
（U0/X0 只被 `<init>` 和 `I1` 写，Y0 不被 `I1` 写）。

> 附注：`EffectItemView` 在已提交的 `symbols.json` 里带 `super` + `fieldTypes`，而
> `sym_camera3.py` 里只有 `strings`。直接跑 `sym.py` 会把那两条约束删掉，所以这次是
> 手工往 JSON 里加条目、同时把同样的条目写进 `sym_camera.py` 保持两边一致。
> 下次动 `sym.py` 前先把这个不一致补上。

---

## 7. r363 仍复现 → 真因在 LMT 树的「无画像加载」（r364）

r363 的 `mbPreviewHdr` 修复在日志里确实触发了 5 次（`live photo preview HDR restored`），
但 19:24 复现的那条实况**依然鲜艳**。逐条比对 r363 下的「坏样本 19:24」与「好样本 19:40」
（19:40 是重启相机后直接大师模式拍的），差别只有一个，且与预览 HDR 无关：

| 项 | 19:24（坏） | 19:40（好） |
| --- | --- | --- |
| 视频编码参数 | HEVC Main10 / HLG / BT.2020 | 完全相同 |
| 视频分辨率 | 2576×1428 | 2576×1428 |
| `Rgba2YuvConversion` / `genImageDataPool` | 各 2 次 | **0 次** |
| 视频/封面彩度比（同亮度区间） | **2.22** | **0.97** |
| Y 平面中位数 | **180** | 128（与封面一致） |
| `RicohGrPort: BasicTone profile=master-colour` | 2 次（`t04104_…` / `mb04104_…`） | **0 次** |
| 预览 GL 滤镜 `processImage` | `gr` 54 帧 + `professional` 143 帧 | 全程 0 帧 |

坏视频不是「GR 的 look 没退」——它比 19:24 那张 GR 照片还艳一倍；也不是「略艳」，
是**整张亮 1.4 倍、彩度 2.2 倍**，边缘还带红青色边（低分辨率上采样的痕迹）。

### 7.1 机制

实况的「动态部分」是预览流的帧（2576×1428，与 `OCAM_LivePhoto.VideoEffectPipeline` 一致），
不是成片管线。这条流**只在会话开始时 load 一次 LMT，帧本身不带画像**，于是
`applyLmtPath` 走的是 `activeProfile == kUnknown` 分支：它保留对象上一次指着的树。

- GR 拍完，对象指着 `.../ricoh_gr/lmt`；
- 切回大师后视频流的那次 load 没有画像 → **继续用 GR 树**（r361 之后被改成 host 树）；
- 封面是成片，`BasicTone profile=master-colour` 每次现算目录 → 大师 look。

于是同一次拍摄，视频和封面用了两棵不同的树。host 就是原厂 professional，本身比大师亮、
比大师艳，所以观感是「视频很鲜艳、封面正常」。其它模式不吃这套 LMT，所以只有大师实况中招；
树挂在长命对象上，所以除重启相机外不会自愈。

### 7.2 修复（r364）

`app/src/main/cpp/gr_basictone.cpp`：

1. `rememberMasterDir()` / `freshMasterDir()`：记住最近一次交给成片的大师 LMT 目录（10 分钟内有效）；
2. `lastModeSeen`：记录最近一个非 0 的 capture mode（0 是阶段切换的过渡值，不覆盖）；
3. `applyLmtPath` 的 `kUnknown` 分支改为：
   - GR 模式（或未见过 mode 且 GR 帧刚过）→ 不动，仍归 GR；
   - 大师模式（mode 33）且有新鲜目录 → 把树切到该大师目录，并 `-0xeb0 = -1` 强制重载；
   - 其它模式 → 若树上还留着 GR，退回 host。

日志新增一行（每次切换类型只打一次）：
`load without frame profile: tree switched to master-colour (…)`。

这样视频流与封面用同一棵大师树，观感才对得上；GR 自身的捕获不受影响（带动画帧时
`activeProfile == kGr`，走的是上面那条正常分支）。

## 8. r364 复核：树切换没被调用过（2026-11-14 19:57）

复现步骤同 §7（GR 拍一张 → 切大师 → 拍实况），新日志里**一次 `load without frame profile`
都没有**，包括 r361 稳定出现的那句 `tree kept: GR`。也就是说 `applyLmtPath` 的 kUnknown
分支从来没执行：这条流在本次会话里**根本没有再调用 loadLut**。

### 8.1 证据链

`table read`（pop_path_remap 的 `noteTableRead`，每进程每路径一次）把两条流分得很清楚：

| 时刻 | 读的树 | 说明 |
|---|---|---|
| 19:57:11.104 – 11.148（进 GR） | `ricoh_gr/lmt/LMTLut0..3` + `SC32`/`CWCM32` | applyLmtPath 走 kGr，正常 |
| 19:57:15.064（切大师） | `setting_Retro/SimTool_Preview_Normal_*.ini` | 过渡对象 |
| 19:57:15.070 – 15.082 | `/odm/etc/camera/basictone/lmt/SC32Lut0..8`、`CWCM32Lut0..8` | **只有 SC/CWCM，没有 LMT** |
| 之后 | 无 | 视频流不再读任何表 |

对照正常样本（重启相机 → 直接大师 → 拍实况，19:40）：

- `SimTool_Dolby.ini` → `[switch]` **全 0**（LMT/SC/CWCM/Vig/Merge 都关），BasicTone 基本直通；
- 唯一的 LMT 读取是 `.413/.416` 的 `lmt/LMTLut0`、`LMTLut1`，那是烘焙预览表时读的**基底**。

坏样本切到大师后走的是 `setting/SimTool_Master.ini`（19:57:16.058），它的 `[switch]`
是 **LMT=1 SC=1 CWCM=1 Vig=1 Merge=1**——全开。

### 8.2 结论

不是「树指错了」，是**视频流的对象缺一张 look 表**：

- 大师模式的对象由 `SimTool_Master.ini` 建起来，SC / CWCM 全开且已加载（那 18 次 table read）；
- LMT 是 `loadLut` 才加载的东西，而这条流**不调 loadLut**，于是 LMT 表一直是空的；
- 空 LMT + 全开的 SC/CWCM → 只做了一半的色彩链路 → 偏暖、过饱和（视频帧 R−B = 47，
  正常 17，封面 12；同亮度分箱彩度比 2.12，正常 0.75~0.98）；
- 封面每次现算大师目录并真的加载了 6 张表，所以正常。

好样本之所以正常，是因为它的对象走的是 `SimTool_Dolby.ini`（全关），BasicTone 不参与，
画面直接来自 GL 上的预览 look。

### 8.3 修复（r365）

Java `MasterColor`：预览表确定时把目录写到 `files/jiege/mastercolor/preview`，切换 look /
清预览 / 离开大师时删除。

native `gr_basictone.cpp`：新增 `reloadStreamTree()`，在 `processCore` 拿到**无画像帧**
（`known == false`，即预览 / 实况视频）时：

1. GR 空闲窗口内（800 ms）不动；
2. 读 pin 目录（500 ms 节流，不逐帧 fopen）；
3. 每个对象每个目录只补一次：写 `+0x008`、`+0xeb0 = -1`，用上一次 loadLut 的
   `(mode, settingPath, flag)` 重放 `originalLoadLut3`（X8U 是 2 参数版本则调 `originalLoadLut`）。

日志：`stream tree -> <dir> (mode=… setting=…)`。

这样视频流拿到的是和封面同一套 look 表，SC/CWCM 保持原厂（封面也是），两者才对得上。

## 9. r366 复核：根因锁定为 emCamMode 残留（2026-11-14 20:31）

r365 的「视频流补加载 look 表」方向也无效，但这次把根因彻底定位到 Java 层。

### 9.1 决定性证据：emCamMode 2 vs 0

`BasicToneMalloc() emCamMode` 日志对比（同一进程不同样本）：

| 阶段 | 干净大师(19:40) | GR后大师(20:31) |
|---|---|---|
| 预览/视频流 | **emCamMode: 2** (→SimTool_Dolby, LMT直通) | **emCamMode: 0** (→SimTool_Master, LMT全开) |
| 成片 ODT | emCamMode: 0 | emCamMode: 0 |

- 封面成片两种状态都正常（R−B 10~11，C 16~28）；
- 视频流 GR 后 R−B 44.8 vs 干净 16.8，艳 2.1~2.2 倍；
- 视频 73 帧从头到尾一样艳（不是中途变色），编码元数据（HEVC Main10/BT.2020/HLG/full）三个样本完全一致。

结论：**封面没坏、LUT 表没坏、编码没坏，坏的是视频流(预览流)的 emCamMode 从 2 被残留成 0**，
导致预览流额外跑了一遍大师 LMT（增饱和+加暖）。

### 9.2 反编译定位（本次新增）

- `Lut3dDataManager`（`com.oplus.camera.common.utils.Lut3dDataManager`，classes.dex）：
  - `initializeBasicToneEnv`（混淆名 `e(int combineType, int capMode, String modeName)`）按
    modeName 选目录：`"retroCamera"`(GR)→`setting_Retro`，其余→`setting`；幂等短路
    `if (O==capMode && M==combineType) return`（字段 M/O 缓存）。
  - 日志 tag `OCAM_Lut3dDataManager`，`initializeBasicToneEnv, combineType: 1, capMode: 0 capModeName professional`
    只在启动打一次，之后走幂等短路。
  - `CombineLutHelper`（`com.oplus.ocs.camera.CombineLutHelper`，classes23.dex）→ `CombineLutJNI.initV3(String,String,String,int,int,String)`
    （SDK jar `com.oplus.camera.unit.sdk.jar` 里的 `com.oplus.camera.combinelut.CombineLutJNI`），第 4/5 个 int 即 combineType/capMode，透传到 native `Param_BasicTone_Base::BasicToneMalloc(InitParam*)`（`libCombineLut.so`，emCamMode 在 InitParam+0x28）。
  - `capModeName` 来自 `TexturePreviewRequest.getCapModeName()`（预览纹理请求的模式名）。
- `GRCapMode extends ProfessionalMode`（SDK jar），GRCapMode 自己有 `getSurfaceUseCase`（返回
  `"gr_mode_case"`/`"gr_mode_hq_raw_case"`）和 `onConfigure`；我们模块的 `GrModeHooks.hookSdkModeInheritance`
  用 `SuperCall.inherit` 把它们替换成 ProfessionalMode 的实现（返回 `"professional_case"`）。

### 9.3 根因链

GR 借用 professional 的 use case(`professional_case`) → GR 预览流按大师配置(emCamMode 0) →
切回大师后预览流未重建、emCamMode 残留 0 → 大师实况视频流用 SimTool_Master(LMT全开) → 艳2倍。

### 9.4 待办

- 定位 emCamMode(2=预览流/0=成片) 在 GR→大师切换时该复位的确切方法（`TexturePreviewRequest.getCapModeName`
  或 use case 决定点），打补丁强制预览流回 2。
- 反编译产物：`work/lp5/dex/`(相机 33 个 dex)、`work/lp5/sdk/`(com.oplus.camera.unit.sdk.jar 的 classes.dex)、
  `work/lp5/dex/classes.txt`(149万行)、`work/lp5/dex/classes29.txt`、`work/lp5/dex/libCombineLut.so`。

## 10. r367 修复：GR 退出后强制预览流 capMode 复位（2026-11-14 21:40）

### 10.1 完整根因链（反编译闭环）

- `Lut3dDataManager.e(int combineType, int capMode, String modeName)`（initializeBasicToneEnv）是唯一入口，
  capMode(=native emCamMode) 决定读 SimTool_Dolby(2, LMT关) 还是 SimTool_Master(0, LMT开)。
- 幂等短路 `if (O==capMode && M==combineType) return`（字段 M/O 缓存上次 pair）。
- 两个调用点：
  1. `nn.l.y0`（预览渲染器，classes23.dex）：capMode 按 mode 名映射
     professional/xpan(且 n0.U())→0, gr→2, vibe→0, commonVideo→1, retroCamera→0, 其它→2。
  2. `FilterMenuCanvas2D.initBasicToneConvertEnv`（滤镜画布，classes25.dex）：capMode 固定 0。
- 日志 `MasterPalette: camera tone env init <combineType>,<capMode>`（已有 hook）证实：
  好样本（19:40 进程6921）预览流 `1,2`、成片 `1,0`；
  坏样本（20:31 进程31781）进 GR 前 `1,2`、进 GR 后全 `1,0`，切回大师也再没回到 2。

### 10.2 修复

新增 `GrPreviewStreamRestore`（gr 包，挂进 `GrModeHooks.install`）：
- hook `ModeSwitch.switch(from,to,caller)`，检测 `from=="gr" && to!="gr"`；
- 延迟 1500ms（等大师 session 起来）后台线程执行：
  `Lut3dDataManager.l(false)`（清 M/O 缓存）→ `e(1, 2, "")`（combineType=1, capMode=2, 空 mode 走 setting 目录）。
- 日志 `preview stream restore -> e(1,2,"") = ...`。

### 10.3 待验证

- 复现 GR→大师→实况→相册播放，确认视频/封面彩度比回到 ≈1.0；
- 若仍不对，检查 `e(1,2,"")` 是否命中 setting 目录 + SimTool_Dolby、以及延迟 1500ms 是否足够。

---

## 11. r369 回滚：emCamMode 方向整个错了（2026-11-14 22:05）

r368 装上去之后，用户报告的现象**反转**了：不再是「播放鲜艳」，而是「播放变暗、预览也暗、
画面寡淡偏黄」。而且**第二次拍就正常**。这是决定性的方向信号。

### 11.1 关键证据：大师预览流 emCamMode 本来就是 0

把 r368 本次日志（进程 22562，21:55）和 r368 之前的干净大师日志（进程 22496，20:17）逐帧对比：

| 状态 | `Lut3d.load` 调用 | emCamMode |
|---|---|---|
| 干净大师启动（20:17 进程22496） | `camera tone env init 1,0 professional` | 0 |
| GR 后大师（20:31 进程31781） | `camera tone env init 1,0 professional` | 0 |
| 切到 common（视频）模式 | `Lut3d.load id=1 arg=2 mode=common` | 2 |

**干净大师和 GR 后大师的预览流 emCamMode 完全一样，都是 0。** emCamMode=2 只在切到
common（视频）模式那一刻出现。§9/§10 把「视频模式的 emCamMode=2」误当成了「大师预览流的正确值」，
据此做的 r367/r368 修复方向整个反了。

大师模式的预览流就该 emCamMode=0（SimTool_Master，大师 LMT 开），封面成片也是 0，两者一致。
r368 的 `e(1,2,"")` 把「GR 之后」的预览流错误地推向 Dolby 直通（emCamMode=2，无大师 look），
于是 GR→大师后的第一次实况视频帧丢了大师 LMT → 变暗寡淡；第二次拍大师模式自己重新 init 回
emCamMode=0 才恢复。这正好解释「第一次暗、第二次正常」。

### 11.2 对照实验确认

用户对照：**重启相机 → 直接进大师拍实况（不进 GR）→ 播放正常**。证明 r368 的污染只发生在
「GR 之后」的预览流，直接进大师不受影响。回滚方向确认无误。

### 11.3 回滚

- 删除 `GrPreviewStreamRestore.java`；
- `GrModeHooks.install` 移除 `GrPreviewStreamRestore.install` 挂载；
- 版本 r368 → r369。

### 11.4 回到真正的根因

「鲜艳」的真正根因不是 emCamMode，而是**更早的 GR LMT 树残留**（§4 的原始判断）：
GR 拍完，预览/实况视频流对象还指着 `.../ricoh_gr/lmt`，切回大师后这条流不再调 loadLut，
LMT 树一直停在 GR。r364（applyLmtPath 树切换，§7）和 r365（reloadStreamTree 主动重载，§8）
正是对准这个方向的工作，但 §8 复核发现 r364 的 kUnknown 分支没被调用。

下一步应回到 §7/§8 的树残留方向：在「鲜艳」状态下验证 r365 的 `reloadStreamTree` 是否真的把
视频流树切回大师 look，而不是继续在 emCamMode 上打转。

---

## 12. r370 真根因：e() 幂等短路只看 capMode/combineType，不看 modeName（2026-11-14 22:20）

### 12.1 决定性反编译证据

`Lut3dDataManager.l(Z)`（清缓存方法）把两个幂等字段写 -1：

```smali
22b41c: iput v1, .O   # O(capMode) = -1
22b420: iput v1, .M   # M(combineType) = -1
22b426: iput v1, .P   # P(modeName) = null
```

`Lut3dDataManager.e(IILjava/lang/String;)` 的短路只看 M/O，不看 modeName：

```smali
0051: iget v1, .O            # O = 上次 capMode
0053: if-ne v1, v6, 005b     # O != 新 capMode → 执行
0055: iget v1, .M            # M = 上次 combineType
0057: if-ne v1, v5, 005b     # M != 新 combineType → 执行
0059: monitor-exit; return   # 都相同 → 短路 return
```

**GR 和大师的 combineType 都是 1、capMode 都是 0**（日志 `Lut3d.load id=1 arg=0 mode=gr`
和 `mode=professional` 完全同参）。于是 GR→大师切换时，相机自己的 `e(1,0,"professional")`
被幂等短路跳过，**大师 LUT 树根本没重建，继续用 GR 树** → 实况视频鲜艳。

### 12.2 之前 r367/r368 为什么「碰巧有效但有副作用」

r368 用 `e(1,2,"")`（capMode=2，不同于 GR 的 0），打破了短路、强制重建了环境，所以「鲜艳」
消失；但 capMode=2 是 Dolby 直通（LMT 关），把大师 look 也一起丢了 → 第一次拍变暗。第二次拍
大师自己重新 init 回 capMode=0 才恢复。这解释了「第一次暗、第二次正常」。

### 12.3 用户观察的「卸载旧版后开始」的解释

不是卸载残留，而是 `p04000_0a4f1074ddabd946` 预览目录（16:46 旧版 bake 的 LMTLut）被新版复用：
`writeKey` 目录名 = `p%05d_%016x`(cct + hash)，cct=4000 + look=x8u + 同参数 → 同名目录，
`key.bin` 已存在就短路复用，LMTLut 不重 bake。但 md5 验证 `p04000` 与 `mb04154`(22:11 封面) 的
LMTLut0/1 完全相同，所以 LMTLut 内容不是「鲜艳」的变量——真凶仍是上面的幂等短路。

### 12.4 修复（r370）

`GrPreviewStreamRestore`（重建）：
- hook `ModeSwitch.switch`，`from=="gr" && to!="gr"`；
- 延迟 1500ms 后台线程：`l(false)`（M/O → -1，破短路）→ `e(1, 0, "professional")`。
- **capMode 保持 0**（不是 r368 的 2），modeName 用 "professional"，让大师 look 正确加载。

### 12.5 待验证

复现 GR→大师→实况→相册播放，确认视频/封面一致（既不变艳也不变暗）。

---

## 13. r370 失败 + r371 回退 e(1,2) 的实测（2026-11-14 22:26）

### 13.1 r370（e(1,0,"professional")）结果：仍鲜艳

capMode 保持 0 的修复**没有消除鲜艳**。这推翻了 §12「幂等短路是根因」的判断——因为实测日志
证明大师切换时 `e(1,0,"professional")` **并没有被短路**。

### 13.2 决定性反证：CombineLutHelper.init 确实执行了

日志铁证（第 3 次切换 22:26:35.965）：

```
Lut3d.load id=1 arg=0 mode=professional          ← 大师切换
MasterColor: preview LMT .../p04000_...          ← CombineLutHelper.init 被 hook 且替换目录
MasterColor: preview table .../p04000_...        ← 预览表 pin
```

`MasterColor: preview LMT` 是 `CombineLutHelper.init` 的 before hook 打的，能打出说明 init 真的
被调用了、没有短路。所以「GR/大师同参 (1,0) 导致短路、大师 LUT 不重建」的判断是**错的**。

### 13.3 双渲染线程证据（真根因方向）

`Lut3d.update` 有两个固定线程 tid：
- `tid=1826`：只在 GR 模式（grMode=true）打 update
- `tid=1836`：只在非 GR（大师）打 update

`Lut3d.load` 的 tid 也随切换在 1700/1701/1709 之间变化。**GR 有独立的预览流线程**，它通过同一
个 manager 单例（inst=82b45df 恒不变）写共享 LUT 表。切回大师后 GR 线程停更，但它在 GR 期间
写进 manager 的 LUT 状态（树/参数）没有被大师流覆盖干净，导致实况视频（预览流 RGBA→YUV）
残留 GR 色彩。

### 13.4 r371 回退 e(1,2,"") 的实测

改回 `e(1,2,"")`（capMode=2 Dolby 直通）：
- **播放（成片）正常**：成片管线每次都重新 e(1,0) 初始化，不受预览流 capMode=2 污染。
- **预览有时变暗、有时正常**：`e(1,2,"")` 异步 1500ms 触发，与大师预览帧的 `e(1,0)` 初始化
  存在**竞态**——若大师帧在其后触发 e(1,0) 则拉回 capMode=0（正常），否则停在 capMode=2（暗）。

### 13.5 结论与下一步方向

`e(1,2,"")` 能破鲜艳的本质：它把 manager 的 LUT 环境整个重置成 Dolby 直通，**顺带清掉了 GR 线程
写进共享 LUT 表的残留**。副作用「预览变暗」来自 capMode=2 丢大师 look + 异步竞态。

---

## 14. 真根因锁定：视频流 LUT 树残留 GR，切大师后无机制指回（2026-11-15 07:00）

### 14.1 emCamMode 全程 = 0，只有 e(1,2,"") 才 = 2

native `BasicToneMalloc` 日志全程跟踪：GR 和大师都是 `emCamMode: 0` + `SimTool_Master.ini`。
**唯一出现 `emCamMode: 2` + `SimTool_Dolby.ini` 的是我的 e(1,2,"")**。彻底推翻「emCamMode 0/2」理论。

### 14.2 决定性证据：进 GR 读 GR 树，切大师 15 秒内预览流零活动

- 进 GR（22:24:31）：`table read ricoh_gr/lmt/LMTLut0`（真实读入 GR 树）
- 切大师（22:24:38）：`MasterColor: preview LMT p04000`（args[0] 已替换），`BasicToneMalloc emCamMode 0`，
  但 **native `readLMTLut() ReadLut:0`（没读表）**，且**没有 `table read p04000/LMTLut0`**
- 切大师到拍摄（22:24:38→53）**15 秒内预览流零 loadLut/processCore 活动**
- 22:24:53 拍摄封面时才 `stream tree -> p04000` + `tree switched to master-colour t04127`（已晚）

### 14.3 真根因

实况视频流（预览流）的 BasicTone 处理**不经过 processCore/loadLut hook**。进 GR 时 `GrEffectTransport`
用 4 参数 `CombineLutHelper.init`（gr 分支）把它的 LUT 树直接指到 `ricoh_gr/lmt`；切大师后没有任何
机制把它指回大师树。封面成片每次现算（正常），视频流停在 GR 树（鲜艳）。

r364/r365 的 `reloadStreamTree`/`applyLmtPath` 方向对，但依赖 processCore/loadLut 时机，覆盖不到预览流。

### 14.4 LMTLut 内容不是变量

md5 验证 `p04000`（预览表 16:46）和 `t04127`（封面表 22:24）的 LMTLut0-5 **全部 6 个文件相同**。
所以「look 表内容不匹配」不是鲜艳原因，树指向（GR vs 大师）才是。

### 14.5 修复方向

在 GR 退出时（ModeSwitch.switch）强制视频流重读大师树，而非等 processCore、也非 e(1,2,"") 重置成 Dolby。
候选：
1. GR 退出时清掉 `MasterColor` 的 `loadedDir` 幂等缓存 + 调 `reload()` 强制重放大师 load；
2. 或让 `GrEffectTransport` 在 GR 退出时，用大师参数主动重放一次 `CombineLutHelper.init`（6参数版），
   使视频流对象读回大师树。

---

## 15. r372 两步失败 + 最终根因（ReadLut:0 = LMT 空表）+ r373 回退（2026-11-15 07:30）

用户测试 r372（`e(1,2,"")` + `e(1,0,"professional")` 两步）后反馈「播放时还是鲜艳」。
这一步把根因彻底钉死，不再是任何"树指向"或"幂等短路"的猜测。

### 15.1 决定性证据：视频流的 `readLMTLut` 返回 `ReadLut:0`（LMT 表为空）

逐帧跟踪 native `libCombineLut.so` 的 `Param_BasicTone_CPU` 日志，实况视频流（`VideoEffectPipeline`,
camera id 12，固定线程 **tid=1836**）在切回大师后（22:24:38.763）确实重新走了 `readLMTLut`：

```
readLMTLut() Lux_1
readLMTLut() CCT0_1_50 / CCT1_1_50      ← 大师读表要 CCT 插值表
readLMTLut() ReadLut:0 (×2)             ← 读表失败，返回 0
readLMTLut() NextLutname_LMTLut0_0_0_0  ← 它要找 LMTLut0_0_0_0 这个文件
```

而 `pop_path_remap` 的 `table read` 日志里，切大师后**没有任何 ricoh_gr 或大师目录的读表记录**——
视频流根本没通过文件系统读到 LMT 表。

### 15.2 真正的根因（无歧义）

大师的 `readLMTLut` 读的是 **CCT 插值表 `LMTLut0_0_0_0`**（`%s/LMT%dLut%d%d%d` 模板，带 lux/CCT 后缀），
而视频流对象的 LMT 目录指针（`+0x008` 那个 256 字节 buffer）在进 GR 时被 4 参数 `CombineLutHelper.init`
指到 `ricoh_gr/lmt`，**切大师后没有任何机制把它指回大师目录**。于是：

- 视频流用「大师的读表逻辑」去找 `ricoh_gr/lmt/LMTLut0_0_0_0`；
- GR 目录里只有 `LMTLut0`（无 `_0_0_0` 后缀），`LMTLut0_0_0_0` 不存在 → fopen 失败 → `ReadLut:0`；
- LMT 表空 + SC/CWCM 全开（`BasicToneProcessNext() LMT SC CWCM`）→ 只跑了一半色彩链路 → 偏暖过饱和（鲜艳）。

封面成片每次重新 bake + 读大师目录（有 `LMTLut0`）→ 正常。这就是「视频鲜艳、封面正常」且只有大师实况中招、
只有 GR 之后中招、重启相机自愈（presenter 重建）的完整解释。

> 修正 §7/§8/§14 的「树残留 GR」措辞：不是"视频用了 GR 的表"，而是"视频的 LMT 表**空**了
> （ReadLut:0），只跑了 SC/CWCM"。GR 表本身在切大师时并没有被视频流继续引用——是目录指针没复位，
> 导致大师读表找不到文件。

### 15.3 为什么各种方案都失败

| 方案 | 环境状态 | 视频（LMT+SC/CWCM） | 预览 | 结果 |
|---|---|---|---|---|
| GR 后残留 | Master 半开（SC/CWCM 开、LMT 空） | 鲜艳 ✗ | 正常 ✓ | — |
| r368/r371 `e(1,2,"")` | Dolby 全关直通 | 正常 ✓ | 有时暗 ✗ | 唯一"播放正常" |
| r370/r372 `e(1,0,"professional")` | Master 半开（延迟读表，LMT 仍空） | 鲜艳 ✗ | 正常 ✓ | 又鲜艳 |

`e(1,0,"professional")` 走 5 参数 `initV2`（capMode=0），native 端 `readLMTLut` **延迟**读表
（要等 processCore/loadLut，而视频流不经过），所以它把环境从 Dolby 拉回"Master 半开"——SC/CWCM
重新打开、LMT 依旧空 → 鲜艳回来了。这就是 r372 两步失败的机制：Step2 把 Step1 好不容易清掉的
"半开状态"又造了回来。

### 15.4 真正的修复方向（下一步，未实施）

要让视频流的 `readLMTLut` 真正读到大师表，两个落点：

1. **hook `readLMTLut`**（`_ZN19Param_BasicTone_CPU10readLMTLutEii`，视频流每次处理都调它）：
   在执行前，若「大师模式 + 对象的 LMT 目录仍指向 ricoh_gr/lmt + 有 pin 的预览表目录」，把目录
   字段指回大师预览表目录（`/files/jiege/mastercolor/preview` 或 `lastMasterDir`）。需要反汇编确认
   `Param_BasicTone_CPU` 的目录字段偏移（`BasicTone_OGL` 是 `+0x008`，CPU 类可能不同）。
2. **或让 `pop_path_remap` 拦截 `ricoh_gr/lmt/*` 路径**：当「非 GR 模式 + 读 ricoh_gr 的 LMTLut」时，
   把 `LMTLut%d_%d_%d_%d`（CCT 插值名）重定向到大师预览表目录的 `LMTLut0`（配合 `masterColorRemapPath`
   已有的 LMTLut 回退逻辑）。这个改动最小、最贴近现有 remap 架构，但要注意 `readLMTLut` 读表用的
   open 是否真的走 `pop_path_remap` 拦截的 openat/open（§15.1 显示 table read 没记录到 ricoh_gr 读，
   可能视频流读表绕过了 open 拦截，需先验证）。

### 15.5 r373 决定

回退 r372 的两步为 **r371 的单步 `e(1,2,"")`**，恢复"播放正常（视频/封面一致）"，接受"预览有时暗"
为 baseline（用户此前已确认可接受）。真正的"视频流读到大师表"修复留给 §15.4，需要反汇编确认
`Param_BasicTone_CPU` 目录偏移后实施。


---

## 16. 最终定论：视频流的 CombineLut 实例在 GR 后未被重新 init（r373 实测推翻 §15）

### 16.1 §15 的根因是错的

r373 实测（2026-11-15 07:26）用两组日志做了决定性对比：**正常大师启动** vs **GR 后切大师**，
主预览流的 CombineLut 日志**逐行相同**：

```
MasterColor: preview LMT p04000_...      ← 都替换 args[0]
read3DlutSet: SimTool_Master.ini LMTLutNum:4
readLMTLut() Lux_1 CCT0_1_50 ReadLut:0   ← 都读 0 个表
NextLutname_LMTLut0_0_0_0
```

所以 `ReadLut:0` 是**大师模式的正常态**，不是"读表失败"。§15 把 `ReadLut:0` 当成失败、
并据此推断"大师目录缺 CCT 插值表"是**误判**。`ReadLut:0_1/2_3` + `Lux_2/CCT_600` 是另一条流
（GR 或其它 sensor）的态，跟大师无关。

### 16.2 两条管线（真正关键）

| 管线 | 库 | 类 | 谁在用 |
|---|---|---|---|
| 视频/预览流 | libCombineLut.so | Param_BasicTone_CPU | 实况视频（VideoEffectPipeline, cameraId=12, lutType=7） |
| 封面/照片 | libBasicTonePhoto.so | BasicTone_OGL | 封面成片 |

`gr_basictone.cpp` 的所有 hook（`processCore`/`loadLut`/`reloadStreamTree`/`applyLmtPath`）全在
**BasicTone_OGL（封面管线）**上。而 `GrPreviewStreamRestore` 用的 `Lut3dDataManager.e()` 作用在
**主预览流的 CombineLut 单例**。二者都**碰不到视频流自己的 CombineLut 实例**——这就是 r368~r373
全部无效的根因。

### 16.3 铁证：第一次实况拍摄期间视频流零初始化

时间线（r373 复现，07:26）：

```
07:26:52.771  BasicTone profile=GR path=ricoh_gr/lmt   ← GR 封面
07:26:56.689  leaving GR -> professional                ← 切大师
07:26:56.706  CombineLut_Initialize (tid=23945 主预览)  ← 主预览重新 init 成大师
07:26:58.212  tone env rebuild -> e(1,2,"") = true      ← r373 补丁（作用主预览，无用）
07:26:59.685  VideoEffectPipeline: init cameraId=12     ← 第一次实况
07:27:00.793  第一次实况完成
              （期间：零 CombineLut_Initialize / 零 loadLut / 零 Lut3d.load）
07:27:09.952  stream tree -> p04000 (reloadStreamTree)  ← 第二次拍才触发
```

第一次实况（07:26:59）拍摄期间，**没有任何 CombineLut_Initialize / loadLut / Lut3d.load**——
视频流的 CombineLut 实例**根本没有被重新初始化**，一直停在进 GR 时 `GrEffectTransport` 把它
指到的 `ricoh_gr/lmt` 树 → 视频用 GR 树渲染 → 鲜艳。

第二次拍（07:27:09）封面管线 BasicTone_OGL 的 loadLut 触发了 `reloadStreamTree`
（`stream tree -> p04000`），顺带重置了共享 native 环境 → 第二次正常。这就是"时好时坏"。

### 16.4 编码/滤镜/模式都正常（排除项）

- 所有实况编码一致：`color-standard=6 (BT.2020), color-transfer=7 (PQ/HDR), color-range=2,
  profile=2 (HEVC Main10)`——第一次/第二次/正常启动全同，**不是 HDR/SDR 编码问题**。
- `mFilterType='default'`、`mModeName=professional`——滤镜/模式无残留。
- `mbPreviewHdr: true`——`LivePhotoHdrRestore` 的 HDR 恢复已生效，也不是它的锅。

### 16.5 正确修复方向

视频流（VideoEffectPipeline, cameraId=12）的 CombineLut 实例在 GR 后未被重新 init，需要**主动
触发它重新加载大师树**。入口候选：`VideoEffectPipeline` 的 LUT 加载方法
（`preloadRenderLutIfNeed`/`setRenderLutType`，日志串 "preloadRenderLutIfNeed, loadLutAsync lutType"），
在 GR 退出（ModeSwitch.switch）时对视频流重放一次大师 LUT 加载。落点要精确到视频流的对象，
而不是主预览流的 Lut3dDataManager 单例。



---

## 17. r374 最终定论：capMode 分槽 + 延迟 e(1,2,"") 把视频槽拉成「读不到表」（2026-11-15 08:00）

### 17.1 推翻 §16 的两个关键证据

实施 §16.5 方案前的反汇编与日志复核发现两处硬伤：

1. **`mn/f0` 不是 VideoEffectPipeline**：其 source_file 是 `PreviewHDRControl.java`，`n(I)V`
   （preloadRenderLutIfNeed）只在启动首帧后由 `mn.h.i0()` 调一次（`n(0)`+`n(4)`，且加载的是
   `T` 表里**固定**的 colorspace .cube，如 `p3_to_bt2020_3dlut.cube`——与模式/look 无关）。
   真正的 VideoEffectPipeline 是 `eh/c0`（source `VideoEffectPipeline.java`，VEP init 日志出处），
   其色彩 LUT 由 `gh/p3`（LivePhotoPresenter）经 `Lut3dDataManager.j(type, cb)` 的 float[] 回调获取
   （mLutType=7 → `j(1)` = colorspace 转换表）——同样与 look 无关。**视频的 look 不在这些
   float[] render LUT 上。**
2. **GOOD 与 BAD 的视频流 native 处理不是「一个坏一个好」**：把**拍摄瞬间**的处理块逐行对比——
   - GOOD（19:40:52.778，tid 8838）：`readLMTLut Lux_2 / NeedInter_0.811 / CCT0_2_600 /
     ReadLut:0_1 / ReadLut:2_3 / Coeff_0.89_0.11 / NextLutname_LMTLut0_1_2_3` —— **读到表、
     CCT 插值模式**。
   - BAD（22:24:38.763 起，tid 1836）：`Lux_1 / NoNeedInter_1.000 / CCT0_1_50 / ReadLut:0 ×2 /
     NextLutname_LMTLut0_0_0_0` —— **0 个表**。
   §16.1 只对比了「主预览流」的日志（那是 Lux_1 正常态），没对比**拍摄瞬间视频流**的处理块；
   「视频流实例未被 init」字面为真，但不是颜色差异的来源。视频 = cameraId12 预览流帧 +
   native LMT 处理：有表 → 与封面一致；0 表（LMT/SC/CWCM 全跳，`No need SC CWCM!!!`）→
   裸流 → 偏暖过饱和。

### 17.2 真正的机制（与全部证据自洽）

- 全 app 只有 `Lut3dDataManager.e(combineType, capMode, capModeName)` 调用 `CombineLutHelper.init`
  （CombineLutHelper 全 app 只有该单例一个实例）。e() 按 capModeName 分支：
  `"gr"/"vibe"` → **4 参**（立即读）；palette 白名单（common/portrait/night）→ **6 参 initV3**
  （第 6 参 `/odm/etc/camera/basictone/palette`，立即读）；其余 → **5 参 initV2**（延迟读）。
- **native 侧按 capMode 分槽**：预览帧处理走 capMode=0 槽（e(1,0,"professional")，Lux_1/ReadLut:0
  是其正常态，look 由 GL render LUT 纹理补上），视频帧处理走 capMode=2 槽。这解释了
  §16.1「预览与视频两种 readLMTLut 态并存」。
- **视频槽只由延迟任务重建**：模式切换（尤其 GR 进出）后 ~1.4s 相机跑
  `Lut3d.load id=1 arg=2 mode=<当前 capModeName>` → e(1,2,X)。
  - photo 模式 X=`"common"` → palette initV3 → 视频槽有表（**GOOD 的来源**；photo→master 直切
    后没有新任务，视频槽保持好状态——19:40 会话实测）。
  - master 模式 X=`""` → initV2 延迟读 → 视频槽 ReadLut:0（**BAD 的来源**；只在 GR 进出后才
    触发该延迟任务）。
- r368~r373 无效的必然性：我们自己调 e(1,2,"") 与相机的延迟任务殊途同归（initV2 延迟读，坏状态）；
  e(1,0,"professional")（r370/r372）修的是预览槽。「r371 播放正常」不可复现，因为 1.4s 相机任务
  与 1.5s 我们的任务竞争 M/O 幂等，谁先跑谁定状态，两者都是坏状态。
- r371 的 `e(1,2,"")` 理论与实测全部对上：22:24:39.791 相机先跑（我们 40.7x 被短路）→ 视频槽
  坏 → r371/r372 两次实况均鲜艳。

### 17.3 修复（r374，`GrPreviewStreamRestore.java`）

把视频槽**钉在 GOOD 状态**（palette initV3）：

1. **hook `e(I,I,String)`**：本进程离开过 GR 后（`leftGr` 置位），非 GR 模式下的 `e(1,2,"")`
   （含 null）一律改写 args[2] 为 `"common"` → 相机的延迟任务自己走 palette 分支。
   GR 模式内不改写（GR 实况必须保持 GR 槽）。
2. **restore() 改为 `l(false)` + `e(1,2,"common")`**：与相机延迟任务收敛到同一 (1,2) 组合，
   先到者赢、后到者被 M/O 短路，无论时序如何视频槽都落在 palette 好状态。
   副作用「预览有时暗」预期消失：palette initV3 是相机在 photo 模式的自然行为，且不再产生
   延迟读的空表状态。

日志判据（验证 r374）：拍 GR → 切大师 → 拍实况，logcat 里应有
`RicohGrPort: video slot rebuild: e(1,2,"") -> e(1,2,"common")`（相机延迟任务被改写时）或
`tone env rebuild -> e(1,2,"common") = true`（我们的 1.5s 任务先跑时）；拍摄期间视频流
`readLMTLut` 应呈 `Lux_2 / NeedInter / ReadLut:0_1_2_3`（有表）而非 `ReadLut:0`。

### 17.4 未尽事项

- r373 会话里第二次实况（07:27:09）为何正常仍未完全解释（当时封面 bake 的 loadLut/树切换
  在 09.88-09.95 发生，疑似其触发了某次 e(1,2,common) 或等价修复，未捕获日志）。r374 修复后
  该现象应不再依赖运气。
- `eh/c0`（VEP）/`gh/p3`（LivePhotoPresenter）链路里 float[] render LUT（j(1) colorspace 表）
  与 look 无关，已排除出本 bug；不再作为落点。

## 18. r374 实测：第一张好、第二张鲜艳 → 真凶是「拍摄完成后的预览环境刷新」（r375，2026-11-15 08:55）

### 18.1 实测时间线（08:48 会话，r374 日志 `work/r374_test1.txt`）

| 时刻 | 事件 | 视频流状态 |
|---|---|---|
| 08:48:07.357 | 进 GR：`Lut3d.load id=1 arg=0 mode=gr`（4 参 GR init，立即读表） | 08:08 起块变 `LMTLut0_1_2_3`（好） |
| 08:48:11.411 | 离开 GR → 大师：`Lut3d.load id=1 arg=0 mode=professional` | 11:42-12:19 视频块全 `_0_0_0`（坏） |
| 08:48:12.919 | 相机延迟任务 `Lut3d.load id=1 arg=2 mode=common`（本次原生即 common，未触发我们的改写） | — |
| 08:48:12.965 | **我们的 restore**：`tone env rebuild -> e(1,2,"common") = true` → palette initV3 有表 | （palette 环境下处理帧不打 readLMTLut 块） |
| 08:48:18.370 | **第 1 张实况**封面 bake（`BasicTone.cpp 1165 LMTLut0_1_2_3`） | 预录帧 12.9-23.0 间被 palette 有表处理 → **视频好** ✓ |
| 08:48:23.045 | **拍摄完成流程**：`CombineLut_Release` + `BasicToneFree` → `Lut3d.load id=1 arg=0 mode=professional` → initV2 延迟读重建 | **全局环境丢表** |
| 08:48:23.406 起 | 下一张的预录帧在坏环境处理 | `_0_0_0`（`iPaletteFlag=0, CCT0_1_50, ReadLut:0`） |
| 08:48:31.399 | **第 2 张实况** | **视频鲜艳** ✓（坏帧来自 23.4-31.4 窗口） |
| 08:48:39.014 | 场景变化（CCT 50→600）后 readLMTLut 偶然读回表 `ReadLut:0_1_2_3` | （同环境、iPaletteFlag 同为 0——差异只在 CCT/Lux 决策值） |
| 08:49:30.225 | **第 3 张实况** | 视频状态承接 39.0 的好块，预期正常 |

用户观感与日志完全对齐：只报第二张鲜艳。

### 18.2 模型修正：CombineLut 是全局单例环境，不是 capMode 分槽

- `CombineLut_Release()` 一次性释放整个单例（23:045 实证），随后 initV2 重建——
  「预览槽/视频槽各自独立」的表述（§17.2）不成立；实际是**谁最后 e() 谁定义全局环境**，
  预览帧与视频帧处理都被动使用它。
- 进 GR 时 `e(1,0,"gr")` 立即读 GR 表 → 视频流也有表（GR 内 `_1_2_3` 印证）。
- **每次实况拍摄完成后 ~5s，相机跑一次 `Lut3d.load id=1 arg=0 mode=professional`**
  （拍摄后预览刷新）→ 释放并以无表 initV2 重建 → 下一张实况的预录帧全部坏。
  r374 只在模式切换时修一次，因此只救了第一张。
- professional initV2 环境并非绝对无表：39.014 的好块与 23.645 的坏块同环境
  （`iPaletteFlag=0`、`reserved[0..6] empty`），差异在 CCT/Lux 决策值（`CCT0_1_50` 读 0 表
  vs `CCT0_2_600` 读全表）——即该环境下**部分场景读得到表、部分读不到**（r373「第二张
  自然好」即场景变化的巧合，不再是未解之谜）；palette initV3 环境则全场景有表。把视频
  环境钉死在 palette 是唯一稳态。

### 18.3 修复（r375）

`GrPreviewStreamRestore.hookVideoSlotRebuild` 新增 **after 补刀**：当本进程离开过 GR、当前
非 GR、且 `e(1,0,"professional"/"")`（表 initV2 延迟读、无表）刚执行完时，在同一线程立刻
`e(1,2,"common")` 重新拉起 palette 环境。参数：

- `(1,2,"common")` 重入本 hook 时不匹配任何分支，无递归风险；
- 与 M/O 幂等配合：相机刷新把 M/O 切到 (1,0) 后，我们的补刀因 (1,2)≠(1,0) 必然真执行
  palette initV3（立即读表），坏窗口不再存在；
- 每次 `e(1,0,professional)` 后多一次 Release+palette init（几十 ms，模式切换/拍摄后才
  发生，频率低）。
- 预览影响评估：photo 模式全程 palette 环境（GOOD 会话 19:40 实证预览正常），大师模式预览
  look 由 GL render LUT 补（r338），基础色调换 palette 与原厂 photo 一致，风险低。

原有两个机制保留：空名 `e(1,2,"")` 改写（防延迟任务变体）+ 模式切换 1.5s restore（首拍兜底）。

### 18.4 验证判据（更新）

拍 GR → 切大师 → **连拍 ≥2 张实况**，每张的视频都应与封面一致。logcat：

- 每张拍完 ~5s 应出现 `RicohGrPort: preview env refresh e(1,0,"professional") -> re-arm video e(1,2,"common") = true`；
- 拍摄间隔中视频流不应再出现成串 `NextLutname_LMTLut0_0_0_0` 块；
- 拍摄期间视频流 `readLMTLut` 呈 `ReadLut:0_1_2_3`（或 palette 环境下无块）。

## 19. r375 实测翻车：预览灰、视频灰 → lutTask 被即时补刀掐死（r376，2026-11-15 09:40）

### 19.1 现象与定位

r375（即时补刀）实测：GR→大师**预览立刻变灰**；拍实况封面正常，**视频与预览一样灰**。
即 palette env 没变，输出却从 r374 的「视频好、预览正常」变成「全灰」——差异不在环境本身，
在**时序**。对比两轮会话切换后的 GL 渲染线程日志：

- r374（好）：`11.428 e(1,0,professional)` → **1.5s 后** `12.918 palette init` →
  `13.929 OCAM_GLRenderingThread: lutTaskComplete` ✓
- r375（灰）：`00.577 e(1,0,professional)` → **45ms 后** `00.622 palette init`（补刀）→
  **lutTaskComplete 从未出现**；18.8 GL 渲染线程退出时 `mbNeedWaitLutTaskRun: false` ✗

### 19.2 机理

相机每次预览环境刷新（切换/拍后）都会让 GL 渲染线程跑一次 **lutTask**（重建预览 render LUT）。
它需要约 1 秒的窗口；在这之前把 env 抢走（palette init），lutTask 被中止且不再重试 →
预览 render LUT 未配置 → **预览灰**；实况视频流的帧处理同依赖这条渲染链 → **视频同样灰**；
封面走独立静图管线（HdrTransform/hybridraw）→ 正常。r374 的 restore 恰好延迟 1.5s，
lutTask 得以完成——这就是「r374 第一张好且预览正常」而 r375 全灰的全部原因。

### 19.3 修复（r376）

- 补刀**延迟化**：after hook 只起线程，`REARM_DELAY_MS = 1500` 后再 `e(1,2,"common")`
  （复刻 r374 已验证时序，让 lutTask 先完成）；延迟窗口内切回 GR 则放弃。
- **删除旧的 1.5s restore**（`scheduleRestore`/`restore`/`l(false)`）：离开 GR 必然触发相机的
  `e(1,0,professional)`（r374/r375 两轮实证），延迟补刀天然覆盖模式切换场景；旧 restore 的
  `l(false)` 清 memo 后强制二次 init，与补刀并发时序不可控，是隐患。
- 空名矫正（before hook `e(1,2,"")→"common"`）保留。

### 19.4 验证判据（更新）

GR→大师→连拍 ≥2 张实况：预览全程不应变灰；每轮刷新 ~1.5s 后出现
`RicohGrPort: delayed re-arm after e(1,0,"professional") -> e(1,2,"common") = true`；
且切换后 ~3s 内应有 `OCAM_GLRenderingThread: lutTaskComplete`；视频应与封面一致。

### 19.5 r376 实测确认（2026-11-15 09:47）

用户实测通过：GR → 大师，预览正常不变灰，连拍实况视频与封面一致。r368–r376 九轮迭代，
本 bug 闭环。最终方案：空名矫正 + 1.5s 延迟补刀（无 l(false)、无即时干预）。

## 20. r376 副作用：XPAN 拍实况闪退 → hook 作用域收窄到大师模式（r377，2026-11-15 09:50）

### 20.1 现象与定位

r376 实测后用户切 XPAN 拍实况 → 闪退。崩溃栈（`work/xpan_crash.txt`，09:47:58.840）：

```
FATAL EXCEPTION: VideoEffectProcessThread
NullPointerException: Attempt to get length of null array
  at FloatBuffer.wrap ← float[] 表为 null
  at Raw3dTexture.k → GLRenderer.a0.b → WatermarkRenderer.p → WatermarkProcessor.b
  at VideoEffectPipeline.r / onDrawFrame
```

实况视频渲染链（VEP → 水印渲染 → Raw3dTexture）拿到的 float[] 表为 null。

### 20.2 根因

日志序列：09:47:37.9 用户在 **retroCamera**（哈苏宽幅相关模式）时，r376 的延迟补刀醒来——
它的条件只有「离开过 GR && 非 GR」，retroCamera/XPAN 全中招 → `e(1,2,"common")` palette init
在错误模式下执行；09:47:55.4 用户 GR→XPAN（内部名 `xpan`，`e(1,0,"xpan")` initV2 延迟读）；
09:47:58.5 XPAN 开实况（VEP init **lutType: 1**，区别于大师的 7）→ 拿表 → null → NPE。
被污染的 palette 环境让 XPAN 的视频渲染表链拿到 null（XPAN 不在 palette 支持范围）。

### 20.3 修复（r377）

`GrPreviewStreamRestore` 新增 `currentMode`（由 ModeSwitch hook 的 `to` 参数持续维护，
大师内部名 `professional`），三个触发点全部收紧：

1. before 空名矫正：要求 `leftGr && inMasterMode() && !isGrMode`；
2. after 补刀排队：同上；
3. 延迟线程醒来执行前：**重新检查** `inMasterMode()`（用户可能已在 1.5s 窗口内切走）。

XPAN/retroCamera/人像/夜景/photo 等所有非大师模式从此零干预（原生行为）。
r376 已验证的大师实况修复不受影响（流程内 currentMode 恒为 professional）。

### 20.4 验证判据

- XPAN 拍实况不再闪退（视频正常出片）；
- 大师实况修复依旧有效（GR→大师连拍视频与封面一致）；
- 日志中 `delayed re-arm` 行只应出现在大师模式期间，XPAN 期间绝不应出现。

## 21. r377 双 bug 复盘：大师空闲灰 + XPAN 实况绿 → 改写式修复（r378，2026-11-15 10:55）

### 21.1 大师空闲灰（复现条件：GR 拍一张 → 切大师 → 停 1-2 分钟）

日志链（`work/r377_test3.txt`，10:44-10:46 会话）：
- 10:44:33 进大师，34.867 延迟补刀 palette（=true）→ 预览正常约 30 秒；
- **10:45:01.371 HardwareBuffer.close 泄漏潮**（finalizer 线程回收大量未关闭 buffer，tid 19101）；
- ~10:45:30-10:46:00 预览变灰（用户 10:46:01 触摸反应：ACTION_DOWN → 切 commonVideo）；
- 上一轮灰会话（r377_test2，10:38:55-59）同样有泄漏潮（182 条）——两轮灰的公共前兆；
- 灰的恢复 = 灭屏/亮屏（预览管线整体重建）；模式切换进出无效。
- 另证：全日志三次 lutTaskComplete 全是 **VEP 视频处理线程**的（XPAN 实况 ×2 + GR 实况 defer
  job ×1），预览自己的 lutTask 在任何会话（好坏都算）都从未出现——**§19 的「lutTask 掐死」机理
  标签贴错了对象**：灰与 lutTask 无关，真正的公共因素是「空闲期延迟补刀线程的第二次 init +
  GC 泄漏潮」。

### 21.2 XPAN 实况绿（封面正常、播放全绿）

模块探针铁证对比：
- 好会话（r374，08:48:13.930）：`rendererPrepared=true, selectedSize=64, selectedHash=379247366,
  basicHash=-1504930108`
- 绿会话（10:37:38.156）：`rendererPrepared=false, rendererRNull=true, rendererSNull=true,
  selectedSize=-1, selectedHash=0, basicHash=-881252736`

`basicHash` 变了 = `Lut3dDataManager.c`（BasicTone float[]）被 GR init 换成了 GR 表；后续
highPixel/xpan 的 initV2「延迟读」不清也不更新该数组 → GR 残留表长期生效。XPAN 实况桥接
（`ensureXpanLiveBasicToneTexture`）用 `manager.c` 建 GL 纹理 → **XPAN 帧用 GR 表渲 → 色彩
空间错乱 → 播放全绿**。「原来是好的」时代无 GR hook，`manager.c` 恒为 photo palette 表。
（r376 的 XPAN 闪退 = 同一残留链的另一种表现：selected 空时 WatermarkRenderer 拿 null NPE。）

### 21.3 r378 修复：改写式，彻底删掉延迟补刀

`GrPreviewStreamRestore` 全量重写，三件事，全部同步、零线程、零 sleep：
1. **GR 出口填表**：ModeSwitch 离开 GR 时同步跑一次 `e(1,2,"common")`（palette initV3 立即读表）
   ——把 `manager.c` 刷回 photo palette 表（XPAN 及所有后续模式受益）；此刻在相机目标模式的
   env 刷新之前，不存在打断窗口。
2. **大师刷新改写**：before-hook 把 master 下的 `e(1,0,"professional"/"")` 改写成
   `e(1,0,"common")`——相机自己的刷新直接走 palette 分支（photo 模式的原生序列，stock 长期
   验证），不再需要任何事后补丁 init。灰的两条路径（即时补刀掐渲染 / 延迟补刀污染空闲管线）
   连同机制一起删除。
3. **空名视频矫正**（保留）：master 下 `e(1,2,"")` → `"common"`。

幂等性：memo 按 (combineType, capMode) 记；(1,0,common) 与 (1,2,common) 交替 init，与 stock
photo 模式的 (1,0)+(1,2) 交替序列一致。XPAN/photo/GR 的原生刷新零干预（r377 的模式门控保留）。

### 21.4 验证判据

- **大师**：GR 拍一张 → 切大师 → 停 2-3 分钟：预览不应变灰；日志应有
  `GR exit palette fill -> professional, e(1,2,"common") = true` 与
  `master refresh rewritten: e(1,0,"professional") -> e(1,0,"common")`；
- **XPAN**：GR 拍一张 → 切 XPAN → 拍实况：视频应正常不绿不崩（探针 selectedHash 应非 0）；
- **大师实况回归**：GR → 大师连拍实况视频仍与封面一致。

## 22. r378 双症状复盘：大师立即灰 + XPAN 仍绿 → 延迟补刀统一架构（r379，2026-11-15 11:20）

### 22.1 大师立即灰＝(1,0,common) 改写本身

r378 实测（11:02:43，pid 30082）：`GR exit palette fill = true` + `master refresh rewritten:
e(1,0,"professional") -> e(1,0,"common")` 都执行后，**切到大师预览立即变灰**。大师预览的
GL look 链依赖 capMode=0 槽名为 "professional"——改写为 "common" 直接断链。同会话的大师
实况视频（IMG20261115110303，11:03:03）经分析工具检验帧均值 [120,118,117] **颜色平衡**——
改写对视频槽是有效的，坏的只是预览槽。§21「e(1,0,common)＝stock photo 原生序列所以安全」
的假设被推翻：photo 模式安全，大师模式不安全。

### 22.2 XPAN 绿的真相：视频帧根本没有相机内容（不是调色问题）

用 `tools/analyze_live_photo.py` 解出绿实况的内嵌视频（`work/lp_r378/xpan_green_embedded.mp4`）：
- 全片所有采样帧均值**逐位相同** [122.817, 188.634, 122.817]——同一帧静态重复；
- 接触表目检：纯绿单色填充＋上下 XPAN 字样的边框 UI（水印/letterbox 渲染正常），
  **没有相机内容**。R==B≈123、G≈189 正是「亮度有结构、色度平面全零」的特征
  （chroma=0 时 R/B 被对称压低、G 保留亮度主导）。
- r377 绿会话（10:37）与 r378 绿会话（11:03）的帧路径探针**逐字段相同**
  （path=common, basicBinds=0/0/0, warpDraws=0…），唯一差异是 basicHash（GR 表 vs palette 表）
  ——两者都绿 ⇒ 绿与我们桥接纹理的表内容无关，与 GR 访问本身相关。

### 22.3 根因链：XPAN 没有自己的视频槽刷新

日志铁证：进 XPAN 后只有 `e(1,0,"xpan")`，**从未有过任何 `arg=2` load**（master 的
延迟任务也不打）；photo 模式的延迟任务打 `e(1,2,"common")`（palette initV3 立即读表）。
⇒ **XPAN（及一切无 arg=2 任务的模式）的实况视频槽完全靠继承**：
- 好时代（photo→XPAN）：继承 photo 的 palette 槽＋palette `manager.c` → XPAN 实况正常；
- GR 之后：`e(1,0,xpan)` 走 initV2，其 `process()` 步骤在 GR 4 参 init 污染的 native 环境
  下重算 `manager.c`（r377 探针实证：basicHash 从 palette -1504930108 变 GR -881252736；
  r378 GR 出口 fill 恢复后又被 e(1,0,xpan) 再次毒化）→ 桥接纹理/渲染链拿到坏数据 →
  色度全零的绿屏。
- r376 闪退＝同一残留链的另一种表现（j() 回调送 null → WatermarkRenderer NPE）。

附：Lut3dDataManager 反汇编确认——`e()` 每个分支结尾都跑 `CombineLutHelper.process(...)`
并把结果写进字段 `c`（即"initV2 延迟读不清 c"的旧表述不准确：**是 initV2 的 process 在
被 GR 污染的 native 环境下产出坏 c**）；palette initV3 分支则产出正确 palette 表。

### 22.4 空闲灰的旧证据作废

HardwareBuffer.close 告警是 **stock 常态噪音**：好会话 r374_test1 有 8650 条、log_good_1940
有 10715 条，与灰会话同量级（§18/§21 把泄漏潮当作灰前兆是误判）。r377 空闲灰会话里
35.8 之后模块再无任何介入，灰却出现在 ~60s 后——空闲灰与延迟补刀的因果未被证实，
也可能与 stock 长时间空闲行为有关。r379 恢复 r376 已验证时序后留观察。

### 22.5 r379 修复：统一延迟补刀架构

`GrPreviewStreamRestore` 全量重写，只剩两种介入（全部 leftGr 门控、零同步 init、零 GR 内介入）：
1. **master**：`e(1,0,"professional"/"")` 完成后 1.5s 补 `e(1,2,"common")`（r376 已验证时序，
   覆盖模式切换与每张拍后刷新 → 连拍全好）；醒来复查非 GR 且当前仍是 master/xpan。
2. **xpan（新增）**：`e(1,0,"xpan")` 完成后同样 1.5s 补 `e(1,2,"common")`——把好时代
   "从 photo 继承 palette 视频槽"重新交给 XPAN。
3. 空名 `e(1,2,"")→"common"` 改写保留（master 门控）。
删除：r378 的 GR 出口同步 fill（r375 式打断风险＋与 per-mode 补刀完全冗余）与
(1,0,common) 改写（立即灰元凶）。

### 22.6 验证判据

- GR→大师：预览立即正常（无灰）；~1.5s 后 `master video slot re-armed ... = true`；
  连拍 ≥2 张实况视频与封面一致；
- GR→XPAN（可直接去，或经其他模式）：~1.5s 后 `xpan video slot re-armed ... = true`；
  拍实况：视频应有真实相机内容（封面色调一致），不再是绿屏；
- 长时间（>2 分钟）停留在大师观察预览是否变灰（若仍灰且日志显示期间模块零介入，
  则空闲灰与模块无关，另行排查 stock 行为）。

## 23. r380 修复 XPAN 拍照闪退：拒绝已释放的 BasicTone 空壳（2026-11-15 14:06）

### 23.1 现象与直接根因

用户报告 r379 下 XPAN 一点拍照就闪退。崩溃仍发生在水印二次合成：

```text
java.nio.FloatBuffer.wrap(null)
  at com.oplus.camera.common.gl.t0.k(Raw3dTexture.java:56)
  at com.oplus.camera.common.gl.a0.b(GLRenderer.java:56)
  at ch.h.p(WatermarkRenderer.java:37)
  at gh.s4.b(WatermarkProcessor.java:1029)
```

`LutParameters.e` 在一次实况拍摄结束后仍保留非空的 `Raw3dTexture` 对象，但纹理销毁时其
内部 `float[] A` 已经被释放为 `null`。XPAN 水印合成原先只判断对象是否非空，于是把这个
空壳重新交给原厂 `GLRenderer` 上传，最终执行 `FloatBuffer.wrap(null)`。

这不是调色值错误，也不是模式判断错误；问题在于“Java 对象仍存在”不等于“纹理后备数据仍可
上传”。

### 23.2 r380 修复

修改文件：

```text
app/src/main/java/local/jiege/hook/xpan/XpanLiveFeature.java
```

1. 新增 `hasBasicToneData()`，同时检查对象类型、内部 `float[] A`、纹理尺寸和
   `size × size × size × 3` 数据长度；
2. 水印合成优先取当帧 `ch.g` 渲染器的有效纹理；渲染器纹理无效时，才接受仍完整无损的
   `LutParameters.e`；
3. 两者都无效时保留原厂水印路径，不再上传已释放的空壳；
4. 临时改写 `LutParameters.e` 后恢复原值，不把共享字段永久设成 `null`；
5. 重建 BasicTone 纹理前检查现有纹理后备数据，失效时重新创建。

### 23.3 构建产物

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.5-r380.apk
7,468,467 字节
SHA-256 09B12E437F9822A86DC35143BE5E828C922DC6BF9597EC01519623DADA8B4D59

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.5-r380-KSU.zip
305,503,837 字节
SHA-256 DCC30C125DC60B22D3C322E6CF5FFE254443575CB80AE9B5807A5D8E72BD6B06
```

设备热安装版本已核对为 `local.jiege.camera / Muse 4.4.5-r380 / versionCode 820`；
系统相机仍为 `com.oplus.camera / 7.006.123`。

### 23.4 实机验收

测试条件：PKJ110，XPAN，实况照片开启，水印开启；未重启手机。

本轮在同一相机进程 `PID 11405` 上完成：

1. 单拍 1 张，实况视频完成事件为 76 帧、约 3043 ms；
2. 同一 XPAN 会话连续拍摄 10 张；
3. 退出 XPAN，切到哈苏超清，再重新进入 XPAN；
4. 重新进入后再次连续拍摄 10 张。

关键日志：

```text
14:00:50.682 OCAM_LivePhoto-CapturedVideo: complete
14:00:50.684 frameCount:76, videoCodecDuration:3043ms
14:04:36.847 XPAN watermark composite replaced stale BasicTone texture
             with this frame's valid texture, mode=xpan
```

第 3 步重新进入后实际命中了问题路径：参数里的纹理仍是失效空壳，r380 用当帧有效纹理替换，
没有把空壳送去上传。随后每次拍摄后的 `pidof com.oplus.camera` 均为 `11405`；本轮新增
21 张过程中未再出现：

```text
FATAL EXCEPTION
FloatBuffer.wrap
Raw3dTexture.java:56
```

本地证据：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r380_validation_20261115\r380_crash_validation_relevant.log
E:\OmniCam-Muse-main\output\xpan_live_photo\r380_validation_20261115\r380_xpan_crash_validation_20261115.log
E:\OmniCam-Muse-main\output\xpan_live_photo\r380_validation_20261115\r380_xpan_reenter.xml
```

结论仅覆盖本次“XPAN 点拍摄即闪退”问题。实况动态附件内容、旧的颜色/亮度问题仍按各自
复现证据单独验收，不因本次闪退修复而一并宣称闭环。

## 24. r388-r391 XPAN 实况：网格创建成功但 EIS 队列为空（2026-11-15 18:00）

### 24.1 现象拆分

XPAN 实况当前要分开看两条链：

1. 封面和动态附件的颜色映射；
2. EIS 网格是否真正进入 `ImageData.o`，再由 `VideoEffectPipeline` 交给 Warp 渲染。

r387 已证明动态附件本身可以带正确颜色，但 XPAN 的 1920×864 视频流仍可能绕过 Warp，
造成动态画面、方向和亮度与封面不一致。因此不能只凭“附件不再是 Log 灰”判断 EIS 链正常。

### 24.2 r388：普通照片实况与 XPAN 的路线差异

普通照片实况的正常基线：

```text
EIS init: 1920x1440
EIS grid: 29x16
ImageData.e(grid) before: directGridNull=true
ImageData.e(grid) after:  directGridNull=false
EIS status: 150 -> 101
动态附件完成: 93 帧
```

XPAN 旧路径：

```text
XPAN live EIS bypass active
EIS init-after: bypass=true, gridNull=true
ImageData.e inputGridNull=true
directGridNull=true
VideoEffectPipeline: path=common, warpDraws=0
```

本地证据：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r388_eis_route_20261115\normal_photo_live_r388.log
E:\OmniCam-Muse-main\output\xpan_live_photo\r388_eis_route_20261115\xpan_live_r388.log
```

原因不是 Warp 开关本身。原厂 `fh.a.d()` 仍为真，真正的问题是 XPAN 强制走 EIS bypass 后，
`gh.g.c()` 跳过网格创建，于是：

```text
gh.g.x == null
-> ImageData.e(null)
-> ImageData.o == null
-> VideoEffectPipeline 没有可用网格
-> Common fallback
```

### 24.3 r389：不能直接关闭 EIS bypass

将 `persist.omnicam.xpan.eisbypass` 暂时设为 `0` 后，网格可以创建，但原生 EIS 对 XPAN
1920×864 流持续返回状态 `152`：

```text
EIS process failed, result 152
VideoEncoder onError, code=152
sendMetaToEis unknown err, mStatusCode=152
```

因此直接删除 bypass 会让动态附件收不到有效帧，不是可用方案。日志：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r389_native_eis_20261115\xpan_native_eis.log
```

### 24.4 r390：临时允许网格创建，暴露遗漏的队列初始化

r390 只在 `gh.g.c()` 执行期间临时让 EIS bypass 谓词返回 `false`，让原厂创建网格，返回后
立即恢复 bypass。网格创建确实成功：

```text
phase=init-after, bypass=true, eisEnabled=true, gridNull=false
```

但下一帧进入原厂 bypass 分支时立即崩溃：

```text
java.lang.NullPointerException
  Attempt to invoke virtual method 'boolean java.util.ArrayDeque.offer(java.lang.Object)'
  on a null object reference
  at gh.g.f(EISProcessor.java:810)
```

反编译确认 `gh.g.c()` 的两个分支互斥：

- 创建网格分支直接 `return`；
- 只有不创建网格的 bypass 分支才执行 `this.z = new ArrayDeque<>()`。

r390 同时进入了“创建网格”和“后续 bypass 消费元数据”两种状态，恰好绕过了原厂默认的
字段初始化组合。日志：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r390_grid_init_fix_20261115\xpan_grid_init_fix.log
```

### 24.5 r391 修复

修改文件：

```text
app/src/main/java/local/jiege/hook/xpan/XpanLiveFeature.java
```

r391 在 XPAN 实况的 `gh.g.c()` 成功返回后，如果 `gh.g.z` 仍为空，就补建原厂同类型的
`ArrayDeque<Long>`；并在每帧进入 `gh.g.f()` 前再做一次同类空值防呆。作用域受 XPAN 模式、
实况开启和 EIS bypass 状态约束，不修改原厂方法，也不改变其他模式的 EIS。

再次核对反编译代码后，这条修复与后续消费路径能够闭合：

```text
gh.g.c() succeeds -> gh.g.x != null
gh.g.z repaired  -> bypass branch can offer metadata
gh.g.f()         -> status 101 after queue has data
gh.v.f8()        -> imageData.e(gh.g.x)
ImageData.e()    -> ImageData.o != null
```

修复后的预期链路：

```text
XPAN live EIS metadata queue initialized at grid-init
phase=init-after, bypass=true, gridNull=false
ImageData.e(grid), directGridNull=false
EIS status 150 -> 101
VideoEffectPipeline path 中出现 warp
动态视频正常完成
```

这条修复不宣称已经实机成功。手机在 r391 构建完成前已撤走，因此以下验收尚未执行。

### 24.6 构建与静态核验

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.5-r391.apk
7,484,851 字节
SHA-256 231F73A3CC833F6A1E93E77E2E00A930A3C7EC1D524106E1609E98ABD82B46EB

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.5-r391-KSU.zip
305,507,868 字节
SHA-256 46C7035081180C2CD304848E7140AFD7A19F91E7B9792976C5522A70F4F6C5B0
```

- APK 版本：`Muse 4.4.5-r391 / versionCode 831`；
- APK Signature Scheme v3 验证通过；
- ZIP 内外 APK 均为 7,484,851 字节且 SHA-256 完全一致；
- `verify_port_artifacts.py` 六组检查全部通过；
- 更新构建脚本后已重新完整构建；Gradle 52 个任务全部真实执行，脚本要求的 XPAN 57 条
  探针全部存在，不再依赖人工检查旧 APK；
- DEX 已确认包含 `metadata queue initialized`、初始化失败和 EIS route 探针字符串。

### 24.7 下次实机验收顺序

1. 保持 `persist.omnicam.xpan.eisbypass=1`，安装 r391，不重启手机；
2. 进入 XPAN，开启实况，先停留 3 秒再拍，避免把初始缓存阶段当作失败；
3. 确认 `XPAN live EIS metadata queue initialized at grid-init` 只出现一次；
4. 确认 `init-after` 为 `bypass=true, gridNull=false`；
5. 确认 `ImageData.e` 之后 `directGridNull=false`；
6. 确认主帧日志出现 `path=warp` 和 `warpDraws>0`；
7. 检查动态附件是否连续、方向正确，并与封面比较颜色和亮度；
8. 确认相机 PID 不变，无 `ArrayDeque.offer`、`FATAL EXCEPTION` 或 status 152。

若队列修复后仍为 `path=common`，应保留这一轮的完整 logcat、封面、动态附件和主帧日志，
再检查 Warp 入队分支；不要继续通过放宽 EIS status 或关闭 bypass 来猜测修复。

### 24.8 r392 实机结果：停止强制 Warp，优先恢复真实动态画面

r391 的实机动态附件可以正常解码 79 帧，但三处采样均接近 RGB
`[190,190,190]` 至 `[235,235,235]`，主帧为 `path=warp`、`warpDraws=1`，
同时 `transformNull=true`。这与用户在相册看到的空白播放一致，证明问题发生在
动态附件生成阶段。

r392 将强制创建 EIS 网格移到显式诊断属性 `persist.omnicam.xpan.eisgrid` 后面并默认关闭，
使 XPAN 实况回到已验证能拿到相机纹理的 common 路线。实机未重启手机，覆盖安装并只重启
相机进程后，连续拍摄两张，最终动态附件均为 79 帧、约 3.2 秒，画面内容真实：

```text
path=common
commonDraws=1
warpDraws=0
transformNull=true
OCAM_LivePhoto-CapturedVideo: complete
frameCount:79
videoCodecDuration:3164ms
```

相册识别为实况照片，播放时可见画面变化，不再空白。相机 PID 全程保持 `5933`，
没有 `FATAL EXCEPTION`。本轮没有证明 Warp 路线可用，也没有宣称动态与封面的亮度完全一致；
接触预览仍显示动态画面比封面更亮、更灰，应作为下一项独立问题处理。

本地证据：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r392_validation_20261115\
IMG20261115202251_final.jpg
E:\OmniCam-Muse-main\output\xpan_live_photo\r392_validation_20261115\
analysis_202251_final\xpan_r392_zoom_cover_video_contact_preview.jpg
E:\OmniCam-Muse-main\output\xpan_live_photo\r392_validation_20261115\
r392_xpan_validation.log
```

## 25. r395 复核 GR → 大师实况：LUT 任务成功，但视频槽没有延迟补刀（r396，2026-11-15）

### 25.1 本轮现象和样本

复现路径保持单一：

1. 进入 GR，拍一张；
2. 切到大师；
3. 等待大师预览稳定后拍约 3 秒运动实况；
4. 在本地拆出实况照片内嵌视频，比较封面与视频首、中、末帧。

视频不是只有某一帧偏色：72 帧整段都持续偏暖，饱和度也高于封面，说明问题发生在录制窗口
使用的色彩环境，而不是封面选取时点或单帧合成。

| 样本 | 平均 RGB | 平均饱和度 |
|---|---|---:|
| 封面 | `148.193 / 148.694 / 143.025` | `85.709` |
| 视频首帧 | `172.454 / 156.391 / 130.217` | `92.710` |
| 视频中帧 | `167.616 / 151.257 / 128.587` | `95.370` |
| 视频末帧 | `168.641 / 152.180 / 128.840` | `95.105` |

本地证据：

```text
E:\OmniCam-Muse-main\output\gr_master_live_lut_r395_diag\diag_so_far.log
E:\OmniCam-Muse-main\output\gr_master_live_lut_r395_diag\IMG20261115214426.jpg
E:\OmniCam-Muse-main\output\gr_master_live_lut_r395_diag\analysis_214426\master_live_embedded.mp4
E:\OmniCam-Muse-main\output\gr_master_live_lut_r395_diag\analysis_214426\master_live_measurements.json
E:\OmniCam-Muse-main\output\gr_master_live_lut_r395_diag\analysis_214426\master_live_cover_video_contact_preview.jpg
```

### 25.2 已排除：LUT 回调没有失败

本次 `VideoEffectPipeline` 正常初始化：

```text
21:44:26.734 VideoEffectPipeline: init ... cameraId: 12, lutType: 7
```

同一会话的只读诊断确认：

```text
callbackSeen=true
taskCompleted=true
taskFailed=false
rendererRNull=false
rendererSNull=false
watermarkNull=false
```

因此不能再把这次现象归因于 LUT 回调丢失、GL 任务未完成或 WatermarkRenderer 拿到空纹理。
r394 曾尝试通过 `GrMasterLiveLutRestore` 直接恢复任务；它与本次证据不吻合，r396 没有启用这条
路径。

### 25.3 与 r376 已实机通过方案的差异

r376 在“离开 GR 后的大师模式”里，会在相机自己的
`e(1,0,"professional")` 或空模式名刷新完成后等待 1.5 秒，再执行：

```text
e(1,2,"common")
```

这一步把大师实况的视频槽重新装到 palette 状态。r376 当时已经通过实机验证；后续为修复
XPAN、人像和相册问题，多个版本反复重写或停用了这段逻辑。

本次 r395 日志只有：

```text
21:44:24.091 e(1,0,"professional")
21:44:24.678 e(1,0,"professional")
```

整段没有 master 视频槽的 `e(1,2,"common")` 补刀，也没有
`master video slot re-armed ...`。这与“LUT 任务成功、但视频整段持续偏暖”完全吻合，因此
r396 首先恢复 r376 已验证的时序，而不是继续扩大到新的色彩改造。

### 25.4 r396 修改

`GrPreviewStreamRestore` 重新挂入 GR 模式：

1. 只有本相机进程真实离开过 GR 后才启用；
2. 大师收到 `e(1,0,"professional")` 或空模式名刷新后，延迟 1500 ms；
3. 醒来后重新检查 generation、当前模式和 GR 状态，仍在大师时执行
   `e(1,2,"common")`；
4. 不修改 `e(1,0)` 的模式名，避免复现 r378 的大师预览立即变灰；
5. 旧任务会被新的刷新或模式切换作废，不会一个手势连续叠多次补刀；
6. XPAN 中原有的同类延迟补刀保留，只处理 XPAN 自己的 `e(1,0,"xpan")`。

同时把 `GrEffectTransport` 中 `tracedInit.equals(...)` 改成空值安全的
`"gr".equals(tracedInit)` / `"stale".equals(tracedInit)`。r395 日志实际出现过一次
`String.equals on null object`，该异常不应继续留在测试包内。

### 25.5 r396 实机验收

手机回来后不重启设备，只安装 r396、强制停止并启动相机：

1. GR 拍一张，切大师并等待至少 2 秒；
2. 日志应出现
   `professional video slot re-armed after e(1,0,"professional") -> e(1,2,"common") = true`；
3. 若拍后相机再次刷新 `e(1,0,"professional")`，应再次出现同类补刀；
4. 预览不得变灰、不得闪退；
5. 连续拍至少 2 张运动实况；
6. 用 `tools/analyze_live_photo.py` 比较两张的封面与视频 RGB、饱和度。只有两张视频都明显
   接近各自封面，才能宣布该项闭环。

### 25.6 r396 构建产物

完整构建、APK 版本回读、DEX 探针、v3 签名、模块包内外 APK 一致性及
`tools/verify_port_artifacts.py` 六组检查均已通过：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.5-r396.apk
7,501,235 字节
SHA-256 C01BA14594AEC5D0CD9A0D575589C5A40DB440751D6D18515D9C6C44B266CDA6

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.5-r396-KSU.zip
305,515,091 字节
SHA-256 7336422E744C62C157874D21AA78EE0ED55DE082F30A6F7AFF321CE50E62AA4C
```

r396 仍只属于静态构建通过的测试包。最终必须用真实实况成片确认两张视频都接近封面，不能
只凭补刀日志出现就宣布修复成功。

## 26. r397：暂停大师模式延迟补刀，验证亮屏/切换后的再次灰化（2026-11-16）

r396 实机已明确复现“切换一次或熄屏再点亮后，预览先正常、约 1.5 秒后又变灰”。日志与最终
灰图来自同一会话：

```text
07:44:42.166 onResume
07:44:42.211 Lut3d.load id=1 arg=0 mode=professional
07:44:42.212 CombineLut_Initialize
07:44:42.218 SimTool_Master.ini
07:44:43.756 Lut3d.load id=1 arg=2 mode=common
07:44:43.757 CombineLut_Initialize
07:44:43.764 SimTool_Dolby.ini
07:44:43.772 professional video slot re-armed
07:44:43.783 selected=common, cap=2
```

本地同区域测得正常大师截图的平均饱和度为 `0.4662`，亮屏后延迟灰图为 `0.1858`；此前确认的
真灰图约为 `0.1795`。因此当前不能把 r396 的延迟补刀视为成功修复。

证据文件：

```text
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\repro.log
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\repro_key_events.txt
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\current_2116.png
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\wake_continue_1.png
```

r397 只做一项诊断性改动：

1. 大师模式不再执行延迟 `e(1,2,"common")`；
2. XPAN 原有延迟补刀保持不变；
3. 不改 `e(1,0)` 模式名，不改 BasicTone 原生重载链；
4. 熄屏再亮和模式往返后，大师应持续保持 `SimTool_Master / cap=0`，不得再次出现由模块触发
   的 `SimTool_Dolby / cap=2` 切换。

该版首先验证灰化因果。若大师预览稳定后，GR → 大师实况视频重新偏暖，必须另找只作用于实况
视频流的恢复点，不能重新用全局 CombineLut 补刀换取预览灰化。

## 26. r397：暂停大师模式延迟补刀，验证亮屏/切换后的再次灰化（2026-11-16）

r396 实机已明确复现“切换一次或熄屏再点亮后，预览先正常、约 1.5 秒后又变灰”。日志与最终
灰图来自同一会话：

```text
07:44:42.166 onResume
07:44:42.211 Lut3d.load id=1 arg=0 mode=professional
07:44:42.212 CombineLut_Initialize
07:44:42.218 SimTool_Master.ini
07:44:43.756 Lut3d.load id=1 arg=2 mode=common
07:44:43.757 CombineLut_Initialize
07:44:43.764 SimTool_Dolby.ini
07:44:43.772 professional video slot re-armed
07:44:43.783 selected=common, cap=2
```

本地同区域测得正常大师截图的平均饱和度为 `0.4662`，亮屏后延迟灰图为 `0.1858`；此前确认的
真灰图约为 `0.1795`。因此当前不能把 r396 的延迟补刀视为成功修复。

证据文件：

```text
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\repro.log
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\repro_key_events.txt
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\current_2116.png
E:\OmniCam-Muse-main\output\gr_master_live_lut_r396_test\live_probe_20261116\wake_continue_1.png
```

r397 只做一项诊断性改动：

1. 大师模式不再执行延迟 `e(1,2,"common")`；
2. XPAN 原有延迟补刀保持不变；
3. 不改 `e(1,0)` 模式名，不改 BasicTone 原生重载链；
4. 熄屏再亮和模式往返后，大师应持续保持 `SimTool_Master / cap=0`，不得再次出现由模块触发
   的 `SimTool_Dolby / cap=2` 切换。

该版首先验证灰化因果。若大师预览稳定后，GR → 大师实况视频重新偏暖，必须另找只作用于实况
视频流的恢复点，不能重新用全局 CombineLut 补刀换取预览灰化。

## 23. 用户关键线索「r232 正常」→ 回到 r232 做代码考古（r405，2026-11-16 15:05）

### 23.1 r232 到底"正常"在哪

`git show 9fd9e62`（r232 稳定版）对比当前工作区，三个事实：

1. **r232 的 `GrModeHooks` 根本没有 MasterColor / GrPreviewStreamRestore /
   GrMasterLiveLutRestore 这些注入**——大师色彩那套（master look 烘表、预览表 pin、
   实况表桥接）在 r232 时不存在。
2. **r232 的 `applyLmtPath` 是无条件早退**：`if (activeProfile == kUnknown) return;`
   —— 无画像的流对象（预览、实况视频）**没人碰它的 LMT 树**，它一直持有相机给它的那棵。
3. r232 的 `MasterColor.java` 已存在但也只是注入（`inMaster` 只有 ModeSwitch 一处维护）。

所以「r232 正常」的真实含义是：**那次拍摄的封面和视频都是原厂色调**（master look 那时
还落不到成片——这正是 r338 `e5aeda2` 修的 `LMTPhotoLut` 拼写 bug）。两边一致，不是视频
被正确调色。

r338 之后封面开始带 master look，视频没跟上，差异才出现。用户感知的「视频鲜艳」=
**视频还停在原厂（或旧表）色调，而封面已经变淡雅**。

### 23.2 r232 → 现在，所有能影响视频颜色的改动（逐条）

| 改动 | 引入 | 对视频的影响 |
|---|---|---|
| master look 落到成片（`LMTPhotoLut` 拼写修复） | r338 `e5aeda2` | **封面开始带 look**，与视频首次产生差异（一切的起点） |
| `getLmtParams` 双 arity hook | r333-r335 `c4ae53c` | 无（只保证 profile 能被记到） |
| `applyLmtPath` 的 kUnknown 分支主动切树 | r361 `acae7d5` | 无画像加载会被切到 master/host 树 |
| `reloadStreamTree`（把无画像的流重载到 `pinnedPreviewDir()`） | 未提交，r363 后 | **视频流的表直接由预览 pin 决定** |
| `lut_replay`（camera id 12 残留 GR 表的补救） | r361 | **opt-in，marker 文件不存在＝从未生效** |
| `inMaster` 被 `Lut3d.load` 的任意 mode 覆盖 | 既有 | 见 23.3，会把整条预览表链打停 |

### 23.3 本次实测抓到的两个具体缺陷

**(a) 预览表链被内部模式名打停，pin 冻结**

日志（`work/r379_test1.txt`，r404）整个会话只有 `capture`/`baked` 两种 MasterColor 行，
**没有任何 `preview table` / `preview key`**。而 `live/` 目录里 14:48 明明烘出了
`p04000_42dd…`，pin 文件却仍指向 **10:50** 的 `p04000_4677…`。

原因：`onPreviewResult` 的守门条件是 `inMaster`，而 `inMaster` 在 `Lut3d.load` 的
before hook 里被写成 `MASTER_MODE.equals(args[2])`。相机在模式切换/拍完刷新时会用内部名
（`commonVideo` 等）调 `Lut3d.load`，于是 `inMaster` 被打回 false，整条预览表链停摆，
pin 再也不会更新。视频流于是长期读一棵旧表（本次是 10 小时前、另一组 CCM 的）。

**(b) `reloadStreamTree` 会把封面刚设好的树抢走**

同一份日志：

```
14:49:54.303  BasicTone profile=master-colour path=…/live/t04097_…
14:49:54.355  stream tree reload: actual=t04097  target=p04000_4677   ← 44 ms 后
14:51:55.199  capture … path=main … -> …/live/mb04097_…
14:51:55.288  stream tree reload: actual=mb04097 target=p04000_4677   ← 87 ms 后
```

原因：`frameProfiles` 在 `processCore` 里是 **用完即 erase**，所以一张照片只有第一个 pass
带 profile；后续 pass 落进 else 分支被当成"流帧"，`reloadStreamTree` 于是把**拍摄对象自己**
重载到预览表。拍摄对象和视频流对象因此可能被不同表调色。

### 23.4 r405 修复

1. **`MasterColor`：`inMaster` 只置真不置假**
   `Lut3d.load` 只能"确认"大师模式，不能否定它；离开大师由 `hookModeSwitch` 唯一负责
   （那里知道屏幕真的换了）。预览表链不再被内部刷新名打停，pin 恢复跟随当前 CCT/CCM。
2. **`gr_basictone.cpp`：`reloadStreamTree` 不再碰刚被拍摄设定的对象**
   新增 `appliedObject/appliedMs`，`applyLmtPath` 设定完记一笔；`reloadStreamTree` 对
   1.5 s 内刚被设定的同一对象直接返回。封面的表不会再被同一次拍摄的后续 pass 抢走。
3. 诊断：`onPreviewResult` 每条早退路径打一行 `preview chain idle: <原因>`（同因只打一次），
   下次日志可以直接看预览链是不是活的。

`lut_replay` 仍是 opt-in（marker `/data/user/0/com.oplus.camera/files/jiege/lut_replay`）。
本轮不动它——它与 23.3(a)(b) 是两条独立路径，先看修完这两条还剩多少差异，再决定是否启用，
免得一次改两个变量。

### 23.5 验证判据

GR 拍一张 → 切大师 → 等 2~3 秒 → 拍实况 → 播放：

- 日志应出现 `preview chain idle: …` 只在真正该停的时机（拍摄中/合成中），
  空闲时应出现 `preview key camera=…` 与 `preview table …/p0xxxx_…`，且 pin 文件
  `…/mastercolor/preview` 的目录名应随 CCT 变化，不再是很久以前的；
- 拍摄时不应再出现 `stream tree reload: actual=<封面表> target=<预览表>` 这种
  刚设好就被抢的配对；
- 视频与封面观感应一致。

## 27. r405 实测仍鲜艳 → 实况纹理同步的 identity 短路（r406，2026-11-16 15:20）

### 27.1 r405 的两处修复都生效了，但仍鲜艳

`work/r405_test1.txt`（相机 pid 9257，会话 15:15:25 起）：

- **预览表链活了**（§23.3a 修复生效）：15:16:27.360 `preview metadata settled after gr ->
  professional camera=2 cct=3976 ccmHash=f7b0711f` → `preview key camera=2 look=hncs cct=4000`
  → `preview table .../live/p04000_4677e38fe4339f0f` → `preview LMT ...`。整条链完整。
- **封面表没被抢**（§23.3b 修复生效）：整个会话 **零条** `stream tree reload`（上一版每次
  拍摄都有 44ms / 87ms 的抢树配对）。
- 封面表：15:16:33.059 `baked .../mb04017_...` + `BasicTone profile=master-colour path=.../mb04017`，
  静图 native 侧 `basictone: [BasicTone.cpp] 1165 readLMTLut() m_pcNextLutnameLMTLut0_1_2_3`——
  **封面读到了完整 4 张 LMT**。
- 视频侧：15:16:26.4 ~ 15:16:39.7 的 CombineLut 块全部 `NextLutname_LMTLut0_0_0_0`
  （偶发 `0_1_0_0`）——**视频链一张表都没有**。
- 观感/数值：封面 RGB [149.8,148.2,127.8] sat 110.2；视频 [164.0,147.9,130.9] sat 89.8，
  **R 通道比封面高 14.2**，接触表目检为明显的暖红偏。

### 27.2 关键对照：「R+14」不是版本回归，而是恒定量

把用户认为「正常」的那两轮 r374 实况（08:48:28 / 08:49:27）拉下来用同一工具测量：

| 样本 | 封面 RGB | 视频 RGB | delta |
|---|---|---|---|
| r374 good1 (08:48:28) | 150.0 / 149.4 / 143.3 | 164.7 / 148.3 / 124.8 | **R +14.7  B −18.6** |
| r374 good2 (08:49:27) | 150.6 / 149.7 / 144.4 | 164.3 / 148.2 / 124.5 | **R +13.7  B −19.9** |
| r406 前 (15:16:29)  | 149.8 / 148.2 / 127.8 | 164.0 / 147.9 / 130.9 | **R +14.2  B +3.1** |

⇒ **视频的 R 通道比封面高约 14 是所有版本都存在的固定特征**，r232 时代也一样（那时封面
不带 look，两边一起偏，眼睛看不出来）。r338 之后封面带上 look，这个固定的 R 增益才被看见。
所以这一轮不能再去追「哪次改动弄“坏”了视频」——要解决的是**视频帧从来没有用上带 look 的表**。

### 27.3 真凶：`GrMasterLiveLutRestore` 的 identity 短路

`GrMasterLiveLutRestore` 的职责正是「每帧前把 `manager.c`（BasicTone 表）推给
`renderer.s`」，但整个会话 **零条** `master live BasicTone texture synced`。原代码：

```java
WeakReference<float[]> previousRef = syncedTables.get(renderer);
float[] previous = previousRef == null ? null : previousRef.get();
if (previous == table || currentTable == table) { ...; return; }   // ← 陷阱
```

`manager.c` 是 **native 原地复用的同一个 float[] 实例**（内容每帧在变、对象身份不变）。
所以第一次同步成功后 `previous == table` 永远成立 → **后续每一帧都静默 return**，
`renderer.s` 被永久冻结在进程早期建的那张原厂表上。视频于是永远是「裸流色调」（暖、艳），
与已经带上 look 的封面分道扬镳。

r374 那几轮之所以看着「对」，是因为它的 `e(1,2,"common")` 重建让 CombineLut 环境本身有表，
把 renderer.s 的冻结掩盖了；r397 为修预览灰关掉那次重建后，冻结就彻底暴露。

### 27.4 r406 修复 + 一次实测可判的诊断

1. **按内容采样比较代替对象身份比较**：`sample(float[])` 每 512 个取 1 个（约 1536 个样本
   + 数组长度）做指纹，内容变了就替换纹理。避免了每帧哈希 786k 个 float 的开销，也不会
   再被原地复用骗过去。
2. **每个早退分支打一行诊断**（同因只打一次）：
   `live texture sync skipped: <原因>`，原因覆盖 mode 门控（带 leftGr/master/current/gr 四值）、
   pipeline 门控、`o0.g`、`renderer.s == null`、manager 表非法、`already in sync: texture sample=…`。
   替换成功时打印 `old=… sample=…, new=… sample=…`，一眼看出换没换、换成什么。
3. 新增 `GrPreviewStreamRestore.modeName()` 供诊断读取当前内部模式名。

### 27.5 验证判据

GR 拍一张 → 切大师 → 拍实况 → 播放：

- 日志必须出现 **`master live BasicTone texture synced ... old=… sample=X, new=… sample=Y`**
  （X≠Y），且采样指纹随 CCT 变化而刷新（会出现多次，不是一次）；
- 若出现 `already in sync`，说明纹理本来就是当前表——那么鲜艳就与这张表无关，
  下一步查 CombineLut 视频槽（`NextLutname_LMTLut0_0_0_0`）；
- 若出现任一 `skipped: <原因>`，该原因就是下一个要修的门。

### 28. r406 实测：`already in sync` 是假阳性（r407 修复）

**实测（r406，2026-11-16 15:38，GR 拍一张 → 切大师 → 拍实况）**
用户反馈「还是鲜艳」。日志只有一条同步相关记录：

```
15:38:53.933 RicohGrPort: live texture sync skipped: already in sync: texture sample=3171eab
```

零条 `master live BasicTone texture synced`。按 §27.5 的判据落到第二分支——但**这一分支的
判据本身是错的**，不是「表已经是当前表」，而是「纹理的 A 字段和 manager.c 是同一个数组」。

**机理**：`gh.p3.onInit()` 把 `manager.c` 这个 float[] **实例本身**交给纹理（`texture.A`），
native 之后原地改写该实例的内容。于是 `sample(texture.A)` 与 `sample(manager.c)` 永远相等，
判重每次都答「已同步」并 return。GPU 纹理的内容却仍是 `onInit()` 那一刻上传的旧表
（当时还在 GR／上一个模式）——**采样比较的是对象持有的数组，不是 GPU 里的像素**。
r406 把「对象身份比较」换成「内容比较」，但比较对象选错了，症状原样保留。

**成片实测（`work/lp_r406/a.jpg`，`tools/analyze_live_photo.py`）**

| | R | G | B | sat | spread |
|---|---|---|---|---|---|
| 封面 3376x6080 | 148.4 | 147.9 | 132.7 | 92.1 | 33.2 |
| 内嵌视频 1428x2576 | 165.4 | 151.2 | 134.7 | 87.3 | 44.3 |
| 差 | **+17.0** | +3.3 | +2.0 | — | — |

R 涨 11.5%、G 涨 2.2%、B 涨 1.5% —— 三通道不同比例，是**真实的白平衡偏暖**，
不是 HDR→SDR / BT.2020 转换那类等比误差。封面的 R≈G（148.4/147.9，中性），
视频 R−G=14.2（明显偏红）：视频确实没有吃到同一张 look 表。

**r407 修复**（`GrMasterLiveLutRestore`）：判重改为比较「**上次真正上传**的内容指纹」。

```java
Uploaded last = uploadedTables.get(renderer);          // 上传时记录的 {纹理 identity, 内容指纹}
if (last != null && last.texture == texture && Arrays.equals(wanted, last.sample)) return;
// 否则无论纹理对象里那个数组长什么样，一律重新 g(纹理, size, table) 上传
```

- 上传记录用强引用（指纹约 1.5k 个 int = 6 KiB；原实现存 `WeakReference`，会被 GC 掉）。
- 记录里带纹理 identity：`onInit()` 重建纹理后必须重传，即使表没变。
- 上传成功日志新增 `shared-array=true/false`，直接证明「纹理持有的数组是不是就是 manager.c」。
- 失败重试上限 6 次，避免每帧刷屏。

**验证判据（r407）**：GR 拍一张 → 切大师 → 拍实况 → 播放，日志应有

```
master live BasicTone texture synced ... shared-array=true, old=… sample=<旧>, new=… sample=<新>
```

`shared-array=true` 即证实上面的机理。若 `synced` 出现但视频**仍然**鲜艳，
则说明 `manager.c` 本身不含 look（CombineLut 初始化读到的不是 live 表），
下一步改成核对 `manager.c` 与 live 目录表的数值对应，而不是继续在纹理上传上打转。

### 29. r408/r409：表本身的形状（放弃指纹，直接读像素数据）

**r407 实测**：`live texture sync skipped: params gate: o0.g false`，仍然零条 `synced`。

**决定性观测（r407 日志，16:16 会话）** —— `manager.c` 在 `e(1,0,"professional")` 前后：

| 时刻 | hash | head8 |
|---|---|---|
| 36.153（e 之前，仍是 GR 的表） | `cb792680` | 0, 0, 0, **0.014954**, 0.000793, 0.002838, 0.028564, 0.001434 |
| 36.751（e(1,0,professional) 之后） | `3085fb7` | 0, 0, 0, **0.000226**, 0, 0.000018, 0.000485, 0 |

格点 1 的 R 从 0.01495（≈1/63，正常曲线）掉到 0.000226 —— **表被压扁约 57 倍，等于没有 tone 处理**。
而 `renderer.s` 的内容就是它（`rendererS=t0@6531823` 与 `basicTable` 同 hash）。
`e(1,0,"professional")` 走 5 参 `initV2`（延迟读表），此时 `live/` 目录尚未烘完
（日志 39.154 才出现 `BasicTone profile=master-colour path=…/t04044_…`），于是算出这张空表。

**另一个必须记住的格式事实**：`live/<dir>/LMTLut0` 与 stock LMT 是 **planar**
（`raw[index]`=R、`raw[nodes+index]`=G、`raw[2*nodes+index]`=B，`index=(b*n+g)*n+r`，
见 `master_core.cpp` 的 `index = (size_t(b) * n + g) * n + r`），
而 `Lut3dDataManager.c`（`onInit()` 交给纹理的那个）是 **interleaved**（每格点 RGB 连续）。
两者不能直接互换 —— 这是 r409 转换的依据。

**r409**：
1. 新增 `MasterColor.previewTableDir()`，暴露当前 still 管线用的 live 目录。
2. `GrMasterLiveLutRestore` 读该目录的 `LMTLut0`，**planar → interleaved** 转置后缓存
   （按目录 + mtime/size 打戳），作为 `renderer.s` 的首选来源；读不到才退回 `manager.c`。
3. `dumpOnce()` 把两张表都写成 `tbl_<sample>.bin`（3 MiB，小端 float），
   成功日志带 `source=live|manager`。

**为什么这样最可能对**：封面用的就是这张 live 表（native BasicTone 读它），
视频换成同一张表，两者在定义上一致，不再依赖 `manager.c` 是否重建成功。

**判据**：`master live BasicTone texture synced … source=live` 出现 → 视频应立刻与封面同色。
若画面花屏/黑屏 → planar/interleaved 判断反了，把两个数组下标对调即可（改一行）。
若仍鲜艳但 `source=live` → 说明 `renderer.s` 不是调色载体，转查 `renderer.r` 或编码流。

### 30. r410-r412：`o0.g` 不是病根，视频用的是预览表（r409 结论被推翻）

**r409 实测**（`work/r409_test1.txt`，16:45 会话）：仍然鲜艳，且
`live table loaded from …/live/p04000_… sample=3171eab` 与 `tbl_3171eab`（manager 表）
**指纹完全相同** —— r409 的「改读 live 目录表」在数据上是空操作。

**逐字节比对（`work/lp_r409/`，logical 64³）**

| 对比 | mean abs diff | max | 结论 |
|---|---|---|---|
| `tbl_flat` vs `p04000` | 0.000000 | 0.000000 | **完全相同** |
| `tbl_flat` vs `mb04058` | 0.009928 | 0.371220 | 不同 |
| `tbl_flat` vs `t04058` | 0.064420 | 0.804010 | 不同 |

即：实况视频采样的表 = `p04000`（预览表，烘焙于 10:50），
**封面用的 `mb`/`t` 表是另一张**。§29 把 `p04000` 的“对角曲线扁平”当成“表坏了”是误读：
对角线 R≈G≈B 本来就是中性轴，look 的颜色差在**非对角**，所以对角看不出来。
`0.000226` 那个 head[3] 只是暗部点，不是整表被压扁。

**r410/r411 现场证据**（自动化实测，GR 拍一张 → 切大师 → 拍）

```
renderer.s null                                  ← 抓拍帧首次进入时纹理还没建
master live frame 1: o0.g=true renderer.p=true renderer.q=true texture=t0@fa82ca{size=64,length=786432} table=3171eab
draw p() seen 1: args=10 renderer=ch.g renderer.p=true renderer.q=true lutParams=bh.o lutParams.g=true renderer.s=t0@fa82ca{…}
```

- `LutParameters.g`（`ch.g.p()` 的 args[8]，stock 的 BasicTone 开关）在纹理就绪后
  **本来就是 true** —— r409 那次 `o0.g false` 只是“LUT 环境尚未初始化”。
  所以「视频帧没过 LUT」的假设不成立，r410 的开关翻转让位。
- r410 在 `renderer.s == null` 时就把 `g` 置 true，导致该张实况**丢了视频轨**
  （`removeCaptureTimeRange … mCaptureTimeRanges: {}`，3.9 MB 纯静图）。r411 起
  只有在纹理存在时才允许抬 `g`。

**r412 改动**（`GrMasterLiveLutRestore`）
1. 新增 `coverTableDir()`：扫描 `mastercolor/live/`，取最新 `mb*`（main buffer，封面用）
   目录，其次最新 `t*`，最后才回落 `MasterColor.previewTableDir()`。
2. `preferredTable()` 统一供两处入口使用（`gh.p3.r` 抓拍帧 + `ch.g.p` 每帧绘制）：
   优先封面表，读不到才用 `manager.c`。
3. 两张表都 `dumpOnce`，日志带目录名，便于离线复算。

**判据**：`master live BasicTone texture synced … sample=X` 里的 X 应等于
`tbl_<最新 mb>`；视频播放颜色向封面靠拢即成立。若换表后无变化，
则色差在 BasicTone 之外的环节（P010 转换 `ch.c` / `rgba2p010_layout_yuv_frag`），
下一步转查该程序是否绑定 LUT。

**注**：r412 已构建（`work/apk_build_r412.log`，Muse 4.4.5-r412 / 852），
但设备在安装前掉线（`adb devices` 为空），**尚未安装**。

### 31. r412 实测（自动化 GR→大师）：`synced` 终于生效，但色差变成"偏暗 + 偏暖"

**重要操作事实（差点误判一次）**：大师实况的 MP4 不是拍摄后立刻写进 JPEG 的。
`/data/vendor/camera_process/0/livephoto/<id>_main` 先落盘，延迟任务在**数分钟后**才把它
追加到 JPEG 末尾（实测 20:51 拍的 4.26 MB 在 20:58 变成 14.2 MB，20:53 的 4.25 MB → 18.3 MB）。
**刚拍完看到 4 MB 左右不代表丢了视频轨**，要等几分钟或等下一次保存动作。本轮我据此一度
误判"r410 起视频轨全丢"，实际全部正常，撤回该结论。

**现场（r412，20:48 GR 拍一张 → 切大师 → 20:51 拍实况）**

```
live table loaded from …/mastercolor/live/mb04173_cbf4642a8571945e size=64 sample=fd06e44b
master live BasicTone texture synced, size=64, source=manager,
  old=… sample=f3d9e7ff, new=… sample=fd06e44b, shared-array=false, thread=VideoEffectProcessThread
```

- `coverTableDir()` 生效：读到的是 `mb04173`（封面表）。
- 首次出现真正执行到底的 `synced`，`shared-array=false`（纹理里的数组确实不是 `manager.c`），
  证实 §28 的机理判断。
- 但 `isTargetMode()` 依赖 `hasLeftGr`：**直接从其它模式进大师（不经过 GR）时为 false**，
  两个入口全部早退（诊断日志 `mode gate: leftGr=false master=true current=professional`）。
  所以任何"大师实况"测试都必须先走一遍 GR，否则模块完全不介入，测不出东西。

**成片实测（`work/lp_r412b/`）**

| 张 | 封面 R/G/B | 视频 R/G/B（首帧） | 差 |
|---|---|---|---|
| 20:51:50 | 151.8 / 148.7 / 132.8 | 137.3 / 130.8 / 117.3 | −14.5 / −17.9 / −15.5 |
| 20:53:18 | 151.7 / 148.6 / 133.3 | 137.4 / 130.9 / 117.7 | −14.3 / −17.7 / −15.6 |

与 r409 的 `[150.1,148.9,136.6] → [166.6,151.2,133.1]`（视频更亮更红）相比，
r412 换表后**视频整体变暗约 10%，暖偏仍在**：

- ECC 对齐后逐通道拟合：R×0.646、G×0.543、B×0.500，截距 +38…+49，相关系数仅 0.54–0.72。
  增益不等且相关低 → 两者不是同一个色调曲线，视频对比度明显更低（阴影被抬起）。
  增益比 **R/G = 1.19、B/G = 0.92** 即仍有约 19% 的相对暖偏。
- 结论：**换表能改亮度，改不掉暖偏**。差异不在"用哪张 BasicTone 表"，而在
  静图与实况视频走的是两条不同的 HDR→SDR 归一化链路（静图走 still tone，视频走 VEP 的
  P010/`ch.c` 输出）。

**下一步方向（待用户主观确认后再动）**
1. 若用户认为"视频偏暗"不可接受 → 回退 r409 的预览表选择（`previewTableDir()`）。
2. 若仍是"鲜艳/偏黄" → 不再动 BasicTone 表，转查 VEP 的 P010 输出通路
   （`ch.c` / `rgba2p010_layout_yuv_frag`）里是否有第二处 LUT 或色彩矩阵，
   以及静图侧的 `master_color` / `gr_adjustment_lmt` 是否只在静图链路生效。

### 32. r413：不再动 BasicTone 表，改回"封面 HDR vs 视频 HDR10"这条线

**为什么放弃换表**：§31 已经证明换表只改亮度。进一步核实：

| 项 | 大师 20:51 | POP 20:55 |
|---|---|---|
| 封面 JPEG 增益图 `hdrgm`/`GainMap` | 有 | 有 |
| 视频 pix_fmt | `yuv420p10le` | `yuv420p10le` |
| 视频 colorspace / primaries | 9 (BT.2020) | 9 (BT.2020) |
| 视频 transfer | 18 (SMPTE 2084 / PQ) | 18 (PQ) |
| `ch.c` P010 转换器 `uColorMode` | 0 | 0 |

即：两张封面都带 Ultra HDR 增益图，两段视频都是 BT.2020+PQ 的 HDR10，转换器与着色器
（`rgba2p010_layout_yuv_frag`）完全一致。**"视频更鲜艳" 不是编码侧或着色器选错**，
而是「同一个 look 在静图链路与 VEP 链路上落点不同」——POP 没有 look，两边都中性，所以看不出差异；
大师有 look，两条链路的差异才显形。

**r413 改动**（`LivePhotoSdrCover`）：把封面 SDR 化的适用范围从 `common`（照片）扩到
`professional`（大师）。

- 依据：该类的既有结论是「相册用增益图渲染封面，而 MotionPhoto 附件走另一条路」，
  照片模式早已用这招消除同类不一致；大师此前被排除在外，正是用户报症状的模式。
- XPAN / high-pixel 仍排除：前者清标志会让预览变灰，后者会让延迟任务收不到完成通知。
- 新增诊断：遇到非 SDR 模式打 `capture tag mode=…`，模式在集合内但标志不满足打
  `capture tag mode=… live=… ultraHdr=…`，用于确认大师上报的 `mCaptureMode` 到底是什么字符串。

**验证判据**：大师实况拍完后，日志应出现
`cover SDR mode=professional … ultraHdr=true -> false`。若只出现
`capture tag mode=master …`（或其它字符串），说明模式名与我假设的 `professional` 不同，
把该字符串加进 `SDR_MODES` 即可。

**构建**：`work/apk_build_r413.log`（Muse 4.4.5-r413 / 853）。**设备在安装时再次掉线，尚未安装。**

**顺带记住的验收技巧**：实况视频是延迟数分钟才追加进 JPEG 的，想立刻看结果就**再拍一张**
或切一次模式，会把这个延迟任务顶出来。
