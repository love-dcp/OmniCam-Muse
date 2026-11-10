# OmniCam Muse 模块

目标设备：PMA110 / Find X9 Ultra，相机 7.006.103。KernelSU 模块（ID `ricoh_pop_x9u`）配一个 LSPosed APK `omnicam-muse-<版本>.apk`，包名 `local.jiege.camera`。LSPosed 作用域：系统框架、相机、相册。

## 功能

- 理光 GR：GR 模式、影调、焦距条、快照对焦、相机与相册水印。
- POP：复古相机模式及资源、高像素；滑杆进成片，成片走 Ultra HDR。
- X10：清透、琥珀滤镜，调色盘及其成片调度（后置、人像、前置）。
- 大师模式：大师滤镜补全（徕卡 M9、富士 Rec.709 LUT，霓虹、和光），大师色彩（HNCS、Lumix、胶片、Canon、Darktable、Leica M9）。
- 自然模式、哈苏超清、普通模式 25MP 解禁、移轴模式、快门声。
- 设置在桌面或 LSPosed 中打开 APK。

各功能的原理与涉及文件见 `docs/MODULE.md`。

## 安装与回退

刷入模块并重启：刷入时模块安装包内 APK（`pm install -r -d`，结果写入模块目录的 `install.log`，签名不一致等失败不卸载已装应用），重启后相机、相册和系统框架都加载新版。同 ID 的模块原位更新。停用模块并重启即回退全部挂载。设置保存后相机下次读取即生效；快门声设置在铃声模式变化或重启相机后生效。

`post-fs-data.sh` 重建资源前，先延迟卸载本模块在 `/odm/etc/` 的配置与资源绑定，再从原厂目录合并资源，支持 KSU 软重启时重复执行。`/odm/lib64` 的库挂载由 Hybrid Mount 与 `post-mount.sh` 管理。

只停用模块会留下设置应用、相机/相册里的模块文件和偏好。要彻底清除，刷入仓库根目录构建出的 `OmniCam-Muse-Uninstall-<版本>-KSU.zip`：支持 KernelSU 热安装时刷完即清理并自删；否则执行模块 Action 或 `ksud soft-reboot`（X8U 不要硬重启）。细节见 `uninstaller/README.md`。

## PKJ110 原生补丁

三项由 `post-fs-data.sh` 开机绑定；EIS 与 CAF 补丁已在真机验证。ISZ 首版已通过关闭防抖的 1080p30、4K30 SDR 和 4K30 HDR 场景，但防抖开启与 60fps 尚未完成，因此仍保持 opt-in：

| 补丁 | payload | md5 | 作用 |
| --- | --- | --- | --- |
| `oplus_eis_camera.vcfg` | `payload_pkj110/etc/` | `48ab334b` | 修 6X 预览下半部竖条纹 |
| `com.qti.stats.caflogicaldriver.so` | `payload_pkj110/lib64/` | `69c781d3` | 堵 CAF UBSAN abort |
| `libextensionlayer.so` | `payload_pkj110/lib64/` | `6c3d0eb1` | 专业视频传感器内变焦（关闭防抖时支持 1080p30、4K30 SDR/HDR；**默认关闭**，见下方说明） |

EIS 配置的目标路径必须用 `vendor_configs_file` 上下文，库用 `vendor_file`，搞反了相机读不到配置会直接闪退。

**本机不能硬重启（会掉临时 root），所以 `post-fs-data.sh` 实际上从没跑过。** 更新模块后用这个脚本立即生效，无需重启：

```sh
su -c 'sh /data/adb/modules/ricoh_pop_x9u/apply-now.sh'
su -c 'am force-stop com.oplus.camera'
su -c 'kill $(pidof vendor.qti.camera.provider-service_64)'
```

ISZ 那一项**默认不装**，需要有意识地加 `--with-isz` 才会启用。单项回退：在模块目录 touch `disable_eis_config` / `disable_caf_ubsan_fix`，或直接 `umount -l <目标路径>`；要重新启用 ISZ 则 touch `enable_native_isz`。

## 已知问题

**视频 + 超级防抖 + 变焦时，从相册返回会卡住，久了闪退。**

这是 CHI/CamX 的原生缺陷，不是补丁引入的，配置层无法修。完整链路：应用返回时把**记忆的倍率随会话首批请求一起下发**，HAL 第一帧就要在 2.2–2.5X 上做多摄配置，`MCTFE` 的 IFE per-request 配置在 `End CONFIG` 之后约 50ms 直接失败（`ErrorCode:1047 CamxResultExtIFEEPRFail`），会话进 bad state；之后每次 StreamOn 都等满 2900ms 超时，重试约 16 轮后 chi 看门狗 `SIGABRT`。

**关键：不是管线缺失。** 2026-10-28 17:51 的对照实测里四颗镜头（`cam0/2/3/4`）全部初始化、管线齐全，仍然在 `End CONFIG` 后 50ms 报同一个 1047。此前"CHI 只建主摄管线"的说法是特定条件下的结果，不是原因。

已排除的方向：管线数量与对端缺失（四摄全建仍复现）、`is_izoom_at2x`（改成 0 只影响建了哪些管线，1047 照旧）、`[NoDeferCameraId]`（管的是 sensor 初始化，`deferSlaveInitMask=0x18` 只覆盖 tele/ultratele，主摄和超广角本来就不延时）、`pipeline_defer_count`（调 0 更差，已回滚）、StreamOn 超时值（改构造函数无效，运行时被覆盖；而且 1047 先于超时发生）、app 侧"记忆倍率"持久化键（`shared_prefs` 里没有，倍率只存进程内存）。

**规避方式（实测多次 100% 有效）：进相册前先把变焦拨回 1X，返回后再拨回去。** 1X 建会话时首帧没有倍率跳变，从干净状态起跑，不会触发该缺陷。

（若将来要做彻底修复，方向是在相机 app 进程内 hook Camera2 请求，把会话创建后的头几帧 `SCALER_CROP_REGION` 强制成全幅、之后再放行真实倍率；代价是画面会先广角起一下再跳过去。）

**专业视频 ISZ 先支持主摄 2X–3X，6X 保留原厂长焦链路。**

旧版只检查 `m_modeDescriptor`。运行日志（`pluginbase.cpp:5360`）证明**普通视频 + 超级防抖**也报告 descriptor `4`，因此旧白名单会误放行普通视频；sensor 切到裁剪模式后与 EIS 的全幅标定不匹配，最终可触发 MCTFE 失败和 provider `SIGABRT`。

当前候选库只允许 `m_modeDescriptor=30` 的专业视频，并在原生判断中接受 `2.0X–3.0X`（含 3X）；普通视频曾报告 descriptor `4`，4K30 回归分别报告 descriptor `2` 和 `6`，都会在 sensor 裁剪判断前被拒绝。原生上限同时保证从 2X / 3X 切到 6X 时不再返回 ISZ，6X 保留原厂长焦链路。

专业视频关闭防抖时，1080p30、4K30 SDR 和 4K30 HDR 的 2X/3X 都已实测进入 `SensorMode:13`；4K30 的 3X 裁剪区为 `4096x2560`。HDR 成片保持 3840×2160、30fps 的 10-bit HEVC、BT.2020/HLG，489 帧完整解码，记录过程没有 `RecoveryErr` 或 `ErrorCode:1047`。切到 6X 后 ISZ 关闭并回到原厂长焦链路，4K30 连续观察 30 秒未出现 MCTFE `1047`。普通视频 4K30 的 2X、3X 和 6X 隔离也已回归，没有进入 ISZ；标准 4K30 SDR 报告 descriptor `2` 时不会生效，这是预期的模式隔离。

专业视频自身开启防抖时，ISZ 与 EIS 的 FOV 仍可能冲突。当前建议在专业视频中关闭防抖使用；防抖开启和 60fps 都不能视为已经验证。

真机候选使用独立的双 bind 流程，不经过 `apply-now.sh`：先运行
`tools/x8u_isz_lab.ps1 -Action Status`，再运行 `-Action Apply`。每完成一组倍率用
`-Action Capture -Name <名称>` 保存截图、UI 和 ISZ 日志，结束时运行 `-Action Restore`
恢复原厂并检查无残留挂载。完整矩阵和判定标准见 `docs/X8U_ISZ_TEST.md`。

