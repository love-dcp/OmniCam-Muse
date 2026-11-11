# 相册视频编辑器拒绝「片门全开」视频（格式不支持）

> 结论先行：**相册自己有一条「长边 > 3840 就不给编辑」的门**，片门全开录的是 4096×3072，正好踩线。
> 门的开关是 `TranscodingManager` 上的一个布尔字段（本版本里是 `com.oplus.gallery.videoeditorpage.video.business.input.a.e`），
> 只有检测视频时读它一次。`VideoEditorOver4k` 在每次检测前把它清掉，超 4K 因此走原厂本来就有的「降规格」分支。
> **状态：代码已落地并编译（r234/674），等设备连上做上机验证。**

## 1. 症状与复现

专业视频 → 片门全开录一段 → 相册点开 → 点铅笔 → 编辑器闪一下就退，提示「格式不支持」。
落点：`EditorIntroActivity$onCreate$2$2$2$4` 的 `create_timeline_failed`，Toast 文案是资源
`videoeditor_notif_not_supported`（0x7f142405）。

## 2. 对照实验（都是实机）

| 文件 | 规格 | 能编辑 |
|---|---|---|
| 1080p60 SDR | 1920×1080，L4.1，tier=1 | ✅ |
| 4K60 SDR | 3840×2160（coded 2176），L5.1，tier=1 | ✅ |
| 4:3 720×540（录屏） | 720×540 | ✅ |
| OG 60fps Log | 4096×3072，Main10，H L6.0，HLG | ❌ |
| OG 60fps SDR | 4096×3072，L6.0，tier=1 | ❌ |
| OG 30fps SDR | 4096×3072，**tier=0**，L6.0 | ❌ |
| OG60 SDR，hvcC+VPS+SPS 的 tier 改 Main | 4096×3072，tier=0 | ❌ |
| 4K60 只改容器 tkhd/stsd 标成 4096×3072 | 实际 3840×2160 | ✅ |
| OG30 只改容器标成 3840×2160 | 实际 4096×3072 | ❌ |

由此排除：**画幅比例无关**（4:3 720×540 能编辑）、**帧率无关**（OG30 也不行）、
**tier / profile / HDR 无关**、**容器里的宽高无关**——判定读的是码流里的真实尺寸。

## 3. 决定性日志

```
E TranscodingManager: [detectTranscodeInfo] unsupported over 4K video:
    NvsFileInfo{mSize=3072x4096, mFps=30.001097, mCodecMimeType='video/hevc'}
```

## 4. 反编译还原（相册 17.10.41，18 个 dex）

链路上三个类（名字都被 R8 改成短名，下面的「真实名」来自类内字符串与内部类属性）：

| 短名 | 是谁 | 所在 dex |
|---|---|---|
| `com.oplus.aiunit.vision.zgq` | `NvsFileInfo` 的构造：`H(String)` → `getAVFileInfo` 取尺寸/旋转/帧率/编码 | classes9 |
| `com.oplus.aiunit.vision.c5b0` | `TranscodeInfo` 数据类（`s` = `NvsFileInfo`，`d` = 需降规格，`e`/`f` = 目标规格名/帧率） | classes17 |
| `com.oplus.aiunit.vision.yvc0` | 检测主体，`i(List, b5b0, e5b0, Function0) -> List` = `detectTranscodeInfoList` | classes9 |
| `com.oplus.gallery.videoeditorpage.video.business.input.a` | `TranscodingManager`，字段 `a..h`，`e:Z` 是那道门的开关 | classes11 |

判定的字节码（`yvc0.i`，偏移 0703–076e）：

```
m = max(size.w, size.h)
if (info.d)  { if (m > 3840) transcode.d = true;  accept }        // d = 非纯视频文件，30fps 占位
else {
    if (m < 3840)  accept
    else if (m > 3840 && manager.e)  { log "unsupported over 4K video"; 丢掉这一项 }   // ← 我们踩的分支
    else                             { 走降规格：transcode.e = 目标名("1080p" 或 profile)，accept }
}
```

`manager.e` 在 `yvc0.g(kfe)` 里一次性赋值：

```
a.e = phj.x4.get()  ||  z9b.c(6, "is_camera_support_8k", false)
```

**全 dex 只有两处引用这个字段**：`g()` 里写、`i()` 里读。也就是说它只控制「超 4K 收不收」，摘掉不会牵动别的行为。

## 5. 修法

`app/src/main/java/local/jiege/hook/gallery/VideoEditorOver4k.java`：hook `yvc0.i`，进入前把 `manager.e` 置 false。
之后 4096×3072 走的是 3840 宽 4K 也在走的那条「降规格」分支，源尺寸/帧率/时长都还是真的，时间线几何不受影响。

符号用指纹而不是名字（`assets/symbols.json` 的 gallery 段）：

| id | 指纹 |
|---|---|
| `VideoTranscode` | class，串 `[loadScaleAndSaveBitmap] both uri and filePath are null or empty` |
| `VideoTranscode.limit` | `@VideoTranscode` 里唯一的 `boolean` 字段 |
| `VideoTranscodeDetector` | class，串 `limitTo4k, transformChannel is null` |
| `VideoTranscode.detect` | `@VideoTranscodeDetector` 里 `(List,?,?,Function0) -> List` |

`TranscodingManager` 实例本身**按类型从检测器实例的字段里扫出来**，不依赖字段名 `d`。
`VideoTranscode.limit` 也不用名字 `e`。开关组 `persist.omnicam.aura.skip` 加 `videoedit` 可单独关掉。

## 6. 上机验证（2026-11-11 19:47，r234/674，已通过）

```bash
adb install -r app/build/outputs/apk/release/app-release.apk       # r234/674
adb shell am force-stop com.coloros.gallery3d
adb shell "logcat -G 16M; logcat -c"                              # 相册日志掉得快
# 打开相册 → 那个 OG 视频 → 铅笔
adb shell "logcat -d | grep -a 'LSPosedFramework: (com.coloros.gallery3d)'"
```

实测结果：

| 判据 | 结果 |
|---|---|
| `Symbols: gallery: 36 resolved, 0 missing` | ✅ 4 条 `VideoTranscode*` 全命中 |
| `VideoEditorOver4k: detect pass over 1 item(s), over-4K limit true` | ✅ 门确实开着，说明 hook 打在真方法上 |
| `unsupported over 4K video` 不再出现 | ✅ 全日志零命中 |
| 焦点落到 `EditorActivity` | ✅ 编辑器进得去，不再是 toast 后立即退出 |

**注意**：`detect pass returned N entr(y|ies)` 这条 after 日志没打出来。不影响结论（before 打到了、
`unsupported over 4K` 消失、Activity 起来了），推测是该检测方法本身是 suspend 链、方法体没在抓日志的窗口里返回。
下次要看返回值就直接看编辑器是否起来，不必依赖那行。

**免手动点的直达命令**（跳过网格和播放页，等价于点铅笔）：

```bash
adb shell "am start -n com.coloros.gallery3d/com.oplus.gallery.videoeditorpage.video.app.EditorIntroActivity \
  -a android.intent.action.VIEW -d content://media/external/video/media/<_id> -t video/mp4"
```

`_id` 用 `content query --uri content://media/external/video/media --projection _id:_display_name:width:height` 查。
`am start` 会打一段 `SecurityException` 栈——那是 URI 授权检查的噪声，不影响拉起。

## 7. 还没验证 / 风险

- **回归未做**：1080p60 / 4K60 两段没在 r234 上重测编辑。它们本来就到不了那道门（3840 不大于 3840），
  逻辑上无影响，但严格说起来是未验项。
- 相册升级换版本后，指纹若失配，`VideoEditorOver4k` 只会打一行 `unavailable`，不会影响其它功能。

## 8. 原尺寸导出（r236 / 676）

能编辑之后导出还是 2160×2880。原片 3072×4096，缩放比 2160/3072 = 0.703125 —— **短边被压到 2160**，
也就是「4K 按短边算」。

### 出口不在转码，在导出

先排除了上一节那条转码路径：整个编辑会话里 `TranscodingManager` 一行日志都没有，
`/data/user/0/com.coloros.gallery3d` 下也没有 `limit4K_*` 文件。真正的证据是导出瞬间：

```
I meicam : Compile video with size=2160x2880
```

压缩尺寸是 App 交给 Meicam 的，所以得看 Java 侧谁算的。

### 算尺寸的是 od20（V 表：ResolutionUtil）

导出按钮走 `EditorSaveUIController.N()`：

```java
Size   src    = kfe.B();                      // 编辑器自己的画布尺寸
cn10   ratio  = timeline.getRatioOption();
String key    = timeline.getNodeResolution(); // "4K" / "2K" / "1080p" / "720p" / "480p"
Size   out    = od20.e(timeline, src, ratio, key);
```

`od20.e` 先从时间线取唯一片段的真实尺寸（`htc0.h/i`），再转手调
`od20.d(src, ratio, key, true, clipSize)`。`od20.d` 的逻辑：

```
cap   = od20.b(key)      // "4K"→3840x2160, "2K"→2560x1440, "1080p"→1920x1080 ...
size  = od20.f(aspect, cap)
        // aspect≤1 先把 cap 转成 (min,max)，再用 it50.c 按 aspect 重算：
        //   w2 = cap.w/aspect, h2 = cap.h*aspect;
        //   w2<=cap.h ? (cap.w, w2) : (h2, cap.h)
        // 3:4 代入 cap=(2160,3840)：w2=2880、h2=2880，取 (2160, 2880) ← 就是这个数
```

### OPPO 自己留了「片门全开不缩放」

`od20.a(srcSize, z, key, opengateSupport)` 里写着：

```
opengateSupport && z && key=="4K" && src!=null && 3840 < max(w,h) <= 4096  →  true
```

为真时 `d` 直接返回 `sizeRef`（或调用方传进来的 target），不套 4K cap。
`opengateSupport` 来自 `z9b.c(4, "feature_is_support_camera_opengate", false)`，
调用方传的 `z` 也常年是 false —— 两道门都关着，所以永远走不到。

### 修法

`VideoEditorOver4k` 加第二个钩子，挂在 `od20.d` 的 after：把入参里**所有 Size** 扫一遍，
取长边落在 (3840, 4096] 的那个（`e` 分支里那是片段的真实尺寸，输入分支里是源尺寸），
在 `key` 以 `4K` 开头且与返回值不同的时候把返回值换成它。

`symbols.json` / `sym_gallery.py` 各加一条 `VideoResolution.target`：`static`、
参数 `(Size, ?, String, boolean, Size)`、返回 `Size`、方法内串 `feature_is_support_camera_opengate`。
**不给 `in`**（全 dex 搜）：该串在 classes18 只出现在 `od20.d`、在 classes2 出现在两个签名完全不同的方法里，
靠「参数表 + 方法内串」唯一命中，比用 `ResolutionUtil` 当类指纹稳（那个串跨 3 个 dex）。

实机验证（r236）：打开 OG 片段即出现

```
resolution 2160x2880 from 1080x1440/3072x4096 key=4K
open-gate clip keeps its size: 2160x2880 -> 3072x4096
```

`1080x1440` 是编辑器预览画布（3:4 的 1080 档），`3072x4096` 是片段真实尺寸。
改完之后**导出成片尺寸待确认**（设备在验证时锁屏了）。

### 仍未做

- 导出成片是否真是 3072×4096、以及编码耗时/码率是否正常 —— 待一次完整导出。
- 编辑器预览画布仍是 1080x1440（那是另一档，预览不跟着放大不影响成片）。

