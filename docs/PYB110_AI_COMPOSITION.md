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

### 5.1 那个 so 能不能直接拿过来用：装得进去，跑不起来

先修正一句前面写得含糊的话：`libai_camera_composition_lite2.so` **并不链接** QNN 库。
用 `tools/elf_deps.py deps` 读它自己的动态段，NEEDED 只有五个：

```
NEEDED  liblog.so
NEEDED  libm.so
NEEDED  libc++_shared.so
NEEDED  libdl.so
NEEDED  libc.so
SONAME  libai_camera_composition_lite2_q.so
```

全是任何 Android 都有的库，所以 `dlopen` 在 X8U 上**不会因为缺库失败**。SONAME 带 `_q`
后缀，说明 OPPO 是「一个引擎 + 按厂商出变体」的构建方式。

真正拦住它的是三处硬约束，都能在二进制里读到：

| 约束 | 证据 |
|---|---|
| **SoC 白名单** | `runllm::socguard` 内嵌表只有 `SM8975 / SM8850 / SM8950` 三项，`configSocModel` 先查表 |
| **后端按路径 dlopen** | `QcomLite2Impl::init()` → `qnn_runtime_preflight::check<DynamicLoader>`，从 yaml 的 `backend_library` 取 `/odm/lib64/libQnnHtp.so`，MTK 上不存在 |
| **没有非高通后端** | 符号里只有 `QcomLite2Impl`，没有任何 CPU / SNPE / TFLite 回退路径 |

也就是说：so 装进去、`dlopen` 成功，但 `init()` 会先被 socguard 拒，再被 QNN dlopen 拒，
拿到后端也读不了权重——`vision.bin` 等是 HTP context binary，与 Hexagon v85 绑定。

另外两条有用的线索：

- `libApsAICompositionJni.so` 里有 `nativeInitComposition_dlopen` 和
  `nativeRunCompositionStreaming, streaming not supported by loaded so`，证明 JNI 层是
  **按名字 dlopen 后端**、再 `dlsym` 出 `Lite2_RunStreamingFunc` / `Lite2_RunFromRgbaFunc`。
  换句话说上层完全不在乎后端是谁——如果哪天有 MTK 变体，接口是现成的。
- 同一个 so 里还有 `TrackerAPI_trackFrameFunc` / `TrackerAPI_setTargetPointInfoFunc`，
  这就是我们之前判定「本机缺 tracker」的那个东西，它和构图引擎是同一个包发出来的。

补充：`libApsAICompositionJni.so` 本次提取到 0x54000–0x57FFF 是空洞（动态段正在其中），
所以它的 NEEDED 表只能从字符串区反推（`libvndksupport/libdl/libc/liblog/libandroid/
libnativewindow/libm`，同样全是平台库）。如果需要完整文件，得先补 EROFS 跨 pcluster 回引。

结论：**这条路走不通**，而且不是「改个配置」能通的——socguard 是写死的表，QNN 是别家的 NPU。

### 5.2 可借鉴（已落地的部分）

1. **输出协议对齐** — *原本就一致，未改动*
   `CloudComposer.jsonOf()` 吐的就是
   `outputNum/crops[]/zoom/srcCrop/version/confidenceLevel/compositionDesc`，字段名一个不差。

2. **多候选用模型自己的框** — *已改*
   原实现把云端返回的多余 box 全部丢弃，只取第一个，然后**伪造**一个同中心的 tighter
   候选凑两张卡（注释里也写着「相机每次事件只收一个建议」）。一加16 的采样证明
   `outputNum:2` 且每个 crop 自带独立 `zoom` 与 `compositionDesc` 是完全合法的，
   所以现在改成：最多收 2 个真框，各自算 zoom、各自写描述；只有模型确实只给一个框
   （或两个框被 zoom 下限挤到同一档）时，才用 `tighter()` 合成第二张。

3. **候选间距与三段式文案** — *已改*
   注释写「stock 是 3.0x 和 4.76x，比例 1.59，卡片行按那个形状排」，代码里却写的
   `TIGHT_RATIO = 1.15`——两张卡几乎重合。已改回 `1.59`。
   同时照一加16 的 `title + desc1..3` 结构：宽候选 `AI 灵感构图`、紧凑候选
   `AI 灵感特写`，`desc1` 用模型给的 ratio 解读，`desc2` 由裁剪框中心算出
   「取景中心偏左、居中高度」，不再是一句万能套话。

4. **审核先于请求** — *已改*
   端侧顺序是「AntiSpam 审核 → 推理」。我们原来是「先发云端请求 → 相机再拿裁好的图
   去 AIUnit 审核 → 超时/失败才被我们改成 PASS」，等于每张照片白跑一次上传。
   现在 hook `AntiSpamUtil.e(Bitmap)`（发起审核那个方法）**在发出去之前**就写好
   `PASS` 并回收帧：帧不出设备，条件变量不被关闭，`a(long)` 立刻返回，整个往返消失。
   `a(long)` 原本超时也返回 PASS，所以裁决从来不是决定性因素，短路没有损失。

5. **prompt + 96 token** — *无法落地*
   这条只对「自己做生成」的路径有效。我们的云端客户端是
   `AICompositionClient.process(Bitmap, AICompositionCallback)`，签名里没有 prompt，
   也不回自然语言，只回 box + ratio 标签；文案是我们自己拼的。所以照抄 prompt 无处可放，
   能借的只是「96 token 够用」这个预算感——已体现在 `desc1/desc2` 的长度控制上。

6. **异步不阻塞** — *保持现状*
   一加16 把推理放 `AICompositionThread`。我们的 `comp.block` 原地等 2200 ms 是刻意的
   「伪端侧」，`holdingPass` 窗口已处理 `reopenComposition()` 打断，不动。

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
| `tools/elf_deps.py` | 无 binutils 时读 so 的 DT_NEEDED / SONAME / 未定义符号（`deps/syms/undef`），5.1 那张表就是它出的 |
| `tools/z_erofs_selftest.py` | COMPRESSED_COMPACT 索引探针 |

已知限制：OPPO 的 mkfs 会给部分 pcluster 使用「跨 pcluster 回引 + 前导 0 填充」的
变体，目前约 0.2% 的簇解不全（表现为少数 zip 条目 CRC 不符），不影响
yaml/so/配置/字符串级分析；如需 100% 提取 APK，还要补这一块的字典前缀语义。
