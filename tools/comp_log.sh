#!/bin/sh
# 抓一次「灵感构图」的关键日志，用来判断变焦到底走到哪一步。
#
#   sh tools/comp_log.sh            # 最近 300 行
#   sh tools/comp_log.sh 800        # 最近 800 行
#
# 判读：
#   phase RECT_SHOWN -> ZOOM_TRIGGERED   camera 自己发了变焦（带 j=目标倍率）
#   phase ZOOM_TRIGGERED -> FINISHED     状态机接受了这个倍率
#   zoom target X -> Y                   我们改写了 j（tracker 缺失）
#   zoom converged: i ...                r122 起把 i 顶成 j 让状态收敛
#   zoom applied directly: X             comp.directzoom=1 的兜底生效
#   一条都没有                            大部分 hooks 没装上，先看 AiComposition 的 install 日志
lines=${1:-300}

adb logcat -d 2>/dev/null \
  | grep -E "CompReal|AiComposition|executeZoom|AIComposition|composition" \
  | tail -"$lines"
