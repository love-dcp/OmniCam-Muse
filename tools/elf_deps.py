#!/usr/bin/env python3
"""Read an Android/ELF shared object's dynamic dependencies without readelf.

The sandbox has no binutils, so the two questions that decide whether a
library lifted from another firmware can even load here are answered by hand:

  deps   DT_NEEDED / DT_SONAME / DT_RPATH / DT_RUNPATH, plus the architecture
  syms   imported symbols grouped by library prefix, so it is obvious which
         half of the imports only a vendor backend could provide
  undef  dump every undefined symbol

Usage:
  python tools/elf_deps.py deps <file.so> [<file.so> ...]
  python tools/elf_deps.py syms <file.so> [--top N]
  python tools/elf_deps.py undef <file.so> [--filter qnn]
"""

import struct
import sys

EM_NAMES = {40: "ARM", 183: "AArch64", 62: "x86-64", 3: "x86"}
DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH = 1, 14, 15, 29
DT_STRTAB, DT_STRSZ, DT_SYMTAB, DT_SYMENT, DT_HASH = 5, 10, 6, 11, 4
DT_GNU_HASH = 0x6FFFFEF5


class Elf:
    def __init__(self, path):
        with open(path, "rb") as handle:
            self.data = handle.read()
        if self.data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file: " + path)
        self.is64 = self.data[4] == 2
        self.little = self.data[5] == 1
        self.end = "<" if self.little else ">"
        self.machine = struct.unpack_from(self.end + "H", self.data, 18)[0]
        if self.is64:
            phoff, = struct.unpack_from(self.end + "Q", self.data, 32)
            phentsize, phnum = struct.unpack_from(self.end + "HH", self.data, 54)
        else:
            phoff, = struct.unpack_from(self.end + "I", self.data, 28)
            phentsize, phnum = struct.unpack_from(self.end + "HH", self.data, 42)
        self.loads = []
        self.dynamic = None
        for i in range(phnum):
            off = phoff + i * phentsize
            if self.is64:
                p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
                    struct.unpack_from(self.end + "IIQQQQQQ", self.data, off)
            else:
                p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = \
                    struct.unpack_from(self.end + "IIIIIIII", self.data, off)
            if p_type == 1:
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == 2:
                self.dynamic = (p_offset, p_filesz)

    def vaddr_to_off(self, vaddr):
        for l_vaddr, l_off, l_size in self.loads:
            if l_vaddr <= vaddr < l_vaddr + l_size:
                return l_off + (vaddr - l_vaddr)
        return None

    def entries(self):
        if not self.dynamic:
            return []
        off, size = self.dynamic
        step = 16 if self.is64 else 8
        fmt = self.end + ("qQ" if self.is64 else "iI")
        out = []
        for i in range(size // step):
            tag, val = struct.unpack_from(fmt, self.data, off + i * step)
            if tag == 0:
                break
            out.append((tag, val))
        return out

    def cstr(self, off):
        end = self.data.find(b"\0", off)
        if end < 0:
            end = len(self.data)
        return self.data[off:end].decode("utf-8", "replace")

    def strtab(self):
        table = size = None
        for tag, val in self.entries():
            if tag == DT_STRTAB:
                table = self.vaddr_to_off(val)
            elif tag == DT_STRSZ:
                size = val
        return table, size

    def needed(self):
        table, _ = self.strtab()
        if table is None:
            return []
        names = []
        for tag, val in self.entries():
            if tag in (DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH):
                names.append((tag, self.cstr(table + val)))
        return names

    def dynsym_count(self):
        """Symbol count from DT_HASH or DT_GNU_HASH; needed before walking dynsym."""
        table, _ = self.strtab()
        hash_off = gnu_off = None
        sym_off = None
        for tag, val in self.entries():
            if tag == DT_HASH:
                hash_off = self.vaddr_to_off(val)
            elif tag == DT_GNU_HASH:
                gnu_off = self.vaddr_to_off(val)
            elif tag == DT_SYMTAB:
                sym_off = self.vaddr_to_off(val)
        if hash_off is not None:
            # nbucket, nchain -- nchain is the symbol count.
            return struct.unpack_from(self.end + "II", self.data, hash_off)[1]
        if gnu_off is not None:
            nbuckets, symoffset, bloom_size, bloom_shift = struct.unpack_from(
                self.end + "IIII", self.data, gnu_off)
            bucket_off = gnu_off + 16 + bloom_size * (8 if self.is64 else 4)
            buckets = struct.unpack_from(self.end + "%dI" % nbuckets, self.data, bucket_off)
            last = max(buckets) if buckets else 0
            if last < symoffset:
                return symoffset
            chain_off = bucket_off + nbuckets * 4
            step = 8 if self.is64 else 4
            fmt = self.end + ("Q" if self.is64 else "I")
            while True:
                val, = struct.unpack_from(fmt, self.data, chain_off + (last - symoffset) * step)
                if val & 1:
                    return last + 1
                last += 1
        if sym_off is not None:
            return None
        return None

    def undefined(self):
        """Every imported symbol name, i.e. st_shndx == SHN_UNDEF."""
        table, size = self.strtab()
        count = self.dynsym_count()
        sym_off = None
        for tag, val in self.entries():
            if tag == DT_SYMTAB:
                sym_off = self.vaddr_to_off(val)
        if table is None or count is None or sym_off is None:
            return []
        names = []
        if self.is64:
            step, fmt = 24, self.end + "IBBHQQ"
        else:
            step, fmt = 16, self.end + "IIIBBH"
        for i in range(count):
            fields = struct.unpack_from(fmt, self.data, sym_off + i * step)
            st_name = fields[0]
            shndx = fields[1] if not self.is64 else fields[3]
            if st_name == 0 or shndx != 0:
                continue
            names.append(self.cstr(table + st_name))
        return names


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f%s" % (n, unit) if unit != "B" else "%dB" % n
        n /= 1024.0


def cmd_deps(paths):
    for path in paths:
        elf = Elf(path)
        print("== %s  (%s, %s)" % (path, EM_NAMES.get(elf.machine, elf.machine),
                                   human(len(elf.data))))
        tag_names = {DT_NEEDED: "NEEDED", DT_SONAME: "SONAME",
                     DT_RPATH: "RPATH", DT_RUNPATH: "RUNPATH"}
        rows = elf.needed()
        if not rows:
            print("   (static: no dynamic section)")
        for tag, name in rows:
            print("   %-8s %s" % (tag_names.get(tag, tag), name))
        print()


def cmd_syms(path, top=20):
    elf = Elf(path)
    undef = elf.undefined()
    groups = {}
    for name in undef:
        key = "other"
        low = name.lower()
        for prefix in ("qnn", "_zn", "java_", "__cxa", "_zdl", "dl", "pthread",
                       "mem", "str", "printf", "log"):
            if low.startswith(prefix):
                key = prefix
                break
        groups.setdefault(key, []).append(name)
    print("== %s: %d undefined symbols" % (path, len(undef)))
    for key in sorted(groups, key=lambda k: -len(groups[k])):
        names = groups[key]
        print("   %-10s %4d   e.g. %s" % (key, len(names), ", ".join(names[:3])))
    print()
    for name in undef:
        if name.lower().startswith(("qnn", "_qnn")):
            print("   ", name)


def cmd_undef(path, needle):
    elf = Elf(path)
    for name in elf.undefined():
        if not needle or needle.lower() in name.lower():
            print(name)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    mode, rest = argv[1], argv[2:]
    top = 20
    if "--top" in rest:
        i = rest.index("--top")
        top = int(rest[i + 1])
        del rest[i:i + 2]
    needle = ""
    if "--filter" in rest:
        i = rest.index("--filter")
        needle = rest[i + 1]
        del rest[i:i + 2]
    if mode == "deps":
        cmd_deps(rest)
    elif mode == "syms":
        for path in rest:
            cmd_syms(path, top)
    elif mode == "undef":
        for path in rest:
            cmd_undef(path, needle)
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
