"""Bakes the two MONOPAN master LUTs (Leica M3 port) into the module payload.

The Opus 1.3 release ships two .MS-LUT files for MONOPAN 50 / MONOPAN 50 迫冲 that are
byte-identical *identity* tables: they only register the filter type, the look comes from the
capture pass (monopan.cpp: M3 tone, film grain from libmialgo_monopan, vignette).

On this device the camera's filter node renders the LUT in the viewfinder as well, so an identity
table would leave the preview in colour. These tables therefore carry the monochrome conversion
only - luminance with a gentle master curve - and the capture pass adds the tone and the grain on
top of it. 迫冲 keeps a little more contrast and a lifted toe, as a push-processed print does.

    python tools/bake_monopan_luts.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bake_rec709_to_serenity_mslut import bake, identity_table, load_header

PROJECT = Path(__file__).resolve().parent.parent
OUTPUT = PROJECT / "module/payload/meishe_lut"
HEADER = OUTPUT / "LeicaM9.cube.rgb.bin"

# Rec.709 luminance weights, applied on encoded values: the camera's filter node works on the
# encoded signal, and this is a look, not a colorimetric conversion.
LUMA = np.array([0.2126, 0.7152, 0.0722])


def s_curve(y: np.ndarray, contrast: float, toe: float, shoulder: float) -> np.ndarray:
    """Contrast around 0.5 plus a toe lift and a soft shoulder, all on encoded values."""
    centred = np.clip((y - 0.5) * contrast + 0.5, 0.0, 1.0)
    lifted = toe + (1.0 - toe) * centred
    knee = 1.0 - shoulder
    over = lifted > knee
    lifted = np.where(over, knee + shoulder * np.tanh((lifted - knee) / max(shoulder, 1e-6)), lifted)
    return np.clip(lifted, 0.0, 1.0)


def mono_table(size: int, contrast: float, toe: float, shoulder: float) -> np.ndarray:
    rgb = identity_table(size)  # [b, g, r, 3]
    y = rgb @ LUMA
    out = s_curve(y, contrast, toe, shoulder)
    return np.repeat(out[..., None], 3, axis=-1)


# (file name, contrast, toe, shoulder)
TABLES = [
    ("JG_MONOPAN50.cube.rgb.bin", 1.12, 0.000, 0.14),
    ("JG_MONOPAN50PRO.cube.rgb.bin", 1.26, 0.018, 0.10),
]


def main() -> None:
    header = load_header(HEADER)
    dmin = np.zeros(3)
    dmax = np.ones(3)
    for name, contrast, toe, shoulder in TABLES:
        table = mono_table(32, contrast, toe, shoulder)
        payload = bake(table, dmin, dmax, "srgb", "css4")
        (OUTPUT / name).write_bytes(header + payload.tobytes())
        print("wrote %s (contrast %.2f, toe %.3f)" % (name, contrast, toe))


if __name__ == "__main__":
    main()
