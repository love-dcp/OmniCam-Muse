# KernelSU unpacks the module with whatever modes the zip carries, and a plain "zip -r" carries
# none -- every file arrives 0644. post-fs-data.sh, service.sh and action.sh are then not
# executable, so after a reboot the module reports itself installed and silently does nothing
# (only the mountify overlay still applies). tools/zip_module.py now records 0755, and this is the
# guaranteed fallback: customize.sh runs after extraction, before the menu manager's swap.
chmod 0755 "$MODPATH"/*.sh 2>/dev/null

# Settings app and hooks: install the APK this module ships while the module is flashed, so LSPosed
# points at it before the reboot that activates the module. A failed install (e.g. a different
# signing key) is left as it is and logged to install.log; the installed app is never uninstalled.
PACKAGE=local.jiege.camera
# Old builds stay next to the new one (handy for rollback), so pick the APK whose version matches
# module.prop instead of whichever name happens to sort first.
VER=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | tr -d ' ')
VER=${VER#Muse}
APK="$MODPATH/omnicam-muse-$VER.apk"
[ -f "$APK" ] || APK=$(ls -1 "$MODPATH"/omnicam-muse-*.apk 2>/dev/null | sort | tail -n 1)
STAGED=/data/local/tmp/omnicam-muse-module.apk
HAVE=$(dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
WANT=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop")
if [ -n "$APK" ] && cp "$APK" "$STAGED" && chmod 0644 "$STAGED"; then
    RESULT=$(pm install -r -d "$STAGED" 2>&1)
    rm -f "$STAGED"
else
    RESULT="APK missing or not staged"
fi
printf 'installed %s -> %s: %s\n' "${HAVE:-none}" "$WANT" "$RESULT" > "$MODPATH/install.log"
ui_print "- Settings app: $RESULT"
# The staged data and the binds above are what a reinstall used to need help with; they are now
# done in the flash, so there is nothing to run afterwards. The one thing this cannot do for
# itself is make a still-running camera process load the new hooks - say that, don't order it.
ui_print "- The settings app is now this build; it loads the next time the camera process"
ui_print "  starts (nothing to do, unless the camera was left open in the background)"

# Update marker for uninstall.sh: this flash replaces an existing
# /data/adb/modules/ricoh_pop_x9u, i.e. it is an in-place UPDATE, not a removal.
# The old module's uninstall.sh runs during the manager's swap and must stay
# conservative (keep the settings app and its user settings); this module's
# service.sh clears the marker once it has run, so a later uninstall.sh run is
# recognised as a genuine removal and does the full cleanup.
if [ -d /data/adb/modules/ricoh_pop_x9u ]; then
    : > /data/adb/.omnicam_muse_update 2>/dev/null
    ui_print "- In-place update detected: uninstall.sh will keep the settings app"
fi

# ---------------------------------------------------------------------------
# Make the flash self-sufficient: one script, one call.
#
# Uninstalling this module removes everything it persists outside its own directory - staged
# camera data (files/{jiege,pop_port,ricoh_gr,mode_limit_watermark,odm/...}), staged gallery data
# (files/{ricoh_gr_styles,jiege_ipu}, the business_featureSwitch key), nine feature keys in
# override_config_data.xml, the mode table, and every /odm bind. Only post-fs-data.sh and
# service.sh rebuild that, and they run at BOOT - which never happens here (temporary root, so the
# phone is never rebooted). Reinstalling used to come back half dead until the stages were run by
# hand.
#
# selfheal.sh runs both stages in boot order, then drops the mode table so the camera rebuilds it
# with the feature rows in place. Order matters: the feature keys must exist BEFORE the table is
# rebuilt, or 理光 GR / 移轴 / POP silently vanish from the mode bar and never come back on their
# own (the mode table is library-first: once written it is read from the db, not from the keys).
#
# Gate on "the phone's storage is up", not on sys.boot_completed: a flash that lands while that
# property is still unset used to skip this whole block silently, which put the reinstall back to
# half dead - the exact failure this is here to prevent. The camera's own data directory is the
# honest signal that /data/user is decrypted and the app has run at least once.
READY=0
[ -d /data/user/0/com.oplus.camera ] && READY=1
[ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && READY=1

# mountify (the metamodule on this device) MAY take over module installation: its metainstall.sh
# runs this customize.sh, then does `rm -rf /data/adb/modules/<id>`, moves the freshly extracted
# tree into place, and only THEN runs $MODULE_HOT_RUN_SCRIPT. A bind created here would point at
# the old directory that rm just deleted - which is how the three /odm/etc/camera/config binds went
# missing on the r146 reinstall. Exporting the hook covers that case.
#
# But do NOT make the hook the only path. Its presence on disk means nothing: mountify only acts as
# the installer after it has been activated by a boot, and until then ksud uses its own path
# (modules_update + an `update` flag, swapped in at the next boot). Gating on
# /data/adb/modules/mountify/metainstall.sh made the flash skip the self-heal entirely in exactly
# that state - the module looked installed and did nothing. Run it here, unconditionally, and let
# the hook run it again after a swap if mountify really did take over. Both stages are idempotent.
export MODULE_HOT_RUN_SCRIPT=selfheal.sh

SELFHEAL="$MODPATH/selfheal.sh"
case "$MODPATH" in *modules_update*) IN_UPDATE=1 ;; *) IN_UPDATE=0 ;; esac

if [ "$READY" != "1" ] || [ ! -f "$SELFHEAL" ]; then
    ui_print "- Storage not up yet: staging will happen on the next camera launch"
elif [ "$IN_UPDATE" = "1" ]; then
    # ksud put this build in /data/adb/modules_update and marked the installed module for
    # replacement at the NEXT BOOT. This phone is never rebooted (temporary root), so that swap
    # would never happen: the module would sit in modules_update forever while the old build kept
    # running. Finish it here instead - swap the directory in, then run the self-heal against the
    # real location so the binds point at a path that will not move again.
    #
    # A script cannot swap the directory it is currently being read from, so the work happens in a
    # detached helper: written to /data/local/tmp, started with setsid so it survives the installer
    # exiting, and it deletes itself when done.
    ui_print "- Taking effect now (no reboot): swapping into place, then staging"
    FINISH=/data/local/tmp/omnicam_finish.sh
    cat > "$FINISH" <<'OMNICAM_FINISH_EOS'
#!/system/bin/sh
# Runs after the manager finished. Swaps modules_update -> modules, then heals.
MOD=/data/adb/modules/ricoh_pop_x9u
UPD=/data/adb/modules_update/ricoh_pop_x9u
ID=ricoh_pop_x9u
LOG=/data/local/tmp/omnicam_finish.log
: > "$LOG"
log() { echo "$1" >> "$LOG"; }

# Let the manager finish writing (it still touches modules_update for a moment).
sleep 5

# The module's own binds keep files inside the old directory alive, and `rm -rf` then fails with
# "Device or resource busy" - which leaves the directory non-empty and makes the following `mv`
# nest the new tree INSIDE the old one. That happened once; drop the binds first, lazily so a
# still-open library cannot block it.
for T in /odm/etc/camera/meishe_lut /odm/etc/camera/filters_lut /odm/etc/camera/basictone \
         /odm/etc/camera/oplus_eis_camera.vcfg \
         /odm/etc/camera/config/oplus_camera_algo_switch_config \
         /odm/etc/camera/config/camera_unit_config \
         /odm/etc/camera/config/camera_unit_feature_config.protobuf \
         /vendor/lib64/camera/components/com.qti.stats.caflogicaldriver.so ; do
    umount -l "$T" 2>/dev/null && log "umount $T"
done

if [ -d "$UPD" ]; then
    am force-stop com.oplus.camera 2>/dev/null
    rm -rf "$MOD" 2>/dev/null
    if [ -d "$MOD" ]; then
        OLD="/data/adb/modules/${ID}.old.$$"
        if mv "$MOD" "$OLD" 2>/dev/null; then
            log "old tree parked at $OLD"
            ( sleep 10; rm -rf "$OLD" ) >/dev/null 2>&1 &
        else
            log "could not park the old tree; swap aborted"
        fi
    fi
    if [ ! -d "$MOD" ] && mv "$UPD" "$MOD" 2>/dev/null; then
        rm -f "$MOD/update"
        log "swapped modules_update -> modules"
    else
        log "SWAP FAILED - leaving modules_update for the next boot"
    fi
    rm -rf "/data/adb/modules_update/$ID" 2>/dev/null
else
    log "no modules_update; nothing to swap"
fi

if [ -f "$MOD/selfheal.sh" ]; then
    sh "$MOD/selfheal.sh" >>"$LOG" 2>&1
    log "selfheal rc=$?"
else
    log "selfheal.sh missing after swap"
fi

rm -f "$0"
OMNICAM_FINISH_EOS
    chmod 0755 "$FINISH"
    setsid sh "$FINISH" >/dev/null 2>&1 &
    ui_print "  runs in a few seconds; watch /data/local/tmp/omnicam_finish.log"
else
    ui_print "- Staging camera and gallery data, rebuilding /odm binds"
    if sh "$SELFHEAL" >/dev/null 2>&1; then
        ui_print "  done - nothing left to run"
    else
        ui_print "  selfheal reported an error; see selfheal.log in the module directory"
    fi
fi

# ---------------------------------------------------------------------------
# Clear the stale CamX override properties at flash time so the fix is already in effect when
# the camera is first opened after the flash, not only after the next boot. Same list and same
# reasoning as clean_stale_camera_props() in post-fs-data.sh: these are `persist.*` leftovers
# from earlier in-sensor-zoom experiments that survive both "clear camera data" and a module
# uninstall, and they crash the stock HAL with an AF-ROI underflow (XPAN 2X, portrait 2X).
CLEARED=""
for P in persist.vendor.camera.disableSatFusion \
         persist.vendor.camera.preview.insensorzoom \
         persist.vendor.camera.aps.insensorzoom ; do
    V=$(getprop "$P" 2>/dev/null)
    if [ -n "$V" ]; then
        setprop "$P" "" 2>/dev/null && CLEARED="$CLEARED $P"
    fi
done
for P in persist.camera.logInfoMask persist.camera.logCoreCfgMask \
         persist.camera.logVerboseMask persist.camera.logWarningMask ; do
    if [ "$(getprop "$P" 2>/dev/null)" = "0xFFFFFFFF" ]; then
        setprop "$P" "" 2>/dev/null && CLEARED="$CLEARED $P"
    fi
done
if [ -n "$CLEARED" ]; then
    ui_print "- Cleared stale camera properties:$CLEARED"
    printf 'cleared stale camera properties:%s\n' "$CLEARED" >> "$MODPATH/install.log"
else
    ui_print "- No stale camera properties to clear"
fi

# The `gallery` hook group is gone as of r32: GalleryReturnRecovery existed only to paper over the
# 2.5x gallery round trip, which turned out to be the stale persist.vendor.camera.* overrides cleared
# above, not an app-side fault. A skip list left pointing at it is now inert, so take it out before
# it confuses the next bisect. Only the exact cases below are touched - a list that also names live
# groups is left alone and just reported, since rewriting it here could drop a setting the user meant.
SKIP=$(getprop persist.omnicam.aura.skip 2>/dev/null)
case "$SKIP" in
    gallery|gallery,|,gallery)
        if setprop persist.omnicam.aura.skip "" 2>/dev/null; then
            ui_print "- Cleared obsolete hook skip: gallery"
            printf 'cleared obsolete hook skip: gallery\n' >> "$MODPATH/install.log"
        fi
        ;;
    *gallery*)
        ui_print "- Note: persist.omnicam.aura.skip still names 'gallery' ($SKIP);"
        ui_print "  that group no longer exists, the rest of the list is untouched"
        ;;
esac

# ---------------------------------------------------------------------------
# Composition tuning switches: back to shipped defaults at flash time.
#
# The composition hooks (灵感构图 / 实景构图) read a set of `persist.omnicam.*` properties that
# nothing in the settings app writes -- they exist so a behaviour can be A/B tested without a
# rebuild, and every reader passes a default, so empty means "as shipped". They survive a flash,
# a reboot, and "clear camera data", which makes a switch left in its debug position look like a
# broken build on the very next install: realfollow=0 turns the subject detector off entirely,
# aesthetic=1 brings back the 276 MB the AIU iqa model costs, compdump=1 writes a 3 MB PNG per
# pass, comp.master=0 hides the entry in 大师, compiconall=1 puts it back in every other mode.
#
# Reset here and only here. Doing it on every boot would also be defensible, but it would quietly
# undo a value someone set on purpose (comp.master=0, objectdetect=0) at the next restart; a flash
# is the one moment "shipped defaults" is what was asked for.
COMP_PROPS="aesthetic memgate rearm gateopen realfollow realforce realprobe realself realtrack
realtrack2 croptrace viewdump cloudcomp demoinject compforce compclear compdump compshot
compiconall compiconlift comppref comppush compguard objectdetect objectfirst plantdetect
scenedetect centrefirst centreanchor
comp.directzoom comp.fallback comp.fixzoom comp.freeze comp.gr comp.hold comp.k4invert comp.keep
comp.landing comp.master comp.noaiunit comp.refresh comp.ride comp.skipalign comp.skipzoom
comp.trackok comp.twice comp.zoom"
RESET=""
for P in $COMP_PROPS; do
    V=$(getprop "persist.omnicam.$P" 2>/dev/null)
    [ -n "$V" ] || continue
    setprop "persist.omnicam.$P" "" 2>/dev/null && RESET="$RESET $P=$V"
done
if [ -n "$RESET" ]; then
    ui_print "- Composition switches back to defaults (were:$RESET)"
    printf 'composition switches reset to defaults:%s\n' "$RESET" >> "$MODPATH/install.log"
else
    ui_print "- Composition switches already at defaults"
fi

true
