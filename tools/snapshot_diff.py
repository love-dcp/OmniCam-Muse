#!/usr/bin/env python3
"""Compare two OmniCam Muse module snapshots produced by tools/omnicam_snapshot.sh.

The snapshot is a plain text report with sections marked by
``########## name ##########``. Sections that are md5 listings get a per-file
added / removed / changed report; everything else gets a unified diff.

    python tools/snapshot_diff.py before.txt after.txt [--full]

Exit code is 0 when the two snapshots match, 1 when they differ. ``--full``
also prints the unified diff for md5 sections (file by file is usually enough).
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys

SECTION = re.compile(r"^########## (.+?) ##########$")
MD5_LINE = re.compile(r"^([0-9a-f]{32})  (.+)$")


def parse(path):
    sections = {}
    order = []
    current = None
    for raw in open(path, "r", encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        m = SECTION.match(line)
        if m:
            current = m.group(1)
            order.append(current)
            sections.setdefault(current, [])
            continue
        if current is not None:
            sections[current].append(line)
    return sections, order


def md5_map(lines):
    out = {}
    for line in lines:
        m = MD5_LINE.match(line)
        if m:
            out[m.group(2)] = m.group(1)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--full", action="store_true",
                    help="print the unified diff of md5 sections too")
    ap.add_argument("--max", type=int, default=60,
                    help="max entries listed per category (default 60)")
    args = ap.parse_args()

    a, a_order = parse(args.before)
    b, b_order = parse(args.after)

    names = []
    for name in a_order + b_order:
        if name not in names:
            names.append(name)

    total = 0
    for name in names:
        la = a.get(name)
        lb = b.get(name)
        if la is None:
            print(f"\n=== {name}: only in {args.after} ===")
            total += len(lb)
            continue
        if lb is None:
            print(f"\n=== {name}: only in {args.before} ===")
            total += len(la)
            continue

        ma, mb = md5_map(la), md5_map(lb)
        if ma or mb:
            added = sorted(set(mb) - set(ma))
            removed = sorted(set(ma) - set(mb))
            changed = sorted(p for p in set(ma) & set(mb) if ma[p] != mb[p])
            lost = total + len(added) + len(removed) + len(changed)
            if not (added or removed or changed):
                continue
            print(f"\n=== {name} ===")
            for label, items in (("added", added), ("removed", removed), ("changed", changed)):
                if not items:
                    continue
                print(f"  {label}: {len(items)}")
                for p in items[: args.max]:
                    print(f"    {label[0]}  {p}")
                if len(items) > args.max:
                    print(f"    ... {len(items) - args.max} more")
            total += len(added) + len(removed) + len(changed)
            if args.full:
                for line in difflib.unified_diff(la, lb, args.before, args.after, n=1):
                    print(line)
            continue

        # Plain text section: only report when it actually differs.
        if la == lb:
            continue
        print(f"\n=== {name} (text) ===")
        diff = list(difflib.unified_diff(la, lb, args.before, args.after, lineterm="", n=1))
        for line in diff[:400]:
            print(line)
        if len(diff) > 400:
            print(f"... {len(diff) - 400} more diff lines")
        total += 1

    print("")
    if total == 0:
        print("MATCH: the two snapshots are identical")
        return 0
    print(f"DIFFERENT: {total} change(s).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
