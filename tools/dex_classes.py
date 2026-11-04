#!/usr/bin/env python3
"""List / grep class descriptors inside .dex files without jadx (which hangs
on these OPPO builds).  Pure struct parsing of the dex header tables.

    python tools/dex_classes.py list  <dex>...            # all class descriptors
    python tools/dex_classes.py grep  <pattern> <dex>...  # substring match
    python tools/dex_classes.py strings <pattern> <dex>... # match in string pool
"""
import struct
import sys


def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def read_string(data, sid_off):
    n, off = uleb(data, sid_off)
    end = data.index(b"\0", off)
    return data[off:end].decode("utf-8", "replace")


class Dex:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        d = self.data
        if d[:4] != b"dex\n":
            raise SystemExit(f"{path}: not a dex")
        (self.string_ids_size, self.string_ids_off) = struct.unpack_from("<II", d, 0x38)
        (self.type_ids_size, self.type_ids_off) = struct.unpack_from("<II", d, 0x40)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from("<II", d, 0x60)

    def string_at(self, idx):
        if idx >= self.string_ids_size:
            return ""
        (off,) = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)
        return read_string(self.data, off)

    def type_at(self, idx):
        if idx >= self.type_ids_size:
            return ""
        (sid,) = struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)
        return self.string_at(sid)

    def classes(self):
        for i in range(self.class_defs_size):
            (cidx,) = struct.unpack_from("<I", self.data, self.class_defs_off + i * 32)
            yield self.type_at(cidx)


def main(argv):
    action = argv[1]
    if action == "list":
        for f in argv[2:]:
            for c in Dex(f).classes():
                print(c)
        return 0
    pat = argv[2].lower()
    for f in argv[3:]:
        dx = Dex(f)
        if action == "grep":
            for c in dx.classes():
                if pat in c.lower():
                    print(f"{f}: {c}")
        elif action == "strings":
            for i in range(dx.string_ids_size):
                s = dx.string_at(i)
                if pat in s.lower():
                    print(f"{f}: {s}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
