#!/usr/bin/env bash
# 升级到 ColorOS 17.0.1.201 之后，一次性把「适配 + 排障」需要的原件与指纹拉回来。
#
# 用法：
#   bash tools/collect_after_201.sh            # 全部（含 164MiB 的 APK）
#   bash tools/collect_after_201.sh --no-apk   # 只拉配置与库指纹，快
#
# 只读：不 push、不 mount、不 force-stop，不会动相机进程，也不会碰模块目录。
set -u

OUT="${OUT:-work/ota201_pull}"
WITH_APK=1
[ "${1:-}" = "--no-apk" ] && WITH_APK=0

export MSYS_NO_PATHCONV=1
ADB="adb"
mkdir -p "$OUT"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "找不到 adb"

$ADB wait-for-device
STATE=$($ADB get-state 2>/dev/null | tr -d '\r')
[ "$STATE" = "device" ] || die "设备状态是 '$STATE'，不是 device"

# root 可用性：升级后临时 root 通常会掉，先探一下，别一上来就失败
if ! $ADB shell "su -c id" 2>/dev/null | grep -q "uid=0"; then
    die "设备上没有 root。OTA 后临时 root 会失效，先重新拿到 root 再跑本脚本"
fi

BUILD=$($ADB shell getprop ro.build.display.id | tr -d '\r')
REL=$($ADB shell getprop ro.build.version.release | tr -d '\r')
PID=$($ADB shell getprop ro.product.name | tr -d '\r')
say "system : $BUILD (Android $REL, $PID)"

CAMLINE=$($ADB shell dumpsys package com.oplus.camera 2>/dev/null \
    | grep -m2 -E 'versionName|versionCode' | tr -d '\r' | paste -sd' ')
say "camera : ${CAMLINE:-<dumpsys 无输出>}"

# 三个原件里最要紧的两个：配置决定 HAL 节点表，vcfg 决定 6X 预览
pull_su() {  # pull_su <设备路径> <本地名>
    local src=$1 dst="$OUT/$2"
    if $ADB shell "su -c 'ls -l $src'" >/dev/null 2>&1; then
        $ADB shell "su -c 'cp -f $src /sdcard/_oc_pull.tmp && chmod 644 /sdcard/_oc_pull.tmp'" \
            || die "复制失败: $src"
        $ADB pull /sdcard/_oc_pull.tmp "$dst" >/dev/null 2>&1 || die "adb pull 失败: $src"
        $ADB shell "rm -f /sdcard/_oc_pull.tmp" >/dev/null 2>&1
        say "pulled : $2 ($(wc -c < "$dst") bytes)"
    else
        say "MISSING: $src"
        return 1
    fi
}

pull_su /odm/etc/camera/CameraHWConfiguration.config CameraHWConfiguration.config
pull_su /odm/etc/camera/oplus_eis_camera.vcfg        oplus_eis_camera.vcfg
[ "$WITH_APK" = "1" ] && pull_su /my_product/app/OplusCamera/OplusCamera.apk OplusCamera.apk

say ""
say "== 相机栈指纹（决定该挂哪套 payload）=="
for f in /odm/lib64/libextensionlayer.so \
         /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so \
         /vendor/lib64/hw/camera.qcom.so; do
    h=$($ADB shell "su -c 'md5sum $f'" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    say "  ${h:-?}  $f"
done
say "  期望 libextensionlayer = 36d10228409c766502b8e67566c6be49"

say ""
say "== 本地文件体检 =="
python - "$OUT" <<'PY'
import json, os, sys

out = sys.argv[1]
cfg = os.path.join(out, "CameraHWConfiguration.config")
vcfg = os.path.join(out, "oplus_eis_camera.vcfg")
apk = os.path.join(out, "OplusCamera.apk")

if os.path.exists(cfg):
    d = open(cfg, "rb").read()
    bad = d.count(b"\0")
    print(f"  config : {len(d)} bytes, NUL={bad}, LF={d.count(chr(10).encode())}, "
          f"[InsensorZoom]={'[InsensorZoom]' in d.decode('utf-8', 'replace')}")
    print(f"  config 完整 -> {bad == 0 and len(d) > 200000}")

if os.path.exists(vcfg):
    d = open(vcfg, "rb").read()
    try:
        n = len(json.loads(d.decode("utf-8")))
        print(f"  vcfg   : {len(d)} bytes, JSON ok, keys={n}")
    except Exception as e:
        print(f"  vcfg   : {len(d)} bytes, JSON ERR {e}")

if os.path.exists(apk):
    import zipfile
    try:
        z = zipfile.ZipFile(apk)
        print(f"  apk    : {os.path.getsize(apk)} bytes, zip ok, entries={len(z.namelist())}")
    except Exception as e:
        print(f"  apk    : {os.path.getsize(apk)} bytes, zip ERR {e}")
PY

say ""
say "全部通过后执行（--hw-config / --eis-vcfg 只有在体检 OK 时才带）："
say "  python tools/build_pkj110_payload.py --gen 17.0.1.201 \\"
say "    --odm-lib work/ota201_pull/libextensionlayer.so \\"
say "    --caf-lib work/ota201_pull/caflogicaldriver.so \\"
say "    --hw-config $OUT/CameraHWConfiguration.config \\"
say "    --eis-vcfg  $OUT/oplus_eis_camera.vcfg \\"
say "    --out module/payload_pkj110_201"
say ""
say "注：--odm-lib/--caf-lib 需要的是未打补丁的 stock 库；若这里没有，就用固件解出的"
say "    E:/ota201/stock/odm/lib64/libextensionlayer.so 与"
say "    E:/ota201/stock/vendor_lib64/caflogicaldriver.so（已校验与 201 一致）。"
