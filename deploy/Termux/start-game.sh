#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  一键启动：唤醒 → 打开碧蓝航线 → 按机型测量渲染区 → 起 ALAS
#
#  设计要点（重要）：
#    * 全程**不修改系统分辨率**。不使用 `wm size 1280x720`，
#      因为那是全局逻辑分辨率，会把桌面、通知栏、所有 App 一起压成 16:9，
#      手机上显示成横排，日常完全没法用（真实踩过三次的坑）。
#    * 碧蓝航线自己是按 16:9 画布渲染、居中显示在屏幕上的；
#      ALAS 真正需要的是"一张 1280x720、内容和 720p 一致的截图"。
#      所以就在设备层把游戏渲染区裁出来缩回 1280x720 喂给 ALAS
#      （module/device/screen_adapt.py），触摸时再按同样比例反算回屏幕坐标。
#    * 渲染区不是写死的：每次启动都用 detect_render_area.py 实测黑边再写入
#      config/screen_adapt.json —— 换机型、换系统字体、换分辨率都能自适应。
#
#  用法：
#    start-game.sh            启动游戏 + 适配 + 起 ALAS（最常用）
#    start-game.sh game       只启动游戏并适配，不起 ALAS
#    start-game.sh stop       停止 ALAS（不动游戏、不动分辨率）
#    start-game.sh status     看当前状态
# =============================================================================
set -u

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export PATH="$PREFIX/bin:/system/bin:/system/xbin${PATH:+:$PATH}"

SELF_DIR=$(cd "$(dirname "$0")" && pwd)
CT="/root/AzurLaneAutoScript/deploy/Termux"
WAIT_LOAD="${WAIT_LOAD:-25}"
MODE="${1:-all}"

case "$MODE" in
    stop)
        exec bash "$SELF_DIR/run-alas.sh" stop
        ;;
    status)
        exec bash "$SELF_DIR/run-alas.sh" status
        ;;
    game|all) : ;;
    *)
        echo "用法: $0 [all|game|stop|status]" >&2
        exit 2
        ;;
esac

echo "=================================================="
echo "  ALAS 一键启动 —— 系统 720x1280（横屏＝1280x720）"
echo "=================================================="

echo ""
echo "[0/3] 充电时保持常亮（不写系统设置，拔线即失效）"# 为什么不用 `settings put system screen_off_timeout 2147483647`：
# 那是永久项，会把你的「30 秒自动锁屏」永久改掉，日常一直生效。
# `svc power stayon true` 只在充电时阻止休眠，拔掉充电线就恢复原样，
# 重启也会复位 —— 挂机插着线正好不熄屏，平时用手机不受影响。
/system/bin/su -c "svc power stayon true" >/dev/null 2>&1 || true

echo ""
echo "[0/3] 确保 adbd TCP 端口（容器里的 ALAS 靠它连本机）"
# 手机重启后 service.adb.tcp.port 会归零，容器就 adb connect 不上，
# 表现为「启动失败」和「恢复屏幕没反应」两个看起来无关的故障。
bash "$SELF_DIR/ensure_adbd.sh" || true

echo ""
echo "[1/3] 确保分辨率 + 唤醒屏幕 + 打开碧蓝航线 + 校验截图尺寸"
bash "$SELF_DIR/_exec.sh" "$CT/c_prepare_game.sh" "$WAIT_LOAD"
rc=$?
if [ "$rc" -ne 0 ]; then
    echo ""
    echo "!!! 准备失败（exit $rc）"
    echo "    排查：手机是否解锁、游戏是否在前台、是否已横屏"
    echo "    当前屏幕尺寸: $(/system/bin/dumpsys window 2>/dev/null | grep -m1 -o 'cur=[0-9]*x[0-9]*')"
    exit "$rc"
fi

if [ "$MODE" = "game" ]; then
    echo ""
    echo "游戏已就绪，分辨率 720x1280（横屏即 1280x720），未启动 ALAS。"
    exit 0
fi

echo ""
echo "[2/3] 清理旧实例"
bash "$SELF_DIR/run-alas.sh" stop >/dev/null 2>&1 || true

echo ""
echo "[3/3] 启动 ALAS"
bash "$SELF_DIR/run-alas.sh" daemon
sleep 12
echo ""
bash "$SELF_DIR/run-alas.sh" status
echo ""
echo "--------------------------------------------------"
echo " 完成。桌面按 720x1280 竖排，游戏横屏即 1280x720。"
# $HOME 在 `su -M -c` 拽起来的环境里常常是空的（APP 点「开始」就是这条路径），
# 直接拼会打出 `//alas-daemon.log` 这种怪路径，所以再兜一次底。
echo " 日志: ${HOME:-/data/data/com.termux/files/home}/alas-daemon.log"
echo "--------------------------------------------------"
