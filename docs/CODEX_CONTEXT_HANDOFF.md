# Codex 会话交接（2026-11-08）

工作目录：`E:\OmniCam-Muse-main`

## 2026-11-10：r229 人像首次进入默认 1×（用户已确认）

r228 只覆写 `com.oplus.portrait.zoom.value.default`，日志中该配置值已经从 `3.0` 变为
`1.0`，但相机随后仍由默认倍率或保存倍率分支覆盖，界面没有稳定停在 1×。因此配置键
覆写只能作为辅助，不能单独解决该问题。

r229 增加一次性变焦提交修正：

1. 在 `fm.o1.onStart()` 后登记一次有效期 5 秒的待处理状态；
2. 钩住 `il.h0.y4(float, boolean)`，仅在当前模式字段为 `portrait` 时处理；
3. 将本次首次变焦提交改为 `1.0`，并用原子操作立即消费状态；
4. 状态消费后不再修改倍率，避免用户手动选择 `3×` 时被拉回 1×。

相关代码：

```text
app/src/main/java/local/jiege/hook/photozoom/PortraitDefaultZoom.java
app/src/main/java/local/jiege/hook/ModuleEntry.java
```

r229 构建和静态校验通过：

- APK 包内版本：`Muse 4.4.4-r229`，versionCode `669`；
- DEX 中三条 `PortraitDefaultZoom` 探针均存在；
- APK Signature Scheme v3 验证通过；
- KSU 包内外 APK 均为 `5,267,187` 字节，SHA-256 完全一致；
- `tools/verify_port_artifacts.py` 六组检查全部通过。

测试包：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r229.apk
SHA-256 1D966258398028CD950375199C4DB5B15B6C16B67C2A62062A279097CF09E6AD

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r229-KSU.zip
303,732,907 字节
SHA-256 7CC908768AEEA6580EF5DDC324FF1B0C0F24E642AA84E69CCC248D5296AD2892
```

用户已在 r229 实机确认：进入人像模式时默认显示 `1×`。本记录不额外声称尚未单独复测的
冷启动、模式往返和手动选择 3× 后保持等边界场景；如后续发现回归，应分别记录后再扩大结论。

## 2026-11-10：r226 修复哈苏超清 5000 万像素实况拍完卡住（已实机验证）

用户确认该卡顿是上一版引入的回归。r225 为哈苏超清实况增加了按拍摄周期管理
P010 的钩子，目标是避免 P010 一直保持开启后，延迟 JPEG 与实况 Session 互相等待。
但 r225 把原厂 `b9.p.accept()` 的实况视频完成分支误判为 case 16；反编译安装包后确认，
真正执行 `onVideoCaptureCompleted`、移除两个 CaptureHandle 集合并关闭 Session 的分支是
case 8。case 16 没有进入该收尾路径，因此钩子从未在视频完成后释放 P010。

r226 只把完成分支改为 case 8，并保留以下条件：

1. 只在 `highPixel` 的实况 Session 中处理；
2. 原厂 `accept()` 已从 `c` 和 `i` 两个 CaptureHandle 集合移除当前 handle；
3. 两个集合都为空后才释放 P010，避免连拍时提前清除；
4. Session 关闭仍保留兜底释放。

r223 卡顿日志的时序如下：

```text
10:52:43.244  OCAM_LivePhoto-CapturedVideo: complete
10:52:43.246  onVideoCaptureCompleted, active=1, pending=0
10:53:26.137  Session is closed
10:53:27.446  notifyImageProcessed
```

视频完成到 Session 关闭约 42.9 秒，到图片处理完成约 44.2 秒。关闭相关钩子的 r224
对照没有这段长等待，和“上一版改坏”的现象一致。

r226 已完整构建并通过：

- APK 包内版本：`Muse 4.4.4-r226`，versionCode `666`；
- 最终 DEX 中 `VIDEO_CAPTURE_COMPLETED_CASE` 的值为 8，释放函数与探针均比较 8；
- APK Signature Scheme v3 验证通过；
- `verify_port_artifacts.py` 六组检查全部通过；
- KSU 包内外 APK 均为 `5,267,187` 字节，SHA-256 完全一致。

测试包：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r226.apk
SHA-256 E31F8702E7E7817BB5BBE6549362636D08B8D842B0EA39B7A4BBEB8D5B7FC8CC

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r226-KSU.zip
303,732,547 字节
SHA-256 A029AE922A2C82FF24FC80C694A1EEC8932FA139A622B819AE645659E876C4E5
```

手机恢复 ADB 后已完成 r226 热安装，没有重启手机。设置应用、KernelSU 模块和实际加载的
`/data/adb/modules/ricoh_pop_x9u` 均为 `Muse 4.4.4-r226 / 666`。测试时进入哈苏超清并开启
实况，界面从“5000 万像素”切换为“4K 实况照片”，符合该模式的实况状态。

第一张只点屏幕中心快门，拍完原地等待 12 秒。关键日志如下：

```text
13:29:07.603  LivePhoto capture, mode=highPixel
13:29:10.446  OCAM_LivePhoto-CapturedVideo: complete
13:29:10.447  onVideoCaptureCompleted, active=1, pending=0
13:29:10.448  HighPixelLivePhoto: operation flags 0x183c300 -> 0x103c300
              (video capture completed)
```

视频完成到 P010 清除只间隔约 2 毫秒，没有再次出现 r223 的约 43 秒 Session 等待。相机
进程保持为 PID `24484`，前台 Activity 正常，延迟 JPEG 约一秒内生成缩略图。随后在同一个
Session 内连拍第二张：

```text
13:30:01.017  operation flags 0x103c300 -> 0x183c300
              (P010=true, capture stage)
13:30:03.066  OCAM_LivePhoto-CapturedVideo: complete
13:30:03.070  operation flags 0x183c300 -> 0x103c300
              (video capture completed)
```

第二张开拍前重新应用 P010，视频完成后约 1 毫秒再次清除，说明同一 Session 连续拍摄的
生命周期正确。之后已成功进入相册查看照片并重新打开相机，模式栏也能从哈苏超清切到理光 GR，
未再出现拍完整机界面卡住或模式无法切换的问题。

验收结果：

1. case 8 后出现 `video capture completed` 的 P010 释放日志；
2. 释放与 `onVideoCaptureCompleted` 在同一秒内，实际约 1 至 2 毫秒；
3. 延迟 JPEG 和缩略图快速生成，不再等待约 40 秒；
4. 相册、返回相机、切换模式均不卡住；
5. 同一 Session 连拍第二张正常；
6. 哈苏超清本次实况成片可见、方向正常。

测试期间切换人像和普通视频时，用户一度观察到颜色异常，数秒后自行恢复。该现象没有伴随
r226 的 P010 钩子日志，只发生在模式切换后的短暂阶段；当前未修改人像或普通视频的颜色管线。
如果再次稳定复现，应单独记录切换前后的预览和成片，不把哈苏超清生命周期修复与普通模式
颜色问题混在同一次修改中。

如果 case 8 已命中但仍未释放，下一步只补 case 8 的前后集合状态诊断，不以延长等待时间
判断是否修复。

## 2026-11-09：r217 修复 XPAN 实况按钮触摸被曝光轮截获（待明早实机验证）

用户最后确认 r216 下 XPAN 实况按钮仍然点不动。结合 r213 的 UI 层级和 r214 反编译结果，
根因落在触摸分发，不在实况功能本身：

- `XPanExposureWheel` 绘制层级为 39，顶部 `live_photo` 按钮层级只有 9；
- 反编译的 `XPanExposureWheel.onTouchEvent()` 无条件返回 `true`；
- 点击落在实况按钮上时，曝光轮仍先取得 `DOWN` 并吞掉整段手势；
- r196 已经证明，触摸真正到达按钮时，`enableLivePhoto` / `disableLivePhoto` 都能正常调用。

r216 的方向是让曝光轮把落在实况按钮上的完整手势转发给按钮，但有两个实现缺口：

1. 曝光轮本地坐标被原样传给同级按钮，导致约 `21×19` 像素的坐标偏差；
2. 转发日志只记录一次，无法验证连续开关。

r217 的修改位于：

```text
E:\OmniCam-Muse-main\app\src\main\java\local\jiege\hook\xpan\XpanLiveFeature.java
```

现在在 `XPanExposureWheel.dispatchTouchEvent()` 上路由手势：

- 只在 `ACTION_DOWN` 落在可见、可用、可点击的 `live_photo` 按钮内时接管；
- 同一个手指的后续 `MOVE` / `UP` / `CANCEL` 都转发给该按钮；
- 用两个 View 的屏幕原点差修正 `MotionEvent` 坐标；
- 只有按钮没有消费整段手势时，才在 `UP` 上执行一次 `performClick()` 兜底；
- 每次点击都输出 `XPAN wheel gesture forwarded to live` 和
  `XPAN wheel live gesture completed`，可连续验证至少 10 次开关。

r217 已完整构建并通过：

- APK 包内版本：`Muse 4.4.4-r217`，versionCode `657`；
- 三条新增路由标记均存在于 DEX；
- APK Signature Scheme v3 验证通过；
- ZIP 内外 APK 大小均为 `5,267,187` 字节，SHA-256 完全一致；
- `tools/verify_port_artifacts.py` 六组检查全部通过。

测试包：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r217.apk
5,267,187 字节
SHA-256 FCE926F9E268EFEFB48118747E20C50763A849A81D695EEA9E047C713F9F9B54

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r217-KSU.zip
303,729,409 字节
SHA-256 519FF3F4649E787747E682EA4D98D04A27624AB4CD6548D4AFF2662B56F00152
```

构建完成时 `adb devices -l` 为空，r217 尚未实机验证。手机回来后只安装 r217、强制停止
并启动相机应用，严禁重启手机。明早先做触摸验收，不要同时改颜色链路：

1. 进入 XPAN，确认日志出现 `XPAN exposure-wheel live gesture router armed`；
2. 点击实况按钮中心以及四边附近，连续切换至少 10 次；
3. 每次应成对出现 `XPAN wheel gesture forwarded to live` 和
   `XPAN wheel live gesture completed`；
4. 每次应同时看到 `XPAN live photo click before/after` 以及
   `enableLivePhoto` / `disableLivePhoto`；
5. 开关稳定后，拍摄约 3 秒运动场景，回归动态画面、颜色、封面亮度和保存不闪退。

如果只有转发日志但没有按钮 click 日志，再判断是按钮消费了事件却没有触发点击，还是触摸
没有命中按钮可点击区域。届时先补受控诊断，不凭日志猜测颜色回归。

## 2026-11-09：r204 日志已本地归档，r206 transform 探针待实机验证

用户离开时已把完整日志和相机状态拉到本地：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r204_takeaway_20261109_1752\full_logcat_threadtime.log
E:\OmniCam-Muse-main\output\xpan_live_photo\r204_takeaway_20261109_1752\dumpsys_media_camera.txt
```

r204 成片动态附件有 92 帧、约 3.115 秒，运动和保存均正常；封面平均饱和度约
`123.905`，动态附件首、中、末帧仅约 `32.164 / 32.475 / 32.374`。本次没有选择
用户 LUT，`selectedLutIndex=0`、`customLut=false`。

日志已确认 BasicTone 纹理创建、着色器准备、参数启用和绑定全部发生：

```text
XPAN live BasicTone texture created, lutType=1, size=64, dataLength=786432
XPAN live BasicTone direct shader prepared
XPAN live BasicTone direct attachment, enabled=true
XPAN live BasicTone bound, lutType=1, size=64
```

因此当前不能再归因于 BasicTone 未创建或未绑定。剩余最高优先级嫌疑是
`ImageData.i` 继承的 HDR transform/GTM 或普通 LUT 叠加。完整代码路径和判断规则见：

```text
E:\OmniCam-Muse-main\output\xpan_live_photo\r204_takeaway_20261109_1752\r204_local_analysis.md
```

r206 只增加三类一次性诊断，不改变颜色行为：

- `XPAN live transform capture probe`：记录事件 transform 和 `ImageData.i`；
- `XPAN live transform draw probe`：记录 `ch.g.p(...)` 的 BasicTone、普通 LUT、
  离屏标志和 transform；
- `XPAN live transform upload probe`：记录 `a0.o(...)` 是否实际上传主表和 GTM。

r206 已构建并通过完整构建、DEX 探针、v3 签名、ZIP 一致性和
`verify_port_artifacts.py` 六组检查：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r206.apk
5,250,803 字节
SHA-256 B3B827336FCA5D023020C599FA6A0EE9A0C08ECB06B1BDF9EFA1639879444103

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r206-KSU.zip
303,725,230 字节
SHA-256 C95DA16337DF16D1EDBB3DEA7FFD1B04394FB68EFF7B2B76CDB47A746805286B
```

构建时 `adb devices -l` 为空，r206 尚未安装或实机测试。手机回来后安装 r206，
只强制停止并启动相机应用，不重启手机；在 XPAN 开启实况、不选用户 LUT，拍摄约
3 秒运动场景，然后拉取三条 transform 探针日志和实际样片。修复前不能用“日志出现
BasicTone”宣布颜色成功，必须以动态附件饱和度与封面接近为准。

## 2026-11-09：XPAN 实况动态附件仍为 Log 灰（r201，待实机验证）

用户已在 r199 样片上确认：

- XPAN 实况动态画面能够运动；
- 封面亮度正常；
- 成片可以保存，拍摄结束时不再闪退。

唯一剩余问题仍是动态视频附件呈 Log 灰色。r199 的 90 帧附件为
`710×1920`、`3.112` 秒；封面平均饱和度约 `126.465`，视频首、中、末帧平均饱和度约
`34.287 / 34.648 / 34.439`，说明封面已经正常而动态附件没有套用 BasicTone。

根因已由反编译确认：

- XPAN 的 `VideoEffectPipeline` 初始化值为 `lutType=1`，普通实况为 `lutType=7`；
- 页面初始化代码对非 7 类型显式写入 `renderer.p=true`，因此 XPAN 确实编译并选择了
  `eis_warp_renderer_basic_tone_frag`；
- `gh.p3.onInit()` 只在 `lutType=7` 时创建 `a0.s` BasicTone 纹理；
- `gh.p3` 只在 `7 == lutType` 时把 `bh.o.g` 设为 `true`；
- `ch.g.p(...)` 在 `bh.o.g=false` 时明确写入 `uNeedBasicTone=0`；
- 因而 XPAN 虽然使用 BasicTone 着色器，却没有启用 `uNeedBasicTone`，也没有绑定
  BasicTone 纹理。

r201 新增 `ch.g.p(...)` 前置补丁，仅在以下条件全部满足时生效：

1. 当前模式为 `xpan`；
2. 渲染器为 `ch.g` 且 `renderer.p=true`；
3. 实况仍开启；
4. 原厂 `bh.o.g` 仍为 `false`；
5. `Lut3dDataManager$b.a` 已提供有效的 BasicTone `t/c` 数据。

补丁用原厂 `GLRenderer.g(null, size, data)` 创建纹理，写入 `a0.s`，再把
`bh.o.g=true`，随后让原厂 `ch.g.p(...)` 按 BasicTone 正常分支绑定。旧
`GLRenderer.b()` 钩子保留为兜底。补丁没有修改共享 `lutType`。

构建产物：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r201.apk
5,234,419 字节
SHA-256 6C7EA728A9589BE1C821102457E3190E1733F9A79F2331D43E2D5954619DD3D4

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r201-KSU.zip
303,721,368 字节
SHA-256 D0647FA427904E98E1BC9107BCD90F5D9DFD205FEF12C0568533D33A677974DB
```

静态检查已通过：APK 版本为 `Muse 4.4.4-r201`、versionCode `644`；新增诊断字符串在
DEX 内；APK v3 签名与 ZIP 一致性检查通过；`verify_port_artifacts.py` 六组检查均
PASS。

当前 `adb devices -l` 为空，尚未实机验证，不能据此宣布修复成功。手机重新连接后应：

1. 安装 r201，仅强制停止并启动相机应用；
2. 进入 XPAN、开启实况，拍摄约 3 秒有明显运动的场景；
3. 日志确认 `XPAN live BasicTone texture created`、
   `XPAN live BasicTone parameters enabled` 和
   `XPAN live BasicTone bound, lutType=1`；
4. 提取附件，确认动态画面仍可运动，视频饱和度明显高于 r199 的约 34，且与封面颜色
   接近；
5. 回归大师、哈苏超清和普通实况。

手机会在重启后丢失临时 root，未经用户明确批准不得重启。

## 2026-11-09：XPAN 实况动态画面静止（r197，待解锁后实拍验证）

用户确认 r196 的 XPAN 实况封面已经不再变暗，但成片仍不带动态画面，取景器也不运动。
现有 `+2 EV` 曝光补正保持不变。

日志确认编码链没有收到任何视频帧：

```text
mVideoFormat=null
EISWrapper: putData, gyro is null
videoFrameCount is 0
```

根因链路为：

```text
gh.v.f8()
 -> gh.g.f(ImageData.a)
 -> ImageData.a.b(bb.s.r2) == null
 -> 返回 -100
 -> gh.p3.onDrawFrame() 丢弃该帧
```

r197 在 `ImageData$a.b(CaptureResult.Key)` 返回后做窄范围兜底：

1. 只在当前模式为 `xpan`、实况仍开启、读取键为
   `com.oplus.camera.gyro.value.gyrotoeis` 且原值为 `null` 时生效；
2. 返回一帧 `int[10]` 中性陀螺数据，帧时间和样本时间都取该 CaptureResult 的
   `getTimestamp()`；
3. X/Y/Z 均为 0，不伪造运动补偿；目标是先让原始 EIS 和编码器接收真实图像帧；
4. 不修改系统相机共享 CaptureResult，因此不影响大师、哈苏超清和普通照片；
5. 无论 `fh.a.j` 真假，都在公共返回点兜底，不依赖 `ImageData$a.b` 的 Map 缓存分支。

r197 已构建并安装，静态校验结果：

- APK 报告 `Muse 4.4.4-r197`、versionCode 640；
- APK Signature Scheme v3 验证通过，签名证书为 Android Debug；
- 直接输出 30 条、XPAN 24 条诊断字符串均存在于 DEX；

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r197.apk
5,234,419 字节
SHA-256 77B7D97BE2182AB0B1132BC583FFEF687116FAEC649A437B118F9C604E517DD1

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r197-KSU.zip
303,718,871 字节
SHA-256 662986665E92D3294AF002B4566D573099D02CCA50EA530F85F7DAB9177916EC
```

相机应用已在 r197 下启动，日志已确认：

```text
XpanLiveFeature: XPAN synthetic gyro fallback armed
XpanLiveFeature: installed
```

手机随后进入六位密码锁屏，未尝试绕过。测试日志采集位于：

```text
output/xpan_live_photo/r197_live_test.log
```

下一次验收必须确认：

1. 日志出现 `XpanLiveFeature: XPAN synthetic gyro injected`；
2. 不再持续出现 `EISWrapper: putData, gyro is null`；
3. 日志出现 `VideoEncoder ... onOutputFormatChanged`；
4. 成片日志的 `videoFrameCount` 大于 0；
5. XPAN 相册中的实况播放时，画面内容确实随时间变化。

手机是临时 root，未经用户明确批准不得重启。

## 2026-11-09：XPAN 实况照片明显变暗（r193，待实机验证）

同一 XPAN 场景、曝光时间均为 `19,996,025 ns` 时，关闭和开启实况的拍摄结果如下：

| 实况 | ISO | 平均 RGB |
|---|---:|---|
| 关闭 | 800 | 约 `[123.57, 110.28, 90.24]` |
| 开启 | 200 | 约 `[54.41, 45.98, 38.99]` |

开启实况后 ISO 降到四分之一，画面约暗 2 EV。两次拍摄的 `capture_mode` 都是
`xpan_parallel`，`iso_value_from_app` 都是 `-1`；主要差异只有
`livephoto_enable=0 -> 1`。因此问题发生在本机 XPAN 的相机请求或 AE/HAL 流配置阶段，
不是成片保存后的色彩处理。

本轮在 `bb.c1.v(CaptureRequest.Key, value, boolean)` 这个最终请求参数入口加入最小
实验补正：

1. 只在当前相机模式为 `xpan` 且原生实况偏好为开启时生效；
2. 对 `CONTROL_AE_EXPOSURE_COMPENSATION` 增加默认 `660` 单位，即约 `+2 EV`；
3. 使用“原值 + 补正”而不是累计写入，保留用户原有 EV，预览请求反复重建也不会叠加；
4. 关闭实况或离开 XPAN 后立即停止补正；
5. 可用 `persist.omnicam.xpan.liveev` 覆盖默认单位，设为 `0` 可关闭本实验。

上次 r193 构建已通过：

- APK 报告 `Muse 4.4.4-r193`、versionCode 636；
- APK Signature Scheme v3 验证通过，签名证书为 Android Debug；
- 直接输出诊断字符串 28 条、XPAN 诊断字符串 18 条均存在于 DEX；
- ZIP 内 APK 与外部 APK 均为 5,234,419 字节，SHA-256 完全一致。

测试包：

```text
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r193.apk
SHA-256 A55DFE7F07E2352DBA191FC07A9B0E04F0C7C3D84BD437D121B52009FFD5418

E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r193-KSU.zip
SHA-256 CB2ACC821EA1D3B98E649E428EE972ACBA29E6D2C03C826EB4CE81C4E888B760
```

构建和静态校验完成时 `adb devices -l` 为空，尚未做 r193 实机验证。手机重新连接后只
强制停止并启动相机应用，不重启手机。验收同一场景的 XPAN 实况开/关：

1. 日志出现 `XPAN live exposure fix armed`；
2. 进入 XPAN 后日志模式切到 `xpan`；
3. 实况开启时出现 `XPAN live exposure correction`，关闭实况后不再出现；
4. 实况关闭 ISO 约 800，实况开启 ISO 应接近 800；
5. 两张成片平均亮度接近，且手动 EV 仍按用户设置变化；
6. 实况动态附件仍继续单独验收，必须以 `videoFrameCount > 0` 和
   `writeSubVideoResult:true` 为准。

手机是临时 root，未经用户明确批准不得重启。

## 2026-11-08：XPAN 实况照片按钮恢复（r180，待实机验证）

用户反馈 XPAN 模式原来可以拍实况照片，现在顶部实况按钮消失。完整日志已经拉到本地：

```text
output/xpan_live_photo/xpan_before_20261108.log
48,764,071 字节，约 17:51–17:57
```

日志中的直接原因高置信：

```text
17:53:18.553 XPAN feature list[1] n=15
             包含 com.oplus.camera.feature.xpan 和 ...feature.filter
             缺少 com.oplus.camera.feature.live_photo

17:53:21.042 XPAN 拍摄参数 livephoto_enable=0

17:55:19.884 普通模式 feature list[1] n=16
             包含 com.oplus.camera.feature.live_photo
17:55:19.888 OCAM_LivePhotoPresenter onCreate
```

因此不是单纯的状态默认关闭，而是 XPAN 的特性表根本没有原厂实况特性，FeatureManager
没有创建 `LivePhotoPresenter`，顶部按钮和拍摄链路都不存在。`ModeStateSplit` 写入
`xpan=off` 只能解释默认关闭，不能解释按钮完全消失。

已新增独立注入，不能与自定义 XPAN 显影实况 `XpanLivePhoto` 混在一起，也不能依赖
`XPAN_DEVELOPING_LIVE` 开关：

```text
app/src/main/java/local/jiege/hook/xpan/XpanLiveFeature.java
```

该钩子做三件事：

1. 在同时含 `feature.xpan`、且不含 `feature.live_photo` 的小特性表中补入原厂
   `com.oplus.camera.feature.live_photo`；
2. 让 `gb.d0.d(cameraId, "xpan", "...feature.live_photo")` 返回 `true`，允许
   FeatureFactory 创建原厂 presenter。
3. 原厂 `gh.e2.a2(EventMessagePreviewRequestStage)` 只允许普通照片、长焦、专业、
   GR、哈苏超清和复古相机在 stage 4 执行实况预览逻辑，`xpan_mode` 会被判定为
   `unitMode is incorrect`。钩子在该方法返回后复制事件并标记为 `photo_mode`，
   通过 `invokeOriginalMethod` 再调用一次，不修改共享事件，因此其他 presenter
   仍然看到 `xpan_mode`。

`ModuleEntry` 已在 XPAN 安装区域调用 `XpanLiveFeature.installCamera(classLoader)`。

旧测试包只包含前两个钩子，不能用于本轮验证：

```text
module/omnicam-muse-4.4.4-r179.apk
5,218,035 字节
SHA-256 184241D356747C77BBC9690C96E19B9B2D61DF3BBDCE1D71E95B598BAF7E9A6B

OmniCam-Muse-4.4.4-r179-KSU.zip
303,710,299 字节
SHA-256 DD6495330D9CD0CF6505EA5F2C33F0D0E2FCDA83770EDCB9DDA6E8F44529F34D
```

新测试包需要重新构建为 r180 / versionCode 623，构建后必须确认 APK 的
`classes.dex` 内包含以下兼容日志：

```text
XPAN preview stage accepted through photo_mode compatibility
```

手机已经重新连接。安装新包后只强制停止并重启相机应用，不重启手机，按以下顺序测试：

1. 日志出现 `XpanLiveFeature: installed`、特性表 `n=15 -> n=16`、FeatureFactory gate
   和 preview stage 兼容日志；
2. XPAN 进入后出现 `OCAM_LivePhotoPresenter ... onCreate`；
3. 顶部实况按钮出现并可开关；
4. 开启后拍摄参数从 `livephoto_enable=0` 变为 `1`；
5. 成片确为实况照片，退出 XPAN 后普通照片和哈苏超清实况状态不受影响。

手机是临时 root，未经用户明确批准不得重启。

## 2026-11-08：r187–r190 菜单项补建修复（r190，待实机验证）

r187 已证明 XPAN 实况 presenter 链路基本正常，日志出现：

```text
XPAN live_photo added to feature list (14 -> 15)
XPAN mode answers pref_live_photo_key = true
XPAN live photo eligibility gate opened
XPAN preview stage accepted through photo_mode compatibility
XPAN resume live photo close suppressed
```

但 r187 的界面树中，XPAN 左上仍只有
`xpan_switch_to_physical_ui`，没有 `pref_live_photo_key`。因此问题不在 presenter
创建，而在左上菜单项根本没有被创建。

相关本地证据：

```text
output/xpan_live_photo/r187_xpan_launch_20261108_2202.log
output/xpan_live_photo/r187_window_20261108_2202.xml
output/xpan_live_photo/smali13/com/oplus/camera/ui/newsetting/container/r.smali
output/xpan_live_photo/smali13/cq/l.smali
```

反编译确认：

- `container.r.B(...)` 才负责创建菜单对象；r189 在该方法前注入
  `pref_live_photo_key`，但钩子执行时左上容器仍处于 `common` 上下文，
  `isXpanMenuContainer()` 为 false，因此没有注入。
- `cq.l.f1()` 只读取已有 `r.i` 重排菜单，不会补建缺失项。
- `jq.h.A()` 和 `jq.h.d0(GONE)` 只能保护已存在对象；对象不存在时没有作用。
- `container.r.k(String)` 是原厂按需创建入口：从 `r.c` 查菜单元数据，
  检查菜单位置，复用 `r.j` 缓存或构造原厂 option，再调用 `r.n(...)`。

r190 保留 r189 的 `B()` 早期注入作为兜底，并在 `findLivePhotoMenuItem()` 找不到
对象时调用原厂 `r.k("pref_live_photo_key")`。随后：

1. 从 `r.h` 或 `r.i` 重新查找对象；
2. 若 `r.k()` 只创建在缓存 `r.j` 中，则安全采用该对象；
3. 对该对象在 `r.h`、`r.i` 中按引用去重后补齐成员关系；
4. View 尚未挂载时调用原厂 `r.n(item, true, true)`；
5. 继续使用原有 `g0()`、`d0(VISIBLE)`、`A()` 和 GONE 防护。

新增的关键日志：

```text
XPAN live photo menu requested through stock container helper
XPAN live photo menu created through stock container helper
XPAN live photo menu stock helper failed
XPAN live photo menu cache adopted after
XPAN live photo menu membership failed
XPAN top-left menu still has no live photo item after
```

r190 构建与独立校验结果：

```text
module/omnicam-muse-4.4.4-r190.apk
5,218,035 字节
SHA-256 5A00DCAB7CC1BC6BA8F244054665DC8BF7C19B9B0BD31676EBA9975F7A469301

OmniCam-Muse-4.4.4-r190-KSU.zip
303,714,612 字节
SHA-256 54712909BCFDEAA404B65A99F9BE90F712C42011E296349DFC734A8C34A74593
```

- APK 报告 `Muse 4.4.4-r190`、versionCode 633；
- APK Signature Scheme v3 验证通过，签名证书为 Android Debug；
- 直接输出 28 条、XPAN 11 条诊断字符串均存在于 DEX；
- ZIP 内 APK 与外部 APK 均为 5,218,035 字节，SHA-256 完全一致。

手机连接后只强制停止并启动相机应用，不重启手机。验收步骤：

1. 进入 XPAN，确认左上出现 `pref_live_photo_key`；
2. 确认日志出现 `requested`，并出现 `created` 或 `cache adopted` 之一；
3. 若仍失败，读取 `still has no live photo item` 后的 `keys` 和异常栈；
4. 开关按钮后拍摄，确认拍摄参数 `livephoto_enable=1` 且成片带有实况附件；
5. 退出 XPAN，复核普通照片和哈苏超清实况状态不受影响。

## 2026-11-08 最终验收状态

r173 已通过 X8U 实机验收，用户确认画面正常。验收条件为专业视频、4K30、Log、自定义
`2383lumix1` LUT：

- 打开“录制效果直出”后，预览和成片都出现所选 LUT 的色调；
- 关闭直出后，成片恢复灰色 Log 基线；
- 成片为 `2160×3840`、旋转 0°，角度正确；
- SDR Rec.709、自定义 LUT 和此前的颜色异常均已在同一运行包内复核。

没有使用诊断覆盖。手机上的 `persist.omnicam.loglut.halmode` 与
`persist.omnicam.loglut.clearflags` 均为空，会话日志仍显示原生请求：

```text
HAL session=31 ... value=2 int=2 override=-1 applied=false sent=2 direct=2
selectedLutIndex=15 customLut=true
```

正式 12.166 秒样片从 15:41:53.124 开始，编码链完整：

```text
recorder selected actual=2 direct=2 format=log selectedLutIndex=15 armed=true
recorder selected App MediaCodec actual=2 target=true appCodec=om.l
app encoder startup ... P0=true ... targetCodec=true protected=true branch=l0.c/renderer
encoder callback surface=valid size=2160x3840 selectedLutIndex=15 customLut=true
output renderer drew frame=300 lutIndex=15 selectedLutIndex=15 customLut=true
output pipeline ... outputRendererFrameCount=365 outputFrames=365
```

预览侧为普通 LUT shader，合成 LUT 没有抢回：

```text
GL final shader choice direct=2 format=log shader=h0
bypassOesPreviewLut=false activeLut=true inputIndex=15
syntheticEnabled=false syntheticTable=false
```

样片和对比证据：

```text
output/lut_preview_fix/r174_native_on_154153.mp4
124,086,835 字节，12.166 秒，hvc1 2160×3840，旋转 0°
SHA-256 BBD09F369E95BA99AFC6C148194DA66FD793C9C87B48E1EEABF5E0EB0B77A39E

output/lut_preview_fix/r174_native_on_mid.png
output/lut_preview_fix/r174_native_video_ab_preview.jpg
output/lut_preview_fix/r174_native_video_ab_detail_preview.jpg
output/lut_preview_fix/r174_native_video_ab_metrics.json
```

中央区域 A/B：直出关闭平均饱和度 23.35%，开启 50.79%；平均 ΔE76 27.34，中位数
25.11，P95 59.20。开启态保留 LUT 的暖色、高反差和高饱和，关闭态仍为灰色 Log。

跨模式也已复核通过。15:46:57 切入人像，15:47:24 切回普通照片；日志显示
`proVideoActive=false`，照片路径的 `BaseMode v2=7` 没有被模块覆盖。人像 1.5×和照片
1×均为正常色彩，没有再出现红蓝偏色：

```text
output/lut_preview_fix/omnicam_portrait_1x.png
output/lut_preview_fix/omnicam_back_photo.png
output/lut_preview_fix/r174_mode_retest.log
```

最终交付包已校验：

```text
APK：Muse 4.4.4-r173，versionCode 616
E:\OmniCam-Muse-main\module\omnicam-muse-4.4.4-r173.apk
SHA-256 A290D3B79F3F807CEA46B518A06E761170D6889B9D250D511E855F79DF678D47

ZIP：E:\OmniCam-Muse-main\OmniCam-Muse-4.4.4-r173-KSU.zip
SHA-256 2E2E99BED406D1773B5FE8E8C5C5240EBA7B4F26F80E198966948F2E622CD765
```

APK v3 签名验证通过；ZIP 内 APK 为 5,201,651 字节，SHA-256 与上面的 APK 完全一致；
`python tools/verify_port_artifacts.py` 六组检查全部 PASS。

根因仍保留在 `docs/LOG_LUT.md` §6.8 至 §6.9：直出开启时 `d7(0)` 落 0，导致普通预览
LUT 节点缺失；同时录像需精确切到 App MediaCodec type 2，并由输出渲染器持续绑定所选
LUT。当前没有必须继续修复的 LUT 直出项。

手机保持连接且 root 正常，本轮没有重启。未经用户明确批准仍不得重启；临时 root 会丢失。

## 已完成事项

1. 使用 OPPO O-Log v1 官方 LUT 将 Log 视频还原为 Rec.709 SDR；
2. 修复关闭“录制效果直出”时，自定义 LUT 选中后取景器不变色的问题；
3. 修复打开“录制效果直出”后，预览和成片仍保持 Log 灰色的问题，r173 已通过实机验收；
4. 处理 DeepSeek Responses 网关因累计图片和工具输出过大而返回 HTTP 413 的问题。

## 413 问题的实测结论

- 自定义 `deepseek-flash` 模型目录把上下文窗口标为 1M，但 DeepSeek 网关的请求体上限明显更小，约 48 MiB。
- 出问题的会话 rollout 分别约 52.45 MiB 和 53.10 MiB。
- 触发 413 时的 O-Log 会话 rollout 约 18 MiB，其中一张原尺寸 PNG 的 base64 单项约 14 MiB。
- 新会话配置已设为：
  - `model_auto_compact_token_limit = 200000`
  - `tool_output_token_limit = 20000`
- 这些设置只限制以后新建或重新加载的会话。已经写入历史的 base64 图片不会被删除。

## 继续工作时

- 不要在旧的长会话里继续。先新建会话，再让它读取本文件。
- 不要把大型 PNG/JPG 直接放进对话。先缩放到合理尺寸并转成 JPEG，或只提供本地路径。
- 命令输出只取相关片段；大型日志写入文件后再用 `rg` 定位。
- 历史 O-Log 截图在 `output/video/olog_before_after.jpg` 和
  `output/video/olog_rec709_lut_preview.jpg`，视频在 `output/video/`。

## 已完成的 O-Log 还原交付

- 原始素材：`output/video/olog_source_1080p30.mp4`
- Rec.709 成片：`output/video/olog_to_rec709_1080p30.mp4`
- 官方 LUT：`output/video/O-Log-Rec.709_Gamma2.4_v1.6.cube`
- 前后对比：`output/video/olog_before_after.jpg`

这份 LUT 已与官方 `o-log_aces_workflow_guide.zip` 中的副本逐字节核对一致。
Find X8 Ultra 应使用 O-Log v1，不要使用 O-Log2 LUT。

## 相机 LUT 直出历史结论（2026-11-07，已由 r173 修复）

详见 `docs/LOG_LUT.md` 第 6 节：

- **已修复**：关闭“录制效果直出”时，选择导入的自定义 LUT，取景器会实际变色；
- **当时仍失败、现已修复**：打开“录制效果直出”后，取景器和成片都不变色，成片仍是灰色
  Log；r173 通过补齐预览 LUT 契约并校正录像编码链解决了该问题，最终证据见 §6.9；
- HAL 日志中的 `com.oplus.log.video.mode=2` 只证明请求进入会话，**不证明 HAL 应用了
  LUT**，必须结合编码 Surface、输出渲染器和实际成片颜色判断；
- 8-bit `yuv420p`、文件大小或 RGB 均值都不能单独证明 LUT 成功，之前据此得出的“直出成功”
  结论已作废；
- 本次最终验收覆盖 SDR Rec.709 和自定义 LUT；HDR HLG 全链没有纳入本次验收。

2026-11-07 17:50 手机曾被临时撤走，当时只能做本地分析；2026-11-08 手机已恢复连接并完成
最终验收。该机是临时 root，未经用户明确批准仍不得重启。

## 2026-11-07 晚间接续记录（历史，已由 r173 修复）

- 当时实机对照：同一 `2383lumix1` 自定义 LUT、专业视频 4K30、Log。
- `direct_off_2383lumix1.mp4`：取景器变色，但成片仍为 Log 灰色。
- `direct_on_2383lumix1.mp4`：取景器和成片都不变色。
- 两段本地样本位于 `output/lut_preview_fix/`。`direct_on` 为 HEVC 8-bit BT.709，
  `direct_off` 为 HEVC 10-bit BT.2020/BT.709 transfer；像素格式差异只说明编码路径不同。
- 当时为下一次实机测试补充了精确日志：
  - `HAL session=N custom LUT name ... raw=... sent=...`；
  - `HAL session=N log video mode type=... value=... int=...`；
  - `output EGL surface created target=...`；
  - `output pipeline jit=... A=... B=... C=... z=...`；
  - `output renderer drew frame=... lutIndex=...`。
- 判断规则：只有成片画面真实出现 LUT 色调变化才算成功。`mode=2`、输出渲染器出现或
  8-bit 编码都只是中间证据。

上述两段是 r173 之前的失败样本，不再代表当前状态；最终通过样片是
`output/lut_preview_fix/r174_native_on_154153.mp4`。

## 工作区注意事项

工作区可能已有用户修改。不要 reset、checkout 或覆盖无关改动。编辑前先查看
`git status --short` 和相关 diff。

## 2026-11-07：文件夹导入复测通过

手机空间恢复后，已对 `Muse 4.4.4-r164` 完成一次冷启动现场复测：

- 标准系统目录选择器正常返回
  `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FOmniCam_Lut_Folder`。
- 递归扫描根目录和 `sub` 子目录，共得到 2 个候选 LUT。
- 原厂面板无需重启即可刷新，并同时显示 `LutNested` 与 `LutRoot`。
- 重复导入同一文件夹没有产生重复名称。
- 实际选择 `LutRoot` 成功，随后完成了一次 4K 30p Log 短录制。

关键日志见：

```text
output/lut_import_test/r164_space_retest.log
output/lut_import_test/r164_space_retest_record.log
```

其中检索 `LogLutImport` 可以看到：

```text
tree scan complete directories=2 candidates=2
imported 2 LUT(s) through system picker
LogLut: lut panel shown by stock (D)
```

此前“还是一样的提示”实际是相机在手机空间不足时显示的“手机空间不足，无法拍摄”，
不是文件夹导入失败。空间恢复后该提示未再出现。

本次验证生成了一个测试视频：

```text
/storage/emulated/0/DCIM/Camera/VID20261107145912.mp4
```

约 16.3 秒、159,157,338 字节。若不再需要输出画面对比，可以先删除该测试视频。
