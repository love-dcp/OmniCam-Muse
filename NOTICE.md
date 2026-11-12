# 第三方来源

OmniCam Muse 以 GPL-3.0 发布（[LICENSE](LICENSE)）。下列部分来自第三方项目，保留各自的许可。

## PhotonCamera（bjzhou 分支）

- 来源：https://github.com/bjzhou/PhotonCamera
- 许可：Apache License 2.0，全文见 [LICENSES/Apache-2.0-PhotonCamera.txt](LICENSES/Apache-2.0-PhotonCamera.txt)
- 涉及：`app/src/main/cpp/master_canon.*`（Canon 照片风格引擎）、`app/src/main/cpp/master_dt.*`（AgX、darktable sigmoid / filmic 影调引擎）、`tools/mastercolor/engines_canon.py`、`tools/mastercolor/engines_dt.py`，以及由这些引擎生成的 `module/payload/master_color/looks/` 里的 `canon_*`、`agx`、`filmic`、`sigmoid` 表。

## Spektrafilm

- 来源：https://github.com/andreavolpato/spektrafilm（作者 Andrea Volpato），经 PhotonCamera 的 `cpp/spektrafilm` 实现调用
- 代码许可：GPL-3.0。涉及 `tools/mastercolor/spektrafilm_driver.cpp`、`tools/mastercolor/looks.py` 的胶片部分。
- 胶片配置文件与导出的 LUT 许可：CC BY-SA 4.0，全文见 [LICENSES/SPEKTRAFILM_LICENSE.txt](LICENSES/SPEKTRAFILM_LICENSE.txt)。涉及 `module/payload/master_color/looks/film_*.bin` 与 `module/payload/master_color/looks/tables/`。

## Lumix 照片风格

`module/payload/master_color/looks/` 里的 Lumix 表由 Panasonic Lumix S9 的照片风格数据整理而来。

## OPPO 相机数据与计算内核

- `module/payload/master_color/looks/x8u/LMTLut0..3` 来自 Find X8 Ultra（PKJ110 17.0.0.104）的原厂大师 LMT。
- `app/src/main/cpp/ccm_dump_source.h` 的采样、白平衡与 CCM 计算前缀整理自 PMA110 原厂 `libOPAlgoCamHybridRaw.so` 的 ProProc OpenCL 内核，用于同帧线性 RGB 转储。

## OMaster —— 大师模式调色参数库

- 来源：https://github.com/iCurrer/OMaster
- 作者：Silas（[@iCurrer](https://github.com/iCurrer)）、Luminary（[@fengyec2](https://github.com/fengyec2)），以及各条预设各自的署名作者（见每条的 `author` 字段）。
- 许可：CC BY-NC-SA 4.0（署名 — 非商业性使用 — 相同方式共享）。
- 涉及：`app/src/main/assets/master_presets.txt` 里内置的预设参数与拍摄建议；`app/src/main/assets/master_presets/*.webp`（各条预设的封面与样片，取自 OMaster 内置资源并等比缩小转码）；以及设置页「大师预设」的数据模型与呈现方式（`app/src/main/java/local/jiege/settings/MasterPresetLibrary.java`、`app/src/main/kotlin/local/jiege/ui/MasterPresetScreen.kt`）。
- 说明：OmniCam Muse 是免费、非商业的模块，按该许可保留署名，并以相同方式共享上述数据文件与图片（`master_presets.txt`、`master_presets/*.webp` 及其导出的副本同样为 CC BY-NC-SA 4.0）。预设的数值、拍摄建议与封面照片著作权归原作者及各位预设作者，本模块只做收录与展示，不对其内容主张权利；图片仅为适配手机列表做了等比缩放与有损压缩，未做裁剪以外的修改。
- 未使用：本模块没有引入 OMaster 的源代码。

