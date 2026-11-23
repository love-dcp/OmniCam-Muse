"""Recovery bootstrap for the camera spec: replays every entry of the live
assets/symbols.json over C after the curated sources run.

Why: the 2026-11-23 session (r505-r511, camera 7.006.317 migration) verified a
full fingerprint set against the on-device 317 build (PortReport: 341 ok, 1
expected-missing) and mined several new entries straight from the device dexes
(work/fp_strings.py). Those live in assets/symbols.json only. A sym.py run used
to wipe them (it did once - restored from the session transcript replay, see
work/symbols_replay/).

Policy: assets/symbols.json is the authority for every key it defines - it is
what the runtime resolver actually verified - so this file OVERWRITES C with
the live JSON entries. Curated sources keep contributing keys the live JSON
does not have yet (e.g. not-yet-rebuilt experiments) and remain the place to
author NEW entries; once a build verifies them on device, the JSON carries
them forward automatically. Do not re-add short-name ("P"/"b"/"G") style
definitions here - the runtime rule is fingerprints only.

The circularity (generator reads its own output) is intentional and benign.
"""

import json as _json
import os as _os

_live = _os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "symbols.json")
if _os.path.isfile(_live):
    with open(_live, encoding="utf-8") as _f:
        for _k, _v in _json.load(_f).get("camera", {}).items():
            C[_k] = _v
