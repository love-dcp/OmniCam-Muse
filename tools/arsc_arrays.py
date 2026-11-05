"""Dump string-array resources out of a binary resources.arsc.

aapt2 refuses these tables (OPPO's build leaves a few string blocks in a shape
it calls truncated) and the APK central directory can be unusable when only
part of the image was recoverable, so this reads the table directly:

  python tools/arsc_arrays.py dump <resources.arsc> [--grep log]

It prints every complex (array) entry as `key = [value, ...]` so two builds can
be diffed by resource name.
"""
import argparse
import struct
import sys

RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_REFERENCE = 0x01

FLAG_COMPLEX = 0x0001
FLAG_COMPACT = 0x0008


class Table:
    def __init__(self, data):
        self.d = data
        self.strings = []

    def read_string_pool(self, off):
        dtype, hsize, size = struct.unpack_from("<HHI", self.d, off)
        if dtype != RES_STRING_POOL_TYPE:
            return []
        strcount, stylecount, flags, strstart = struct.unpack_from(
            "<IIII", self.d, off + 8)
        utf8 = bool(flags & 0x100)
        offs = [struct.unpack_from("<I", self.d, off + hsize + 4 * i)[0]
                for i in range(strcount)]
        pool = []
        base = off + strstart
        for o in offs:
            if o == 0xFFFFFFFF:
                pool.append(None)
                continue
            p = base + o
            try:
                if utf8:
                    # two varint lengths (utf16 length, then utf8 byte length)
                    n = self.d[p]
                    p += 2 if n & 0x80 else 1
                    ln = self.d[p]
                    p += 1
                    if ln & 0x80:
                        ln = ((ln & 0x7F) << 8) | self.d[p]
                        p += 1
                    pool.append(self.d[p:p + ln].decode("utf-8", "replace"))
                else:
                    ln = struct.unpack_from("<H", self.d, p)[0]
                    p += 2
                    if ln & 0x8000:
                        ln = ((ln & 0x7FFF) << 16) | struct.unpack_from(
                            "<H", self.d, p)[0]
                        p += 2
                    pool.append(self.d[p:p + ln * 2].decode("utf-16-le",
                                                            "replace"))
            except Exception:
                pool.append(None)
        return pool

    def value_str(self, dtype, data):
        if dtype == TYPE_STRING and 0 <= data < len(self.strings):
            return self.strings[data]
        if dtype == TYPE_REFERENCE:
            return "@0x%08x" % data
        if dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
            return str(data)
        return "0x%02x:%s" % (dtype, data)


def parse(path, grep=None):
    data = open(path, "rb").read()
    if struct.unpack_from("<H", data, 0)[0] != RES_TABLE_TYPE:
        raise SystemExit("not a resources.arsc")
    hsize, total = struct.unpack_from("<HI", data, 2)[0], \
        struct.unpack_from("<I", data, 4)[0]
    table = Table(data)

    off = hsize
    first_pool = None
    packages = []
    while off + 8 <= len(data):
        ctype, chsize, csize = struct.unpack_from("<HHI", data, off)
        if csize < 8:
            break
        if ctype == RES_STRING_POOL_TYPE and first_pool is None:
            table.strings = table.read_string_pool(off)
            first_pool = table.strings
        elif ctype == RES_TABLE_PACKAGE_TYPE:
            packages.append(off)
        off += csize

    if not table.strings and first_pool:
        table.strings = first_pool

    for pkg_off in packages:
        _, phsize, psize = struct.unpack_from("<HHI", data, pkg_off)
        type_strings_off, = struct.unpack_from("<I", data, pkg_off + 268)
        key_strings_off, = struct.unpack_from("<I", data, pkg_off + 276)
        types = table.read_string_pool(pkg_off + type_strings_off)
        keys = table.read_string_pool(pkg_off + key_strings_off)

        pos = pkg_off + phsize
        end = pkg_off + psize
        while pos + 8 <= end:
            ctype, chsize, csize = struct.unpack_from("<HHI", data, pos)
            if csize < 8:
                break
            if ctype == RES_TABLE_TYPE_TYPE and chsize >= 20:
                tid = data[pos + 8]
                tname = types[tid - 1] if 0 < tid <= len(types) else str(tid)
                flags = data[pos + 9]
                entry_count, entries_start = struct.unpack_from(
                    "<II", data, pos + 12)
                conf_size, = struct.unpack_from("<I", data, pos + 20)
                idx_at = pos + 20 + conf_size
                offsets = []
                if flags & 0x01:      # sparse: (idx, offset16) pairs
                    for i in range(entry_count):
                        idx, o16 = struct.unpack_from("<HH", data,
                                                      idx_at + 4 * i)
                        offsets.append((idx, o16 * 4))
                elif flags & 0x02:    # offset16
                    for i in range(entry_count):
                        o16, = struct.unpack_from("<H", data, idx_at + 2 * i)
                        offsets.append((i, o16 * 4 if o16 != 0xFFFF else None))
                else:
                    for i in range(entry_count):
                        o32, = struct.unpack_from("<I", data, idx_at + 4 * i)
                        offsets.append((i, o32 if o32 != 0xFFFFFFFF else None))
                for idx, eoff in offsets:
                    if eoff is None:
                        continue
                    at = pos + entries_start + eoff
                    if at + 8 > len(data):
                        continue
                    esize, eflags = struct.unpack_from("<HH", data, at)
                    if eflags & FLAG_COMPACT:
                        # Android 12 compact entry: key in the high half of the
                        # size field, value inline right after the 4-byte header.
                        keyidx = esize >> 8
                        dtype = data[at + 3]
                        dval, = struct.unpack_from("<I", data, at + 4)
                        key = (keys[keyidx] if keyidx < len(keys) else "?") or "?"
                        yield tname, key, [table.value_str(dtype, dval)], False
                        continue
                    keyidx, = struct.unpack_from("<I", data, at + 4)
                    key = (keys[keyidx] if keyidx < len(keys) else "?") or "?"
                    if not eflags & FLAG_COMPLEX:
                        vat = at + esize
                        vsize, res0, dtype = struct.unpack_from("<HBB", data,
                                                                vat)
                        dval, = struct.unpack_from("<I", data, vat + 4)
                        yield tname, key, [table.value_str(dtype, dval)], False
                        continue
                    parent, count = struct.unpack_from("<II", data, at + 8)
                    vals = []
                    mat = at + esize
                    for i in range(count):
                        mo = mat + 12 * i
                        if mo + 12 > len(data):
                            break
                        name, vsize, res0, dtype, dval = struct.unpack_from(
                            "<IHBBI", data, mo)
                        vals.append(table.value_str(dtype, dval))
                    yield tname, key, vals, True
            pos += csize


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("arsc")
    ap.add_argument("--grep", help="only rows whose key or values contain this")
    ap.add_argument("--arrays-only", action="store_true", default=True)
    a = ap.parse_args()
    n = 0
    for tname, key, vals, complex_ in parse(a.arsc):
        if not complex_:
            continue
        joined = ",".join(vals)
        if a.grep and a.grep.lower() not in (key + joined).lower():
            continue
        print("%s/%s = [%s]" % (tname, key, joined))
        n += 1
    print("\n%d rows" % n, file=sys.stderr)


if __name__ == "__main__":
    main()
