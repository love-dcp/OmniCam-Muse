# PKJ110 · ColorOS 17.0.1.201 适配说明（r500）

本文记录 X8U（PKJ110）从 `17.0.0.104` 迁到 `17.0.1.201` 时到底变了什么、
模块凭什么判断该给哪一套 payload、以及还有哪些文件没有拿到。

## 1. 变的不是 App，是相机栈

| 文件 | 17.0.0.104（旧） | 17.0.1.201（新） |
|---|---|---|
| `/odm/lib64/libextensionlayer.so` | 1723576 字节，md5 `6af236b1…`，build-id `…040a329637` | 1756488 字节，md5 `36d10228…`，build-id `9b9b6754…` |
| `IsIZoomEnableVideo` 模式闸门 | vaddr `0xa61c8` | vaddr `0xaa838`（**+0x4670**） |
| AEC 闸门 `ccmp w16,#1,#0,ne` | `0xa632c` | `0xaa99c` |
| 60fps `csel w21,wzr,w21,eq` | `0xa635c` | `0xaa9cc` |
| `/vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so` | md5 `7040ae71…` | md5 `707ebea9…`，两个 `subs/b.lo` 站点**位置不变** |
| `/odm/etc/camera/CameraHWConfiguration.config` | 274231 字节 | 274161 字节（内容有变） |

相机 HAL 整体重链，`camera.qcom.so` 的 build-id 也从 `76597dd6…` 换成
`162f8bd3…`——这正是网友「专业视频开 ISZ 一开就闪退」的根因：旧
`CameraHWConfiguration.config` 被 bind 到新 HAL 上，
`ChiHwConfigWrapper::Create` 把一个未映射的地址交给 CFI：

```
signal 11 #00 __cfi_slowpath+28 (libdl.so)
         #01 ChiHwConfigWrapper::Create (.cfi) (camera.qcom.so)
         #02 CNodeImpl::Initialize → Pipeline::CreateDescriptor
Process vendor.qti.camera.provider-service_64, uptime 0s
```

## 2. 模块现在怎么选 payload（特征匹配）

不再按「机型 = PKJ110 所以用 payload_pkj110」，而是**哈希即将被替换的那份 stock 库**：

```
PKJ_STOCK_17_0_0=6af236b1dbfae3629c86e8f956d33f77
PKJ_STOCK_17_0_1=36d10228409c766502b8e67566c6be49
```

- 命中 `17_0_0` → `payload_pkj110/`
- 命中 `17_0_1` → `payload_pkj110_201/`
- 都不命中 → **一个都不挂**，`mount.log` 写
  `video isz skipped: no payload matches this camera stack`
- 若文件已经是我们自己的 payload（热重挂场景），退回 `ro.build.display.id` 判断
- 调试可用 `touch /data/adb/modules/ricoh_pop_x9u/force_pkj_payload`，
  内容写 `17.0.1.201` 或 `17.0.0`，强制指定

`apply-now.sh`（热应用）用同一套指纹；`--with-isz` 在指纹不认识时直接拒绝，并提示
「不是缺授权，是这个系统版本的相机库还没适配」。

原生补丁也不再写死地址：`tools/patch_x8u_ext_isz.py` 先按记录地址校验指令字，
对不上就按**指令签名**在整库里找唯一命中点，唯一才改、不唯一就报错退出。
本次实测：

```
note: mode gate relocated 0xa61c8 -> 0xaa838 (build differs, signature matched once)
note: aec gate  relocated 0xa632c -> 0xaa99c (build differs, signature matched once)
```

CAF 补丁（`patch_x8u_caf_sat.py`）在新库上两个站点逐字节一致，无需重定位。

## 3. 新 payload 内容

`module/payload_pkj110_201/`（含 `baseline.json` 记录来源指纹）：

| 文件 | 作用 | md5 |
|---|---|---|
| `lib64/libextensionlayer.so` | 专业视频 ISZ（模式闸门 + AEC 闸门） | `47db6163…` |
| `lib64/com.qti.stats.caflogicaldriver.so` | 2X→3X/6X 裁剪减法下溢饱和，避免 ubsan abort 把 provider 打死 | `8735f4d4…` |

**不再包含 `CameraHWConfiguration.config`**：那个 config 只对它自己那一代 HAL 有效，
在 X8U 上 `[InsensorZoom]` 行由 `AECStatsProcessor` 消费，而 X8U 从不实例化它
（见 `tools/patch_x8u_ext_isz.py` 头部）——真正让 ISZ 生效的是上面那个库补丁。
把旧 config 挂到 201 上就是这次崩 provider 的原因，所以新基线只挂库。

## 4. 还差什么（需要一台 17.0.1.201 机器）

`17.0.1.201` 的固件包只有 `payload.bin`，分区是 EROFS（lz4 + big pcluster）。
`tools/ota_payload_extract.py` 能正确解出各分区镜像（与官方 `payload-dumper-go`
产出的镜像逐字节一致），但 `tools/erofs_extract.py` 在「大文件最后一个 pcluster」
上仍会失败：`CameraHWConfiguration.config` 尾部 ~17KB、`oplus_eis_camera.vcfg`
尾部 ~175KB、`OplusCamera.apk` 里 155 个簇解不出来（zip 中央目录因此在尾部损坏）。
小文件（1.7MB 的 `libextensionlayer.so`、226KB 的 `caflogicaldriver.so`）完好，
所以上面两个 payload 是可信的；被截断的三个文件一律**没有塞进模块**。

在 201 机器上取原件（不装模块也能跑）。一条命令版：

```bash
bash tools/collect_after_201.sh            # 含 164MiB 的 APK
bash tools/collect_after_201.sh --no-apk   # 只拉配置 + 库指纹
```

它只读（不 push / 不挂载 / 不 force-stop），会把 `CameraHWConfiguration.config`、
`oplus_eis_camera.vcfg`、`OplusCamera.apk` 拉到 `work/ota201_pull/`，同时打出
三个相机库的 md5 与本地体检结论（config 是否含 NUL、vcfg 是否合法 JSON、apk 是否
可解 zip）。手工等价命令：

```bash
adb shell su -c 'cp /odm/etc/camera/CameraHWConfiguration.config /sdcard/'
adb shell su -c 'cp /odm/etc/camera/oplus_eis_camera.vcfg      /sdcard/'
adb shell su -c 'cp /my_product/app/OplusCamera/OplusCamera.apk /sdcard/'
adb shell dumpsys package com.oplus.camera | grep -m2 -E 'versionName|versionCode'
```

> APK 的真实路径是 `/my_product/app/OplusCamera/`（`/product` 是它的一部分挂载），
> 从固件镜像里也确认在 `my_product.img`。

拿到之后：

```bash
python tools/build_pkj110_payload.py --gen 17.0.1.201 \
    --odm-lib stock/libextensionlayer.so --caf-lib stock/caflogicaldriver.so \
    --hw-config stock/CameraHWConfiguration.config --eis-vcfg stock/oplus_eis_camera.vcfg \
    --out module/payload_pkj110_201
```

脚本会拒绝含 NUL 的 config / 非法 JSON 的 vcfg（即被截断的版本），
并通过 `--hw-config` 存在与否决定是否把 config 打进 payload。

相机 App 版本同样未知：201 上 `com.oplus.camera` 仍是同一个包名，但 APK 从
`/my_product/app/OplusCamera/OplusCamera.apk`（164MiB）分发；拿到 APK 后需要重跑
`app/src/main/assets/symbols.json` 的字符串锚点校验，再决定是否补 hook。

## 5. 已知行为变化

- 17.0.1.201 上：ISZ 只挂库，config 行不生效；若实测「ISZ 开了但不切 sensor 裁剪」，
  说明新 HAL 仍需要 config 行，那时必须用 201 的原件重新生成（见上）。
- `persist.omnicam.sdrbt`（libAlgoInterface 的 SDR BasicTone 实验）在 201 上被
  直接忽略，因为那份补丁的偏移属于 17.0.0.x。
- `oplus_eis_camera.vcfg`（6X 预览变形修复）在 201 上没有 payload，会被跳过并记一行日志。
