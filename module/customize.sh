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
# The install replaces the APK but this phone keeps the running camera process alive
# (verified: pm install -r leaves the pid unchanged), and LSPosed only loads the new
# hooks when that process starts. Nothing is forced here - just say what to do.
ui_print "- If the camera was running in the background, swipe it out of recents (or"
ui_print "  toggle anything in the settings app) so the new build is loaded"

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
