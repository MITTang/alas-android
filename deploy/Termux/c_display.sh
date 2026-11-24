#!/bin/bash
# =============================================================================
#  容器内：手机分辨率开关（由 Termux 层的 display.sh 调用）
#
#  为什么必须在容器里做：
#    Termux 里 `su -c "wm size ..."` 会被 Magisk 的 u:r:magisk:s0 domain 挡住，
#    报 cmd: Failure calling service window: Failed transaction (2147483646)。
#    而容器里的 adb 连的是本机 adbd，执行时是设备上的 **shell uid**，
#    对 cmd window 服务有权限，wm 能正常工作。
#
#  为什么是 720x1280 而不是 1280x720（重要）：
#    `wm size` 设的是**竖屏基准**的尺寸。
#    设成 720x1280（9:16 竖屏）的好处是：
#      - 竖屏时桌面 / 所有 App 按 720x1280 正常排版；
#      - 一打开碧蓝航线，系统自动转成横屏 → 逻辑分辨率正好是 **1280x720**，
#        也就是 ALAS 原生要求的那个尺寸，内容铺满、没有黑边，
#        截图直接可用，不需要任何裁剪缩放。
#    设成 1280x720 就反了：竖屏基准是横的，整个桌面会被压成横向排版。
#
#  density 必须一起改：
#    720x1280 @ 293dpi → 逻辑宽度 393dp，和出厂 1080x2400 @ 440dpi 的 393dp 一样，
#    所以图标、字体、布局的视觉尺寸都不变，只是像素精度低一点。
#    只改 size 不改 density → 720x1280 @ 440dpi 只有 262dp 宽，UI 会直接崩掉。
#
#  重启后还在吗：
#    `wm size` / `wm density` 会写进 Settings.Global 的
#    display_size_forced / display_density_forced，重启由 DisplayManagerService
#    重新应用，所以是持久的。native 模式会用 wm reset 清掉这两个值。
#
#  用法：bash c_display.sh [720p|native|status]
# =============================================================================
set -uo pipefail

for v in ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT ANDROID_I18N_ROOT \
         ANDROID_TZDATA_ROOT EXTERNAL_STORAGE ASEC_MOUNTPOINT BOOTCLASSPATH \
         LD_PRELOAD LD_LIBRARY_PATH PYTHONHOME PYTHONPATH DEX2OATBOOTCLASSPATH; do
    unset "$v" 2>/dev/null || true
done
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8
export HOME=/root

ADB="${ADB:-/usr/bin/adb}"
S="${SERIAL:-127.0.0.1:5555}"
MODE="${1:-status}"
SIZE="${SIZE:-720x1280}"
DENSITY="${DENSITY:-293}"

$ADB start-server >/dev/null 2>&1 || true
for i in $(seq 1 8); do
    $ADB connect "$S" >/dev/null 2>&1
    $ADB devices 2>/dev/null | grep -q "^$S.*device" && break
    sleep 2
done
if ! $ADB devices 2>/dev/null | grep -q "^$S.*device"; then
    echo "!!! adb 连不上 $S，无法操作分辨率" >&2
    $ADB devices >&2
    exit 1
fi

case "$MODE" in
    native|reset)
        echo "==> 恢复出厂分辨率（1080x2400 @ 440dpi）"
        $ADB -s "$S" shell wm size reset
        $ADB -s "$S" shell wm density reset
        ;;
    720p|apply)
        echo "==> 设为 ${SIZE} @ ${DENSITY}dpi（竖屏基准；游戏横屏后＝1280x720）"
        $ADB -s "$S" shell wm size "$SIZE"
        $ADB -s "$S" shell wm density "$DENSITY"
        ;;
    status)
        ;;
    *)
        echo "用法: bash c_display.sh [720p|native|status]" >&2
        exit 1
        ;;
esac

echo "--- 当前 ---"
$ADB -s "$S" shell "wm size; wm density"
