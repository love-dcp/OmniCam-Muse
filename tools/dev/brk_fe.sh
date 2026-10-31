#!/system/bin/sh
# 在 frontend 库任意 vaddr 处写入 brk #0x3e8（临时断点，provider 重启即失效）
# 用法: brk_fe.sh <vaddr-hex>    例如 brk_fe.sh 0x64e70
V=$1
PID=$(ps -A -o PID,NAME | grep 'provider-service' | awk '{print $1}' | head -1)
if [ -z "$PID" ]; then echo "NO_PROVIDER"; exit 1; fi
BASE=$(grep libcamxhwlfrontend /proc/$PID/maps | head -1 | cut -d- -f1)
if [ -z "$BASE" ]; then echo "NO_LIB_LOADED pid=$PID"; exit 2; fi
ADDR=$(awk -v h="$BASE" -v o="$V" 'BEGIN{
  n=0; h=tolower(h); sub(/^0x/,"",h);
  for(i=1;i<=length(h);i++){ c=substr(h,i,1); v=index("0123456789abcdef",c)-1; n=n*16+v }
  o=tolower(o); sub(/^0x/,"",o); m=0;
  for(i=1;i<=length(o);i++){ c=substr(o,i,1); v=index("0123456789abcdef",c)-1; m=m*16+v }
  printf "%.0f", n+m }')
echo "pid=$PID base=0x$BASE vaddr=$V addr=$ADDR"
printf '\xe8\x03\x20\xd4' | dd of=/proc/$PID/mem bs=1 seek=$ADDR conv=notrunc 2>/dev/null
echo "write_rc=$?"
echo -n "now: "
dd if=/proc/$PID/mem bs=1 skip=$ADDR count=4 2>/dev/null | od -An -tx1
