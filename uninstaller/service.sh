#!/system/bin/sh
# OmniCam Muse uninstaller - late-boot cleanup and self-removal.
#
# KernelSU starts service.sh after the system is up. This is the safety net for a failed
# flash-time pass (for example a package-manager error) and for files the camera recreated
# during boot. When it finishes, this module deletes its own directory so nothing is left in
# the KernelSU module list.
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
    # Keep the uninstaller one more boot so the package-manager uninstall can be retried.
    echo "OmniCam Muse 设置应用仍未卸载，保留卸载器以便下次启动重试"
    safe_touch_empty "$MODDIR/retry"
    exit 0
fi

# Also mark the directory for removal before deleting it: if rm fails halfway, KernelSU will
# still drop it on the next boot. The case guard keeps a malformed $MODDIR from ever reaching
# rm -rf.
case "$MODDIR" in
    /data/adb/modules/*)
        [ "$MODDIR" != "/data/adb/modules" ] || exit 0
        safe_touch_empty "$MODDIR/remove"
        safe_rm_rf "$MODDIR"
        ;;
esac
[ -n "$SELF_ID" ] && safe_rm_rf "/data/adb/modules_update/$SELF_ID"
[ -n "$SELF_ID" ] && safe_rm_rf "/data/adb/ksu/module_configs/$SELF_ID"
