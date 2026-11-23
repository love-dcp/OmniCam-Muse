#!/usr/bin/env bash
# r500 / ColorOS 17.0.1.201 装机后恢复：让 Zygisk Next + LSPosed 真正注入，再重放 native 挂载。
#
# 为什么需要这一步：临时 root 是在 zygote 已经启动之后才拿到的，Zygisk Next 没有机会
# 注入 zygote，于是 /data/adb/lspd 不会启动、相机进程 maps 里也没有 lsposed —— 模块的
# Java hook（调色盘、GR、XPAN、大师）全部静默失效，只有手动 bind 的 native 库有效。
#
# 用法：
#   bash tools/after_reboot_201.sh              # 软重启 zygote + 重放挂载 + 重启相机
#   bash tools/after_reboot_201.sh --no-zygote  # 只重放挂载（zygote 已经处理过时用）
#   bash tools/after_reboot_201.sh --check      # 只体检，不动设备
#
# 只重启 zygote（Android framework），不碰内核，root 不会掉。
set -u

export MSYS_NO_PATHCONV=1
ADB=adb
DO_ZYGOTE=1
CHECK_ONLY=0

for a in "$@"; do
    case "$a" in
        --no-zygote) DO_ZYGOTE=0 ;;
        --check)     CHECK_ONLY=1; DO_ZYGOTE=0 ;;
        *) echo "unknown option: $a" >&2; exit 2 ;;
    esac
done

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

$ADB wait-for-device
[ "$($ADB get-state 2>/dev/null | tr -d '\r')" = "device" ] || die "设备不是 device 状态"

if ! $ADB shell "su -c id" 2>/dev/null | grep -q "uid=0"; then
    die "没有 root。先跑 root 工具（KMroot / GhostLock）拿到 uid=0 再执行本脚本"
fi
say "root ok: $($ADB shell 'su -c id' | tr -d '\r')"

BUILD=$($ADB shell getprop ro.build.display.id | tr -d '\r')
KERN=$($ADB shell uname -r | tr -d '\r')
say "system: $BUILD"
say "kernel: $KERN"

check_inject() {
    local z pid
    z=$($ADB shell "su -c 'pidof zygote64'" 2>/dev/null | tr -d '\r')
    say "zygote64 pid=${z:-none}"
    if [ -n "$z" ]; then
        say "  zygote maps zygisk/lsposed: $($ADB shell "su -c 'grep -icE \"zygisk|lsposed\" /proc/$z/maps'" 2>/dev/null | tr -d '\r')"
    fi
    say "  lspd pid=$($ADB shell "su -c 'pidof lspd'" 2>/dev/null | tr -d '\r')"
    pid=$($ADB shell "pidof com.oplus.camera" 2>/dev/null | tr -d '\r')
    if [ -n "$pid" ]; then
        say "  camera pid=$pid maps lsposed: $($ADB shell "su -c 'grep -ic lsposed /proc/$pid/maps'" 2>/dev/null | tr -d '\r')"
    else
        say "  camera not running"
    fi
}

say ""
say "== before =="
check_inject

if [ "$CHECK_ONLY" = "1" ]; then
    say ""
    say "check-only: 设备未被改动"
    exit 0
fi

if [ "$DO_ZYGOTE" = "1" ]; then
    say ""
    say "== restarting zygote (screen goes black for a while; root is kept) =="
    $ADB shell "su -c 'setprop ctl.restart zygote'" || die "setprop 失败"
    sleep 3

    # 等 package manager 回来：pm 可用就说明 framework 起来了
    ok=0
    for i in $(seq 1 60); do
        if $ADB shell "pm path android" 2>/dev/null | grep -q "package:"; then
            ok=1
            break
        fi
        sleep 2
    done
    [ "$ok" = "1" ] || die "framework 60 次轮询后仍未恢复；adb 还在，可手动执行 setprop ctl.start zygote"
    say "framework back"
    sleep 3
fi

say ""
say "== replaying native mounts (ISZ on) =="
$ADB shell "su -c 'sh /data/adb/modules/ricoh_pop_x9u/apply-now.sh --with-isz 2>&1 | tail -18'" | tail -20

say ""
say "== lib fingerprints =="
$ADB shell "su -c 'md5sum /odm/lib64/libextensionlayer.so /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so'" | tr -d '\r'
say "  expect libextensionlayer = 47db6163f275a0fe2430315d447a6390 (our ISZ build)"
say "  expect caflogicaldriver  = 5a8f76d3778edf380f610c629863d8e4 (our sat build)"

say ""
say "== restarting camera =="
$ADB shell "su -c 'am force-stop com.oplus.camera; kill \$(pidof vendor.qti.camera.provider-service_64) 2>/dev/null'" >/dev/null 2>&1
sleep 2
$ADB shell "monkey -p com.oplus.camera -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 6

say ""
say "== after =="
check_inject

say ""
say "外观判据："
say "  lspd pid 非空 + camera maps lsposed > 0  -> 模块已注入，可以进相机看调色盘/GR 是否回来"
say "  若 lspd 仍为空 -> 看 /data/adb/lspd/log/ 最新一条，或确认 Zygisk Next 在管理器里是启用状态"
