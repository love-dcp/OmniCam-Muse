"""Scan or extract a single partition out of an A/B OTA payload.bin.

The payloads OPPO ships for this device are full (REPLACE / REPLACE_BZ / REPLACE_XZ /
ZERO only), so no delta patching is involved: every operation carries its own bytes.

  python tools/pmx110_pull.py scan odm --find libai_camera_composition_lite2.so
  python tools/pmx110_pull.py extract odm --out F:/电脑/pmx110/odm.img
"""
import argparse
import bz2
import lzma
import struct
import sys
from pathlib import Path

from payload_dumper.update_metadata_pb2 import DeltaArchiveManifest

BLOCK = 4096
OP_REPLACE, OP_REPLACE_BZ, OP_ZERO, OP_REPLACE_XZ = 0, 1, 6, 8


def open_payload(path):
    fh = open(path, "rb")
    header = fh.read(20)
    if header[:4] != b"CrAU":
        raise SystemExit(f"{path}: not a payload (magic {header[:4]!r})")
    version = struct.unpack(">Q", header[4:12])[0]
    manifest_size = struct.unpack(">Q", header[12:20])[0]
    sig_size = struct.unpack(">I", fh.read(4))[0]
    manifest = DeltaArchiveManifest()
    manifest.ParseFromString(fh.read(manifest_size))
    data_start = 20 + 4 + manifest_size + sig_size
    print(f"payload v{version}, {len(manifest.partitions)} partitions, "
          f"blobs at {data_start}", file=sys.stderr)
    return fh, manifest, data_start


def find_partition(manifest, name):
    for part in manifest.partitions:
        if part.partition_name == name:
            return part
    raise SystemExit(f"no such partition: {name}")


def inflate(op, raw):
    kind = op.type
    if kind == OP_REPLACE:
        return raw
    if kind == OP_REPLACE_BZ:
        return bz2.decompress(raw)
    if kind == OP_REPLACE_XZ:
        return lzma.decompress(raw)
    raise SystemExit(f"unsupported operation type {kind}")


def dst_ranges(op):
    return [(ext.start_block * BLOCK, ext.num_blocks * BLOCK) for ext in op.dst_extents]


def scan(fh, data_start, part, needles):
    """Stream the decompressed partition and report where each needle appears."""
    hits = 0
    for op in part.operations:
        if op.type == OP_ZERO:
            continue
        fh.seek(data_start + op.data_offset)
        buf = inflate(op, fh.read(op.data_length))
        for needle in needles:
            at = buf.find(needle)
            while at != -1:
                hits += 1
                where = f"op@{op.data_offset}+{at}"
                context = buf[max(0, at - 96):at + len(needle) + 96]
                printable = "".join(chr(c) if 32 <= c < 127 else "." for c in context)
                print(f"[hit] {needle.decode()} {where}")
                print(f"      {printable}")
                at = buf.find(needle, at + 1)
        del buf
    print(f"\n{hits} hit(s) in {part.partition_name}")
    return 0


def extract(fh, data_start, part, out_path):
    size = part.new_partition_info.size
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    done = 0
    with open(out_path, "wb") as dst:
        dst.truncate(size)
        for index, op in enumerate(part.operations, 1):
            if op.type == OP_ZERO:
                continue
            fh.seek(data_start + op.data_offset)
            buf = inflate(op, fh.read(op.data_length))
            pos = 0
            for offset, length in dst_ranges(op):
                dst.seek(offset)
                dst.write(buf[pos:pos + length])
                pos += length
            done += len(buf)
            if index % 200 == 0:
                print(f"  {index}/{len(part.operations)} ops, {done / 1e9:.2f} GB",
                      file=sys.stderr, flush=True)
    print(f"wrote {out_path} ({size / 1e9:.2f} GB)")

    with open(out_path, "rb") as check:
        head = check.read(4096)
    erofs = head[1024:1028]
    ext4 = head[1080:1082]
    if erofs == b"\xe2\xe1\xf5\xe0":
        print("filesystem: erofs")
    elif ext4 == b"\x53\xef":
        print("filesystem: ext4")
    else:
        print(f"filesystem: unknown (sb magic {erofs.hex()} / {ext4.hex()})")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("payload")
    parser.add_argument("action", choices=["scan", "extract"])
    parser.add_argument("partition")
    parser.add_argument("--find", action="append", default=[],
                        help="needle to look for (repeatable), scan only")
    parser.add_argument("--out", help="output image path, extract only")
    args = parser.parse_args()

    fh, manifest, data_start = open_payload(args.payload)
    part = find_partition(manifest, args.partition)
    print(f"{part.partition_name}: {part.new_partition_info.size / 1e9:.2f} GB, "
          f"{len(part.operations)} ops", file=sys.stderr)

    if args.action == "scan":
        needles = [n.encode() for n in args.find] or [b"libai_camera_composition"]
        return scan(fh, data_start, part, needles)
    if not args.out:
        raise SystemExit("--out is required for extract")
    return extract(fh, data_start, part, args.out)


if __name__ == "__main__":
    sys.exit(main())
