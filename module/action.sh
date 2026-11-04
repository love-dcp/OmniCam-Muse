#!/system/bin/sh
# OmniCam Muse - KernelSU module action (the "Action/执行" button in the manager).
#
# One button, two meanings, told apart by how recently it was last pressed:
#
#   1st press  -> REPAIR. Runs selfheal.sh, which rebuilds everything a flash would need a reboot
#                 for: staged camera/gallery data, the nine feature keys, the /odm binds, the
#                 merged LUT trees, and the mode table (so 理光 GR / 移轴 / POP come back).
#                 This is the button to press when something looks "not installed": modes missing
#                 from the bar, POP/GR filters gone, master colour dead.
#
#   2nd press within 3 minutes -> FULL UNINSTALL. The same cleanup uninstall.sh runs on a genuine
#                 removal (binds, merged trees, staged data, prefs, persist.omnicam.* props, the
#                 settings app and its LSPosed data), then marks the module for removal.
#
# The window is time-based rather than a confirm file on purpose: a confirm file left on disk made
# the button fire the destructive path on the next unrelated tap, days later.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || { echo "no MODDIR"; exit 1; }

STAMP=$MODDIR/action_last_press
NOW=$(date +%s)
LAST=0
[ -f "$STAMP" ] && LAST=$(cat "$STAMP" 2>/dev/null)
case "$LAST" in ''|*[!0-9]*) LAST=0 ;; esac

if [ "$LAST" -gt 0 ] && [ $((NOW - LAST)) -lt 180 ]; then
    # --- second press: full uninstall ---------------------------------------
    rm -f "$STAMP"
    [ -f "$MODDIR/uninstall_full.sh" ] || { echo "uninstall_full.sh missing"; exit 1; }
    . "$MODDIR/uninstall_full.sh"
    cleanup_module_full
    # KernelSU drops the module at the next boot (uninstall.sh then runs again in full mode,
    # which is idempotent, so nothing is left behind either way).
    safe_touch_empty "$MODDIR/remove"
    say "OmniCam Muse 已完全清理；软重启后模块自动移除。"
    exit 0
fi

# --- first press: repair ----------------------------------------------------
echo "$NOW" > "$STAMP"
echo "== OmniCam Muse 一键修复 =="
echo "重建暂存数据、/odm 挂载和模式表（理光 GR / 移轴 / POP）。"
echo
if [ -f "$MODDIR/selfheal.sh" ]; then
    sh "$MODDIR/selfheal.sh"
    echo
    echo "完成。详情：$MODDIR/selfheal.log"
    if grep -q "MISSING" "$MODDIR/selfheal.log" 2>/dev/null; then
        echo
        echo "警告：有挂载没建立成功，日志里标了 MISSING："
        grep "MISSING" "$MODDIR/selfheal.log"
    fi
else
    echo "selfheal.sh 缺失，模块包不完整，请重新刷入。"
fi
echo
echo "3 分钟内再点一次 Action 将完全卸载（会删除设置应用）。"
exit 0
