#!/usr/bin/env python3
"""解析 .plt 每个 stub 的 GOT 目标 -> 动态符号名"""
import sys
from capstone import *
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

path = sys.argv[1]
f = open(path,'rb'); elf = ELFFile(f)
rel = elf.get_section_by_name('.rela.plt'); symtab = elf.get_section(rel['sh_link'])
got2name = {}
for r in rel.iter_relocations():
    si = r['r_info_sym']
    got2name[r['r_offset']] = symtab.get_symbol(si).name if si else '?'

plt = elf.get_section_by_name('.plt')
f.seek(plt['sh_offset']); code = f.read(plt['sh_size'])
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True
insns = list(md.disasm(code, plt['sh_addr']))
i = 0
res = {}
while i < len(insns):
    ins = insns[i]
    if ins.mnemonic == 'adrp' and i+1 < len(insns) and insns[i+1].mnemonic == 'ldr':
        page = ins.operands[1].imm
        ldr = insns[i+1]
        # ldr x17, [x16, #imm]
        off = ldr.operands[1].mem.disp
        got = page + off
        res[ins.address] = (got, got2name.get(got, '<notplt>'))
        i += 3
        continue
    i += 1
want = [int(a,0) for a in sys.argv[2:]] if len(sys.argv)>2 else []
for a, (got, name) in sorted(res.items()):
    if not want or a in want:
        print(f"stub {a:#x} -> GOT {got:#x} = {name}")
