#!/usr/bin/env python3
import sys
from capstone import *
from elftools.elf.elffile import ELFFile

path, start, end = sys.argv[1], int(sys.argv[2], 0), int(sys.argv[3], 0)
f = open(path, 'rb')
elf = ELFFile(f)
segs = []
for s in elf.iter_segments():
    if s['p_type'] == 'PT_LOAD':
        segs.append((s['p_vaddr'], s['p_vaddr'] + s['p_filesz'], s['p_offset']))

def v2o(v):
    for va, ve, off in segs:
        if va <= v < ve:
            return off + (v - va)
    return None

o1, o2 = v2o(start), v2o(end)
f.seek(o1)
code = f.read(o2 - o1)
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
for i in md.disasm(code, start):
    print(f"{i.address:#08x}: {i.bytes.hex():<10} {i.mnemonic:<8} {i.op_str}")
