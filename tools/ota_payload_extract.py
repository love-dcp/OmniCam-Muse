#!/usr/bin/env python3
"""Streaming extractor for ColorOS/Android OTA payload.bin inside a signed zip.

Only the requested partitions are written, so a 10 GB firmware zip yields just
the images we need.  Supported ops: REPLACE, REPLACE_BZ, REPLACE_XZ, ZERO,
DISCARD.  Partial-update ops (SOURCE_COPY/BSDIFF family) are reported, never
guessed at.

    python tools/ota_payload_extract.py list   <zip>
    python tools/ota_payload_extract.py ops    <zip> -p odm,my_product
    python tools/ota_payload_extract.py dump   <zip> -p odm,my_product -o out/
"""
import argparse
import bz2
import lzma
import os
import re
import struct
import sys
import zipfile
from pathlib import Path

OP_NAMES = {
    0: "REPLACE", 1: "REPLACE_BZ", 2: "MOVE", 3: "BSDIFF", 4: "SOURCE_COPY",
    5: "SOURCE_BSDIFF", 6: "ZERO", 7: "DISCARD", 8: "REPLACE_XZ",
    9: "PUFFDIFF", 10: "BROTLI_BSDIFF", 11: "ZUCCHINI", 12: "LZ4DIFF_BSDIFF",
    13: "LZ4DIFF_PUFFDIFF",
}


# ---------------------------------------------------------------- protobuf ---
def pb_fields(buf):
    """Yield (field_number, wire_type, value) with minimal wire-format parsing.

    Truncated / misaligned buffers simply end the stream instead of raising,
    because heuristic callers probe submessages that may not be protobufs.
    """
    i, n = 0, len(buf)
    while i < n:
        try:
            key = 0
            shift = 0
            while True:
                b = buf[i]
                i += 1
                key |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            num, wt = key >> 3, key & 7
            if num == 0:
                return
            if wt == 0:
                val = 0
                shift = 0
                while True:
                    b = buf[i]
                    i += 1
                    val |= (b & 0x7F) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                yield num, wt, val
            elif wt == 2:
                ln = 0
                shift = 0
                while True:
                    b = buf[i]
                    i += 1
                    ln |= (b & 0x7F) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                if ln < 0 or i + ln > n:
                    return
                yield num, wt, buf[i:i + ln]
                i += ln
            elif wt == 5:
                yield num, wt, buf[i:i + 4]
                i += 4
            elif wt == 1:
                yield num, wt, buf[i:i + 8]
                i += 8
            else:
                return
        except IndexError:
            return



def parse_partition(buf):
    """Heuristic decode of a PartitionUpdate: name + operations + size.

    Field numbers move between update_engine schemas (ColorOS 17 puts the
    partition list at 13, ops at 8 and the image size at 19), so everything is
    recognised by shape rather than by a hardcoded tag.
    """
    part = {"name": None, "ops": [], "est_size": 0, "fs": "", "varints": []}
    for num, wt, val in pb_fields(buf):
        if wt == 2:
            sub = parse_op(val)
            if sub is not None:
                part["ops"].append(sub)
                continue
            txt = val.decode("utf-8", "replace")
            if num == 1 and re.fullmatch(r"[A-Za-z0-9_.-]+", txt):
                part["name"] = txt
            elif txt in ("ext4", "erofs", "f2fs", "squashfs"):
                part["fs"] = txt
        elif wt == 0:
            part["varints"].append(val)
    if part["ops"]:
        end = max(s + b for op in part["ops"] for s, b in op["dst"])
        bs = 4096
        want = end * bs
        # image size is one of the plain varints: pick the one closest to it
        cand = [v for v in part["varints"] if want * 0.5 <= v <= want * 1.05]
        part["est_size"] = max(cand) if cand else want
    return part


def parse_op(buf):
    op = {"type": None, "off": 0, "len": 0, "dst": [], "dst_len": 0}
    for num, wt, val in pb_fields(buf):
        if num == 1 and wt == 0:
            op["type"] = val
        elif num == 2 and wt == 0:
            op["off"] = val
        elif num == 3 and wt == 0:
            op["len"] = val
        elif num == 6 and wt == 2:
            ext = {}
            for n2, w2, v2 in pb_fields(val):
                if n2 == 1 and w2 == 0:
                    ext["start"] = v2
                elif n2 == 2 and w2 == 0:
                    ext["blocks"] = v2
            if "start" in ext and "blocks" in ext:
                op["dst"].append((ext["start"], ext["blocks"]))
        elif num == 7 and wt == 0:
            op["dst_len"] = val
    if op["type"] is None or not op["dst"] or op["type"] > 13:
        return None
    if op["type"] in (6, 7):
        op["len"] = 0
    elif op["len"] <= 0:
        return None
    return op


class Payload:
    def __init__(self, zip_path):
        self.z = zipfile.ZipFile(zip_path)
        self.fp = self.z.open("payload.bin")
        head = self.fp.read(24)
        if head[:4] != b"CrAU":
            raise SystemExit("not an update payload")
        # update_engine writes the header big-endian
        self.version, mlen, siglen = struct.unpack(">QQI", head[4:24])
        if self.version < 2:
            siglen = 0
        self.manifest = self.fp.read(mlen)
        self.data_offset = 24 + mlen + siglen
        self.block_size = 4096
        self.partitions = {}
        for num, wt, val in pb_fields(self.manifest):
            if num == 3 and wt == 0 and 512 <= val <= 65536:
                self.block_size = val
            elif wt == 2:
                p = parse_partition(val)
                if p["name"] and p["ops"]:
                    self.partitions[p["name"]] = p
        for p in self.partitions.values():
            if not p["est_size"]:
                end = max((s + b for s, b in (e for op in p["ops"] for e in op["dst"])),
                          default=0)
                p["est_size"] = end * self.block_size

    def read(self, off, ln):
        self.fp.seek(self.data_offset + off)
        buf = self.fp.read(ln)
        if len(buf) != ln:
            raise SystemExit("short read in payload data region")
        return buf

    def dump(self, name, out_path):
        p = self.partitions[name]
        unsupported = sorted({op["type"] for op in p["ops"]
                              if op["type"] not in (0, 1, 6, 7, 8)})
        if unsupported:
            raise SystemExit(
                "%s uses partial-update ops %s; needs source images"
                % (name, [OP_NAMES.get(t, t) for t in unsupported]))
        bs = self.block_size
        total = p["est_size"]
        done = 0
        with open(out_path, "wb") as f:
            f.truncate(total)
            for op in p["ops"]:
                t = op["type"]
                if t in (6, 7):
                    raw = b""
                else:
                    raw = self.read(op["off"], op["len"])
                    if t == 1:
                        raw = bz2.decompress(raw)
                    elif t == 8:
                        raw = lzma.decompress(raw)
                pos = 0
                for start, blocks in op["dst"]:
                    span = blocks * bs
                    chunk = raw[pos:pos + span] if t not in (6, 7) else b""
                    f.seek(start * bs)
                    if t in (6, 7):
                        f.write(b"\0" * span)
                    else:
                        if len(chunk) < span:
                            chunk = chunk + b"\0" * (span - len(chunk))
                        f.write(chunk)
                    pos += span
                done += 1
        return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["list", "ops", "dump"])
    ap.add_argument("zip")
    ap.add_argument("-p", "--partitions", default="")
    ap.add_argument("-o", "--out", default=".")
    a = ap.parse_args()

    pl = Payload(a.zip)
    if a.cmd == "list":
        for name, p in sorted(pl.partitions.items()):
            kinds = {}
            for op in p["ops"]:
                kinds[OP_NAMES.get(op["type"], op["type"])] = \
                    kinds.get(OP_NAMES.get(op["type"], op["type"]), 0) + 1
            print("%-16s %10.1f MB  %s" % (name, p["est_size"] / 1048576.0,
                                           ",".join("%s x%d" % kv for kv in kinds.items())))
        return

    targets = [s for s in a.partitions.split(",") if s] or list(pl.partitions)
    if a.cmd == "ops":
        for name in targets:
            p = pl.partitions[name]
            kinds = {}
            for op in p["ops"]:
                k = OP_NAMES.get(op["type"], op["type"])
                kinds[k] = kinds.get(k, 0) + 1
            print("%-16s ops=%d %s" % (name, len(p["ops"]), kinds))
        return

    Path(a.out).mkdir(parents=True, exist_ok=True)
    for name in targets:
        dst = Path(a.out) / (name + ".img")
        size = pl.dump(name, dst)
        print("%-16s -> %s (%.1f MB)" % (name, dst, size / 1048576.0))


if __name__ == "__main__":
    sys.exit(main())
