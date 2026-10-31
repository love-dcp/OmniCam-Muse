#!/system/bin/sh
# OmniCam Muse uninstaller - hot-install entry point.
#
# KernelSU runs this after the module has been moved into /data/adb/modules when the
# installer requested a hot install (MODULE_HOT_INSTALL_REQUEST=true). The phone is fully
# booted and user storage is unlocked, so the late cleanup can run right here and the
# uninstaller can remove itself without a reboot. If the kernel/manager does not support
# hot install, customize.sh's normal install path plus post-fs-data.sh/service.sh cover the
# same work on the next soft reboot.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || exit 0
[ -f "$MODDIR/cleanup.sh" ] || exit 0
. "$MODDIR/cleanup.sh"

cleanup_late

if [ "$APP_STILL_INSTALLED" = "1" ]; then
    echo "OmniCam Muse 设置应用仍未卸载；卸载器保留，可执行模块 Action 或下次软重启重试"
    safe_touch_empty "$MODDIR/retry"
    exit 0
fi

say "热卸载完成，卸载器将在数秒后自动移除"
remove_self_later
