#!/usr/bin/env python3
"""提取 O-Live 实况 JPEG 中的主视频并生成轻量帧对照。"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps


def iter_boxes(data: bytes, start: int):
    offset = start
    while offset + 8 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        box_type = data[offset + 4 : offset + 8]
        header = 8
        if size == 1:
            if offset + 16 > len(data):
                return
            size = struct.unpack_from(">Q", data, offset + 8)[0]
            header = 16
        elif size == 0:
            size = len(data) - offset
        if size < header or offset + size > len(data):
            return
        yield box_type, offset, offset + size
        offset += size


def find_embedded_videos(data: bytes) -> list[dict]:
    candidates: list[dict] = []
    search_from = 0
    while True:
        offset = data.find(b"ftyp", search_from)
        if offset < 4:
            break
        start = offset - 4
        size = struct.unpack_from(">I", data, start)[0]
        boxes = list(iter_boxes(data, start))
        types = {box[0] for box in boxes}
        if size >= 24 and b"moov" in types and b"mdat" in types:
            end = boxes[-1][2]
            candidates.append(
                {
                    "offset": start,
                    "end": end,
                    "size": end - start,
                    "brand": data[offset + 4 : offset + 8]
                    .split(b"\0", 1)[0]
                    .decode("latin1", "replace"),
                    "boxes": [box[0].decode("latin1", "replace") for box in boxes],
                }
            )
        search_from = offset + 4
    return candidates


def frame_metrics(rgb: np.ndarray) -> dict:
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    channels = rgb.astype(np.float32)
    mean = channels.mean(axis=(0, 1))
    spread = float(np.mean(channels.max(axis=2) - channels.min(axis=2)))
    return {
        "mean_rgb": [round(float(value), 3) for value in mean],
        "mean_value": round(float(mean.mean()), 3),
        "mean_saturation": round(float(hsv[:, :, 1].mean()), 3),
        "mean_channel_spread": round(spread, 3),
    }


def fit_tile(image: np.ndarray, width: int, height: int) -> np.ndarray:
    source_height, source_width = image.shape[:2]
    scale = min(width / source_width, height / source_height)
    resized = cv2.resize(
        image,
        (max(1, int(source_width * scale)), max(1, int(source_height * scale))),
        interpolation=cv2.INTER_AREA,
    )
    tile = np.full((height, width, 3), 238, dtype=np.uint8)
    y = (height - resized.shape[0]) // 2
    x = (width - resized.shape[1]) // 2
    tile[y : y + resized.shape[0], x : x + resized.shape[1]] = resized
    return tile


def labelled_tile(
    image: np.ndarray, label: str, width: int = 480, height: int = 270
) -> np.ndarray:
    body = fit_tile(image, width, height - 34)
    tile = np.full((height, width, 3), 255, dtype=np.uint8)
    tile[34:] = body
    cv2.putText(
        tile,
        label,
        (12, 24),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.65,
        (30, 30, 30),
        1,
        cv2.LINE_AA,
    )
    return tile


def read_video_samples(path: Path) -> tuple[dict, list[tuple[float, np.ndarray]]]:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise RuntimeError(f"无法打开提取出的视频: {path}")
    declared_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))

    frames: list[np.ndarray] = []
    while True:
        ok, bgr = capture.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    capture.release()
    if not frames:
        raise RuntimeError(f"视频没有可解码帧: {path}")

    indices = sorted({0, len(frames) // 2, len(frames) - 1})
    samples = [(index / max(1, len(frames) - 1), frames[index]) for index in indices]
    info = {
        "declared_frames": declared_frames,
        "decoded_frames": len(frames),
        "fps": fps,
        "width": width,
        "height": height,
        "duration_seconds": round(len(frames) / fps, 3) if fps else None,
    }
    return info, samples


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jpeg", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prefix", default="live")
    args = parser.parse_args()

    data = args.jpeg.read_bytes()
    candidates = find_embedded_videos(data)
    if not candidates:
        raise RuntimeError("JPEG 中未找到完整的内嵌 MP4")
    primary = max(candidates, key=lambda item: item["size"])
    args.output_dir.mkdir(parents=True, exist_ok=True)
    video_path = args.output_dir / f"{args.prefix}_embedded.mp4"
    video_path.write_bytes(data[primary["offset"] : primary["end"]])

    cover = ImageOps.exif_transpose(Image.open(args.jpeg)).convert("RGB")
    cover_rgb = np.asarray(cover)
    video_info, samples = read_video_samples(video_path)
    sample_metrics = [
        {
            "position": position,
            **frame_metrics(frame),
        }
        for position, frame in samples
    ]

    labels = ("Cover", "Video first", "Video middle", "Video last")
    tiles = [labelled_tile(cover_rgb, labels[0])]
    for index, (position, frame) in enumerate(samples):
        label = f"{labels[index + 1]} @ {position:.0%}"
        tiles.append(labelled_tile(frame, label))
    contact = np.vstack((np.hstack(tiles[:2]), np.hstack(tiles[2:4])))
    contact_path = args.output_dir / f"{args.prefix}_cover_video_contact_preview.jpg"
    cv2.imwrite(
        str(contact_path),
        cv2.cvtColor(contact, cv2.COLOR_RGB2BGR),
        [cv2.IMWRITE_JPEG_QUALITY, 84],
    )

    report = {
        "jpeg": str(args.jpeg.resolve()),
        "jpeg_size": len(data),
        "embedded_candidates": candidates,
        "selected_candidate": primary,
        "extracted_video": str(video_path.resolve()),
        "cover": {
            "width": cover.width,
            "height": cover.height,
            **frame_metrics(cover_rgb),
        },
        "video": video_info,
        "video_samples": sample_metrics,
        "contact_preview": str(contact_path.resolve()),
    }
    report_path = args.output_dir / f"{args.prefix}_measurements.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "report": str(report_path.resolve()),
                "cover": report["cover"],
                "video": report["video"],
                "video_samples": report["video_samples"],
            },
            ensure_ascii=True,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
