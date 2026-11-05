#!/usr/bin/env python3
"""Find which methods reference a given string constant inside .dex files.

dexdump refuses these OPPO builds (bad checksum / non-zero padding) and jadx
hangs, so this walks the dex tables directly:

    string_ids address the pool, class_defs give class_data, class_data gives
    encoded_method with a code_off, and a code_item's insns carry
    const-string (0x1a) / const-string/jumbo (0x1b) with a string index.

    python tools/dex_strref.py <string> <dex>...        # class#method hits
    python tools/dex_strref.py --near <string> <dex>    # pool neighbours

--near prints the strings whose pool index is close to the match, which is
usually enough to guess the option list a label belongs to.
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
        self.n_method, self.o_method = struct.unpack_from("<II", d, 0x58)
        self.n_class, self.o_class = struct.unpack_from("<II", d, 0x60)

    def string(self, idx):
        if idx >= self.n_str:
            return None
        off = struct.unpack_from("<I", self.d, self.o_str + 4 * idx)[0]
        return read_str(self.d, off)

    def type(self, idx):
        sidx = struct.unpack_from("<I", self.d, self.o_type + 4 * idx)[0]
        return self.string(sidx)

    def method_name(self, idx):
        if idx >= self.n_method:
            return "?"
        off = self.o_method + 8 * idx
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.d, off)
        return f"{self.type(class_idx)}.{self.string(name_idx)}"

    def class_name(self, class_idx):
        return self.type(class_idx)

    def insns_string_refs(self, code_off):
        d = self.d
        insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        base = code_off + 16
        refs = []
        i = 0
        while i < insns_size:
            unit = struct.unpack_from("<H", d, base + 2 * i)[0]
            op = unit & 0xFF
            if op == 0x1A:  # const-string vAA, string@BBBB
                if i + 1 < insns_size:
                    refs.append(struct.unpack_from("<H", d, base + 2 * (i + 1))[0])
                i += 2
            elif op == 0x1B:  # const-string/jumbo vAA, string@BBBBBBBB
                if i + 2 < insns_size:
                    lo = struct.unpack_from("<H", d, base + 2 * (i + 1))[0]
                    hi = struct.unpack_from("<H", d, base + 2 * (i + 2))[0]
                    refs.append(lo | (hi << 16))
                i += 3
            elif 0x2E <= op <= 0x31:  # branches with a payload offset: skip target
                i += 2
            elif op in (0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D):  # goto/switch
                i += 2
            elif op == 0x26 or op == 0x27:  # fill-array-data / packed-switch payload
                i += 2
            elif 0x32 <= op <= 0x3D:  # cmp branches
                i += 2
            else:
                i += 1
        return refs


def walk_methods(dex, callback):
    d = dex.d
    for ci in range(dex.n_class):
        off = dex.o_class + 32 * ci
        class_idx, _flags, _super, _ifaces, _src, _anno, class_data_off, _sv = \
            struct.unpack_from("<8I", d, off)
        cname = dex.class_name(class_idx)
        if not class_data_off:
            continue
        p = class_data_off
        sf, p = uleb(d, p)
        inf, p = uleb(d, p)
        dm, p = uleb(d, p)
        vm, p = uleb(d, p)
        for _ in range(sf + inf):
            _, p = uleb(d, p)
            _, p = uleb(d, p)
        for kind, count in (("direct", dm), ("virtual", vm)):
            midx = 0
            for _ in range(count):
                try:
                    diff, p = uleb(d, p)
                    midx += diff
                    _acc, p = uleb(d, p)
                    code_off, p = uleb(d, p)
                except IndexError:
                    # Truncated class_data in a few rebuilt builds; stop this class.
                    return
                if code_off:
                    callback(cname, dex.method_name(midx), code_off)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    near = argv[1] == "--near"
    target = argv[2] if near else argv[1]
    files = argv[3:] if near else argv[2:]
    for path in files:
        dex = Dex(path)
        hits = [i for i in range(dex.n_str) if dex.string(i) == target]
        if not hits:
            continue
        print(f"== {path}  idx={hits}")
        if near:
            for h in hits:
                for k in range(max(0, h - 12), min(dex.n_str, h + 13)):
                    mark = ">>" if k == h else "  "
                    print(f"  {mark} [{k}] {dex.string(k)}")
            continue
        wanted = set(hits)

        def cb(cname, mname, code_off):
            refs = dex.insns_string_refs(code_off)
            if wanted & set(refs):
                others = [dex.string(r) for r in refs if r in wanted or (r < dex.n_str and r not in wanted)]
                ctx = [s for s in (dex.string(r) for r in refs) if s and s != target][:8]
                print(f"  {cname}#{mname}")
                print(f"      ctx: {ctx}")

        walk_methods(dex, cb)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
