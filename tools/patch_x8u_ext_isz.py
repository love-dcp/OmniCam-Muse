"""X8U (PKJ110) pro-video in-sensor-zoom unlock for /odm/lib64/libextensionlayer.so.

This is the NATIVE X8U mechanism, discovered 2026-10-26 after the CameraHWConfiguration.config
route (tools/patch_x8u_video_isz.py) proved to be dead code on this build:

  * The [InsensorZoom] keys are consumed by AECStatsProcessor inside camera.qcom.so -- which is
    never instantiated on X8U (Create() at 0x7efa00 is unreferenced; a 562-segment heap scan for
    its vtable at 0x9a6b20 found zero instances). Everything that patch rewrote was inert.
  * The live decision is ``ExtensionPluginBase::IsIZoomEnableVideo`` in libextensionlayer.so
    (0xa5d70), reached through the ChiX extension layer. Its chain: zoom ratio in [2.0, 30.0],
    modeDescriptor in {4, 6, 8, 10, 0x1e}, and ``aecIzoomState[camId] == 1`` (60fps is still
    rejected outright).
  * ``aecIzoomState`` is an AEC runtime state the X8U never raises: the 8-entry array stays
    all-zero while, under identical conditions, the PREVIEW path (IsIZoomEnablePreview,
    0xa5924 -- same chain minus the AEC gate) returns isTriggerIzoom=1. The gate is therefore
    pure conservatism, not a capability check.

Patch: exactly one instruction, at vaddr 0xa632c inside IsIZoomEnableVideo::

    ccmp w16, #1, #0, ne   (0x7a411a00)   -- "is aecIzoomState[camId] == 1 ?"
    ccmp wzr, #0, #0, ne   (0x7a401be0)   -- "is 0 == 0 ?" -> always true

The comparison compares equal unconditionally, so the AEC gate passes while the zoom-range,
mode-whitelist and 60fps rejections keep working: video converges on preview behaviour and
nothing else changes. Verified 2026-10-26 on 1080p30 pro video (opmode 0x830b) @ 2.5x:
IsIZoomEnableVideo -> isTriggerIzoom 1 -> IZoomRequire 1 -> zoomState 2 ->
FillScalerRawCropRegion SensorMode:13 Crop {2048,1792,4096,2560} of the 8192x6144 array
(102 hits over one recording vs 0 before), pipeline stable at ~29fps, no crash.

Deployment (module ricoh_pop_x9u, PKJ110 profile): the patched lib is bound over
/odm/lib64/libextensionlayer.so by post-fs-data.sh with u:object_r:vendor_file:s0;
``touch /data/adb/modules/ricoh_pop_x9u/disable_pro_video_isz`` disables the bind.
Diagnostics note: the decision log lives under the "ChiX" tag, pluginbase.cpp:5321-5378, and
is gated by g_enableChxLogs[4]/[6] (libchilog.so + 0xc00c) which OverrideLogSettingsAtConfigureFile
resets on every camera mode switch -- re-hot-write the gate AFTER entering the mode.

Usage:
    python tools/patch_x8u_ext_isz.py \
        --input  libextensionlayer.so \
        --output payload_pkj110/lib64/libextensionlayer.so
"""

import argparse
import hashlib
import struct
import sys

# IsIZoomEnableVideo AEC-gate compare, at this vaddr (== file offset on this build:
# every PT_LOAD has p_offset == p_vaddr, verified against the shipped .so).
PATCH_VADDR = 0xA632C

ORIG_WORD = 0x7A411A00  # ccmp w16, #1, #0, ne
NEW_WORD = 0x7A401BE0  # ccmp wzr, #0, #0, ne

# 60fps rejection, ~28 bytes later in the same function (verified 2026-10-30):
#   0xa6354: ldr w12, [x23, #0xec]      ; configured fps
#   0xa6358: cmp w12, #0x3c             ; == 60 ?
#   0xa635c: csel w21, wzr, w21, eq     ; 60fps -> zero the ISZ flag
# NOPing the csel keeps w21 (the whitelist/range result) at any fps, so ISZ is
# decided by zoom-range + mode-whitelist + AEC gate alone.
FPS_VADDR = 0xA635C
FPS_ORIG_WORD = 0x1A9503F5  # csel w21, wzr, w21, eq
NOP_WORD = 0xD503201F       # nop

STOCK_MD5 = None  # filled in by main() from the input; informational only


def vaddr_to_offset(data: bytes, vaddr: int) -> int:
    """Map a virtual address to a file offset via PT_LOAD headers.

    Aborts if vaddr is not inside an executable segment, so a relocated or
    differently linked build cannot be patched blind.
    """
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        sys.exit("error: input is not a little-endian ELF64 file")
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags = struct.unpack_from("<II", data, off)
        p_offset, p_vaddr, _p_paddr, p_filesz = struct.unpack_from("<QQQQ", data, off + 8)
        if p_type != 1:  # PT_LOAD
            continue
        if p_vaddr <= vaddr < p_vaddr + p_filesz:
            if not p_flags & 1:  # PF_X
                sys.exit(f"error: vaddr {vaddr:#x} is not in an executable segment")
            return vaddr - p_vaddr + p_offset
    sys.exit(f"error: vaddr {vaddr:#x} is not covered by any PT_LOAD segment")


def capstone_check(word: int, expect: str) -> None:
    try:
        from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    except ImportError:
        print("note: capstone not installed, skipping disassembly cross-check")
        return
    insn = next(iter(Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN).disasm(
        struct.pack("<I", word), 0)))
    if f"{insn.mnemonic} {insn.op_str}" != expect:
        sys.exit(f"error: encoding {word:#x} is not '{expect}' "
                 f"(decodes as '{insn.mnemonic} {insn.op_str}')")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--input", required=True, help="stock libextensionlayer.so")
    ap.add_argument("--output", required=True, help="patched copy to write")
    ap.add_argument("--allow-60fps", action="store_true",
                    help="also NOP the 60fps csel at 0xa635c so IsIZoomEnableVideo "
                         "no longer zeroes the ISZ flag at >=60fps (experiment, 2026-10-30)")
    args = ap.parse_args()

    data = bytearray(open(args.input, "rb").read())
    off = vaddr_to_offset(bytes(data), PATCH_VADDR)
    orig = struct.unpack_from("<I", data, off)[0]
    if orig != ORIG_WORD:
        sys.exit(f"error: word at vaddr {PATCH_VADDR:#x} (file offset {off:#x}) is "
                 f"{orig:#x}, expected {ORIG_WORD:#x} -- wrong build?")

    capstone_check(orig, "ccmp w16, #1, #0, ne")
    capstone_check(NEW_WORD, "ccmp wzr, #0, #0, ne")

    struct.pack_into("<I", data, off, NEW_WORD)
    print(f"patched {args.input} vaddr {PATCH_VADDR:#x} (file offset {off:#x}): "
          f"{ORIG_WORD:#x} -> {NEW_WORD:#x} (AEC izoom gate permanently passes)")

    if args.allow_60fps:
        foff = vaddr_to_offset(bytes(data), FPS_VADDR)
        forig = struct.unpack_from("<I", data, foff)[0]
        if forig != FPS_ORIG_WORD:
            sys.exit(f"error: word at vaddr {FPS_VADDR:#x} (file offset {foff:#x}) is "
                     f"{forig:#x}, expected {FPS_ORIG_WORD:#x} -- wrong build?")
        capstone_check(forig, "csel w21, wzr, w21, eq")
        struct.pack_into("<I", data, foff, NOP_WORD)
        print(f"patched vaddr {FPS_VADDR:#x} (file offset {foff:#x}): "
              f"{FPS_ORIG_WORD:#x} -> {NOP_WORD:#x} (60fps rejection NOPed)")

    blob = bytes(data)
    with open(args.output, "wb") as fh:  # binary mode: no CRLF translation ever
        fh.write(blob)

    print(f"stock    md5 {hashlib.md5(open(args.input,'rb').read()).hexdigest()}")
    print(f"patched  md5 {hashlib.md5(blob).hexdigest()}")
    print(f"wrote {args.output} ({len(blob)} bytes)")


if __name__ == "__main__":
    main()
