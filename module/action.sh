#!/system/bin/sh
# OmniCam Muse - KernelSU module action (the "Action/执行" button in the manager).
#
# Explicit FULL uninstall: the same cleanup uninstall.sh runs on a genuine removal
# (binds, merged trees, staged camera/gallery data, prefs, persist.omnicam.* props,
# the settings app and its LSPosed data), then marks this module for removal at the
# next boot. Use it when the module is being retired and nothing should be left
# behind, or as the escape hatch if uninstall.sh ever misfires into its conservative
# update branch.
#
# Two-press confirmation: the first press only arms (the button is easy to hit by
# accident and the cleanup removes the settings app); the second press fires.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || { echo "no MODDIR"; exit 1; }
[ -f "$MODDIR/uninstall_full.sh" ] || { echo "uninstall_full.sh missing"; exit 1; }
. "$MODDIR/uninstall_full.sh"

if [ ! -f "$MODDIR/confirm_full_uninstall" ]; then
    : > "$MODDIR/confirm_full_uninstall"
    say "第一步完成：已确认准备完全卸载。"
    say "再点一次 Action 立即执行全量清理（含设置应用 local.jiege.camera）。"
    say "如需取消：删除模块目录里的 confirm_full_uninstall 文件即可。"
    exit 0
fi
rm -f "$MODDIR/confirm_full_uninstall"

cleanup_module_full

# KernelSU drops the module at the next boot (uninstall.sh then runs again in full
# mode, which is idempotent, so nothing is left behind either way).
safe_touch_empty "$MODDIR/remove"
say "OmniCam Muse 已完全清理；软重启后模块自动移除。"
