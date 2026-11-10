#!/usr/bin/env python3
"""比较 Ultra HDR 实况照片的静态基础层、增益重建层和实况封面帧。

XPAN 的静态 JPEG 与实况视频来自不同的相机处理阶段。这个工具按 JPEG XMP 指定的
PresentationTimestamp 精确抽取视频帧，并把静态基础层与 GainMap 重建层缩放到同一
画面尺寸，输出亮度、颜色和全局映射摘要，便于区分基础层偏暗与观看端未应用增益图。
"""

from __future__ import annotations

import argparse
import io
import json
import re
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

from analyze_live_photo import find_embedded_videos
from inspect_ultrahdr_jpeg import (
    iter_jpeg_segments,
    parse_mpf,
    reconstruct_ultrahdr,
)


def companion_data(data: bytes) -> tuple[bytes | None, bytes | None]:
    """返回 MPF 增益图字节和主图 XMP 文本。"""

    segments = list(iter_jpeg_segments(data))
    primary_end = segments[-1][3]
    mpf_payload = None
    mpf_tiff_base = None
    primary_xmp = None
    for marker, _, payload_start, _, payload in segments:
        if marker == 0xE1 and payload.startswith(
            b"http://ns.adobe.com/xap/1.0/\x00"
        ):
            primary_xmp = payload.split(b"\x00", 1)[1]
        if marker == 0xE2 and payload.startswith(b"MPF\x00"):
            mpf_payload = payload[4:]
            mpf_tiff_base = payload_start + 4
    if mpf_payload is None or mpf_tiff_base is None:
        return None, primary_xmp
    mpf = parse_mpf(mpf_payload)
    images = mpf.get("images", [])
    if len(images) < 2:
        return None, primary_xmp
    image = images[1]
    start = mpf_tiff_base + image["data_offset"]
    end = start + image["size"]
    if not (primary_end <= start < end <= len(data)):
        return None, primary_xmp
    return data[start:end], primary_xmp


def image_metrics(rgb: np.ndarray) -> dict:
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    value = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    return {
        "mean_rgb": [round(float(v), 3) for v in rgb.mean(axis=(0, 1))],
        "mean_value": round(float(rgb.mean()), 3),
        "mean_luma": round(float(value.mean()), 3),
        "median_luma": round(float(np.median(value)), 3),
        "p10_luma": round(float(np.percentile(value, 10)), 3),
        "p90_luma": round(float(np.percentile(value, 90)), 3),
        "mean_saturation": round(float(hsv[:, :, 1].mean()), 3),
    }


def read_frame(path: Path, seconds: float) -> tuple[np.ndarray, dict]:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise RuntimeError(f"无法打开视频: {path}")
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    index = max(0, min(count - 1, round(seconds * fps)))
    capture.set(cv2.CAP_PROP_POS_FRAMES, index)
    ok, bgr = capture.read()
    capture.release()
    if not ok:
        raise RuntimeError(f"无法读取第 {index} 帧: {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), {
        "fps": round(fps, 6),
        "frame_count": count,
        "requested_seconds": round(seconds, 6),
        "selected_frame": index,
    }


def render_contact(
    base: np.ndarray, gain: np.ndarray, video: np.ndarray, path: Path
) -> None:
    height = 720
    width = round(height * video.shape[1] / video.shape[0])

    def fit(image: np.ndarray) -> np.ndarray:
        return cv2.resize(
            image, (width, height), interpolation=cv2.INTER_AREA
        )

    base = fit(base)
    gain = fit(gain)
    video = fit(video)
    difference = cv2.absdiff(gain, video)
    difference = cv2.convertScaleAbs(difference, alpha=2.0)
    labels = ("基础层", "GainMap 重建", "实况封面帧", "重建与视频差异 x2")
    tiles = []
    for image, label in zip((base, gain, video, difference), labels):
        tile = np.full((height + 42, width, 3), 255, dtype=np.uint8)
        tile[42:] = image
        cv2.putText(
            tile,
            label,
            (12, 29),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.72,
            (20, 20, 20),
            1,
            cv2.LINE_AA,
        )
        tiles.append(tile)
    contact = np.hstack(tiles)
    cv2.imwrite(
        str(path),
        cv2.cvtColor(contact, cv2.COLOR_RGB2BGR),
        [cv2.IMWRITE_JPEG_QUALITY, 85],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jpeg", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prefix", default="cover")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    data = args.jpeg.read_bytes()
    gain_data, xmp_payload = companion_data(data)
    if gain_data is None or xmp_payload is None:
        raise RuntimeError("JPEG 缺少增益图或主图 XMP")
    xmp = xmp_payload.decode("utf-8", "replace")
    match = re.search(r"MotionPhotoPresentationTimestampUs=\"(\d+)\"", xmp)
    if not match:
        raise RuntimeError("主图 XMP 没有 MotionPhotoPresentationTimestampUs")
    cover_us = int(match.group(1))

    candidates = find_embedded_videos(data)
    if not candidates:
        raise RuntimeError("JPEG 中没有完整的内嵌视频")
    selected = max(candidates, key=lambda item: item["size"])
    video_path = args.output_dir / f"{args.prefix}_embedded.mp4"
    video_path.write_bytes(data[selected["offset"] : selected["end"]])

    with Image.open(io.BytesIO(data[: selected["offset"]])) as source:
        base_image = ImageOps.exif_transpose(source).convert("RGB")
    gain_image, gain_stats = reconstruct_ultrahdr(base_image, gain_data)
    video_frame, video_info = read_frame(video_path, cover_us / 1_000_000.0)
    height, width = video_frame.shape[:2]
    base_rgb = np.asarray(
        base_image.resize((width, height), Image.Resampling.LANCZOS)
    )
    gain_rgb = np.asarray(
        gain_image.resize((width, height), Image.Resampling.LANCZOS)
    )

    contact_path = args.output_dir / f"{args.prefix}_cover_comparison.jpg"
    render_contact(base_rgb, gain_rgb, video_frame, contact_path)
    base_metrics = image_metrics(base_rgb)
    gain_metrics = image_metrics(gain_rgb)
    video_metrics = image_metrics(video_frame)
    report = {
        "jpeg": str(args.jpeg.resolve()),
        "cover_timestamp_us": cover_us,
        "video": video_info,
        "base": base_metrics,
        "gain_map_reconstruction": {
            "metrics": gain_metrics,
            "gain": gain_stats,
        },
        "video_cover_frame": video_metrics,
        "mean_luma_ratios": {
            "base_to_video": round(
                base_metrics["mean_luma"] / video_metrics["mean_luma"], 6
            ),
            "gain_map_to_video": round(
                gain_metrics["mean_luma"] / video_metrics["mean_luma"], 6
            ),
        },
        "contact": str(contact_path.resolve()),
    }
    report_path = args.output_dir / f"{args.prefix}_comparison.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
