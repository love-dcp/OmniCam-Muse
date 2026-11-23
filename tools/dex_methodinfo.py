#!/usr/bin/env python3
"""Per-method fingerprint dump / search across .dex files.

symbols.json must locate members by fingerprint (strings, params, ret, field
refs, invokes, numbers) instead of R8 short names, because every ColorOS point
release re-obfuscates. This tool mines those fingerprints offline:

    # everything a class' methods touch
    python tools/dex_methodinfo.py <dex>... --class 'Lcom/oplus/camera/foo/Bar;'

    # search all dexes for methods matching a fingerprint (must hit exactly once)
    python tools/dex_methodinfo.py <dex>... --strings 'a','b' --ret Z \\
        --params 'Ljava/lang/String;' --static --fields 'LFoo;.bar:I' \\
        --invokes 'LFoo;.baz(J)V' --numbers 660,-1 --in 'Lcom/...;'
"""
import struct
import sys

# instruction length in 16-bit code units, indexed by opcode (dalvik spec)
SIZES = [
    # 0x00-0x0F
    1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
    # 0x10-0x1F
    1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
    # 0x20-0x2F
    2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 3, 2, 2,
    # 0x30-0x3F
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1,
    # 0x40-0x4F
    1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0x50-0x5F
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0x60-0x6F
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,
    # 0x70-0x7F
    3, 3, 3, 3, 3, 1, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0x80-0x8F
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0x90-0x9F
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xA0-0xAF
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xB0-0xBF
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0xC0-0xCF
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0xD0-0xDF
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    # 0xE0-0xEF
    2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1,
    # 0xF0-0xFF
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 3, 3, 2, 2,
]


def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


class Dex:
    def __init__(self, path):
        self.path = path
        self.d = d = open(path, "rb").read()
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
        if idx >= self.string_size:
            return None
        s = self._strings.get(idx)
        if s is None:
            off = struct.unpack_from("<I", self.d, self.string_off + idx * 4)[0]
            _n, p = uleb(self.d, off)
            s = self.d[p:self.d.index(b"\0", p)].decode("utf-8", "replace")
            self._strings[idx] = s
        return s

    def type(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_off + idx * 4)[0])

    def proto(self, idx):
        if idx >= self.proto_size:
            return "?", []
        _shorty, ret, params_off = struct.unpack_from("<III", self.d, self.proto_off + idx * 12)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            for i in range(size):
                params.append(self.type(struct.unpack_from("<H", self.d, params_off + 4 + i * 2)[0]))
        return self.type(ret), params

    def field_ref(self, idx):
        c, t, n = struct.unpack_from("<HHI", self.d, self.field_off + idx * 8)
        return f"{self.type(c)}.{self.string(n)}:{self.type(t)}"

    def method_ref(self, idx):
        c, p, n = struct.unpack_from("<HHI", self.d, self.method_off + idx * 8)
        ret, params = self.proto(p)
        return f"{self.type(c)}.{self.string(n)}({','.join(params)}){ret}"

    def field(self, idx):
        c, t, n = struct.unpack_from("<HHI", self.d, self.field_off + idx * 8)
        return self.type(t), self.string(n)

    def method(self, idx):
        c, p, n = struct.unpack_from("<HHI", self.d, self.method_off + idx * 8)
        ret, params = self.proto(p)
        return self.string(n), params, ret

    def classes(self):
        for i in range(self.class_size):
            off = self.class_off + i * 32
            yield self.type(struct.unpack_from("<I", self.d, off)[0]), off

    def members(self, cdata_off):
        """fields and methods; index diffs reset at each of the 4 sections."""
        d = self.d
        p = cdata_off
        sf, p = uleb(d, p)
        inf, p = uleb(d, p)
        dm, p = uleb(d, p)
        vm, p = uleb(d, p)
        fields = []
        for count in (sf, inf):
            idx = 0
            for _ in range(count):
                diff, p = uleb(d, p)
                fl, p = uleb(d, p)
                idx += diff
                fields.append((idx, fl))
        methods = []
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, p = uleb(d, p)
                fl, p = uleb(d, p)
                code_off, p = uleb(d, p)
                idx += diff
                methods.append((idx, fl, code_off))
        return fields, methods

    def code_fingerprint(self, code_off):
        """strings, field refs, invoke refs, numeric literals in one code item."""
        d = self.d
        strings, fields, invokes, numbers = [], [], [], []
        if not code_off:
            return strings, fields, invokes, numbers
        insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        base = code_off + 16
        i = 0
        while i < insns_size:
            unit = struct.unpack_from("<H", d, base + 2 * i)[0]
            op = unit & 0xFF
            if unit == 0x0100:  # packed-switch payload: ident, size, first_key, targets
                n = struct.unpack_from("<H", d, base + 2 * (i + 1))[0]
                i += 4 + n * 2
                continue
            if unit == 0x0200:  # sparse-switch payload: ident, size, keys, targets
                n = struct.unpack_from("<H", d, base + 2 * (i + 1))[0]
                i += 2 + n * 4
                continue
            if unit == 0x0300:  # fill-array-data payload: ident, elem_width, size, data
                w = d[base + 2 * i + 2]
                n = struct.unpack_from("<I", d, base + 2 * (i + 2))[0]
                i += 4 + (w * n + 1) // 2
                continue
            size = SIZES[op] if op < len(SIZES) else 1
            if op == 0x1A:  # const-string
                if i + 1 < insns_size:
                    s = self.string(struct.unpack_from("<H", d, base + 2 * (i + 1))[0])
                    if s is not None:
                        strings.append(s)
            elif op == 0x1B:  # const-string/jumbo
                lo = struct.unpack_from("<H", d, base + 2 * (i + 1))[0]
                hi = struct.unpack_from("<H", d, base + 2 * (i + 2))[0]
                s = self.string(lo | (hi << 16))
                if s is not None:
                    strings.append(s)
            elif 0x52 <= op <= 0x5F:  # iget/iput
                fields.append(self.field_ref(struct.unpack_from("<H", d, base + 2 * (i + 1))[0]))
            elif 0x60 <= op <= 0x6D:  # sget/sput
                fields.append(self.field_ref(struct.unpack_from("<H", d, base + 2 * (i + 1))[0]))
            elif 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:  # invokes
                invokes.append(self.method_ref(struct.unpack_from("<H", d, base + 2 * (i + 1))[0]))
            elif op == 0x12:  # const/4 (4-bit signed in high nibble of B)
                nib = (unit >> 12) & 0xF
                numbers.append(nib - 16 if nib >= 8 else nib)
            elif op == 0x13:  # const/16
                if i + 1 < insns_size:
                    v = struct.unpack_from("<h", d, base + 2 * (i + 1))[0]
                    numbers.append(v)
            elif op == 0x14:  # const
                if i + 2 < insns_size:
                    lo = struct.unpack_from("<H", d, base + 2 * (i + 1))[0]
                    hi = struct.unpack_from("<H", d, base + 2 * (i + 2))[0]
                    numbers.append(lo | (hi << 16))
            elif op == 0x15:  # const/high16
                if i + 1 < insns_size:
                    numbers.append(struct.unpack_from("<H", d, base + 2 * (i + 1))[0] << 16)
            i += size
        return strings, fields, invokes, numbers


def parse_args(argv):
    opts = {"dexes": [], "class": None, "in": None, "strings": None,
            "params": None, "ret": None, "static": None, "fields": None,
            "invokes": None, "numbers": None, "limit-strings": None}
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--class":
            opts["class"] = argv[i + 1]; i += 1
        elif a == "--in":
            opts["in"] = argv[i + 1]; i += 1
        elif a in ("--strings", "--params", "--fields", "--invokes", "--numbers"):
            opts[a[2:]] = argv[i + 1].split(","); i += 1
        elif a == "--ret":
            opts["ret"] = argv[i + 1]; i += 1
        elif a == "--static":
            opts["static"] = True
        elif a == "--limit-strings":
            opts["limit-strings"] = int(argv[i + 1]); i += 1
        elif a.startswith("--"):
            pass
        else:
            opts["dexes"].append(a)
        i += 1
    return opts


def matches(f, cls_desc, mname, params, ret, static, o):
    if o["in"] and cls_desc != o["in"]:
        return False
    if o["ret"] and ret != o["ret"]:
        return False
    if o["params"]:
        want = [p if p.startswith("L") or "/" in p else short(p) for p in o["params"]]
        if want != params:
            return False
    if o["static"] is not None and static != o["static"]:
        return False
    strings, fields, invokes, numbers = f
    if o["strings"]:
        for s in o["strings"]:
            if s not in strings:
                return False
    if o["fields"]:
        for s in o["fields"]:
            if not any(x.startswith(s) for x in fields):
                return False
    if o["invokes"]:
        for s in o["invokes"]:
            if s not in invokes:
                return False
    if o["numbers"]:
        for n in o["numbers"]:
            try:
                if int(n) not in numbers:
                    return False
            except ValueError:
                return False
    return True


def short(t):
    return {"Z": "boolean", "I": "int", "J": "long", "F": "float",
            "D": "double", "B": "byte", "S": "short", "C": "char",
            "V": "void"}.get(t, t)


def flags_of(v):
    out = []
    for bit, name in ((0x1, "public"), (0x2, "private"), (0x8, "static"),
                      (0x10, "final"), (0x100, "constructor?")):
        if v & bit:
            out.append(name)
    return ",".join(out) or "-"


def main(argv):
    o = parse_args(argv)
    if not o["dexes"]:
        raise SystemExit(__doc__)
    hits = []
    for path in o["dexes"]:
        dex = Dex(path)
        for desc, off in dex.classes():
            if o["class"] and desc != o["class"]:
                continue
            access, _super, _if, _src, _anno, cdata, _sv = struct.unpack_from(
                "<IIIIIII", dex.d, off + 4)
            if not cdata:
                continue
            fields, methods = dex.members(cdata)
            for midx, fl, code_off in methods:
                mname, params, ret = dex.method(midx)
                f = dex.code_fingerprint(code_off)
                if o["class"]:
                    strings, frefs, invs, nums = f
                    print(f"M {mname}({', '.join(params)}){ret} [{flags_of(fl)}] code=0x{code_off:x}")
                    if strings:
                        print(f"   strings: {strings[:14]}")
                    if frefs:
                        print(f"   fields : {frefs[:14]}")
                    if invs:
                        print(f"   invokes: {sorted(set(invs))[:14]}")
                    if nums:
                        uniq = sorted(set(nums))
                        print(f"   numbers: {uniq[:20]}")
                    continue
                static = bool(fl & 0x8)
                if matches(f, desc, mname, params, ret, static, o):
                    hits.append((path, desc, mname, params, ret, fl, f))
    if not o["class"]:
        for path, desc, mname, params, ret, fl, f in hits:
            print(f"{path} {desc}.{mname}({','.join(params)}){ret} [{flags_of(fl)}]")
            strings, frefs, invs, nums = f
            if strings:
                print(f"   strings: {strings[:10]}")
            if frefs:
                print(f"   fields : {frefs[:10]}")
            if invs:
                print(f"   invokes: {sorted(set(invs))[:10]}")
        print(f"--- {len(hits)} hit(s)")
        return 0 if len(hits) == 1 else 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
