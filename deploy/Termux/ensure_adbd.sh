#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# 确保 adbd 的 TCP 端口可用
#
# 为什么需要这个文件：
#   容器里的 ALAS（以及 display.sh 改分辨率）都是靠 `adb connect 127.0.0.1:5555`
#   操作本机的 —— 只有走 adb 才绕得开 Magisk 的 u:r:magisk:s0 域限制。
#   而 `service.adb.tcp.port` 这个属性**手机一重启就归零**，此时：
#     start-game.sh → "!!! adb 连不上 127.0.0.1:5555" → 启动失败
#     display.sh    → "!!! adb 连不上，无法操作分辨率" → 「恢复屏幕」按了没反应
#   两个看起来无关的故障其实是同一个原因（真机实测 2026-11-20）。
#
#   所以启动 / 恢复屏幕之前都先跑一遍这里，把端口补上，以后不用人工处理。
#
# 用法：bash ensure_adbd.sh          # 已在监听则什么都不做
# =============================================================================
set -u

PORT="${ADB_TCP_PORT:-5555}"

port_ok() {
    # bash 的 /dev/tcp 假连接：能连上说明 adbd 已经在监听
    (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null && return 0
    return 1
}

if port_ok; then
    echo "adbd TCP $PORT 已就绪"
    exit 0
fi

echo "adbd TCP $PORT 未监听，尝试开启"

run_root() {
    if [ "$(id -u 2>/dev/null)" = "0" ]; then
        /system/bin/sh -c "$1"
    else
        /system/bin/su -c "$1"
    fi
}

# 和 PC 上 `adb tcpip 5555` 等价：先写属性，再重启 adbd。
# 注意这一步会让 USB 调试短暂断开（adbd 进程整个重启），几秒后自己回来。
if ! run_root "setprop service.adb.tcp.port $PORT; stop adbd; sleep 1; start adbd"; then
    echo "!!! 开启失败：没有 root 权限？" >&2
    exit 1
fi

i=0
while [ "$i" -lt 15 ]; do
    i=$((i + 1))
    sleep 1
    if port_ok; then
        echo "adbd TCP $PORT 已就绪（等待 ${i}s）"
        exit 0
    fi
done

echo "!!! adbd TCP $PORT 仍然连不上，检查是否被 USB 调试授权弹窗挡住" >&2
exit 1
