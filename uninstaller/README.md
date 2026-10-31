# OmniCam Muse 卸载器

刷入式 KernelSU 模块，用来把 OmniCam Muse 装在系统里的东西清干净。

## 用法（X8U 优先热卸载）

### A. 热卸载：刷完即完成，不需要重启

1. 在 KernelSU 管理器中刷入 `OmniCam-Muse-Uninstall-1.4-KSU.zip`，或在终端执行：

   ```sh
   su -c 'ksud module install /sdcard/OmniCam-Muse-Uninstall-1.4-KSU.zip'
   ```

2. KernelSU 支持 hot install 时（安装脚本里 `KSU_HAS_HOT_INSTALL=true`），安装结束会自动执行模块里的 `hot-clean.sh`，完成清理并在 5 秒后删除卸载器自己。管理器会提示不需要重启。
3. 直接打开相机即可：相机和相册进程在刷入阶段已被强制停止，下次启动会加载原厂配置。

热卸载只处理“卸载”，不需要把任何模块热挂载进 `/odm`；它做的是热卸载：`umount` / `umount -l` 摘掉模块的 bind 和 `/odm/lib64` 覆盖层，再删除文件、卸载 APK。

### B. 没走成热安装时

如果 KernelSU 版本较旧、热安装被安全模式/待生效的 metamodule 阻止，模块会正常安装并在列表里出现。任选一种收尾：

```sh
# 方式 1：执行模块 Action，重跑清理并自删（推荐，无需重启）
su -c 'ksud module action omnicam-muse-uninstaller'

# 方式 2：软重启。会执行 post-fs-data/service，保留内核和临时 root
su -c 'ksud soft-reboot'
```

KernelSU 管理器里的 Action 按钮等价于方式 1。

**X8U 千万不要用电源键重启或任何硬重启**：那会重启内核并掉临时 root。`ksud soft-reboot` 只做 `stop` / `start`，不重启内核；KernelSU 的 `soft_reboot` 会依次执行各模块的 `post-fs-data`、`start`、`service`，正是本卸载器需要的收尾阶段。

### 关于“KSU 热挂载”

- KernelSU 没有给任意模块的 `system/` 树做通用热挂载的命令；它的 hot install 只是“安装后立刻把暂存目录换到 live 并执行一个脚本”，分区文件仍然下次启动才挂。本卸载器利用的是这个脚本时机，不需要挂载。
- `ksud kernel umount add` 是 KernelSU 的“卸载列表”（对指定进程隐藏挂载），不是真正的 `umount`，不要拿它代替热卸载。
- `ksud module uninstall <id>` 只是给模块打 `remove` 标记，下次启动才删；要立即收尾请用 `module action` 或重新刷 1.4 的热安装。
- 相机/相册进程已经被 `am force-stop`，所以相机侧无需重启。唯一还需要软重启才能彻底清掉的是 **system_server 里已经加载的 LSPosed 钩子**；如果只关心相机，热卸载后可以直接用，想要系统框架也完全回到原厂再执行一次 `ksud soft-reboot`。

## 1.4 修复

PKJ110 上模块 bind 在 `/proc/mounts` 里 source 会显示成 `/dev/block/dm-37`，真正来源只在 `/proc/self/mountinfo` 的 root 字段（`/adb/modules/...`、`/adb/omnicam_muse/...`）。1.3 及以前只按 `/proc/mounts` 匹配，漏掉这些 bind 后会删掉合并源，留下**空目录覆盖原厂 LUT 目录**，表现为大师/XPAN 闪退、照片/视频滤镜消失。

1.4 现在：

- 同时解析 mountinfo 的 root 字段，识别 `/adb/modules/ricoh_pop_x9u/...`、`/adb/omnicam_muse/...` 等挂载；
- 先摘 `/odm/lib64` overlay，再执行第二次 unmount，处理被 overlay 遮住的文件 bind；
- `remove_merged_trees` 也检查 mountinfo，避免在 bind 仍存活时删除合并源。

## 分区红线（X8U 临时 root）

本卸载器**不写任何分区**，也没有 `mount` / `remount` / `dd` / `flash` / `mkfs` 之类的命令。具体边界：

- **不写** `/odm`、`/system`、`/vendor`、`/product`、`/system_ext`、`/boot`、`/init_boot`、`/vendor_boot`、`/metadata`，也不碰 `/dev/block/*` 或任何块设备。
- `/odm` 上只执行 `umount`，摘掉模块自己创建的 bind 和 `/odm/lib64` 的 overlay。`umount` 只改内核挂载表，不改分区内容；而且**绝不会 `umount /odm` 本身**。
- 所有删除都走 `safe_rm_rf` / `safe_rm_f` 白名单：只允许 `/data/**`、`/sdcard/**`、`/storage/emulated/**`，并明确拒绝这些根目录本身。
- mountify 的 `/mnt/vendor/mountify` 是内存工作区。只有确认 `/mnt/vendor` 或 `/mnt/vendor/mountify` 的挂载类型是 `tmpfs` 时才清理；一旦发现是真实分区挂载，直接跳过并打印警告。
- `persist.omnicam.*` 通过 `resetprop` 删除，只改 property store（`/data/property`），不碰分区。
- 如果 `/odm/lib64` 的挂载类型不是 `overlay`/`tmpfs`（例如真实分区挂载），卸载器会跳过 detach，避免把挂载操作变成分区操作。
- 卸载必须摘掉模块自己创建的 bind，这只能是 `umount`；`umount` 不写 `/odm`。如果不接受任何 `umount`，就无法在不硬重启的前提下让相机回到原厂（软重启的 post-fs-data 同样要 `umount` 这些 bind）。

因此，热卸载路径也是安全的：它只热卸载模块的 bind，再删除 `/data` 和 `/sdcard` 里的数据，不写任何系统分区。

## 会清掉什么

| 位置 | 内容 |
|---|---|
| `/data/adb/modules/ricoh_pop_x9u`、`/data/adb/modules_update/ricoh_pop_x9u` | KSU 模块本体 |
| `/data/adb/omnicam_muse`、`/data/adb/ricoh_pop_x9u` | 合并挂载的临时源目录 |
| `local.jiege.camera` | LSPosed 设置应用及其数据、ART profile |
| LSPosed 模块偏好 | `/data/misc/*/prefs/local.jiege.camera`（大师滤镜配置、导入的 LUT 等） |
| 相机 `files/jiege` | 大师色彩、导入 LUT、大师预设、理光 GR、25MP/200MP、XPAN 水印备份等 |
| 相机 `files/ricoh_gr`、`files/pop_port`、`files/mode_limit_watermark` | GR LMT、POP 资源、GR 水印样式（只删 realme GR 资源，保留目录中的其他文件） |
| 相机 `files/fisheye_lens`、`files/fisheye_capture`、`pop_heif.txt` | 鱼眼与诊断状态 |
| 相机 `cache/jiege_*`、`cache/xpan_live` | 符号缓存、GR 快门声、XPAN 实况临时文件 |
| 相册 `files/ricoh_gr_styles`、`files/jiege_ipu`、`files/jiege` | GR 水印样式、调色盘 SDK、端口状态 |
| 相机/相册 `shared_prefs` | `pref_jiege_*`、`*_jiege_*`、GR/XPAN 水印槽位、`pref_camera_mode_key`、`pref_snap_switch_key`；相机 `override_config_data.xml` 中的 9 个注入键；相册的 GR 开关键删除（默认 `false`） |
| `persist.omnicam.*` | 所有模块属性（柔光、自然模式、鱼眼、超清、诊断开关等） |
| `/sdcard/Android/data/com.oplus.camera/files/muse` | RAW / ccmVec / HDRTransform 转储 |
| `/sdcard/omnicam-diag-*`、`/data/local/tmp/omnicam-*`、`x8u-*` | 诊断与修复脚本产物 |
| `/odm/lib64` 的 mountify 覆盖层、`/mnt/vendor/mountify/odm/lib64` 内的模块库 | X9/X10 BasicTone 后端等；覆盖层只 `umount`，工作区仅限 tmpfs 才删 |
| 相机 `databases/mode_data.db` | 用模块特性标志建出的模式库，删除后由相机按原厂配置重建 |

**不会删除**：照片、视频、相册媒体、相机/相册应用本身、用户自己放在别处的文件。

## 执行阶段

- `customize.sh`：刷入时手机已解锁，负责卸载 APK、停止相机/相册、改偏好、清私有目录，并请求 KernelSU 热安装。
- `hot-clean.sh`：热安装成功时在安装结束后立即运行，做解锁后的兜底清理，然后自删；这是 X8U 不重启的主路径。
- `action.sh`：热安装没走成时手动执行，等于把 `customize.sh` + 收尾再跑一遍，然后自删。
- `post-fs-data.sh` / `service.sh`：如果模块留到了下次启动，在软重启的 post-fs-data 和 service 阶段完成同样的清理并自删；PKJ110 内核里残留的旧 bind/overlay 也在这里摘掉。

所有阶段都可重复执行；任何一步失败，下一步会再试。

## 注意

- 相机模式布局会恢复默认（`mode_data.db` 被删除后重建），这是清除模块模式列表（GR/POP/移轴）的代价。
- 如果设置应用因系统限制没卸载成功，卸载器会保留自己，可再次执行 Action 或软重启后重试；也可以在系统设置里手动卸载 `OmniCam Muse` 后再执行 Action。
- 如果 KernelSU 的“超级用户”列表里还留着 `OmniCam Muse`，请在管理器里手动删除该授权。
- LSPosed 的模块作用域记录由 LSPosed 在 APK 卸载后自行清理；若界面仍显示旧条目，执行一次 `ksud soft-reboot` 即可。
- 如果同时装有其他通过 mountify 覆盖 `/odm/lib64` 的模块，热卸载会摘掉这一层覆盖；再执行一次 `ksud soft-reboot` 即可让其他模块重新挂载。
- 手动运行过 `x8u-repair.sh` / `x8u-config-revert.sh` 的话，`/sdcard` 下可能有 `x8u-pristine-config`、`x8u-startup-db`、`OplusCamera-stock.apk` 等原始配置备份。卸载器不删除这些备份，确认不需要后可自行删除。

## 构建

```powershell
.\build-uninstaller.ps1
```

输出 `OmniCam-Muse-Uninstall-<版本>-KSU.zip`。也可以直接调用：

```powershell
python tools\zip_module.py uninstaller OmniCam-Muse-Uninstall-1.4-KSU.zip
```
