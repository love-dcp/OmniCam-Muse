#!/usr/bin/env python3
"""
X8U (PKJ110) HangDumpBufferManager 泄漏 —— 源头补丁 (variant D / leak-source)

★ 与 variant C (析构补救) 的区别：
  C 在 MCTFENode 析构里补 Uninitialize —— 太晚，且对 type=7 主 PacketManager 也有副作用。
  D 直接堵在"创建路径覆盖旧指针"那一刻 —— 精准、无副作用。

根因（2026-10-28 反汇编 + 运行时实锤）:
  MCTFENode::PostCreate 里创建 HangDump manager:
    0x65d64  bl 0x66230                      ; wrapper: 创建 manager + 注册
  0x66230 wrapper 内部:
    0x662b4  bl <create>                     ; x22 = 新 CmdBufferManager
    0x662e8  bl 0x676a0                      ; hashmap find_or_insert(key=8) -> x0 = 节点
    0x662ec  str x22, [x0, #0x18]            ; ★ 直接把 manager 写进节点 +0x18
  0x676a0 语义已确认 = find_or_insert:
    0x66804 bl 0x676a0 / 0x66808 ldr x22,[x0,#0x18]   <- 纯查找用法，返回既有节点
  所以 key=8 已存在时，0x676a0 返回【旧节点】，
  0x662ec 的 str 直接覆盖旧 manager 指针 -> 旧对象失去引用 -> 617KB DMA 永久泄漏。

  后果链（实测）:
    MCTFE EPR 失败 -> recovery 循环 3.2s/轮 -> 每轮重建 manager + 覆盖 -> 每轮漏 617KB
    -> 累积 33 个 -> synx: Subsystem restart for core_id 12 (DSP 崩)
    -> DMI_STATUS polling timed out -> sensor 命令发不下去 -> 预览死、界面卡住

补丁:
  0x662ec  str x22,[x0,#0x18]  ->  b 0x5dcc4
  cave @0x5dcc4:
    stp x29,x30,[sp,#-0x30]!
    stp x0,x22,[sp,#0x10]         ; 保存 节点指针 / 新 manager
    ldr x9,[x0,#0x18]             ; 旧 manager
    cbz x9, skip                  ; 首次创建时为空 -> 直接跳过
    adrp/ldr x16, GOT[Recycle]
    sub x16,x16,#0x660            ; Uninitialize = Recycle - 0x660 (同在 libcamxcoreutils)
    mov x0,x9
    blr x16                       ; Uninitialize(旧) -> UninitializePools + FreeMemHandles
 skip:
    ldp x0,x22,[sp,#0x10]
    ldp x29,x30,[sp],#0x30
    str x22,[x0,#0x18]            ; 原始指令
    b 0x662f0                     ; 回原流程

安全性:
  - 首次创建: [x0,#0x18]==0 -> cbz 直接跳过，行为与原版完全一致
  - 重复创建: 先释放旧的再覆盖，不再泄漏
  - Uninitialize 自带 this[0x108]==1 幂等门，即使被重复调用也安全

用法:
  python patch_x8u_fe_leak_source.py --input libcamxhwlfrontend.so --out fe_leakfix_v1.so
"""
import argparse
import hashlib
import struct
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
except ImportError:
    Cs = None

HIJACK_VA = 0x662EC          # str x22, [x0, #0x18]
CAVE_VA = 0x5DCC4            # .text 内 828B 全 0 填充洞
RET_VA = 0x662F0             # 原流程下一条指令
GOT_RECYCLE = 0x1A9C88       # frontend GOT 里 Recycle 的项
RECYCLE_MINUS_UNINIT = 0x660  # Uninitialize(0xcd290) = Recycle(0xcd8f0) - 0x660

ORIG_STR = bytes.fromhex("160c00f9")   # 0xF9000C16 str x22, [x0, #0x18]


# ---------------- ARM64 编码 ----------------
def enc_b(pc, target):
    off = (target - pc) >> 2
    assert -(1 << 25) <= off < (1 << 25), "b 超出范围"
    return struct.pack("<I", 0x14000000 | (off & 0x03FFFFFF))


def enc_blr(rn):
    return struct.pack("<I", 0xD63F0000 | (rn << 5))


def enc_cbz64(rt, pc, target):
    off = (target - pc) >> 2
    assert -(1 << 18) <= off < (1 << 18), "cbz 超出范围"
    return struct.pack("<I", 0xB4000000 | ((off & 0x7FFFF) << 5) | rt)


def enc_mov_orr(rd, rm):
    return struct.pack("<I", 0xAA0003E0 | (rm << 16) | rd)


def enc_adrp(rd, pc, target):
    imm = ((target & ~0xFFF) - (pc & ~0xFFF)) >> 12
    immlo = imm & 3
    immhi = (imm >> 2) & 0x7FFFF
    return struct.pack("<I", 0x90000000 | (immlo << 29) | (immhi << 5) | rd)


def enc_ldr_imm64(rt, rn, off):
    assert off % 8 == 0 and 0 <= off < 0x8000
    return struct.pack("<I", 0xF9400000 | ((off // 8) << 10) | (rn << 5) | rt)


def enc_sub_imm64(rd, rn, imm):
    assert 0 <= imm < 0x1000
    return struct.pack("<I", 0xD1000000 | (imm << 10) | (rn << 5) | rd)


def enc_stp_pre64(rt, rt2, rn, imm):
    assert imm % 8 == 0 and -256 <= imm <= 248
    return struct.pack("<I", 0xA9800000 | (((imm // 8) & 0x7F) << 15) | (rt2 << 10) | (rn << 5) | rt)


def enc_ldp_post64(rt, rt2, rn, imm):
    assert imm % 8 == 0 and -256 <= imm <= 248
    return struct.pack("<I", 0xA8C00000 | (((imm // 8) & 0x7F) << 15) | (rt2 << 10) | (rn << 5) | rt)


def enc_stp_imm64(rt, rt2, rn, imm):
    assert imm % 8 == 0 and 0 <= imm < 0x800
    return struct.pack("<I", 0xA9000000 | ((imm // 8) << 15) | (rt2 << 10) | (rn << 5) | rt)


def enc_ldp_imm64(rt, rt2, rn, imm):
    assert imm % 8 == 0 and 0 <= imm < 0x800
    return struct.pack("<I", 0xA9400000 | ((imm // 8) << 15) | (rt2 << 10) | (rn << 5) | rt)


def build_cave():
    code = bytearray()
    pc = CAVE_VA

    def emit(b):
        nonlocal pc
        assert len(b) == 4, b.hex()
        code.extend(b)
        pc += 4
        return pc - 4

    emit(enc_stp_pre64(29, 30, 31, -0x30))                 # stp x29, x30, [sp, #-0x30]!
    emit(enc_stp_imm64(0, 22, 31, 0x10))                   # stp x0, x22, [sp, #0x10]
    emit(enc_ldr_imm64(9, 0, 0x18))                        # ldr x9, [x0, #0x18]   (旧 manager)
    at_cbz = pc
    emit(enc_cbz64(9, pc, 0))                              # cbz x9, <skip>  (占位, 回填)
    emit(enc_adrp(16, pc, GOT_RECYCLE))                    # adrp x16, GOT[Recycle] 页
    emit(enc_ldr_imm64(16, 16, GOT_RECYCLE & 0xFFF))       # ldr  x16, [x16, #off]
    emit(enc_sub_imm64(16, 16, RECYCLE_MINUS_UNINIT))      # sub  x16, x16, #0x660
    emit(enc_mov_orr(0, 9))                                # mov  x0, x9
    emit(enc_blr(16))                                      # blr  x16  (Uninitialize)
    skip_va = pc
    emit(enc_ldp_imm64(0, 22, 31, 0x10))                   # ldp x0, x22, [sp, #0x10]
    emit(enc_ldp_post64(29, 30, 31, 0x30))                 # ldp x29, x30, [sp], #0x30
    emit(bytes.fromhex("160c00f9"))                        # str x22, [x0, #0x18]  (原始指令)
    emit(enc_b(pc, RET_VA))                                # b 0x662f0

    # 回填 cbz
    fixed = enc_cbz64(9, at_cbz, skip_va)
    code[(at_cbz - CAVE_VA):(at_cbz - CAVE_VA) + 4] = fixed
    return bytes(code)


def selftest(cave):
    if Cs is None:
        print("[!] 无 capstone，跳过自校验")
        return
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    lines = [f"  {i.address:#08x}: {i.bytes.hex():<8} {i.mnemonic:<7} {i.op_str}"
             for i in md.disasm(cave, CAVE_VA)]
    print("[cave 反汇编]")
    for ln in lines:
        print(ln)
    got = [ln.split(": ")[1].split()[1] for ln in lines]
    expect = ["stp", "stp", "ldr", "cbz", "adrp", "ldr", "sub", "mov", "blr",
              "ldp", "ldp", "str", "b"]
    if got != expect:
        raise SystemExit(f"[X] cave 自校验失败\n got={got}\n exp={expect}")
    print(f"[OK] cave 自校验通过（{len(cave)}B）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    data = bytearray(open(args.input, "rb").read())

    if data[HIJACK_VA:HIJACK_VA + 4] != ORIG_STR:
        raise SystemExit(f"[X] 0x{HIJACK_VA:x} 原始字节不符: {data[HIJACK_VA:HIJACK_VA+4].hex()} "
                         f"(期望 {ORIG_STR.hex()})")
    if data[CAVE_VA:CAVE_VA + 64] != b"\x00" * 64:
        raise SystemExit(f"[X] cave 0x{CAVE_VA:x} 不是全 0 填充区，换地址")

    cave = build_cave()
    selftest(cave)

    print(f"\n[注入] 0x{HIJACK_VA:x} str x22,[x0,#0x18] -> b 0x{CAVE_VA:x}")
    data[HIJACK_VA:HIJACK_VA + 4] = enc_b(HIJACK_VA, CAVE_VA)
    data[CAVE_VA:CAVE_VA + len(cave)] = cave

    open(args.out, "wb").write(data)
    print(f"[输出] {args.out}")
    print(f"[md5 ] {hashlib.md5(data).hexdigest()}")


if __name__ == "__main__":
    main()
