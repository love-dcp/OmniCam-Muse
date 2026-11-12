# XPAN 实况照片（XpanLiveFeature）与顶部栏触摸归属

`app/src/main/java/local/jiege/hook/xpan/XpanLiveFeature.java`

XPAN 的小特性表里没有 `com.oplus.camera.feature.live_photo`，FeatureManager 因此不建
`LivePhotoPresenter`，按钮和拍摄链都不存在。本 hook 补回特性表、放行预览请求、补
`CameraRequestTag.t1`、做 `+2EV` 曝光补正、陀螺兜底，并在左上菜单栏里补建实况项。
完整链路见 `docs/CODEX_CONTEXT_HANDOFF.md` 的 r187–r217 段。

## 1. 2026-11-11 修复：顶部栏第二个图标点了没反应

### 现象

XPAN 模式下，左上角实况按钮右侧那个**相机图标**（`xpan_switch_to_physical_ui`，点它应该在
「标准界面 / 物理界面」之间切换取景框样式）按下去毫无反应；同一排的实况按钮和右侧齿轮设置都正常。
关掉本模块的 XPAN 注入（`setprop persist.omnicam.xpan.skip` 系列 → 实际用
`persist.omnicam.aura.skip xpanfeature`）后，该图标**立刻恢复正常**，说明是自己弄坏的。

### 根因：实况项把原厂图标挤进了曝光轮的地盘

`dumpsys activity top` 把视图树按父偏移累加成绝对坐标后，两个矩形的关系一目了然：

| View | 绝对矩形 | 说明 |
| --- | --- | --- |
| `CameraMenuContainerTopLeft`（`oplus_setting_bar_left`） | (42,162)-(234,258) | 顶部左栏，宽 2 个槽位 |
| `live_photo`（我们补建的实况项） | (42,162)-(138,258) | 槽 0 |
| `xpan_switch_to_physical_ui`（原厂相机图标） | (138,162)-(234,258) | 槽 1 |
| `XpanExposureContainer` / `XPanExposureWheel` | (145,120)-(934,438) | 曝光轮，**盖住顶部栏右半段** |
| `oplus_setting_bar_right`（齿轮） | (942,162)-(1038,258) | 曝光轮右边界之外 |

原厂之所以一直没事，是因为**原厂左栏只有一个图标，占 42..138，正好落在曝光轮左边界 145 的左边**；
齿轮在 934 右边。也就是说原厂布局是「贴着曝光轮左右两侧之外的缝里」摆图标的，从不需要和轮子抢触摸。

我们补建实况项后左栏变成 2 个槽位（宽度 96→192），原厂相机图标被顶到 138..234，
而 `XPanExposureWheel.onTouchEvent` 在自身矩形内**无条件 `return true`**，于是点击被轮子吞掉，
图标看着可见可点、实际收不到事件。

验证方式：在 x=186（轮子内）点击 → 界面无任何变化；在 x=141（轮子左边界外 6px）点击 →
立刻触发实况按钮（说明分界正好在 145）。

### 修法：把「只转发给实况按钮」改成「转发给顶部栏里被压住的那一项」

r217 那版写死了 `LIVE_PHOTO_VIEW_ID = live_photo` 作为转发目标，只能救实况自己。
现在 `hookExposureWheelTouchYield` 在 `ACTION_DOWN` 时改为：

1. 用资源 id 找到 `oplus_setting_bar_left` / `oplus_setting_bar_right` 两栏；
2. 在栏内做**从后往前的深度优先**命中测试（后绘制的在上面，就是本该收到点击的那个），
   取最近一个 `VISIBLE + enabled + clickable` 的 `RotationOptionItemView`；
3. 找到就把整段手势按两 View 的屏幕原点差修正坐标后转发过去（`dispatchGestureTo`），
   并 `setResult(TRUE)` 让曝光轮不再吃这一段；
4. 找不到（点的是刻度区本身）就照旧交给曝光轮。

实况项保留为 fallback：它还没被挂进栏的那一小段窗口里仍能命中。

### 实测（PKJ110，r232 代码 + 本修复）

```
XPAN wheel gesture forwarded to top strip item, x=192, y=192,
wheel=Rect(145, 120 - 934, 438),
target=com.oplus.camera.ui.view.RotationOptionItemView Rect(141, 138 - 238, 235),
visible=true, clickable=true, consumed=true
XPAN wheel top strip gesture completed, consumed=true, endedInside=true
```

- 物理界面 → 标准界面、标准界面 → 物理界面，两个方向都点得动；
- 实况按钮照常开关（`XPAN live photo click after ... current=on/off`）；
- 在刻度上横向拖动，曝光指针正常跟随（无回归）；
- 关掉注入时的原厂行为不变。

> 教训：顶部栏里**任何**新增图标都可能落进曝光轮矩形。以后往左栏加东西，先按上表比对坐标，
> 或者直接依赖这套转发（它对整栏通用，不认具体 id）。

## 2. 构建时的坑：`work/apk_only.ps1` 曾把项目路径写死成 `G:\`

该脚本（Codex 期留下）第一行是 `$project = 'G:\OmniCam-Muse-main'`。项目已经搬到 `E:`，
但 `G:` 是一块真实数据盘、上面还留着一份 **r163 / 606 的旧副本**，于是「编译成功」的其实是旧副本，
产物时间戳不变、装回去的仍是老 APK（表现为改动完全不生效、日志里版本号还是旧的）。
现在改成 `$project = Split-Path -Parent $PSScriptRoot`，跟着脚本自身位置走。
判断有没有踩这个坑：构建日志末尾的 `APK ready: ... (versionName / versionCode)` 应与
`module/module.prop` 一致；不一致就是编译了别的树。

## 3. 2026-11-11 修复：开「水印」后 XPAN 实况的动态附件又变回 Log 灰（r240 真机通过）

### 现象

- 只拍实况（水印关）：动态附件颜色正常（r201–r210 那套 BasicTone 补丁生效）。
- 实况 + 照片水印：封面正常，**动态附件整幅发灰**、黑位抬起（背景由黑变浅灰）、色彩发闷。
- 实测同一场景：封面 HSV 平均饱和度 `95.2`，附件 `20.7`（与 r199 的 Log 灰一致）。

日志（`work/xpan_wm_full.log`，21:46 那次拍摄）关键三行：

```
OCAM_LivePhoto.VideoEncoder[..][PAUSING]: onPrepare, mCropPreviewSize: 1920x864, mbIsWatermarkOpen: true, ...
OCAM_LivePhoto.VideoEncoder[..][STARTED]: initGLContext, sharedContext: ...
OCAM_WatermarkProcessor: WatermarkProcessor create.
XPAN main frame path, seq=1, path=common, ... watermarkOpen=true, ... lutType=1, rendererPrepared=false
```

`mbIsWatermarkOpen=true` 让编码器在 `p3` 之外**多建一个水印合成器**（`gh.s4` → 渲染器 `ch.h`）。

### 根因：水印那一遍画的是**原始未显影画面**，而且 `LutParameters` 被写死成 null

先说结论：不是「LUT 套了两遍」，而是「第二遍重画时**根本没做 BasicTone**」。

反编译证据（`work/p3_r_full.txt` / `work/s4_b.txt` / `work/chh_p.txt`）：

1. `gh.p3.r(ImageData)` 先把这一帧画进 HW buffer（本模块的 BasicTone 桥就挂在这里，见 r208/r210）。
2. 随后 `if (image_is_watermark_open)` → `p3.P0(gh.s4)->b(u0 frame, ImageData)`（`p3_r` 第 663 行）。
   注意传进去的 `u0` 是 **p3 的输入纹理**，不是 p3 画完的结果。
3. `gh.s4.g(u0, Z, Z)` 把这个输入纹理 `f()` 拷到自己的目标 `s4.v`，再交给渲染器 `ch.h`：
   `s4.j`（`Lch/h;`）就是水印合成器。
4. `s4.b` 调 `ch.h.p(ArrayList, [F, [F, [F, I, I, I, Lbh/o, Lm9/c)` 时，
   `LutParameters` 参数是**写死的 null**（`const/16 v18, 0`）。`ch.h.p` 第 85 行：
   ```java
   if (lutParameters != null && lutParameters.g) { uNeedBasicTone = 1; ... }
   else                                          { uNeedBasicTone = 0; }
   ```
   → 合成这一遍 `uNeedBasicTone = 0`，BasicTone 与影调表全部不做。
5. 于是输出 = **原始（未显影）画面 + 水印**。封面走 APS/HAL 不经过这条链，所以封面一直是好的。

一句话：**第一遍已经显影了，但水印那遍把显影前的原料重画了一遍。**

> 反汇编里 `ch.h.p` 的 `if (a0.q) uNeedLut=1` 只能决定有没有 look LUT，
> 补不回 BasicTone。实测 `ch.h` 的 `q` 为 true（见下方日志 `rendererLut=true`），
> 所以 r239 那版「关掉 q」不但没修好，还原样保留了 Log 灰。

### 修法：把主绘制这一帧的 `LutParameters` 交给水印合成器

`XpanLiveFeature.hookXpanLiveWatermarkCopy()`：

1. `p3.r` 入口处（`beginMainFrame` 之后）把 `pipeline.o0`（本帧真正使用的 `LutParameters`）记到
   `activeLutParameters`。
2. hook `gh.s4.b`，在 XPAN、实况、`image_is_watermark_open=true` 时打开一个线程内的
   「合成窗口」标记，方法返回即清除。
3. 在这个窗口内 hook `ch.h.p`，把第 8 个参数（`LutParameters`，原厂传 null）
   替换成 `activeLutParameters`。合成那遍于是自己把 BasicTone、影调表、look LUT 全走一遍，
   结果与「水印关」那条已知正常的链路一致。

- 只作用于 XPAN + 实况 + 水印开的这一帧；其它模式、普通视频水印、封面拍照都不受影响。
- 参数替换走 `param.args[7]`，原厂对象不被修改；窗口结束即失效，不会污染后续帧。
- `persist.omnicam.xpan.livewm=0` 可退回原厂行为（排查用）。
- 新增日志：
  - `XPAN live watermark composite armed, renderer=ch.h, rendererLut=..., gradedParams=...`
  - `XPAN watermark composite regraded with the frame's LutParameters, stock=null, paramsG=true, ...`

### 陷阱：`LutParameters.e` 是空的，强行 `uNeedBasicTone=1` 会画出纯白

r241 第一版直接把参数塞过去，结果**成片开头约 0.9 秒纯白**（26 帧，HSV `V=190 / S=3 / std=10.7`），之后才正常。
探针（`XPAN watermark composite #N`）给出答案：

```
composite #1, stock=null, basicTexture=null, g=true, lutSize=64.0, avgLuma=0.0
```

`g=true` 让 `ch.h.p` 置 `uNeedBasicTone=1`，但它随后执行 `this.s = params.e` 再 `a0.b(slot, shader)`；
XPAN 的 `LutParameters.e`（BasicTone 三维贴图）**本来就是 null**——主绘制用的是渲染器字段
`gh.g.s`，由本模块 `ensureXpanLiveBasicToneTexture()` 现场建好。贴图槽绑了空对象，
着色器就采到默认值 → 整幅纯白。

所以合成前必须先解决贴图来源，顺序为：

1. `params.e` 非空 → 直接用；
2. 否则借 `pipeline.w.s`（主绘制刚建好的那张 BasicTone 纹理）；
3. 两者都没有 → **不替换参数**，退回原厂（宁可这一帧 Log，也不出白帧）。

借来的贴图是临时写进 `params.e` 的，`ch.h.p` 返回后立刻置回 null，避免影响下一帧主绘制。
实测 r242：`#6 basicTexture=borrowed`，随后 `#7+` 变成 `own`（原厂也补上了），全片无白帧。

### 验证要点

1. XPAN、开实况、开水印，拍一段有运动、且**画面里有大片纯黑**的场景（黑位最容易看出来）。
2. 日志应出现上面两条（`stock=null`、`paramsG=true`）；`XPAN main frame path` 里 `watermarkOpen=true` 照旧。
3. 抽出附件逐帧看：**第 0 帧就该有颜色**（r242 实测 `S 44.8 / std 62.2`，此前白帧段为 `S 3 / std 10.7`）。
4. 回归：水印关的实况、人像/大师的普通实况、普通视频水印。
5. 对照手段：`setprop persist.omnicam.xpan.livewm 0` 后重拍，应回到 Log 灰（`S ≈ 21`）。
   注意附件是**延迟落盘**的（先写 1.8MB 静图，约 1 分钟后才追加视频），别看到小文件就以为没合成。

