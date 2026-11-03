"""Parse an A/B OTA payload.bin inside a signed OTA zip and list its partitions.

Reads only the header + manifest; the data blobs are left untouched.
"""
import struct
import sys
import zipfile

from payload_dumper.update_metadata_pb2 import DeltaArchiveManifest

ZIP = r"F:\电脑\ColorOS PMX110_17.0.0.109(CN01).zip"


def main() -> int:
    zf = zipfile.ZipFile(ZIP)
    with zf.open("payload.bin") as fh:
        header = fh.read(20)
        magic = header[:4]
        if magic != b"CrAU":
            print("unexpected magic", magic)
            return 1
        version = struct.unpack(">Q", header[4:12])[0]
        manifest_size = struct.unpack(">Q", header[12:20])[0]
        sig_size = struct.unpack(">I", fh.read(4))[0]
        manifest = fh.read(manifest_size)

    print(f"payload version={version} manifest={manifest_size}B sig={sig_size}B")
    blob_start = 20 + 4 + manifest_size + sig_size
    print(f"data blobs start at payload offset {blob_start}\n")

    m = DeltaArchiveManifest()
    m.ParseFromString(manifest)
    print(f"block_size={m.block_size} minor_version={m.minor_version} "
          f"partitions={len(m.partitions)}\n")
    print(f"{'partition':<22}{'size(GB)':>10}{'fs':>10}{'ops':>7}  types")
    total = 0
    for p in m.partitions:
        size = p.new_partition_info.size if p.HasField("new_partition_info") else 0
        total += size
        kinds = {}
        for op in p.operations:
            kind = op.type
            kinds[kind] = kinds.get(kind, 0) + 1
        names = ",".join(f"{t}" for t in sorted(kinds))
        print(f"{p.partition_name:<22}{size / 1e9:>10.3f}"
              f"{p.filesystem_type or '-':>10}{len(p.operations):>7}  {names}")
    print(f"\ntotal {total / 1e9:.2f} GB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
