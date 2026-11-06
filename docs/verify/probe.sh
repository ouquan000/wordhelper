#!/usr/bin/env bash
# 探针脚本：只做「状态读取 / 输入注入 / 截图 / 色差计算」，不含任何判定逻辑。
# 判定阈值与评分规则一律以 docs/test-spec-1.4.1.md 为准，本脚本不参与打分。
#
# 用法：
#   probe.sh state                      # 输出 FRONT / BACK / UNKNOWN
#   probe.sh tap X Y
#   probe.sh swipe X1 Y1 X2 Y2 MS
#   probe.sh swipetap                   # 上滑 670px + 立刻点击（F4 专用，同一条设备端命令）
#   probe.sh shot NAME
#   probe.sh hold [X Y MS NAME]         # 按住取样，产出 NAME_rest/NAME_press/NAME_after.png
#   probe.sh f5diff REST.png PRESS.png  # 输出卡片区域 mean 与 pct_gt6
#   probe.sh record NAME SECONDS        # 录屏到 OUT/NAME.mp4
#   probe.sh reset                      # 冷启动到学习页

set -u
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-/c/Program Files/ASUS/GlideX/adb.exe}"
SERIAL="${SERIAL:-emulator-5554}"
PKG="${PKG:-com.kaoyan.wordhelper}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${OUT:-$ROOT/.tmp/verify}"
mkdir -p "$OUT"

a() { "$ADB" -s "$SERIAL" "$@"; }
abs() { case "$1" in /*|[A-Za-z]:*) echo "$1";; *) echo "$OUT/$1";; esac; }
# Windows Python 不认 /d/... 形式的路径，转成 D:\...
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

cmd="${1:-}"; shift || true

case "$cmd" in
  state)
    # uiautomator dump 在 Compose 动画/重组期间会失败，并留下上一次的残缺 XML；
    # 先删目标文件再 dump，并校验语义树够不够大，不够就重试几次。
    dump_ok=0
    for _ in 1 2 3 4; do
      a shell rm -f /sdcard/ui.xml >/dev/null 2>&1
      a shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      a exec-out cat /sdcard/ui.xml 2>/dev/null > "$OUT/_state.xml"
      sz=$(wc -c < "$OUT/_state.xml" 2>/dev/null || echo 0)
      if [ "${sz:-0}" -gt 6000 ]; then dump_ok=1; break; fi
      sleep 0.6
    done
    python - "$(win "$OUT/_state.xml")" "$dump_ok" <<'PY'
import sys
p = sys.argv[1]
ok = sys.argv[2] if len(sys.argv) > 2 else '1'
if ok != '1':
    print('UNKNOWN')
    raise SystemExit
s = open(p, encoding='utf-8', errors='replace').read()
FRONT_MARK = '轻触卡片查看释义与例句'
BACK_MARKS = ('记忆思路', '常用搭配', 'EF系数因子', '近义词', '同根词', '短语')
if FRONT_MARK in s:
    print('FRONT')
elif any(m in s for m in BACK_MARKS):
    print('BACK')
else:
    print('UNKNOWN')
PY
    ;;

  tap)
    a shell input tap "$1" "$2"
    ;;

  swipe)
    a shell input swipe "$1" "$2" "$3" "$4" "$5"
    ;;

  swipetap)
    # 设备端连续执行，中间无 host 进程开销（F4 必须这样测）
    a shell "input swipe 540 1450 540 780 120; input tap 540 1056"
    ;;

  shot)
    a exec-out screencap -p > "$OUT/${1:-shot}.png"
    echo "$OUT/${1:-shot}.png"
    ;;

  hold)
    X="${1:-540}"; Y="${2:-1056}"; MS="${3:-1500}"; NAME="${4:-hold}"
    a exec-out screencap -p > "$OUT/${NAME}_rest.png"
    a shell input swipe "$X" "$Y" "$X" "$Y" "$MS" &
    pid=$!
    sleep 0.6
    a exec-out screencap -p > "$OUT/${NAME}_press.png"
    wait "$pid" || true
    a exec-out screencap -p > "$OUT/${NAME}_after.png"
    echo "$OUT/${NAME}_rest.png $OUT/${NAME}_press.png $OUT/${NAME}_after.png"
    ;;

  f5diff)
    python - "$(win "$(abs "${1:-hold_rest.png}")")" "$(win "$(abs "${2:-hold_press.png}")")" <<'PY'
import sys
import numpy as np
from PIL import Image
rest = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.int16)
press = np.asarray(Image.open(sys.argv[2]).convert('RGB')).astype(np.int16)
x1, y1, x2, y2 = 44, 378, 1036, 1735
r = rest[y1:y2, x1:x2]
p = press[y1:y2, x1:x2]
d = np.abs(r - p).mean(axis=2)
print(f"mean_diff={d.mean():.2f}")
print(f"pct_gt6={100.0 * (d > 6).mean():.2f}%")
PY
    ;;

  record)
    NAME="${1:-rec}"; SEC="${2:-6}"
    a shell rm -f "/sdcard/$NAME.mp4" >/dev/null 2>&1
    a shell screenrecord --time-limit "$SEC" --size 540x1170 --bit-rate 2000000 "/sdcard/$NAME.mp4"
    a pull "/sdcard/$NAME.mp4" "$OUT/$NAME.mp4" >/dev/null 2>&1
    echo "$OUT/$NAME.mp4"
    ;;

  reset)
    a shell am force-stop "$PKG"
    sleep 1
    a shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 6
    echo "restarted"
    ;;

  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
