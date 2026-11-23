#!/usr/bin/env python3
"""Dump fields and methods of one class out of a set of .dex files.

The module's symbols.json addresses members by their R8 short names ("L", "G",
"b", field "P"), and a ColorOS point release re-obfuscates the camera app, so a
few of those names move. Finding the new name needs exactly this: the member
list of a known class, with types and access flags so the right overload can be
picked again.

    python tools/dex_members.py <dex>... --class 'Lcom/oplus/camera/foo/Bar;'
    python tools/dex_members.py <dex>... --grep 'Lut3dDataManager'
    python tools/dex_members.py <dex>... --class '...' --methods-only
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


def sleb(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            if b & 0x40:
                result -= 1 << shift
            return result, off


class Dex:
    def __init__(self, path):
        self.path = path
        self.data = d = open(path, "rb").read()
        if d[:4] != b"dex\n":
            raise SystemExit(f"{path}: not a dex")
        self.string_size, self.string_off = struct.unpack_from("<II", d, 0x38)
        self.type_size, self.type_off = struct.unpack_from("<II", d, 0x40)
        self.proto_size, self.proto_off = struct.unpack_from("<II", d, 0x48)
        self.field_size, self.field_off = struct.unpack_from("<II", d, 0x50)
        self.method_size, self.method_off = struct.unpack_from("<II", d, 0x58)
        self.class_size, self.class_off = struct.unpack_from("<II", d, 0x60)
        self._strings = {}

    def string(self, idx):
        s = self._strings.get(idx)
        if s is not None:
            return s
        off = struct.unpack_from("<I", self.data, self.string_off + idx * 4)[0]
        n, p = uleb(self.data, off)
        s = self.data[p:self.data.index(b"\0", p)].decode("utf-8", "replace")
        self._strings[idx] = s
        return s

    def type(self, idx):
        sid = struct.unpack_from("<I", self.data, self.type_off + idx * 4)[0]
        return self.string(sid)

    def proto(self, idx):
        _shorty, ret, params_off = struct.unpack_from(
            "<III", self.data, self.proto_off + idx * 12)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.data, params_off)[0]
            for i in range(size):
                t = struct.unpack_from("<H", self.data, params_off + 4 + i * 2)[0]
                params.append(self.type(t))
        return self.type(ret), params

    def field(self, idx):
        c, t, n = struct.unpack_from("<HHI", self.data, self.field_off + idx * 8)
        return self.type(t), self.string(n)

    def method(self, idx):
        c, p, n = struct.unpack_from("<HHI", self.data, self.method_off + idx * 8)
        ret, params = self.proto(p)
        return self.string(n), params, ret

    def classes(self):
        for i in range(self.class_size):
            off = self.class_off + i * 32
            class_idx = struct.unpack_from("<I", self.data, off)[0]
            yield self.type(class_idx), off


def flags_of(v):
    out = []
    for bit, name in ((0x1, "public"), (0x2, "private"), (0x4, "protected"),
                      (0x8, "static"), (0x10, "final"), (0x40, "volatile")):
        if v & bit:
            out.append(name)
    return ",".join(out) or "-"


def dump(dex, descriptor):
    for desc, off in dex.classes():
        if desc != descriptor:
            continue
        access, super_idx, _ifaces, _src, _anno, cdata_off, _sv = struct.unpack_from(
            "<IIIIIII", dex.data, off + 4)
        super_desc = dex.type(super_idx) if super_idx != 0xFFFFFFFF else "-"
        print(f"class {desc}\n  extends {super_desc}  flags={flags_of(access)}")
        if not cdata_off:
            print("  (no class_data)")
            return True
        p = cdata_off
        sf, p = uleb(dex.data, p)
        inf, p = uleb(dex.data, p)
        dm, p = uleb(dex.data, p)
        vm, p = uleb(dex.data, p)
        print(f"  static_fields={sf} instance_fields={inf} direct={dm} virtual={vm}")
        for label, count in (("static field", sf), ("instance field", inf)):
            idx = 0
            for _ in range(count):
                diff, p = uleb(dex.data, p)
                fl, p = uleb(dex.data, p)
                idx += diff
                ftype, fname = dex.field(idx)
                print(f"  F {label}: {fname} : {ftype}  [{flags_of(fl)}]")
        for label, count in (("direct", dm), ("virtual", vm)):
            idx = 0
            for _ in range(count):
                diff, p = uleb(dex.data, p)
                fl, p = uleb(dex.data, p)
                _code, p = uleb(dex.data, p)
                idx += diff
                mname, params, ret = dex.method(idx)
                print(f"  M {label}: {mname}({', '.join(params)}){ret}  [{flags_of(fl)}]")
        return True
    return False


def main(argv):
    dexes, cls, grep = [], None, None
    methods_only = "--methods-only" in argv
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--class":
            i += 1
            cls = argv[i]
        elif a == "--grep":
            i += 1
            grep = argv[i]
        elif a.startswith("--"):
            pass
        else:
            dexes.append(a)
        i += 1
    if not dexes:
        raise SystemExit(__doc__)
    for path in dexes:
        dex = Dex(path)
        if cls:
            if dump(dex, cls):
                return 0
        elif grep:
            for desc, _off in dex.classes():
                if grep in desc:
                    print(f"{path}: {desc}")
    if cls:
        print(f"class not found: {cls}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
