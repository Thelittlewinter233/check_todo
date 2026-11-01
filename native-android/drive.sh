#!/bin/bash
# 分步守卫版 UI 驱动：每步校验目标存在才点击，输出当前屏文本。
ADB="D:/mumu模拟/emulator/MuMuPlayer-12.0/shell/adb.exe"
D="-s 127.0.0.1:16384"

dump() { "$ADB" $D shell "uiautomator dump /sdcard/step.xml && cat /sdcard/step.xml" > /tmp/step.xml 2>/dev/null; wc -c < /tmp/step.xml; }

boundsof() { grep -o "$1" /tmp/step.xml | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1; }

center_tap() {
  local b="$1"
  if [ -z "$b" ]; then echo "SKIP: no match"; return 1; fi
  local L=${b%][*}; L=${L#\[}; local R=${b#*][}; R=${R%\]}
  local X=$(( ( ${L%,*} + ${R%,*} ) / 2 ))
  local Y=$(( ( ${L#*,} + ${R#*,} ) / 2 ))
  echo "tap $X,$Y"
  "$ADB" $D shell input tap $X $Y
}

case "$1" in
  state)
    dump; grep -o 'text="[^"]\{1,44\}"' /tmp/step.xml | head -16
    ;;
  taptext)
    dump; center_tap "$(boundsof "text=\"$2\"[^>]*bounds=\"[^\"]*\"")"
    ;;
  shutter)
    dump; center_tap "$(boundsof 'resource-id="com.android.camera2:id/shutter_button"[^>]*bounds="[^"]*"')"
    ;;
  donereview)
    dump; center_tap "$(boundsof 'content-desc="完成"[^>]*bounds="[^"]*"')"
    ;;
  screencap)
    "$ADB" $D exec-out "screencap -p" > "$2"
    ls -l "$2" | awk '{print $5}'
    ;;
esac
