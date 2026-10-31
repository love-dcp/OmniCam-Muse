"""X8U (PKJ110) normal-mode 25MP gate relaxation for the HYBRIDRAW 2DOL full-QBC plans.

Generated from ``module/payload_pkj110/pop_decision.json`` (the X8U plaintext decision config with
the POP BasicTone branches, tools/patch_x8u_pop_decision.py). Unlike the X9U unlock
(tools/patch_25mp.py) the X8U 25MP gates all live in the decision tree itself — the device has no
X9U-style lighthouse lux/ISO triggers or APS thermal tables for this path — so a single file is
patched: the two ``APS_FEATURE_HYBRIDRAW_2EXP_FULL_QBC`` plans of ``ALGO_HYBRIDRAW_DAYTIME_2DOL``.

  * plan[2] — the auto path while 超清优先 is OFF (zoom 1.5–2.0x on the main pipeline, 3.7–6.0x on
    the tele pipeline): QBC capture, output still downscaled.
  * plan[3] — the 超清优先 path (full_bining_qbc_open=true; main 1.0–2.0x, tele, ultra-tele
    6.0–13.1x): the real 25MP output.

What is relaxed (removed from both plans' planCondition): the ISO ceiling (currentIso < 2000), the
thermal ceiling (thermalLevel < 9), the scene-motion and motion-capture gates, the 25M capture
counters (quickShotNumWithFullQBC / captureNumsOf25M), the ASD scene veto, blacksoft, the deferred
job amount, the livephoto / filter / group-photo veto, underwater, macro close-up, interval
shooting and the burst-type veto.

The low-light router (r23): relaxing the plans alone is not enough for night shots — two
runtime booleans route the HYBRIDRAW family away from DAYTIME_2DOL in low light:

* ``turboRawNightLimiter`` — true at night; its disjunct inside plain ``ALGO_HYBRIDRAW_DAYTIME``
  (which is evaluated AFTER 2DOL, via nextMode) captures the fall-through. The stock 2DOL
  condition required it == true inside two AND groups.
* ``isSupportHR2Dol`` — the AE-computed "2Dol readout supported right now" flag; false in low
  light. With the NightLimiter leaves gone this is the LAST gate: at night 2DOL falls through
  to plain DAYTIME whose defaultResult is ``APS_FEATURE_HYBRIDRAW_HDR_SR_BINNING`` /
  ``APS_SENSOR_MODE_BINNING`` (12MP) — exactly what the night capture logs showed
  (``case_algo_name: ALGO_HYBRIDRAW_DAYTIME, featureType: 63``). The ``hrDebugMode == 2``
  debug bypass for this flag is dead in MP builds (the property read is gated by a null debug
  pointer), so the flag is removed from the routing condition instead.

Both flags sit as leaves of AND groups (the ``and2`` main path and the ``and`` forceMacro
branch, disjuncts of the top-level OR), where dropping a term only widens the match. The
removal is surgical: no other term is touched and no key is renumbered, so the routing diff
against stock is exactly four lines.

What stays gated (deliberately, mirroring the X9U unlock): lens selection (currentPipeline /
zoomRatio), captureMode, the flash-off requirement (the QBC 2-exposure cadence does not mix with
the flash) and ``full_bining_qbc_open`` itself — the plans keep their switch semantics.

Output: ``module/payload_pkj110/unlock25mp/oplus_camera_preview_decision_config.json`` (plaintext
JSON; staged by service.sh into files/jiege/unlock25mp_pkj and served by unlock25mp.cpp while the
settings switch is on, in place of the POP decision the same hook serves when it is off).
"""
from __future__ import annotations

import json
import copy
import re
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SOURCE = PROJECT / "module/payload_pkj110/pop_decision.json"
OUT_DIR = PROJECT / "module/payload_pkj110/unlock25mp"
DECISION = "oplus_camera_preview_decision_config.json"
TARGET_MODE = "ALGO_HYBRIDRAW_DAYTIME_2DOL"
TARGET_FEATURE = "APS_FEATURE_HYBRIDRAW_2EXP_FULL_QBC"
NIGHT_ROUTER = "turboRawNightLimiter"
# r23: the AE-computed "sensor supports 2Dol readout right now" flag. False in low light
# (and the hrDebugMode==2 debug bypass is dead in MP builds — the property read is gated by a
# null debug pointer), which is the LAST gate that routes night captures away from the relaxed
# FULL_QBC plans into plain ALGO_HYBRIDRAW_DAYTIME (APS_SENSOR_MODE_BINNING, 12MP).
HR2DOL_SUPPORT = "isSupportHR2Dol"
ROUTER_LEAVES = (NIGHT_ROUTER, HR2DOL_SUPPORT)

REMOVE = {
    # ISO / thermal (the advertised "放宽照度、ISO、温度和运动限制")
    "currentIso", "thermalLevel",
    # motion
    "oplusMeta->com.qti.stats_control.motion_detected", "paramsHolder->motion_capture_enable",
    # 25M capture counters (session-scoped, would veto shot #3 onwards)
    "quickShotNumWithFullQBC", "captureNumsOf25M",
    # scene / mode vetoes that ride along
    "oplusMeta->com.oplus.asd.ui.value", "paramsHolder->blacksoft_enable",
    "paramsHolder->defer_job_amount", "paramsHolder->livephoto_enable", "paramsHolder->filter_open",
    "paramsHolder->group_photo_on_off", "paramsHolder->under_water_status",
    "paramsHolder->macro_closeup_enable", "paramsHolder->current_burst_capture_type",
    "paramsHolder->interval_shooting_open",
}
KEEP = {
    # lens selection
    "currentPipeline", "zoomRatio",
    # mode and the 超清优先 switch semantics
    "captureMode", "paramsHolder->full_bining_qbc_open",
    # flash: the QBC 2-exposure cadence does not mix with the flash (kept like the X9U unlock)
    "meta->ANDROID_FLASH_MODE",
}


def _base(key: str) -> str:
    return re.sub(r"\d+$", "", key)


def _strip_group(op: str, body: dict, seen: dict[str, int]) -> dict | None:
    kept: dict = {}
    neutralised = False
    for key, value in body.items():
        if isinstance(value, dict):
            inner = _strip_top({key: value}, seen)
            if inner is None:
                neutralised = True
            else:
                kept.update(inner)
            continue
        name = value[0]
        if name in REMOVE:
            seen[name] = seen.get(name, 0) + 1
            neutralised = True
            continue
        if name not in KEEP:
            raise RuntimeError(f"Unclassified term in the {TARGET_FEATURE} plan: {name}")
        kept[key] = value
    if op == "or" and neutralised:
        return None  # an OR with a removed disjunct can no longer veto
    return kept or None


def _strip_top(node: dict, seen: dict[str, int]) -> dict | None:
    out: dict = {}
    for key, body in node.items():
        op = _base(key)
        if op not in ("and", "or"):
            raise RuntimeError(f"Unexpected top-level operator {key}")
        group = _strip_group(op, body, seen)
        if group is not None:
            out[key] = group
    return out or None


def renumber(node):
    """Keep operator keys contiguous (eq, eq1, eq2, ...) so a sequential reader still sees every term."""
    if not isinstance(node, dict):
        return node
    counts: dict[str, int] = {}
    out: dict = {}
    for key, value in node.items():
        base = _base(key)
        index = counts.get(base, 0)
        counts[base] = index + 1
        out[base if index == 0 else f"{base}{index}"] = renumber(value)
    return out


def strip_router(node: dict, seen: dict[str, int], under_not: bool = False) -> dict | None:
    """Surgically drop the ROUTER_LEAVES (turboRawNightLimiter, isSupportHR2Dol) from the algo's
    outer (routing) condition.

    The stock tree (verified) has each term twice, each a leaf of an AND group; removing an AND
    term only widens the group. A term under a NOT (or inside an OR group, where dropping it
    would tighten) must never happen — abort loudly instead of producing a bad config. Key names
    are left exactly as stock (the stock tree itself has gaps, e.g. ``and2`` without ``and1``,
    so no renumbering is needed for the routing diff to parse).
    """
    out: dict = {}
    for key, body in node.items():
        op = _base(key)
        if op not in ("and", "or", "not"):
            raise RuntimeError(f"Unexpected routing operator {key}")
        kept: dict = {}
        for term_key, value in body.items():
            if isinstance(value, dict):
                inner = strip_router({term_key: value}, seen, under_not or op == "not")
                if inner is None:
                    raise RuntimeError(f"A routing group under {key} became empty")
                kept.update(inner)
                continue
            leaf = value[0]
            if leaf in ROUTER_LEAVES:
                if under_not or op == "not":
                    raise RuntimeError(f"{leaf} found under a NOT")
                if op == "or":
                    raise RuntimeError(f"{leaf} found inside an OR group")
                seen[leaf] = seen.get(leaf, 0) + 1
                continue
            kept[term_key] = value
        if not kept:
            return None  # group emptied
        out[key] = kept
    return out or None


def patch(decision: dict) -> tuple[int, dict[str, int]]:
    """Relax the two FULL_QBC plans and the day/night router in place; returns counts."""
    nodes = [n for n in decision["multiAlgo"]["algo"] if n.get("mode") == TARGET_MODE]
    if len(nodes) != 1:
        raise RuntimeError(f"expected exactly one {TARGET_MODE}, found {len(nodes)}")
    seen: dict[str, int] = {}
    router_seen: dict[str, int] = {}
    stripped_router = strip_router(nodes[0]["condition"], router_seen)
    if stripped_router is None:
        raise RuntimeError("routing condition became empty")
    for leaf in ROUTER_LEAVES:
        if router_seen.get(leaf) != 2:
            raise RuntimeError(f"expected 2 {leaf} routing leaves, removed {router_seen}")
    nodes[0]["condition"] = stripped_router
    seen.update(router_seen)
    patched = 0
    for result in nodes[0].get("algoResult") or []:
        for plan in result.get("plan") or []:
            if (plan.get("planResult") or {}).get("featureType") != TARGET_FEATURE:
                continue
            stripped = _strip_top(plan["planCondition"], seen)
            if stripped is None:
                raise RuntimeError("FULL_QBC plan condition became empty")
            plan["planCondition"] = renumber(stripped)
            patched += 1
    if patched != 2:
        raise RuntimeError(f"expected 2 {TARGET_FEATURE} plans (超清优先 off/on), patched {patched}")
    return patched, seen


def _target_plans(decision: dict):
    nodes = [n for n in decision["multiAlgo"]["algo"] if n.get("mode") == TARGET_MODE]
    if len(nodes) != 1:
        raise RuntimeError(f"expected exactly one {TARGET_MODE}, found {len(nodes)}")
    for result in nodes[0].get("algoResult") or []:
        for plan in result.get("plan") or []:
            if (plan.get("planResult") or {}).get("featureType") == TARGET_FEATURE:
                yield plan


def verify(original: dict, patched: dict) -> None:
    """The output must parse, keep the POP edits and both FULL_QBC plans, and only drop vetoes."""
    text = json.dumps(patched, ensure_ascii=False)
    if text.count(TARGET_FEATURE) != json.dumps(original, ensure_ascii=False).count(TARGET_FEATURE):
        raise RuntimeError("FULL_QBC featureType count changed")
    if text.count("retro_camera_mode") != json.dumps(original, ensure_ascii=False).count("retro_camera_mode"):
        raise RuntimeError("the POP BasicTone branches changed")
    switches = []
    for plan in _target_plans(patched):
        cond = json.dumps(plan["planCondition"])
        for term in REMOVE:
            if term in cond:
                raise RuntimeError(f"term still present in a FULL_QBC plan: {term}")
        if "full_bining_qbc_open" not in cond:
            raise RuntimeError("FULL_QBC plan lost the full_bining_qbc_open term")
        if "currentPipeline" not in cond or "zoomRatio" not in cond:
            raise RuntimeError("FULL_QBC plan lost the lens-selection terms")
        if "meta->ANDROID_FLASH_MODE" not in cond:
            raise RuntimeError("FULL_QBC plan lost the flash gate")
        switches.append("true" if '"paramsHolder->full_bining_qbc_open", true' in cond else "false")
    if sorted(switches) != ["false", "true"]:
        raise RuntimeError(f"expected one plan per full_bining_qbc_open value, got {switches}")
    router = [n for n in patched["multiAlgo"]["algo"] if n.get("mode") == TARGET_MODE][0]["condition"]
    router_text = json.dumps(router, ensure_ascii=False)
    for leaf in ROUTER_LEAVES:
        if leaf in router_text:
            raise RuntimeError(f"the routing condition still routes around via {leaf}")
    for kept in ("zoomRatio", "hrDebugMode", "forceMacroMode", "captureMode"):
        if kept not in router_text:
            raise RuntimeError(f"the routing condition lost a stock term: {kept}")
    if json.dumps(router, ensure_ascii=False).count('"and"') + router_text.count('"and2"') < 2:
        raise RuntimeError("the routing condition lost an AND branch")


def run() -> None:
    original = json.loads(SOURCE.read_bytes().decode("utf-8"), strict=False)
    decision = copy.deepcopy(original)
    patched, seen = patch(decision)
    verify(original, decision)
    OUT_DIR.mkdir(exist_ok=True)
    (OUT_DIR / DECISION).write_bytes((json.dumps(decision, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(f"X8U 25MP unlock: {patched} FULL_QBC plans relaxed, {sum(seen.values())} veto terms removed")
    print(f"  (incl. the {NIGHT_ROUTER} day/night router leaves — low light stays on DAYTIME_2DOL)")
    for name in sorted(seen):
        print(f"  - {name} x{seen[name]}")


if __name__ == "__main__":
    run()
