#!/usr/bin/env python3
"""Fix the X8U (PKJ110) com.qti.stats.caflogicaldriver.so sub-overflow by SATURATION.

Why this exists (2026-10-28)
----------------------------
`2X -> 3X/6X` in video mode with super-EIS made the camera unresponsive. Root cause turned out
to be our own patch chain, not a stock bug:

  stock  : crop arithmetic underflows -> ubsan abort -> provider dies -> init restarts it ->
           state is clean, the camera just blinks. Acceptable.
  fix3   : rewrote the two `bl __ubsan_handle_sub_overflow_minimal_abort` call sites into
           `b 0xc800` (`mov w0,#1; ret`). That silences the abort but the subtraction result is
           still wrong, AND it returns early, skipping every `strh` that fills the crop struct.
           Garbage crop reaches MCTFE -> `zoomLevel [0,0,0,0]` -> IFE EPR fail 1047 ->
           chi recovery every 3.2 s leaks one 617 KB HangDumpBufferManager -> permanent hang.

This patch instead keeps control flow intact and SATURATES each underflowing subtraction to 0:

    0xc6e8: subs w17, w10, w12
    0xc6ec: b.lo  #0xc81c        ->  csel w17, w17, wzr, hs
    ...
    0xc724: subs w0,  w8,  w9
    0xc728: b.lo  #0xc81c        ->  csel w0,  w0,  wzr, hs

`csel Xd, Xn, wzr, hs` keeps the difference when there is no unsigned borrow, and yields 0 when
there is one. Execution falls through to the very next instruction, so the struct fields below
(`strh w4,[x19]`, `strh w5,[x19,#4]`, ...) still get written with a consistent, clamped value.

Usage:
    python tools/patch_x8u_caf_sat.py [--stock PATH] [-o OUT] [--check]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import struct
import sys
from pathlib import Path

from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs
from elftools.elf.elffile import ELFFile

REPO = Path(__file__).resolve().parent.parent
DEFAULT_STOCK = REPO / ".workbuddy" / "palette_debug" / "caflogicaldriver.so"
DEFAULT_OUT = REPO / ".workbuddy" / "palette_debug" / "caf_sat_v1.so"

STOCK_MD5 = "7040ae7161329dc208b9a0e35b1b77ea"

# vaddr -> (expected original word, replacement word, human note)
PATCHES = {
    0xC6EC: (0x54000983, 0x1A9F2231, "subs w17,w10,w12 ; saturate w17 to 0"),
    0xC728: (0x540007A3, 0x1A9F2000, "subs w0,w8,w9    ; saturate w0 to 0"),
}


def vaddr_to_off(elf: ELFFile, vaddr: int) -> int | None:
    for seg in elf.iter_segments():
        if seg["p_type"] != "PT_LOAD":
            continue
        lo, hi, off = seg["p_vaddr"], seg["p_vaddr"] + seg["p_filesz"], seg["p_offset"]
        if lo <= vaddr < hi:
            return off + (vaddr - lo)
    return None


def disasm_one(blob: bytes, vaddr: int) -> str:
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    for ins in md.disasm(blob, vaddr):
        return f"{ins.mnemonic} {ins.op_str}".strip()
    return "<undecodable>"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", default=str(DEFAULT_STOCK))
    ap.add_argument("-o", "--out", default=str(DEFAULT_OUT))
    ap.add_argument("--check", action="store_true", help="verify only, write nothing")
    args = ap.parse_args()

    src = Path(args.stock)
    if not src.exists():
        print(f"ERROR: stock not found: {src}", file=sys.stderr)
        return 1

    blob = bytearray(src.read_bytes())
    md5 = hashlib.md5(blob).hexdigest()
    if md5 != STOCK_MD5:
        print(f"WARNING: stock md5 {md5} != expected {STOCK_MD5}", file=sys.stderr)

    elf = ELFFile(io.BytesIO(bytes(blob)))

    ok = True
    for vaddr, (expect, repl, note) in sorted(PATCHES.items()):
        off = vaddr_to_off(elf, vaddr)
        if off is None:
            print(f"ERROR: {vaddr:#x} not mapped", file=sys.stderr)
            return 1
        cur = struct.unpack_from("<I", blob, off)[0]
        status = "OK " if cur == expect else "MISMATCH"
        print(f"[{status}] {vaddr:#08x} (off {off:#x})  cur={cur:#010x} expect={expect:#010x}  {note}")
        if cur != expect:
            ok = False
            continue
        if not args.check:
            struct.pack_into("<I", blob, off, repl)

    if not ok:
        print("Aborting: this is not the stock layout this tool was written for.", file=sys.stderr)
        return 1

    if args.check:
        print("check mode: nothing written")
        return 0

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bytes(blob))

    print("\n--- verification (patched) ---")
    elf2 = ELFFile(io.BytesIO(out.read_bytes()))
    for vaddr in sorted(PATCHES):
        off = vaddr_to_off(elf2, vaddr)
        for delta in (0, 4):
            va = vaddr + delta
            o = vaddr_to_off(elf2, va)
            if o is not None:
                print(f"  {va:#08x}: {bytes(blob[o:o+4]).hex()}  {disasm_one(bytes(blob[o:o+4]), va)}")

    print(f"\nwrote {out}")
    print(f"md5 {hashlib.md5(out.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
