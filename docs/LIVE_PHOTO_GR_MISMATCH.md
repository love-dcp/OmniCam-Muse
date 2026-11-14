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
