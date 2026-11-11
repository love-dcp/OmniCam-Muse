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
