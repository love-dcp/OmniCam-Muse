# O-Log / O-Log2 调查（V2：以 OPPO 官方技术资料为准）

> V1 版本（2026-11 初）只靠固件字符串推断，得出了两个错误结论：
> ① 以为支持 O-Log2 的机型只有「Find X10 系列 / 一加16」；
> ② 以为 Log 预览 LUT 是 `/odm/etc/camera/filters_lut/log_video_*_3dlut.cube`。
>
> V2 拿到 OPPO 官方曲线公式与官方 LUT 后全部推翻，并给出可复算的判据。
>
> **最终结论：X8U 仍然是 O-Log（v1），O-Log2 搬运不到 X8U。
> 但「不可粘贴」的理由变了——不是 SoC 代差，是 OPPO 根本没给 X8U 编这条曲线。**

---

## 0. 结论速览

| 问题 | V1 结论 | V2 结论（已证实） |
|---|---|---|
| O-Log2 支持哪些机型 | 只有下一代 SoC（X10 / 一加16） | **Find X9 Ultra（PMA110）就有**，同代 MTK 平台 |
| X8U 是第几代 Log | 没定 | **O-Log v1**，官方白皮书机型表明确列出 Find X8 Ultra |
| Log 预览 LUT 文件名 | `filters_lut/log_video_*_3dlut.cube` | **`LUTData/LogVideo{SDR,HDR}*.bin`**，33³ RGBA float32 + 8 字节头 |
| X8U 有没有这个 LUT | 缺 cube | **整个 `LUTData` 目录都没有**（odm / product / my_product 全扫） |
| 能否搬 O-Log2 到 X8U | 不能（跨 SoC） | **不能（OPPO 未给 X8U 编曲线；且 X8U 是 QCOM、X9U 是 MTK）** |

唯一新增的好消息：现在有了**可复算的曲线数学**和**已验证的 bin 转换器**，
X8U 缺失的 Log 预览 LUT 可以自己生成，不必再赌 X9U 的文件能不能用。

---

## 1. 官方资料（2026-11-05 抓取）

页面：<https://www.oppo.com/cn/log-to-rec709-download/>

| 文件 | 大小 | 内容 |
|---|---|---|
| `O-Log2-technical-transform-tools_v1-CN.zip` | 19.2 MB | 3 个 65³ `.cube` |
| `O-Log2_ACES_Workflow_Guide_CHN_V2.zip` | 6.3 MB | DCTL + CTL + 中英文流程 PDF |
| `o-log-technical-transform-tools_v0.zip` | 4.3 MB | O-Log v1 的 2 个 65³ `.cube` |
| `oppo-o-log_profile_whitepaper_v1.pdf` | 320 KB | **O-Log v1 完整技术规格（11 页）** |
| `o-log_aces_workflow_guide.zip` | — | v1 的 ACES 流程 |

官方 LUT 清单：

```
O-Log2-to-Rec709_Gamma24-D65_65.cube      5,081,625
O-Log2-to-Rec2020_PQ1000_65.cube          7,035,691
O-Log2-to-Rec2020_HLG-D65_65.cube         7,101,970
O-Log-Rec.709 Gamma2.4_v1.6.cube          7,801,351   (v1)
O-Log-Rec.2020 PQ1000_v1.6.cube           7,574,774   (v1)
```

页面只写了用途（10bit Log → Rec.709 Gamma2.4 的 SDR / Rec.2020 PQ1000 的 HDR），
**没有贴曲线公式**。公式在两处：v1 在白皮书 §3，O-Log2 在 ACES 包的
`O-Log2_to_ACES_AP0_V2.ctl` 注释里。

### 1.1 共通规格（两代一致）

- 色域 **O-Gamut = ITU-R BT.2020-2 原色**，白点 D65，
  `Y = 0.2627R + 0.6780G + 0.0593B`
- 编码 **H.265 / YUV420，10bit**（14bit 线性压缩到 10bit，仅量化损失）
- 码率 **4K60 ≈ 120 Mbps ±20%**；每分钟体积：1080P30 240MB、1080P60 360MB、
  1080P120 540MB、4K30 600MB、4K60 900MB、4K120 1350MB
- ACES 转换矩阵两代**完全相同**（Rec2020→XYZ、D65→D60 CAT02、XYZ→AP0），
  唯一差别是 v1 解码后多一步 `/16*7.37235` 归一化，O-Log2 直接输出场景线性

---

## 2. 两代曲线的精确差异

```
O-Log (v1)  编码: P = 0.139 · ln(R + 0.019) + 0.614        R = 场景反射率 0..16
            解码: R = e^((P-0.614)/0.139) - 0.019

O-Log2      编码: y = 0.0855 · log2(x + 0.0096) + 0.693     x = 相对场景线性
            解码: x = 2^((y-0.693)/0.0855) - 0.0096
```

### 2.1 关键锚点

| 项目 | O-Log v1 | O-Log2 | 差 |
|---|---|---|---|
| 18% 灰 | 0.3896（码 **399**） | 0.4879（码 **499**） | +0.098 |
| 纯黑（输入 0） | 0.0631（码 **65**） | 0.1199（码 **123**） | +0.057 |
| 每档曝光码值 | 0.09635 | 0.0855 | −11% |
| 白点截幅 | R=16（1600%） | x=12.02（1202%） | 高光少 0.4 档 |
| 总编码档位 | 9.72 | 10.29 | 暗部多 0.57 档 |
| 18% 灰位置 | 偏下（39% 码位） | **正中间（49% 码位）** | 设计哲学变了 |

O-Log2 不是「动态范围更大」，而是**把 18% 灰摆到码值正中**（0.4879≈499/1023），
上下留出对称的调色空间，代价是高光截幅点从 1600% 降到 1202%。
这是往电影机 Log（ARRI / ACES 工作流）靠的设计，不是给手机「多拍两档」。

### 2.2 用错代际的代价（可复算）

在官方 65³ 网格上取 18% 灰：

| 素材 | 正确输出 | 套错代际 LUT 的输出 | 误差 |
|---|---|---|---|
| v1 素材 → 套 v2 LUT | 0.3382 | 0.2425 | **−0.096**（暗 10 IRE） |
| v2 素材 → 套 v1 LUT | 0.4269 | 0.5294 | **+0.103**（亮 10 IRE） |

即：**两代 LUT 绝对不能混用**，中间调就有 10% 的显示偏差，暗部更大。

---

## 3. 机型归属

### 3.1 官方白皮书（O-Log v1）机型表

| 机型 | 动态范围（SNR=1） |
|---|---|
| **OPPO Find X8 Ultra** | 14 stops |
| OnePlus 15 | 13 stops |
| OPPO Find X9 | 14 stops |
| OPPO Find X9 Pro | 14 stops |

X8 Ultra 被官方明确列为 **O-Log（v1）** 机型。表里**没有 Find X9 Ultra** ——
因为 X9U 用的是 O-Log2（见 §4）。

### 3.2 V1 文档的两处错误（已修正）

| V1 写的 | 实际 |
|---|---|
| 「X9U = PMX110」 | **X9U = PMA110**，PMX110 是 Find X10 Pro Max |
| 「X8U 是 MTK / MTK 没 HTP」 | X8U 是 **SM8750 骁龙 8 Elite（Hexagon v79）**；X9U 才是 MTK |

「MTK 没有 HTP 所以搬不了」的说法作废 —— X9U 恰恰是 MTK 且跑 O-Log2。

---

## 4. 决定性证据：X9U 原生 Log LUT 就是官方 O-Log2

### 4.1 找到真文件

X9U（PMA110，ColorOS 17）的 `odm.img` 里：

```
/odm/etc/camera/LUTData/LogVideoSDRBt709.bin    575,000 B
/odm/etc/camera/LUTData/LogVideoHDRBt2020.bin 1,724,984 B
```

注意：**不是** `filters_lut/*.cube`，X8U 与 X9U 都没有 `filters_lut` 这个目录
（X8U 只有 `meishe_lut`，那是滤镜用的）。V1 文档里的文件名是错的。

`LogVideoSDRBt709` / `LogVideoHDRBt2020` 这两个字符串在相机 APK 里被
`Lcom/oplus/camera/feature/logvideo/view/LogLutMenuView;.setSelectedLutPositionAndApply`
引用（classes9.dex，字符串 idx 3626），即菜单选择后按名字去 native 取 LUT。

### 4.2 逐条比对结果

用官方 `O-Log2-to-Rec709_Gamma24-D65_65.cube` 降采样到 33³、
按 §5 的格式重新打包后，与 X9U 原生文件逐条目比较：

| 对比对象 | 完全一致（1e-6） |
|---|---|
| 原生 vs 官方 **O-Log2** 生成 | **33,543 / 35,937 = 93.34%** |
| 原生 vs 官方 **O-Log v1** 生成 | 27 / 35,937 = 0.08% |

不一致的全部集中在 **33543..35936** 这段连续区间 —— 也就是 X9U 文件尾部
（EROFS 解压受损的 6.6%），不是算法差异。头部 8 字节也逐字节相同。

**结论：X9U 的 Log 视频走 O-Log2。** 这也解释了为什么官方 v1 白皮书的机型表
里没有 X9 Ultra。

---

## 5. `.bin` 格式（已完全逆向，可双向转换）

```
offset 0   4B  magic        21 10 C6 08
offset 4   4B  参数          00 00 6A 3C   (SDR；HDR 文件此 4 字节不同，含义未解)
offset 8   N³ × 16B 条目     每条目 4 × float32 (v0,v1,v2,alpha)，alpha 恒 1.0
           N = 33
```

- 轴序**与 `.cube` 完全相同**（第 3 分量变化最快），可直接顺序拷贝，无需重排。
  用 33³↔65³ 共用格点做过验证：重排后一致率立刻掉到 3.17%。
- 输出通道 0 跟随输入第 3 分量（即 (r=0,g=0,b) 只涨通道 0），
  说明 OPPO 的 LUT 内部就是这一套通道约定，照抄即可。
- HDR 文件 = `8 + 3 × 33³ × 16`，看起来像三张 33³ 叠放，
  但只能解出其中一张（identity），另两张是乱数 —— 大概率是本机 EROFS
  提取在多 pcluster 大文件上仍有损坏（工具已知问题）。
  **HDR 布局待设备端 `adb pull` 复核，不要凭这份镜像下结论。**

---

## 6. 对 X8U 到底意味着什么

### 6.1 仍然搬不了 O-Log2 录制

| 拦住它的东西 | 证据级别 |
|---|---|
| 官方把 X8U 定为 O-Log v1 | 白皮书机型表（硬证据） |
| 曲线在 ISP/HAL tuning 层，按 sensor 单独标定 | O-Log2 在 X8U 的 `/odm/lib64`、`/vendor/lib64`、`camera.oemlayer.so` 里零标识 |
| 官方 LUT 只是「Log → 显示」的转换表 | 它改的是**看**，改不了**录进文件里的曲线** |
| 平台不同 | X8U=SM8750(QCOM)，X9U=Dimensity(MTK)，tuning 二进制不可互换 |

### 6.2 可用 `.cube` 改善的只有「预览/后期」，不是录制

如果有人把官方 O-Log2 LUT 塞给 X8U 的 Log 预览，会得到 §2.2 里 **暗 10 IRE**
的错误画面（X8U 拍的是 v1）。所以适配时必须用 v1 的 LUT。

### 6.3 X8U 的两个真实缺口（可动手，数据层面）

1. **缺整个 `/odm/etc/camera/LUTData/` 目录**
   —— odm / product / my_product 三个分区全扫，没有任何 `LogVideo*.bin`。
   相机代码却在按名字加载。补上正确的 v1 LUT 后，Log LUT 菜单有可能被点亮。
   难点：`/odm` 只读且目录不存在，需要目录级 bind 或 overlay，
   不是现在模块那种「单文件 bind」能覆盖的。**先确认设备上目录是否真的不存在。**
2. **黑位判据测试（一句话定案 X8U 到底是哪代）**
   遮住镜头拍 1 秒 Log 视频，取一帧，读最暗处的码值（10bit）：
   - **约 65** → O-Log v1
   - **约 123** → O-Log2

   这条判据比 grep 字符串硬，比看直方图直观。已按 §2 的公式算好期望值。

---

## 7. 复现命令

```bash
# 1. 下载官方资料
curl -L -o olog2_lut.zip  "https://www.oppo.com/content/dam/oppo_com/cn/mkt/footer/O-Log2-technical-transform-tools_v1-CN.zip"
curl -L -o olog2_aces.zip "https://www.oppo.com/content/dam/oppo_com/cn/mkt/footer/O-Log2_ACES_Workflow_Guide_CHN_V2.zip"
curl -L -o olog_wp.pdf    "https://www.oppo.com/content/dam/oppo_com/cn/mkt/footer/oppo-o-log_profile_whitepaper_v1.pdf"

# 2. 曲线数值对比（含锚点表）
python -c "..."            # 见 docs 正文 §2
# 3. 解析 cube 中性轴 / 反推亮度映射
python tools/olog_lut_analyze.py probe "O-Log2-to-Rec709_Gamma24-D65_65.cube"

# 4. 取 X9U 原生 Log LUT（注意参数顺序是 <img> <action>，且必须是 Windows 路径）
MSYS_NO_PATHCONV=1 python tools/erofs_extract.py "G:/ColorOS17/x9u_extract/odm.img" \
    list --path /etc/camera/LUTData
MSYS_NO_PATHCONV=1 python tools/erofs_extract.py "G:/ColorOS17/x9u_extract/odm.img" \
    cat --path /etc/camera/LUTData/LogVideoSDRBt709.bin > LogVideoSDRBt709.bin

# 5. cube -> 手机 bin，并与原生文件比对
python tools/olog_cube_to_bin.py "O-Log2-to-Rec709_Gamma24-D65_65.cube" gen_olog2_sdr.bin 33
```

---

## 8. 本轮新增工具

| 工具 | 用途 |
|---|---|
| `tools/olog_lut_analyze.py` | `probe` 中性轴取样 / `compare` 两 LUT 差值统计 / `shrink` 降采样 / `synth` 按公式合成 |
| `tools/olog_cube_to_bin.py` | `.cube` → OPPO `LUTData/*.bin`（33³ RGBA float32 + 8B 头），已验证与原生文件 93.3% 逐字节一致 |

已知坑：
- `erofs_extract.py` 的参数顺序是 **`<img> <action>`**（内存里写反过），
  且 Windows Python 不认 `/g/...`，一律 `G:/...`；
- `cat` 子动作忽略 `--out`，要用 shell 重定向；
- 大文件（>1 MB 多 pcluster）提取仍可能损坏 —— HDR LUT 就栽在这上面；
- `log2` 子串污染极强（WebRTC `rtclog2.*`、`Technologies` 的 `olog`），
  搜索必须用 `O-Log2` / `video_format_log2` 这类全名。
