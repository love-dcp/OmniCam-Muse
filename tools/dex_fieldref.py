#!/usr/bin/env python3
"""Find which methods touch a given field (class + name) inside .dex files.

Resource lookups such as R.array.maser_video_format_names_log2 cost a
const (0x14) when inlined, but a compiler that keeps the R field emits an
sget-object against the field_id table instead, and then a byte grep cannot
see it. This walks field_ids -> class_defs -> code_item and decodes the
field-access opcodes (0x52..0x6d) to report the methods.

    python tools/dex_fieldref.py <ClassDescriptor> <fieldName> <dex>...
    python tools/dex_fieldref.py --list <ClassDescriptor> <dex>   # all fields
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


def read_str(data, off):
    _, off = uleb(data, off)
    end = data.index(b"\0", off)
    return data[off:end].decode("utf-8", "replace")


class Dex:
    def __init__(self, path):
        self.path = path
        self.d = open(path, "rb").read()
        d = self.d
        if d[:4] != b"dex\n":
            raise SystemExit(f"{path}: not a dex")
        self.n_str, self.o_str = struct.unpack_from("<II", d, 0x38)
        self.n_type, self.o_type = struct.unpack_from("<II", d, 0x40)
        self.n_proto, self.o_proto = struct.unpack_from("<II", d, 0x4C)
        self.n_field, self.o_field = struct.unpack_from("<II", d, 0x50)
        self.n_method, self.o_method = struct.unpack_from("<II", d, 0x58)
        self.n_class, self.o_class = struct.unpack_from("<II", d, 0x60)

    def string(self, idx):
        if idx >= self.n_str:
            return None
        off = struct.unpack_from("<I", self.d, self.o_str + 4 * idx)[0]
        return read_str(self.d, off)

    def type(self, idx):
        if idx >= self.n_type:
            return None
        sidx = struct.unpack_from("<I", self.d, self.o_type + 4 * idx)[0]
        return self.string(sidx)

    def field(self, idx):
        if idx >= self.n_field:
            return None, None
        class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.d, self.o_field + 8 * idx)
        return self.type(class_idx), self.string(name_idx)

    def method_name(self, idx):
        if idx >= self.n_method:
            return "?"
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.d, self.o_method + 8 * idx)
        return f"{self.type(class_idx)}.{self.string(name_idx)}"


def field_ops(code, off, base, size):
    """Yield (opcode, field_idx) for field-access instructions."""
    # Some builds carry a code_item whose insns_size runs past EOF (the dex is
    # rebuilt in place and a few entries keep a stale length). Clamp instead of
    # letting struct.unpack_from raise and lose the whole file.
    size = min(size, max(0, (len(code) - base) // 2))
    i = 0
    while i < size:
        unit = struct.unpack_from("<H", code, base + 2 * i)[0]
        op = unit & 0xFF
        if 0x52 <= op <= 0x5F:  # iget/iput family, format 22c
            if i + 1 < size:
                yield op, struct.unpack_from("<H", code, base + 2 * (i + 1))[0]
            i += 2
        elif 0x60 <= op <= 0x6D:  # sget/sput family, format 21c
            if i + 1 < size:
                yield op, struct.unpack_from("<H", code, base + 2 * (i + 1))[0]
            i += 2
        elif op in (0x1A, 0x1B):
            i += 2 if op == 0x1A else 3
        elif 0x2E <= op <= 0x31 or 0x32 <= op <= 0x3D or op in (0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D):
            i += 2
        elif op == 0x26 or op == 0x27:
            i += 2
        else:
            i += 1


def walk_methods(dex, callback):
    d = dex.d
    for ci in range(dex.n_class):
        off = dex.o_class + 32 * ci
        class_idx, _f, _s, _i, _src, _a, cdo, _sv = struct.unpack_from("<8I", d, off)
        if not cdo:
            continue
        cname = dex.type(class_idx)
        p = cdo
        sf, p = uleb(d, p)
        inf, p = uleb(d, p)
        dm, p = uleb(d, p)
        vm, p = uleb(d, p)
        for _ in range(sf + inf):
            _, p = uleb(d, p)
            _, p = uleb(d, p)
        for _kind, cnt in (("D", dm), ("V", vm)):
            midx = 0
            for _ in range(cnt):
                diff, p = uleb(d, p)
                midx += diff
                _acc, p = uleb(d, p)
                co, p = uleb(d, p)
                if co:
                    callback(cname, dex.method_name(midx), co)


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    if argv[1] == "--list":
        cls, files = argv[2], argv[3:]
        for path in files:
            dex = Dex(path)
            for i in range(dex.n_field):
                c, n = dex.field(i)
                if c == cls:
                    print(f"  field[{i}] {c}->{n}")
        return 0
    cls, name, files = argv[1], argv[2], argv[3:]
    for path in files:
        dex = Dex(path)
        targets = {i for i in range(dex.n_field) if dex.field(i) == (cls, name)}
        if not targets:
            continue
        print(f"== {path} field_idx={sorted(targets)}")

        def cb(cname, mname, co):
            size = struct.unpack_from("<I", dex.d, co + 12)[0]
            hit = [op for op, fi in field_ops(dex.d, 0, co + 16, size) if fi in targets]
            if hit:
                print(f"   {cname}#{mname}  ops={[hex(h) for h in hit]}")

        walk_methods(dex, cb)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
