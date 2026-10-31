#!/usr/bin/env python3
"""Patch com.qti.stats.caflogicaldriver.so (PKJ110 / X8U) to fix the native
CAF UBSAN sub-overflow abort that crashes camera.provider when toggling EIS /
crossing 2.5x zoom in normal video mode (master camera switch path).

Root cause (2026-10-27):
  Function at vaddr 0xc5a0 computes a stats buffer size as
  (count+1) << shift and checks int32 overflow. On overflow the compiled
  -fno-sanitize-recover code branches to a cold block (bl -> PLT ->
  __ubsan_handle_sub_overflow_minimal_abort) which aborts the whole
  provider process. This fires in daily use (106 tombstones over 2 days).

Fix:
  Redirect the three overflow-check branches (0xc5d0 b.ne -> 0xc818,
  0xc6ec / 0xc728 b.lo -> 0xc81c) to the function's own clean epilogue at
  0xc800 (mov w0,#1; ldp x20,x19; ldp x29,x30; add sp,#0x50; autiasp; ret).
  On overflow the function now simply skips the stats update and returns.
  Direct branches are not subject to BTI landing-pad checks.

  DO NOT "fix" this by patching the ubsan handler entry to `ret`:
  the call site is reached via `b.cond -> bl`, so a bare ret returns into
  the next cold bl (0xc81c) with a leaked 0x50 stack frame and a clobbered
  LR chain -> SIGILL (BTI) or stack-exhaustion SIGSEGV. Both were observed.

Usage:
  python patch_x8u_caf_ubsan.py --input caflogicaldriver.so --output caflogicaldriver_fix.so
"""
import argparse
import hashlib
import struct
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
except ImportError:
    Cs = None

EPILOGUE = 0xc800
EXPECT_ORIG = {0xc5d0: 0x54001241, 0xc6ec: 0x54000983, 0xc728: 0x540007a3}


def vaddr_to_offset(data, vaddr):
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, o)
        if p_type == 1 and p_flags & 1 and p_vaddr <= vaddr < p_vaddr + p_filesz:
            return p_offset + (vaddr - p_vaddr)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', required=True)
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    data = bytearray(open(args.input, 'rb').read())
    for va, expect in EXPECT_ORIG.items():
        foff = vaddr_to_offset(data, va)
        if foff is None:
            sys.exit(f'cannot map vaddr {va:#x}')
        orig = struct.unpack_from('<I', data, foff)[0]
        if orig != expect:
            sys.exit(f'unexpected word at {va:#x}: {orig:#x} (expected {expect:#x}) — wrong lib version?')
        new = (orig & ~(0x7FFFF << 5)) | (((EPILOGUE - va) >> 2) << 5)
        struct.pack_into('<I', data, foff, new)
        print(f'{va:#x}: {orig:#x} -> {new:#x}  (branch to cold bl redirected to epilogue {EPILOGUE:#x})')

    open(args.output, 'wb').write(bytes(data))
    print('md5:', hashlib.md5(bytes(data)).hexdigest())

    if Cs:
        md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        d = open(args.output, 'rb').read()
        ok = True
        for va in EXPECT_ORIG:
            foff = vaddr_to_offset(d, va)
            ins = list(md.disasm(d[foff:foff + 4], va))[0]
            tgt = int(ins.op_str.split('#')[-1], 16)
            print(f'  verify {va:#x}: {ins.mnemonic} {ins.op_str}')
            if tgt != EPILOGUE:
                ok = False
        if not ok:
            sys.exit('verification failed')


if __name__ == '__main__':
    main()
