#!/system/bin/sh
#
# OmniCam Aura - post-mount phase (library replacement).
#
# KernelSU runs the stages in this order inside post-fs-data:
#     post-fs-data.sh  ->  load system.prop  ->  metamount.sh (mounts every module's system/ tree)
#         ->  post-mount.sh
# so this script runs AFTER the overlayfs mount and still before zygote.
#
# That timing is why the three X9U algorithm libraries no longer sit in system/odm/lib64/.
# Anything there is applied by metamount.sh unconditionally, with no chance to key on the device,
# which is exactly what broke Find X8 Ultra. Only libBasicTonePhotoX9/X10.so stay in system/,
# because the overlay is the one mechanism able to *create* a path that does not exist yet.
#
#   PMA110 (Find X9 Ultra) - ISP tuning is com.qti.tuned.lighthouse*.bin.
#       Installs OmniCam's own backends over /odm/lib64, as before.
#
#   PKJ110 (Find X8 Ultra) - ISP tuning is com.qti.tuned.zf*.bin.
#       Replaces exactly ONE library: libBasicTonePhoto.so, bound from the X9U backend
#       (system/odm/lib64/libBasicTonePhotoX10.so) under its stock name. X8U's build of that file
#       carries no palette code at all -- 0 occurrences of "Palette" against the X9U backend's
#       PaletteSCLutMalloc / PaletteSCLutFrameMalloc / m_iPaletteFlag -- so the palette looked
#       right in preview (which uses libCombineLut.so, shipped inside the camera APK) and never
#       reached the shot. Full reasoning, and why the X9U backend is safe here while the X9U
#       *pair* was not, is in README 4.14 and in the block at the end of this branch.
#       X8U's own libAlgoInterface.so (60 MB) and libmsnativefilter.so stay untouched: the
#       black-frame incident was the X9U pair -- AlgoInterface included -- landing under the 8 KB
#       router stub that payload/lib64 puts in that slot, so the stub dlopen'd X10 for X8U
#       hardware and the preview pipeline stalled. Neither the stub nor the X9U AlgoInterface is
#       involved in what happens below.
#
# Do NOT detect with `[ -f /odm/lib64/libBasicTonePhotoX9.so ]`: the module itself ships that file
# and metamount.sh has already overlaid it by the time this phase runs, so it exists on both
# devices. The ISP tuning files are device-only and safe to test.
set -u
MODDIR=${0%/*}
LOG=$MODDIR/mount.log
: > "$LOG"
log() { echo "$1" >> "$LOG"; }

# Where the two X9U BasicTone backends ended up.
#
# Plain KernelSU leaves them at system/odm/lib64/, where metamount merges the module's system/
# tree into /odm. mountify (the metamodule installed on this device) does not merge system/ at
# all: its metainstall.sh runs `mountify_handle_partition odm`, which moves system/odm/* up to
# <module>/odm/* and mounts that as a partition. Reading only the first path silently skipped
# these two libraries after a reinstall through mountify, so resolve it once, here.
ODM_LIB=""
for D in "$MODDIR/system/odm/lib64" "$MODDIR/odm/lib64"; do
    if [ -d "$D" ]; then ODM_LIB=$D; log "odm lib dir: $D"; break; fi
done
[ -n "$ODM_LIB" ] || ODM_LIB="$MODDIR/system/odm/lib64"

getprop_safe() {
    getprop "$1" 2>/dev/null
}

detect_profile() {
    # The model name is NOT in ro.product.device on this platform: PKJ110 reports
    # OP5DD3L1 there, and ro.product.odm.vendor.device / ro.vendor.oplus.device are
    # empty, so a device-based probe silently falls through to the ISP fallback.
    # Verified on PKJ110: ro.product.model=PKJ110, ro.product.device=OP5DD3L1,
    # ro.build.display.id=PKJ110_15.0.1.502(CN01).  Probe model/name first.
    for P in ro.product.vendor.model ro.product.vendor.name \
             ro.product.model ro.product.name \
             ro.product.odm.model ro.product.odm.name \
             ro.product.bootimage.model \
             ro.build.product \
             ro.build.display.id \
             ro.oplus.version.my_manifest \
             ro.product.vendor.device ro.product.device ro.product.odm.device \
             ro.product.odm.vendor.device ro.vendor.oplus.device; do
        V=$(getprop_safe "$P")
        case "$V" in
            *PKJ110*) log "detect: $P=$V"; echo PKJ110; return ;;
            *PMA110*) log "detect: $P=$V"; echo PMA110; return ;;
        esac
    done
    if [ -f /odm/lib64/camera/com.qti.tuned.lighthousemain.bin ]; then
        log "detect: lighthouse ISP tuning present"
        echo PMA110
    elif [ -f /odm/lib64/camera/com.qti.tuned.zfmain.bin ]; then
        log "detect: zf ISP tuning present"
        echo PKJ110
    else
        # Conservative fallback: leaving backends stock only loses features, binding the wrong
        # backend aborts BasicTone for the whole camera.
        log "detect: unknown device, assuming PKJ110 (backends left stock)"
        echo PKJ110
    fi
}

bind_library() {
    SOURCE=$1
    NAME=$2
    TARGET="/odm/lib64/$NAME"
    if [ ! -f "$SOURCE" ]; then log "lib skip $NAME: no source"; return 0; fi
    if [ ! -f "$TARGET" ]; then log "lib skip $NAME: target absent"; return 0; fi
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:same_process_hal_file:s0 "$SOURCE" 2>>"$LOG" || true
    if mount --bind "$SOURCE" "$TARGET" >>"$LOG" 2>&1; then
        log "lib bind ok $TARGET ($(stat -c %s "$SOURCE") B)"
    else
        log "lib bind FAIL $TARGET"
    fi
}

PROFILE=$(detect_profile)
log "begin profile=$PROFILE"

if [ "$PROFILE" = PMA110 ]; then
    # X9/X10 first: metamount.sh created both paths from system/odm/lib64/ a moment ago.
    bind_library "$ODM_LIB/libBasicTonePhotoX9.so"  libBasicTonePhotoX9.so
    bind_library "$ODM_LIB/libBasicTonePhotoX10.so" libBasicTonePhotoX10.so
    # These three replace stock files that already exist on X9U, so each bind has a target.
    bind_library "$MODDIR/payload/lib64/libBasicTonePhoto.so" libBasicTonePhoto.so
    bind_library "$MODDIR/payload/lib64/libAlgoInterface.so"  libAlgoInterface.so
    bind_library "$MODDIR/payload/lib64/libmsnativefilter.so" libmsnativefilter.so
else
    log "PKJ110: X8U keeps AlgoInterface + msnativefilter, and its own BasicTone backend"
    # metamount.sh has ALREADY overlaid /odm/lib64 out of every module's system/odm/lib64/.
    # Do NOT detach that overlay (see "Do not detach /odm/lib64" below).
    for L in libBasicTonePhoto.so libAlgoInterface.so libmsnativefilter.so; do
        log "  $L = $(stat -c %s "/odm/lib64/$L" 2>/dev/null) B  (X8U stock 538400 / 60867488 / 7992584)"
    done

    # X8 Ultra keeps its own BasicTone backend. Do NOT bind the X10 one over it.
    #
    # History (2026-10-14 .. 2026-10-21). This profile used to bind
    # system/odm/lib64/libBasicTonePhotoX10.so over libBasicTonePhoto.so, because X8U's build of
    # that library carries no palette code at all:
    #     "Palette"            0 occurrences      (X10: PaletteSCLutMalloc / PaletteSCLutFrameMalloc)
    #     "SimTool_Palette_*"  0 occurrences
    # so the gallery's colour styles rendered in preview and came back uncoloured in the shot.
    # The swap did fix that -- and silently broke something larger, which went unnoticed until the
    # two libraries were finally compared side by side:
    #
    #   * X10 renamed the loader: loadLut(CamMode) -> loadLut(CamMode, const char*, bool). This is
    #     why the GR / master-colour hook in libaura_native.so never latched on this device: it
    #     looked for the two-argument symbol, and dlsym() matches exactly one string.
    #   * X10 opens "%s/LMTPhotoLut0" on the flat LMT path. X8U ships no LMTPhotoLut* (hence the
    #     "LMTLutFrameMallocDynamic() can't open LMT file!" crashes once worked around by copying
    #     the X10 tables in post-fs-data.sh).
    #   * Above all, master-colour captures stopped honouring the look. Measured on PKJ110 on
    #     2026-10-21, same scene, same look:
    #         X10 backend : meanSat 0.6011  (colour -- the look is gone)
    #         stock       : meanSat 0.0647  (monochrome -- correct)
    #     Unmounting this single mount is the only difference between those two rows.
    #
    # The trade is therefore: X10 backend = gallery palette works, master-colour captures broken;
    # stock backend = master-colour captures work, gallery palette has no implementation.
    # Master colour is this module's headline feature, the palette is secondary, so the stock
    # backend wins (port-x8u/README.md 4.15). The third-party X8U port reached the same conclusion
    # and ships no algorithm-library overlay at all.
    #
    # Detaching a stale bind is still required: metamount.sh overlays /odm/lib64 from its workspace
    # unconditionally on every boot, and a soft reboot re-runs this phase while the old bind is
    # still in place.
    # Runtime palette-backend switch (the file that is lost on a clean reflash):
    #   /odm/lib64/libBasicTonePhoto.so is stock on PKJ110 and has no palette code. X10's
    #   libBasicTonePhotoX10.so does, and binding it there is what made the capture palette work.
    #   The stock branch below detaches that bind because X10 also breaks master-colour captures.
    # Set persist.omnicam.aura.palettebt=1 to choose the palette backend; unset/0 keeps master colour.
    if [ "$(getprop_safe persist.omnicam.aura.palettebt)" = "1" ] \
        && [ -f "$ODM_LIB/libBasicTonePhotoX10.so" ]; then
        if grep -q " /odm/lib64/libBasicTonePhoto.so " /proc/self/mounts 2>/dev/null; then
            umount /odm/lib64/libBasicTonePhoto.so 2>>"$LOG" || true
        fi
        bind_library "$ODM_LIB/libBasicTonePhotoX10.so" libBasicTonePhoto.so
        log "PKJ110: palette backend X10 bound (palette capture ON, master-colour capture trade-off)"
    else
        if grep -q " /odm/lib64/libBasicTonePhoto.so " /proc/self/mounts 2>/dev/null; then
            umount /odm/lib64/libBasicTonePhoto.so 2>>"$LOG" \
                && log "PKJ110: detached stale libBasicTonePhoto.so bind"
        fi
        log "PKJ110: basic-tone is stock $(md5sum /odm/lib64/libBasicTonePhoto.so 2>/dev/null | cut -d' ' -f1) (expect 8651fa24 = sha256 a93f40de)"
    fi

    # POP capture RAW fix (door #4, docs/MODULE.md §32/34): this used to bind the patched
    # libAlgoInterface.so on every boot. A/B on PKJ110 (2026-10-21) says the patch itself is what
    # breaks POP capture, so it is now opt-in only.
    #
    # Background for the patch: X8U stock libAlgoInterface.so keeps the BasicTone "all-zero skip"
    # armed -- basicToneProcessV1 ORs the four master sliders and cbz-jumps past the P010->8-bit
    # conversion when they are all zero, so POP's P010 reaches the encoder unconverted. X9U NOP-ed
    # that cbz at 0x2040688, X8U's copy at 0x1e7ae00 is NOP-ed in payload_pkj110.
    #
    # What the A/B showed: with the patched library bound, every non-inst POP stock -- CCD, 0.6x,
    # the plain stocks -- came back black or magenta-cast (Y mean 0.8 / p50 0 on 0.6x, 117 on CCD
    # 1x), while inst stayed fine because it is re-encoded from an 8-bit Bitmap through the PLD
    # watermark path. logcat said "libHeifEncoderWrapper.so dlopen failed" plus
    # "SnapAlloc: No map for format 0x36": those two tbz do not decide whether BasicTone is
    # appended, they select the HDR pipeline variant, so the SDR shot was handed back as P010
    # (YCBCR_P010) with no encoder left to finish it -- and X8U has no libHeifEncoderWrapper.so
    # anywhere. Unbinding restored every stock immediately, with no other change.
    #
    # The detach below runs unconditionally, before the opt-in test: a soft reboot restarts
    # userspace but NOT the kernel, so a bind made by an older build survives across reboots and
    # would keep the black frames coming even after the module is updated.
    BTFIX_SRC="$MODDIR/payload_pkj110/lib64/libAlgoInterface.so"
    if grep -q " /odm/lib64/libAlgoInterface.so " /proc/self/mounts 2>/dev/null; then
        umount /odm/lib64/libAlgoInterface.so 2>>"$LOG" \
            && log "PKJ110: detached libAlgoInterface.so bind"
    fi
    # Property name is kept short on purpose: PROP_NAME_MAX is 32 including the NUL, so any name
    # longer than 31 characters can never be set and the opt-in would look broken for no reason.
    if [ "$(getprop_safe persist.omnicam.sdrbt)" = "1" ] && [ -f "$BTFIX_SRC" ]; then
        bind_library "$BTFIX_SRC" libAlgoInterface.so
        log "PKJ110: algo-interface now $(md5sum /odm/lib64/libAlgoInterface.so 2>/dev/null | cut -d' ' -f1) (sdrbt experiment ON, POP capture will be broken)"
    else
        log "PKJ110: algo-interface left stock $(sha256sum /odm/lib64/libAlgoInterface.so 2>/dev/null | cut -c1-8) (sdrbt opt-in off)"
    fi
fi

# ---------------------------------------------------------------------------------------------
# Do not detach /odm/lib64, and put back what other modules need.
#
# Until r155 the PKJ110 branch lazily unmounted /odm/lib64 on every run, to keep the X9U
# BasicTone / AlgoInterface pair from landing on X8U under stock names. It worked, and it broke
# OPlusHapticsEnhancer (欧加真马达振动增强) in the process:
#
#   * mountify builds ONE overlay per partition directory, merged from *all* modules at once, so
#     "unmount /odm/lib64" is not "unmount my files" -- it is "unmount everybody's".
#   * That overlay is the only thing that put the vibration module's patched
#     liboplusvibratoreffect.so into /odm/lib64. After the detach the module's own web UI
#     reported 挂载失败 (md5 of /odm/lib64/liboplusvibratoreffect.so != the module's copy) and the
#     enhanced vibration was gone.
#   * metamount.sh unmounts its own tmpfs workspace at the end of its run (`umount -l /mnt/vendor`),
#     so /mnt/vendor/mountify is unreachable afterwards and neither mounting nor purging from
#     there can be redone. Detaching the overlay is therefore permanent until the next boot --
#     and this device only ever gets soft reboots, so it stayed broken.
#
# The leak that motivated the detach no longer exists at the source: system/odm/lib64/ now holds
# only libBasicTonePhotoX9.so and libBasicTonePhotoX10.so, under their own names. An overlay can
# add files but cannot rename one, so it cannot shadow libBasicTonePhoto.so or anything else
# stock. Nothing needs detaching any more.
#
# What remains is the opposite risk: if metamount did not run, or its overlay was lost, third
# party libraries are missing outright. Put them back by hand -- a per-file bind is exactly what
# the overlay would have produced, and it survives anything this module does to /odm/lib64.
restore_foreign_lib64() {
    for SYSDIR in /data/adb/modules/*/system/odm/lib64; do
        [ -d "$SYSDIR" ] || continue
        OTHER=${SYSDIR%/system/odm/lib64}
        [ "$OTHER" = "$MODDIR" ] && continue
        if [ -f "$OTHER/disable" ] || [ -f "$OTHER/remove" ]; then
            log "foreign lib64 skipped (module disabled): ${OTHER##*/}"
            continue
        fi
        for SRC in "$SYSDIR"/*; do
            [ -f "$SRC" ] || continue
            NAME=${SRC##*/}
            TARGET=/odm/lib64/$NAME
            [ -f "$TARGET" ] || continue
            LIVE_MD5=$(md5sum "$TARGET" 2>/dev/null | cut -d' ' -f1)
            SRC_MD5=$(md5sum "$SRC" 2>/dev/null | cut -d' ' -f1)
            if [ -n "$LIVE_MD5" ] && [ "$LIVE_MD5" = "$SRC_MD5" ]; then
                log "foreign lib64 already active: $NAME (${OTHER##*/})"
                continue
            fi
            chown root:root "$SRC"
            chmod 0644 "$SRC"
            # Mirror the label of the stock file being replaced: that context is the one the HAL
            # opening it is known to accept.
            chcon --reference="$TARGET" "$SRC" 2>>"$LOG" \
                || chcon u:object_r:vendor_file:s0 "$SRC" 2>>"$LOG" || true
            if mount --bind "$SRC" "$TARGET" >>"$LOG" 2>&1; then
                log "foreign lib64 restored: $TARGET (${OTHER##*/})"
            else
                log "foreign lib64 bind FAIL: $TARGET (${OTHER##*/})"
            fi
        done
    done
}
restore_foreign_lib64

log "end profile=$PROFILE"
