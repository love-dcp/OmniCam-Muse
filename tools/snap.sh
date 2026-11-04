#!/bin/sh
# Take an OmniCam Muse module snapshot from the connected device.
#
#   sh tools/snap.sh before     -> work/snapshot/before-<timestamp>.txt
#   sh tools/snap.sh after      -> work/snapshot/after-<timestamp>.txt
#   python tools/snapshot_diff.py <before.txt> <after.txt>
#
# Records md5 lists for the module tree, the merged /odm trees, the camera app's staged data and
# its shared_prefs, plus live bind mounts, runtime properties and SELinux labels. The small
# payload directories are also pulled as tarballs into work/snapshot/<name>/.
#
# Local paths are kept relative on purpose: with MSYS_NO_PATHCONV=1 (needed so /sdcard survives
# Git Bash) an absolute local path would reach adb unconverted and fail to stat.

set -e
export MSYS_NO_PATHCONV=1

LABEL=${1:-snap}
TS=$(date +%Y%m%d-%H%M%S)
NAME="$LABEL-$TS"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
OUT="work/snapshot"
REMOTE="/sdcard/$NAME.txt"

mkdir -p "$OUT/$NAME"

echo "==> pushing snapshot script"
adb push tools/omnicam_snapshot.sh /sdcard/omnicam-snapshot.sh >/dev/null

echo "==> running on device"
adb shell "su -c 'cp /sdcard/omnicam-snapshot.sh /data/local/tmp/omnicam_snapshot.sh; chmod 755 /data/local/tmp/omnicam_snapshot.sh; sh /data/local/tmp/omnicam_snapshot.sh $REMOTE'"

echo "==> pulling report"
adb pull "$REMOTE" "$OUT/$NAME.txt" >/dev/null
sed -n '3,5p' "$OUT/$NAME.txt"

echo "==> pulling small payload trees"
adb shell "su -c 'cd /data/user/0/com.oplus.camera \
    && tar -cf /sdcard/snap-prefs.tar shared_prefs \
    && tar -czf /sdcard/snap-odm.tgz files/odm \
    && tar -czf /sdcard/snap-pop_port.tgz files/pop_port'"
# Only the module's own logs, never the whole tree (payload is ~550 MB).
adb shell "su -c 'cd /data/adb/modules/ricoh_pop_x9u && ls *.log >/dev/null 2>&1 && tar -czf /sdcard/snap-logs.tgz \$(ls *.log)'" >/dev/null 2>&1 || true
for f in snap-prefs.tar snap-odm.tgz snap-pop_port.tgz snap-logs.tgz; do
    adb pull "/sdcard/$f" "$OUT/$NAME/$f" >/dev/null 2>&1 || echo "    (skip $f)"
done

echo ""
echo "snapshot: $OUT/$NAME.txt"
echo "compare : python tools/snapshot_diff.py <before>.txt $OUT/$NAME.txt"
