#!/bin/bash
# =============================================================================
#  容器内：确保分辨率 → 启动碧蓝航线 → 等它横屏 → 校验 ALAS 能拿到 1280x720
#
#  由 Termux 层的 start-game.sh 通过 _exec.sh 调起。
#  参数：$1 = 等待游戏进入可截图状态的秒数（默认 20）
#
#  分辨率策略（已实测）：
#    系统 wm size = 720x1280（竖屏基准，重启由 Settings.Global 恢复）
#    → 桌面按 720x1280 正常排版
#    → 打开碧蓝航线系统自动横屏，逻辑分辨率正好变成 1280x720
#    → 截图直接就是 ALAS 要的尺寸，内容铺满无黑边，不需要裁剪缩放
# =============================================================================
set -u

WAIT_LOAD="${1:-20}"
ADB_BIN="${ADB_BIN:-/usr/bin/adb}"
SERIAL="${ADB_SERIAL:-127.0.0.1:5555}"
GAME_ACT="${GAME_ACT:-com.bilibili.azurlane/com.manjuu.azurlane.MainActivity}"
ALAS_DIR="${ALAS_DIR:-/root/AzurLaneAutoScript}"
WANT_SIZE="${WANT_SIZE:-720x1280}"
WANT_DENSITY="${WANT_DENSITY:-293}"

# 清掉 Termux 的环境污染，让容器内 adb / python 拿到干净的 Linux 环境
for v in ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT ANDROID_I18N_ROOT \
         ANDROID_TZDATA_ROOT EXTERNAL_STORAGE ASEC_MOUNTPOINT BOOTCLASSPATH \
         LD_PRELOAD LD_LIBRARY_PATH PYTHONHOME PYTHONPATH DEX2OATBOOTCLASSPATH; do
    unset "$v" 2>/dev/null || true
done
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8
[ -f /root/.alas-env.sh ] && . /root/.alas-env.sh

A() { "$ADB_BIN" -s "$SERIAL" "$@"; }

adb_up() {
    "$ADB_BIN" start-server >/dev/null 2>&1 || true
    local i state
    for i in $(seq 1 10); do
        "$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1 || true
        state=$("$ADB_BIN" devices 2>/dev/null | awk -v s="$SERIAL" '$1 == s {print $2}' | head -1)
        [ "$state" = "device" ] && return 0
        sleep 1
    done
    echo "!!! adb 连不上 $SERIAL" >&2
    "$ADB_BIN" devices >&2
    return 1
}

cur_size() {
    A shell dumpsys window 2>/dev/null | tr -d '\r' \
        | grep -o 'cur=[0-9]*x[0-9]*' | head -1 | sed 's/cur=//'
}

is_landscape() {
    local s="$1" w h
    [ -z "$s" ] && return 1
    w="${s%x*}"; h="${s#*x}"
    [ -n "$w" ] && [ -n "$h" ] && [ "$w" -gt "$h" ]
}

echo "[1/5] 连接 adb"
adb_up || exit 1
echo "    设备：$("$ADB_BIN" devices | awk -v s="$SERIAL" '$1 == s {print $1, $2}')"

echo "[2/5] 确保分辨率 = ${WANT_SIZE} @ ${WANT_DENSITY}dpi（竖屏基准）"
now_size=$(A shell wm size 2>/dev/null | tr -d '\r' | grep -o 'Override size: .*' | sed 's/Override size: //')
now_dens=$(A shell wm density 2>/dev/null | tr -d '\r' | grep -o 'Override density: .*' | sed 's/Override density: //')
if [ "$now_size" = "$WANT_SIZE" ] && [ "$now_dens" = "$WANT_DENSITY" ]; then
    echo "    已经是 ${WANT_SIZE} @ ${WANT_DENSITY}dpi，跳过"
else
    echo "    当前 override: size=${now_size:-无} density=${now_dens:-无}，开始设置"
    A shell wm size "$WANT_SIZE" >/dev/null 2>&1 || true
    A shell wm density "$WANT_DENSITY" >/dev/null 2>&1 || true
    sleep 2
    echo "    设置后: $(A shell wm size 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
fi
# 关掉渲染区裁剪层：现在是原生 720p，再裁一次会裁错
python3.10 - <<'PY' 2>/dev/null || true
import json
p = '/root/AzurLaneAutoScript/config/screen_adapt.json'
d = json.load(open(p, encoding='utf-8'))
if d.get('Enable'):
    d['Enable'] = False
    json.dump(d, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print('    screen_adapt: 已关闭（原生 720p 不需要裁剪）')
PY

echo "[3/5] 唤醒屏幕"
A shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sleep 1
A shell input swipe 540 2000 540 600 300 >/dev/null 2>&1 || true
sleep 1

echo "[4/5] 启动碧蓝航线并等横屏（最多 40 秒）"
A shell am start -n "$GAME_ACT" 2>&1 | tr -d '\r' | sed 's/^/    /'
landed=no
for i in $(seq 1 20); do
    sz=$(cur_size)
    if is_landscape "$sz"; then
        echo "    已横屏：cur=$sz（第 ${i} 次探测）"
        landed=yes
        break
    fi
    sleep 2
done
[ "$landed" = "no" ] && echo "    警告：40 秒内还是竖屏，cur=$(cur_size)"
echo "    等画面加载 ${WAIT_LOAD} 秒"
sleep "$WAIT_LOAD"

echo "[5/5] 校验 ALAS 拿到的截图"
cd "$ALAS_DIR" || exit 1
python3.10 -c "
import sys, subprocess, numpy as np, cv2
raw = subprocess.check_output(['/usr/bin/adb', '-s', '127.0.0.1:5555', 'exec-out', 'screencap', '-p'])
img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
h, w = img.shape[:2]
print('    截图 h,w =', (h, w))
if (w, h) == (1280, 720):
    print('    OK：1280x720，正是 ALAS 要的尺寸')
    sys.exit(0)
print('    !!! 不是 1280x720，实际', (w, h))
sys.exit(3)
"
rc=$?

echo ""
echo "--- 复核 ---"
echo "  wm     : $(A shell wm size 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
echo "  density: $(A shell wm density 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
echo "  cur    : $(cur_size)"
echo "  适配层 : $(tr -d '\n' < "$ALAS_DIR/config/screen_adapt.json" 2>/dev/null)"
echo "---"
exit $rc
