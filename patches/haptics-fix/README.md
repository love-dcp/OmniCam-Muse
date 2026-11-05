# OmniCam 冲突修复（振动增强）

给「装过 OmniCam Muse 之后，欧加真马达振动增强【复生版】报『挂载失败』、振感变弱」的人用。

## 症状

- 振动模块的控制台页面状态卡是红的「挂载失败」；
- 刷入 OmniCam Muse 之前振感明显，之后变回原厂那种；
- 振动模块本身没问题，重装它也没用（因为它不是没装上，是它的库被别人的挂载动作带走了）。

## 原因

这类模块（mountify 等元模块）给每个分区目录只建**一块** overlay，内容是**所有模块的
`system/<分区>/` 合并结果**。`/odm/lib64` 就是一块。

OmniCam Muse 在 r155 及以前的 X8U（PKJ110）分支里，为了让相机用自己的后端库，
执行了 `umount -l /odm/lib64` —— 那是**整块**卸，不是只卸自己的文件。于是同一块里
振动模块那个改过路径的 `liboplusvibratoreffect.so` 一起没了，它读不到效果表，
控制台按 md5 一比就是「挂载失败」。

更麻烦的是元模块跑完会把自己临时的 workspace 摘掉（`/mnt/vendor/mountify` 不可达），
所以事后既不能重挂 overlay，也不能靠删文件只去掉别人的那部分。只能按文件补。

## 这个补丁做什么

扫 `/data/adb/modules/*/system/odm/lib64/*`，逐个和 `/odm/lib64/` 里的同名文件比 md5，
不一致就用 `mount --bind` 把模块那一份顶上去，SELinux 标签照抄被替换的原厂文件；
如果换的是振动库，再重启一次 `vendor.oplus.vibrator` 让 HAL 重新加载。

- **不写任何系统分区**，只做 bind 挂载，重启即失效，删模块也不会有残留文件。
- **幂等**：已经正常的文件直接跳过，可以反复执行。
- 装上就生效（安装脚本里跑一遍），也可以随时在 KernelSU 里点「操作」再执行。

## 用法

1. KernelSU / 类似的管理器 → 模块 → 从本地安装 → 选这个 zip；
2. 安装界面会直接打印结果，看到 `+ 已修复 /odm/lib64/liboplusvibratoreffect.so ← OPlusHapticsEnhancer` 即成功；
3. 回到振动模块的控制台看一眼，应该变成「挂载成功」；
4. 不放心就点一下本模块的「操作」再跑一次，输出会和日志 `/data/local/tmp/omnicam_haptics_fix.log` 一致。

## 如果没修好

看日志最后两行：

- `没有需要修的东西` —— 说明对方的库不在 `system/odm/lib64/`（换了目录结构或用了别的挂载方式），
  把振动模块自己的 `module_install.log` 发我；
- `修复失败 ... bind 被拒绝` —— 把 `/data/local/tmp/omnicam_haptics_fix.log` 发我。

## 根治

OmniCam Muse r156 起不再整块卸载 `/odm/lib64`，并且会主动把别的模块的库补回来。
装 r156 或更新版本之后，这个补丁就不再需要了。
