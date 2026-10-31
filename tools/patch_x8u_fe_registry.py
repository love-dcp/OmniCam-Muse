#!/usr/bin/env python3
"""
X8U (PKJ110) 普通视频+防抖 HangDumpBufferManager 泄漏 —— 根治补丁 (variant C)

根因（2026-10-28 运行时实锤）:
  MCTFENode 析构 -> frontend(libcamxhwlfrontend.so) 0x76cd8
    -> 注册表析构 0x64e70（this = 注册表对象）
       注册表布局（tombstone_09 现场 dump 逐字段确认）:
         +0x00  ???  (0x96b08)
         +0x08  桶数组指针
         +0x10  桶数（11，质数）      ← 析构里当 count 清零桶数组
         +0x18  首节点指针            ← 析构第一段就是遍历这条链！
         +0x20  元素数（7）
         +0x40  另一条链（其 [+0x18] 会被 CamxFree 真正释放）
         +0x80  非 HangDump 组的 buffer 基数
         +0x88  名字串指针
       节点布局: +0x00 next / +0x08 hash / +0x10 key(int) / +0x18 value(manager)
       0x64e70 遍历 +0x18 链时，对每个 value 只做一次 CFI 虚调用（blr x12），
       **从不释放 CmdBufferManager 本体** → 其 617344B DMA buffer kref 卡 2 泄漏，
       每次开关流漏一个，累积把 IPE/BPS DMI 通道占死 → 下次 streamon 固件死等 → 卡死。

补丁:
  0x64ed0 `blr x12` -> `b 0x5dcc4`（.text 内 828B 全 0 填充洞）
  code cave @0x5dcc4 先调 CmdBufferManager::Uninitialize 再做原始虚调用:
     Uninitialize 地址 = GOT[Recycle] - 0x660
       （两者同在 libcamxcoreutils.so：Recycle 0xcd8f0 / Uninitialize 0xcd290）
     Uninitialize 内部 = UninitializePools + FreeMemHandles -> CSLReleaseBuffer
     且自带 this[0x108]==1 幂等门，重复调用安全。
  只对 map 里的 value 生效（key 7/8/9 = 主 PacketManager / HangDump / PerFrameRegDump），
  主 PacketManager 若已被正常路径释放，这里的 Uninitialize 会因幂等门直接返回。

用法:
  python patch_x8u_fe_registry.py --input libcamxhwlfrontend.so --out fe_regfix.so
"""
import argparse
import hashlib
import struct
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
except ImportError:
    Cs = None

HIJACK_VA = 0x64ED0          # blr x12
CAVE_VA = 0x5DCC4            # 828B 全 0 填充洞

ORIG_HIJACK = bytes.fromhex("80013fd6")   # 0xD63F0180 blr x12


# ---------------- 手写 ARM64 编码 ----------------
def enc_b(pc, target):
    off = (target - pc) >> 2
    assert -(1 << 25) <= off < (1 << 25), "b 超出范围"
    return struct.pack("<I", 0x14000000 | (off & 0x03FFFFFF))


def enc_blr(rn):
    return struct.pack("<I", 0xD63F0000 | (rn << 5))


def enc_mov_orr(rd, rm):
    """mov rd, rm  →  orr rd, xzr, rm"""
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


def build_cave():
    """code cave: 保存 x12/x30 -> Uninitialize(x21) -> 恢复 -> 原始虚调用 -> 回原流程"""
    GOT_RECYCLE = 0x1A9C88
    code = bytearray()
    pc = CAVE_VA

    def emit(b):
        nonlocal pc
        assert len(b) == 4
        code.extend(b)
        pc += 4
        return pc - 4

    emit(enc_stp_pre64(12, 30, 31, -0x10))                    # stp x12, x30, [sp, #-0x10]!
    emit(enc_adrp(16, pc, GOT_RECYCLE))                       # adrp x16, page(GOT)
    emit(enc_ldr_imm64(16, 16, GOT_RECYCLE & 0xFFF))          # ldr  x16, [x16, #imm]
    emit(enc_sub_imm64(16, 16, 0x660))                        # sub  x16, x16, #0x660
    emit(enc_mov_orr(0, 21))                                  # mov  x0, x21
    emit(enc_blr(16))                                         # blr  x16   (Uninitialize)
    emit(enc_ldp_post64(12, 30, 31, 0x10))                    # ldp x12, x30, [sp], #0x10
    emit(enc_mov_orr(0, 21))                                  # mov  x0, x21
    emit(enc_blr(12))                                         # blr  x12   (原始虚调用)
    emit(enc_b(pc, 0x64ED4))                                  # b 0x64ed4
    return bytes(code)


def selftest(cave):
    """用 capstone 反汇编校验编码（无 capstone 时跳过）"""
    if Cs is None:
        print("[!] 无 capstone，跳过自校验")
        return
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    lines = [f"  {i.address:#08x}: {i.bytes.hex():<8} {i.mnemonic:<6} {i.op_str}"
             for i in md.disasm(cave, CAVE_VA)]
    print("[cave 反汇编]")
    for ln in lines:
        print(ln)
    expect = ["stp", "adrp", "ldr", "sub", "mov", "blr", "ldp", "mov", "blr", "b"]
    got = [ln.split(": ")[1].split()[1] for ln in lines]
    if got != expect:
        raise SystemExit(f"cave 自校验失败: {got} != {expect}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    data = bytearray(open(args.input, "rb").read())

    if data[HIJACK_VA:HIJACK_VA + 4] != ORIG_HIJACK:
        raise SystemExit(f"0x{HIJACK_VA:x} 原始字节不符: {data[HIJACK_VA:HIJACK_VA+4].hex()}")

    cave = build_cave()
    selftest(cave)

    print(f"\n[注入] 0x{HIJACK_VA:x} blr x12 -> b 0x{CAVE_VA:x}  ({len(cave)}B cave)")
    data[HIJACK_VA:HIJACK_VA + 4] = enc_b(HIJACK_VA, CAVE_VA)
    data[CAVE_VA:CAVE_VA + len(cave)] = cave

    open(args.out, "wb").write(data)
    print(f"[输出] {args.out}")
    print(f"[md5 ] {hashlib.md5(data).hexdigest()}")


if __name__ == "__main__":
    main()
