#!/system/bin/sh
# KernelSU 安装时执行：解包完立刻修一遍，不用等重启。
# 本机是临时 root、从不重启，所以这一步是主要生效路径。

chmod 0755 "$MODPATH"/*.sh 2>/dev/null

ui_print "- 正在把 /odm/lib64 里被卸掉的库补回来"
sh "$MODPATH/apply.sh" > /dev/null 2>&1

# apply.sh 已经把过程和结果写进这个文件，逐行转给安装界面。
if [ -f /data/local/tmp/omnicam_haptics_fix.log ]; then
    while IFS= read -r LINE; do
        ui_print "  $LINE"
    done < /data/local/tmp/omnicam_haptics_fix.log
fi

ui_print "- 安装完成。删掉本模块不会撤销已经做过的挂载（重启后自然恢复）。"
