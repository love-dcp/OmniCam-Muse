#!/system/bin/sh
# =============================================================================
# uninstall.sh — remove everything this module persisted outside $MODDIR
#
# Runs when KernelSU/Magisk deletes the module (the module directory still exists
# while this runs). The manager ALSO runs it on in-place module updates, and an
# update must not uninstall the settings app — that would wipe its user settings
# every time the module is refreshed. The two cases are told apart by the update
# marker:
#
#   customize.sh  writes /data/adb/.omnicam_muse_update when this zip flashes
#                 OVER an existing /data/adb/modules/ricoh_pop_x9u (an update);
#   service.sh    of the newly installed module clears the marker once it ran;
#   this script   marker present (or a staged modules_update/<id>) => UPDATE:
#                 conservative cleanup only — everything removed here is
#                 rewritten by the new module's service.sh on the next boot.
#                 No marker => genuine REMOVAL: full cleanup via uninstall_full.sh
#                 (binds, staged app data, prefs, props, the settings app and
#                 its LSPosed data).
#
# The heuristic is deliberately biased to the safe side (a stale marker can only
# keep the settings app alive). action.sh offers the full cleanup as an explicit
# two-press button when even that is not enough.
# =============================================================================
MODDIR=${0%/*}
MODULE_ID=ricoh_pop_x9u
UPDATE_MARKER=/data/adb/.omnicam_muse_update

if [ -d "/data/adb/modules_update/$MODULE_ID" ] || [ -f "$UPDATE_MARKER" ]; then
# =============================================================================
# UPDATE MODE — conservative. Everything below is rewritten by the new module's
# service.sh on the next boot, so updates are safe. Persistent state cleaned:
#   camera  shared_prefs/override_config_data.xml   9 feature keys
#           databases/mode_data.db                  gr/retroCamera rows (built by the
#                                                   camera from those keys; deleting the
#                                                   db once rebuilds the stock mode list)
#           shared_prefs/...characteristics_cache   rebuilt by the camera
#           files/{ricoh_gr,jiege,pop_port,odm/...,mode_limit_watermark}
#   gallery shared_prefs/business_featureSwitch_config.xml  is_camera_gr_supported
#           files/{ricoh_gr_styles,jiege_ipu}
#   props   persist.omnicam.* hook switches (inert once the module is gone)
# =============================================================================
say() { echo "[uninstall] $*"; }

say "update detected — conservative cleanup (settings app kept)"

CAM=/data/user/0/com.oplus.camera
GAL=/data/user/0/com.coloros.gallery3d

# --- 1. camera -----------------------------------------------------------------
# Kill it first so nothing rewrites the files under us.
am force-stop com.oplus.camera 2>/dev/null
CAM_UID=$(stat -c %u "$CAM" 2>/dev/null)

# 1a. Remove exactly the keys service.sh wrote; keep any stock keys in the file.
PREF="$CAM/shared_prefs/override_config_data.xml"
if [ -f "$PREF" ]; then
    for KEY in \
        com.oplus.gr.mode.support \
        com.oplus.camera.mode.data.db.version \
        com.oplus.available.gr.mode.zoomvalues \
        com.oplus.gr.mode.marked.zoomvalues \
        com.oplus.camera.capture.hdr.cap.mode.value \
        com.oplus.camera.preview.hdr.cap.mode.value \
        com.oplus.feature.retro.camera.support \
        com.oplus.flashlevel.configurable.support \
        com.oplus.feature.tilt.shift.photo.support
    do
        sed -i "/name=\"$KEY\"/d" "$PREF" 2>/dev/null
    done
    if ! grep -q '<string\|<boolean\|<int\|<long' "$PREF" 2>/dev/null; then
        rm -f "$PREF"
        say "override_config_data.xml had only module keys -> file removed"
    else
        [ -n "$CAM_UID" ] && chown "$CAM_UID:$CAM_UID" "$PREF"
        restorecon "$PREF" 2>/dev/null
        say "module keys removed from override_config_data.xml"
    fi
fi

# 1b. mode_data.db: with the gr/retroCamera feature keys gone the camera would rebuild
# the stock table, but the rows it already wrote stay until then — drop the db once so
# the stock mode list (no GR / POP entries) comes back on the next launch. This is the
# same rm service.sh performs for the tilt-shift reset, so the rebuild is proven.
DB="$CAM/databases/mode_data.db"
if [ -f "$DB" ] && grep -aq retroCamera "$DB"; then
    rm -f "$DB" "$DB-journal" "$DB-wal" "$DB-shm"
    say "mode_data.db dropped (camera rebuilds it without GR/POP)"
fi

# 1c. Characteristics cache is built from our feature flags; let the camera rebuild it.
rm -f "$CAM/shared_prefs/com.oplus.ocs.camera.camera_characteristics_cache.xml" 2>/dev/null

# 1d. Module-owned directories inside the camera's sandbox.
rm -rf "$CAM/files/ricoh_gr" "$CAM/files/jiege" "$CAM/files/pop_port" "$CAM/files/mode_limit_watermark"
rm -rf "$CAM/files/odm/etc/camera/pld_watermark"
# rmdir only removes empty dirs: if the camera itself uses files/odm it is left alone.
rmdir "$CAM/files/odm/etc/camera" "$CAM/files/odm/etc" "$CAM/files/odm" 2>/dev/null
say "camera files cleaned"

# --- 2. gallery ----------------------------------------------------------------
am force-stop com.coloros.gallery3d 2>/dev/null
GAL_UID=$(stat -c %u "$GAL" 2>/dev/null)
GPREF="$GAL/shared_prefs/business_featureSwitch_config.xml"
if [ -f "$GPREF" ]; then
    sed -i '/business_featureSwitch_is_camera_gr_supported/d' "$GPREF" 2>/dev/null
    [ -n "$GAL_UID" ] && chown "$GAL_UID:$GAL_UID" "$GPREF"
    restorecon "$GPREF" 2>/dev/null
fi
rm -rf "$GAL/files/ricoh_gr_styles" "$GAL/files/jiege_ipu"
say "gallery cleaned"

# --- 3. hook switches ----------------------------------------------------------
# persist.omnicam.* decides what the hooks do; the new module's own defaults apply
# after the update, and a stale switch would override them.
if command -v resetprop >/dev/null 2>&1; then
    getprop | awk -F'[][]' '/^\[persist\.omnicam\./ {print $2}' | while read -r P; do
        resetprop --delete "$P" 2>/dev/null
    done
    say "persist.omnicam.* props deleted"
else
    say "resetprop not found; leftover persist.omnicam.* props are handled by the new module"
fi

say "done — update cleanup finished, the new module re-stages everything on next boot"
exit 0
fi

# =============================================================================
# REMOVAL MODE — full cleanup (see uninstall_full.sh for the inventory).
# =============================================================================
if [ -f "$MODDIR/uninstall_full.sh" ]; then
    . "$MODDIR/uninstall_full.sh"
    cleanup_module_full
else
    # Safety net for a broken package: fall back to the conservative body above.
    say() { echo "[uninstall] $*"; }
    say "uninstall_full.sh missing — running minimal cleanup"
    am force-stop com.oplus.camera 2>/dev/null
    am force-stop com.coloros.gallery3d 2>/dev/null
    rm -rf /data/user/0/com.oplus.camera/files/ricoh_gr \
           /data/user/0/com.oplus.camera/files/jiege \
           /data/user/0/com.oplus.camera/files/pop_port \
           /data/user/0/com.oplus.camera/files/mode_limit_watermark \
           /data/user/0/com.oplus.camera/files/odm/etc/camera/pld_watermark \
           /data/user/0/com.coloros.gallery3d/files/ricoh_gr_styles \
           /data/user/0/com.coloros.gallery3d/files/jiege_ipu
    if command -v resetprop >/dev/null 2>&1; then
        getprop | awk -F'[][]' '/^\[persist\.omnicam\./ {print $2}' | while read -r P; do
            resetprop --delete "$P" 2>/dev/null
        done
    fi
    say "minimal cleanup done — uninstall the settings app (local.jiege.camera) manually"
fi
