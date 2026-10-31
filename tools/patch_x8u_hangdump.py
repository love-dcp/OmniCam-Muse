#!/usr/bin/env python3
"""
X8U (PKJ110) 普通视频防抖卡死根治补丁 —— 禁用 MCTFE0 HangDumpBufferManager 泄漏

根因（2026-10-27 实测定性）:
- 超级防抖(SUPEREIS3, opmode 0x8028)激活 MCTFE0 节点, frontend 库
  (libcamxhwlfrontend.so) 在 PostCreate(0x65510) 里无条件创建
  "HangDumpBufferManager" (纯调试 hang dump 功能)。
- 关流时只调 CmdBufferManager::Recycle (软回收单个 buffer), 从不调
  Uninitialize (销毁 manager 本体) -> 底层 617KB DMA buffer krefCount 卡 2
  泄漏, 每次开流-关流新泄漏一个, 累积不可逆。
- 泄漏 buffer 占死 IPE/BPS DMI 通道 -> 下次 streamon 固件死等
  DMI_STATUS polling timed out -> 卡死。

补丁策略:
- this[0] = HangDump buffer 数量 (节点偏移 0), 在 PostCreate 里累加子资源
  链表的 [资源+0x30] 字段得到; this[4] = PerFrameRegDumpBufferManager 数量。
- 创建门: 0x65d20 `cbz w10` (w10 = this[0])。

变体 A (--variant zero-field, 默认/推荐):
  0x656dc: str w12,[x19] -> str wzr,[x19]
  让 this[0] 恒 = 0, 创建门 + 下游 packet 声明都读到 0, 一致, 不泄漏不黑屏。

变体 B (--variant zero-gate, 备选):
  0x65d1c: ldr w10,[x19] -> mov w10,#0
  只在创建门处强制 w10=0, this[0] 保留累加后的真实值。
  用于 A 若黑屏时排查下游声明是否读 this[0]。

用法:
  python patch_x8u_hangdump.py --input libcamxhwlfrontend.so --out fe_hangfix.so [--variant A|B]
"""
import argparse
import sys


def patch_variant_a(data: bytearray):
    """改 0x656dc: str w12,[x19] -> str wzr,[x19] (this[0] 恒归零)"""
    off = 0x656DC
    orig = bytes.fromhex("6c0200b9")  # str w12, [x19]
    new = bytes.fromhex("7f0200b9")   # str wzr, [x19]
    if data[off:off + 4] != orig:
        raise SystemExit(f"变体A: 0x{off:x} 原始字节不匹配: {data[off:off+4].hex()} != {orig.hex()}")
    data[off:off + 4] = new
    return [("0x656dc", orig.hex(), new.hex(), "str w12,[x19] -> str wzr,[x19]")]


def patch_variant_b(data: bytearray):
    """改 0x65d1c: ldr w10,[x19] -> mov w10,#0 (创建门强制0)"""
    off = 0x65D1C
    orig = bytes.fromhex("6a0240b9")  # ldr w10, [x19]
    new = bytes.fromhex("0a008052")   # mov w10, #0
    if data[off:off + 4] != orig:
        raise SystemExit(f"变体B: 0x{off:x} 原始字节不匹配: {data[off:off+4].hex()} != {orig.hex()}")
    data[off:off + 4] = new
    return [("0x65d1c", orig.hex(), new.hex(), "ldr w10,[x19] -> mov w10,#0")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--variant", default="A", choices=["A", "B"])
    args = ap.parse_args()

    with open(args.input, "rb") as f:
        data = bytearray(f.read())

    if args.variant == "A":
        changes = patch_variant_a(data)
    else:
        changes = patch_variant_b(data)

    with open(args.out, "wb") as f:
        f.write(data)

    import hashlib
    print(f"补丁变体 {args.variant} 完成, 输出 {args.out}")
    print(f"md5 = {hashlib.md5(data).hexdigest()}")
    for off, o, n, desc in changes:
        print(f"  +{off} {o} -> {n}  ({desc})")


if __name__ == "__main__":
    main()
