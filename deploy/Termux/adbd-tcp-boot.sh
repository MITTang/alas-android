#!/system/bin/sh
# =============================================================================
# 开机自动开启 adbd 的 TCP 端口（Magisk service.d 脚本）
#
# 为什么需要它：
#   `service.adb.tcp.port` 这个属性**每次重启都会归零**。归零之后，容器里的
#   ALAS 和 display.sh 都 adb connect 不上 127.0.0.1:5555，所以表现为：
#     - 点「开始」→ "!!! adb 连不上 127.0.0.1:5555" → 启动失败
#     - 点「恢复屏幕」→ "!!! adb 连不上，无法操作分辨率" → 按了没反应
#   两者其实是同一个原因（真机实测 2026-11-20/21）。
#
# 放在 /data/adb/service.d 下，Magisk 会在 late_start service 阶段以 root 执行，
# 也就是系统起来后、桌面出现那段时间 —— 不用开 App、不用插电脑就会自动补上。
#
# 副作用：adbd 进程会重启一次，USB 调试会短暂断开两三秒后自己恢复；
# 不影响手机正常使用，也不需要用户做任何操作。
# =============================================================================
PORT=5555

# 等系统真正启动完成再动 adbd，避免和系统自己的 adb 初始化撞车
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 1
    i=$((i + 1))
    [ "$i" -gt 120 ] && break
done
sleep 3

CUR="$(getprop service.adb.tcp.port 2>/dev/null | tr -d '[:space:]')"
if [ "$CUR" = "$PORT" ]; then
    # 属性已经在，但仍要确保 adbd 是按这个属性起来的
    log -t ALAS_ADBD "already $PORT, restart adbd to be safe"
fi

setprop service.adb.tcp.port "$PORT"
stop adbd
sleep 1
start adbd

log -t ALAS_ADBD "adbd restarted with tcp port $PORT"
