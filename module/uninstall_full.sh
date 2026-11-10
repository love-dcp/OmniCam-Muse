#!/system/bin/sh
# =============================================================================
# uninstall_full.sh — OmniCam Muse full cleanup library (module copy)
#
# Vendored from uninstaller/cleanup.sh (the standalone uninstaller); keep the
# two in sync. Sourced by uninstall.sh (genuine module removal) and action.sh
# (explicit full uninstall). Every entry point is idempotent.
#
# What belongs to OmniCam Muse:
#   * KSU module             /data/adb/modules/ricoh_pop_x9u (+ modules_update)
#   * merged bind sources    /data/adb/omnicam_muse, /data/adb/ricoh_pop_x9u
#   * settings app           local.jiege.camera (+ its LSPosed prefs)
#   * camera private data    files/jiege, files/ricoh_gr, files/pop_port, ...
#   * gallery private data   files/ricoh_gr_styles, files/jiege_ipu, files/jiege
#   * preferences            module keys in the camera/gallery shared_prefs
#   * system properties      persist.omnicam.*
#   * debug leftovers        /sdcard/omnicam-diag-*, /data/local/tmp/omnicam-*, x8u-*
#
# It never touches DCIM, Pictures, Movies, the camera/gallery APKs themselves,
# or any user photo/video.
# =============================================================================

CAM_PKG=com.oplus.camera
GAL_PKG=com.coloros.gallery3d
APP_PKG=local.jiege.camera
CAM_DATA=/data/user/0/$CAM_PKG
GAL_DATA=/data/user/0/$GAL_PKG
SELF_ID=omnicam-muse-uninstaller
MODULE_ID=ricoh_pop_x9u
UPDATE_MARKER=/data/adb/.omnicam_muse_update
# Set by uninstall_settings_app when the package is still present afterwards.
APP_STILL_INSTALLED=0

# Module-only preference keys written by the hooks into the camera's shared_prefs.
# `pref_jiege_*` covers presets/watermark slots; `*_jiege_*` covers the per-mode state.
# The remaining names are stock keys whose values the module changes (GR/XPAN watermark
# slots, last mode, snap focus). Removing them makes the camera fall back to its defaults
# instead of pointing at a mode/style that no longer exists.
PREF_PATTERN='name="pref_jiege_|name="[^"]*_jiege_|name="pref_watermark_gr_capture_switch_key"|name="pref_watermark_capture_switch_key"|name="pref_camera_mode_key"|name="pref_snap_switch_key"|name="pref_ai_master_watermark_photo_style_id"|name="pref_ai_master_watermark_photo_style_id_backup"|name="pref_ai_master_watermark_photo_open_state"|name="pref_ai_master_watermark_mode_limit_style_id"|name="pref_ai_master_watermark_mode_limit_style_id_backup"|name="pref_ai_master_watermark_mode_limit_open_state"|name="pref_watermark_mode_limit_name"'

# Keys service.sh injects into the camera's override_config_data.xml.
OVERRIDE_KEYS="com.oplus.gr.mode.support \
com.oplus.camera.mode.data.db.version \
com.oplus.available.gr.mode.zoomvalues \
com.oplus.gr.mode.marked.zoomvalues \
com.oplus.camera.capture.hdr.cap.mode.value \
com.oplus.camera.preview.hdr.cap.mode.value \
com.oplus.feature.retro.camera.support \
com.oplus.flashlevel.configurable.support \
com.oplus.feature.tilt.shift.photo.support"

say() {
    if type ui_print >/dev/null 2>&1; then
        ui_print "$1"
    else
        echo "$1"
    fi
}

warn() {
    say "! $1"
}

is_mounted() {
    [ -n "$1" ] || return 1
    grep -q " $1 " /proc/self/mounts 2>/dev/null
}

# mountify keeps its workspace on a tmpfs under /mnt/vendor. Never delete there unless the
# mount is provably in-memory: a real partition mount under this path is a red line.
mountify_workspace_is_tmpfs() {
    # A symlinked workspace could lead into a real partition; refuse it outright.
    [ -L /mnt/vendor ] && return 1
    [ -L /mnt/vendor/mountify ] && return 1
    _mnt=$(awk '$2 == "/mnt/vendor/mountify" { print $3; exit }' /proc/self/mounts 2>/dev/null)
    if [ -n "$_mnt" ]; then
        [ "$_mnt" = "tmpfs" ] && return 0
        return 1
    fi
    grep -q " /mnt/vendor tmpfs " /proc/self/mounts 2>/dev/null && return 0
    return 1
}

# Only user data may be removed. The exact roots are rejected as well, so a malformed
# variable can never turn into `safe_rm_rf /data`, `safe_rm_rf /data/adb/modules_update/`
# or `safe_rm_rf /sdcard`.
allowed_data_path() {
    case "$1" in
        /*) ;;
        *) return 1 ;;
    esac
    case "$1" in
        *"/../"*|*/..|*"/./"*|*/.) return 1 ;;
    esac
    _clean_path=$1
    while [ "${_clean_path%/}" != "$_clean_path" ]; do
        _clean_path=${_clean_path%/}
    done
    case "$_clean_path" in
        /data|/data/adb|/data/adb/modules|/data/adb/modules_update|/data/adb/ksu|/data/user|/data/user/0|/data/user_de|/data/user_de/0|/data/misc|/data/local/tmp|/data/app|/data/data|/sdcard|/sdcard/Android|/storage/emulated/0|/mnt/vendor/mountify) return 1 ;;
        /data/*) return 0 ;;
        /sdcard/*)
            _sdcard_real=$(readlink -f /sdcard 2>/dev/null)
            case "$_sdcard_real" in
                /storage/emulated/*) return 0 ;;
                *) return 1 ;;
            esac
            ;;
        /storage/emulated/*) return 0 ;;
        /mnt/vendor/mountify/*) mountify_workspace_is_tmpfs && return 0; return 1 ;;
    esac
    return 1
}

# All removals in this library go through these two helpers. They refuse /odm, /system,
# /vendor, /product, /system_ext, /boot, /metadata, /dev/block and any other non-data path.
safe_rm_rf() {
    _rm_failed=0
    for _path in "$@"; do
        [ -n "$_path" ] || continue
        if allowed_data_path "$_path"; then
            command rm -rf "$_path" 2>/dev/null || _rm_failed=1
        else
            warn "拒绝删除非数据路径：$_path"
            _rm_failed=1
        fi
    done
    return $_rm_failed
}

safe_rm_f() {
    _rm_failed=0
    for _path in "$@"; do
        [ -n "$_path" ] || continue
        if allowed_data_path "$_path"; then
            command rm -f "$_path" 2>/dev/null || _rm_failed=1
        else
            warn "拒绝删除非数据路径：$_path"
            _rm_failed=1
        fi
    done
    return $_rm_failed
}

# Create a marker/scratch file only inside data. A pre-existing symlink is removed first so
# the redirect cannot be pointed at a partition file.
safe_touch_empty() {
    case "$1" in
        *"/../"*|*/..) warn "拒绝写入带 .. 的路径：$1"; return 1 ;;
        /data/user/*|/data/user_de/*|/data/adb/modules/*|/data/adb/modules_update/*|/data/local/tmp/*) ;;
        *) warn "拒绝写入非数据路径：$1"; return 1 ;;
    esac
    _parent=${1%/*}
    [ -L "$_parent" ] && { warn "拒绝写入符号链接目录：$_parent"; return 1; }
    [ -L "$1" ] && command rm -f "$1" 2>/dev/null
    : > "$1" 2>/dev/null
}

app_alive() {
    pidof "$1" >/dev/null 2>&1 && return 0
    ps -A 2>/dev/null | grep -q "[ ]$1$" && return 0
    return 1
}

force_stop_apps() {
    for _pkg in "$CAM_PKG" "$GAL_PKG" "$APP_PKG"; do
        am force-stop "$_pkg" >/dev/null 2>&1 || true
    done
    # am force-stop is asynchronous; wait briefly so no process recreates its files below.
    _i=0
    while [ "$_i" -lt 20 ]; do
        _alive=0
        app_alive "$CAM_PKG" && _alive=1
        app_alive "$GAL_PKG" && _alive=1
        [ "$_alive" -eq 0 ] && break
        sleep 1
        _i=$((_i + 1))
    done
}

# Kills the root logcat started by the settings app's "开启日志" if it is still recording.
kill_logcat_capture() {
    for _proc in /proc/[0-9]*; do
        [ -r "$_proc/cmdline" ] || continue
        _cmd=$(tr '\0' ' ' < "$_proc/cmdline" 2>/dev/null)
        case "$_cmd" in
            *"logcat -b main,system,crash -f /data/user/0/local.jiege.camera"*)
                _pid=${_proc##*/}
                [ "$_pid" = "$$" ] && continue
                kill "$_pid" 2>/dev/null || kill -9 "$_pid" 2>/dev/null
                ;;
        esac
    done
}

is_ours_source() {
    case "$1" in
        /data/adb/modules/ricoh_pop_x9u|/data/adb/modules/ricoh_pop_x9u/*) return 0 ;;
        /data/adb/modules_update/ricoh_pop_x9u|/data/adb/modules_update/ricoh_pop_x9u/*) return 0 ;;
        /data/adb/omnicam_muse|/data/adb/omnicam_muse/*) return 0 ;;
        /data/adb/ricoh_pop_x9u|/data/adb/ricoh_pop_x9u/*) return 0 ;;
        # mountinfo reports the bind root relative to the /data mount as /adb/...
        /adb/modules/ricoh_pop_x9u|/adb/modules/ricoh_pop_x9u/*) return 0 ;;
        /adb/modules_update/ricoh_pop_x9u|/adb/modules_update/ricoh_pop_x9u/*) return 0 ;;
        /adb/omnicam_muse|/adb/omnicam_muse/*) return 0 ;;
        /adb/ricoh_pop_x9u|/adb/ricoh_pop_x9u/*) return 0 ;;
        /data/local/tmp/x8u-*|/data/local/tmp/omnicam-*) return 0 ;;
        /mnt/vendor/mountify/odm/lib64/libBasicTonePhoto*) return 0 ;;
        /mnt/vendor/mountify/odm/lib64/libAlgoInterface.so) return 0 ;;
        /mnt/vendor/mountify/odm/lib64/libmsnativefilter.so) return 0 ;;
    esac
    return 1
}

# Unmounts every bind whose source lives in the module or its scratch trees. This catches
# normal boot state as well as the PKJ110 soft-reboot case, where the kernel keeps the old
# mounts while userspace (and the module directory) has been replaced.
unmount_ours() {
    _list="$MODDIR/unmount.list.$$"
    safe_touch_empty "$_list" || _list=/data/local/tmp/omnicam-unmount.$$
    safe_touch_empty "$_list"
    while read -r _id _parent _majmin _root _tgt _opts _rest; do
        if is_ours_source "$_root"; then
            printf '%s\n' "$_tgt" >> "$_list"
        fi
    done < /proc/self/mountinfo
    # Keep the /proc/mounts source fallback for kernels that report the bind source there.
    while read -r _src _tgt _rest; do
        if is_ours_source "$_src"; then
            printf '%s\n' "$_tgt" >> "$_list"
        fi
    done < /proc/mounts
    # Deepest/latest mounts first; a directory bind and a file bind can share a target.
    _targets=$(awk '!seen[$0]++ { a[++n] = $0 } END { for (i = n; i >= 1; i--) print a[i] }' "$_list" 2>/dev/null)
    safe_rm_f "$_list"
    for _tgt in $_targets; do
        # Never unmount /odm itself, /system, /vendor, /data or anything outside the module's
        # own bind points. umount only changes the mount table; it does not write a partition.
        # The CAF UBSAN fix binds under /vendor/lib64/camera/components (post-fs-data.sh).
        case "$_tgt" in
            *"/../"*|*/..) warn "跳过带 .. 的挂载点：$_tgt"; continue ;;
            /odm/*) ;;
            /vendor/lib64/camera/components/*) ;;
            *) warn "跳过非模块挂载：$_tgt"; continue ;;
        esac
        is_mounted "$_tgt" || continue
        _n=0
        while is_mounted "$_tgt" && [ "$_n" -lt 4 ]; do
            umount "$_tgt" >/dev/null 2>&1 || umount -l "$_tgt" >/dev/null 2>&1 || break
            _n=$((_n + 1))
        done
        if is_mounted "$_tgt"; then
            warn "挂载仍存在：$_tgt（重启后消失）"
        else
            say "  已卸载 $_tgt"
        fi
    done
}

# Never detach /odm/lib64 itself. mountify builds one merged overlay for every module in that
# directory, so detaching it would also remove OPlusHapticsEnhancer's vibration library and other
# modules' files until the next boot. This module only owns the X9/X10 BasicTone files under their
# own names; leaving those harmless extra files behind is safer than breaking a foreign module.

# Merged trees are bind sources. Removing them while still mounted would leave empty
# directories behind the mount, so defer to the boot phase when that happens.
remove_merged_trees() {
    for _dir in /data/adb/omnicam_muse /data/adb/ricoh_pop_x9u; do
        [ -e "$_dir" ] || continue
        _root_rel=${_dir#/data}
        if grep -qF " $_dir/" /proc/self/mounts 2>/dev/null || \
           grep -qF " $_dir/" /proc/self/mountinfo 2>/dev/null || \
           grep -qF " $_root_rel/" /proc/self/mountinfo 2>/dev/null; then
            warn "$_dir 仍被挂载，保留到重启后清理"
        else
            safe_rm_rf "$_dir" && say "  已删除 $_dir"
        fi
    done
}

# Legacy module ids from earlier installs. The live module directory ($MODDIR) is skipped:
# in the uninstall.sh context KernelSU removes it right after this script returns, and in
# the action.sh context the caller drops a `remove` marker instead.
remove_module_dirs() {
    for _id in $MODULE_ID omnicam_muse omnicam-muse; do
        for _base in /data/adb/modules /data/adb/modules_update; do
            _dir="$_base/$_id"
            [ "$_dir" = "$MODDIR" ] && continue
            [ -e "$_dir" ] || continue
            # If the recursive delete fails halfway, KernelSU still drops the module at the
            # next boot because of this flag.
            [ -d "$_dir" ] && safe_touch_empty "$_dir/remove"
            if safe_rm_rf "$_dir"; then
                say "  已删除 $_dir"
            else
                warn "无法删除 $_dir（已标记为下次启动移除）"
            fi
        done
        # Newer KernelSU keeps per-module key/value configs outside the module directory.
        safe_rm_rf "/data/adb/ksu/module_configs/$_id" 2>/dev/null
    done
}

remove_mountify_workspace() {
    # The workspace is an in-memory tmpfs. If that cannot be proven, leave it alone: never
    # delete from a real partition.
    if ! mountify_workspace_is_tmpfs; then
        warn "跳过 mountify 工作区：/mnt/vendor 不是 tmpfs"
        return 0
    fi
    for _name in libBasicTonePhoto.so libBasicTonePhotoX9.so libBasicTonePhotoX10.so \
                 libAlgoInterface.so libmsnativefilter.so; do
        _file=/mnt/vendor/mountify/odm/lib64/$_name
        [ -e "$_file" ] && safe_rm_f "$_file"
    done
    for _dir in /mnt/vendor/mountify/*ricoh_pop_x9u* /mnt/vendor/mountify/*omnicam*; do
        [ -e "$_dir" ] && safe_rm_rf "$_dir"
    done
}

# The settings app keeps every switch in one SharedPreferences file, and the
# persist.omnicam.* properties are only a projection of it: at start-up the app rewrites them from
# its own store. Backing up the properties alone therefore loses the setting again on the first
# launch after a reinstall (measured 2026-11-04: soft-light strength 7 came back as 0).
# Copy the store out as well; selfheal.sh puts it back before the app starts.
PREFS_BACKUP_DIR=/sdcard/OmniCam-Muse/app-prefs
backup_app_prefs() {
    _src=/data/data/$APP_PKG/shared_prefs/module_settings.xml
    if [ ! -f "$_src" ]; then
        say "  设置应用暂无偏好文件，跳过备份"
        return 0
    fi
    if mkdir -p "$PREFS_BACKUP_DIR" 2>/dev/null \
        && cp -f "$_src" "$PREFS_BACKUP_DIR/module_settings.xml" 2>/dev/null; then
        say "  已备份设置应用到 $PREFS_BACKUP_DIR"
    else
        warn "设置应用偏好备份失败"
    fi
}

# Deletes every persist.omnicam.* property. `resetprop` ships with KernelSU (and Magisk);
# without it the properties cannot be removed cleanly, so report that instead of leaving an
# empty value behind.
#
# Before deleting, the live values are written to /sdcard/OmniCam-Muse/omnicam-props.txt.
# Those properties are the only place a setting lives once the camera process has to read it
# (different uid, SELinux categories), and this script also pm-uninstalls the settings app, so
# after a reinstall nothing can rebuild them: composition fell back to the on-device engine,
# the gallery palette lost its backend and the natural-mode / soft-light switches came back off.
# selfheal.sh replays this file on the next install (only for properties that are absent, so a
# deliberate change made after the reinstall still wins). Delete the file to start clean.
PROPS_BACKUP=/sdcard/OmniCam-Muse/omnicam-props.txt
clean_props() {
    _keys=$(getprop 2>/dev/null | sed -n 's/^\[\(persist\.omnicam[^]]*\)\].*/\1/p')
    [ -n "$_keys" ] || return 0
    if mkdir -p "${PROPS_BACKUP%/*}" 2>/dev/null; then
        getprop 2>/dev/null | grep '^\[persist\.omnicam' > "$PROPS_BACKUP" 2>/dev/null \
            && say "  已备份 $(wc -l < "$PROPS_BACKUP") 条属性到 $PROPS_BACKUP"
    else
        warn "无法写属性备份，重装后将回到默认值"
    fi
    _resetprop=
    for _candidate in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop; do
        [ -x "$_candidate" ] && { _resetprop=$_candidate; break; }
    done
    if [ -z "$_resetprop" ]; then
        _resetprop=$(command -v resetprop 2>/dev/null)
    fi
    if [ -z "$_resetprop" ]; then
        warn "未找到 resetprop，以下属性未删除：$_keys"
        return 0
    fi
    for _key in $_keys; do
        "$_resetprop" --delete "$_key" >/dev/null 2>&1 || \
            "$_resetprop" -d "$_key" >/dev/null 2>&1 || \
            warn "属性未删除：$_key"
    done
}

# Rewrites a SharedPreferences XML file without the lines matching an ERE pattern, keeping
# the inode, owner, mode and SELinux label the owning app expects.
strip_pref_file() {
    _file=$1
    _pattern=$2
    # This function rewrites a file in place; only app data may ever be touched.
    case "$_file" in
        *"/../"*|*/..) warn "拒绝改写带 .. 的路径：$_file"; return 0 ;;
        /data/user/*|/data/user_de/*) ;;
        *) warn "拒绝改写非应用数据路径：$_file"; return 0 ;;
    esac
    [ -L "$_file" ] && { warn "拒绝改写符号链接：$_file"; return 0; }
    [ -L "${_file%/*}" ] && { warn "拒绝改写符号链接目录：${_file%/*}"; return 0; }
    [ -f "$_file" ] || return 0
    grep -Eq "$_pattern" "$_file" 2>/dev/null || return 0
    _owner=$(stat -c '%u:%g' "$_file" 2>/dev/null)
    _mode=$(stat -c '%a' "$_file" 2>/dev/null)
    _tmp="${_file}.omnicam-uninstall.$$"
    # Never follow a pre-existing symlink planted at the temp path.
    safe_rm_f "$_tmp"
    grep -Ev "$_pattern" "$_file" > "$_tmp" 2>/dev/null || true
    if grep -q '<map' "$_tmp" 2>/dev/null; then
        cat "$_tmp" > "$_file"
        [ -n "$_owner" ] && chown "$_owner" "$_file" 2>/dev/null
        [ -n "$_mode" ] && chmod "$_mode" "$_file" 2>/dev/null
        restorecon "$_file" >/dev/null 2>&1 || true
    else
        warn "跳过无法识别的偏好文件：$_file"
    fi
    safe_rm_f "$_tmp"
}

strip_config_keys() {
    _file=$1
    shift
    _regex=
    for _key in "$@"; do
        [ -n "$_regex" ] && _regex="$_regex|"
        _regex="${_regex}name=\"$_key\""
    done
    [ -n "$_regex" ] && strip_pref_file "$_file" "$_regex"
}

clean_camera_data() {
    _full=$1
    [ -d "$CAM_DATA" ] || return 0
    safe_rm_rf "$CAM_DATA/files/jiege" \
           "$CAM_DATA/files/ricoh_gr" \
           "$CAM_DATA/files/pop_port" \
           "$CAM_DATA/files/fisheye_lens" \
           "$CAM_DATA/files/fisheye_capture" \
           "$CAM_DATA/files/odm/etc/camera/pld_watermark"
    # mode_limit_watermark may also hold stock styles; remove only the realme GR artwork and
    # JSON the module copied in, then drop the directory if nothing else is left.
    _ml="$CAM_DATA/files/mode_limit_watermark"
    if [ -d "$_ml" ]; then
        safe_rm_rf "$_ml"/gr_style_* "$_ml"/realme_gr_* "$_ml"/sketch_* \
               "$_ml"/watermark_camera_realme_gr_* 2>/dev/null
        rmdir "$_ml" 2>/dev/null || true
    fi
    rmdir "$CAM_DATA/files/odm/etc/camera" "$CAM_DATA/files/odm/etc" "$CAM_DATA/files/odm" 2>/dev/null || true
    safe_rm_f "$CAM_DATA/pop_heif.txt"
    safe_rm_rf "$CAM_DATA"/cache/jiege_* "$CAM_DATA"/cache/xpan_live
    if [ "$_full" = full ]; then
        safe_rm_f "$CAM_DATA"/databases/mode_data.db*
    fi
}

# The mode database is rebuilt by the camera from the current feature flags. If a stale one
# still carries the module's tilt-shift row after the module is gone, drop it so the camera
# recreates it with the stock mode list.
reset_mode_db_if_dirty() {
    _db="$CAM_DATA/databases/mode_data.db"
    [ -f "$_db" ] || return 0
    if grep -aq 'tiltShift\|retroCamera' "$_db" 2>/dev/null; then
        safe_rm_f "$_db" "$_db-journal" "$_db-wal" "$_db-shm"
        say "  已重置相机模式数据库"
    fi
}

clean_gallery_data() {
    [ -d "$GAL_DATA" ] || return 0
    # Keep any stock files in this directory; only the GR styles and drawables are ours.
    _styles="$GAL_DATA/files/ricoh_gr_styles"
    if [ -d "$_styles" ]; then
        safe_rm_rf "$_styles"/gr_style_* "$_styles"/realme_gr_* "$_styles"/sketch_* \
               "$_styles"/watermark_camera_realme_gr_* 2>/dev/null
        rmdir "$_styles" 2>/dev/null || true
    fi
    safe_rm_rf "$GAL_DATA/files/jiege_ipu" "$GAL_DATA/files/jiege"
    safe_rm_rf "$GAL_DATA"/cache/jiege_*
}

clean_prefs() {
    for _base in /data/user/0 /data/user_de/0; do
        for _file in "$_base/$CAM_PKG"/shared_prefs/*.xml "$_base/$CAM_PKG"/shared_prefs/*.xml.bak; do
            strip_pref_file "$_file" "$PREF_PATTERN"
        done
        _file="$_base/$CAM_PKG/shared_prefs/override_config_data.xml"
        # shellcheck disable=SC2086
        strip_config_keys "$_file" $OVERRIDE_KEYS
        # shellcheck disable=SC2086
        [ -f "$_file.bak" ] && strip_config_keys "$_file.bak" $OVERRIDE_KEYS
        for _file in "$_base/$GAL_PKG"/shared_prefs/*.xml "$_base/$GAL_PKG"/shared_prefs/*.xml.bak; do
            strip_pref_file "$_file" "$PREF_PATTERN"
        done
        _file="$_base/$GAL_PKG/shared_prefs/business_featureSwitch_config.xml"
        # service.sh inserts this key as true; removing it restores the stock default (false).
        strip_pref_file "$_file" 'name="business_featureSwitch_is_camera_gr_supported"'
    done
}

# The settings app carries the LSPosed registration: LSPosed stores the module's APK path in
# its own database, and a `pm uninstall` + reinstall gives the package a new /data/app/<hash>
# path. Measured on PKJ110 (2026-11-04): after a full uninstall + reinstall, LSPosed still had
# the deleted path, so it loaded no module at all - no hooks, POP crashed on the first frame
# (PopInverseMaskProgram never injected), the inspiration-composition entry and the 哈苏超清
# button were gone. Nothing in the module can repair that without writing LSPosed's SQLite
# database, so by default a full uninstall keeps the APK and only wipes its data; the next
# reinstall is an in-place update and LSPosed keeps working.
# Set OMNICAM_PURGE_APP=1 for a real removal (then LSPosed must be told about the module again).
uninstall_settings_app() {
    APP_STILL_INSTALLED=0
    if ! command -v pm >/dev/null 2>&1; then
        APP_STILL_INSTALLED=1
        warn "找不到 pm，设置应用保持原样：$APP_PKG"
        return 0
    fi
    if ! pm path "$APP_PKG" >/dev/null 2>&1; then
        say "  设置应用未安装，跳过"
        return 0
    fi
    if [ "${OMNICAM_PURGE_APP:-0}" != "1" ]; then
        say "  保留设置应用（保住 LSPosed 登记），只清空它的数据"
        am force-stop "$APP_PKG" >/dev/null 2>&1 || true
        pm clear "$APP_PKG" >/dev/null 2>&1 || warn "清空 $APP_PKG 数据失败"
        # Data cleared, package kept: LSPosed must keep its record for this package.
        APP_STILL_INSTALLED=1
        return 0
    fi
    say "  卸载设置应用 $APP_PKG（OMNICAM_PURGE_APP=1）"
    am force-stop "$APP_PKG" >/dev/null 2>&1 || true
    pm uninstall "$APP_PKG" >/dev/null 2>&1 || \
        pm uninstall --user 0 "$APP_PKG" >/dev/null 2>&1 || true
    if pm path "$APP_PKG" >/dev/null 2>&1; then
        # Do not delete the package directory behind the package manager's back: a half
        # uninstalled app is worse than a retry on the next boot.
        APP_STILL_INSTALLED=1
        warn "设置应用卸载失败，将在重启后再试：$APP_PKG"
        return 0
    fi
    for _dir in "/data/user/0/$APP_PKG" "/data/user_de/0/$APP_PKG" \
                "/data/misc/profiles/cur/0/$APP_PKG" "/data/misc/profiles/ref/$APP_PKG"; do
        safe_rm_rf "$_dir" 2>/dev/null
    done
    for _dir in /data/app/*/"$APP_PKG"-*; do
        [ -e "$_dir" ] && safe_rm_rf "$_dir" 2>/dev/null
    done
}

# LSPosed keeps a new-style module's SharedPreferences under
# /data/misc/<random>/prefs/<package> (or prefs<userId> for non-zero users), not in the app's
# own data dir. Uninstalling the APK normally makes LSPosed delete it via removeModule(), but
# remove the package directory here as well in case that broadcast was missed.
clean_lsposed_module_data() {
    for _dir in /data/misc/*/prefs /data/misc/*/prefs[0-9]*; do
        [ -e "$_dir/$APP_PKG" ] || continue
        safe_rm_rf "$_dir/$APP_PKG" 2>/dev/null && say "  已删除 LSPosed 偏好 $_dir/$APP_PKG"
    done
    for _base in /data/adb/lspd/prefs /data/adb/lsposed/prefs; do
        [ -e "$_base/$APP_PKG" ] && safe_rm_rf "$_base/$APP_PKG" 2>/dev/null
    done
}

# Dumps written by the capture debugging switches; they live inside the camera's external
# data dir, not in DCIM.
clean_external_data() {
    safe_rm_rf "/sdcard/Android/data/$CAM_PKG/files/muse" 2>/dev/null
    safe_rm_rf /sdcard/omnicam-diag-* 2>/dev/null
}

# Scratch files used by the module's repair/diagnostic scripts.
clean_tmp_files() {
    safe_rm_rf /data/local/tmp/omnicam-muse-module.apk \
           /data/local/tmp/omnicam-diag.log \
           /data/local/tmp/x8u-fixcfg \
           /data/local/tmp/x8u-fixlut \
           /data/local/tmp/x8u-repair-backup 2>/dev/null
    safe_rm_f /data/local/tmp/x8u-fix-pop.log \
          /data/local/tmp/x8u-fix-vibrant.log \
          /data/local/tmp/x8u-repair*.log \
          /data/local/tmp/omnicam-*.log \
          /data/local/tmp/omnicam-unmount.* 2>/dev/null
}

# --- module context entry point ---------------------------------------------

# Full cleanup for a genuine module removal (uninstall.sh) or the explicit action.sh run.
# Everything the module ever wrote outside its own directory is removed: binds, merged
# trees, staged app data, prefs, props, the settings app and its LSPosed data. The live
# module directory itself is left to KernelSU (uninstall.sh) or the `remove` marker
# (action.sh); remove_module_dirs only touches legacy/foreign copies.
cleanup_module_full() {
    say "- 停止相机、相册和设置应用"
    force_stop_apps
    kill_logcat_capture

    say "- 卸载模块挂载"
    unmount_ours
    # Run a second pass for per-file binds that became visible after the first scan.
    unmount_ours

    say "- 卸载设置应用"
    backup_app_prefs
    uninstall_settings_app
    [ "$APP_STILL_INSTALLED" = "0" ] && clean_lsposed_module_data

    say "- 清理相机数据"
    clean_camera_data full
    reset_mode_db_if_dirty

    say "- 清理相册数据"
    clean_gallery_data

    say "- 恢复相机与相册偏好"
    clean_prefs

    say "- 清理调试产物与系统属性"
    clean_external_data
    clean_tmp_files
    clean_props

    say "- 清理 /data/adb 残留"
    remove_merged_trees
    remove_mountify_workspace
    remove_module_dirs
    safe_rm_f "$UPDATE_MARKER" 2>/dev/null

    say "- 完成（模块目录由 KernelSU 移除；相机与相册下次启动恢复原厂状态）"
}
