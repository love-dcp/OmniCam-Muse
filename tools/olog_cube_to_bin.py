#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 .cube 3D LUT 转成 OPPO 相机 `/odm/etc/camera/LUTData/*.bin` 的二进制格式。

bin 格式（由 X9U/PMA110 的 LogVideoSDRBt709.bin 逆向得到，已用官方 O-Log2 LUT 逐点验证）:
  offset 0 : 8 字节头, 前 4 字节固定 21 10 c6 08，后 4 字节 SDR 为 00 00 6a 3c
  offset 8 : N^3 个条目, 每条目 4 × float32 (R,G,B,A)，A 恒为 1.0
  轴序     : **与 .cube 完全相同**（第 3 个分量变化最快），可直接顺序拷贝，无需重排。
             已验证：官方 O-Log2→Rec709 65^3 降采样到 33^3 后，与 X9U 原生
             LogVideoSDRBt709.bin 逐条一致（仅原生文件尾部若干条因提取损坏不符）。

用法:
  python tools/olog_cube_to_bin.py <in.cube> <out.bin> [N] [--magic-tail hh,hh,hh,hh]
"""
import sys
import struct

MAGIC = bytes([0x21, 0x10, 0xC6, 0x08])
TAIL_SDR = bytes([0x00, 0x00, 0x6A, 0x3C])   # SDR 文件观测到的 4 字节


def read_cube(path):
    size, data = None, []
    with open(path, "r", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s.upper().startswith("LUT_3D_SIZE"):
                size = int(s.split()[-1]); continue
            if s[0].isalpha():
                continue
            p = s.split()
            if len(p) >= 3:
                data.append((float(p[0]), float(p[1]), float(p[2])))
    if size is None or len(data) != size ** 3:
        raise SystemExit("bad cube: size=%s entries=%d" % (size, len(data)))
    return size, data


def cube_at(size, data, pos):
    """pos 为 0..size-1 的连续浮点坐标，三线性插值；.cube 轴序 B 最快"""
    base = [min(int(p), size - 2) for p in pos]
    fr = [pos[i] - base[i] for i in range(3)]
    acc = [0.0, 0.0, 0.0]
    for d in range(8):
        o = [(d >> 2) & 1, (d >> 1) & 1, d & 1]
        w = 1.0
        for i in range(3):
            w *= fr[i] if o[i] else (1 - fr[i])
        idx = ((base[0] + o[0]) * size + (base[1] + o[1])) * size + (base[2] + o[2])
        v = data[idx]
        for i in range(3):
            acc[i] += w * v[i]
    return acc


def convert(src, dst, N=33, tail=TAIL_SDR):
    size, data = read_cube(src)
    print("src %s: %d^3 -> %d^3" % (src, size, N))
    if size == N:
        # 顺序与 .cube 一致，直接拷贝
        body = bytearray()
        for v in data:
            body += struct.pack("<4f", v[0], v[1], v[2], 1.0)
    else:
        scale = (size - 1) / (N - 1)
        body = bytearray()
        for ri in range(N):
            for gi in range(N):
                for bi in range(N):
                    # 与 .cube 同序：第 3 个分量（b）变化最快
                    pos = [ri * scale, gi * scale, bi * scale]
                    v = cube_at(size, data, pos)
                    body += struct.pack("<4f", v[0], v[1], v[2], 1.0)
    with open(dst, "wb") as f:
        f.write(MAGIC + tail + body)
    print("wrote %s: %d bytes (expect %d)" % (dst, 8 + len(body), 8 + N ** 3 * 16))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    tail = TAIL_SDR
    if "--magic-tail" in sys.argv:
        i = sys.argv.index("--magic-tail")
        raw = sys.argv[i + 1]
        dst_arg = sys.argv[2]
        tail = bytes(int(x, 16) for x in raw.split(","))
        conv_args = sys.argv[1:3]
    else:
        conv_args = sys.argv[1:3]
    n = 33
    for a in sys.argv[3:]:
        if a.isdigit():
            n = int(a)
    convert(conv_args[0], conv_args[1], n, tail)
