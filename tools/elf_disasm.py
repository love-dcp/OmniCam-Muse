"""Disassemble a named AArch64 ELF symbol or an explicit address range.

Exact branch targets are annotated with symbol names when the ELF retains a
symbol table. This keeps vendor-library reverse engineering repeatable without
requiring a system-wide disassembler.
"""

import argparse
import struct

from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

from elf_symbols import list_symbols, read_sections


def executable_segment(data, sections, address):
    for section in sections:
        if not (section["flags"] & 0x4):
            continue
        if section["address"] <= address < section["address"] + section["size"]:
            return section
    raise SystemExit(f"error: address {address:#x} is not in an executable section")


def symbol_map(data, sections):
    return {address: name for address, _, _, name in list_symbols(data, sections, "")}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("binary")
    parser.add_argument("symbol", help="mangled symbol name or address such as 0x2e320")
    parser.add_argument("--length", type=lambda value: int(value, 0))
    parser.add_argument("--start", type=lambda value: int(value, 0))
    parser.add_argument("--stop", type=lambda value: int(value, 0))
    args = parser.parse_args()

    data = open(args.binary, "rb").read()
    sections = read_sections(data)
    symbols = list_symbols(data, sections, "")
    names = {address: name for address, _, _, name in symbols}

    if args.symbol.lower().startswith("0x"):
        start = int(args.symbol, 0)
        length = args.length or 256
    else:
        matches = [item for item in symbols if item[3] == args.symbol]
        if not matches:
            matches = [item for item in symbols if args.symbol in item[3]]
        if len(matches) != 1:
            for address, size, _, name in matches:
                print(f"{address:#x} {size:6d} {name}")
            raise SystemExit(f"error: symbol lookup matched {len(matches)} symbols")
        start, length, _, _ = matches[0]
    if args.start is not None:
        start = args.start
    if args.stop is not None:
        length = args.stop - start

    section = executable_segment(data, sections, start)
    offset = section["offset"] + start - section["address"]
    blob = data[offset:offset + length]
    engine = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    engine.detail = True
    for instruction in engine.disasm(blob, start):
        annotation = ""
        if instruction.mnemonic in ("bl", "b") and instruction.operands:
            target = instruction.operands[0].imm
            if target in names:
                annotation = f"  ; {names[target]}"
        print(f"{instruction.address:08x}: {instruction.mnemonic:<8} "
              f"{instruction.op_str}{annotation}")


if __name__ == "__main__":
    main()
