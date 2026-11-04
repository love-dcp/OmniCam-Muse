#!/system/bin/sh
# OmniCam-Muse 安装自检（设备端执行，toybox sh 兼容）
# 用法：adb push 到 /data/local/tmp/omnicam_verify.sh 后 su -c 'sh ...'
# 目的：卸载重装 / 刷入之后，30 秒内判断"是不是一次到位"
#
# 基线（r147 健康态）：ok=20 fail=0 warn=0
#   bind 8 / merged 470 / pop_port 90 / jiege 355 / ricoh_gr 77 / keys 9

MOD=ricoh_pop_x9u
MDIR=/data/adb/modules/$MOD
CAM=/data/user/0/com.oplus.camera
GAL=/data/user/0/com.coloros.gallery3d
PASS=0; FAIL=0; WARN=0

ok()   { echo "  [ok]   $*"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $*"; FAIL=$((FAIL+1)); }
warn() { echo "  [warn] $*"; WARN=$((WARN+1)); }
info() { echo "  [info] $*"; }

echo "=== 1. 模块状态 ==="
if [ -f "$MDIR/module.prop" ]; then
  ok "$(grep -E '^version=' "$MDIR/module.prop" | head -1 | tr -d '\r') $(grep -E '^versionCode=' "$MDIR/module.prop" | head -1 | tr -d '\r')"
else
  bad "module.prop 不存在（模块没落地）"
fi
[ -d "/data/adb/modules_update/$MOD" ] && warn "modules_update 有残留副本（还没交换）" || ok "无 modules_update 残留"
[ -f "$MDIR/disable" ] && bad "模块被 Disable" || ok "模块未 Disable"
[ -f "$MDIR/update" ] && warn "update 标志还在（下次开机会被覆盖）" || ok "无 update 标志"
[ -f "$MDIR/remove" ] && bad "存在 remove 标志（重启即卸载）" || true

# 模块版本和设置 APK 的版本必须一致。r149 曾经把 r149 的模块配上 r147 的 APK：
# build.gradle.kts 里另有一份 versionName，改 module.prop 时漏改，装完设置页显示旧版本号。
# 现在 build.ps1 从 module.prop 传参并用 aapt2 回读校验，这里再加一道设备端守卫。
MV=$(sed -n 's/^version=//p' "$MDIR/module.prop" 2>/dev/null | head -1 | tr -d '\r')
AV=$(dumpsys package local.jiege.camera 2>/dev/null | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
if [ -z "$AV" ]; then
  bad "设置 APK 未安装"
elif [ "$MV" = "$AV" ]; then
  ok "APK 版本与模块一致：$AV"
else
  bad "APK 版本不一致：模块=$MV / APK=$AV"
fi

echo "=== 2. /odm bind（8 条逐项）==="
NB=0
for p in \
  /odm/etc/camera/meishe_lut \
  /odm/etc/camera/filters_lut \
  /odm/etc/camera/basictone \
  /odm/etc/camera/oplus_eis_camera.vcfg \
  /odm/etc/camera/config/oplus_camera_algo_switch_config \
  /odm/etc/camera/config/camera_unit_config \
  /odm/etc/camera/config/camera_unit_feature_config.protobuf \
  /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so ; do
  if grep -q " $p " /proc/mounts 2>/dev/null; then
    ok "bind $p"; NB=$((NB+1))
  else
    bad "缺 bind $p"
  fi
done
echo "  ---- 实际 bind 条数 $NB / 8"

echo "=== 3. merged 树 ==="
NM=$(find /data/adb/omnicam_muse/merged -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$NM" -ge 400 ]; then ok "merged 文件数 $NM"; else bad "merged 文件数 $NM（期望 ~470）"; fi

echo "=== 4. 相机私有 staging（$CAM/files）==="
for pair in jiege:350 pop_port:80 ricoh_gr:70 mode_limit_watermark:1; do
  d=${pair%:*}; thr=${pair#*:}
  n=$(find "$CAM/files/$d" -type f 2>/dev/null | wc -l | tr -d ' ')
  if [ "$n" -ge "$thr" ]; then ok "$d = $n"; else bad "$d = $n（期望 >=$thr）"; fi
done
# 相册侧：理光 GR 风格分组（不是相机的目录）
n=$(find "$GAL/files/ricoh_gr_styles" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" -ge 1 ]; then ok "gallery ricoh_gr_styles = $n"; else bad "gallery ricoh_gr_styles = $n（期望 >=1）"; fi
# LUT 走 bind 挂到 /odm，不复制进 cam/files
for d in meishe_lut filters_lut; do
  n=$(find "/odm/etc/camera/$d" -maxdepth 1 -type f 2>/dev/null | wc -l | tr -d ' ')
  if [ "$n" -ge 100 ]; then ok "/odm $d = $n"; else bad "/odm $d = $n（期望 >=100）"; fi
done

echo "=== 5. 相机 prefs feature key ==="
PF=$CAM/shared_prefs/override_config_data.xml
if [ -f "$PF" ]; then
  n=$(grep -o 'name="[^"]*"' "$PF" 2>/dev/null | sort -u | wc -l | tr -d ' ')
  if [ "$n" -ge 8 ]; then ok "override_config_data key 数 $n"; else warn "override_config_data key 数 $n（期望 9）"; fi
  for k in retro.camera.support tilt.shift.photo.support gr.mode.support; do
    grep -q "$k" "$PF" 2>/dev/null && ok "  含 $k" || bad "  缺 $k"
  done
else
  bad "override_config_data.xml 不存在（相机没起过？）"
fi

echo "=== 6. 模式库 mode_data.db ==="
DB=$CAM/databases/mode_data.db
if [ -f "$DB" ]; then
  ok "mode_data.db 存在（$(wc -c < "$DB" | tr -d ' ') 字节）"
  for k in xpan highPixel retroCamera tiltShift professional; do
    grep -aq "$k" "$DB" 2>/dev/null && ok "  含 $k" || bad "  缺 $k"
  done
else
  bad "mode_data.db 不存在（重开一次相机让它重建）"
fi

echo "=== 7. persist.omnicam.* （运行时属性，0 条属正常）==="
info "条数 $(getprop 2>/dev/null | grep -c 'persist.omnicam')"

echo "=== 8. 污染属性（值为空的 key 不算）==="
POLL=$(getprop 2>/dev/null \
  | grep -iE 'persist\..*camera.*(insensorzoom|disableSatFusion)' \
  | grep -vE '\[[^]]+\]: \[\]$')
if [ -z "$POLL" ]; then ok "无 insensorzoom/disableSatFusion 污染（空值不算）"; else bad "存在污染属性：$POLL"; fi

echo "=== 9. 进程 ==="
for p in com.oplus.camera com.oplus.camera.provider; do
  pid=$(pidof "$p" 2>/dev/null)
  [ -n "$pid" ] && ok "$p 运行中 (pid $pid)" || info "$p 未运行"
done

echo "=== 10. mountify ==="
if [ -f /data/adb/modules/mountify/module.prop ]; then
  [ -f /data/adb/modules/mountify/disable ] && bad "mountify 被 Disable" || ok "mountify 启用"
else
  warn "未检测到 mountify 模块"
fi

echo
echo "=== 汇总：ok=$PASS fail=$FAIL warn=$WARN ==="
[ "$FAIL" -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
