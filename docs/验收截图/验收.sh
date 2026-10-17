#!/bin/bash
# 真机验收脚本：进会话 → 硬判据（最后一条消息末尾在屏上）→ 截图
ADB=/media/wangke/OFFICE/workspace/android-toolchain/sdk/platform-tools/adb
SHOT=/media/wangke/OFFICE/workspace/dsh-companion/docs/验收截图
cd /media/wangke/OFFICE/workspace/dsh-companion

restart() {
  "$ADB" shell am force-stop io.github.westanke.dshcompanion >/dev/null 2>&1
  sleep 1
  "$ADB" shell am start -n io.github.westanke.dshcompanion/.MainActivity >/dev/null 2>&1
  sleep 6
  "$ADB" shell input tap 588 1134   # 进入应用
  sleep 7
}

# 用法：验收.sh <会话列表里的 y 坐标> <标签> <探针词...>
Y=$1; TAG=$2; shift 2
restart
"$ADB" shell input tap 522 "$Y"    # 进会话
sleep 16
"$ADB" exec-out screencap -p > "$SHOT/验收-$TAG.png"
TXT=$("$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml 2>/dev/null)
echo "── $TAG ──"
if [ -z "$TXT" ]; then echo "  ❌ UI dump 失败"; exit 1; fi
# 硬判据：所有探针词都要在屏上
OK=1
for w in "$@"; do
  if echo "$TXT" | grep -q "$w"; then echo "  ✅ 含「$w」"; else echo "  ❌ 缺「$w」"; OK=0; fi
done
# 假判据（仅作参考，不能当通过标准）
if echo "$TXT" | grep -qE '跳到|最新'; then echo "  ℹ️  「跳到底部」按钮仍显示（这条不是判据）"; fi
[ $OK -eq 1 ] && echo "  ★ 通过" || echo "  ✗ 未通过"
