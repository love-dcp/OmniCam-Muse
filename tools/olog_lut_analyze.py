#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 OPPO O-Log / O-Log2 官方 .cube，反推曲线与输出变换，并生成对比表 / 降采样 33^3。

用法:
  python tools/olog_lut_analyze.py probe   <cube>          # 中性轴取样 + 反推输出变换
  python tools/olog_lut_analyze.py compare <cubeA> <cubeB> # 两个 cube 差值统计
  python tools/olog_lut_analyze.py shrink  <in.cube> <out.cube> <N>
  python tools/olog_lut_analyze.py synth   <v1|v2> <out.cube> <N> [sdr|pq|hlg]
"""
import sys, math


def read_cube(path):
    size = None
    data = []
    dom = None
    with open(path, "r", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s.upper().startswith("LUT_3D_SIZE"):
                size = int(s.split()[-1]); continue
            if s.upper().startswith("DOMAIN_MIN"):
                dom = [float(v) for v in s.split()[1:]]; continue
            if s[0].isalpha():
                continue
            p = s.split()
            if len(p) >= 3:
                data.append((float(p[0]), float(p[1]), float(p[2])))
    if size is None:
        raise SystemExit("no LUT_3D_SIZE in %s" % path)
    if len(data) != size ** 3:
        raise SystemExit("size mismatch: %d entries vs %d^3" % (len(data), size))
    return size, data


def idx(size, r, g, b):
    """标准 .cube 顺序: R 最慢, B 最快"""
    return (r * size + g) * size + b


def probe(path):
    size, data = read_cube(path)
    print("size=%d entries=%d" % (size, len(data)))
    print("%-6s %-10s %-22s" % ("code", "norm", "out(r,g,b)"))
    for i in list(range(0, size, 4)) + [size - 1]:
        v = i / (size - 1)
        rgb = [data[idx(size, i, i, i)][c] for c in range(3)]
        print("%-6d %-10.5f %.6f %.6f %.6f" % (i, v, rgb[0], rgb[1], rgb[2]))


def enc_v1(R):
    return 0.139 * math.log(R + 0.019) + 0.614


def dec_v1(P):
    return math.exp((P - 0.614) / 0.139) - 0.019


def enc_v2(x):
    return 0.0855 * math.log2(x + 0.0096) + 0.693


def dec_v2(y):
    return 2 ** ((y - 0.693) / 0.0855) - 0.0096


# Rec.2020 -> Rec.709 (linear, D65 保持)
M_2020_TO_709 = [
    [1.66049100, -0.58764114, -0.07284986],
    [-0.12455047, 1.13289990, -0.00834942],
    [-0.01815076, -0.10057889, 1.11872965],
]


def gamut_709_to_2020(rgb):
    return [sum(M_2020_TO_709[i][j] * rgb[j] for j in range(3)) for i in range(3)]


def oetf_709(x):
    x = max(0.0, min(1.0, x))
    return 1.099 * x ** 0.45 - 0.099 if x >= 0.018 else 4.5 * x


def oetf_gamma24(x):
    x = max(0.0, min(1.0, x))
    return x ** (1 / 2.4)


def pq_oetf(L):
    """L: 0..1 归一化亮度 (1.0 = 10000 nits)"""
    L = max(0.0, min(1.0, L))
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    p = L ** m1
    return ((c1 + c2 * p) / (1 + c3 * p)) ** m2


def aces_ify(rgb):
    """模拟官方 DCTL 的 D65->D60 CAT02 + AP0 矩阵 (仅供形状参考)"""
    return rgb


def synth(version, out, N, mode):
    """按官方公式合成 O-Log(v1|v2) -> Rec709 Gamma2.4 / Rec2020 PQ1000 的 3D LUT"""
    lines = ['TITLE "synthesized O-Log%s -> %s"' % ("2" if version == "v2" else " v1", mode),
             "LUT_3D_SIZE %d" % N, ""]
    dec = dec_v2 if version == "v2" else dec_v1
    for ri in range(N):
        for gi in range(N):
            for bi in range(N):
                src = [ri / (N - 1), gi / (N - 1), bi / (N - 1)]
                lin = [max(0.0, dec(s)) for s in src]          # 场景线性 (Rec2020 原色)
                if mode == "sdr":
                    g = gamut_709_to_2020(lin)
                    o = [oetf_gamma24(v) for v in g]
                elif mode == "pq":
                    o = [pq_oetf(v / 100.0) for v in lin]      # 100x 映射到 10000nit
                else:
                    o = [pq_oetf(v) for v in lin]
                lines.append("%.6f %.6f %.6f" % tuple(
                    max(0.0, min(1.0, v)) for v in o))
    open(out, "w").write("\n".join(lines) + "\n")
    print("wrote %s (%d^3)" % (out, N))


def shrink(src, out, N):
    """三线性下采样到 N^3"""
    size, data = read_cube(src)
    lines = ['TITLE "resampled from %s"' % src, "LUT_3D_SIZE %d" % N, ""]
    scale = (size - 1) / (N - 1)
    for ri in range(N):
        for gi in range(N):
            for bi in range(N):
                pos = [ri * scale, gi * scale, bi * scale]
                lo = [min(int(math.floor(p)), size - 2) for p in pos]
                fr = [pos[i] - lo[i] for i in range(3)]
                acc = [0.0, 0.0, 0.0]
                for d in range(8):
                    o = [(d >> 2) & 1, (d >> 1) & 1, d & 1]
                    w = 1.0
                    for i in range(3):
                        w *= fr[i] if o[i] else (1 - fr[i])
                    p = data[idx(size, lo[0] + o[0], lo[1] + o[1], lo[2] + o[2])]
                    for i in range(3):
                        acc[i] += w * p[i]
                lines.append("%.6f %.6f %.6f" % tuple(acc))
    open(out, "w").write("\n".join(lines) + "\n")
    print("wrote %s (%d^3)" % (out, N))


def compare(a, b):
    sa, da = read_cube(a)
    sb, db = read_cube(b)
    if sa != sb:
        raise SystemExit("sizes differ %d vs %d, shrink first" % (sa, sb))
    diffs = []
    for i in range(len(da)):
        for c in range(3):
            diffs.append(abs(da[i][c] - db[i][c]))
    diffs.sort()
    n = len(diffs)
    mean = sum(diffs) / n
    print("entries=%d  mean=%.5f  p50=%.5f  p95=%.5f  p99=%.5f  max=%.5f" % (
        len(da), mean, diffs[n // 2], diffs[int(n * .95)], diffs[int(n * .99)], diffs[-1]))
    # 中性轴逐点差
    print("%-6s %-18s %-18s %s" % ("code", "A", "B", "delta"))
    for i in range(0, sa, max(1, sa // 16)):
        pa = da[idx(sa, i, i, i)]
        pb = db[idx(sb, i, i, i)]
        print("%-6d %.5f %.5f %.5f   %.5f %.5f %.5f   %+.5f" % (
            i, pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], pa[0] - pb[0]))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "probe":
        probe(sys.argv[2])
    elif cmd == "synth":
        synth(sys.argv[2], sys.argv[3], int(sys.argv[4]),
              sys.argv[5] if len(sys.argv) > 5 else "sdr")
    elif cmd == "shrink":
        shrink(sys.argv[2], sys.argv[3], int(sys.argv[4]))
    elif cmd == "compare":
        compare(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
