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
# Make the flash self-sufficient.
#
# Uninstalling this module deletes the data it stages into the camera and the gallery
# (files/jiege, ricoh_gr, pop_port, mode_limit_watermark, jiege_ipu, ricoh_gr_styles, plus
# nine feature keys in override_config_data.xml) and only service.sh rebuilds it — at boot,
# which never happens on this phone. That is why a reinstall used to come back half dead
# until the staging was run by hand. Run it here instead, with service.sh told to skip the
# boot-only waits.
#
# The /odm config binds are rebuilt for the same reason: their source lives inside the module
# directory, which the manager replaces on every flash, so an old mount keeps pointing at the
# previous (now deleted) file and a changed payload would never reach the camera.
#
# Gate on "the phone's storage is up", not on sys.boot_completed: a flash that lands while that
# property is still unset used to skip this whole block silently, which put the reinstall back to
# half dead - the exact failure this is here to prevent. The camera's own data directory is the
# honest signal that /data/user is decrypted and the app has run at least once.
READY=0
[ -d /data/user/0/com.oplus.camera ] && READY=1
[ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && READY=1
if [ "$READY" = "1" ] && [ -d "$MODPATH/payload" ]; then
    ui_print "- Staging camera and gallery data"
    ( cd "$MODPATH" && OMNICAM_STAGE_ONLY=1 sh ./service.sh ) > "$MODPATH/stage.log" 2>&1
    if [ $? -eq 0 ]; then
        ui_print "  done"
    else
        ui_print "  staging reported an error; see stage.log in the module directory"
    fi

    PROFILE=PKJ110
    CONFIG_SRC="$MODPATH/payload_pkj110"
    CONFIG_FILES="oplus_camera_algo_switch_config camera_unit_config camera_unit_feature_config.protobuf"
    if [ "$(getprop ro.product.model 2>/dev/null)" = PMA110 ]; then
        PROFILE=PMA110
        CONFIG_SRC="$MODPATH/payload"
        CONFIG_FILES="oplus_camera_config oplus_camera_aps_config oplus_camera_preview_decision_config.json $CONFIG_FILES"
    fi
    NSENTER=/system/bin/nsenter
    config_bound() {
        $NSENTER -t 1 -m -- cat /proc/self/mountinfo 2>/dev/null | grep -q " $1 "
    }
    bind_config() {
        SRC="$1"; TGT="$2"
        [ -f "$SRC" ] && [ -f "$TGT" ] || return 1
        chown root:root "$SRC" 2>/dev/null
        chmod 0644 "$SRC" 2>/dev/null
        chcon u:object_r:vendor_configs_file:s0 "$SRC" 2>/dev/null
        N=0
        while config_bound "$TGT" && [ "$N" -lt 6 ]; do
            $NSENTER -t 1 -m -- umount -l "$TGT" 2>/dev/null || break
            N=$((N + 1))
        done
        $NSENTER -t 1 -m -- mount --bind "$SRC" "$TGT" 2>/dev/null
    }
    ui_print "- Re-binding the camera config for $PROFILE"
    for NAME in $CONFIG_FILES; do
        if bind_config "$CONFIG_SRC/$NAME" "/odm/etc/camera/config/$NAME"; then
            ui_print "  bound $NAME"
        else
            ui_print "  could not bind $NAME (run apply-now.sh with root)"
        fi
    done
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
