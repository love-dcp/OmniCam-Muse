"""List ELF symbols from either .dynsym or .symtab.

The camera libraries are stripped to different degrees. Some retain only
.dynsym, while the vendor extension layers keep a normal .symtab. This utility
accepts both so reverse-engineering notes can be reproduced with one command.
"""

import argparse
import struct


def read_sections(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit("error: input is not a 64-bit ELF file")
    endian = "<" if data[5] == 1 else ">"
    shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
    shentsize = struct.unpack_from(endian + "H", data, 0x3A)[0]
    shnum = struct.unpack_from(endian + "H", data, 0x3C)[0]
    shstrndx = struct.unpack_from(endian + "H", data, 0x3E)[0]
    sections = []
    for index in range(shnum):
        offset = shoff + index * shentsize
        fields = struct.unpack_from(endian + "IIQQQQIIQQ", data, offset)
        name_offset, name_type, flags, address, file_offset, size, link, _, _, entsize = fields
        sections.append({
            "index": index,
            "name_offset": name_offset,
            "type": name_type,
            "flags": flags,
            "address": address,
            "offset": file_offset,
            "size": size,
            "link": link,
            "entsize": entsize,
        })
    string_section = sections[shstrndx]
    names = data[
        string_section["offset"]:string_section["offset"] + string_section["size"]
    ]
    for section in sections:
        section["name"] = read_c_string(names, section["name_offset"])
    return sections


def read_segments(data):
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
    phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
    segments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from(endian + "II", data, offset)
        p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = (
            struct.unpack_from(endian + "QQQQQQ", data, offset + 8))
        segments.append({
            "index": index,
            "type": p_type,
            "flags": p_flags,
            "offset": p_offset,
            "address": p_vaddr,
            "file_size": p_filesz,
            "memory_size": p_memsz,
            "align": p_align,
        })
    return segments


def read_c_string(table, offset):
    end = table.find(b"\0", offset)
    if end < 0:
        end = len(table)
    return table[offset:end].decode("utf-8", "replace")


def list_symbols(data, sections, pattern):
    endian = "<" if data[5] == 1 else ">"
    hits = []
    for section in sections:
        if section["type"] not in (2, 11):
            continue
        strings = sections[section["link"]]
        string_data = data[
            strings["offset"]:strings["offset"] + strings["size"]
        ]
        entry_size = section["entsize"] or 24
        for offset in range(
                section["offset"],
                section["offset"] + section["size"],
                entry_size):
            st_name, st_info, _, st_shndx, st_value, st_size = struct.unpack_from(
                endian + "IBBHQQ", data, offset)
            if st_name == 0 or st_shndx == 0:
                continue
            name = read_c_string(string_data, st_name)
            if pattern.lower() not in name.lower():
                continue
            hits.append((st_value, st_size, section["name"], name))
    return hits


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("binary")
    parser.add_argument("pattern", nargs="?", default="")
    parser.add_argument("--sections", action="store_true")
    parser.add_argument("--segments", action="store_true")
    args = parser.parse_args()
    data = open(args.binary, "rb").read()
    sections = read_sections(data)
    if args.sections:
        for section in sections:
            print(
                f"{section['index']:2d} {section['name']:20s} "
                f"type={section['type']:<2d} flags={section['flags']:x} "
                f"addr={section['address']:x} off={section['offset']:x} "
                f"size={section['size']:x}")
        return
    if args.segments:
        for segment in read_segments(data):
            print(
                f"{segment['index']:2d} type={segment['type']:<2d} "
                f"flags={segment['flags']:x} addr={segment['address']:x} "
                f"off={segment['offset']:x} filesz={segment['file_size']:x} "
                f"memsz={segment['memory_size']:x} align={segment['align']:x}")
        return
    hits = sorted(list_symbols(data, sections, args.pattern))
    for address, size, section, name in hits:
        print(f"{address:08x} {size:6d} {section:14s} {name}")
    print(f"# total {len(hits)} symbol(s) matching {args.pattern!r}")


if __name__ == "__main__":
    main()
