# O-Log2 在一加16 / Find X9 Ultra 固件中的实现调查

> 目标：确认「O-Log2」是否在一加16（PYB110 / ColorOS 17.0.1.201）与
> Find X9 Ultra（PMX110 / ColorOS 17.0.0.109）固件里有真实实现，
> 以及能否搬到 X8 Ultra（PKJ110 / ColorOS 17.0.0 / 相机 7.006.123）。
>
> 结论：**没有可移植的实现**。O-Log2 在两边相机 APK 里只是一组
> 「预埋未接线」的字符串与数组；三套固件在这块完全同源。
> 真正的能力在 HAL / ISP tuning 层，而 X8U 的 HAL 里没有任何 log2 标识。

---

## 1. O-Log2 是什么（官方口径）

2026-10-08 Blackmagic Camera for Android 3.5 的更新说明写明：新增
**OPPO O-Log2 录制**，支持 **OPPO Find X10 系列与一加16 系列**。

这有两个含义：

1. O-Log2 是 OPPO 给第三方相机 App 开放的一个 Log 录制档位，
   不只是自带相机的菜单项；
2. 支持名单里没有 Find X9 Ultra，更没有 X8 Ultra —— 它是随新一代
   SoC（SM8975 / 天玑 9500 级）一起下放的能力。

所以判断「能不能搬到 X8U」，等价于判断「X8U 的 HAL/ISP 是否认识
O-Log2」，而不是「APK 里有没有那个字符串」。

---

## 2. 逐层比对

三套固件的比对样本：

| 代号 | 机型 | 固件 | 相机 APK |
|---|---|---|---|
| X8U | PKJ110（本机） | ColorOS 17.0.0 | 7.006.123 (60000) |
| X9U | PMX110 | ColorOS 17.0.0.109 | 7.006.119 (60000) |
| PYB110 | 一加16 | ColorOS 17.0.1.201 | APK 提取不完整，用 35 个 dex 比对 |

注意 X8U 的相机 APK 版本**比 X9U 还新**，不存在「X8U 版本旧所以没有」。

### 2.1 资源层：三套一模一样

`aapt2 dump resources` 实测，X8U 与 X9U 的资源表逐项相同：

```
resource 0x7f0300e6 array/maser_video_format_names
    () (array) size=3
      [@string/video_format_sdr, @string/video_format_hdr, @string/video_format_log]
resource 0x7f0300e7 array/maser_video_format_names_log2
    () (array) size=3
      [@string/video_format_sdr, @string/video_format_hdr, @string/video_format_log2]
resource 0x7f0300e8 array/maser_video_format_values
    () (array) size=3
      ["sdr", "hdr", "log"]
resource 0x7f110bb5 string/video_format_log
    () "Log"
resource 0x7f110bb6 string/video_format_log2
    () "O-Log2"
resource 0x7f1108f4 string/log2
    () "O-Log2"
    (zh-rCN) "O-Log2"
resource 0x7f0713ce dimen/log2_guide_bubble_entry_offset_from_center
```

关键点：`maser_video_format_values` 只有 **`"sdr", "hdr", "log"`**，
没有 `"log2"` 这个值。也就是说即使把显示名换成 O-Log2，下发的格式值
仍然是 `log` —— 相机内部只认一个 Log 档。

### 2.2 代码层：`log2` 数组是死资源

用 `tools/dex_fieldref.py` 扫完 33 个 dex：

```
$ python tools/dex_fieldref.py "Lcom/oplus/camera/R\$array;" \
      "maser_video_format_names_log2" x8u_dex/*.dex
== x8u_dex/classes30.dex field_idx=[23860]
```

只打印了字段定义，**没有一条方法命中**。X9U、PYB110 同样为空。
`R$string.log2` 也一样，没有任何方法引用。

也就是说：`maser_video_format_names_log2` 与 `R.string.log2` 是随包发布
但从未接线的预埋资源，三套固件一致。

### 2.3 唯一提到 O-Log2 的代码

`classes23.dex` 里 `Lrq/g1;.f()`（大师视频 COUI 提示构建器）反汇编：

```
const-string v1, "Log"
invoke-virtual {v1, v0}, String.contentEquals(CharSequence)Z
const-string v2, "O-Log2"
if-nez v0, 0017
...
invoke-virtual {v2, v0}, String.contentEquals(CharSequence)Z
```

它做的是 `if (p == "Log" || p == "O-Log2") 创建提示气泡`，是一个
**防御性兼容判断**（为将来/别处传来的值留门），不产生 O-Log2。

### 2.4 logvideo 包：三套同源

按类名收集 `com.oplus.camera.feature.logvideo` 下的类：

| 固件 | 类数 | 差异 |
|---|---|---|
| X8U | 42 | — |
| X9U | 42 | 与 X8U 完全相同 |
| PYB110 | 43 | 多一个内部类 `LogLutMenuView$c` |

`LogLutMenuView` / `LogPreviewView` 引用的字符串常量也完全一致
（`LogVideoHDRBt2020`、`LogVideoSDRBt709`）。没有任何 O-Log2 专用分支。

### 2.5 HAL / OEM 层：不存在 log2

X8U 设备端实测（已 root）：

```
$ grep -l -a "O-Log2" /odm/lib64/*.so            → 无
$ grep -l -a -E "olog2|o_log2|OLOG2" /odm/lib64/*.so → 无
$ grep -l -a -E "O-Log2|olog2" /vendor/lib64/*.so /vendor/lib64/hw/*.so → 无
```

`/odm/lib64/hw/camera.oemlayer.so`（1.4 MB，OPPO 的 CAMX OEM 层）里
与 log 相关的可打印字符串只有：

```
Oplus Technologies, Inc.
```

`/odm/lib64/libextensionlayer.so` 里只有一句日志格式串：

```
camera.mode: %s, isLogVideo: %d
```

它只打印「是不是 Log 视频」，不区分代际。

### 2.6 一个真实的缺口：X8U 没有 Log 的 3D LUT 文件

`log_video_sdr_3dlut.cube` / `log_video_hdr_3dlut.cube` 是 Log 预览
还原用的 3D LUT。三套固件的情况：

| 固件 | 文件 | 大小 |
|---|---|---|
| X9U | `/etc/camera/filters_lut/log_video_sdr_3dlut.cube` | 646 KB |
| X9U | `/etc/camera/filters_lut/log_video_hdr_3dlut.cube` | 905 KB |
| PYB110 | 同名，symlink（指向 model APEX） | — |
| **X8U** | **不存在** | — |

而 X8U 的代码明确在 `Lcom/oplus/camera/common/utils/Lut3dDataManager;.d`
里加载这两个文件名（`dex_strref` 命中，上下文里与 `dolby_preview_*`
等并列）。即：**代码期待文件存在，设备上却没有**。

这是 X8U 自身的一个裁剪缺口，也是本轮唯一可以通过补文件改善的点
（见 §4）。

---

## 3. 为什么不可移植

| 拦住它的东西 | 证据 |
|---|---|
| O-Log2 没有 App 侧实现 | 三套 APK 的 log2 数组/字符串均无方法引用；logvideo 类集合与常量一致 |
| 没有可搬的格式值 | `maser_video_format_values` 只有 `sdr/hdr/log`，Log2 没有独立值 |
| HAL 不认识 | X8U 的 `/odm/lib64`、`/vendor/lib64`、`camera.oemlayer.so` 均无 log2 标识 |
| 跨代 SoC | X8U 是 SM8750（Hexagon v79）；O-Log2 首批机型是 SM8975 级 |
| 官方名单 | Blackmagic 3.5 只写 Find X10 系列与一加16 系列 |

补 so 也没有用：即使把 X9U 的某个库整个搬过来，O-Log2 的曲线参数在
ISP tuning（每个 sensor 单独标定）里，跨 SoC 无法复用。

---

## 4. 唯一值得动手试的一件事

**把 X9U 的两个 Log 预览 LUT 补到 X8U，看 Log 预览是否变化。**

- 这是纯数据文件，不涉及签名、不涉及 HAL；
- X8U 代码已经在按这两个文件名加载，只是文件缺失；
- 风险：`.cube` 是 33³ 格点，X9U 与 X8U 的 sensor/色彩管线不同，
  直接套可能偏色。必须先备份、可回滚（模块 bind mount 已有先例）。

预期收益：Log 预览的色彩映射更接近新机型。**不是** O-Log2 录制。

除此之外没有可落地的「O-Log2 移植」。

---

## 5. 复现命令

```bash
# 1. 三套 dex 摊平（X9U / X8U 从各自 APK 取）
python - <<'EOF'
import zipfile, os
for apk, out in [("x9u_targets/OplusCamera.apk", "G:/ColorOS17/olog2/x9u_dex"),
                 ("x8u_product_lib64/OplusCamera.apk", "G:/ColorOS17/olog2/x8u_dex")]:
    z = zipfile.ZipFile(apk); os.makedirs(out, exist_ok=True)
    for n in z.namelist():
        if n.endswith(".dex"):
            open(out + "/" + os.path.basename(n), "wb").write(z.read(n))
EOF

# 2. 资源对比（必须用 Windows 版 aapt2）
AAPT=/c/Users/Mr-DCP/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe
$AAPT dump resources x8u.apk > x8u_res.txt
grep -i "log2" x8u_res.txt

# 3. 字段引用（判断死资源）
python tools/dex_fieldref.py "Lcom/oplus/camera/R\$array;" \
       "maser_video_format_names_log2" G:/ColorOS17/olog2/x8u_dex/*.dex

# 4. 字符串引用 + 上下文
python tools/dex_strref.py "O-Log2" G:/ColorOS17/olog2/x8u_dex/classes23.dex

# 5. 设备端 HAL 自查
adb shell "su -c 'grep -l -a -E \"O-Log2|olog2\" /odm/lib64/*.so /vendor/lib64/*.so'"
adb shell "su -c 'ls /odm/etc/camera/filters_lut/ | grep -i log'"
```

---

## 6. 本轮新增工具

| 工具 | 用途 |
|---|---|
| `tools/dex_fieldref.py` | 按 `类描述符 + 字段名` 找引用该字段的方法（解字段引用，不走常量内联） |
| `tools/dex_strref.py` | 按字符串常量找引用它的方法，并打印该方法其余常量 `--near` 看字符串池邻居 |

两个工具都只读 dex 表（`string_ids/type_ids/field_ids/method_ids/class_defs`
+ `code_item`），不依赖 jadx / dexdump —— 后两者在这些 OPPO dex 上分别
会卡死和因 checksum/padding 拒收。

已知坑：
- Windows Python 不认 `/g/...`，一律 `G:/...`；
- `dexdump` 对个别重建过的 dex 会给出越界的 `insns_size`，工具里已做
  clamp（否则整个文件解析中断）；
- **`log2` 子串污染极强**：WebRTC 的 `rtclog2.*`、`Technologies` 里的
  `olog` 都会误命中。搜索必须用 `O-Log2` / `video_format_log2` 这类
  全名，或先限定资源表。
