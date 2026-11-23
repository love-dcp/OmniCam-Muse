#!/usr/bin/env python3
"""Build a PKJ110 camera payload for one ColorOS baseline, by fingerprint.

The X8U port used to ship fixed offsets and a fixed set of files, which is why a
ColorOS point release could put an old payload on a new camera stack and take the
provider down at boot. This script makes the derivation explicit:

  * every stock input is identified by ELF build-id + md5, printed and recorded;
  * the native patches are located by instruction signature, not by address, so a
    relink only moves the patch, it does not break it (see
    tools/patch_x8u_ext_isz.py:resolve_gate);
  * the payload directory carries baseline.json so post-fs-data.sh, apply-now.sh
    and whoever debugs this later can tell which stock it belongs to.

Usage:
    python tools/build_pkj110_payload.py \
        --gen 17.0.1.201 \
        --odm-lib   stock/odm/lib64/libextensionlayer.so \
        --caf-lib   stock/vendor_lib64/caflogicaldriver.so \
        --out       module/payload_pkj110_201

Optional (only when the file was extracted intact -- see below):
    --hw-config stock/odm/etc/camera/CameraHWConfiguration.config
    --eis-vcfg  stock/odm/etc/camera/oplus_eis_camera.vcfg

Why the config/vcfg are optional: they are EROFS-compressed and our pure-python
reader (tools/erofs_extract.py) cannot decode the last pcluster of a big file, so
a freshly extracted copy can carry a garbage tail. Shipping a truncated camera
config is worse than shipping none -- the 17.0.1.201 provider crash came from a
stale config in the first place. Ask for the file from a running device instead:
    su -c 'cp /odm/etc/camera/CameraHWConfiguration.config /sdcard/'
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def build_id(data: bytes) -> str:
    if data[:4] != b"\x7fELF":
        return "?"
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]

    def sh(i):
        return struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + i * e_shentsize)

    try:
        strtab = sh(e_shstrndx)
        for i in range(e_shnum):
            s = sh(i)
            name = data[strtab[4] + s[0]:].split(b"\0")[0]
            if name == b".note.gnu.build-id":
                raw = data[s[4]:s[4] + s[5]]
                namesz = struct.unpack_from("<I", raw, 0)[0]
                descsz = struct.unpack_from("<I", raw, 4)[0]
                return raw[12 + namesz:12 + namesz + descsz].hex()
    except Exception:
        return "?"
    return "?"


def fingerprint(path: Path) -> dict:
    data = path.read_bytes()
    return {
        "path": str(path),
        "size": len(data),
        "md5": hashlib.md5(data).hexdigest(),
        "build_id": build_id(data),
        "elf": data[:4] == b"\x7fELF",
    }


def run(cmd: list[str]) -> None:
    print("+ " + " ".join(cmd))
    rc = subprocess.call(cmd)
    if rc != 0:
        sys.exit(f"error: command failed with {rc}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gen", required=True, help="baseline label, e.g. 17.0.1.201")
    ap.add_argument("--odm-lib", required=True, type=Path)
    ap.add_argument("--caf-lib", required=True, type=Path)
    ap.add_argument("--hw-config", type=Path)
    ap.add_argument("--eis-vcfg", type=Path)
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()

    out: Path = a.out
    (out / "lib64").mkdir(parents=True, exist_ok=True)

    stock = {"generation": a.gen, "sources": {}}
    stock["sources"]["libextensionlayer.so"] = fingerprint(a.odm_lib)
    stock["sources"]["caflogicaldriver.so"] = fingerprint(a.caf_lib)
    for s in stock["sources"].values():
        if not s["elf"]:
            sys.exit(f"error: {s['path']} is not an ELF file -- extraction is broken")

    run([sys.executable, str(REPO / "tools/patch_x8u_ext_isz.py"),
         "--input", str(a.odm_lib),
         "--output", str(out / "lib64/libextensionlayer.so")])
    run([sys.executable, str(REPO / "tools/patch_x8u_caf_sat.py"),
         "--stock", str(a.caf_lib),
         "-o", str(out / "lib64/com.qti.stats.caflogicaldriver.so")])

    payload = {
        "generation": a.gen,
        "files": {},
    }
    for rel in ("lib64/libextensionlayer.so", "lib64/com.qti.stats.caflogicaldriver.so"):
        payload["files"][rel] = fingerprint(out / rel)

    if a.hw_config:
        blob = a.hw_config.read_bytes()
        if b"\0" in blob:
            sys.exit(f"error: {a.hw_config} contains NUL bytes -- truncated EROFS tail, "
                     "pull it from a running device instead")
        (out / "etc/camera").mkdir(parents=True, exist_ok=True)
        (out / "etc/camera/CameraHWConfiguration.config").write_bytes(blob)
        stock["sources"]["CameraHWConfiguration.config"] = fingerprint(a.hw_config)
    if a.eis_vcfg:
        blob = a.eis_vcfg.read_bytes()
        try:
            json.loads(blob.decode("utf-8"))
        except Exception as exc:
            sys.exit(f"error: {a.eis_vcfg} is not valid JSON ({exc}) -- truncated tail, "
                     "pull it from a running device instead")
        (out / "etc").mkdir(parents=True, exist_ok=True)
        (out / "etc/oplus_eis_camera.vcfg").write_bytes(blob)
        stock["sources"]["oplus_eis_camera.vcfg"] = fingerprint(a.eis_vcfg)

    (out / "baseline.json").write_text(
        json.dumps({"stock": stock, "payload": payload}, indent=1) + "\n",
        encoding="utf-8")

    print("\n-- fingerprints the module gates on (stock side) --")
    for name, s in stock["sources"].items():
        print(f"  {name:32} md5={s['md5']} bid={s['build_id'][:16]} size={s['size']}")
    print("-- shipped payload --")
    for rel, s in payload["files"].items():
        print(f"  {rel:32} md5={s['md5']}")
    print(f"\nwrote {out} (baseline.json included)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
