#!/usr/bin/env bash
# 本地入口：adp push 自检脚本 -> 设备执行 -> 输出 + 拉 selfheal.log
# 用法：bash tools/omnicam_verify.sh
set -u
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/work"

adb wait-for-device >/dev/null 2>&1 || { echo "设备未连接"; exit 2; }

# adb server 刚启动时第一次 push 可能竞态失败，重试 3 次
# 注意：Git Bash 的 $ROOT 形如 /g/OmniCam-Muse-main，adb.exe 不认，必须用相对路径
cd "$ROOT" || exit 2
PUSHED=0
for i in 1 2 3; do
  if adb push tools/omnicam_verify_device.sh /data/local/tmp/omnicam_verify.sh >/dev/null 2>&1; then
    PUSHED=1; break
  fi
  sleep 1
done
[ "$PUSHED" = "1" ] || { echo "push 失败（重试 3 次）"; adb push tools/omnicam_verify_device.sh /data/local/tmp/omnicam_verify.sh; exit 2; }

adb shell "su -c 'sh /data/local/tmp/omnicam_verify.sh'" 2>/dev/null | tee "$ROOT/work/verify-install.txt"

LOG=/data/adb/modules/ricoh_pop_x9u/selfheal.log
echo
echo "=== selfheal.log 末尾 30 行 ==="
adb shell "su -c 'tail -n 30 $LOG'" 2>/dev/null | tee -a "$ROOT/work/verify-install.txt"

echo
echo "=== finish 脚本日志（不重启直装时才有）==="
adb shell "su -c 'tail -n 15 /data/local/tmp/omnicam_finish.log'" 2>/dev/null | tee -a "$ROOT/work/verify-install.txt"

echo
echo "已保存：work/verify-install.txt"
