#!/system/bin/sh
# KernelSU 里的「操作」按钮：随时手动再修一次，结果显示在执行输出里。
# 振动模块报「挂载失败」时，点这个就够了，不用重启。

MODDIR=${0%/*}
sh "$MODDIR/apply.sh"
