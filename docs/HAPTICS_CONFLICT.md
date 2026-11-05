# 与「欧加真马达振动增强【复生版】」的挂载冲突（r156 已修）

一句话：**我们曾经在 PKJ110 上 `umount -l /odm/lib64`，而 mountify 给所有模块只建一块 `/odm/lib64`
overlay，于是把振动模块那个改过的 `liboplusvibratoreffect.so` 一起卸掉了。** 现在改成「不碰 overlay，
缺什么补什么」。

## 1. 现象

装 OmniCam Muse 之后，欧加真马达振动增强的网页控制台报 **挂载失败**，振感增强失效。
它自己的状态检查（`webroot/status_check.sh`）判据是两个文件的 md5：

```
/odm/lib64/liboplusvibratoreffect.so                          b0e7b073e05c703746aea7c71b11b7b6   ← 原厂
/data/adb/modules/OPlusHapticsEnhancer/system/odm/lib64/...   7c8f5a410cbd1a6cbe780e53ce87c448   ← 模块
```

不一致 → `{"ok":false,"status":"mismatch","message":"..."}` → 前端显示「挂载失败」。

## 2. 振动模块是怎么工作的

1. 安装时十六进制修补 `liboplusvibratoreffect.so`：把内嵌路径
   `/odm/etc/vibrator/vibrator_effect.json` 换成 `/data/odm/etc/gs2/vibrator_effect.json`
   （`module_install.log` 里那行 `Patch @ 0x0000B350`）。这样效果表就能随便改，不用动只读分区。
2. 效果表写在 `/data/odm/etc/gs2/vibrator_effect.json`（13 KB，已确认存在）。
3. 该库放在 `system/odm/lib64/`，**挂载交给元模块**（模块根目录有 `mode_meta` 标记，
   它的 `dispatch.sh` 见到这个标记就直接 exit，自己一个 bind 都不做）。
4. `service.sh` 在开机解锁后 `stop/start vendor.oplus.vibrator`，让 HAL 重新加载这个库。

所以它完全依赖 mountify 把 `system/odm/lib64/liboplusvibratoreffect.so` 送到 `/odm/lib64/`。

## 3. 我们为什么会拆掉它

`module/post-mount.sh` 的 PKJ110 分支原本这样写：

```sh
n=0
while grep -q " /odm/lib64 " /proc/self/mounts; do
    umount -l /odm/lib64 || umount /odm/lib64 || break
    n=$((n+1)); [ "$n" -ge 4 ] && break
done
```

目的是阻止 X9U 的 `libBasicTonePhoto.so` / `libAlgoInterface.so` / `libmsnativefilter.so`
以**原厂同名**落到 X8U 上（那一对库在 X8U 上会黑帧 / 主色失效）。

三条事实叠在一起才成了灾难：

- **mountify 的 overlay 是「所有模块合并成一块」**，不是每模块一块。`/odm/lib64` 的
  `lowerdir = workspace : 原厂`，workspace 里同时有我们的 `libBasicTonePhotoX9/X10.so`
  和振动模块的 `liboplusvibratoreffect.so`。卸 overlay = 卸所有人的。
- **mountify 跑完就把自己的 tmpfs workspace 摘了**（`metamount.sh` 结尾
  `busybox umount -l "$MNT_FOLDER"`），所以事后 `/mnt/vendor/mountify` 不可达，
  既不能重挂、也不能靠删 workspace 里的文件来「只去掉自己的部分」。这一拆就是永久，
  直到下一次开机。
- **本机只软重启**，而软重启不重建 overlay（metamount 认为已经挂过了），
  于是振动模块会一直坏着。

## 4. 为什么现在可以不拆

拆 overlay 的唯一理由是「同名覆盖」。当前 `system/odm/lib64/` 里只剩
`libBasicTonePhotoX9.so` 和 `libBasicTonePhotoX10.so` —— **都是用它们自己的名字**，
而 overlay 只能新增文件、不能改名，所以它根本盖不到 `libBasicTonePhoto.so`。
拆的理由已经不存在了，于是：

- **不再 umount `/odm/lib64`**（`post-mount.sh` 里那段整块删掉，改成注释说明）。
- 原来那条「软重启后旧 overlay 冻结」的兜底，本来就由各处**按文件**的
  `umount /odm/lib64/libBasicTonePhoto.so`、`umount /odm/lib64/libAlgoInterface.so` 承担，
  不依赖整块卸载。

## 5. 加了什么：`restore_foreign_lib64`

`post-mount.sh` 末尾新增一步，两个机型都跑：

```
扫 /data/adb/modules/*/system/odm/lib64/*
  跳过自己、跳过 disable/remove 的模块
  目标 /odm/lib64/<同名> 存在才处理
  md5 已经一致 → 说明 overlay 或别人已经挂好了，跳过
  不一致 → chown/chmod/chcon(--reference 目标文件) 后 mount --bind 过去
```

含义：**不管 mountify 有没有把 overlay 建起来，别人的库都必须到位。** 它同时解决两种情况：

| 场景 | 之前 | 现在 |
|---|---|---|
| overlay 正常 | 正常 | md5 一致，跳过 |
| overlay 丢了（拆过 / mountify 失败 / 软重启冻结） | 别人的库消失 | 逐文件 bind 补回 |

`module/x8u-repair.sh` 里同样的道理：那段「拆 overlay」保留（它本来就是手动修复工具），
但后面加了 **B2 段**，拆完立刻按同样规则把别的模块的库补回来。

## 6. 上机验证（2026-11-05 r155 → r156）

```
$ sh /data/adb/modules/ricoh_pop_x9u/post-mount.sh
begin profile=PKJ110
lib bind ok /odm/lib64/libBasicTonePhoto.so (591552 B)
PKJ110: palette backend X10 bound (...)
foreign lib64 restored: /odm/lib64/liboplusvibratoreffect.so (OPlusHapticsEnhancer)
end profile=PKJ110

$ md5sum /odm/lib64/liboplusvibratoreffect.so
7c8f5a410cbd1a6cbe780e53ce87c448      ← 与模块一致
$ ls -lZ /odm/lib64/liboplusvibratoreffect.so
-rw-r--r-- 1 root root u:object_r:vendor_file:s0 233384

$ setprop ctl.restart vendor.oplus.vibrator     # 11554 → 26100
$ sh .../webroot/status_check.sh
{"success":true,"ok":true,"status":"ok","message":"OK: overlay matches system and metadata OK"}
```

标签用 `chcon --reference=<被替换的原厂文件>` 复制，正好是 `vendor_file`，
和振动模块自己期望的 `WANT_CTX` 一致。

## 7. 以后加模块时注意

- **别整块卸载任何「多模块共用」的 overlay 目录**（`/odm/lib64`、`/system/lib64`、
  `/my_product/app` …）。mountify 是按**分区目录**合并的，不是按模块。
- 要排除自己的某个文件，只有两条正路：
  1. 别用原厂同名放进 `system/`（overlay 不会改名，就不会覆盖）；
  2. 覆盖后用**按文件**的 bind/umount 修正，绝不动父目录。
- 想确认某个库到底有没有挂上：`md5sum /odm/lib64/<lib> 模块副本`，
  别只看 `ls` 大小 —— overlay 里看到的永远是「合并结果」，看不出是谁提供的。

## 8. 独立修复补丁（给已经被弄坏的人）

源码在 `patches/haptics-fix/`，产物 `OmniCam-VibrationFix-1.0.zip`（约 6 KB）。
它**不依赖 OmniCam Muse**，也不需要 r156，单独装着就能修。

| 文件 | 作用 |
|---|---|
| `apply.sh` | 核心：扫 `modules/*/system/odm/lib64/*`，与 `/odm/lib64/` 同名文件比 md5，不一致就 bind；换的是振动库就 `ctl.restart vendor.oplus.vibrator` |
| `customize.sh` | 安装时立刻跑一遍（本机不重启，这是主要生效路径），结果回显在安装界面 |
| `service.sh` | 等 `sys.boot_completed` + 3 秒后再跑一遍（确保晚于元模块建 overlay） |
| `action.sh` | KernelSU 的「操作」按钮，随时手动重跑 |
| `module.prop` | `id=omnicam_haptics_fix` |

设计要点：

- **只用 bind，不写只读分区**；重启即失效，卸载模块不留残留。
- **幂等**：md5 一致就跳过，可以反复点。
- **标签照抄**：`chcon --reference=<被替换的原厂文件>`，实测得到 `vendor_file`，
  正好是振动模块自己期望的 `WANT_CTX`。
- 目标文件不存在时跳过并说明原因（只读分区不能新建挂载点）。

端到端验证（2026-11-05 17:50，先 `umount -l` 制造故障态）：

```
$ md5sum /odm/lib64/liboplusvibratoreffect.so
b0e7b073e05c703746aea7c71b11b7b6        ← 原厂，故障态
$ ksud module install OmniCam-VibrationFix-1.0.zip
- 正在把 /odm/lib64 里被卸掉的库补回来
  + 已修复 /odm/lib64/liboplusvibratoreffect.so  ←  OPlusHapticsEnhancer
  == 振动 HAL 已重启：vendor.oplus.vibrator  pid 26100 → 7099 ==
  == 结果：检查 1 个文件，修复 1 个，失败 0 个 ==
$ sh .../OPlusHapticsEnhancer/webroot/status_check.sh
{"success":true,"ok":true,"status":"ok","message":"OK: overlay matches system and metadata OK"}
```

第二次执行走幂等分支：

```
- liboplusvibratoreffect.so 已是 OPlusHapticsEnhancer 的版本，无需处理
== 结果：检查 1 个文件，修复 0 个，失败 0 个 ==
```
