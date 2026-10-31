#!/usr/bin/env python3
"""
X8U (PKJ110) com.qti.chi.override.so — SynchronizeBody INT_MAX 无限等待修复
==========================================================================

问题（2026-10-27 定位）:
  超级防抖视频会话拆除时 MCTF/EIS 缓冲区在内核侧泄漏（kref 2 / uref 0），
  pending 的 Async_RealTimeMCX_Create 任务永远完不成。
  chi.override 内部"Feature2Graph 异步结果收尾"循环（静态函数 @0x231910）
  对每个 pending 结果调用:
      CHIThreadManager::SynchronizeBody(threadMgr, hJob, 2, -1)   // -1 = INT_MAX = 无限等
  → 收尾线程永久挂死 → 退回相机重建会话时帧流起不来 → app 等帧超时关闭设备 → "卡住"。
  日志签名: chithreadmanager.cpp:926 SynchronizeBody() <job>: Flush/Sync TimedWait 2147483647/2147483647 ms

修复:
  0x2319e0: movn w3, #0 (mov w3, #-1)  →  movz w3, #0x1388 (mov w3, #5000)
  收尾等待封顶 5 秒。该调用点 bl 之后返回值不被检查（直接继续清理），
  超时路径与成功路径完全一致——只是不再永远等。
  其余 17 个 SynchronizeBody 调用点（500/2000/3500ms 合法等待）不受影响。

用法:
  python tools/patch_x8u_chi_sync_timeout.py <stock_chi_override.so> [-o 输出路径] [--timeout 毫秒]
"""
import argparse
import hashlib
import sys
from pathlib import Path

# PT_LOAD 可执行段 identity 映射（vaddr == file offset），已实测验证
PATCH_OFF = 0x2319E0
ORIG = bytes.fromhex('03008012')   # movn w3, #0  → mov w3, #-1


def movz_w3_imm16(imm: int) -> bytes:
    assert 0 <= imm <= 0xFFFF, "timeout 必须能被单条 movz 编码 (0..65535)"
    word = 0x52800000 | (imm << 5) | 3
    return word.to_bytes(4, 'little')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('stock', help='stock com.qti.chi.override.so 路径')
    ap.add_argument('-o', '--out', default='chi_override_syncfix.so')
    ap.add_argument('--timeout', type=int, default=5000,
                    help='收尾等待封顶毫秒数 (默认 5000，需 > MCR flush 的 3500)')
    args = ap.parse_args()

    data = bytearray(Path(args.stock).read_bytes())
    if len(data) < PATCH_OFF + 4:
        sys.exit('文件过小，不是有效的 com.qti.chi.override.so')

    cur = bytes(data[PATCH_OFF:PATCH_OFF + 4])
    if cur == movz_w3_imm16(args.timeout):
        print(f'[{args.timeout}] 已是补丁版，无需修改')
    elif cur != ORIG:
        sys.exit(f'0x{PATCH_OFF:x} 处字节与预期不符: {cur.hex()} (期望 {ORIG.hex()})，'
                 'BuildId 可能不同，请人工核对')
    else:
        data[PATCH_OFF:PATCH_OFF + 4] = movz_w3_imm16(args.timeout)
        Path(args.out).write_bytes(bytes(data))

    out = Path(args.out)
    if out.exists():
        print(f'输出: {out}  md5={hashlib.md5(out.read_bytes()).hexdigest()}')
        print(f'补丁点 0x{PATCH_OFF:x}: {ORIG.hex()} (mov w3, #-1) '
              f'-> {movz_w3_imm16(args.timeout).hex()} (mov w3, #{args.timeout})')


if __name__ == '__main__':
    main()
