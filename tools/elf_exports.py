"""List exported symbols of an ELF .so whose names match a pattern, with their st_value offsets."""
import struct
import sys

path = sys.argv[1]
pattern = sys.argv[2] if len(sys.argv) > 2 else "Ms"

data = open(path, "rb").read()
assert data[:4] == b"\x7fELF"
is64 = data[4] == 2
assert is64

e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]

sections = []
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from(
        "<IIQQQQIIQQ", data, off
    )
    sections.append(
        dict(name_off=name, type=typ, addr=addr, offset=offset, size=size, link=link, entsize=entsize)
    )

shstr = sections[e_shstrndx]
shstrtab = data[shstr["offset"] : shstr["offset"] + shstr["size"]]


def cstr(table, off):
    end = table.find(b"\0", off)
    return table[off:end].decode("utf-8", "replace")


dynsym = next(s for s in sections if s["type"] == 11)  # SHT_DYNSYM
strtab = sections[dynsym["link"]]
strdata = data[strtab["offset"] : strtab["offset"] + strtab["size"]]

count = dynsym["size"] // 24
hits = []
for i in range(count):
    off = dynsym["offset"] + i * 24
    st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, off)
    if st_name == 0 or st_shndx == 0:
        continue
    bind = st_info >> 4
    if bind not in (1, 2):  # GLOBAL, WEAK
        continue
    name = cstr(strdata, st_name)
    if name.startswith(pattern):
        hits.append((st_value, name, st_size))

for value, name, size in sorted(hits):
    print("%08x %6d %s" % (value, size, name))
print("# total %d exported symbols matching %r" % (len(hits), pattern))
