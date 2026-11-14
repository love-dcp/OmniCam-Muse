# 调色盘在横屏下乱跳（PKJ110 / 相机 7.006）

> 定案于 2026-11-14，r341。涉及 `MasterPalette`（我们挂的调色盘），不涉及相册里的
> `GalleryColorPalette`。

## 现象

横屏时：

- 照片、人像模式：位置正常。
- 大师、POP、视频、哈苏超清：调色盘飘到画面中间，位置每次都不一样。
- 转回竖屏之后还是错的，只有切换一次模式才恢复正常。

## 三态

相机对横屏的处理是两套：

| 页面 | 横屏时 |
|---|---|
| 照片、人像 | **只有预览画面旋转**，UI 保持竖屏布局 |
| 大师、POP、视频、哈苏超清 | **整棵 view 树旋转 90°** |

`paldump` 的树快照能直接看到第二种：

```
TREE master-palette decor=2376x1080 views=35
  camera_root RootRelativeLayout V[2376,0 1080x2376]
  camera ScreenRelativeLayout      V[2376,0 1080x2376]
```

decor 是 2376×1080（横屏），相机根布局仍是 1080×2376（竖屏尺寸），被平移到
x=2376 并绕左上角转 90° —— 竖屏的"底部"于是成了屏幕的左边。

## 为什么我们的面板会跑

调色盘是 `decor.addView` 的，**不在那棵旋转树里**，用的还是竖屏锚点：

- 大师（lifted）：`dialTop = screenTop(controlPanel) + 117dp`。竖屏时 control panel
  在底部（top≈1485），117dp 之后正好落在快门上方；横屏时相机把 control panel
  摆到窗口顶部（`screenTop` 读到 0），于是 `dialTop=351` —— 画面正中。
- POP / 视频（docked）：`dialTop = decor.height - dial - 16dp`，横屏下算出来是屏幕
  底部，而那一带在旋转后的布局里正是取景画面。
- 旋转之后没有任何东西重跑布局（tick 里只在 `!paletteUp` 时更新入口按钮），
  所以转回竖屏还是横屏时的坐标，直到切换模式触发一次 `open()`。

## 修法（r341）

1. **横屏另给一套位置**：dial 停到预览卡左侧的空带、垂直居中，距窗口左边
   84dp。这个数不是猜的 —— 照片模式自己的调色盘横屏时就落在 84dp（`paldump`
   量到 x=253 @3x），而模式栏、POP 的滤镜列、大师的参数列都在别处，所以这条
   竖带在所有四个页面上都是空的。`WIDE_DIAL_LEFT_DP = 84`。
2. **窗口尺寸一变就重排**：tick 每 250ms 比对 `decor` 的宽高，变了就
   `hide() + open()` 重来一次。这同时解决"转回竖屏还是错的" —— 不需要切模式。
3. 哈苏超清的滤镜行横屏下收窄到 dial 宽度、放在 dial 正上方（原来 `MATCH_PARENT`
   会横跨整个 2376 宽的画面）。
4. 诊断：打开时打一行
   `dial at <x>,<y> <w>x<h> v=… shown=… decor=…x… wide=…` 和同样的 `readout at`。
   横屏这类问题以后不用再靠截图猜。

## 竖屏基准（同机实测，横屏改动不能动它）

```
# 竖屏，大师，打开调色盘
dial at 396,1836 288x288 wide=false
readout at 162,120 756x72
```

横屏同一台机器：

```
dial at 252,396 288x288 wide=true      # 84dp = 252px @3x，垂直居中 (1080-288)/2
readout at 810,120 756x72              # 仍是顶部居中
```

## 没动的部分

- GR 面板（`openGrPalette`）不在这条路上：它自己有 ring / strip 两套布局，横屏
  下还没验过。
- 相机自己的"重置"按钮横屏时跟着整树转，位置由相机决定；我们镜像缩略图的那个
  `attachMasterReset` 会随 `open()` 重建，横屏下量到在右上，和缩略图对称。
