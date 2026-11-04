# 设备操作与排查笔记

Find X9 Ultra（PMA110，骁龙，KernelSU + LSPosed）上的设备操作与排查方法。每条都来自实际调试，标明的“已验证”是日志或反汇编直接看到的。

## 设备环境与坑（已验证）

- **`adb shell su -c 'a; b; c'` 只有第一条是 root**，后面的是 shell 身份。多条命令要写成脚本推上去再 `su -c 'sh /data/local/tmp/x.sh'`。`ls` 是 toybox，不支持 `--time-style`。
- 重启后 adb 授权常常丢失，需要用户解锁并重新允许。`service.sh` 要等用户解锁、应用数据目录出现后才执行。
- **临时 bind 挂载**（不动模块目录，重启即恢复）：
  1. 把文件推到 `/data/local/tmp/`，`chown root:root`、`chmod 644`；
  2. `toybox chcon <目标现有标签> <文件>`（`libAlgoInterface.so` 用 `u:object_r:same_process_hal_file:s0`，`/odm/etc/camera/config/*` 用 `u:object_r:vendor_configs_file:s0`）；
  3. `am force-stop com.oplus.camera`；
  4. `/data/adb/ksu/bin/busybox mount --bind <文件> <目标>`（`toybox mount --bind` 不认）；
  5. 用 `md5sum <目标>` 确认。
- **挂载会传播到模块路径**：在 `/data/adb/modules/ricoh_pop_x9u/...` 读出来的也是临时文件的 md5，所以挂载期间别拿模块路径的文件当「原件」备份。
- **卸载只卸一层**：模块自己在 `/odm/...` 上有一层 bind（源是模块目录里的文件）。`umount` 一次只卸最上面一层。多卸一次会把模块自己的绑定也卸掉，表现为 `/odm` 的 md5 变回原厂文件；这时要用 `busybox mount --bind <模块目录文件> <目标>` 重新绑回去。
- **替换模块文件**：写 `<file>.new`，设好 owner/权限/标签，再 `mv` 覆盖（新 inode）。覆盖之后，原来的 bind 指向已删除的 inode，在它上面再叠加会报 `No such file or directory`；要先 `umount` 那一层，再把模块里的新文件绑上去（这就是模块开机时做的事）。
- 原厂库：想读被模块遮住的 `/odm` 原文件，用 `mount -o bind /odm <临时目录>`（非递归 bind，看不到上面那层文件级挂载），读完 `umount`。`nsenter -t 1 -m`、`unshare` 被拒，`mount -t erofs` 失败。
- 别在手机上乱按唤醒键加启动相机（曾误触系统语音助手）。日志和截图里有用户的通知、聊天内容，不要引用也不要放进仓库。

## 安装即生效（本机不能硬重启，r147 起）

本机 root 来自 CVE 漏洞，硬重启就掉，所以「装完重启一下」这条路不存在。KernelSU 装模块的标准流程恰好依赖重启：解包到 `/data/adb/modules_update/<id>`、写 `update` 标志，开机时才替换进 `/data/adb/modules/<id>` 并跑 `post-fs-data.sh` / `service.sh`。不重启 = 装了等于没装。

r147 把这条链在安装时自己走完，分三件事：

1. **交换目录**（`customize.sh` → `/data/local/tmp/omnicam_finish.sh`，`setsid` 后台）
   脚本不能搬自己（正在被 `sh` 逐行读），所以写到 tmp 再后台跑。**先 `umount -l` 掉模块自己的 8 条 bind**——否则 `rm -rf` 会卡在 `Device or resource busy`（`caflogicaldriver.so` 被厂商进程打开），目录删不干净，后面的 `mv` 会把新树**嵌套**进旧目录（`/data/adb/modules/<id>/<id>/`），这个坑实际踩过。`rm -rf` 后仍非空就 `mv` 到 `<id>.old.$$` 再删，最后 `mv modules_update -> modules` 并删 `update` 标志。
2. **自愈**（`selfheal.sh`）：按开机真实顺序跑 `post-fs-data.sh` → `post-mount.sh` → `service.sh`，再删 `mode_data.db`、重启 camera provider。见下条。
3. **模式表**：见「模式面板是库优先的」——重装后 `理光 GR / 移轴 / POP` 消失就是这张表在 prefs 还没写时被相机重建了。

`selfheal.sh` 也挂在 `MODULE_HOT_RUN_SCRIPT` 上（mountify 会在它自己的目录交换**之后**执行该变量指定的脚本），并且 `action.sh` 第一下就是跑它（3 分钟内再点一次才是完全卸载）。三条路都幂等。

**判断装完到底有没有生效**，看 `/data/adb/modules/<id>/selfheal.log`，末尾必须是 8 条 `ok`：

```
ok /odm/etc/camera/meishe_lut ... ok /odm/etc/camera/config/camera_unit_feature_config.protobuf
ok /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so
pop_port=90  jiege=408  ricoh_gr=77  merged=470   /   9 keys staged
```

有一行 `MISSING` 就是没修好，别急着测功能。

## 安装后必查的几项（重装会丢）

| 项 | 谁重建 | 丢了的现象 |
|---|---|---|
| 9 个 prefs 开关（`override_config_data.xml`） | `service.sh` | 理光 GR / POP / 移轴 / 60fps 全没有 |
| `databases/mode_data.db` 的 `retroCamera` / `tiltShift` 行 | 删表让相机重建 | 模式栏少 GR / 移轴，且**不会自己回来** |
| `/odm/etc/camera/config/*` 三条 bind | `post-fs-data.sh` | POP 决策、25MP、retro 分支全失效 |
| `files/{jiege,pop_port,ricoh_gr,mode_limit_watermark}` | `service.sh` | 大师色彩 / GR / POP 滤镜空 |
| `/data/adb/omnicam_muse/merged`（470 文件） | `post-fs-data.sh` | 大师滤镜组、色彩卡、Retro ini 空 |
| `system/odm` 或 `odm/` 下的 X9/X10 库 | `post-mount.sh` | `palettebt=1` 时参考不到；路径两处都要认（mountify 会把 `system/odm` 搬到 `odm/`）|

## 日志与崩溃现场

- 相机调试日志：`setprop oplus.autotest.camera.debug.forcelog true`（必须字符串 `true`，重启后失效）再 `am force-stop com.oplus.camera`；日志在 `/data/vendor/cam_alog/`（root 读）。库的日志只输出 ERROR 级，所以 INFO 级的算法内部日志看不到，只能间接判断。
- **判定一张图实际用了什么算法**：成片 JPEG 里 APS 元数据的 `"algo": [...]` 字符串，或日志里 `grep -a "doProcessImages()  \[for Debug\]"` 行的 `found algo:`。注意后者是 ERROR 级的打印，**可能比 JPEG 里的少**（移轴的 47 在日志里没有、在 JPEG 里有），以 JPEG 为准。
- **会话是否 Ultra HDR**：日志 `createNewSession, entity: CameraSessionEntity{... mbUltraHdrEnable: true, mbPreviewHdrTransformEnable: ...}`，同一行有 `mSurfaceControl=...producer.mode.<模式类>`。成片是否真带增益图：JPEG 里有没有 `hdrgm` 和 MPF 标记。
- 拍照算法清单（引擎收到的）：`beforeCapture()  params: {... capture_algo_list:[...]}` 和 `ApsCaptureDecision ... algoList for capture decision [i/n] id: name`。
- **崩溃现场**，按类型找：
  - HAL 进程（`vendor.qti.camera.provider-service`）：`adb logcat -b crash`、`/data/vendor/camera/OfflineLog_CrashLog_*`、`/data/tombstones`。HAL 的主动 abort 前一定有一条 `OLog` 错误，格式串在库的字符串表里，反汇编找 `adrp x0 … add x0 …` 对应的串能读到原因。
  - 应用进程：`adb logcat -b crash`（重启会清空），重启后还在的是 `/data/system/dropbox/system_app_crash@*.txt`。相机应用崩溃（Java 异常）不会产生 tombstone 或 HAL 崩溃记录，别因为这两处没有就断定没崩。
- 看相机应用代码（jadx 反编译）时名字是混淆的，但字符串常量没混淆：用 `com.oplus.…` 键名、模式名、日志里的类名做入口反查（例如 `OCAM_y2_BaseMode` ↔ `gm/y2.java`）。SDK（`ApsProcessor`、`PhotoMode` 等）在 `com.oplus.camera.unit.sdk.jar`，不在相机 APK 里，类名没混淆。
- 反汇编引擎库：用 NDK 29 自带的 LLVM 工具（`llvm-objdump`、`llvm-nm`、`llvm-readelf`、`llvm-mc`、`llvm-objcopy`）。库是 aarch64，`.text` 从 `0x1504000`；文件偏移与虚拟地址在 `.rodata`/`.text` 里相同。找字符串的引用：扫描 `adrp`+`add` 对，目标等于字符串地址。决策配置里的 `APS_ALGO_*` 名字不在库里以明文出现，所以不能靠 `strings` 判断某个名字是否合法。

## 工作方法

1. 先静态确定「被什么挡住」，再用临时 bind 挂载验证，再固化，最后真实重启复核。重启复核发现过临时挂载测不出的问题（移轴数据库降级，开机早期进程的行为）。
2. 判断一个算法有没有被调用，看 JPEG 里的 `algo` 清单；判断为什么没被调用，看会话标志、决策日志（`traverseRunAlgo()  [pass]/[fail] |____SINGLE_ALGO_*`）和反汇编。
3. 修改手机上的东西前先说明，改完记得清理：临时文件、日志、日志开关。
4. **柔光 A/B 先弃一张**：相机重启或长时间空闲后的第一张走 `4096×3072` 全尺寸，之后才是 `1280×960` 代理帧。四档都要落在代理帧上再比，否则量到的是 1280×960 上采样到 3072×4096 的损失（曾把 9% 的高频损失读成 64%）。跨族比较之前先看两张 JPEG 的字节数是否同族。
5. **重装模块前先存快照**：`sh tools/snap.sh before` / `sh tools/snap.sh after`，再 `python tools/snapshot_diff.py <before>.txt <after>.txt`。快照记录模块树、`/data/adb/omnicam_muse/merged`、相机 `files/pop_port`、`files/jiege`、`files/odm`、`shared_prefs` 的逐个 md5，外加现场 bind 挂载、`omnicam`/`persist.*camera` 属性、SELinux 标签；小的几个目录同时打成 tar 存在 `work/snapshot/<name>/`。重装后"说不清哪里变了"时，这一步能直接把差异指出来（见过属性残留、merged 树残留、某个 staging 文件没重建三种）。

