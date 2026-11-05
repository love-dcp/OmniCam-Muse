#!/system/bin/sh
# 开机后再修一遍。
#
# 为什么不在 post-fs-data 里做：元模块（mountify）的 overlay 是在 post-fs-data 阶段
# 建起来的，早于/晚于本模块不确定。在它之前 bind 会被它盖住，所以等系统起来、
# 所有挂载都定型之后再动手最稳。服务脚本这里已经足够早，HAL 重启也在本脚本里做。

MODDIR=${0%/*}

# 等开机完成，最多等 120 秒，绝不死等。
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
    sleep 1
    i=$((i + 1))
done

# 再等几秒让各家 service.sh 把挂载补齐，避免两边打架。
sleep 3

sh "$MODDIR/apply.sh" > /dev/null 2>&1
