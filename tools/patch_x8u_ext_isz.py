"""X8U (PKJ110) pro-video in-sensor-zoom unlock for /odm/lib64/libextensionlayer.so.

This is the NATIVE X8U mechanism, discovered 2026-10-26 after the CameraHWConfiguration.config
route (tools/patch_x8u_video_isz.py) proved to be dead code on this build:

  * The [InsensorZoom] keys are consumed by AECStatsProcessor inside camera.qcom.so -- which is
    never instantiated on X8U (Create() at 0x7efa00 is unreferenced; a 562-segment heap scan for
    its vtable at 0x9a6b20 found zero instances). Everything that patch rewrote was inert.
  * The live decision is ``ExtensionPluginBase::IsIZoomEnableVideo`` in libextensionlayer.so
    (0xa5d70), reached through the ChiX extension layer. Its chain: zoom ratio in [2.0, 30.0],
    modeDescriptor in {4, 6, 8, 10, 0x1e}, and ``aecIzoomState[camId] == 1``.
  * ``aecIzoomState`` is an AEC runtime state the X8U never raises: the 8-entry array stays
    all-zero while, under identical conditions, the PREVIEW path (IsIZoomEnablePreview,
    0xa5924 -- same chain minus the AEC gate) returns isTriggerIzoom=1. The gate is therefore
    pure conservatism, not a capability check.
  * Runtime logs identify the mode reliably: professional video reports m_modeDescriptor 30,
    while ordinary video, including ordinary video with super stabilization, reports 4. The
    previous work guessed that ExtensionPluginBase+0x54 held an operation mode and compared it
    with 0x830B; that field is not a reliable session discriminator and is no longer read.

Patches:

  1. 0xa61c8-0xa61d8 -- replace the broad descriptor/range admission test with an exact
     professional-video and near-zoom test. s9 is the requested zoom ratio:

         fmov s0, #3.0
         fcmp s0, s9              ; compare the 3.0 ceiling with requested zoom
         cset w11, ge             ; ordered 3.0 >= zoom; NaN is rejected
         cmp  w7, #0x1e           ; m_modeDescriptor == professional video
         csel w21, w11, wzr, eq   ; feed the existing range/AEC chain

     This admits professional-video requests from 2.0X through 3.0X (the original lower-range
     loop still enforces >= 2.0), rejects ordinary descriptor-4 video, and prevents the native
     function from reporting ISZ at 4X-6X. The 6X path therefore stays on the stock telephoto
     pipeline even if a stale configuration row exists.

  2. 0xa632c -- bypass the dormant AEC gate:

    ccmp w16, #1, #0, ne   (0x7a411a00)   -- "is aecIzoomState[camId] == 1 ?"
    ccmp wzr, #0, #0, ne   (0x7a401be0)   -- "is 0 == 0 ?" -> always true

The AEC patch makes that runtime gate pass; the descriptor-plus-zoom patch supplies the missing
professional-video scope. The original lower zoom bound and 60fps rejection stay intact unless
``--allow-60fps`` is explicitly requested for an experiment.

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

# IsIZoomEnableVideo. These vaddrs equal file offsets on this build: every PT_LOAD has
# p_offset == p_vaddr, but vaddr_to_offset() below still validates that instead of assuming it.
AEC_GATE_VADDR = 0xA632C

AEC_GATE_ORIG_WORD = 0x7A411A00  # ccmp w16, #1, #0, ne
AEC_GATE_NEW_WORD = 0x7A401BE0   # ccmp wzr, #0, #0, ne

# Original descriptor whitelist, 0xa61c8..0xa61d8:
#   and  w11, w7, #0xfffffffd
#   cmp  w11, #4
#   ccmp w11, #8, #4, ne
#   ccmp w7, #0x1e, #4, ne
#   cset w21, eq
MODE_GATE_VADDR = 0xA61C8
MODE_GATE_ORIG_WORDS = (
    0x121E78EB,  # and  w11, w7, #0xfffffffd
    0x7100117F,  # cmp  w11, #4
    0x7A481964,  # ccmp w11, #8, #4, ne
    0x7A5E18E4,  # ccmp w7, #0x1e, #4, ne
    0x1A9F17F5,  # cset w21, eq
)
MODE_GATE_NEW_WORDS = (
    0x1E211000,  # fmov s0, #3.0
    0x1E292000,  # fcmp s0, s9
    0x1A9FB7EB,  # cset w11, ge            (ordered 3.0 >= zoom, NaN rejected)
    0x710078FF,  # cmp  w7, #0x1e          (m_modeDescriptor == professional video)
    0x1A9F0175,  # csel w21, w11, wzr, eq
)

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
    decoded = insn.mnemonic + (f" {insn.op_str}" if insn.op_str else "")
    if decoded != expect:
        sys.exit(f"error: encoding {word:#x} is not '{expect}' "
                 f"(decodes as '{decoded}')")


def patch_word(data: bytearray, vaddr: int, orig: int, new: int, expect: str,
               replacement: str) -> None:
    off = vaddr_to_offset(bytes(data), vaddr)
    actual = struct.unpack_from("<I", data, off)[0]
    if actual != orig:
        sys.exit(f"error: word at vaddr {vaddr:#x} (file offset {off:#x}) is "
                 f"{actual:#x}, expected {orig:#x} -- wrong build or already patched?")
    capstone_check(orig, expect)
    capstone_check(new, replacement)
    struct.pack_into("<I", data, off, new)
    print(f"patched vaddr {vaddr:#x} (file offset {off:#x}): "
          f"{orig:#x} -> {new:#x} ({replacement})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--input", required=True, help="stock libextensionlayer.so")
    ap.add_argument("--output", required=True, help="patched copy to write")
    ap.add_argument("--allow-60fps", action="store_true",
                    help="also NOP the 60fps csel at 0xa635c so IsIZoomEnableVideo "
                         "no longer zeroes the ISZ flag at >=60fps (experiment, 2026-10-30)")
    args = ap.parse_args()

    data = bytearray(open(args.input, "rb").read())

    off = vaddr_to_offset(bytes(data), MODE_GATE_VADDR)
    actual = struct.unpack_from(f"<{len(MODE_GATE_ORIG_WORDS)}I", data, off)
    if actual != MODE_GATE_ORIG_WORDS:
        printable = ", ".join(f"{word:#x}" for word in actual)
        expected = ", ".join(f"{word:#x}" for word in MODE_GATE_ORIG_WORDS)
        sys.exit(f"error: mode gate at vaddr {MODE_GATE_VADDR:#x} (file offset {off:#x}) is "
                 f"({printable}), expected ({expected}) -- wrong build or already patched?")
    for expect, word in zip(
        ("and w11, w7, #0xfffffffd", "cmp w11, #4", "ccmp w11, #8, #4, ne",
         "ccmp w7, #0x1e, #4, ne", "cset w21, eq"),
        MODE_GATE_ORIG_WORDS,
    ):
        capstone_check(word, expect)
    for expect, word in zip(
        ("fmov s0, #3.00000000", "fcmp s0, s9", "cset w11, ge", "cmp w7, #0x1e",
         "csel w21, w11, wzr, eq"),
        MODE_GATE_NEW_WORDS,
    ):
        capstone_check(word, expect)
    struct.pack_into(f"<{len(MODE_GATE_NEW_WORDS)}I", data, off, *MODE_GATE_NEW_WORDS)
    print(f"patched {args.input} vaddr {MODE_GATE_VADDR:#x} (file offset {off:#x}): "
          f"descriptor whitelist -> professional video within 3.0x")

    patch_word(
        data, AEC_GATE_VADDR, AEC_GATE_ORIG_WORD, AEC_GATE_NEW_WORD,
        "ccmp w16, #1, #0, ne", "ccmp wzr, #0, #0, ne",
    )

    if args.allow_60fps:
        patch_word(
            data, FPS_VADDR, FPS_ORIG_WORD, NOP_WORD,
            "csel w21, wzr, w21, eq", "nop",
        )

    blob = bytes(data)
    with open(args.output, "wb") as fh:  # binary mode: no CRLF translation ever
        fh.write(blob)

    print(f"stock    md5 {hashlib.md5(open(args.input,'rb').read()).hexdigest()}")
    print(f"patched  md5 {hashlib.md5(blob).hexdigest()}")
    print(f"wrote {args.output} ({len(blob)} bytes)")


if __name__ == "__main__":
    main()
