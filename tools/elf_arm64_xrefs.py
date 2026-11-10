"""Find AArch64 code references to byte strings or hexadecimal virtual addresses.

The X8U camera libraries are stripped, so names such as ``loadEISConfig`` only
exist as literals. This utility maps those literals to runtime addresses and
finds nearby ADRP/ADD or ADR instructions in executable PT_LOAD segments.
"""

import argparse
import struct
from dataclasses import dataclass

from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs


@dataclass(frozen=True)
class Segment:
    offset: int
    vaddr: int
    file_size: int
    flags: int

    @property
    def executable(self) -> bool:
        return bool(self.flags & 1)


def load_segments(data: bytes) -> list[Segment]:
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("error: input is not a little-endian ELF64 file")

    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize = struct.unpack_from("<H", data, 0x36)[0]
    phnum = struct.unpack_from("<H", data, 0x38)[0]
    segments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from("<II", data, offset)
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, offset + 8)
        p_filesz = struct.unpack_from("<Q", data, offset + 32)[0]
        if p_type == 1:
            segments.append(Segment(p_offset, p_vaddr, p_filesz, p_flags))
    return segments


def file_to_vaddr(segments: list[Segment], offset: int) -> int | None:
    for segment in segments:
        if segment.offset <= offset < segment.offset + segment.file_size:
            return segment.vaddr + offset - segment.offset
    return None


def find_literals(data: bytes, segments: list[Segment], literal: bytes) -> list[int]:
    addresses = []
    start = 0
    while True:
        offset = data.find(literal, start)
        if offset < 0:
            break
        address = file_to_vaddr(segments, offset)
        if address is not None:
            addresses.append(address)
        start = offset + 1
    return addresses


def operands(insn):
    return tuple(insn.operands)


def iter_executable_instructions(data: bytes, segments: list[Segment]):
    engine = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    engine.detail = True
    for segment in segments:
        if not segment.executable:
            continue
        blob = data[segment.offset : segment.offset + segment.file_size]
        for relative in range(0, len(blob) - 3, 4):
            address = segment.vaddr + relative
            insn = next(engine.disasm(blob[relative : relative + 4], address), None)
            if insn is not None:
                yield insn


def find_xrefs(data: bytes, segments: list[Segment], targets: set[int]) -> list[tuple[int, int]]:
    last_adrp = {}
    hits = []
    for insn in iter_executable_instructions(data, segments):
        ops = operands(insn)
        if insn.mnemonic == "adrp" and len(ops) >= 2:
            last_adrp[ops[0].reg] = (insn.address, ops[1].imm)
            continue
        if insn.mnemonic == "add" and len(ops) >= 3 and ops[1].reg in last_adrp:
            adrp_address, page = last_adrp[ops[1].reg]
            target = page + ops[2].imm
            if target in targets:
                hits.append((insn.address, target))
            continue
        if insn.mnemonic == "adr" and len(ops) >= 2 and ops[1].imm in targets:
            hits.append((insn.address, ops[1].imm))
    return hits


def disassemble_window(data: bytes, segments: list[Segment], address: int, before: int, after: int):
    segment = next(
        (s for s in segments if s.executable and s.vaddr <= address < s.vaddr + s.file_size),
        None,
    )
    if segment is None:
        return
    start = max(segment.vaddr, address - before * 4)
    end = min(segment.vaddr + segment.file_size, address + (after + 1) * 4)
    offset = segment.offset + start - segment.vaddr
    engine = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    blob = data[offset : segment.offset + end - segment.vaddr]
    for insn in engine.disasm(blob, start):
        marker = "=>" if insn.address == address else "  "
        print(f"{marker} {insn.address:08x}: {insn.mnemonic:<8} {insn.op_str}")


def parse_address(value: str) -> int:
    return int(value, 0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("binary")
    parser.add_argument("--string", action="append", default=[])
    parser.add_argument("--address", action="append", type=parse_address, default=[])
    parser.add_argument("--context", type=int, default=6)
    args = parser.parse_args()

    data = open(args.binary, "rb").read()
    segments = load_segments(data)
    targets = set(args.address)
    target_names = {}

    for text in args.string:
        addresses = find_literals(data, segments, text.encode())
        if not addresses:
            print(f"{text!r}: not found")
        for address in addresses:
            print(f"{text!r}: {address:#x}")
            targets.add(address)
            target_names[address] = text

    for address in args.address:
        if address not in target_names:
            print(f"address {address:#x}")

    hits = find_xrefs(data, segments, targets)
    for address, target in hits:
        label = target_names.get(target, f"{target:#x}")
        print(f"\nXREF {label} at {address:#x}")
        disassemble_window(data, segments, address, args.context, args.context)

    print(f"\n# {len(hits)} direct xref(s)")


if __name__ == "__main__":
    main()
