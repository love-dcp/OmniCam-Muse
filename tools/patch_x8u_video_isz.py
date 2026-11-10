"""X8U (PKJ110) video in-sensor-zoom unlock for the CameraHWConfiguration.config [InsensorZoom] section.

Reverse-engineering basis (2026-10-26, static analysis of /vendor/lib64/hw/camera.qcom.so of the
X8U stock build, see .workbuddy/palette_debug/izoom_decision_fn.txt):

  * ``IsIZoomEnableRange`` (offset 0x9019a4, source line 3403-3475) decides per request whether
    video ISZ applies: 60fps is rejected outright unless the ``isSupport60FpsIzoom`` key is set
    (absent from the stock file -> default 0 -> never); then the current vendor capmode must be
    in the ``VideoIzoomOperationMode`` list (stock: ``0x800B;0x8028``); then the zoom ratio must
    fall into an ``iZoomSettins`` row whose own operation-mode field matches the per-request
    capmode at [request+0xea24]. Rows are dynamically allocated (28 bytes each, no hard cap) and
    filtered at load time against the *global* capmode; each loaded row logs
    ``add izoom range [%f %f] cameraId %d`` (line 3378).
  * The row format on X8U is 9 fields (X9U uses 14, which is why the PMA110 awk refuses to run
    here): ``opmode; RatioMin; RatioMax; phyCameraId; defaultExposure; iZoomId; Lux; DrcGain;
    LowlightExposure``. iZoomId maps to [EISJsonInit] virtual cameras: 5 = main IZOOM,
    7 = tele IZOOM, 8 = ultra-tele IZOOM.
  * Operation modes (from the camera app module/a.java I9()): 0x800B = normal video, 0x8028 =
    EIS-on video, 0x8021/0x830B = FOVC/60fps variants, 0x80AF/0x80CD = master/cinematic video
    variants (ia.k3.a() branch) -- the latter are NOT in the stock VideoIzoomOperationMode list,
    which is why master video never enters video ISZ on X8U.
  * The X9U upstream patch (module/post-fs-data.sh) un-gates Pro-video rows 25-40 by rewriting
    their ISO fields (12/13) from 0/0 to -1/-1. X8U has no Pro rows at all (11 rows, [0]-[10]),
    so the port *adds* rows instead of editing gates.

Patch (all edits inside [InsensorZoom] only, verified structurally):

  1. ``VideoIzoomOperationMode`` gains the extra opmodes (default: the master-video candidates
     0x80AF and 0x80CD; pass --opmodes to override once the runtime log confirms the exact mode).
  2. New ``iZoomSettins`` rows are appended for each extra opmode, mirroring the stock video
     rows but with the lux gate cleared (Lux=0, LowlightExposure=0, like stock row [8] which is
     the only ungated video row):
       - main cam    (camId 0, iZoomId 5) at RatioMin..RatioMax   (default 2.0..3.0)
       - ultra-tele  (camId 4, iZoomId 8)                        (default 5.5..30.0)
       - tele        (camId 3, iZoomId 7)                        (default off; --add-tele)
     ``--main-only`` omits the ultra-tele (and optional tele) rows. The shipped X8U profile uses
     this because 5.5x ultra-tele ISZ can make the professional-video session fail with MCTFE
     1047; 2x--3x main-camera ISZ is the validated first-release scope.
  3. Optional ``--widen-utele`` rewrites RatioMin of the stock utele video rows [7]/[9] from
     12.0 down to 5.5 so 6X video reaches ISZ in the stock modes too.
  4. Optional ``--fps60`` adds ``isSupport60FpsIzoom = TRUE`` so 60fps video can use ISZ.

The HAL re-reads the config when the camera provider restarts, so the patched file must be
bind-mounted over /odm/etc/camera/CameraHWConfiguration.config with the vendor_configs_file
SELinux label (module bind_file() already does the chcon; the generated hot-mount script
reproduces it for manual testing without a reboot).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SECTION = "[InsensorZoom]"
ROW_RE = re.compile(r"^([ \t]*)iZoomSettins\[(\d+)\][ \t]*=[ \t]*(.*)$")
FIELDS_RE = re.compile(r"^\s*(0x[0-9A-Fa-f]+|\d+(?:\.\d+)?)\s*$")

# [EISJsonInit] virtual izoom cameras on PKJ110
IZOOM_ID_MAIN, IZOOM_ID_TELE, IZOOM_ID_UTETELE = 5, 7, 8


def parse_row_values(rest: str) -> list[str]:
    parts = [p.strip() for p in rest.split(";")]
    # trailing ';' produces an empty field; rows have exactly 9 values
    if parts and parts[-1] == "":
        parts = parts[:-1]
    return parts


def fmt_row(idx: int, opmode: str, rmin: str, rmax: str, cam: int, izoom_id: int,
            lux: str = "0", drc: str = "4", lowlight: str = "0") -> str:
    exp = "2" if cam == 0 else "1"
    return (f"    iZoomSettins[{idx}]  =   {opmode};        {rmin};"
            f"         {rmax};               {cam};               {exp};"
            f"                       {izoom_id};                 {lux};    {drc};          {lowlight}")


def patch_logmask(text: str) -> str:
    """Set group 25 (AEC: the video-izoom decision log group) to 1 in BOTH logInfoMask
    and logVerboseMask rows of [OemOverrideLogSettings].

    The OPLUS settings manager (libcamxsettingsmanager.so OverrideLogSettingsAtConfigureFile)
    force-overrides the effective masks with these rows at provider init, so the
    persist.vendor.camera.log*Mask properties have no effect on the X8U MP build -- the
    only way to see the IsIZoomEnableRange decision logs is to edit these rows (verified
    2026-10-26: stock logInfoMask bits 1/7/16/36 == runtime override 0x1000010082; the
    decision logs are "[ VERB]" level templates, so logVerboseMask[25] is the one that
    actually gates them).
    """
    for row in ("logInfoMask", "logVerboseMask"):
        m = re.search(rf"(?m)^([ \t]*{row}[ \t]*=[ \t]*)([^\n]+)$", text)
        if not m:
            sys.exit(f"no {row} row - wrong stock file?")
        vals = [v.strip() for v in m.group(2).rstrip(";").split(";")]
        if len(vals) != 37:
            sys.exit(f"{row} has {len(vals)} groups, expected 37")
        vals[25] = "1"
        text = text.replace(m.group(0), m.group(1) + ";".join(vals) + ";", 1)
    return text


def patch_ascii_log(text: str) -> str:
    """Set enableAsciiLogging = 1 in [OemOverrideLogSettings].

    THE critical log gate on the X8U MP build (found 2026-10-26): the stock file has
    ``enableAsciiLogging = 0``, and libcamxsettingsmanager.so applies it at provider
    init (line 314, "enableAsciiLogging is 0x1, override to 0x0") AFTER printing the
    mask overrides -- which silences every CamX-core INFO/VERB/WARN log from then on
    (ALWAYS_ON/ERROR still print; the chiLog* ChiX layer is unaffected). This is why
    the IsIZoomEnableRange decision logs never showed up even with both masks open.
    """
    m = re.search(r"(?m)^([ \t]*enableAsciiLogging[ \t]*=[ \t]*)([^\n]+)$", text)
    if not m:
        sys.exit("no enableAsciiLogging row - wrong stock file?")
    text = text.replace(m.group(0), m.group(1) + "1;", 1)
    return text


def patch_logic_camid(text: str, camid: str) -> str:
    """Add ``VideoIzoomEnableLogicCamId`` to [InsensorZoom].

    Read by AECStatsProcessor::InitIzoomSettings (camera.qcom.so 0x909068, key from the
    [InsensorZoom] section, default -1, stored at this+0xac). Absent from the X8U stock
    file; likely the per-logical-camera master switch for the video-izoom graph. X8U
    logical camera id 0 = rear. (Exact consumer semantics not fully reverse-engineered;
    validate empirically with the decision logs once ascii logging is on.)
    """
    m = re.search(r"(^|\n)(\[InsensorZoom\]\n)(.*?)(?=\n\[|\Z)", text, re.S)
    if not m:
        sys.exit("no [InsensorZoom] section")
    body = m.group(3)
    if re.search(r"(?m)^[ \t]*VideoIzoomEnableLogicCamId[ \t]*=", body):
        return text  # already present
    anchor = re.search(r"(?m)^([ \t]*)VideoIzoomOperationMode[ \t]*=", body)
    if not anchor:
        sys.exit("no VideoIzoomOperationMode key to anchor the new row")
    new_body = body.replace(
        anchor.group(0),
        f"{anchor.group(1)}VideoIzoomEnableLogicCamId = {camid}\n" + anchor.group(0), 1)
    return text[:m.start(3)] + new_body + text[m.end(3):]


def patch(text: str, extra_opmodes: list[str], add_tele: bool, add_utele: bool,
          widen_utele: bool,
          fps60: bool, main_range: tuple[str, str], utele_range: tuple[str, str],
          tele_range: tuple[str, str], log_izoom: bool) -> str:
    # split out the [InsensorZoom] section
    m = re.search(r"(^|\n)(\[InsensorZoom\]\n)(.*?)(?=\n\[|\Z)", text, re.S)
    if not m:
        sys.exit("stock config has no [InsensorZoom] section")
    body = m.group(3)

    # --- VideoIzoomOperationMode -------------------------------------------
    mm = re.search(r"^([ \t]*)VideoIzoomOperationMode[ \t]*=[ \t]*([^\n]+)", body, re.M)
    if not mm:
        sys.exit("stock config has no VideoIzoomOperationMode key")
    stock_modes = [v.strip() for v in mm.group(2).strip().split(";") if v.strip()]
    new_modes = stock_modes[:]
    for op in extra_opmodes:
        if op not in new_modes:
            new_modes.append(op)
    body = body.replace(mm.group(0), f"{mm.group(1)}VideoIzoomOperationMode    = {';'.join(new_modes)};")

    # --- stock row inventory -------------------------------------------------
    rows: dict[int, tuple[int, str]] = {}
    for line in body.split("\n"):
        rm = ROW_RE.match(line)
        if rm:
            rows[int(rm.group(2))] = (len(parse_row_values(rm.group(3))), rm.group(3))
    if not rows:
        sys.exit("no iZoomSettins rows found")
    for idx, (nf, _) in rows.items():
        if nf != 9:
            sys.exit(f"row [{idx}] has {nf} fields, expected 9 - wrong stock file?")
    max_idx = max(rows)

    # --- optional: widen stock utele video rows [7]/[9] ----------------------
    if widen_utele:
        for idx in (7, 9):
            vals = parse_row_values(rows[idx][1])
            if float(vals[3]) != 4:
                sys.exit(f"row [{idx}] phyCameraId {vals[3]}, expected 4 - refusing to edit")
            vals[1] = utele_range[0]
            # (?m) line-anchored, skip field 1 (opmode), rewrite field 2 (RatioMin);
            # commented-out rows ("#iZoomSettins[7] = ...") do not match the anchor
            body, n = re.subn(
                rf"(?m)^([ \t]*iZoomSettins\[{idx}\][ \t]*=[ \t]*[^;\n]+;)[ \t]*[^;\n]+;",
                rf"\g<1>         {utele_range[0]};",
                body, count=1)
            if n != 1:
                sys.exit(f"failed to rewrite RatioMin of stock row [{idx}]")

    # --- append rows for the extra opmodes -----------------------------------
    new_lines = ["",
                 "    # --- patched video ISZ rows (tools/patch_x8u_video_isz.py) ---"]
    idx = max_idx
    for op in extra_opmodes:
        idx += 1
        new_lines.append(fmt_row(idx, op, main_range[0], main_range[1], 0, IZOOM_ID_MAIN))
        if add_utele:
            idx += 1
            new_lines.append(
                fmt_row(idx, op, utele_range[0], utele_range[1], 4, IZOOM_ID_UTETELE))
        if add_tele:
            idx += 1
            new_lines.append(fmt_row(idx, op, tele_range[0], tele_range[1], 3, IZOOM_ID_TELE))
    body = body.rstrip("\n") + "\n" + "\n".join(new_lines) + "\n"

    # --- optional 60fps support -----------------------------------------------
    if fps60:
        mm = re.search(r"^([ \t]*)VideoIzoomEnableCamId[ \t]*=", body, re.M)
        anchor = mm.group(0) if mm else "AecStateSwitchStableCheckCount"
        body = body.replace(anchor, "    isSupport60FpsIzoom = TRUE\n" + anchor, 1)

    text = text[:m.start(3)] + body + text[m.end(3):]

    # --- optional: enable the izoom decision log group -------------------------
    if log_izoom:
        text = patch_logmask(text)

    return text


def patch_full(text: str, logic_camid: str | None, ascii_log: bool, **kw) -> str:
    text = patch(text, **kw)
    if logic_camid is not None:
        text = patch_logic_camid(text, logic_camid)
    if ascii_log:
        text = patch_ascii_log(text)
    return text


def verify(text: str, extra_opmodes: list[str], expect_rows: int) -> None:
    m = re.search(r"\[InsensorZoom\]\n(.*?)(?=\n\[|\Z)", text, re.S)
    body = m.group(1)
    vm = re.search(r"VideoIzoomOperationMode[ \t]*=[ \t]*([^\n]+)", body)
    modes = [v.strip() for v in vm.group(1).strip().split(";") if v.strip()]
    for op in extra_opmodes:
        assert op in modes, f"{op} missing from VideoIzoomOperationMode"
    rows = [ROW_RE.match(l) for l in body.split("\n")]
    rows = [r for r in rows if r]
    for r in rows:
        vals = parse_row_values(r.group(3))
        assert len(vals) == 9, f"row [{r.group(2)}] has {len(vals)} fields"
        for v in vals:
            assert FIELDS_RE.match(v), f"row [{r.group(2)}] bad value {v!r}"
        assert re.match(r"0x[0-9A-Fa-f]+$", vals[0]), \
            f"row [{r.group(2)}] field 1 is not an opmode: {vals[0]!r} (field shift bug?)"
    n_patched = sum(1 for r in rows if parse_row_values(r.group(3))[0] in extra_opmodes)
    assert n_patched == expect_rows, f"expected {expect_rows} new rows, found {n_patched}"
    print(f"verify OK: {len(rows)} rows total, {n_patched} patched, modes={modes}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("stock", type=Path, help="stock CameraHWConfiguration.config (live or pulled copy)")
    ap.add_argument("-o", "--out", type=Path, required=True, help="patched output path")
    ap.add_argument("--opmodes", default="0x80AF;0x80CD",
                    help="opmodes to append to VideoIzoomOperationMode (';' or ',' separated)")
    ap.add_argument("--add-tele", action="store_true", help="also add tele (camId 3) rows")
    ap.add_argument("--main-only", action="store_true",
                    help="only add main-camera rows; omit ultra-tele and tele rows")
    ap.add_argument("--no-utele", action="store_true",
                    help="omit ultra-tele (camId 4) rows while retaining main and --add-tele rows")
    ap.add_argument("--widen-utele", action="store_true",
                    help="lower stock utele video rows [7]/[9] RatioMin to --utele-min")
    ap.add_argument("--fps60", action="store_true", help="add isSupport60FpsIzoom = TRUE")
    ap.add_argument("--log-izoom", action="store_true",
                    help="set logInfoMask group 25 (izoom decision logs) in [OemOverrideLogSettings]")
    ap.add_argument("--ascii-log", action="store_true",
                    help="set enableAsciiLogging = 1 -- without it ALL CamX-core INFO/VERB/WARN "
                         "logs are silenced on the MP build, masks included")
    ap.add_argument("--logic-camid", default=None,
                    help="add VideoIzoomEnableLogicCamId = N to [InsensorZoom] (rear logical cam = 0); "
                         "key is absent from X8U stock, loader default is -1")
    ap.add_argument("--main-min", default="2.0")
    ap.add_argument("--main-max", default="3.0")
    ap.add_argument("--utele-min", default="5.5")
    ap.add_argument("--utele-max", default="30.0")
    ap.add_argument("--tele-min", default="5.0")
    ap.add_argument("--tele-max", default="13.2")
    args = ap.parse_args()

    ops = []
    for op in re.split(r"[;,]", args.opmodes):
        op = op.strip()
        if not op:
            continue
        if op.upper().startswith("0X"):
            ops.append("0x" + op[2:].upper())
        else:
            ops.append(op.upper())
    text = args.stock.read_text(encoding="utf-8", errors="replace")
    patched = patch_full(
        text, args.logic_camid, args.ascii_log,
        extra_opmodes=ops, add_tele=args.add_tele and not args.main_only,
        add_utele=not (args.main_only or args.no_utele), widen_utele=args.widen_utele,
        fps60=args.fps60,
        main_range=(args.main_min, args.main_max),
        utele_range=(args.utele_min, args.utele_max),
        tele_range=(args.tele_min, args.tele_max),
        log_izoom=args.log_izoom)
    per_mode = (1 if args.main_only or args.no_utele else 2) + (
        1 if args.add_tele and not args.main_only else 0)
    verify(patched, ops, per_mode * len(ops))
    # write binary: write_text() on Windows would convert \n to \r\n and the HAL parser
    # sees "\r"-suffixed values -> section silently dropped (stock file is LF-only)
    args.out.write_bytes(patched.encode("utf-8"))
    print(f"written: {args.out}")


if __name__ == "__main__":
    main()
