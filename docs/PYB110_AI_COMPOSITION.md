# 一加16（PYB110 / ColorOS 17.0.1.201）灵感构图实现分析

解包对象：`F:\电脑\ColorOS PYB110_17.0.1.201(CN01).zip`（A/B OTA，payload.bin 10.2 GB）
分析时间：2026-11-05
对照机型：X8U / PKJ110（本模块的目标设备，MTK 平台）

---

## 一、结论先行

一加16 的「灵感构图」**是端侧多模态大模型推理**，不是云端往返，也不是传统的
OpenCV/显著性裁剪：

| 项 | 内容 |
|---|---|
| 视觉编码器 | **FastViT** |
| 语言模型 | **Qwen**（Qwen-VL 系，96 token 输出） |
| 推理后端 | **Qualcomm QNN / Hexagon HTP**（`libQnnHtp.so` + `libQnnSystem.so`） |
| 引擎实现 | `libai_camera_composition_lite2.so`（26 MB，类名 `FastViTQwenComposition`） |
| JNI 桥 | `libApsAICompositionJni.so`（APEX `com.oplus.camera.app`） |
| 模型包 | `com.oplus.camera.aps.algo.model.apex`（1.77 GB，模型 ~345 MB） |

X8U 上**没有**这条链路：同一套 Java 模块（`com.oplus.camera.feature.aicomposition`，
539 个类）完整存在，但缺 `AICameraCompositionLite2` 类、缺 lite2 引擎库、缺模型，
所以只能走云端插件。这与我们此前通过 hook 得到的结论一致。

---

## 二、组件分布（实测路径）

相机本体已经不在 `/my_product/app`，而是 APEX：

```
/my_product/apex/com.oplus.camera.app.apex         126 MB
  └ /app/OplusCamera/OplusCamera.apk               166 MB（33~36 个 dex）
  └ /lib64/libApsAICompositionJni.so               350 KB
  └ /javalib/com.oplus.camera.unit.sdk.jar         5 MB
/odm/apex/com.oplus.camera.aps.algo.library.apex   1.0 GB
  └ /lib64/libai_camera_composition_lite2.so       26 MB  ← 构图引擎
/odm/apex/com.oplus.camera.aps.algo.model.apex     1.77 GB
  └ /etc/camera/ai_composition/
      ├ composition_lite2_config.yaml              3 KB   ← 全部参数
      ├ htp_config.json                            2 KB   ← NPU 电压/功耗档
      ├ vision.bin                                 141 MB ← FastViT
      ├ prefill.bin                                133 MB ← LLM prefill
      ├ lmhead.bin                                 24 MB
      ├ embed.bin                                  47 MB
      └ tokenizer.fvt1                             582 KB
```

`/odm/etc/camera/ai_composition/*` 全部是符号链接，指向上面 model APEX 里的同名文件。

---

## 三、运行原理

### 3.1 配置（`composition_lite2_config.yaml` 原文节选）

```json
{
  "package_version": "1.4.0",
  "soc": "sm8975",
  "model": { "variant": "composition", "version": "1.10.3",
             "vision_bin": "vision.bin", "prefill_bin": "prefill.bin",
             "lmhead_bin": "lmhead.bin", "embed_bin": "embed.bin",
             "tokenizer_json": "tokenizer.fvt1", "attention_mask_neg": -1000.0 },
  "runtime": { "backend_library": "/odm/lib64/libQnnHtp.so",
               "system_library": "/odm/lib64/libQnnSystem.so",
               "residency": "vision_then_llm",
               "section_prefetch": "all", "kv_memhandle": "1",
               "vote_profile": "v221compat", "htp_extension_library": "none" },
  "generation": { "prompt": "请从摄影美学角度分析该图像，给出构图更优的裁剪框并解释构图思路。",
                  "max_new_tokens": 96 },
  "preprocess": { "geometry": "resize_pad", "filter": "bilinear", "reduce": 0 }
}
```

`soc: sm8975` + `htp_config.json` 里 `soc_model: 103`、`dsp_arch: v85`
（Hexagon v85 = 骁龙 8 Elite Gen 5），说明模型二进制是**针对高通 NPU 编译的
QNN context binary**，与 CPU/GPU 无关，更与 MTK 无关。

### 3.2 推理链路

```
相机 Java 层  com.oplus.camera.feature.aicomposition.*
   ├ CompositionStateMachine        状态机（IDLE / COMPOSING / ZOOM_TRIGGERED …）
   ├ AICompositionThread            后台推理线程（对应我们 hook 的 worker）
   ├ CompositionProcessor           调用端侧引擎、解析结果
   ├ DeepThinkManager               「深度思考」多轮/更慢档位
   └ AntiSpamUtil                   图片审核（我们 hook 过：非 PASS 直接拒绝）
        ↓ JNI
libApsAICompositionJni.so
   nativeCreate / nativeInitComposition / nativeRunComposition
   nativeRunCompositionStreaming / nativeRunStreaming / nativeDestroy
        ↓ dlsym
libai_camera_composition_lite2.so
   AICameraCompositionLite2 → QcomLite2Impl → FastViTQwenComposition
        ↓
libQnnHtp.so（Hexagon NPU，shared_buffer + weight sharing）
```

`libai_camera_composition_lite2.so` 的导出符号里能直接读到实现骨架（未 strip，
带 `.debug_info`）：

- `FastViTQwenComposition::load(Paths)` / `buildVisionLut` / `setPreprocessBase`
- `FastViTQwenComposition::generate(...)` / `generateFromImagePixels(...)`
  / `generateFromPixels(...)`（带 `GenTiming` 计时）
- `FastViTQwenComposition::rewarmCallingThread()`
- `QcomLite2Impl::init() / runStreaming(pixels, w, h, …) / runImagePath(path)`
- `qnn_runtime_preflight::…`、`qnn_provider_diagnostics::selectSystem`
- `Java_com_cv_ai_1camera_1composition_1lite2_AICameraCompositionLite2_native*`

即：**FastViT 出视觉 token → Qwen 做 prefill + 逐 token decode → 直接吐出 JSON 字符串**。

### 3.3 输出协议（dex 里保留的样例字符串，原样）

```json
{"outputNum":1,"crops":[{"zoom":3.0,"srcCrop":[306,191,562,533],
  "version":"lite2-3fcfe7d_dirty-20260512_192612","confidenceLevel":100,
  "compositionDesc":{"title":"单人局部构图",
    "desc1":"聚焦人物上半身与面部神态，保留部分桌面物品以增强场景感。",
    "desc2":"","desc3":""}}]}
```

```json
{"outputNum":2,"crops":[
 {"zoom":3.0,"srcCrop":[168,306,424,648],"confidenceLevel":100,
  "compositionDesc":{"title":"","desc1":"保留完整身形与背景环境，呈现真实工作场景。","desc2":"","desc3":""}},
 {"zoom":4.76279,"srcCrop":[208,349,369,564],"confidenceLevel":100,
  "compositionDesc":{"title":"","desc1":"聚焦人物上半身与面部神态，弱化背景干扰。","desc2":"","desc3":""}}]}
```

要点：

- **可多候选**（`outputNum` 1~2），每个候选自带 `zoom` 与 `srcCrop`；
- `srcCrop` 是源坐标系四点框，`zoom` 是建议变焦倍数（决定后续执行哪条变焦路径）；
- 文案是 `title + desc1..3` 三段式，96 token 预算足够；
- `confidenceLevel` 用于 UI 决定是否显示。

---

## 四、与 X8U 的差异（逐项实测）

| 组件 | 一加16 PYB110 | X8U PKJ110 | 说明 |
|---|---|---|---|
| `feature.aicomposition` Java 模块 | 580 个类 | 539 个类 | 两边都有，X8U 只少处理器类 |
| `CompositionStateMachine` | 有 | 有 | 一致 |
| `DeepThinkManager` | 有 | 有 | 一致 |
| `AntiSpamUtil` | 有 | 有 | 一致（我们 hook 过） |
| `CompositionProcessor` | 有 | **无** | 端侧结果处理入口 |
| `CompositionRenderer` | 有 | **无** | 端侧裁剪渲染 |
| `AICompositionThread/Track/Translate` | 有 | **无** | 端侧推理/跟踪线程 |
| `AICameraCompositionLite2` 类 | 有（JNI 引用） | **无** | 端侧引擎的 Java 门面 |
| `libApsAICompositionJni.so` | 有 | 未打包 | — |
| `libai_camera_composition_lite2.so` | 26 MB | — | 端侧引擎 |
| `ai_composition` 模型（345 MB） | 有 | — | QNN 专用 |
| 实际取景构图来源 | 端侧模型（本地，不点图标也走云端/端侧混合） | 云端插件 | 我们 hook 的那条路 |

---

## 五、能不能改进我们的灵感构图

### 5.1 不可行：把端侧模型搬到 X8U

1. 模型是 **QNN context binary**，绑 Hexagon v85 + `soc_model 103`，MTK 天玑平台
   的 NPU（APU/VPU）无法执行；
2. `libai_camera_composition_lite2.so` 依赖 `libQnnHtp.so` / `libQnnSystem.so` /
   `libQnnHtpV85Stub.so`，MTK 上没有；
3. 权重只有 `.bin`，没有原始 FastViT/Qwen 训练格式，无法重新导出成 MTK 后端；
4. X8U 的相机 APK 里连调用方（`CompositionProcessor` / `AICameraCompositionLite2`）
   都没有，补 so 也没人调。

结论：端侧这条路对我们**不可移植**，云端方案是唯一可行路径，这一点之前的判断是对的。

### 5.2 可借鉴（能实际落地的部分）

1. **输出协议对齐**
   我们现在 push 给 `onEvent(1/2, json)` 的 JSON 如果与上面的
   `outputNum/crops[]/zoom/srcCrop/confidenceLevel/compositionDesc` 完全一致，
   就能白拿 UI 侧的全部能力（多候选、标题+描述、置信度）。建议把我们的云端返回
   规范化成这个 schema，字段名不改。

2. **prompt 与预算**
   一加16 用的是一句话 prompt + `max_new_tokens = 96`：
   「请从摄影美学角度分析该图像，给出构图更优的裁剪框并解释构图思路。」
   云端插件可以用同样的措辞与输出长度约束，减少无效文本、降低延迟。

3. **多候选**
   一加16 会同时给 1~2 个候选（不同 zoom 对应不同取景意图）。我们目前若只回一个，
   可以让云端按 2 个候选返回，UI 侧本来就有 `mCompositionResults` 列表。

4. **前置过滤顺序**
   端侧是「AntiSpam 审核 → 推理 → 状态机」。我们 hook 的
   `runCompositionStreaming` 里，先把审核判 PASS 再等云端，能减少一次无效往返。

5. **异步不阻塞**
   一加16 把推理放 `AICompositionThread`，主线程只收回调。我们的
   `comp.block` 原地等待 2200 ms 属于「伪端侧」，可以保留，但要注意
   `holdingPass` 的窗口不要被 `reopenComposition()` 打断（现有实现已处理）。

### 5.3 想要端侧能力时的替代思路（成本高，仅供参考）

- 用 MTK NeuroPilot 重新导出一个小模型（例如把 Qwen2-VL 0.5B 量化成
  `.dlc`），自己实现一个 `runCompositionStreaming` 的本地实现并注入；
- 需要：模型转换、NPU 驱动授权、给相机进程加载自定义 so 的权限，
  以及最关键的——**在 X8U 的 APK 里补一个能被调用的入口**（现在没有）。
- 现实评估：工作量以月计，收益仅是省掉一次网络往返，不划算。

---

## 六、留给设备的验证命令（确认 X8U 是否真的什么都没有）

```bash
adb shell ls -l /odm/etc/camera/ai_composition/           # 期望：No such file
adb shell ls /odm/lib64/ | grep -i composition            # 期望：空
adb shell ls /apex/ | grep -i "aps.algo"                  # 看是否有 algo.model
adb shell 'grep -c AICameraCompositionLite2 /data/app/*/com.oplus.camera*/base.apk'
```

如果前三条全空，说明 X8U 从系统层就没有端侧构图，无需再尝试移植。

---

## 七、本次使用的工具（新增）

| 脚本 | 用途 |
|---|---|
| `tools/erofs_extract.py` | 纯 Python EROFS 读取器（Windows 无 erofs-utils 也能用），支持 FLAT_PLAIN / FLAT_INLINE / COMPRESSED_COMPACT（4B 与 COMPACTED_2B）/ LZ4 与 LZMA，`list/tree/find/grep/cat/dump/info` |
| `tools/dex_classes.py` | 不依赖 jadx 的 dex 类名/字符串检索（`list/grep/strings`） |
| `tools/z_erofs_selftest.py` | COMPRESSED_COMPACT 索引探针 |

已知限制：OPPO 的 mkfs 会给部分 pcluster 使用「跨 pcluster 回引 + 前导 0 填充」的
变体，目前约 0.2% 的簇解不全（表现为少数 zip 条目 CRC 不符），不影响
yaml/so/配置/字符串级分析；如需 100% 提取 APK，还要补这一块的字典前缀语义。
