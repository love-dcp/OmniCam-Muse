"""X8U (PKJ110) 大师模式 JPG MAX：把 50MP 输出的照度门槛降下来。

大师模式选 JPG MAX 时，决定走哪条管线的是 ``ALGO_HYBRIDRAW_MASTER_HQRAW`` 这个节点：它的入口
条件里有一条 ``paramsHolder->raw_value == "jpg_max"``，所以**节点本身不看光线**；真正卡住 50MP
的是节点内部按顺序匹配的 plan：

  * plan[0] ``captureMode == APS_CAPMODE_AI_HIGH_PIXEL`` → QUADBAYER（哈苏超清那条路，不动）
  * plan[1..3] 2.0–3.0x 的 IZOOM 变体（不动）
  * plan[4] ``exposure_time_from_app > 66666666``（>66ms）→ HYBRIDRAW + BINNING，长曝保护
  * plan[5] ``hqRawIsoLimiter==true && hqRawLuxLimiter==true && (hqRawNightLuxLimiter || hqRawDrcGainLimiter)``
            → ``APS_FEATURE_HYBRIDRAW_QUADBAYER``，**这才是 50MP**
  * plan[6] 兜底 → HYBRIDRAW + BINNING（12.5MP）

``hqRaw*Limiter`` 是 AE 侧算出来的"现在这颗传感器能不能做 HQ 全采样"的布尔量，真正的阈值
（remosaicMFNRLuxIdxThresh / remosaicMFNRDrcThresh / remosaicMFNRdarkBoostThresh 之类）在
tuning 里，既没有明文配置可改，也没法从 decision 树外面影响。所以照 25MP 的做法：**在树里把
这个门的叶子摘掉**，让 JPG MAX 直接落到 50MP 那条 plan。

实测（2026-11-01）：只摘 limiter 后室内连拍 3 张，2 张 50MP、1 张 12MP。掉的那张日志是
``[HybridRawPickFrame] cameraMode,sensorMode,captureMode,inputMode:1,1,5,15`` + ``frameNum: 6``
—— 6 帧多帧、binning 读出、无 ``ZSL + PSL force``，正是 plan[4] 的 ``APS_BRACKET_HR_PSL``
产物；而 plan[0..3] 在 1x 都不会命中。所以剩下的唯一门槛就是 **plan[4] 的
``exposure_time_from_app > 66666666``（66ms）快门门限**：环境越暗、AE 要的曝光越长，越容易
被它抢在 50MP 前面命中。

于是这一步把 50MP 的 plan **提到 plan[4] 之前**，并给它自己一个可调的曝光上限
（``--max-exposure-ms``，默认 150ms）。等价于：

    **大师模式 + JPG MAX + 曝光 ≤ 150ms → 50MP**（原来只有 ≤66ms）

保留的东西（故意不动）：
  * plan[4] 的 66ms 门限本身逐字保留 —— 曝光超过我们的新上限时仍然由它回落到 binning，
    只是门槛从 66ms 提到 150ms，手持糊片的底线还在；
  * plan[0]／plan[1..3]：哈苏超清（AI_HIGH_PIXEL）和 2–3x IZOOM 的行为完全保持原样；
  * ``hq_raw``（RAW MAX）路径 —— 新 plan 的条件里钉死 ``raw_value == "jpg_max"``，所以
    RAW MAX 仍然是出厂行为，不会被这条改动带偏。

曝光上限的取值：150ms ≈ 1/6.7s。室内正常灯光下 AE 一般落在 20–120ms，这一档能覆盖绝大多数
"该出 50MP 却没出"的情况；再往上（>150ms）就是真暗光，50MP 四帧合成会明显糊，交回原厂逻辑
更稳。要更激进/更保守就改 ``--max-exposure-ms`` 重跑。
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
DEFAULT_IN = PROJECT / "module/payload_pkj110/pop_decision.json"

TARGET_MODE = "ALGO_HYBRIDRAW_MASTER_HQRAW"
QUADBAYER = "APS_FEATURE_HYBRIDRAW_QUADBAYER"
BINNING_FALLBACK = "APS_FEATURE_HYBRIDRAW"
HQRAW_LIMITERS = ("hqRawIsoLimiter", "hqRawLuxLimiter", "hqRawNightLuxLimiter", "hqRawDrcGainLimiter")

EXPOSURE_LEAF = "paramsHolder->exposure_time_from_app->-1->int64"
SHUTTER_GATE_NS = 66666666  # 原厂 plan[4] 的 66ms 长曝保护
DEFAULT_MAX_EXPOSURE_MS = 150


def new_condition(max_exposure_ns: int) -> dict:
    """50MP plan 的条件：大师模式 + JPG MAX + 曝光不超过上限。不碰 RAW MAX，也不看照度。"""
    return {"and": {
        "eq": ["captureMode", "APS_CAPMODE_MASTER"],
        "eq1": ["paramsHolder->raw_value", "jpg_max"],
        "lt": [EXPOSURE_LEAF, max_exposure_ns],
    }}


def _leaves(node, out: list[str]) -> list[str]:
    if isinstance(node, dict):
        for value in node.values():
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                out.append(value[0])
            else:
                _leaves(value, out)
    elif isinstance(node, list):
        for item in node:
            _leaves(item, out)
    return out


def _node(decision: dict) -> dict:
    nodes = [n for n in decision["multiAlgo"]["algo"] if n.get("mode") == TARGET_MODE]
    if len(nodes) != 1:
        raise RuntimeError(f"expected exactly one {TARGET_MODE}, found {len(nodes)}")
    return nodes[0]


def _plans(node: dict) -> list[dict]:
    plans = []
    for result in node.get("algoResult") or []:
        plans.extend(result.get("plan") or [])
    if not plans:
        raise RuntimeError(f"{TARGET_MODE} has no plans")
    return plans


def _is_shutter_gate(plan: dict) -> bool:
    """plan[4] 是纯快门门限：条件里只有一条 ``exposure_time_from_app > 66ms``。
    注意 plan[1..3] 也带这个比较，但它们还有 zoomRatio 等条件，不能算。"""
    cond = plan.get("planCondition") or {}
    if list(cond) != ["gt"]:
        return False
    operand = cond["gt"]
    return (isinstance(operand, list) and len(operand) == 2
            and operand[0] == EXPOSURE_LEAF and operand[1] == SHUTTER_GATE_NS)


def _shutter_gate_index(plans: list[dict]) -> int:
    hits = [i for i, plan in enumerate(plans) if _is_shutter_gate(plan)]
    if len(hits) != 1:
        raise RuntimeError(f"expected exactly one 66ms shutter gate plan, found {hits}")
    return hits[0]


def patch(decision: dict, max_exposure_ns: int) -> tuple[int, int]:
    node = _node(decision)
    # 注意：必须拿到 result 里那个**真正的** plan 列表来动顺序，_plans() 返回的是副本。
    holders = [result["plan"] for result in node.get("algoResult") or []
               if any(_is_qbc_jpgmax(plan) for plan in result.get("plan") or [])]
    if len(holders) != 1:
        raise RuntimeError(f"expected exactly one algoResult holding the 50MP plan, found {len(holders)}")
    plans = holders[0]

    source = [i for i, plan in enumerate(plans) if _is_qbc_jpgmax(plan)][0]
    if json.dumps(plans[source]["planCondition"]) == json.dumps(new_condition(max_exposure_ns)):
        gate = _shutter_gate_index(plans)
        if source < gate:
            return 0, source  # 已打过

    removed = [leaf for leaf in _leaves(plans[source]["planCondition"], []) if leaf in HQRAW_LIMITERS]
    plan = plans.pop(source)
    plan["planCondition"] = copy.deepcopy(new_condition(max_exposure_ns))

    # 放到 66ms 快门门限之前，否则门限会抢先命中，新阈值形同虚设。
    gate = _shutter_gate_index(plans)
    plans.insert(gate, plan)
    return len(removed), gate


def _is_qbc_jpgmax(plan: dict) -> bool:
    if (plan.get("planResult") or {}).get("featureType") != QUADBAYER:
        return False
    cond = json.dumps(plan.get("planCondition") or {})
    return "jpg_max" in cond or any(leaf in HQRAW_LIMITERS for leaf in _leaves(plan.get("planCondition") or {}, []))


def verify(original: dict, patched: dict, index: int, max_exposure_ns: int) -> None:
    if json.dumps(original["multiAlgo"]["algo"]) == json.dumps(patched["multiAlgo"]["algo"]):
        return  # idempotent no-op
    src, dst = _plans(_node(original)), _plans(_node(patched))
    if len(src) != len(dst):
        raise RuntimeError("the plan count changed")
    if json.dumps(_node(original)["condition"]) != json.dumps(_node(patched)["condition"]):
        raise RuntimeError("the routing condition changed (it must not: it is what admits jpg_max)")

    # 除 50MP 那条之外，其余 plan 必须一条不差、顺序不变。
    others_src = [p for p in src if not _is_qbc_jpgmax(p)]
    others_dst = [p for p in dst if not _is_qbc_jpgmax(p)]
    if json.dumps(others_src, sort_keys=True) != json.dumps(others_dst, sort_keys=True):
        raise RuntimeError("a plan other than the 50MP one changed")

    changed = dst[index]
    if not _is_qbc_jpgmax(changed):
        raise RuntimeError(f"plan[{index}] is not the 50MP plan")
    leaves = _leaves(changed["planCondition"], [])
    for limiter in HQRAW_LIMITERS:
        if limiter in leaves:
            raise RuntimeError(f"plan[{index}] still gates on {limiter}")
    cond = json.dumps(changed["planCondition"])
    if "jpg_max" not in cond:
        raise RuntimeError("the relaxed plan lost the jpg_max scope (it would affect RAW MAX)")
    if "APS_CAPMODE_MASTER" not in cond:
        raise RuntimeError("the relaxed plan lost the master-mode scope")
    if str(max_exposure_ns) not in cond:
        raise RuntimeError("the relaxed plan lost the exposure ceiling")

    # 必须排在 66ms 快门门限之前，否则门限先命中，新阈值不生效。
    gate = _shutter_gate_index(dst)
    if index >= gate:
        raise RuntimeError(f"the 50MP plan sits at {index}, not before the shutter gate at {gate}")
    if (dst[-1].get("planResult") or {}).get("featureType") != BINNING_FALLBACK:
        raise RuntimeError("the binning fallback plan is gone")


def run() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", nargs="?", default=str(DEFAULT_IN),
                        help=f"decision JSON to patch in place (default: {DEFAULT_IN})")
    parser.add_argument("-o", "--out", default=None, help="output path (default: same as source)")
    parser.add_argument("--max-exposure-ms", type=float, default=DEFAULT_MAX_EXPOSURE_MS,
                        help=f"50MP exposure ceiling in ms (default: {DEFAULT_MAX_EXPOSURE_MS})")
    args = parser.parse_args()

    max_ns = int(round(args.max_exposure_ms * 1_000_000))

    source = Path(args.source)
    out = Path(args.out) if args.out else source
    original = json.loads(source.read_bytes().decode("utf-8"), strict=False)
    decision = copy.deepcopy(original)

    removed, index = patch(decision, max_ns)
    verify(original, decision, index, max_ns)
    out.write_bytes((json.dumps(decision, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    changed = json.dumps(_node(original)["algoResult"], sort_keys=True) != json.dumps(_node(decision)["algoResult"], sort_keys=True)
    if changed:
        print(f"JPG MAX 50MP: plan[{index}] of {TARGET_MODE} relaxed, {removed} hqRaw limiter leaves removed")
        print(f"  moved before the {SHUTTER_GATE_NS/1e6:.1f}ms shutter gate")
        print(f"  now: 大师模式 + JPG MAX + 曝光 ≤ {args.max_exposure_ms:g}ms -> APS_FEATURE_HYBRIDRAW_QUADBAYER (50MP)")
        print("  unchanged: RAW MAX (hq_raw), 哈苏超清 (AI_HIGH_PIXEL), 2-3x IZOOM, the shutter gate itself")
    else:
        print("JPG MAX 50MP: already relaxed, nothing to do")
    print(f"  -> {out}")


if __name__ == "__main__":
    run()
