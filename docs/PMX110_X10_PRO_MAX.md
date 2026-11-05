# Find X10 Pro Max（PMX110 / ColorOS 17.0.0.109）解包：灵感构图与 O-Log2

> 固件：`F:\电脑\ColorOS PMX110_17.0.0.109(CN01).zip`（payload 11.2 GB，47 个分区）
> 平台：**MediaTek 天玑 9600 Pro（mt6995）**，2026-09 上市，与一加16（PYB110，高通 sm8975）同代
> 对比对象：X8U（PKJ110，SM8750 / Hexagon v79）、X9U（PMA110）、一加16（PYB110）

---

## 1. 结论先行

| 问题 | 结论 |
|---|---|
| X10 Pro Max 的灵感构图是怎么做的 | **端侧**，但走的是**另一套实现**：319 KB 薄壳 + MTK NeuroPilot（`libmtk_mllm.so` / `libapuwareq`）+ `.dla/.dlx` 模型，模型 630 MB 直接放在 `/odm/etc/camera/ai_composition/` |
| 和一加16 是同一套吗 | **不是**。一加16 是 26 MB 单体 + QNN（`libQnnHtp.so`），X10 是同名库但 319 KB 的加载器 + MTK 后端。**同名不同实现**，OPPO 按平台各编一套 |
| 能移到 X8U 吗 | **不能**。X8U 既没有引擎（缺 `CompositionProcessor` / `AICameraCompositionLite2`），也没有模型，而且它的 QNN 是 v79，连一加16 的 v85 白名单都过不了 |
| X10 的 O-Log2 能在 App 层抄吗 | **抄不到东西**。X10 的相机 APK 里 `maser_video_format_values` 依然只有 `["sdr","hdr","log"]`，**没有 log2 值**；O-Log2 走的是专业视频 / Camera2 通路，曲线在 ISP tuning 里 |
| 有可借鉴的吗 | 有两点：双构图（两条独立答案）+ prompt/预算与输出协议，和之前一加16 的结论一致 |

**最重要的一条纠正**：X8U 是 **SM8750（高通骁龙 8 Elite，Hexagon v79）**，不是 MTK。X10 Pro Max 是 MTK，却照样有端侧灵感构图 —— 所以「MTK 没有 HTP 所以做不了端侧构图」这个理由**是错的**。真正的拦路虎是：OPPO 没有为 X8U 编这套引擎，也没有给它模型。

---

## 2. 分区与解包

`payload.bin` 是 A/B 全量包（`REPLACE / REPLACE_BZ / REPLACE_XZ / ZERO`），分区表 47 项：

| 分区 | 大小 | 用途 |
|---|---|---|
| `my_stock` | 4.72 GB | 预置应用与资源 |
| `odm` | 4.39 GB | **相机配置、HAL、构图模型** |
| `vendor` | 1.37 GB | HAL 与固件 |
| `my_product` | 1.32 GB | **相机 APK（163 MB）** |
| `system` / `system_ext` | 各 ~1.07 GB | 系统 |
| `product` | 0.15 GB | 无相机 |

相机本体在 `/my_product/app/OplusCamera/OplusCamera.apk`（163 MB，34 dex）。
**注意与一加16 不同**：一加16 已把相机搬进 APEX（`/app/OplusCamera/OplusCamera.apk`），X10 还是普通 APK。

---

## 3. 灵感构图：MTK 版架构

### 3.1 组件清单（实测路径）

```
/my_product/app/OplusCamera/OplusCamera.apk                     相机（Java 层，aicomposition 模块）
/my_product/lib64/libApsAICompositionJni.so                      JNI 桥，358256 B —— 与一加16 同尺寸
/odm/lib64/libai_camera_composition_lite2.so                    319 KB 薄壳（一加16 是 26 MB）
/odm/lib64/libmtk_mllm.so                                        611 KB，MTK 多模态 LLM 运行时
/odm/lib64/libmtk_llm.so + /odm/lib64/aiframe/libmtk_llm.so       500 KB / 4 MB
/odm/lib64/aiframe/libapuwareq.mtk.so                             APU 队列中间件
/odm/etc/camera/ai_composition/composition_lite2_config.yaml      1086 B，见下
```

### 3.2 模型（`/odm/etc/camera/ai_composition/`，共 630 MB）

| 文件 | 大小 | 作用 |
|---|---|---|
| `res_vision.dla` | 130 MB | 视觉编码器（FastViT 类） |
| `res_prompt.dla` | 4 MB | prompt 阶段 |
| `res_gen.dla` | 1 MB | 生成阶段 |
| `share.dlx` | 234 MB | 共享权重（LLM 主体） |
| `embedding_fp16.bin` | 259 MB | token embedding（fp16） |
| `overture.bin` | 6 MB | KV cache 初值 |
| `vocab_i2t.bin` + `added_tokens.yaml` | 2 MB | tokenizer |

`.dla` / `.dlx` 是 **MTK NeuroPilot** 的模型格式；一加16 那边是 QNN context binary 的 `.bin`。

### 3.3 配置原文（关键证据）

```yaml
version:
  model: "1.8.11"
  soc: dx6                 # MTK 内部平台代号，对应 mt6995 / 天玑 9600 Pro

modelOptions:
  promptTokenBatchSize: 320
  genTokenBatchSize: 1
  cacheSize: 512
  hiddenSize: 896           # Qwen2.5-0.5B 的规格
  numHead: 14
  numLayer: 24
  maxTokenLength: 4096
  modelInputType: FP16
  modelOutputType: INT16
  cacheType: INT16
  rotEmbBase: 1000000.0

runtimeOptions:
  imageTokenSize: 256
  specialTokens: { bosId: 151644, eosId: 151645, addBos: false }
  tokenizerPath:  [ ...,/vocab_i2t.bin, .../added_tokens.yaml ]
  tokenEmbPath:   /odm/etc/camera/ai_composition/embedding_fp16.bin
  dlaPromptPaths: [ /odm/etc/camera/ai_composition/res_prompt.dla ]
  dlaGenPaths:    [ /odm/etc/camera/ai_composition/res_gen.dla ]
  sharedWeightsPaths: [ /odm/etc/camera/ai_composition/share.dlx ]
  clipPath:       /odm/etc/camera/ai_composition/res_vision.dla
  cachePaths:     [ /odm/etc/camera/ai_composition/overture.bin ]
```

`hiddenSize 896 / 24 层 / 14 头 / bos 151644` = **Qwen2.5-0.5B**，和一加16 用的是同一档模型，只是后端不同。

### 3.4 调用链

```
Camera Java（feature.aicomposition，含 CompositionProcessor / AICompositionThread）
  → libApsAICompositionJni.so        nativeInitComposition_dlopen / Lite2_RunStreamingFunc
  → libai_camera_composition_lite2.so（319 KB，只做参数搬运与调度）
  → dlopen libai_camera_composition_lite2_odm.so（真正的实现，平台相关）
  → libmtk_mllm.so / libmtk_llm.so / libapuwareq.mtk.so（NeuroPilot APU）
  → res_*.dla / share.dlx / embedding_fp16.bin
```

`libai_camera_composition_lite2.so` 里能直接读到的符号（去掉前缀后）：
`mtk_mllm_init_clip_only`、`mtk_mllm_init_llm_after_staged_clip`、`mtk_mllm_consume_prompt_pixels`、
`mtk_mllm_inference_once`、`mtk_llm_swap_model`、`mtk::HuggingFaceTokenizer`、`mtk::RawPixelView`、
`mtk::LogitsKind`，以及字符串 `libmtk_mllm.so` / `libmtk_llm.so` / `libapuwareq` / `libtokenizer.so` /
`libai_camera_composition_lite2_odm.so`。

### 3.5 两平台对照

| 项 | 一加16（PYB110） | X10 Pro Max（PMX110） | X8U（PKJ110） |
|---|---|---|---|
| SoC | 高通 sm8975 | MTK mt6995（dx6） | 高通 SM8750 |
| NPU 栈 | QNN / Hexagon **v85** | NeuroPilot APU | QNN / Hexagon **v79** |
| 引擎 so | `libai_camera_composition_lite2.so` **26 MB** | 同名 **319 KB** | **无** |
| 后端库 | `libQnnHtp.so` / `libQnnSystem.so` | `libmtk_mllm.so` / `libapuwareq.mtk.so` | 有 QNN v79，但无引擎 |
| 模型格式 | QNN `.bin`（345 MB，在 model APEX） | NeuroPilot `.dla/.dlx`（630 MB，在 odm） | **无** |
| Java 构图模块 | 580 类 | 同源 | 539 类，缺 4 个核心类 |
| JNI 桥 | 358256 B | 358256 B（同一套接口） | 有 |

JNI 桥两平台**同尺寸同接口**（`AICameraCompositionLite2_nativeRunStreaming`），说明 OPPO 的设计是
「上层一套、后端按平台换」。X8U 缺的正是「后端那一半」。

---

## 4. O-Log2：三套固件同源，X10 也不在菜单里

### 4.1 资源层（这次拿到了 X10 完整的 `resources.arsc`，14,136,256 B）

| 字符串 | X8U（56143 个串） | X10 Pro Max（59000 个串） |
|---|---|---|
| `log` | #6871 | #7129 |
| `sdr` | #6932 | #7190 |
| `hdr` | #6843 | #7095 |
| `O-Log2` | #5779 | #5926 |
| 独立的 `log2` | **无** | **无** |

`maser_video_format_values` 数组在三套固件里都只有 `["sdr","hdr","log"]`，**没有 log2 这一项**。
`video_format_log2`（显示名 `O-Log2`）三套都有，但没有任何方法引用它 —— 是一条预埋的死资源。

### 4.2 HAL 层

X10 的相机 HAL 是 MTK 自家 `libocam_*`，全在 `/odm/lib64/mt6995/`。在 `libocam_czfeature_node.so`
（1.3 MB）和 `libocam_custompolicy.so` 里搜到的全部 Log 相关字符串只有：

```
[s] Pro Video Scene Config - ProMode:%d LogMode:%d SDR:%d HDR:%d EIS:%d ...
[s] Set dataspace for %s stream 0x%x: %d (LogMode:%d Monitor:%d ProMode:%d HDR:%d LogVideoMode:%d)
CustomMetadata::isLogMode()
```

**没有任何 `O-Log2` / `olog2` 分支**。也就是说 HAL 只认「是不是 Log」这一个布尔，
O-Log2 与 O-Log 的区别不在 HAL 代码里，而在 **ISP tuning 的曲线参数**（以及走哪条 Camera2 通路）。

X10 的 odm 里也没有任何 `*log2*` 命名的 LUT 文件；`log_video_sdr_3dlut.cube`（646 KB）
与 `log_video_hdr_3dlut.cube`（905 KB）和 X9U 完全同名同大小。

### 4.3 官方口径佐证

OPPO 海外新闻稿原文：*"Find X10 Series supports Dolby Vision, **ACES-certified O-Log2** and, for the
first time on an OPPO smartphone, Open Gate Capture"*，且明确说这些能力已做进 **Blackmagic Camera**。

即：O-Log2 是**专业视频 / 第三方相机通路**的能力，与相机 App 的「SDR/HDR/Log」格式菜单是两回事。

---

## 5. X8U 能移植什么

### 5.1 灵感构图：不可移植（定论）

1. 引擎按平台编译：高通版是 QNN context binary + v85 白名单（`SM8975/SM8850/SM8950`），
   MTK 版是 `.dla` + NeuroPilot。X8U 两套都不匹配；
2. X8U 的 QNN 是 **v79**，即使拿到一加16 的 26 MB 引擎，`socguard` 第一关就拒；
3. X8U 的相 APK 里没有 `CompositionProcessor` / `AICameraCompositionLite2`，没人调用；
4. 权重只有后端专用格式，没有可重新导出的原始权重。

**「MTK 没 HTP」不再是理由**——X10 证明 MTK 也能跑端侧构图，问题是 OPPO 没给 X8U 做。

### 5.2 O-Log2：没有可搬的实现

三套固件的 App、资源、HAL 代码在这块完全同源，唯一的差异在 ISP tuning 的曲线，而 tuning 是
按 sensor + SoC 标定的，跨平台不可用。**X8U 想有 O-Log2，只能等 OPPO 给 X8U 出带这个 tuning 的固件。**

### 5.3 仍可借鉴（与一加16 那份结论一致）

1. **双构图**：X10 宣传的「行业首创双构图」= 一次给两条互相独立的建议（人物为主 / 人景融合），
   对应我们 `CloudComposer` 的 `outputNum:2` + 两条独立 crop，已实现；
2. **prompt + 预算**：一加16 的 `prompt` 与 `max_new_tokens=96` 可继续用；
3. **审核先于请求**：已在 r151 落地（`AntiSpamUtil.e(Bitmap)` 短路）。

---

## 6. 附带产出：EROFS 解码器三处硬修复

解 X10 的 APK 时，原来的 `tools/erofs_extract.py` 只能解出 63% 的字节（62.9 MB 缺失），
修完三处后 **缺失降到 0.31 MB（0.18%），耗时从 7 分钟降到 9 秒**：

| # | 问题 | 症状 | 修法 |
|---|---|---|---|
| 1 | 紧凑索引按 4 字节读 | `decode_bits` 用 `<I` 取窗口，14 位条目跨 4 字节边界时高位丢失，type 从第 3 个 lcluster 起全错 | 改读 `<Q`（8 字节）再移位 |
| 2 | 多块 pcluster 第一次失败就放弃 | 4 块才凑齐的 pcluster 在第 1 块时必然解压失败，代码 `break` 直接判死 | 失败当「再加一块」，连错 64 次才放弃 |
| 3 | pcluster 起始块靠 NONHEAD delta 累加 | delta 在 4B/2B 索引交界处会读出 2052 这类垃圾值（实测应为 1），导致 pblk 差 3~6 块 | 改为**顺序推进**：pcluster 首尾相接，`下一个 = 上一个 + 实耗块数`，pack base 只作种子与备选 |

第 3 条是决定性的。实测证据（APK 内两个 head）：

```
lcn=1065 span=22610 → 正确 pblk=22501（pack base 给 22498）
lcn=1070 span=26135 → 正确 pblk=22505（pack base 给 22499）
```

真实 pcluster 严格 +4 连续，pack base 每 pack 只 +1，**不能直接当块地址用**。

---

## 7. 复现命令

```bash
# 分区表
python tools/pmx110_manifest.py

# 抽分区（payload 已解到 G:/pyb110/pmx_payload.bin，也可直接从 zip 里流式读）
python tools/pmx110_pull.py G:/pyb110/pmx_payload.bin extract odm --out G:/pmx110/odm.img
python tools/pmx110_pull.py G:/pyb110/pmx_payload.bin extract my_product --out G:/pmx110/my_product.img

# 找引擎与模型
MSYS_NO_PATHCONV=1 python tools/erofs_extract.py G:/pmx110/odm.img find --name "*composition*"
MSYS_NO_PATHCONV=1 python tools/erofs_extract.py G:/pmx110/odm.img list --path /etc/camera/ai_composition

# 取相机 APK 并拆 arsc
MSYS_NO_PATHCONV=1 python tools/erofs_extract.py G:/pmx110/my_product.img \
    dump --path /app/OplusCamera/OplusCamera.apk --out G:/pmx110/ext/cam3
python tools/arsc_arrays.py <resources.arsc> --grep maser_video
```

> 全部命令在 Git Bash 下都要 `MSYS_NO_PATHCONV=1`，且给 Python 的路径用 `G:/...` 而不是 `/g/...`。

---

## 8. 已知限制

- APK 仍有 0.18%（314,894 B）未解出，导致 **zip 中央目录不可用**（`Bad offset for central directory`）。
  绕过办法：扫 `PK\x03\x04` 局部头单独取条目 —— `resources.arsc` 是 stored，14,136,256 B 完整。
- `tools/arsc_arrays.py` 的 key 名解析在这些表上会出乱码（OPPO 的 key 池 utf-8 变长头有差异），
  所以对比用的是「字符串池里有没有独立的值」而不是「哪个数组引用了它」；结论够用。
- `libai_camera_composition_lite2.so` 里还剩 1 个 pcluster（11,259 B）解不出，是零块 / 跨块回引；
  不影响符号与字符串级结论。
