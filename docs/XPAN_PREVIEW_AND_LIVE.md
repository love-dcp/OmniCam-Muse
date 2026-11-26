# XPAN 预览灰 与 实况封面全白（7.006.317/352）

## 1. 预览变灰：根因与修复（r565/r566）

`gl.s`（123 的 XPAN 预览画布）在 317/352 被重构：只剩 18 个方法、11 个字段，
**没有 `o0` 绘制入口，也没有 `r`/`Z`/`e0` 三个 LUT setter**。
模块的 `panelDraw == null` 分支只打一行日志就降级，于是：

- `reuploadPreviewBasicToneIfWashed`、`ensureXpanPreviewTables` 全部挂在 `o0` 上 → 从未运行
- BasicTone 表被污染后没有任何自愈路径 → 预览一直灰

后继类是 `com.oplus.camera.common.gl.n`（87 方法 / 80 字段），**字段名 n/o/u 原样保留**，
只是入口改了名：

| 语义 | 123 `gl.s` | 317/352 `gl.n` |
|---|---|---|
| field o（XPAN BasicTone） | `r([F,I,Z)`，数据在 `t0.A` | `w([F,I,Z)`，数据在 `r0.z` |
| field n | `Z([F,I,Z)` | `e0([F,I,Z)` |
| map u（17³ 槽位） | `e0([F,I,I)` | `j0([F,I,I)` |

r565 新增 `installModernCanvasLayer` + `repairModernCanvasIfWashed`，挂在
`GLES20.glDrawArrays` 计数上（每 120 次 ≈ 40 帧），三层保护：clean 表存在才推、
流在动不打扰、1.5s 限速。

实测日志：

```
XPAN gl.s canvas layer unavailable on this build (renderer refactored; installing the gl.n layer instead)
XPAN gl.n canvas layer armed, basicTone=true, slot=true, fieldN=true
XPAN gl.n canvas captured, size=64, enabled=true, floats=786432
```

## 2. 实况封面全白：已定位，未修

### 现象
XPAN + 实况拍出的 JPEG 是 **1600x3872、全白**（只有 LUMO 水印正常），
luma mean 0.998 / sat 0。关掉实况开关后同一场景正常（1600x4608、luma 0.71、std 0.20）。

### 已排除（逐项实测，全都不是原因）
| 变量 | 测法 | 结果 |
|---|---|---|
| +2EV 补偿 | `persist.omnicam.xpan.liveev=0` | 仍白（EXIF 1/25s ISO400，不是过曝）|
| native 转换 bypass | `noconv=0` | 仍白 |
| commonDraw 程序桥接 | `commonbridge=0`（r566 新增开关）| 仍白 |
| EIS bypass | `eisbypass=0` | 仍白 |
| 帧路径重定向 | `skipz2=0` | 仍白 |
| 超清优先（highPixel）| 设置→格式→标准 | 仍白 |
| 317 vs 352 | 装回 `work/camera317.apk` | 两版均白 |
| 是否 XPAN 专属 | 人像模式实况 | 正常（3244x5132）|

### 真正的断点
`XPAN frame sink, image=..., l=0` —— **video handle 恒为 0（null）**，
r518 的注释已经记过这件事（"frames keep flowing into LivePhotoSession.frameSink
with a null video handle"）。runtime 里 `live chain` trace 只打出
`eis e[] mode=xpan args=0` 两条，说明 317/352 的实况视频链在进入编码器前就断了：
相机拿不到有效视频轨 → 封面取帧（`coverFrameNum: 861`，远超实际 91 帧）失败 → 返回白帧。

结论：**不是模块开关能解决的，需要重做 317/352 的实况视频链路接入。**

> ⚠️ 本节结论已被 §4 推翻（2026-11-26 15:20 实测）：`l=0` 不是断点，
> 白封面的真因是 **XPAN 水印开启**。保存本节只作为排除过程的记录。

## 3. 实况视频亮度（可调参数）

`persist.omnicam.xpan.videolook.mid` 作用于表输出域：
`out = in + e*in*(1-in)`，正数提中灰、负数压中灰。旧值 **-1.1** 是在
「水印支路 2212x912、视频无显影」的坏管线上调出来的，配合 r567 修好的
显影通路会把视频压得比封面暗一档。现在的实测取值见 §4。

## 4. r567 定案：白封面 = XPAN 水印开启（2026-11-26 15:20）

三个变量在日志里是一一对应的（`XPAN main frame path`）：

| 水印状态 | `crop` / `pipelineAB` | 输出 JPEG | 结果 |
|---|---|---|---|
| 开 | 1920x864 / **2212x912** | 3072x7440 或 1600x3872（2.42:1）| **全白**（照片层空，只剩水印）|
| 关 | **1920x710** / 1920x710 | 2912x7872 或 1516x4096（65:24）| 正常 |

即：水印一开，XPAN 的帧管线就切到 2212x912 的 A/B 支路，JPEG 按 2.42:1
出图，照片层拿不到像素 → 白。**跟 317/352 无关**（两版同一个表现），
也跟 EV/EIS/commonDraw 无关（那些都已逐项排除）。

触发这次回归的是 `XpanWatermarkIsolation`：它进 XPAN 时把保存的
`styleId/open_state` 写回，于是水印永远是「开」。当前设备用
`persist.omnicam.aura.skip=xpanwm`（ModuleEntry 有该分组判断）跳过整套隔离，
水印状态就跟着相机自己的开关走。

### 现在这套可用参数（实测）

```
persist.omnicam.xpan.liveev        660   # = +2EV，封面 EXIF 2.0；0 会暗 2 档
persist.omnicam.xpan.commonbridge  1     # 视频显影唯一通路，关掉视频必灰
persist.omnicam.xpan.videolook.mid -1.1  # 配合上面两条，视频色调贴封面
persist.omnicam.xpan.eisbypass     1     # 0 会让 1920x864 被 EIS 拒收（code 152）→ 没有视频
persist.omnicam.aura.skip          xpanwm
```

实测（`IMG20261126151732`，同一场景）：

| | 封面 | 视频首帧 |
|---|---|---|
| 均值 | 96/92/82 | 87/97/101 |
| 饱和度 S | 121 | 122 |
| 灰度 p1/p50 | 17/100 | 8/111 |

对比 15:10 那版（liveev=0 / commonbridge=0）：封面 36/30/23、视频 S=52 —— 又暗又灰。

## 4b. r570–r572：为什么「XPAN + 水印」修不好（2026-11-26 20:20 定案）

r567 只是把水印槽位关掉绕过白封面。这一轮去查了「能不能让它真能用」，
结论是**这台机器上相机自带的 XPAN 水印链路是断的**，三条路都试过：

| 尝试 | 做法 | 结果 |
|---|---|---|
| ① 钳画布 | hook `MasterWatermarkCreator.calculateFinalSize(Size,JSONObject)` 在 XPAN 里原样返回帧尺寸 | 画布不再外扩（`canvas kept at 1920x864`），**成片仍全白** |
| ② 改样式 JSON | 把 `lumo_style_2.json` 的 `isXpanImage` 全改 true、四边 margin 归零 | `isXpanImage: true` 生效，**XPAN 仍白，且普通照片模式也白**（已回滚，勿再试） |
| ③ HAL 加高条 | hook `XPanPresenter.onPrepareCaptureRequest`，把 `CameraRequestTag.n1` 设成 400 | `cropRegion:[360,64,2912,8272]`、参数 `xpan_hswatermark_height, 400` 都到位，**成片仍 2912x7872、预览也无黑条** —— HAL 不渲染 |

真因（日志铁证，`IMG20261126202215`）：

```
watermarkOpen=false -> crop 1920x710 -> 2912x7872   正常
watermarkOpen=true  -> crop 1920x864 -> 1504x3328   全 255
APS_CORE: camApsProcessImages() watermark size not valid!
IPU_WM: processBitmapSize bitmap 864x266, pictureSize 864x1920, isXpanImage:false
```

1. 水印一开，XPAN **不再做 65:24 裁剪**（`crop` 从 1920x710 变 1920x864，
   即整帧 20:9）——所以那不是「裁剪算错」，而是根本没裁。
2. 水印层仍按 4:3 边框样式排版（`lumo_style_2`，`size` margin 10/10/10/111dp），
   合成器拿到的画布与 20:9 帧不匹配，APS 直接判 `watermark size not valid`，
   返回空图 → 全白。
3. 本机 `files/photo_watermark/` 只有 `lumo_style_2`，`mode_limit_watermark/`
   只有 GR 五款；X9U 上 XPAN 用的是云端下发的 `hassel_style_xpan_*`
   （`isXpanImage:true` 的专用排版），这台机器的机型目录里没有，也不会下发。

所以：**XPAN 里水印必须保持关闭**（`XpanWatermarkIsolation.installForceClosed`，
r567 起默认；逃生 `persist.omnicam.xpan.wmallow=1` 只用于实验，会白）。
`XpanHsWatermark`（hook `n1`，`persist.omnicam.xpan.hswm`，默认 0）作为
HAL 加高条的杠杆留着：参数链是通的，换到渲染支持的构建/机型即可生效。


## 5. 复古滤镜 + 实况：视频帧纯色 —— 已定案（r569）

现象：XPAN 选「复古」(fuji-nc) 拍实况，封面正常，播放画面是一整片纯色
（`IMG20261126152917` 首帧 RGB 均值 10/66/83，第二张 15/83/56，
关掉模块表合成后 93/183/93），帧里没有任何画面内容。

### 真因：`skipz2=0` 时走了原厂 Z2 → `dh.e.b` 转换器

日志铁证（每帧一条，只在带滤镜时出现）：

```
E/OCAM_GLESWrapper: GL error: 1282
    at ka.r1.c(GLESWrapper.java:35)          ← checkGlError
    at dh.e.b(Rgba2YuvConversion.java:617)   ← 就是 glDrawArrays 之后那次检查
    at hh.p1.c3(LivePhotoPresenter.java:801)
    at ... mn.g1.s → PreviewRenderer.onDrawFrame
```

- 用 `positions` 表把 `Rgba2YuvConversion.java:617` 映射到字节码偏移 `0x0268`，
  该点紧跟在 `0x025c glDrawArrays(GL_TRIANGLE_STRIP,0,4)` 之后 → **失败的是这次 draw**。
- 对照：不带滤镜的日志（`xpan_cap4.log`，15:20 那批）**一条 1282 都没有**。
- 该 GL 错误只在实况会话把帧交给 `dh.e.b` 时出现；模块默认（`skipz2=1`）帧走
  `X2`/`b3` 分发，根本不进这条 GL 路径，所以默认配置下不会有 1282。

### 不是 317→352 的回归

`Rgba2YuvConversion`（`Ldh/e;`）在两个包里**逐字段、逐方法完全相同**：
25 个实例字段同名同类型，方法签名全同（连 `b:(FFIZZ[FZ)J` 这个 7 参形态都一样），
`ka.r1` / `dh.a` 错误上报器也一致。所以 352 并没有"改坏"实况转换器；
真正的原因是这台设备上 `persist.omnicam.xpan.skipz2` / `.noconv` 在做
"全关对照"时被写成 0 之后没有恢复，而 r498 那套能用的配置里它们是默认值 1。

### r569 的守卫

带滤镜时**无条件**跳过原厂转换器（不再看 `noconv`），并且**无条件**走 Z2 重定向
（不再看 `skipz2`）；不带滤镜时属性照旧生效，调试不受影响。
滤镜依旧通过模块的 task.d 合成进入视频域（日志 `XPAN video look composed,
filter=fuji-nc.bin, cube=4913`），所以强制跳过关不掉滤镜效果。

### 实测（r569，352 相机，复古滤镜 + 实况）

| 样张 | 开关 | 封面 | 视频轨 |
|---|---|---|---|
| `IMG20261126164723` | noconv=1 skipz2=1 | 2912x7872，99/91/75，S130 | 1920x710，103/97/80，S128，grayStd 44 |
| `IMG20261126164535` | noconv=0 skipz2=1 | 1516x4096，99/94/81，S123 | 1920x710，100/94/80，S128，grayStd 45 |

两拍视频都有真实画面且带复古色。顺带观察：`noconv=0`（原厂转换器路径）
出图会退到半幅 1516x4096，`noconv=1` 才是 2912x7872 —— 默认值别动。

