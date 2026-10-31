#!/usr/bin/env python3
import sys
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from elftools.elf.sections import SymbolTableSection

path = sys.argv[1]
f = open(path,'rb')
elf = ELFFile(f)
print("sections:", [s.name for s in elf.iter_sections()][:40])
for sec in elf.iter_sections():
    if isinstance(sec, RelocationSection) and sec.name in ('.rela.plt','.rela.dyn'):
        print(f"\n== {sec.name} ({sec.num_relocations()} entries) ==")
        symtab = elf.get_section(sec['sh_link'])
        n=0
        for r in sec.iter_relocations():
            if sec.name=='.rela.plt':
                si = r['r_info_sym']
                name = symtab.get_symbol(si).name if si else ''
                print(f"  GOT {r['r_offset']:#x} -> {name}  (reloc off {r['r_offset']:#x})")
                n+=1
                if n>200: break
