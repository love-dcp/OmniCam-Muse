#!/usr/bin/env python3
"""Find adrp/add cross references to an address in an AArch64 ELF (stripped ok).

usage:
  python tools/elf_xref.py <so> <hex-target-vaddr> [<hex>...]
  python tools/elf_xref.py <so> --str <substring>      # resolve a string, then xref it
  python tools/elf_xref.py <so> --file <hex-file-offset>

Pitfall that bit us: the adrp opcode mask must NOT include Rd (bits 4..0).
`(w & 0x9F00001F) == 0x90000000` only matches `adrp x0, ...` — every other target
register is silently skipped. Use `(w & 0x9F000000) == 0x90000000` and read Rd
separately. Same class of bug for the add, whose immediate sits at bits 21..10.
"""
import sys
from elftools.elf.elffile import ELFFile

ADRP_OP = 0x90000000
ADRP_MASK = 0x9F000000
ADD_SF64 = 0x91000000
ADD_SF32 = 0x11000000
ADD_MASK = 0xFF800000


def adrp_page(w, pc):
    immlo = (w >> 29) & 3
    immhi = (w >> 5) & 0x7FFFF
    imm = (immhi << 2) | immlo
    if imm & (1 << 20):
        imm -= 1 << 21
    return ((pc >> 12) << 12) + (imm << 12)


def load(path):
    e = ELFFile(open(path, 'rb'))
    text = e.get_section_by_name('.text')
    return e, text.data(), text['sh_addr']


def resolve(e, kind, val):
    if kind == 'str':
        out = []
        for s in e.iter_sections():
            if s['sh_type'] == 'SHT_NOBITS':
                continue
            data = s.data()
            i = data.find(val.encode())
            while i >= 0:
                out.append(s['sh_addr'] + i)
                i = data.find(val.encode(), i + 1)
        return out
    if kind == 'file':
        off = int(val, 16)
        for s in e.iter_sections():
            if s['sh_type'] == 'SHT_NOBITS':
                continue
            if s['sh_offset'] <= off < s['sh_offset'] + s['sh_size']:
                return [s['sh_addr'] + (off - s['sh_offset'])]
        return []
    return [int(val, 16)]


def xref(code, base, target, window=400):
    page = target & ~0xFFF
    lo = target & 0xFFF
    hits = []
    for off in range(0, len(code) - 4, 4):
        w = int.from_bytes(code[off:off + 4], 'little')
        if (w & ADD_MASK) not in (ADD_SF64, ADD_SF32):
            continue
        if ((w >> 10) & 0xFFF) != lo:
            continue
        rn = (w >> 5) & 0x1F
        for back in range(4, window, 4):
            po = off - back
            if po < 0:
                break
            w2 = int.from_bytes(code[po:po + 4], 'little')
            if (w2 & ADRP_MASK) == ADRP_OP and (w2 & 0x1F) == rn:
                if adrp_page(w2, base + po) == page:
                    hits.append(base + off)
                break
    return hits


def main():
    path = sys.argv[1]
    args = sys.argv[2:]
    if not args:
        print(__doc__)
        return
    e, code, base = load(path)
    if args[0] in ('--str', '--file'):
        targets = resolve(e, args[0][2:], args[1])
    else:
        targets = [int(a, 16) for a in args]
    for t in targets:
        hits = xref(code, base, t)
        print('target %#x  refs %d' % (t, len(hits)))
        for h in hits:
            print('    add@%#x' % h)


if __name__ == '__main__':
    main()
