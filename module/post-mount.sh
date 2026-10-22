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
    bind_library "$MODDIR/system/odm/lib64/libBasicTonePhotoX9.so"  libBasicTonePhotoX9.so
    bind_library "$MODDIR/system/odm/lib64/libBasicTonePhotoX10.so" libBasicTonePhotoX10.so
    # These three replace stock files that already exist on X9U, so each bind has a target.
    bind_library "$MODDIR/payload/lib64/libBasicTonePhoto.so" libBasicTonePhoto.so
    bind_library "$MODDIR/payload/lib64/libAlgoInterface.so"  libAlgoInterface.so
    bind_library "$MODDIR/payload/lib64/libmsnativefilter.so" libmsnativefilter.so
else
    log "PKJ110: X8U keeps AlgoInterface + msnativefilter, and its own BasicTone backend"
    # metamount.sh has ALREADY overlaid /odm/lib64 from /mnt/vendor/mountify/odm/lib64, and that
    # workspace copy is where the X9U backends come from. No module script can stop the overlay
    # from being created -- but this phase runs immediately afterwards as root, so it can be
    # detached again here, and the workspace purged.
    #
    # This is not cosmetic. The device only supports a SOFT reboot: root comes from a CVE exploit
    # (com.pkj110.rootapp) that dies on a hard reboot. A soft reboot restarts userspace but NOT
    # the kernel, so these scripts run again while metamount leaves the existing overlay alone --
    # it stays frozen on whatever /mnt/vendor/mountify/odm/lib64 held the first time it was built,
    # long before the module dropped the X9U libraries from system/. Detaching it here is what
    # makes the fix reach the device at all, with no hard reboot.
    n=0
    while grep -q " /odm/lib64 " /proc/self/mounts 2>/dev/null; do
        umount -l /odm/lib64 2>>"$LOG" || umount /odm/lib64 2>>"$LOG" || break
        n=$((n + 1))
        [ "$n" -ge 4 ] && break
    done
    log "PKJ110: detached $n /odm/lib64 overlay(s)"
    for L in libBasicTonePhoto.so libAlgoInterface.so libmsnativefilter.so; do
        if [ -f "/mnt/vendor/mountify/odm/lib64/$L" ]; then
            rm -f "/mnt/vendor/mountify/odm/lib64/$L" 2>>"$LOG" && log "purge workspace $L"
        fi
    done
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
    if grep -q " /odm/lib64/libBasicTonePhoto.so " /proc/self/mounts 2>/dev/null; then
        umount /odm/lib64/libBasicTonePhoto.so 2>>"$LOG" \
            && log "PKJ110: detached stale libBasicTonePhoto.so bind"
    fi
    log "PKJ110: basic-tone is stock $(md5sum /odm/lib64/libBasicTonePhoto.so 2>/dev/null | cut -d' ' -f1) (expect 8651fa24 = sha256 a93f40de)"

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

log "end profile=$PROFILE"
