#!/system/bin/sh
# =============================================================================
# OmniCam 冲突修复 —— 把别的模块放进 /odm/lib64 的库补回来
#
# 为什么会坏：
#   mountify 这类元模块给每个分区目录只建**一块** overlay，内容是「所有模块的
#   system/<分区>/ 合并结果」。/odm/lib64 就是一块。只要有任何一个模块把这块
#   overlay 整体 umount 掉（OmniCam Muse 在 r155 及以前的 PKJ110 分支就是这么做的，
#   目的是不让 X9U 的后端库漏到 X8U），同一块里的其它文件也会一起消失 ——
#   最常见的就是「欧加真马达振动增强」那个改过路径的 liboplusvibratoreffect.so。
#   它的网页控制台靠 md5 比对判断挂载状态，于是显示「挂载失败」，振感增强没了。
#
#   更麻烦的是：元模块跑完会把自己临时的 workspace 摘掉（/mnt/vendor/mountify 不可达），
#   所以事后既不能重挂 overlay，也不能靠删 workspace 里的文件只去掉自己的那部分。
#
# 本脚本做的事（不碰 overlay 本身，只在文件级别补）：
#   扫 /data/adb/modules/*/system/odm/lib64/*，
#   与 /odm/lib64/ 里的同名文件比 md5；
#   不一致就用 bind 挂载把模块那一份顶上去，标签照抄被替换的原厂文件。
#
# 幂等：已经一致的文件直接跳过，可以反复执行。
# =============================================================================
set -u

LOG=/data/local/tmp/omnicam_haptics_fix.log
: > "$LOG"
say() { echo "$1"; echo "$1" >> "$LOG"; }

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    say "需要 root 权限运行"
    exit 1
fi

say "== 扫描 /odm/lib64 里由其它模块提供的库 =="

CHECKED=0
FIXED=0
FAILED=0
VIB_FIXED=0

for D in /data/adb/modules/*/system/odm/lib64; do
    [ -d "$D" ] || continue
    MOD_ROOT=${D%/system/odm/lib64}
    MOD_NAME=${MOD_ROOT##*/}

    if [ -f "$MOD_ROOT/disable" ] || [ -f "$MOD_ROOT/remove" ]; then
        say "- 跳过 $MOD_NAME（已禁用或待删除）"
        continue
    fi

    for SRC in "$D"/*; do
        [ -f "$SRC" ] || continue
        NAME=${SRC##*/}
        TARGET=/odm/lib64/$NAME

        if [ ! -f "$TARGET" ]; then
            say "- 跳过 $NAME：/odm/lib64 里没有同名文件，无需补（$MOD_NAME）"
            continue
        fi

        CHECKED=$((CHECKED + 1))

        SRC_MD5=$(md5sum "$SRC" 2>/dev/null | cut -d' ' -f1)
        LIVE_MD5=$(md5sum "$TARGET" 2>/dev/null | cut -d' ' -f1)

        if [ -n "$SRC_MD5" ] && [ "$LIVE_MD5" = "$SRC_MD5" ]; then
            say "- $NAME 已是 $MOD_NAME 的版本，无需处理"
            continue
        fi

        chown root:root "$SRC" 2>/dev/null
        chmod 0644 "$SRC" 2>/dev/null
        # 标签照抄被替换的原厂文件：那个上下文是打开它的 HAL 一定认可的。
        chcon --reference="$TARGET" "$SRC" 2>/dev/null \
            || chcon u:object_r:vendor_file:s0 "$SRC" 2>/dev/null

        if mount --bind "$SRC" "$TARGET" 2>>"$LOG"; then
            say "+ 已修复 $TARGET  ←  $MOD_NAME"
            FIXED=$((FIXED + 1))
            [ "$NAME" = "liboplusvibratoreffect.so" ] && VIB_FIXED=1
        else
            say "! 修复失败 $TARGET  ←  $MOD_NAME（bind 被拒绝，详见 $LOG）"
            FAILED=$((FAILED + 1))
        fi
    done
done

# ---------------------------------------------------------------------------
# 换了振动库就得让 HAL 重新加载，否则进程里还是旧的那份代码，感觉不到变化。
# ---------------------------------------------------------------------------
if [ "$VIB_FIXED" = "1" ]; then
    if getprop 2>/dev/null | grep -q "vendor\.oplus\.vibrator"; then
        SVC=vendor.oplus.vibrator
    else
        SVC=vendor.qti.vibrator
    fi
    BEFORE=$(getprop init.svc_debug_pid.$SVC 2>/dev/null)
    setprop ctl.restart "$SVC" 2>/dev/null
    i=0
    while [ $i -lt 10 ]; do
        sleep 1
        AFTER=$(getprop init.svc_debug_pid.$SVC 2>/dev/null)
        [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ] && break
        i=$((i + 1))
    done
    say ""
    say "== 振动 HAL 已重启：$SVC  pid ${BEFORE:-?} → ${AFTER:-?} =="
    say "   （若振感仍未变化，试着调一下振动强度或重启一次手机）"
fi

say ""
say "== 结果：检查 $CHECKED 个文件，修复 $FIXED 个，失败 $FAILED 个 =="
if [ "$FIXED" = "0" ] && [ "$FAILED" = "0" ]; then
    say "   没有需要修的东西 —— 要么已经正常，要么对方的模块没把文件放在 system/odm/lib64/。"
    say "   请让对方模块的控制台页面确认一次状态（它靠 md5 判断）。"
fi
say "   日志：$LOG"

exit 0
