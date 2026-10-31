#!/usr/bin/env python3
"""
patch_x8u_streamon_timeout.py — 修改 camera.qcom.so 中 Pipeline::StreamOn 的等待超时默认值。

背景（2026-10-28 定位）：
  日志 `camxpipeline.cpp:1371 Stream On for pipeline[%s] timed out(%d)`
  打印的数字来自 Pipeline 对象 [this+0x69c] 成员，在构造函数中由
    mov w12, #0xb54        (0x52816A8C @ file/vaddr 0x39adec)   ; 2900ms
    str w12, [x0, #0x69c]
  初始化。全库 movz #2900 仅此一处（无第二处常量）。

  该成员在 StreamOn 中被读取后作为 w2 传给 TimedWait：
    0x3adc60: ldr w2, [x19, #0x69c]
    0x3adc6c: bl  #0x99b7f0                    ; 等待 CSL config done，超时=2900
    0x3adc70: cbnz w0, #0x3adf08               ; 非0=超时 → 走错误路径
  0x3adf58 / 0x3ae01c / 0x3ae088 另有三处 ldr 仅供错误日志打印该值。

  实证（repro2，14:49:44）：2900ms 报超时后仅 309ms 该 StreamOn 实际成功，
  说明真正需要的是更宽的等待窗口，而非配置错误。

用法：
  python tools/patch_x8u_streamon_timeout.py --ms 5000
产物：work/hangdump/camera.qcom.streamon<N>.so
"""
import argparse
import hashlib
import shutil
import struct
from pathlib import Path

SRC = Path("work/hangdump/camera.qcom.so")
# (file_offset == vaddr, 因为该 ELF 第一 PT_LOAD 段 vaddr==offset)
PATCH_OFF = 0x39ADEC
ORIG_WORD = 0x52816A8C  # mov w12, #0xb54  (2900)


def movz(reg: int, imm: int, shift: int = 0) -> int:
    assert 0 <= imm <= 0xFFFF
    hw = shift // 16
    return 0x52800000 | (hw << 21) | (imm << 5) | reg


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ms", type=int, default=5000, help="新的超时毫秒值（<=65535）")
    ap.add_argument("--src", default=str(SRC))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    if not (0 < args.ms <= 0xFFFF):
        raise SystemExit("ms 必须在 1..65535 之间（movz 立即数上限）")

    src = Path(args.src)
    out = Path(args.out) if args.out else src.with_name(f"camera.qcom.streamon{args.ms}.so")
    data = bytearray(src.read_bytes())

    cur = struct.unpack_from("<I", data, PATCH_OFF)[0]
    if cur != ORIG_WORD:
        raise SystemExit(
            f"0x{PATCH_OFF:x} 处指令不匹配：期望 0x{ORIG_WORD:08x}，实际 0x{cur:08x}"
        )

    new = movz(12, args.ms)
    struct.pack_into("<I", data, PATCH_OFF, new)

    # 自校验：重新读取
    chk = struct.unpack_from("<I", data, PATCH_OFF)[0]
    assert chk == new

    # 逐字节比对，确认改动只落在这一条指令内（4 字节窗口，实际可能只差 1~4 字节）
    orig_bytes = src.read_bytes()
    diffs = [i for i in range(len(orig_bytes)) if orig_bytes[i] != data[i]]
    if not diffs or diffs[0] < PATCH_OFF or diffs[-1] >= PATCH_OFF + 4:
        raise SystemExit(f"改动越界：{len(diffs)} 字节，范围 0x{diffs[0]:x}..0x{diffs[-1]:x}")

    out.write_bytes(bytes(data))
    md5 = hashlib.md5(bytes(data)).hexdigest()
    print(f"src  {src}  {len(orig_bytes)} bytes  md5={hashlib.md5(orig_bytes).hexdigest()}")
    print(f"out  {out}  {len(data)} bytes  md5={md5}")
    print(f"patch @0x{PATCH_OFF:x}: 0x{ORIG_WORD:08x} (mov w12,#2900) -> "
          f"0x{new:08x} (mov w12,#{args.ms})")
    print(f"changed_bytes={len(diffs)} (全部落在 0x{PATCH_OFF:x} 的 4 字节指令内，其余逐字节一致)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
