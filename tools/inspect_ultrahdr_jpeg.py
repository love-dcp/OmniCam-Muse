#!/usr/bin/env python3
"""检查 Ultra HDR JPEG 的 APP 段、MPF 和增益图元数据。

这个工具只读取文件，不修改原始照片，用于区分“基础层本身偏暗”和“相册没有应用
GainMap”两种情况。输出摘要到终端，并可在指定目录生成缩小后的基础层预览。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
from pathlib import Path
from typing import Iterator

import numpy as np
from PIL import ExifTags, Image, ImageOps


def iter_jpeg_segments(data: bytes) -> Iterator[tuple[int, int, int, int, bytes]]:
    """依次返回 (marker, 段起始, 载荷起始, 段结束, 载荷)。

    从第一个 JPEG 的 SOI 开始，只读取完整 JPEG 本体；遇到 SOS 后按 EOI 结束，避免把
    O-Live 追加的视频误认为 JPEG 段。
    """

    if data[:2] != b"\xff\xd8":
        raise ValueError("文件不是以 JPEG SOI 开头")
    offset = 2
    while offset + 4 <= len(data):
        if data[offset] != 0xFF:
            raise ValueError(f"JPEG 段边界异常: {offset:#x}")
        marker_start = offset
        while offset < len(data) and data[offset] == 0xFF:
            offset += 1
        if offset >= len(data):
            break
        marker = data[offset]
        offset += 1
        if marker in {0xD8, 0xD9} or 0xD0 <= marker <= 0xD7:
            yield marker, marker_start, offset, offset, b""
            if marker == 0xD9:
                return
            continue
        if offset + 2 > len(data):
            return
        length = struct.unpack_from(">H", data, offset)[0]
        payload_start = offset + 2
        end = offset + length
        if end < payload_start or end > len(data):
            raise ValueError(f"JPEG 段长度越界: marker={marker:#x}, offset={marker_start:#x}")
        payload = data[payload_start:end]
        yield marker, marker_start, payload_start, end, payload
        offset = end
        if marker == 0xDA:
            # 熵编码区中的 0xFF 必须被 0x00 填充，RST 标记可用于多扫描 JPEG。
            # 不能直接用 data.find(b"\xff\xd9")，否则压缩数据中的偶然字节会被误判。
            scan = offset
            while scan + 1 < len(data):
                if data[scan] != 0xFF:
                    scan += 1
                    continue
                following = data[scan + 1]
                if following == 0x00 or 0xD0 <= following <= 0xD7:
                    scan += 2
                    continue
                if following == 0xD9:
                    yield 0xD9, scan, scan + 2, scan + 2, b""
                    return
                if following == 0xDA:
                    offset = scan
                    break
                raise ValueError(
                    f"JPEG 熵编码区出现未预期标记: {following:#x}, offset={scan:#x}"
                )
            else:
                raise ValueError("JPEG 主图没有 EOI")


def xmp_text(payload: bytes) -> str:
    """返回 APP1 载荷中的 XMP XML，尽量不包含 JPEG 段的前缀。"""

    start = payload.find(b"<x:xmpmeta")
    end = payload.find(b"</x:xmpmeta>")
    if start < 0 or end < start:
        start = 0
        end = len(payload)
    else:
        end += len(b"</x:xmpmeta>")
    return payload[start:end].decode("utf-8", "replace")


def describe_xmp(payload: bytes) -> dict:
    text = xmp_text(payload)
    keys = (
        "hdrgm:Version",
        "hdrgm:BaseRenditionIsHDR",
        "hdrgm:GainMapMin",
        "hdrgm:GainMapMax",
        "hdrgm:Gamma",
        "hdrgm:OffsetSDR",
        "hdrgm:OffsetHDR",
        "hdrgm:HDRCapacityMin",
        "hdrgm:HDRCapacityMax",
        "Container:Directory",
        "GCamera:MotionPhoto",
        "GCamera:MicroVideo",
        "GCamera:MotionPhotoVersion",
        "GCamera:MotionPhotoPresentationTimestampUs",
    )
    result: dict[str, object] = {
        "length": len(payload),
        "namespace_hdrgm": b"hdrgm" in payload,
        "namespace_motion_photo": b"MotionPhoto" in payload,
        "has_extended_xmp": b"http://ns.adobe.com/xmp/extension/" in payload,
        "xmp": text,
    }
    for key in keys:
        match = re.search(
            rf"{re.escape(key)}\\s*=\\s*\"([^\"]*)\"",
            text,
        )
        if match:
            result[key] = match.group(1)
    for name in (
        "GainMapMin",
        "GainMapMax",
        "Gamma",
        "OffsetSDR",
        "OffsetHDR",
        "HDRCapacityMin",
        "HDRCapacityMax",
    ):
        match = re.search(
            rf"{name}\\s*=\\s*\"([^\"]*)\"",
            text,
        )
        if match:
            result[name] = match.group(1)
    result["preview"] = text[:1000]
    return result


def xmp_jpeg_metadata(data: bytes) -> dict:
    """读取增益图 JPEG 自带的 hdrgm 元数据。"""

    for marker, _, _, _, payload in iter_jpeg_segments(data):
        if marker != 0xE1:
            continue
        if payload.startswith(b"http://ns.adobe.com/xap/1.0/\x00"):
            text = xmp_text(payload.split(b"\x00", 1)[1])
            values: dict[str, float | bool | str] = {"xmp": text}
            for name in (
                "GainMapMin",
                "GainMapMax",
                "Gamma",
                "OffsetSDR",
                "OffsetHDR",
                "HDRCapacityMin",
                "HDRCapacityMax",
            ):
                match = re.search(rf"{name}\s*=\s*\"([^\"]*)\"", text)
                if match:
                    values[name] = float(match.group(1))
            match = re.search(r'BaseRenditionIsHDR\s*=\s*"([^"]*)"', text)
            if match:
                values["BaseRenditionIsHDR"] = match.group(1)
            return values
    return {}


def reconstruct_ultrahdr(
    base: Image.Image, gain_map_data: bytes
) -> tuple[Image.Image, dict]:
    """按 Adobe HDR Gain Map 公式重建完整增益后的 SDR 表示。

    这里使用完整 HDR 容量，不模拟某个具体屏幕的亮度自适应；结果用于判断基础层和
    增益图组合后是否已经接近实况视频，或基础层本身仍明显偏暗。
    """

    metadata = xmp_jpeg_metadata(gain_map_data)
    required = ("GainMapMin", "GainMapMax", "Gamma", "OffsetSDR", "OffsetHDR")
    missing = [name for name in required if name not in metadata]
    if missing:
        raise ValueError(f"增益图缺少元数据: {', '.join(missing)}")
    minimum = float(metadata["GainMapMin"])
    maximum = float(metadata["GainMapMax"])
    gamma = float(metadata["Gamma"])
    offset_sdr = float(metadata["OffsetSDR"])
    offset_hdr = float(metadata["OffsetHDR"])
    if gamma <= 0 or maximum < minimum:
        raise ValueError(
            f"增益图参数字段异常: min={minimum}, max={maximum}, gamma={gamma}"
        )

    base_rgb = ImageOps.exif_transpose(base).convert("RGB")
    with Image.open(io.BytesIO(gain_map_data)) as gain_image:
        gain = gain_image.convert("L").resize(
            base_rgb.size, Image.Resampling.BILINEAR
        )
    gain_values = np.asarray(gain, dtype=np.float32) / 255.0
    log2_gain = minimum + np.power(gain_values, 1.0 / gamma) * (maximum - minimum)
    gain_linear = np.power(2.0, log2_gain)
    sdr = np.asarray(base_rgb, dtype=np.float32) / 255.0
    hdr = (sdr + offset_sdr) * gain_linear[..., None] - offset_hdr
    clipped = np.clip(hdr * 255.0, 0.0, 255.0).round().astype(np.uint8)
    result = Image.fromarray(clipped, "RGB")
    stats = {
        "GainMapMin": minimum,
        "GainMapMax": maximum,
        "Gamma": gamma,
        "OffsetSDR": offset_sdr,
        "OffsetHDR": offset_hdr,
        "HDRCapacityMin": metadata.get("HDRCapacityMin"),
        "HDRCapacityMax": metadata.get("HDRCapacityMax"),
        "BaseRenditionIsHDR": metadata.get("BaseRenditionIsHDR"),
        "gain_linear_min": round(float(gain_linear.min()), 6),
        "gain_linear_mean": round(float(gain_linear.mean()), 6),
        "gain_linear_max": round(float(gain_linear.max()), 6),
        "gain_ev_mean": round(float(np.log2(gain_linear).mean()), 6),
        "clipped_fraction": round(
            float(np.mean((hdr < 0.0) | (hdr > 1.0))), 6
        ),
    }
    return result, stats


def parse_mpf(payload: bytes) -> dict:
    tiff = payload
    if tiff[:4] == b"II*\x00":
        endian = "<"
    elif tiff[:4] == b"MM\x00*":
        endian = ">"
    else:
        return {"error": "MPF TIFF 字节序标记无效", "head": tiff[:16].hex().upper()}
    first_ifd = struct.unpack_from(endian + "I", tiff, 4)[0]
    count = struct.unpack_from(endian + "H", tiff, first_ifd)[0]
    entries: dict[int, tuple[int, int, int]] = {}
    for index in range(count):
        entry_offset = first_ifd + 2 + index * 12
        tag, kind, size = struct.unpack_from(endian + "HHI", tiff, entry_offset)
        value = struct.unpack_from(endian + "I", tiff, entry_offset + 8)[0]
        entries[tag] = (kind, size, value)
    if 0xB002 not in entries:
        return {"error": "MPF 没有 MPEntry", "tags": sorted(entries)}

    kind, count_bytes, value = entries[0xB002]
    if kind != 7:
        return {"error": f"MPEntry 类型异常: {kind}"}
    raw = tiff[value : value + count_bytes]
    images = []
    for offset in range(0, len(raw), 16):
        entry = raw[offset : offset + 16]
        if len(entry) < 16:
            break
        attributes, size, data_offset, dependent1, dependent2 = struct.unpack(
            endian + "IIIHH", entry
        )
        images.append(
            {
                "attributes": attributes,
                "is_primary": bool(attributes & 0x20000000),
                "type": (attributes >> 24) & 0x7,
                "size": size,
                "data_offset": data_offset,
                "dependent1": dependent1,
                "dependent2": dependent2,
            }
        )
    return {
        "byte_count": count_bytes,
        "endian": "little" if endian == "<" else "big",
        "images": images,
        "first_ifd": first_ifd,
        "entries": {hex(tag): values for tag, values in entries.items()},
    }


def image_metrics(image: Image.Image) -> dict:
    rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"), dtype=np.float32)
    maximum = rgb.max(axis=2)
    minimum = rgb.min(axis=2)
    saturation = np.divide(
        maximum - minimum,
        maximum,
        out=np.zeros_like(maximum),
        where=maximum > 0,
    )
    return {
        "width": image.width,
        "height": image.height,
        "mean_rgb": [round(float(value), 3) for value in rgb.mean(axis=(0, 1))],
        "mean_value": round(float(rgb.mean()), 3),
        "mean_saturation_255": round(float(saturation.mean() * 255.0), 3),
    }


def describe_exif(image: Image.Image) -> dict:
    result = {}
    try:
        exif = image.getexif()
    except Exception as error:
        return {"error": f"{type(error).__name__}: {error}"}
    for tag, value in exif.items():
        name = ExifTags.TAGS.get(tag, f"0x{tag:04X}")
        if isinstance(value, bytes):
            result[name] = {
                "length": len(value),
                "head": value[:32].hex().upper(),
            }
        elif isinstance(value, (str, int, float)):
            result[name] = value
        else:
            result[name] = repr(value)[:300]
    return result


def save_preview(data: bytes, path: Path, max_size: int) -> None:
    with Image.open(io.BytesIO(data)) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        image.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        image.save(path, "JPEG", quality=85)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jpeg", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--preview-size", type=int, default=960)
    args = parser.parse_args()

    data = args.jpeg.read_bytes()
    report: dict[str, object] = {
        "jpeg": str(args.jpeg.resolve()),
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest().upper(),
        "segments": [],
    }
    segments = list(iter_jpeg_segments(data))
    primary_end = segments[-1][3]
    report["primary_end"] = primary_end
    report["appended_bytes"] = len(data) - primary_end

    app1s = []
    mpf_payload = None
    mpf_tiff_base = None
    for marker, start, payload_start, end, payload in segments:
        item = {
            "marker": f"APP{marker - 0xE0}" if 0xE0 <= marker <= 0xEF else f"{marker:02X}",
            "start": start,
            "payload_start": payload_start,
            "end": end,
            "length": len(payload),
        }
        report["segments"].append(item)
        if marker == 0xE1:
            app1s.append(payload)
        elif marker == 0xE2 and payload.startswith(b"MPF\x00"):
            mpf_payload = payload[4:]
            mpf_tiff_base = payload_start + 4
        elif marker == 0xE2:
            report.setdefault("app2_heads", []).append(
                {
                    "length": len(payload),
                    "head": payload[:48].hex().upper(),
                }
            )

    report["app1"] = []
    for payload in app1s:
        if payload.startswith(b"Exif\x00\x00"):
            report["app1"].append({"type": "exif", "length": len(payload)})
        elif payload.startswith(b"http://ns.adobe.com/xap/1.0/\x00"):
            xmp = payload.split(b"\x00", 1)[1]
            report["app1"].append({"type": "xmp", **describe_xmp(xmp)})
        else:
            report["app1"].append(
                {
                    "type": "other",
                    "length": len(payload),
                    "head": payload[:32].decode("latin1", "replace"),
                }
            )

    report["mpf"] = parse_mpf(mpf_payload) if mpf_payload is not None else None
    if mpf_payload is not None:
        images = report["mpf"].get("images", [])
        for index, image in enumerate(images):
            # MPF 规范中首幅主图偏移为 0，代表从文件 SOI 开始；其余条目相对
            # MPF TIFF 头定位。该文件的增益图正好从主 JPEG 的 EOI 后开始。
            offset = 0 if index == 0 else mpf_tiff_base + image["data_offset"]
            image["absolute_offset"] = offset
            size = image["size"]
            if offset + size <= len(data):
                image["head"] = data[offset : offset + 16].hex().upper()
        first = images[0] if images else None
        if first is not None:
            primary = data[: primary_end]
            report["primary_image"] = {
                "size": len(primary),
                "sha256": hashlib.sha256(primary).hexdigest().upper(),
            }
            try:
                primary_image = Image.open(io.BytesIO(primary))
                report["primary_image"]["metrics"] = image_metrics(primary_image)
                report["primary_image"]["exif"] = describe_exif(primary_image)
            except Exception as error:
                report["primary_image"]["decode_error"] = (
                    f"{type(error).__name__}: {error}"
                )
            if args.output_dir:
                args.output_dir.mkdir(parents=True, exist_ok=True)
                save_preview(primary, args.output_dir / f"{args.jpeg.stem}_base_preview.jpg", args.preview_size)
            for index, image in enumerate(images):
                offset = image["absolute_offset"]
                size = image["size"]
                if image is first or offset + size > len(data):
                    continue
                try:
                    companion_data = data[offset : offset + size]
                    if args.output_dir:
                        companion_path = (
                            args.output_dir
                            / f"{args.jpeg.stem}_mpf_{index}.jpg"
                        )
                        companion_path.write_bytes(companion_data)
                    companion = Image.open(io.BytesIO(companion_data))
                    image["metrics"] = image_metrics(companion)
                    image["exif"] = describe_exif(companion)
                    image["hdrgm"] = xmp_jpeg_metadata(companion_data)
                    # 当前文件的增益图恒为第二幅图像；若将来格式变化，仍按 hdrgm
                    # 元数据存在与否识别，而不是硬编码 MPF 索引。
                    if "GainMapMax" in image["hdrgm"]:
                        reconstructed, stats = reconstruct_ultrahdr(
                            primary_image, companion_data
                        )
                        image["reconstructed"] = {
                            "metrics": image_metrics(reconstructed),
                            "gain": stats,
                        }
                        if args.output_dir:
                            preview = reconstructed.copy()
                            preview.thumbnail(
                                (args.preview_size, args.preview_size),
                                Image.Resampling.LANCZOS,
                            )
                            preview.save(
                                args.output_dir
                                / f"{args.jpeg.stem}_gainmap_preview.jpg",
                                "JPEG",
                                quality=85,
                            )
                except Exception as error:  # 诊断工具：保留每个 MP 条目的失败原因
                    image["decode_error"] = f"{type(error).__name__}: {error}"

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
