#!/system/bin/sh
# OmniCam Muse uninstaller - flash-time entry point.
#
# KernelSU runs this while the module zip is being installed. The phone is fully booted and
# user storage is unlocked here, so this is the only phase that can uninstall the settings
# app and rewrite the camera/gallery data directories. post-fs-data.sh and service.sh finish
# anything that needs a clean boot (stale mounts, merged bind sources, properties) and then
# remove this module itself.
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR= ;;
esac
[ -n "$MODPATH" ] && MODDIR=$MODPATH
[ -n "$MODDIR" ] || exit 0
if [ ! -f "$MODDIR/cleanup.sh" ]; then
    echo "cleanup.sh missing from module"
    exit 0
fi
. "$MODDIR/cleanup.sh"

# Ask KernelSU to hot-install this module: after the install script returns, ksud swaps the
# staged directory in and runs hot-clean.sh, which finishes the cleanup and removes this
# module. No reboot is needed on KernelSU versions that support hot install.
HOT=false
if [ "$KSU_HAS_HOT_INSTALL" = "true" ]; then
    MODULE_HOT_INSTALL_REQUEST=true
    MODULE_HOT_RUN_SCRIPT=hot-clean.sh
    HOT=true
fi

say "***********************************************"
say " OmniCam Muse 卸载器"
say " 将清除：KSU 模块、LSPosed 设置应用、相机与相册内的"
say " 模块文件/偏好、/data/adb 残留、调试属性与转储。"
say " 不会删除照片、视频，也不会卸载相机和相册本身。"
if [ "$HOT" = "true" ]; then
    say " 管理器支持热安装：刷入后会立即清理并自删，无需重启。"
else
    say " 当前管理器不支持热安装：刷入后执行模块 Action 或软重启。"
    say " X8U 不要硬重启（会掉临时 root）。"
fi
say "***********************************************"

cleanup_flash

say "***********************************************"
if [ "$HOT" = "true" ]; then
    say " 刷入阶段完成，正在执行热卸载收尾。"
else
    say " 刷入阶段完成。请执行 Action 或软重启完成收尾。"
fi
say "***********************************************"
