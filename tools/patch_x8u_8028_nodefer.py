#!/usr/bin/env python3
"""给 [NoDeferCameraId] 补一行 CameraId[0x8028]。

背景
----
X8U 的 `CameraHWConfiguration.config` 里 [NoDeferCameraId] 只列了 7 个 operation mode
(0x8001/0x830b/0x8021/0x8019/0x8010/0x8011/0x830c)，**没有 0x8028**（视频 + 超级防抖 30FPS）。
该节语义是「**哪些 CameraId 不延时初始化**」（NoDefer = 不延时），值为位掩码；
`deferSlaveSensorInit = TRUE` 是节级默认（延时），表里的行是例外清单。
证据：`CameraId[0x8011] = 0x5` 且注释写 `main + ultra wide`，而 0x5 = 0x1|0x4，
说明是位掩码而非单个主相机 id。

列的语义
--------
    CameraId[mode] = defaultId; 0.6~1; 1~3.0; 3.0~6.0; 6.0~20.1; 20.1~120.1
    0x1=main 0x4=ultra wide 0x8=tele 0x10=ultra tele，可作位掩码组合

实测机制（repro4，2026-10-28 15:25）
------------------------------------
    15:25:18.916  CONFIG End 成功
    15:25:18.946  StreamOn 开始等 config done
    15:25:18.969  app 推入 zoom 事件 r=2.3928   ← 恢复上次倍率
    15:25:18.971  MCTFE 报 1047 (IFEEPRFail)    ← +2ms
    15:25:21.846  StreamOn timed out(2900)
即：StreamOn 尚未完成时倍率已被改到 2.39X，MCTFE 需要现场切 SAT 对端，而 uw 从摄
因「延时初始化」还没就绪 → IFE 配置失败 → config done 永不到 → 超时 → 重试 → 自杀。

本补丁（v2）取 0x0; 0x4; 0x5; 0x8; 0x10; 0x10，与 0x8001 仅差 1~3X 段：
- 1~3X 由 0x1(main 单摄不延时) 改为 **0x5(main + ultra wide 均不延时)** ——
  让 uw 从摄在 CONFIG 阶段就就绪，首帧 zoom 到 2.x 时 MCTFE 有对端可用。
  v1 曾误填 0x1，只让主摄不延时、从摄仍延时，所以无效（已实测确认）。
- 0.6~1 / 3~6 / 6~20.1 / 20.1+ 四段与 0x8001 完全一致，确保已修好的 6X 不受影响。
"""
import os
import sys

STOCK = ".workbuddy/tmp/camhw/CameraHWConfiguration.stock.config"
OUT = ".workbuddy/tmp/camhw/CameraHWConfiguration.8028.config"

ANCHOR = b"    CameraId[0x8021]                    =     0x0;          0x4;      0x1;      0x8;      0x10;      0x10     #60FPS VIDEO"
NEW = b"    CameraId[0x8028]                    =     0x0;          0x4;      0x5;      0x8;      0x10;      0x10     #superEIS VIDEO (1-3X main+uw no-defer)"


def main():
    if not os.path.exists(STOCK):
        sys.exit("missing stock config: %s" % STOCK)
    data = open(STOCK, "rb").read()

    if b"CameraId[0x8028]" in data:
        sys.exit("already patched, refuse to run twice")

    assert data.count(ANCHOR) == 1, "anchor line not unique: %d" % data.count(ANCHOR)

    out = data.replace(ANCHOR, ANCHOR + b"\n" + NEW)
    open(OUT, "wb").write(out)

    # 校验：只多一行，其余逐字节一致
    a = data.split(b"\n")
    b = out.split(b"\n")
    assert len(b) == len(a) + 1, (len(a), len(b))
    removed = [i for i in range(len(a)) if a[i] != b[i]]
    print("stock bytes :", len(data))
    print("out   bytes :", len(out), "(+%d)" % (len(out) - len(data)))
    print("lines       :", len(a), "->", len(b))
    print("first diff line index:", removed[:1], "of", len(a))
    tail_ok = a[removed[0]:] == b[removed[0] + 1:] if removed else False
    print("tail identical after insertion:", tail_ok)
    assert tail_ok, "content after insertion point diverged"
    print("OK ->", OUT)


if __name__ == "__main__":
    main()
