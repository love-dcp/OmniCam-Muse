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
# 关于 ISZ：libextensionlayer 的 ISZ 补丁默认不装。实测（2026-10-28 17:40）
# 普通视频 + 超级防抖的 m_modeDescriptor 也是 4，正好落在白名单里，ISZ 一开
# sensor 就切裁剪模式（Physical 13/23/9），与 EIS 的全幅标定不匹配，MCTFE
# 报错后 chi 看门狗会杀掉 provider（表现为切 3X/6X 无反应，约 50 秒后闪退）。
# 另注（2026-10-31 r28 实测）：专业视频开防抖时 2X 画面会退回 1X 全幅
# （EIS 触发 FOV 缩回）——想看到 ISZ 生效请关防抖；防抖位本身不在 ISZ 的
# 门控里（lib 白名单 desc&~2 掩掉防抖位，config iZoomSettins 按 opmode 匹配）。
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

EIS_VCFG="$MODDIR/payload_pkj110/etc/oplus_eis_camera.vcfg"
ISZ_SO="$MODDIR/payload_pkj110/lib64/libextensionlayer.so"
ISZ_CFG="$MODDIR/payload_pkj110/etc/camera/CameraHWConfiguration.config"
CAF_SO="$MODDIR/payload_pkj110/lib64/com.qti.stats.caflogicaldriver.so"

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
    echo "== video isz (opt-in): lib + config =="
    bind_file "$ISZ_SO" "$T_ISZ" vendor_file
    bind_file "$ISZ_CFG" "$T_ISZ_CFG" vendor_configs_file
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
