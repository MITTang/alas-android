#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# 手机分辨率开关（在 Termux 里执行）
#
#   bash display.sh status     查看当前分辨率
#   bash display.sh 720p       切到 ALAS 需要的 1280x720（挂机时用）
#   bash display.sh native     恢复出厂分辨率（不挂机时必须还原）
#
# 为什么需要这个开关：
#   ALAS 只认 1280x720，但手机出厂是 1080x2400。把 720p 设成永久状态会
#   把整机 UI 压在 16:9 横向比例上，日常根本没法用（这是个真实踩过的坑）。
#   所以规则是：挂机时 720p，不挂机时 native。
#
# 实现上走容器里的 adb，而不是 su：
#   `su -c "wm size ..."` 会被 Magisk 的 u:r:magisk:s0 domain 挡住，
#   报 "cmd: Failure calling service window: Failed transaction"；
#   容器里的 adb 连本机 adbd，拿到的是设备 shell uid，对 cmd window 有权限。
# =============================================================================
set -uo pipefail

# Termux 环境（在 Termux app 里已经设好，但通过 `adb shell run-as` 远程调用时不会继承，
# 这里显式补齐，两种调用方式都能跑）
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) export PATH="$PREFIX/bin:/system/bin:$PATH" ;;
esac

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="${CONTAINER:-$(cat "$SELF_DIR/.container" 2>/dev/null || echo ubuntu-base-22.04-base-arm64)}"
THOME="/data/data/com.termux/files/home"
[ -d "$THOME" ] || THOME="${HOME:-$THOME}"
ALAS_HOST_DIR="${ALAS_HOST_DIR:-$THOME/alas/AzurLaneAutoScript}"
MODE="${1:-status}"

if [ ! -d "$ALAS_HOST_DIR" ]; then
    echo "!!! 找不到项目目录：$ALAS_HOST_DIR" >&2
    exit 1
fi

# 改分辨率要经容器里的 adb 操作本机；adbd 的 TCP 端口手机重启后会丢，
# 不先补上就直接报「!!! adb 连不上 127.0.0.1:5555，无法操作分辨率」——
# 这就是「点恢复屏幕没反应」的真正原因。
if [ -x "$SELF_DIR/ensure_adbd.sh" ]; then
    bash "$SELF_DIR/ensure_adbd.sh" || true
fi

proot-distro login "$CONTAINER" \
    --bind "$ALAS_HOST_DIR:/root/AzurLaneAutoScript" \
    -- /bin/bash /root/AzurLaneAutoScript/deploy/Termux/c_display.sh "$MODE"
