#!/usr/bin/env python3
"""Neutralise the X8U (PKJ110) caflogicaldriver.so UNSIGNED-INTEGER sub-overflow traps by
SATURATING each subtraction at 0, keeping all control flow and struct writes intact.

Background (2026-10-28)
-----------------------
Stock behaviour on `video + super-EIS + zoom >= 2.5x`:
    crop arithmetic underflows -> ubsan abort -> provider dies -> init restarts -> state clean.
    The camera merely blinks; the user can keep zooming. Annoying but survivable.

Our former `fix3` rewrote every `bl __ubsan_handle_*_abort` into `b <function epilogue>`, i.e.
"return early, pretend success". That made things far worse:
    the subtraction result is still wrong AND every struct store after it is skipped,
    so garbage crop reaches MCTFE -> zoomLevel [0,0,0,0] -> IFE EPR fail 1047 ->
    chi recovery every 3.2 s leaks a 617 KB HangDumpBufferManager -> permanent hang.

This patch keeps the fall-through path but replaces the trap branch with a saturating select:

    subs  wX, wA, wB
    b.lo  .Lcold            ->   csel  wX, wX, wzr, hs

`csel ..., wzr, hs` keeps the difference when there is no unsigned borrow and yields 0 when there
is one. Execution continues to the next instruction, so all subsequent stores still run with a
consistent, clamped value. `csel` does not modify NZCV, so any consumer of the flags is unaffected.

Only the *unsigned* traps (`b.lo` fed by a `subs`) are handled here. Float-cast (`fcvtzu`+`b.lo`)
and signed traps (`b.vs`) have different saturation semantics and are left untouched on purpose.

Usage:
    python tools/patch_x8u_caf_sat_all.py [--stock PATH] [-o OUT] [--dry-run]
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
DEFAULT_OUT = REPO / ".workbuddy" / "palette_debug" / "caf_sat_v2.so"

STOCK_MD5 = "7040ae7161329dc208b9a0e35b1b77ea"
SUB_HANDLER = "#0x32208"
COND_HS = 2


def vaddr_to_off(elf: ELFFile, vaddr: int) -> int | None:
    for seg in elf.iter_segments():
        if seg["p_type"] != "PT_LOAD":
            continue
        lo, hi, off = seg["p_vaddr"], seg["p_vaddr"] + seg["p_filesz"], seg["p_offset"]
        if lo <= vaddr < hi:
            return off + (vaddr - lo)
    return None


def csel_w(rd: int, rn: int, cond: int = COND_HS) -> int:
    """CSEL Wd, Wn, WZR, cond (32-bit)."""
    return 0x1A800000 | (31 << 16) | (cond << 12) | (rn << 5) | rd


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", default=str(DEFAULT_STOCK))
    ap.add_argument("-o", "--out", default=str(DEFAULT_OUT))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    src = Path(args.stock)
    if not src.exists():
        print(f"ERROR: stock not found: {src}", file=sys.stderr)
        return 1

    blob = bytearray(src.read_bytes())
    md5 = hashlib.md5(blob).hexdigest()
    if md5 != STOCK_MD5:
        print(f"ERROR: stock md5 {md5} != expected {STOCK_MD5}", file=sys.stderr)
        return 1

    elf = ELFFile(io.BytesIO(bytes(blob)))
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

    insns = []
    for seg in elf.iter_segments():
        if seg["p_type"] == "PT_LOAD" and (seg["p_flags"] & 1):
            lo, hi, off = seg["p_vaddr"], seg["p_vaddr"] + seg["p_filesz"], seg["p_offset"]
            for ins in md.disasm(bytes(blob[off:off + (hi - lo)]), lo):
                insns.append(ins)

    cold = {i.address for i in insns if i.mnemonic == "bl" and i.op_str == SUB_HANDLER}
    print(f"sub-overflow cold blocks: {sorted(hex(c) for c in cold)}")

    plan = []
    skipped = []
    for idx, ins in enumerate(insns):
        if ins.mnemonic not in ("b.lo", "b.cc"):
            continue
        try:
            tgt = int(ins.op_str.lstrip("#"), 16)
        except ValueError:
            continue
        if tgt not in cold:
            continue

        prev = None
        for k in range(idx - 1, max(-1, idx - 8), -1):
            m = insns[k].mnemonic
            if m in ("subs", "sub", "cmp", "adds", "fcvtzu", "fcvtzs"):
                prev = insns[k]
                break
        if prev is None or prev.mnemonic != "subs":
            skipped.append(f"{ins.address:#08x}: predecessor={prev.mnemonic if prev else 'None'}")
            continue

        ops = [o.strip() for o in prev.op_str.split(",")]
        rd_s = ops[0]
        if not rd_s.startswith("w"):
            skipped.append(f"{ins.address:#08x}: non-w dest {rd_s}")
            continue
        try:
            rd = int(rd_s[1:])
        except ValueError:
            skipped.append(f"{ins.address:#08x}: bad dest {rd_s}")
            continue
        if rd == 31:
            skipped.append(f"{ins.address:#08x}: dest wzr")
            continue

        off = vaddr_to_off(elf, ins.address)
        orig = struct.unpack_from("<I", blob, off)[0]
        plan.append((ins.address, orig, csel_w(rd, rd), f"subs {prev.op_str} -> csel w{rd}, w{rd}, wzr, hs"))

    print(f"\n=== plan: {len(plan)} trap(s) ===")
    for addr, orig, new, note in plan:
        print(f"  {addr:#08x}  {orig:#010x} -> {new:#010x}   {note}")

    if skipped:
        print(f"\n=== skipped ({len(skipped)}) ===")
        for s in skipped:
            print(f"  {s}")

    if args.dry_run:
        print("\ndry-run: nothing written")
        return 0

    for addr, orig, new, _ in plan:
        off = vaddr_to_off(elf, addr)
        if struct.unpack_from("<I", blob, off)[0] != orig:
            print(f"ERROR: {addr:#08x} mutated underneath", file=sys.stderr)
            return 1
        struct.pack_into("<I", blob, off, new)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bytes(blob))

    print("\n=== verification ===")
    data = out.read_bytes()
    elf2 = ELFFile(io.BytesIO(data))
    md2 = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    for addr, _, _, _ in plan:
        word = data[vaddr_to_off(elf2, addr):][:4]
        ins = next(iter(md2.disasm(word, addr)))
        print(f"  {addr:#08x}: {word.hex()}  {ins.mnemonic} {ins.op_str}")

    print(f"\nwrote {out}\nmd5 {hashlib.md5(data).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
