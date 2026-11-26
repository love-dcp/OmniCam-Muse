# GR 模式「预览与成片颜色差很多」根因与修法（2026-11-25，相机 7.006.317）

## 现象

GR 模式选 U1（用户预设「理光负片」）后：

- 取景：中性、克制的胶片感，蓝通道完整（木桌偏暖棕，罐体金色正常）。
- 成片：饱和度暴涨（mean sat 0.47 → 0.64）、蓝通道被大幅压低（白墙 `[172,209,220] → [145,180,146]`）、
  暗部整体偏青、整体像「多套了一层浓look」。

用户提供：`Screenshot_2026-11-25-21-18-09-09.jpg`（预览）、`IMG20261125211755.jpg`（成片）。
本地复现文件：`work/gr_preview.jpg`、`work/gr_capture.jpg`、`work/gr_prev_u1.png`、`work/gr_cap_u1.jpg`。

## 根因

`gr_basictone.cpp` 用 APS 设置 `gr_effect_size == 63` 判定「这一帧是 GR 帧」，据此把 BasicTone 的
LMT 目录换成 `files/ricoh_gr/lmt`（GR 树）。而读取该设置要靠 `android::getSettingInt`——它没有导出，
只能按**文件偏移 + 函数序言指纹**取。指纹表里只有两个构建：

```
PMA110/7.006.77  offset 0x150cdd0
PKJ110/7.006.94  offset 0x13f46d4
```

本机是 **7.006.317**，偏移早已不同，`resolveSettingInt` 指纹不匹配 → 运行日志：

```
RicohGrPort: getSettingInt: no known layout matched, GR hook disabled
RicohGrPort: getSettingInt unavailable: GR frame detection disabled
```

于是 `isGrFrame()` 恒 false。而 GR 在本移植里是**跑在大师（professional）管线上的**（`GrModeHooks`
把 `gr` 入口换成 `professional`），GR 帧的 capture mode 与大师一样是 33，于是：

```
profile.master = !gr && mode == 33   → true
masterColorLook(...) 命中            → profile = kMaster
RicohGrPort: BasicTone profile=master-colour path=.../mastercolor/live/t04151_...
```

**成片被大师色彩的 LMT 上色，预览走的是 GR 树**（`CombineLut init -> GR tree`），两者自然完全不同。
数据侧对照：预览用 GR 树 + 面板参数，成片用「大师色彩 look（用户选的那支，接近理光负片风格）」。

## 修法（r553）

两道保险：

1. **补 7.006.317 的 `getSettingInt` 偏移**（`app/src/main/cpp/gr_basictone.cpp`）：
   ```
   paciasp; sub sp, sp, #0x80; stp x29,x30,[sp,#0x50]; str x21,[sp,#0x60]
   offset 0x1420188
   ```
   定位方式：反汇编导出的 `getLmtParamsV1`（317 在 `0x1e0af10`），找「读 `[frame + 0x8110]`、
   把栈上 24 字节 key 与 0 作为 fallback 传进去」的那次 `bl`——即 `0x1e0c08c: bl 0x1420188`。
   该函数体是「拷贝 key → map 查找（虚表 +0x38）→ 未命中返回 fallback → 命中 parse int」，
   与 PMA110/PKJ110 老表的语义一致。
2. **另一条不依赖偏移的判据**：Java 侧把相机自己的 `GrSupport.isGrMode()` 写成
   `files/jiege/gr_mode`（`GrEffectTransport.publishMode()`，只在变化时写一次；
   `Lut3d.load`、`captureStage` 与启动时各刷一次）。native 侧 `grFlagActive()` 400ms 缓存读它，
   `isGrFrame()` 优先用它。这样即使换到 7.006.352 或以后某个构建、`gr_effect_size` 根本不带，
   GR 成片也不会再掉进大师色彩路径。

顺带：`applyLmtPath` 里「无 profile 的载入是否属于 GR」的判断也加了同一标志
（原来只认 capture mode 37，而本移植里 GR 用的是 33）。

## 待验证（设备已被取走，r553 只构建未装机）

装机后 GR 模式下拍一张，日志应出现：

```
RicohGrPort: getSettingInt layout=PKJ110/7.006.317 offset=0x1420188
RicohGrPort: GR mode flag -> true
RicohGrPort: BasicTone profile=GR path=/data/user/0/com.oplus.camera/files/ricoh_gr/lmt
```

而**不应**再出现 `BasicTone profile=master-colour`。然后对比同场景预览截图与成片：
色调应一致（成片仅多出颗粒/暗角等 GR 效果本身的差别）。

## 复现/排查工具

- 抓 native 日志必须带上 `RicohGrPort` 标签（`cap537.sh` 只抓 `LSPosedFramework`，看不到
  `gr_effect_size` / `capture mode` / `BasicTone profile` 这些 `__android_log_print`）：
  `logcat -f /data/local/tmp/gr_trace.log -r 4096 -n 2 RicohGrPort:V MasterColor:V LSPosedFramework:V *:S`
- 改机版本后重找偏移：
  1. `llvm-nm -D --defined-only libAlgoInterface.so | grep getLmtParamsV1`
  2. `llvm-objdump -d --start-address=... --stop-address=...`，找 `ldr x0, [x20,#0x8110]` 之后的 `bl`
  3. 取目标地址前 16 字节做序言，地址即文件偏移（`.text` 的 vaddr == off）。
