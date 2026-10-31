#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Patch X8U CameraHWConfiguration.config to enable targeted CamX ASCII logging.

Purpose: diagnose the first-frame ERROR_BUFFER (MCTFE 1047) that happens when
returning from gallery after recording video at ~2.5X with super EIS.

Only 3 lines are touched inside [OemOverrideLogSettings]:
  logWarningMask     -> all groups on
  logInfoMask        -> targeted groups on (buffer / stream / sensor path)
  enableAsciiLogging -> 1

The "key =" prefix of each patched line is reused byte-for-byte from the stock
file; only the value part is regenerated. Everything else stays identical.
"""
import sys

SRC = ".workbuddy/tmp/camhw/CameraHWConfiguration.stock.config"
DST = ".workbuddy/tmp/camhw/CameraHWConfiguration.logpatch.config"

# CamX log group order (from the config header comment), index 0..36
GROUPS = [
    "AFD", "Sensor", "Tracker", "ISP", "PProc", "MemMgr", "Power", "HAL", "JPEG",
    "Stats", "CSL", "App", "Utils", "Sync", "MemSpy", "Format", "Core", "HWL",
    "Chi", "DRQ", "FD", "IQMod", "LRME&CVP", "NCS", "Meta", "AEC", "AWB", "AF",
    "SWP", "Hist", "BPS", "DebugData", "QSAT", "PSM", "StatsNN", "CRE", "OFE",
]
N = len(GROUPS)
assert N == 37, N

# Info-level groups worth logging for a buffer / stream-config failure
INFO_ON = {
    "Sensor", "Tracker", "ISP", "MemMgr", "Stats", "CSL", "Format",
    "Core", "HAL", "DRQ", "AEC", "BPS", "OFE",
}

MASK_WIDTH = 4


def build_values(on_set):
    """Return '   1;  0; ...' — value part only, 37 cells."""
    cells = [(str(1 if g in on_set else 0) + ";").ljust(MASK_WIDTH) for g in GROUPS]
    return "   " + "".join(cells).rstrip()


def split_value(line):
    """Split 'key = value' keeping the prefix ('key =') verbatim."""
    i = line.index("=")
    return line[: i + 1], line[i + 1 :]


def main():
    with open(SRC, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-8")
    if "\r\n" in text:
        sys.exit("source file has CRLF, refusing to patch")

    lines = text.split("\n")
    out = []
    report = {}

    warn_values = build_values(set(GROUPS))
    info_values = build_values(INFO_ON)

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("logWarningMask") and "=" in line:
            prefix, old = split_value(line)
            out.append(prefix + warn_values)
            report["logWarningMask"] = old
            continue
        if stripped.startswith("logInfoMask") and "=" in line:
            prefix, old = split_value(line)
            out.append(prefix + info_values)
            report["logInfoMask"] = old
            continue
        if stripped.startswith("enableAsciiLogging") and "=" in line:
            prefix, old = split_value(line)
            out.append(prefix + " 1;")
            report["enableAsciiLogging"] = old
            continue
        out.append(line)

    missing = {"logWarningMask", "logInfoMask", "enableAsciiLogging"} - set(report)
    if missing:
        sys.exit("missing keys: %s" % missing)

    new_text = "\n".join(out)
    new_lines = new_text.split("\n")

    if len(lines) != len(new_lines):
        sys.exit("line count changed: %d -> %d" % (len(lines), len(new_lines)))
    diff = sum(1 for a, b in zip(lines, new_lines) if a != b)
    if diff != 3:
        sys.exit("unexpected diff count: %d" % diff)

    with open(DST, "wb") as f:
        f.write(new_text.encode("utf-8"))

    print("logInfoMask groups ON:", ", ".join(g for g in GROUPS if g in INFO_ON))
    print("changed lines: %d" % diff)
    print("bytes: %d -> %d" % (len(raw), len(new_text.encode("utf-8"))))
    for k in ("logWarningMask", "logInfoMask", "enableAsciiLogging"):
        old = report[k]
        print("  %-20s old='%s'" % (k, old.strip()[:50]))


if __name__ == "__main__":
    main()
