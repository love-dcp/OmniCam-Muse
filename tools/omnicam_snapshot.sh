#!/system/bin/sh
# OmniCam Muse module snapshot.
#
# Dumps everything that can differ between two installs of the module: the module tree, the
# merged /odm trees, the camera app's staged data, its shared_prefs, the live bind mounts, the
# runtime properties and the SELinux labels. Run it before and after a reinstall and diff the two
# reports -- that is the fastest way to see what a flash actually changed.
#
# Usage: sh omnicam-snapshot.sh [outfile]

OUT=${1:-/sdcard/omnicam-snapshot.txt}
MOD=/data/adb/modules/ricoh_pop_x9u
APP=/data/user/0/com.oplus.camera
MERGED=/data/adb/omnicam_muse/merged

: > "$OUT" 2>/dev/null || { OUT=/data/local/tmp/omnicam-snapshot.txt; : > "$OUT"; }

sec() { echo "" >>"$OUT"; echo "########## $1 ##########" >>"$OUT"; }
say() { echo "$*" >>"$OUT"; }

dump_md5() {
    # $1 = directory, $2 = label
    if [ -d "$1" ]; then
        ( cd "$1" && find . -type f 2>/dev/null | LC_ALL=C sort | while read -r f; do
            md5sum "$f" 2>/dev/null
        done ) >>"$OUT"
    else
        say "MISSING $1"
    fi
}

sec "meta"
say "taken: $(date '+%Y-%m-%d %H:%M:%S')"
say "device: $(getprop ro.product.model) / $(getprop ro.build.display.id)"
say "fingerprint: $(getprop ro.build.fingerprint)"
say "kernel: $(uname -r)"
say "data free: $(df -h /data 2>/dev/null | tail -1)"

sec "app package"
dumpsys package local.jiege.camera 2>/dev/null | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -4 >>"$OUT"
say "camera apk: $(pm path com.oplus.camera 2>/dev/null | head -1)"

sec "module state flags"
for f in disable remove skip_mount update; do
    if [ -e "$MOD/$f" ]; then say "$f: PRESENT"; else say "$f: absent"; fi
done
say "modules_update: $(ls -d /data/adb/modules_update/ricoh_pop_x9u 2>/dev/null || echo none)"
say "install time: $(stat -c %y "$MOD/module.prop" 2>/dev/null)"

sec "module.prop"
cat "$MOD/module.prop" >>"$OUT" 2>/dev/null

sec "md5 base (/data/adb/modules/ricoh_pop_x9u)"
dump_md5 "$MOD"

sec "md5 merged (/data/adb/omnicam_muse/merged)"
dump_md5 "$MERGED"

sec "md5 pop_port (staged LUT/pop data)"
dump_md5 "$APP/files/pop_port"

sec "md5 jiege (staged decision configs)"
dump_md5 "$APP/files/jiege"

sec "md5 files/odm"
dump_md5 "$APP/files/odm"

sec "shared_prefs listing"
ls -l "$APP/shared_prefs" 2>/dev/null | tail -n +2 >>"$OUT"

sec "md5 shared_prefs"
dump_md5 "$APP/shared_prefs"

sec "bind mounts (live)"
grep -E '/(odm|data/adb/omnicam_muse|data/user/0/com.oplus.camera)' /proc/mounts 2>/dev/null >>"$OUT"

sec "bind.log (last 25)"
tail -25 "$MOD/bind.log" >>"$OUT" 2>/dev/null

sec "selinux labels"
ls -lZ "$MOD"/*.sh >>"$OUT" 2>&1
ls -lZ "$APP/files/pop_port" >>"$OUT" 2>&1
ls -lZ "$APP/files/jiege" >>"$OUT" 2>&1
ls -lZ "$APP/shared_prefs" 2>/dev/null | head -40 >>"$OUT" 2>&1
say "--- /odm bound targets ---"
ls -lZ /odm/etc/camera/config >>"$OUT" 2>&1
ls -ldZ /odm/etc/camera/meishe_lut /odm/etc/camera/filters_lut /odm/etc/camera/basictone >>"$OUT" 2>&1

sec "properties: omnicam"
getprop 2>/dev/null | grep -i omnicam >>"$OUT"

sec "properties: persist camera"
getprop 2>/dev/null | grep -iE 'persist.*camera' >>"$OUT"

sec "props set on this device that the module touches"
for p in persist.omnicam.comp.inspiration persist.omnicam.aesthetic persist.omnicam.realfollow \
         persist.omnicam.comp.antispam persist.omnicam.comp.block persist.omnicam.comp.master; do
    say "$p=$(getprop $p)"
done

sec "end"
say "lines: $(wc -l <"$OUT")"
echo "snapshot written to $OUT"
