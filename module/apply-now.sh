#!/system/bin/sh
# apply-now.sh -- 立即应用 PKJ110 原生补丁，不需要重启手机。
#
# post-fs-data.sh 只在开机时执行；本机用临时 root，硬重启会掉 root，
# 所以模块更新后实际上从来不会自动生效。本脚本把同样的三个 bind
# 立刻装上，逻辑与 post-fs-data.sh 的 "Native patches" 块保持一致。
#
# 用法（root）：
#   sh /data/adb/modules/ricoh_pop_x9u/apply-now.sh
#   sh .../apply-now.sh --with-isz         # 额外启用视频 ISZ（默认关闭，见下）
#
# 关于 ISZ：新版库只允许 m_modeDescriptor=30 的专业视频，并且原生判断把倍率
# 限制在 2X-3X；普通视频（包括 descriptor=4 的超级防抖）在切换 sensor 裁剪前
# 被拒绝。原生上限防止从 2X/3X 切到 6X 时残留 ISZ 状态，6X 保留原厂长焦链路。
# 专业视频自身开防抖时仍可能发生 FOV 缩回；想稳定看到 ISZ 请先关防抖。
# 确有需要再加 --with-isz，风险自负。
#
# ISZ = 双 bind（2026-10-31 r29）：只挂 lib 时 config 侧 opmode 白名单没有
# 0x830B 行、也没有 isSupport60FpsIzoom，ISZ 无法真正切 sensor 裁剪。10-26/10-29
# 的成功案例（FillScalerRawCropRegion SensorMode:13）全部是 lib+config 双挂。
#
# 装入后必须重启相机 provider 才会加载新库：
#   am force-stop com.oplus.camera; kill $(pidof vendor.qti.camera.provider-service_64)

MODDIR=${0%/*}
WITH_ISZ=0
[ "$1" = "--with-isz" ] && WITH_ISZ=1

# --- payload 选择：按 stock 库指纹，认不出就一个都不挂 -----------------------
# 17.0.1.201 的相机栈重新链接过（libextensionlayer 1723576 -> 1756488 字节，
# IsIZoomEnableVideo 0xa61c8 -> 0xaa838）。把 17.0.0.x 的 payload 挂到新 HAL 上，
# ChiHwConfigWrapper::Create 会把未映射的指针交给 CFI，provider 0 秒即崩
# （SIGSEGV #00 __cfi_slowpath #01 ChiHwConfigWrapper::Create）。所以这里绝不按
# 「应该是哪个版本」猜，只按文件指纹选；指纹不认识就什么都不挂。
PKJ_STOCK_17_0_0=6af236b1dbfae3629c86e8f956d33f77
PKJ_STOCK_17_0_1=36d10228409c766502b8e67566c6be49
PKJ_PAYLOAD_17_0_0=6c3d0eb13f2e2dd0efa6e608b5d70b43
PKJ_PAYLOAD_17_0_1=47db6163f275a0fe2430315d447a6390
PKJ_CUR=$(md5sum /odm/lib64/libextensionlayer.so 2>/dev/null | cut -d' ' -f1)
if [ -f "$MODDIR/force_pkj_payload" ]; then
    PKJ_GEN=$(cat "$MODDIR/force_pkj_payload" 2>/dev/null | tr -d ' \r\n')
else
    case "$PKJ_CUR" in
        "$PKJ_STOCK_17_0_0") PKJ_GEN=17.0.0 ;;
        "$PKJ_STOCK_17_0_1") PKJ_GEN=17.0.1.201 ;;
        "$PKJ_PAYLOAD_17_0_0"|"$PKJ_PAYLOAD_17_0_1")
            case "$(getprop ro.build.display.id)" in
                *17.0.1.2*|*17.0.2*|*17.1*) PKJ_GEN=17.0.1.201 ;;
                *) PKJ_GEN=17.0.0 ;;
            esac ;;
        *) PKJ_GEN=unknown ;;
    esac
fi
case "$PKJ_GEN" in
    17.0.0) PAYLOAD="$MODDIR/payload_pkj110" ;;
    17.0.1.201) PAYLOAD="$MODDIR/payload_pkj110_201" ;;
    *) PAYLOAD="" ;;
esac
echo "stock camera lib md5=${PKJ_CUR:-none} -> payload=${PAYLOAD:-none} ($PKJ_GEN)"

EIS_VCFG="$PAYLOAD/etc/oplus_eis_camera.vcfg"
ISZ_SO="$PAYLOAD/lib64/libextensionlayer.so"
ISZ_CFG="$PAYLOAD/etc/camera/CameraHWConfiguration.config"
CAF_SO="$PAYLOAD/lib64/com.qti.stats.caflogicaldriver.so"

T_EIS=/odm/etc/camera/oplus_eis_camera.vcfg
T_ISZ=/odm/lib64/libextensionlayer.so
T_ISZ_CFG=/odm/etc/camera/CameraHWConfiguration.config
T_CAF=/vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so

bind_file() {
    SRC="$1"; TGT="$2"; CTX="$3"
    if [ ! -f "$SRC" ]; then echo "skip $TGT: payload missing"; return 1; fi
    if [ ! -f "$TGT" ]; then echo "skip $TGT: target missing"; return 1; fi
    while grep -q " $TGT " /proc/self/mounts 2>/dev/null; do
        umount "$TGT" 2>/dev/null || umount -l "$TGT" 2>/dev/null || break
    done
    chown root:root "$SRC"
    chmod 0644 "$SRC"
    chcon "u:object_r:$CTX:s0" "$SRC" 2>/dev/null
    if mount --bind "$SRC" "$TGT"; then
        echo "ok   $TGT  ($(md5sum "$TGT" | cut -d' ' -f1))"
    else
        echo "FAIL $TGT"
    fi
}

echo "== eis config (6x distortion fix) =="
bind_file "$EIS_VCFG" "$T_EIS" vendor_configs_file

echo "== caf ubsan fix =="
bind_file "$CAF_SO" "$T_CAF" vendor_file

unbind_file() {
    TGT="$1"
    while grep -q " $TGT " /proc/self/mounts 2>/dev/null; do
        umount "$TGT" 2>/dev/null || umount -l "$TGT" 2>/dev/null || break
    done
}

if [ "$WITH_ISZ" = "1" ]; then
    if [ -z "$PAYLOAD" ] || [ ! -f "$ISZ_SO" ]; then
        echo "== video isz REFUSED: 没有匹配当前相机栈的 payload（stock md5=$PKJ_CUR）=="
        echo "   这不是缺少授权，而是这个系统版本的相机库还没有适配；强行挂旧库会崩 provider。"
        unbind_file "$T_ISZ"
        unbind_file "$T_ISZ_CFG"
    else
        echo "== video isz (opt-in): lib =="
        bind_file "$ISZ_SO" "$T_ISZ" vendor_file
        # config 只对它自己那一代有效；17.0.1.201 上挂旧 config 正是 provider
        # CFI 崩溃的来源，因此新基线只挂 lib。
        if [ ! -f "$ISZ_CFG" ]; then
            echo "    config payload 不存在，跳过（$PKJ_GEN 只需 lib）"
            unbind_file "$T_ISZ_CFG"
        else
            bind_file "$ISZ_CFG" "$T_ISZ_CFG" vendor_configs_file
        fi
    fi
else
    # 可能有多层叠加挂载，逐层卸干净（umount 被占用时退化为惰性卸载）
    unbind_file "$T_ISZ"
    unbind_file "$T_ISZ_CFG"
    echo "== isz skipped (off by default) =="
fi

echo
echo "done. restart the camera provider to load the new libraries:"
echo "  am force-stop com.oplus.camera"
echo "  kill \$(pidof vendor.qti.camera.provider-service_64)"
