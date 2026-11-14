# 参考包 `MUSE-X8U-V3-test160-KSU.zip` 对照：理光 GR 颜色 / 大师色彩

日期：2026-11-13 · 对照对象：`F:\手机\MUSE-X8U-V3-test160-KSU.zip`（`V3-test160` / versionCode 598，
二改移植，面向 **X8U + 相机 7.013.51**） · 本仓库：`Muse 4.4.5-r332`（PMA110 7.006.119 / PKJ110 7.006.123）

结论先行：

1. **GR 的颜色数据两边逐字节相同**。`payload/gr_adjustment_lmt/`（36 个 SC16/SC32/CWCM16/CWCM32）
   与 `payload/master_color/`（338 个）共 374 个文件，md5 全部一致；GR 的 staging 脚本、GR 模式
   `camera_unit_config` 的 `gr_mode_case` / `gr_mode_hq_raw_case` 也一致。**"他的 GR 颜色好"不是数据差异。**
2. **差异在渲染侧和机型/相机版本**：参考包多一层 `GrSoftLightBridge`（把 GR 风格塞进美摄柔光槽）
   和一套大得多的原生核心 `libaura_grcore.so`（柔光、调色盘、V2 滤镜、V1 终渲参数、原生颗粒）；
   我们这台 7.006.123 的静图链路**根本不进美摄**（已实测：14 个 `Ms*` 导出挂满钩，一次大师拍照零调用）。
3. **大师色彩"切换没效果"最可能出在设置传输**：参考包把相机侧读设置从 `XSharedPreferences` 换成了
   自己 provider 的 `private_preferences`（80ms 重试 + 落盘缓存 + 500ms 变更轮询），并把
   `master_look` 默认值 1→0 兜底原厂。本仓库仍是 `XSharedPreferences` 一条路，而
   `docs/MODULE.md` 自己就记着"相机进程读不到模块 SharedPreferences（XSharedPreferences 恒空）"。

---

## 1. 两个包是什么关系

| | 参考包 V3-test160 | 本仓库 r332 |
|---|---|---|
| 来源 | Muse 的二改移植（作者"在日出之前"） | Muse 主线 + 我们的 PKJ110 移植 |
| 目标机型 | X8U（PKJ110） | PMA110（X9U）+ PKJ110（X8U） |
| 目标相机 | **7.013.51** | **7.006.103 / 119 / 123** |
| 符号表 | 360 条相机条目 | 249 条（按 7.006.123 重建） |
| 设置读取 | 自己的 provider（`ScopedPreferences`） | `XSharedPreferences` |
| 原生核心 | `libaura_grcore.so` 549KB + `libmuse_palette_x10`、`libopus110_softlight`、`libmuse_photo_v2`、`libmuse_trigger139`、`libmuse_capture_compat` | `libjiege_native.so`（同一份代码里各功能自带实现） |

**这一条就已经解释了大半"网友体感"**：7.013 的机器上装我们这个包，`symbols.json` 是 7.006.123 的，
GR 那一整组钩子（`GrModeHooks` → 后面所有 GR 功能）会因为符号解析失败而整体退出
（`GrPort.installCamera` 里 `install("…")` 全部包在 try 里），而原生侧 `getSettingInt` 指纹、`processCore`
偏移也是按 7.006 写的。所以 7.013 用户对比的其实是"完整 GR" vs "没有 GR"。

---

## 2. 理光 GR：逐层对照

### 2.1 数据层 —— 完全一致

```
payload/gr_adjustment_lmt/*   36 个  (SC16Lut0..8, SC32Lut0..8, CWCM16Lut0..8, CWCM32Lut0..8)   md5 全同
payload/master_color/*        338 个 (hncs_engine.bin, scene.bin, looks/*)                      md5 全同
```

`gr_adjustment_lmt` 就是 GR 的色彩本体：SC16/SC32 是 16³/32³ 的 RGB 浮点立方（49152 / 393216 字节），
CWCM 同尺寸。它们是 BasicTone 在 GR 配置下用来替换原厂 SC/CWCM 的表。

### 2.2 配置层 —— GR 模式本身一致，开启方式不同

两边 `camera_unit_config` 的 `gr_mode_case` 与 `gr_mode_hq_raw_case` 逐条相同（`capture_meta/preview/raw_output/
capture_raw10_dol/capture_raw10_3dol/preview_in_preview`，`"gr_mode": "8009"`）。

区别只在"怎么把 GR 打开"：

- 参考包：**只走 ODM 配置**（`payload/oplus_camera_config` 里 `com.oplus.gr.mode.support` 等），
  它的 `service-payload.sh` 明说"ODM 配置是功能开关的唯一来源，不写入相机覆盖设置"。
- 我们：`service.sh` 往 `override_config_data.xml` 写 `com.oplus.gr.mode.support=1`、
  `com.oplus.camera.mode.data.db.version=102`、`available/marked.gr.mode.zoomvalues`、
  capture/preview 的 HDR 模式列表；ODM 配置另外用 `post-fs-data.sh` 绑定 `payload_pkj110` 的三份。

风险点：`db.version=102` 是**写死的**。相机版本一升（比如 7.013 的 db.version 更高），
这个值会把模式库版本往回压，触发重迁移甚至让 GR 页/风格条不出现。

### 2.3 传输层 —— 同一套 `GrEffectTransport`

参考包的 `GrEffectTransport` 与我们的同名类方法一一对应：`hookPreviewLut`（`Lut3d.load` +
`CombineLutHelper.init` 指向 `files/ricoh_gr/lmt`）、`hookCaptureStage`（`GrMode.captureStage` +
`StageRequest.putBundle`，键 `gr.effect.params`）、`hookSdkRequest`（`GRCapMode.updateCaptureRequestTag`）、
`loadEffectBytes` / `structBytes`（`GrStylePanel.effect` → `GrEffect.table` → `GrEffectTable.buffer`）。
符号表里 `GrStylePanel/GrEffect/GrEffectTable/Stats.setGrEffectParam` 这套条目两边也一致。

### 2.4 渲染层 —— 这里是真差别

我们这台机器上 GR 能落到成片的只有三样：

1. **LMT 树替换**（`gr_basictone.cpp`）：`gr_effect_size == 63` 的帧把 BasicTone 的 LMT 目录指到
   `files/ricoh_gr/lmt`，即"原厂 LMT + GR 的 SC/CWCM"。**这是 GR 颜色的主体**，两边一样。
2. **关掉艺术暗角**：`getVigParamsV1` 之后把 `parameters[0x1a]` 归零。
3. **V1 滤镜节点**（清晰度/暗角/颗粒）：GR 帧用 capture mode 37 临时注册 `doFilterRegister/Process/
   Unregister`。

参考包在这之上还有：

- `gr.GrSoftLightBridge`（+9 个内部类）：进入 GR 时把相机自己的**美摄柔光槽**打开
  （`FilterMenu.show` / `setSoftLight`、`SOFT_LIGHT_OPEN`、`FilterPresenter.openSoftLight` /
  `softLightIndex` / `captureRequest`、`SoftLight.typesForMode`、`sSoftLightNoneIndex`），
  把 GR 风格作为柔光类型送进拍摄请求。也就是说它多了一条"风格整帧上色"的通道。
- `grcompat.GrStyleSourceCompat` / `StyleRoute`：7.013 把 GR 风格 JSON 改成走 `StyleSource.getInputStream`
  （`AssetStyleSource`），他们做了重定向（把 `gr_style_[1-5].json` 指到 `watermark_master_styles/`，
  实体文件在 `gallery/files/ricoh_gr_styles`）。我们 7.006 侧还是 `FileStyleSource`，不需要这层。
- 原生核心 `libaura_grcore.so`：除 BasicTone 之外还带 **柔光**（`MsDoRenderHostBuffer`、GPU 门控/肩部
  shader）、**调色盘**（`masterpalette_sc`、`SDR texture route`、`host route enable`）、
  **V2 滤镜 capture mode**、**V1 终渲参数**（`V1 final render param type=%s soft=%u level=%d grain=%.2f`）、
  **原生颗粒**（`MasterGrain104`，读 `filter_grain_amount/size/roughness/type` 与
  `Grain Shadows/Midtones/Highlights/Color Ratio`）。

### 2.5 所以"他的 GR 颜色好"从哪儿来

按可能性排序：

1. **相机版本**（7.013 vs 7.006）：GR 的成片渲染在相机/HAL 里，7.013 的 GR 管线本来就更新；
   我们的符号与原生偏移只覆盖 7.006，7.013 上整套 GR 钩子会静默退出。
2. **美摄柔光那条通道**：参考包用 `GrSoftLightBridge` 让风格在柔光里整帧上色；我们这台静图不进美摄，
   等于没有这一层（我们早先给 14 个 `Ms*` 导出全部挂钩，一次大师拍照零调用，见 `docs/GRAIN_PKJ110.md`）。
3. **颗粒/暗角**：GR 风格自带的颗粒我们不会出（除非复用 `grain_overlay.cpp`），暗角被我们主动归零；
   参考包的原生颗粒是独立实现的（`MasterGrain104`），不依赖美摄。
4. **开启方式**：ODM 配置 vs 相机 prefs 覆盖（含写死的 `db.version=102`）。

---

## 3. 大师色彩：逐层对照

### 3.1 选择与存值 —— 同一代代码

参考包的 `hook.mastercolor.MasterColor` 与本仓库几乎逐方法对应：`syncSetting`（1000ms 节流、同样的
`master_look*` / `master_blend_*` / `master_film_density_*` / `master_align` 键、`-400..400` 的 EV、
`MODE_BLEND` 时 OPPO 归到 HNCS、双 0 写成 `oppo`）、`writeKey`（`MCOLKEY8`、FNV-1a、同样的排序与字段）、
`onPreviewResult`（`AWBFrameControlCCT`、`CCT_STEP 400`、`uw14/main23/tele70/tele230` 定焦标定、
`PREVIEW_INTERVAL_MS`）、`process/reload/submit/clearPreview`。look 文件格式（5~6 行）也一致。

### 3.2 参考包多出来的四件事

| 项 | 参考包 | 本仓库 |
|---|---|---|
| live 目录名 | `v120_p%05d_%016x` | `p%05d_%016x` |
| 预览工作表缓存 | `mastercolor.PreviewWorkCache`（Key/Entry，LRU） | 无 |
| 表缺失时 | `preview: colour table unavailable; stock retained`（回退原厂，不阻塞） | 写 key 失败就 `clearPreview`，等地重新烘 |
| 设置来源 | `hook.compat.ScopedPreferences` → `content://local.jiege.camera.masterfilters` 的 `private_preferences` | `XSharedPreferences` |

`ScopedPreferences` 的细节（从 dex 读出）：`call("private_preferences", "module_settings")` → Bundle
`values` + `ok`，**80ms 重试**等 provider 起来，**500ms** 轮询文件变更，provider 拿不到时回退到落在
相机自己 prefs 里的 `muse_settings_cache_v2`，并在日志里打 `[MusePrivatePrefs] provider unavailable, using
cached settings:`。provider 侧 `ui.MasterFilterProvider` 加了同名方法，只放行 `com.oplus.camera` 与
`com.coloros.gallery3d`。

### 3.3 "切换没效果"的三条可疑断点（按可能性）

1. **设置没进相机进程**。相机侧 `Settings` 只有 `XSharedPreferences`。`docs/MODULE.md` 明确写过
   "相机进程读不到模块的 SharedPreferences（跨 uid 且跨 SELinux MLS 类别，`XSharedPreferences` 恒空）"，
   柔光的挡位与强度就是**因为这个才改放系统属性**。若这里恒空，`Settings.getInt(MASTER_LOOK)`
   永远返回默认值 `FAMILY_HNCS`，`files/jiege/mastercolor/look` 永远写 `hncs`，
   **在设置页怎么切都不会变**。参考包的 changelog 把这条修法写成三句：
   "master_look 默认值 1→0 兜底原生、provider 查询失败自动重试 80ms、缓存回退不再锁死 loaded 以允许后续刷新"。
2. **`syncSetting()` 的触发点太窄**。我们只有两个入口：`Lut3d.load`（预览）和 `TotalCaptureResult` 构造
   （且要 `inMaster` 为真）。若这台相机的 master 预览不走 `Lut3d.load`（很可能，理由同颗粒：静图链路
   不经美摄），`inMaster` 只能靠 `ModeSwitch.switch` 置位，look 文件就迟迟不写——成片侧读不到就退回
   "无文件 = HNCS"。
3. **成片侧的前提条件**：`gr_basictone.cpp` 认定 master 帧为 `frame+0x18 == 33`，并且要能取到 CCT/CCM
   （否则 `masterColorCaptureInfo` 打 `capture: no CCT, stock LMT`）。`README.md` 自己列在"未验证"里：
   "大师色彩 Find X8 Ultra …均未上机拍照"。也就是说 PKJ110 这条成片链路**从来没被验证过**。

### 3.4 默认值差异

`master_look` 默认：参考包 **0（原厂 OPPO）**，我们 **1（HNCS）**。差别在于设置读不到时的失败姿态：
他们静默等于原厂（看不出来），我们静默等于 HNCS（看起来"一直是同一个调子"）。

### 3.5 真因与修复（2026-11-14，r338）

上一轮把"切换没效果"归到"LMT 只作用在 4096×3072 中间帧、成片却是 6100×3388"，**那个判断是错的**。
6100×3388 就是同一张 4096×3072 竖幅照片，只是被大师水印加了白边：

```
IPU_WM_MasterWatermarkCreator-aiMaster: buildParamsFromJson, finalPicture: 3388x6100,
  pictureSize: 3072x4096, StartX: 158, StartY: 1002
→ 3072 + 158*2 = 3388，4096 + 1002*2 = 6100
```

所以画面内容从头到尾就是 BasicTone 吃进去的那一帧，下游没有任何"另一条管线"。

真正的断点在**文件名的拼写**。`libBasicTonePhoto.so` 里照片路径拼的是 `%s/LMTPhotoLut0`
（另有 `%s/Photo/Sensor%d/LMTLut0`），而 live 目录只烘了 `LMTLut0`；
`masterColorRemapPath` 对不认识的 `LMTPhotoLut*` 一律回落到
`/odm/etc/camera/basictone/lmt/<name>` —— 也就是 **stock 表**。于是：

- 表烘好了、`BasicTone profile=master-colour path=…/live/<key>` 也打了、
  `loadLut` 也跑了，读的却始终是原厂 `LMTPhotoLut0`；
- GR 侧一直没这个问题，因为 `files/ricoh_gr/lmt` 是**整个目录**的拷贝，里面本来就有 `LMTPhotoLut0..3`。

修复（`master_color.cpp`）：新增 `lutIndex()`，同时认 `LMTLut<N>` 与 `LMTPhotoLut<N>` 两种拼写；
目录里有同名文件就用它（`LMTLut0`、混合模式的 `LMTLut1..5`），否则同索引配 `LMTLut<N>`，
再退到 `LMTLut0`。`masterColorPreparePath` 用同一个判断，预览才会在首次 open 时烘表。

实测（大师模式、同一机位连拍，裁掉水印边框后比 160×160 缩略图）：

| look | mean RGB | 饱和度 | 与 OPPO 的平均像素差 |
|---|---|---|---|
| OPPO（原厂） | 96.3 / 91.4 / 76.2 | 53.6 | — |
| HNCS | 102.3 / 94.1 / 65.0 | 66.8 | 9.9 |
| Lumix 标准 | 101.3 / 92.2 / 67.3 | 60.8 | 9.8 |
| 胶片 Kodak Portra 400 | 97.9 / 84.1 / 61.4 | 68.7 | 12.7 |

修复前同一组对比是 **< 1.2**（等于没变）。四张并排见 `work/master_look_ab.png`。

另有一条与代码无关、但同样会让人以为"没效果"的：**大师模式在模式栏 rank 0**，
排在 POP 左边，模式栏默认停在"照片"，必须一直往左滑才看得到。`mode_data.db` 里
`('professional', 4, 'position_headline', 0, …)` 就是它的位置。

---

## 4. 设备回来后怎么验（一次开机就能定案）

```bash
# 0) 装 r332（本版新增三行诊断日志）
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.oplus.camera && adb shell logcat -c

# 1) 设置到底进没进相机？看这一行
adb shell am start -n com.oplus.camera/.Camera && sleep 8
adb shell logcat -d -s MasterColor | grep "prefs file="
#    source=file    → 设置读得到（问题不在这）
#    source=defaults 或 readable=false → 设置没进相机，需要 provider 传输（见 §5.1）

# 2) 大师色彩：切一个 look，再看三处
adb shell cat /data/user/0/com.oplus.camera/files/jiege/mastercolor/look   # 第一行应是所选 id
adb shell logcat -d -s MasterColor | grep -E "master colour |look=|baked |capture:"
#    "look=xxx (module look table)" 变了 → 成片侧认到了
#    "capture: no CCT/gamma.info present" → 成片侧被元数据挡掉（§3.3-3）
#    完全没有 "look=" 行 → masterColorLook 没被调用，即 capture mode 不是 33

# 3) GR：看它有没有被认成 GR 帧
adb shell logcat -d -s RicohGrPort | grep -E "gr_effect_size|capture mode=|BasicTone profile"
#    gr=0 且 capture mode 不是 37 → GR 帧没被认出，GR 树根本没换上
#    profile=GR → GR 树已生效（颜色主体是对的，差异就只剩美摄/颗粒/暗角）

# 4) 同一场景 A/B：GR 与大师各拍一张，比较 GR 是否真有"理光味"
```

判据（GR 颜色是否生效）：`BasicTone profile=GR path=/data/user/0/com.oplus.camera/files/ricoh_gr/lmt`。

---

## 5. 建议的改动（按性价比）

1. **设置传输**（已做一半）：`hook/common/PreferencesFile.java` + `Settings` 改为**先直接解析
   `module_settings.xml`**，`XSharedPreferences` 降为兜底。理由：LSPosed 把偏好目录做成可读，而相机
   进程本来就在读**同一个目录**里的大师滤镜文件（`MasterFilterStore` → `MasterFilterSync`），这条路是通的；
   比加 provider + IPC + 缓存简单，也没有"provider 没起来就全空"的窗口。参考包走 provider 是因为它
   还要给相册用；我们只需要相机读。
2. **`syncSetting()` 提前**（已做）：`MasterColor.installCamera` 里直接跑一次，不再等预览钩子，
   这样只要进程起来，`look` 文件就是对的。
3. **诊断**（已做）：相机启动打 `Settings.describe()`；原生 `masterColorLook` 每次 look 变化打一行
   `look=%s`；`gr_basictone` 打 `gr_effect_size=` 与 `capture mode=`（各只打一次变化）。
4. **默认值**：`master_look` 1→0（跟参考包一致），让读不到设置时等于原厂而不是静默 HNCS。
   —— 这会改变"从没动过设置"用户的默认观感，要不要改由你定。
5. **live 目录版本前缀**：`p%05d…` → `v120_p%05d…`（或 `v332_`），换引擎/换表后不会复用旧烘表。
   参考包是 `v120_`，我们没有任何前缀，引擎升级后旧目录仍可能被命中。
6. **GR 侧**：若要补回美摄那一层，只能自己画（我们已经有 `grain_overlay.cpp` 在成片 dma-buf 上作画）：
   颗粒可直接复用；清晰度/暗角要做就得读 V1 参数（`filter_grain_amount` 一类）再在 YUV 上算。
   这条属于"新功能"，不是修 bug。
