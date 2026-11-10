"""生成 LUT 直出关闭/开启的压缩对比图与基础色彩统计。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(r"E:\OmniCam-Muse-main")
OUTPUT_DIR = ROOT / "output" / "lut_preview_fix"
OFF_PATH = OUTPUT_DIR / "r170_direct_off_second.png"
ON_PATH = OUTPUT_DIR / "r170_direct_on.png"
LUT_PATH = OUTPUT_DIR / "r170_current_lut.cube"
FULL_PREVIEW_PATH = OUTPUT_DIR / "r170_direct_ab_preview.jpg"
DETAIL_PREVIEW_PATH = OUTPUT_DIR / "r170_direct_ab_detail_preview.jpg"
METRICS_PATH = OUTPUT_DIR / "r170_direct_ab_metrics.json"

# 取景器中央固定区域，避开顶部状态栏、参数栏、快门和浮动图标。
ROI_BOX = (250, 400, 830, 1500)


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"))


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for font_path in (
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\msyhbd.ttc"),
        Path(r"C:\Windows\Fonts\simhei.ttf"),
    ):
        if font_path.exists():
            return ImageFont.truetype(str(font_path), size=size)
    return ImageFont.load_default()


def color_metrics(image: np.ndarray) -> dict[str, object]:
    hsv = cv2.cvtColor(image, cv2.COLOR_RGB2HSV)
    lab = cv2.cvtColor(image, cv2.COLOR_RGB2LAB).astype(np.float32)
    lab[:, :, 0] *= 100.0 / 255.0
    lab[:, :, 1:] -= 128.0

    rgb_float = image.astype(np.float32)
    red_green = rgb_float[:, :, 0] - rgb_float[:, :, 1]
    yellow_blue = (
        0.5 * (rgb_float[:, :, 0] + rgb_float[:, :, 1]) - rgb_float[:, :, 2]
    )
    colorfulness = float(
        np.hypot(red_green.std(), yellow_blue.std())
        + 0.3 * np.hypot(red_green.mean(), yellow_blue.mean())
    )

    return {
        "mean_rgb": [round(float(value), 2) for value in image.mean(axis=(0, 1))],
        "mean_lab": [round(float(value), 2) for value in lab.mean(axis=(0, 1))],
        "mean_saturation_percent": round(float(hsv[:, :, 1].mean() * 100.0 / 255.0), 2),
        "colorfulness": round(colorfulness, 2),
    }


def load_cube(path: Path) -> np.ndarray:
    size = 0
    points: list[tuple[float, float, float]] = []
    with path.open("r", encoding="utf-8") as source:
        for raw_line in source:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("LUT_3D_SIZE"):
                size = int(line.split()[-1])
                continue
            if line.startswith(("TITLE", "DOMAIN_MIN", "DOMAIN_MAX")):
                continue
            points.append(tuple(float(value) for value in line.split()))
    if size <= 0 or len(points) != size**3:
        raise ValueError(f"LUT 点数异常：size={size} points={len(points)}")
    # .cube 的第一维变化最快，依次是 R、G、B；转成 [B, G, R, RGB] 便于查表。
    return np.asarray(points, dtype=np.float32).reshape(size, size, size, 3)


def apply_cube(image: np.ndarray, cube: np.ndarray) -> np.ndarray:
    size = cube.shape[0]
    normalized = image.astype(np.float32) / 255.0
    position = np.clip(normalized, 0.0, 1.0) * (size - 1)
    lower = np.floor(position).astype(np.int32)
    upper = np.minimum(lower + 1, size - 1)
    fraction = (position - lower).astype(np.float32)

    blue_lo, green_lo, red_lo = (lower[:, :, 2], lower[:, :, 1], lower[:, :, 0])
    blue_hi, green_hi, red_hi = (upper[:, :, 2], upper[:, :, 1], upper[:, :, 0])
    blue_f, green_f, red_f = (fraction[:, :, 2], fraction[:, :, 1], fraction[:, :, 0])

    result = np.zeros(image.shape, dtype=np.float32)
    for blue_index, blue_weight in ((blue_lo, 1.0 - blue_f), (blue_hi, blue_f)):
        for green_index, green_weight in ((green_lo, 1.0 - green_f), (green_hi, green_f)):
            for red_index, red_weight in ((red_lo, 1.0 - red_f), (red_hi, red_f)):
                weight = blue_weight * green_weight * red_weight
                result += cube[blue_index, green_index, red_index] * weight[:, :, None]
    return np.clip(np.rint(result * 255.0), 0, 255).astype(np.uint8)


def resize_to_width(image: Image.Image, width: int) -> Image.Image:
    height = round(image.height * width / image.width)
    return image.resize((width, height), Image.Resampling.LANCZOS)


def draw_panel_label(
    draw: ImageDraw.ImageDraw,
    x: int,
    y: int,
    width: int,
    text: str,
    fill: tuple[int, int, int],
) -> None:
    font = load_font(34)
    bounds = draw.textbbox((0, 0), text, font=font)
    text_width = bounds[2] - bounds[0]
    text_height = bounds[3] - bounds[1]
    draw.rounded_rectangle(
        (x, y, x + width, y + 54),
        radius=16,
        fill=fill,
    )
    draw.text(
        (x + (width - text_width) / 2, y + (54 - text_height) / 2 - 4),
        text,
        font=font,
        fill=(255, 255, 255),
    )


def make_full_preview(
    off_image: Image.Image,
    on_image: Image.Image,
    metrics: dict[str, object],
) -> None:
    panel_width = 470
    panel_gap = 60
    margin_x = 60
    header_height = 78
    footer_height = 112
    off_panel = resize_to_width(off_image, panel_width)
    on_panel = resize_to_width(on_image, panel_width)
    canvas_width = margin_x * 2 + panel_width * 2 + panel_gap
    canvas_height = header_height + max(off_panel.height, on_panel.height) + footer_height
    canvas = Image.new("RGB", (canvas_width, canvas_height), (18, 18, 20))
    draw = ImageDraw.Draw(canvas)

    left_x = margin_x
    right_x = margin_x + panel_width + panel_gap
    image_y = header_height
    draw_panel_label(draw, left_x, 14, panel_width, "直出关闭", (91, 102, 112))
    draw_panel_label(draw, right_x, 14, panel_width, "直出开启", (232, 112, 27))
    canvas.paste(off_panel, (left_x, image_y))
    canvas.paste(on_panel, (right_x, image_y))

    footer_y = image_y + max(off_panel.height, on_panel.height) + 20
    summary = metrics["difference"]
    footer = (
        f"同一中央区域：平均饱和度 {summary['saturation_off_percent']:.1f}% → "
        f"{summary['saturation_on_percent']:.1f}%，平均 ΔE76 {summary['mean_delta_e76']:.1f}"
    )
    font = load_font(25)
    bounds = draw.textbbox((0, 0), footer, font=font)
    draw.text(
        ((canvas_width - (bounds[2] - bounds[0])) / 2, footer_y),
        footer,
        font=font,
        fill=(230, 230, 232),
    )
    canvas.save(FULL_PREVIEW_PATH, quality=88, optimize=True, progressive=True)


def make_detail_preview(
    off_crop: Image.Image,
    on_crop: Image.Image,
    metrics: dict[str, object],
) -> None:
    panel_width = 500
    panel_gap = 48
    margin_x = 52
    header_height = 76
    footer_height = 94
    off_panel = resize_to_width(off_crop, panel_width)
    on_panel = resize_to_width(on_crop, panel_width)
    canvas_width = margin_x * 2 + panel_width * 2 + panel_gap
    canvas_height = header_height + max(off_panel.height, on_panel.height) + footer_height
    canvas = Image.new("RGB", (canvas_width, canvas_height), (18, 18, 20))
    draw = ImageDraw.Draw(canvas)

    left_x = margin_x
    right_x = margin_x + panel_width + panel_gap
    image_y = header_height
    draw_panel_label(draw, left_x, 12, panel_width, "直出关闭", (91, 102, 112))
    draw_panel_label(draw, right_x, 12, panel_width, "直出开启", (232, 112, 27))
    canvas.paste(off_panel, (left_x, image_y))
    canvas.paste(on_panel, (right_x, image_y))

    footer_y = image_y + max(off_panel.height, on_panel.height) + 16
    off_rgb = metrics["off"]["mean_rgb"]
    on_rgb = metrics["on"]["mean_rgb"]
    footer = (
        f"平均 RGB：关闭 ({off_rgb[0]:.0f}, {off_rgb[1]:.0f}, {off_rgb[2]:.0f})  "
        f"开启 ({on_rgb[0]:.0f}, {on_rgb[1]:.0f}, {on_rgb[2]:.0f})"
    )
    font = load_font(24)
    bounds = draw.textbbox((0, 0), footer, font=font)
    draw.text(
        ((canvas_width - (bounds[2] - bounds[0])) / 2, footer_y),
        footer,
        font=font,
        fill=(230, 230, 232),
    )
    canvas.save(DETAIL_PREVIEW_PATH, quality=90, optimize=True, progressive=True)


def main() -> None:
    global OFF_PATH, ON_PATH, LUT_PATH
    global FULL_PREVIEW_PATH, DETAIL_PREVIEW_PATH, METRICS_PATH

    parser = argparse.ArgumentParser(description="生成 LUT 直出关闭/开启对比。")
    parser.add_argument("--off", type=Path, default=OFF_PATH, help="直出关闭截图")
    parser.add_argument("--on", type=Path, default=ON_PATH, help="直出开启截图")
    parser.add_argument(
        "--prefix",
        default="r170_direct_ab",
        help="输出文件名前缀，不包含扩展名。",
    )
    parser.add_argument("--lut", type=Path, default=LUT_PATH, help="当前 LUT 文件")
    args = parser.parse_args()

    OFF_PATH = args.off
    ON_PATH = args.on
    LUT_PATH = args.lut
    FULL_PREVIEW_PATH = OUTPUT_DIR / f"{args.prefix}_preview.jpg"
    DETAIL_PREVIEW_PATH = OUTPUT_DIR / f"{args.prefix}_detail_preview.jpg"
    METRICS_PATH = OUTPUT_DIR / f"{args.prefix}_metrics.json"

    off_rgb = load_rgb(OFF_PATH)
    on_rgb = load_rgb(ON_PATH)
    x1, y1, x2, y2 = ROI_BOX
    off_crop = off_rgb[y1:y2, x1:x2]
    on_crop = on_rgb[y1:y2, x1:x2]

    off_metrics = color_metrics(off_crop)
    on_metrics = color_metrics(on_crop)
    off_lab = np.asarray(off_metrics["mean_lab"], dtype=np.float32)
    on_lab = np.asarray(on_metrics["mean_lab"], dtype=np.float32)

    off_lab_pixels = cv2.cvtColor(off_crop, cv2.COLOR_RGB2LAB).astype(np.float32)
    on_lab_pixels = cv2.cvtColor(on_crop, cv2.COLOR_RGB2LAB).astype(np.float32)
    off_lab_pixels[:, :, 0] *= 100.0 / 255.0
    on_lab_pixels[:, :, 0] *= 100.0 / 255.0
    off_lab_pixels[:, :, 1:] -= 128.0
    on_lab_pixels[:, :, 1:] -= 128.0
    delta_e = np.sqrt(np.sum((off_lab_pixels - on_lab_pixels) ** 2, axis=2))
    absolute_rgb_delta = np.abs(off_crop.astype(np.int16) - on_crop.astype(np.int16))
    double_lut_image = apply_cube(off_crop, load_cube(LUT_PATH))
    double_lut_metrics = color_metrics(double_lut_image)
    double_lut_lab = cv2.cvtColor(double_lut_image, cv2.COLOR_RGB2LAB).astype(np.float32)
    double_lut_lab[:, :, 0] *= 100.0 / 255.0
    double_lut_lab[:, :, 1:] -= 128.0
    double_lut_delta = np.sqrt(np.sum((double_lut_lab - on_lab_pixels) ** 2, axis=2))

    metrics: dict[str, object] = {
        "off_file": str(OFF_PATH),
        "on_file": str(ON_PATH),
        "lut_file": str(LUT_PATH),
        "roi_box_xyxy": list(ROI_BOX),
        "off": off_metrics,
        "on": on_metrics,
        "difference": {
            "mean_lab_distance": round(float(np.linalg.norm(on_lab - off_lab)), 2),
            "mean_delta_e76": round(float(delta_e.mean()), 2),
            "median_delta_e76": round(float(np.median(delta_e)), 2),
            "p95_delta_e76": round(float(np.percentile(delta_e, 95)), 2),
            "mean_absolute_rgb_delta": [
                round(float(value), 2) for value in absolute_rgb_delta.mean(axis=(0, 1))
            ],
            "saturation_off_percent": off_metrics["mean_saturation_percent"],
            "saturation_on_percent": on_metrics["mean_saturation_percent"],
            "saturation_absolute_change_percent": round(
                float(
                    on_metrics["mean_saturation_percent"]
                    - off_metrics["mean_saturation_percent"]
                ),
                2,
            ),
        },
        "double_application_probe": {
            "description": "把关闭态画面再次通过当前 LUT，检查是否能接近开启态。",
            "predicted": double_lut_metrics,
            "mean_delta_e76_to_actual_on": round(float(double_lut_delta.mean()), 2),
            "median_delta_e76_to_actual_on": round(float(np.median(double_lut_delta)), 2),
            "p95_delta_e76_to_actual_on": round(
                float(np.percentile(double_lut_delta, 95)),
                2,
            ),
        },
    }

    off_image = Image.open(OFF_PATH).convert("RGB")
    on_image = Image.open(ON_PATH).convert("RGB")
    off_crop_image = off_image.crop(ROI_BOX)
    on_crop_image = on_image.crop(ROI_BOX)
    make_full_preview(off_image, on_image, metrics)
    make_detail_preview(off_crop_image, on_crop_image, metrics)
    METRICS_PATH.write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(metrics["difference"], ensure_ascii=False, indent=2))
    print(FULL_PREVIEW_PATH)
    print(DETAIL_PREVIEW_PATH)


if __name__ == "__main__":
    main()
