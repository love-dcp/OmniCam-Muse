#!/system/bin/sh
# X8U professional-video ISZ laboratory helper.
#
# This script only touches these three files:
#   /odm/lib64/libextensionlayer.so
#   /odm/etc/camera/CameraHWConfiguration.config
#   /odm/etc/camera/oplus_eis_camera.vcfg
#
# It is pushed and called by tools/x8u_isz_lab.ps1. The module's normal
# apply-now.sh is deliberately not used for experiments because older mounts
# from previous runs can otherwise hide which candidate is actually active.

set -u

ACTION=${1:-status}
STAGE=${2:-/data/local/tmp/omnicam_isz_6c3d0eb1_9aa9cf7c_stockvcfg}
EXPECT_SO=${3:-6c3d0eb13f2e2dd0efa6e608b5d70b43}
EXPECT_CFG=${4:-9aa9cf7c444e8ed418d2fe41561688ee}
EXPECT_VCFG=${5:-none}
SRC_SO=$STAGE/libextensionlayer.so
SRC_CFG=$STAGE/CameraHWConfiguration.config
SRC_VCFG=$STAGE/oplus_eis_camera.vcfg
TARGET_SO=/odm/lib64/libextensionlayer.so
TARGET_CFG=/odm/etc/camera/CameraHWConfiguration.config
TARGET_VCFG=/odm/etc/camera/oplus_eis_camera.vcfg

STOCK_SO=6af236b1dbfae3629c86e8f956d33f77
STOCK_CFG=470820672de033b9d8f8a93acb5aadc1
STOCK_VCFG=48ab334b5a84d3149e3a956f575c6e49

md5_of() {
    md5sum "$1" 2>/dev/null | awk '{print $1}'
}

is_mounted() {
    awk -v path="$1" '$2 == path { found = 1 } END { exit !found }' \
        /proc/self/mounts 2>/dev/null
}

unmount_target() {
    target=$1
    while is_mounted "$target"; do
        umount "$target" 2>/dev/null ||
            umount -l "$target" 2>/dev/null ||
            break
    done
}

unmount_once() {
    target=$1
    umount "$target" 2>/dev/null ||
        umount -l "$target" 2>/dev/null ||
        return 1
}

stop_camera() {
    am force-stop com.oplus.camera >/dev/null 2>&1 || true
    pids=$(pidof vendor.qti.camera.provider-service_64 2>/dev/null || true)
    if [ -n "$pids" ]; then
        kill $pids 2>/dev/null || true
    fi
    sleep 1
}

bind_one() {
    source=$1
    target=$2
    context=$3

    if [ ! -f "$source" ]; then
        echo "FAIL missing source: $source"
        return 1
    fi
    unmount_target "$target"
    chown root:root "$source" || return 1
    chmod 0644 "$source" || return 1
    chcon "u:object_r:$context:s0" "$source" 2>/dev/null || true
    if mount --bind "$source" "$target"; then
        echo "ok   $target <- $source"
    else
        echo "FAIL mount $target"
        return 1
    fi
}

bind_overlay() {
    source=$1
    target=$2
    context=$3

    if [ ! -f "$source" ]; then
        echo "FAIL missing source: $source"
        return 1
    fi
    chown root:root "$source" || return 1
    chmod 0644 "$source" || return 1
    chcon "u:object_r:$context:s0" "$source" 2>/dev/null || true
    if mount --bind "$source" "$target"; then
        echo "ok   $target <- $source (overlay)"
    else
        echo "FAIL mount overlay $target"
        return 1
    fi
}

show_target() {
    target=$1
    echo "$target"
    echo "  md5:   $(md5_of "$target")"
    mounted=$(is_mounted "$target" && echo yes || echo no)
    echo "  mount: $mounted"
    grep -F "$target" /proc/self/mountinfo 2>/dev/null || true
}

verify_candidate() {
    got_so=$(md5_of "$SRC_SO")
    got_cfg=$(md5_of "$SRC_CFG")
    if [ "$got_so" != "$EXPECT_SO" ]; then
        echo "FAIL staged library md5: ${got_so:-missing} (want $EXPECT_SO)"
        return 1
    fi
    if [ "$got_cfg" != "$EXPECT_CFG" ]; then
        echo "FAIL staged config md5: ${got_cfg:-missing} (want $EXPECT_CFG)"
        return 1
    fi
    if [ "$EXPECT_VCFG" != "none" ]; then
        got_vcfg=$(md5_of "$SRC_VCFG")
        if [ "$got_vcfg" != "$EXPECT_VCFG" ]; then
            echo "FAIL staged EIS config md5: ${got_vcfg:-missing} (want $EXPECT_VCFG)"
            return 1
        fi
    fi
    echo "ok   staged candidate hashes"
}

verify_stock() {
    got_so=$(md5_of "$TARGET_SO")
    got_cfg=$(md5_of "$TARGET_CFG")
    got_vcfg=$(md5_of "$TARGET_VCFG")
    failed=0
    if [ "$got_so" != "$STOCK_SO" ]; then
        echo "FAIL live library md5: $got_so (want stock $STOCK_SO)"
        failed=1
    fi
    if [ "$got_cfg" != "$STOCK_CFG" ]; then
        echo "FAIL live config md5: $got_cfg (want stock $STOCK_CFG)"
        failed=1
    fi
    if [ "$got_vcfg" != "$STOCK_VCFG" ]; then
        echo "FAIL live EIS config md5: $got_vcfg (want stock $STOCK_VCFG)"
        failed=1
    fi
    if is_mounted "$TARGET_SO" || is_mounted "$TARGET_CFG"; then
        echo "FAIL an ISZ library/config target is still mounted"
        failed=1
    fi
    [ "$failed" -eq 0 ] && echo "ok   stock files restored and no ISZ mount remains"
    return "$failed"
}

restore_vcfg_baseline() {
    got_vcfg=$(md5_of "$TARGET_VCFG")
    if [ "$got_vcfg" = "$STOCK_VCFG" ]; then
        return 0
    fi
    if ! unmount_once "$TARGET_VCFG"; then
        echo "FAIL could not remove the experimental EIS config layer"
        return 1
    fi
    got_vcfg=$(md5_of "$TARGET_VCFG")
    if [ "$got_vcfg" != "$STOCK_VCFG" ]; then
        echo "FAIL EIS config baseline md5: ${got_vcfg:-missing} (want $STOCK_VCFG)"
        return 1
    fi
    echo "ok   experimental EIS config layer removed"
}

status() {
    echo "== X8U ISZ status =="
    echo "device: $(getprop ro.product.model) / $(getprop ro.build.display.id)"
    echo
    show_target "$TARGET_SO"
    show_target "$TARGET_CFG"
    show_target "$TARGET_VCFG"
    echo
    echo "camera processes:"
    pidof com.oplus.camera 2>/dev/null || true
    pidof vendor.qti.camera.provider-service_64 2>/dev/null || true
}

apply_candidate() {
    echo "== verify and apply the desc=30 / 2X-3X candidate =="
    verify_candidate || return 1
    stop_camera
    unmount_target "$TARGET_SO"
    unmount_target "$TARGET_CFG"
    restore_vcfg_baseline || return 1
    bind_one "$SRC_SO" "$TARGET_SO" vendor_file || return 1
    bind_one "$SRC_CFG" "$TARGET_CFG" vendor_configs_file || return 1
    if [ "$EXPECT_VCFG" != "none" ] &&
       [ "$(md5_of "$TARGET_VCFG")" != "$EXPECT_VCFG" ]; then
        bind_overlay "$SRC_VCFG" "$TARGET_VCFG" vendor_configs_file || return 1
    fi
    echo
    show_target "$TARGET_SO"
    show_target "$TARGET_CFG"
    show_target "$TARGET_VCFG"
    if [ "$(md5_of "$TARGET_SO")" != "$EXPECT_SO" ] ||
       [ "$(md5_of "$TARGET_CFG")" != "$EXPECT_CFG" ]; then
        echo "FAIL live target hashes do not match the candidate"
        return 1
    fi
    if [ "$EXPECT_VCFG" != "none" ] &&
       [ "$(md5_of "$TARGET_VCFG")" != "$EXPECT_VCFG" ]; then
        echo "FAIL live EIS config does not match the candidate"
        return 1
    fi
    if ! is_mounted "$TARGET_SO" || ! is_mounted "$TARGET_CFG"; then
        echo "FAIL one or both candidate mounts are absent"
        return 1
    fi
    if [ "$EXPECT_VCFG" != "none" ] && ! is_mounted "$TARGET_VCFG"; then
        echo "FAIL candidate EIS config mount is absent"
        return 1
    fi
    echo "ok   candidate is live; open the camera to start a fresh provider session"
}

restore_stock() {
    echo "== stop camera and restore stock files =="
    stop_camera
    unmount_target "$TARGET_SO"
    unmount_target "$TARGET_CFG"
    restore_vcfg_baseline || return 1
    show_target "$TARGET_SO"
    show_target "$TARGET_CFG"
    show_target "$TARGET_VCFG"
    verify_stock
}

case "$ACTION" in
    status)
        status
        ;;
    apply)
        apply_candidate
        ;;
    restore)
        restore_stock
        ;;
    *)
        echo "usage: sh $0 status|apply|restore" >&2
        exit 2
        ;;
esac
