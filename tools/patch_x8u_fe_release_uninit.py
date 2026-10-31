#!/usr/bin/env python3
"""
X8U 普通视频+防抖 卡死修复：frontend 释放路径补 Uninitialize（v2，外层循环版本）

问题：
  libcamxhwlfrontend.so 的资源释放函数 (0x7f830) 遍历 hashmap 里的 CmdBufferManager 时，
  只调用 CmdBufferManager::Recycle(PacketResource*)。Recycle 仅把 resource 归还 free pool，
  不释放底层 CSL/DMA 内存（617KB/个）。于是每次 pipeline 重建都漏一个，kref 卡在 2，
  最终耗尽 DMI / mini_dump 索引 -> 预览死锁。

为什么不是 v1（0x7f8f0 处 Recycle 后立即 Uninitialize）：
  0x7f8dc 起的循环是【内层循环】，同一个 manager 要连续处理 N 个 PacketResource
  （tombstone_13 实测 x28=8）。v1 在第一次 Recycle 后就 Uninitialize，销毁了
  manager 的 Mutex（[0x168] 被清零），第二次 Recycle 进入 Mutex::Lock(NULL) ->
  SIGSEGV (fault addr 0x0)。

v2 修法：
  改外层循环末尾 0x7f900 的 `ldr x25,[x25]`（此时该 manager 的所有 resource 都已
  Recycle 完毕），跳到代码洞：
      Uninitialize(manager)       ; UninitializePools -> FreeMemHandles -> CSLReleaseBuffer
      ldr x25, [x25]              ; 原指令
      b   0x7f904                 ; 回到外层循环
  Uninitialize 地址 = GOT[Recycle] - 0x660。

用法：
  python tools/patch_x8u_fe_release_uninit.py --input <stock.so> --out <patched.so>
"""
import argparse, hashlib, sys

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

PATCH_AT = 0x7F900          # ldr x25, [x25]  (外层循环末尾)
ORIG_INSN = "390340f9"      # ldr x25, [x25]
RESUME_AT = 0x7F904         # cbnz x25, ...
CAVE = 0x5DCC4              # 全 0 填充区
UNINIT_DELTA = 0x660

CAVE_CODE = bytes.fromhex(
    "fd7bbea9"   # stp  x29, x30, [sp, #-0x20]!
    "e00701a9"   # stp  x0, x1, [sp, #0x10]
    "700a0090"   # adrp x16, #0x1a9000
    "104646f9"   # ldr  x16, [x16, #0xc88]   ; GOT[Recycle]
    "108219d1"   # sub  x16, x16, #0x660     ; -> Uninitialize
    "e00316aa"   # mov  x0, x22              ; manager
    "00023fd6"   # blr  x16
    "e00741a9"   # ldp  x0, x1, [sp, #0x10]
    "fd7bc2a8"   # ldp  x29, x30, [sp], #0x20
    "390340f9"   # ldr  x25, [x25]           ; 原指令
    "06870014"   # b    0x7f904
)


def _bl(from_addr: int, to_addr: int) -> bytes:
    off = to_addr - from_addr
    assert off % 4 == 0, "target not 4-byte aligned"
    imm = off >> 2
    assert -(1 << 25) <= imm < (1 << 25), "bl out of range"
    return (0x94000000 | (imm & 0x3FFFFFF)).to_bytes(4, "little")


def vaddr_to_off(elf: ELFFile, vaddr: int):
    for s in elf.iter_segments():
        if s["p_type"] == "PT_LOAD":
            va, ve = s["p_vaddr"], s["p_vaddr"] + s["p_filesz"]
            if va <= vaddr < ve:
                return s["p_offset"] + (vaddr - va)
    return None


def verify_cave():
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    ins = list(md.disasm(CAVE_CODE, CAVE))
    print("=== cave @ %#x 反汇编自校验 ===" % CAVE)
    for i in ins:
        print(f"  {i.address:#08x}: {i.bytes.hex():<8} {i.mnemonic:<6} {i.op_str}")
    txt = " | ".join(f"{i.mnemonic} {i.op_str}" for i in ins)
    checks = [
        ("stp x29, x30, [sp, #-0x20]!" in txt, "prologue"),
        ("adrp x16, #0x1a9000" in txt, "GOT page"),
        ("ldr x16, [x16, #0xc88]" in txt, "GOT slot"),
        ("sub x16, x16, #0x660" in txt, "Uninitialize delta"),
        ("blr x16" in txt, "indirect call"),
        ("ldr x25, [x25]" in txt, "original insn kept"),
        ("b #0x7f904" in txt, "resume jump"),
    ]
    ok = True
    for good, label in checks:
        print(f"   [{'OK' if good else 'FAIL'}] {label}")
        ok &= good
    if not ok:
        sys.exit("cave 自校验失败")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    verify_cave()

    data = bytearray(open(args.input, "rb").read())
    elf = ELFFile(open(args.input, "rb"))

    off_patch = vaddr_to_off(elf, PATCH_AT)
    off_cave = vaddr_to_off(elf, CAVE)
    if off_patch is None or off_cave is None:
        sys.exit("地址无法映射到文件偏移")

    orig = bytes(data[off_patch:off_patch + 4])
    print(f"\n原始 {PATCH_AT:#x} 字节: {orig.hex()}")
    if orig != bytes.fromhex(ORIG_INSN):
        sys.exit(f"原始指令不匹配（期望 {ORIG_INSN} = ldr x25,[x25]），实际 {orig.hex()}")

    region = bytes(data[off_cave:off_cave + len(CAVE_CODE)])
    if region.strip(b"\x00") != b"":
        sys.exit(f"cave 被占用: {region.hex()}")
    print(f"cave {CAVE:#x} ~ {CAVE + len(CAVE_CODE):#x} 全 0，可用")

    data[off_cave:off_cave + len(CAVE_CODE)] = CAVE_CODE
    data[off_patch:off_patch + 4] = _bl(PATCH_AT, CAVE)

    print(f"补丁后 {PATCH_AT:#x} 字节: {bytes(data[off_patch:off_patch + 4]).hex()}"
          f"  (bl -> {CAVE:#x})")

    open(args.out, "wb").write(bytes(data))
    print(f"\n输出: {args.out}")
    print(f"md5: {hashlib.md5(bytes(data)).hexdigest()}")

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    ins = next(md.disasm(bytes(data[off_patch:off_patch + 4]), PATCH_AT))
    print(f"回读: {ins.mnemonic} {ins.op_str}")
    assert ins.mnemonic == "bl" and int(ins.op_str.replace("#", ""), 0) == CAVE


if __name__ == "__main__":
    main()
