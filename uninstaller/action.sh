#!/system/bin/sh
# OmniCam Muse uninstaller - KernelSU module action.
#
# Use this when hot install was not available (or the manager asked for a reboot): run
#   su -c 'ksud module action omnicam-muse-uninstaller'
# or tap the module's action button. It repeats the flash cleanup, runs the late cleanup and
# then removes this module, so no reboot is required. A soft reboot is still the only way to
# unload hooks already loaded into system_server.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || exit 0
[ -f "$MODDIR/cleanup.sh" ] || exit 0
. "$MODDIR/cleanup.sh"

cleanup_flash
cleanup_late

if [ "$APP_STILL_INSTALLED" = "1" ]; then
    echo "OmniCam Muse 设置应用仍未卸载；再次执行 Action 或软重启后重试"
    safe_touch_empty "$MODDIR/retry"
    exit 0
fi

say "热卸载完成，卸载器将在数秒后自动移除"
remove_self_later
