#!/usr/bin/env python3
"""比较实况封面与内嵌视频在多种色彩解释下的对齐结果。"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path

import cv2
import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageOps


@dataclass(frozen=True)
class DecodeMode:
    name: str
    video_filter: str


MODES = (
    DecodeMode("raw", "format=rgb24"),
    # 将 HLG 码值直接按 BT.709 SDR 解释，用于识别“错误的 SDR 编码”样本。
    DecodeMode("bt709_reinterpret", "zscale=p=bt709:t=bt709:m=bt709:r=limited,format=rgb24"),
    # 标准 HLG/BT.2020 ->显示线性 -> Hable -> BT.709 SDR。
    DecodeMode(
        "hlg_hable",
        "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"
        "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=rgb24",
    ),
    DecodeMode(
        "hlg_mobius",
        "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"
        "tonemap=tonemap=mobius:desat=0,zscale=t=bt709:m=bt709:r=tv,format=rgb24",
    ),
)


def ffmpeg_bin() -> str:
    return imageio_ffmpeg.get_ffmpeg_exe()


def read_cover(path: Path, working_width: int) -> np.ndarray:
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    width, height = image.size
    scale = working_width / width
    image = image.resize((working_width, round(height * scale)), Image.Resampling.LANCZOS)
    return cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)


def video_duration(path: Path) -> float:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise RuntimeError(f"无法打开视频: {path}")
    duration = float(capture.get(cv2.CAP_PROP_FRAME_COUNT)) / max(
        1.0, float(capture.get(cv2.CAP_PROP_FPS))
    )
    capture.release()
    if duration <= 0:
        raise RuntimeError(f"无法读取视频时长: {path}")
    return duration


def extract_frame(video: Path, seconds: float, video_filter: str, output: Path) -> None:
    command = [
        ffmpeg_bin(),
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        f"{seconds:.3f}",
        "-i",
        str(video),
        "-frames:v",
        "1",
        "-vf",
        video_filter,
        str(output),
    ]
    subprocess.run(command, check=True)


def feature_homography(source: np.ndarray, target: np.ndarray) -> np.ndarray | None:
    """估计 source 到 target 的单应矩阵；画面变化小时避免强行对齐。"""
    source_gray = cv2.cvtColor(source, cv2.COLOR_BGR2GRAY)
    target_gray = cv2.cvtColor(target, cv2.COLOR_BGR2GRAY)
    sift = cv2.SIFT_create(nfeatures=5000)
    source_points, source_desc = sift.detectAndCompute(source_gray, None)
    target_points, target_desc = sift.detectAndCompute(target_gray, None)
    if source_desc is None or target_desc is None or len(source_points) < 20:
        return None

    matches = cv2.BFMatcher(cv2.NORM_L2).knnMatch(source_desc, target_desc, k=2)
    good = [first for first, second in matches if first.distance < 0.72 * second.distance]
    if len(good) < 18:
        return None

    source_xy = np.float32([source_points[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
    target_xy = np.float32([target_points[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
    matrix, mask = cv2.findHomography(source_xy, target_xy, cv2.RANSAC, 3.0)
    if matrix is None or mask is None or int(mask.sum()) < 16:
        return None
    return matrix


def resize_without_alignment(source: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    return cv2.resize(source, size, interpolation=cv2.INTER_AREA)


def align(source: np.ndarray, target: np.ndarray) -> tuple[np.ndarray, np.ndarray, str]:
    height, width = target.shape[:2]
    matrix = feature_homography(source, target)
    if matrix is None:
        aligned = resize_without_alignment(source, (width, height))
        valid = np.full((height, width), 255, dtype=np.uint8)
        return aligned, valid, "resize"

    aligned = cv2.warpPerspective(
        source,
        matrix,
        (width, height),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
    )
    valid = cv2.warpPerspective(
        np.full(source.shape[:2], 255, dtype=np.uint8),
        matrix,
        (width, height),
        flags=cv2.INTER_NEAREST,
        borderMode=cv2.BORDER_CONSTANT,
    )
    return aligned, valid, "homography"


def central_mask(shape: tuple[int, int], fraction: float = 0.72) -> np.ndarray:
    height, width = shape
    mask = np.zeros((height, width), dtype=np.uint8)
    margin_x = round(width * (1.0 - fraction) / 2.0)
    margin_y = round(height * (1.0 - fraction) / 2.0)
    mask[margin_y : height - margin_y, margin_x : width - margin_x] = 255
    return mask


def ssim(gray_a: np.ndarray, gray_b: np.ndarray, mask: np.ndarray) -> float:
    gray_a = gray_a.astype(np.float32)
    gray_b = gray_b.astype(np.float32)
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    mu_a = cv2.GaussianBlur(gray_a, (11, 11), 1.5)
    mu_b = cv2.GaussianBlur(gray_b, (11, 11), 1.5)
    sigma_a = cv2.GaussianBlur(gray_a * gray_a, (11, 11), 1.5) - mu_a * mu_a
    sigma_b = cv2.GaussianBlur(gray_b * gray_b, (11, 11), 1.5) - mu_b * mu_b
    sigma_ab = cv2.GaussianBlur(gray_a * gray_b, (11, 11), 1.5) - mu_a * mu_b
    map_value = ((2 * mu_a * mu_b + c1) * (2 * sigma_ab + c2)) / (
        (mu_a * mu_a + mu_b * mu_b + c1) * (sigma_a + sigma_b + c2)
    )
    return float(map_value[mask > 0].mean())


def metrics(reference: np.ndarray, candidate: np.ndarray, valid: np.ndarray) -> dict:
    valid = cv2.erode(valid, np.ones((9, 9), np.uint8), iterations=1)
    pixels = valid > 0
    reference_lab = cv2.cvtColor(reference, cv2.COLOR_BGR2LAB).astype(np.float32)
    candidate_lab = cv2.cvtColor(candidate, cv2.COLOR_BGR2LAB).astype(np.float32)
    lab_scale = np.array([100.0 / 255.0, 1.0, 1.0], dtype=np.float32)
    delta = (reference_lab - candidate_lab) * lab_scale
    delta_e = np.sqrt(np.sum(delta * delta, axis=2))[pixels]

    reference_hsv = cv2.cvtColor(reference, cv2.COLOR_BGR2HSV)
    candidate_hsv = cv2.cvtColor(candidate, cv2.COLOR_BGR2HSV)
    gray_reference = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
    gray_candidate = cv2.cvtColor(candidate, cv2.COLOR_BGR2GRAY)
    return {
        "cover_mean_bgr": [round(float(v), 3) for v in reference[pixels].mean(axis=0)],
        "video_mean_bgr": [round(float(v), 3) for v in candidate[pixels].mean(axis=0)],
        "cover_mean_saturation": round(float(reference_hsv[:, :, 1][pixels].mean()), 3),
        "video_mean_saturation": round(float(candidate_hsv[:, :, 1][pixels].mean()), 3),
        "delta_e76_mean": round(float(delta_e.mean()), 3),
        "delta_e76_median": round(float(np.median(delta_e)), 3),
        "delta_e76_p95": round(float(np.percentile(delta_e, 95)), 3),
        "ssim_luma": round(ssim(gray_reference, gray_candidate, valid), 4),
        "valid_fraction": round(float(np.count_nonzero(valid) / valid.size), 4),
    }


def labelled(image: np.ndarray, text: str, width: int = 360) -> np.ndarray:
    height = round(image.shape[0] * width / image.shape[1])
    resized = cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA)
    tile = np.full((height + 32, width, 3), 255, dtype=np.uint8)
    tile[32:] = resized
    cv2.putText(
        tile,
        text,
        (10, 23),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.58,
        (25, 25, 25),
        1,
        cv2.LINE_AA,
    )
    return tile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cover", type=Path)
    parser.add_argument("video", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prefix", default="live_color")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    duration = video_duration(args.video)
    middle = max(0.0, duration * 0.5)
    cover = read_cover(args.cover, 720)
    center = central_mask(cover.shape[:2])
    report: dict[str, object] = {
        "cover": str(args.cover.resolve()),
        "video": str(args.video.resolve()),
        "duration_seconds": round(duration, 3),
        "sample_seconds": round(middle, 3),
        "cover_size": [cover.shape[1], cover.shape[0]],
        "modes": {},
    }
    preview_tiles = [labelled(cover, "Cover")]
    mode_reports: dict[str, object] = {}

    for mode in MODES:
        frame_path = args.output_dir / f"{args.prefix}_{mode.name}_frame.png"
        extract_frame(args.video, middle, mode.video_filter, frame_path)
        frame = cv2.imread(str(frame_path), cv2.IMREAD_COLOR)
        if frame is None:
            raise RuntimeError(f"无法读取抽取帧: {frame_path}")
        aligned, valid, alignment = align(frame, cover)
        full_metrics = metrics(cover, aligned, valid)
        center_metrics = metrics(cover, aligned, valid & center)
        mode_reports[mode.name] = {
            "filter": mode.video_filter,
            "frame": str(frame_path.resolve()),
            "alignment": alignment,
            "full": full_metrics,
            "center": center_metrics,
        }
        preview_tiles.append(labelled(aligned, mode.name))

    report["modes"] = mode_reports
    report_path = args.output_dir / f"{args.prefix}_comparison.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    preview = np.hstack(preview_tiles)
    preview_path = args.output_dir / f"{args.prefix}_decoding_contact_preview.jpg"
    cv2.imwrite(
        str(preview_path),
        preview,
        [cv2.IMWRITE_JPEG_QUALITY, 86],
    )
    print(
        json.dumps(
            {
                "report": str(report_path.resolve()),
                "preview": str(preview_path.resolve()),
                "modes": mode_reports,
            },
            ensure_ascii=True,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
