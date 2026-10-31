#!/system/bin/sh
# OmniCam Muse uninstaller - early-boot cleanup.
#
# Runs before zygote. User storage is not guaranteed to be unlocked yet, so this phase only
# touches mounts, /data/adb, properties and scratch files. The settings app and the camera/
# gallery data were already handled by customize.sh; service.sh runs once more after boot.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || exit 0
[ -f "$MODDIR/cleanup.sh" ] || exit 0
. "$MODDIR/cleanup.sh"

cleanup_boot
