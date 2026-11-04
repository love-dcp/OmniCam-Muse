#!/system/bin/sh
# =============================================================================
# selfheal.sh — put the module back into its "booted" state right after a flash.
#
# Why this exists
# ---------------
# Everything this module does outside its own directory (staged app data, staged
# gallery data, prefs feature keys, /odm config binds, the merged LUT trees) is
# rebuilt by post-fs-data.sh and service.sh — which run at BOOT. This phone has
# temporary root: a hard reboot loses root, so it is never rebooted, and a flash
# therefore used to leave the module half dead until someone ran the stages by
# hand. Real reports after the r146 reinstall:
#
#   * POP / 理光 GR / 哈苏超清 disappeared — mode_data.db had been rebuilt by the
#     camera while the feature keys were absent, so it only carried xpan and
#     highPixel. The mode table is "library first": once written it is read from
#     the db, and rows never come back on their own.
#   * /odm/etc/camera/config/{oplus_camera_algo_switch_config,camera_unit_config,
#     camera_unit_feature_config.protobuf} lost their binds — customize.sh binds
#     them, but mountify's metainstall.sh swaps the module directory afterwards
#     (rm -rf + mv), so the flash-time bind was gone by the time the install
#     finished.
#   * libBasicTonePhoto.so kept a bind to a deleted inode.
#
# What it runs is exactly what a boot would run, in the same order, plus the one
# step a boot gets for free (the camera starts fresh, so it rebuilds the dropped
# mode table).
#
# Both stages it calls are idempotent: service.sh rebuilds staged files in place,
# post-fs-data.sh drops each bind before re-creating it and rebuilds the merged
# trees under /data/adb/omnicam_muse (never inside the module directory, which is
# replaced on every flash).
#
# Called from:
#   customize.sh          — directly (plain KernelSU install path), and via
#                           MODULE_HOT_RUN_SCRIPT (mountify runs that AFTER its
#                           directory swap), plus a delayed background re-run.
#   action.sh             — manually, as a "repair" button.
# =============================================================================
MODDIR=${0%/*}
LOG=$MODDIR/selfheal.log
CAM=/data/user/0/com.oplus.camera
MODULE_ID=ricoh_pop_x9u

: > "$LOG"
log() { echo "$1" >> "$LOG"; }

log "selfheal start: $(date '+%Y-%m-%d %H:%M:%S') model=$(getprop ro.product.model)"
log "moddir=$MODDIR"

# A flash that unpacks a zip without unix modes leaves every script 0644, and after the next boot
# KernelSU cannot exec post-fs-data.sh / service.sh at all. The directory swap preserved exactly
# those modes on r147 (verified by snapshot), so fix them here too: this runs on every install and
# on every manual repair.
SCRIPTS_BEFORE=$(ls -l "$MODDIR"/*.sh 2>/dev/null | grep -c '^-rwx')
chmod 0755 "$MODDIR"/*.sh 2>/dev/null
SCRIPTS_AFTER=$(ls -l "$MODDIR"/*.sh 2>/dev/null | grep -c '^-rwx')
NOSHEBANG=$(ls -l "$MODDIR"/*.sh 2>/dev/null | wc -l)
log "script modes: $SCRIPTS_BEFORE/$NOSHEBANG executable before, $SCRIPTS_AFTER after chmod 0755"

# ---------------------------------------------------------------------------
# 0. UI-mirrored properties. MUST run before any stage: post-mount.sh reads
#    aura.palettebt to choose the BasicTone backend library, and post-fs-data
#    reads others while building the bind set.
#
#   The camera process cannot read the module's preferences (different uid and
#   SELinux categories), so settings reach it as persist.omnicam.* properties.
#   The ONLY writer is the settings page (PhotoSection.pushInspiration), and a
#   full uninstall deletes every persist.omnicam.* property AND pm-uninstalls
#   the app -- so after a reinstall neither side exists, nothing writes the
#   property back, and composition silently runs on the on-device engine while
#   the panel still looks normal. Seed the properties whose default is "on".
#
#   Only when absent: a deliberate "0" from the user survives a reinstall.
# ---------------------------------------------------------------------------
seed_prop() {
    _k=$1
    _v=$2
    _cur=$(getprop "$_k" 2>/dev/null)
    if [ -z "$_cur" ]; then
        if setprop "$_k" "$_v" 2>/dev/null; then
            log "  seeded $_k=$_v"
        else
            log "  could not seed $_k"
        fi
    else
        log "  kept $_k=$_cur"
    fi
}
log "--- mirrored properties ---"
# Replay the backup the full uninstaller leaves behind before seeding defaults: those properties
# were the user's actual settings, and they are the only copy that survives a pm-uninstall.
# Only properties that are currently absent are written, so anything the user changed after the
# reinstall wins over the backup.
PROPS_BACKUP=/sdcard/OmniCam-Muse/omnicam-props.txt
if [ -f "$PROPS_BACKUP" ]; then
    _restored=0
    _skipped=0
    while IFS= read -r _line; do
        _k=$(printf '%s\n' "$_line" | sed -n 's/^\[\([^]]*\)\]: \[\(.*\)\]$/\1/p')
        _v=$(printf '%s\n' "$_line" | sed -n 's/^\[\([^]]*\)\]: \[\(.*\)\]$/\2/p')
        [ -n "$_k" ] || continue
        [ -n "$_v" ] || continue
        if [ -z "$(getprop "$_k" 2>/dev/null)" ]; then
            if setprop "$_k" "$_v" 2>/dev/null; then
                _restored=$((_restored + 1))
            else
                log "  restore failed $_k"
            fi
        else
            _skipped=$((_skipped + 1))
        fi
    done < "$PROPS_BACKUP"
    log "  restored $_restored properties from $PROPS_BACKUP ($_skipped already set, left alone)"
else
    log "  no property backup at $PROPS_BACKUP (clean install or never uninstalled fully)"
fi
seed_prop persist.omnicam.comp.inspiration 1

# ---------------------------------------------------------------------------
# 1. Stop the camera. It holds the old configs, the old staged data and the old
#    hook classes; leaving it running means the user sees the previous build
#    until they swipe it away.
# ---------------------------------------------------------------------------
if pidof com.oplus.camera >/dev/null 2>&1; then
    am force-stop com.oplus.camera 2>/dev/null
    log "camera stopped"
    sleep 1
else
    log "camera not running"
fi

# ---------------------------------------------------------------------------
# 2. /odm binds and the merged LUT trees — the whole boot-time patch set.
#    post-fs-data.sh drops each existing bind before re-creating it, so an
#    install that happened over a live module (source inode deleted) is repaired
#    by the same call.
#
#    This runs BEFORE the staging step on purpose: it is the order a boot uses
#    (post-fs-data -> metamount -> post-mount -> service), and service.sh reads
#    /odm/etc/camera/basictone/lmt to stage the GR tone tables.
# ---------------------------------------------------------------------------
sh "$MODDIR/post-fs-data.sh" >>"$LOG" 2>&1
log "post-fs-data.sh rc=$?"

# ---------------------------------------------------------------------------
# 2b. post-mount.sh — the phase KernelSU runs right after metamount.sh.
#     On PKJ110 it detaches the /odm/lib64 overlay mountify leaves behind and
#     purges the X9U backends from mountify's workspace (master-colour captures
#     depend on the stock BasicTone backend), plus drops stale binds from an
#     older build. None of that happens anywhere else at flash time.
# ---------------------------------------------------------------------------
if [ -f "$MODDIR/post-mount.sh" ]; then
    sh "$MODDIR/post-mount.sh" >>"$LOG" 2>&1
    log "post-mount.sh rc=$?"
fi

# ---------------------------------------------------------------------------
# 3. Camera-side + gallery-side staging, feature keys, fonts.
#    service.sh with OMNICAM_STAGE_ONLY=1 skips the waits that only make sense
#    during boot (it does not skip the work).
# ---------------------------------------------------------------------------
OMNICAM_STAGE_ONLY=1 sh "$MODDIR/service.sh" >>"$LOG" 2>&1
log "service.sh rc=$?"

# ---------------------------------------------------------------------------
# 4. The mode table.
#    prefs are written by step 2, so the camera will rebuild the table WITH the
#    feature rows the moment it starts — but only if the stale table is gone.
#    Check the two rows that the reinstall actually lost (retroCamera = 理光 GR,
#    tiltShift = 移轴; xpan/highPixel survive on their own). Deleting an
#    already-correct table would throw away the user's mode order, so it is only
#    dropped when a row is missing.
# ---------------------------------------------------------------------------
DB=$CAM/databases/mode_data.db
NEED_RESET=0
if [ ! -f "$DB" ]; then
    NEED_RESET=1
    log "mode_data.db absent -> will be rebuilt by the camera"
else
    grep -aq retroCamera "$DB" || { NEED_RESET=1; log "mode_data.db has no retroCamera row"; }
    grep -aq tiltShift   "$DB" || { NEED_RESET=1; log "mode_data.db has no tiltShift row"; }
fi
if [ "$NEED_RESET" = "1" ]; then
    rm -f "$DB" "$DB-journal" "$DB-wal" "$DB-shm"
    log "mode_data.db dropped; the camera rebuilds it from the feature keys now staged"
else
    log "mode_data.db already carries the feature rows"
fi


# ---------------------------------------------------------------------------
# 5. Camera provider. It parses /odm/etc/camera/config/* once when it loads and
#    caches the result, so a rebound config only reaches the HAL after the
#    process is replaced. init respawns it immediately — this is a process
#    restart, not a reboot, and it does not touch the root exploit.
# ---------------------------------------------------------------------------
PROVIDER=vendor.qti.camera.provider-service_64
if pidof $PROVIDER >/dev/null 2>&1; then
    kill $(pidof $PROVIDER) 2>/dev/null
    log "camera provider stopped; init respawns it with the rebound configs"
    sleep 3
    if pidof $PROVIDER >/dev/null 2>&1; then
        log "  provider back: pid $(pidof $PROVIDER)"
    else
        log "  provider has not come back yet (init will retry)"
    fi
fi

# ---------------------------------------------------------------------------
# 6. Report what is actually mounted, so a failure is visible without logcat.
# ---------------------------------------------------------------------------
log "--- resulting binds ---"
for T in /odm/etc/camera/meishe_lut \
         /odm/etc/camera/filters_lut \
         /odm/etc/camera/basictone \
         /odm/etc/camera/oplus_eis_camera.vcfg \
         /odm/etc/camera/config/oplus_camera_algo_switch_config \
         /odm/etc/camera/config/camera_unit_config \
         /odm/etc/camera/config/camera_unit_feature_config.protobuf \
         /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so ; do
    if grep -q " $T " /proc/self/mounts 2>/dev/null; then
        log "  ok      $T"
    else
        log "  MISSING $T"
    fi
done

log "--- staged data ---"
log "  pop_port=$(find "$CAM/files/pop_port" -type f 2>/dev/null | wc -l) files"
log "  jiege=$(find "$CAM/files/jiege" -type f 2>/dev/null | wc -l) files"
log "  ricoh_gr=$(find "$CAM/files/ricoh_gr" -type f 2>/dev/null | wc -l) files"
log "  merged=$(find /data/adb/omnicam_muse/merged -type f 2>/dev/null | wc -l) files"

log "--- feature keys ---"
if [ -f "$CAM/shared_prefs/override_config_data.xml" ]; then
    log "  $(grep -c '<string' "$CAM/shared_prefs/override_config_data.xml") keys staged"
else
    log "  MISSING override_config_data.xml"
fi

log "selfheal done: $(date '+%Y-%m-%d %H:%M:%S')"

# Every chcon/restorecon in the two stages prints a multi-line "SELinux: Loaded file context
# from:" banner plus a list of *_file_contexts paths. That is dozens of lines of noise in front of
# the report and it hid a real MISSING line once during testing. Filter at the very end rather than
# per-command: `sed -i` on toybox did not match these lines reliably.
if grep -q "SELinux: Loaded file context" "$LOG" 2>/dev/null; then
    TMP=$LOG.nose
    grep -v -e "SELinux: Loaded file context" -e "_file_contexts" "$LOG" > "$TMP" 2>/dev/null
    mv -f "$TMP" "$LOG"
fi
exit 0
