#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# 步骤 2/5：让手机自己连自己的 adb（需要 root）
# 原理：ALAS 的所有设备操作都走 adb。游戏和 ALAS 在同一台手机上，
#       所以让 adbd 监听一个 TCP 端口，容器里的 adb 连 127.0.0.1:5555 即可。
#
# 用法：
#   bash 10-phone-adb.sh enable    # 打开 adbd TCP + 关掉系统会杀进程的限制
#   bash 10-phone-adb.sh tune      # 常亮 / 关深色模式 / 电池白名单（可单独跑）
#   bash 10-phone-adb.sh trust     # 把容器里生成的 adb 公钥写进 adb_keys（免弹窗）
#   bash 10-phone-adb.sh status    # 看当前状态
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${ADB_TCP_PORT:-5555}"
CONTAINER="${CONTAINER:-$(cat "$SELF_DIR/.container" 2>/dev/null || echo ubuntu-base-22.04-base-arm64)}"

have_root() {
    command -v su >/dev/null 2>&1 && su -c "id" 2>/dev/null | grep -q "uid=0"
}

as_root() {
    su -c "$1"
}

ac_enable() {
    have_root || { echo "!!! 没有拿到 root，请确认 Magisk/KernelSU 已授权 Termux" >&2; exit 1; }

    echo "==> 打开 adbd TCP 端口 $PORT"
    as_root "setprop service.adb.tcp.port $PORT"
    as_root "stop adbd"
    sleep 1
    as_root "start adbd"
    sleep 2
    echo "    当前端口: $(as_root 'getprop service.adb.tcp.port' || true)"

    echo "==> 解除 Android 12+ 的子进程（phantom process）杀进程限制"
    # 实测：Android 16 上 device_config 那套不生效，真正管用的是下面这个 global 开关
    as_root "settings put global settings_enable_monitor_phantom_procs false" || true
    as_root "device_config put activity_manager max_phantom_processes 2147483647" || true
    as_root "settings get global settings_enable_monitor_phantom_procs" || true

    echo "==> 把 Termux 加入电池白名单 / 允许后台运行"
    as_root "dumpsys deviceidle whitelist +com.termux" || true
    as_root "cmd appops set com.termux RUN_IN_BACKGROUND allow" || true
    as_root "cmd appops set com.termux RUN_ANY_IN_BACKGROUND allow" || true

    ac_tune
    echo
    echo "完成。下一步：进容器执行 20-install-alas.sh"
    echo "（注意：重启 adbd 会让手机和电脑的 USB 调试断开，属正常）"
}

ac_tune() {
    have_root || { echo "!!! 没有 root" >&2; exit 1; }
    local mode="${1:-report}"
    # =====================================================================
    # 默认只报告，不动任何日常设置。
    # 屏幕超时、深色模式是「永久项」，改了会一直影响日常使用，
    # 用户明确要求不要动（真实被投诉过）。
    #
    # 挂机期间要保持亮屏，改用 `svc power stayon true`：
    # 它只在**充电时**生效，拔掉线自动失效，重启也复位，
    # 不是写进 settings 的永久覆盖，属于运行时状态，相对温和。
    # 手机插着 USB 挂机 → 正好不熄屏；拔线后一切照旧。
    # =====================================================================
    if [ "$mode" = "apply" ]; then
        echo "==> 充电时保持常亮（拔线自动失效，不写 settings）"
        as_root "svc power stayon true" >/dev/null 2>&1 || true
    else
        echo "==> 屏幕/深色：只报告，不修改"
    fi
    echo "    屏幕超时   : $(as_root 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r')"
    echo "    深色模式   : $(as_root 'cmd uimode night' 2>/dev/null | tr -d '\r' | head -1)"
    echo "    充电常亮   : $(as_root 'dumpsys power' 2>/dev/null | grep -m1 -o 'mStayOn=[a-z]*')"
    echo "==> 分辨率：不做任何修改（渲染区适配方案，见 module/device/screen_adapt.py）"
    echo "    当前: $(as_root 'wm size' | tr '\n' ' ' | tr -d '\r')"
}

ac_trust() {
    have_root || { echo "!!! 没有 root" >&2; exit 1; }
    # 实机结论：容器里的 adb 新生成的密钥设备不认（一直 unauthorized），
    # 而且 adb 1.0.41 只写私钥不写 .pub。最稳的办法是复用电脑上已被设备授权的密钥。
    local pub="${1:-}"
    if [ -z "$pub" ]; then
        for c in "$HOME/alas/AzurLaneAutoScript/deploy/Termux/_adbkey.pub" \
                 "$HOME/alas/_adbkey.pub" \
                 "$HOME/.android/adbkey.pub"; do
            [ -f "$c" ] && { pub="$c"; break; }
        done
    fi
    if [ -z "$pub" ] || [ ! -f "$pub" ]; then
        echo "!!! 找不到可用的 adb 公钥。两条路任选其一：" >&2
        echo "    1) 在电脑上把 %USERPROFILE%\\.android\\adbkey.pub 拷到手机上：" >&2
        echo "       adb push %USERPROFILE%\\.android\\adbkey.pub /sdcard/Download/" >&2
        echo "       然后在 Termux 里：bash 10-phone-adb.sh trust /sdcard/Download/adbkey.pub" >&2
        echo "    2) 手机插着 USB 时，直接在手机屏幕弹窗点【允许 USB 调试】" >&2
        exit 1
    fi
    echo "==> 使用公钥：$pub"
    as_root "mkdir -p /data/misc/adb"
    as_root "grep -qF \"\$(cat '$pub')\" /data/misc/adb/adb_keys 2>/dev/null || cat '$pub' >> /data/misc/adb/adb_keys" || \
        as_root "cat '$pub' >> /data/misc/adb/adb_keys"
    as_root "chmod 644 /data/misc/adb/adb_keys"
    as_root "chown system:shell /data/misc/adb/adb_keys" || true
    as_root "restorecon /data/misc/adb/adb_keys" || true
    as_root "stop adbd"; sleep 1; as_root "start adbd"; sleep 2
    echo "==> 已写入 /data/misc/adb/adb_keys。"
    echo "    还要把私钥也放进容器（容器 adb 才能用这把钥匙）："
    echo "      电脑上: adb push %USERPROFILE%\\.android\\adbkey /sdcard/Download/"
    echo "      容器内: mkdir -p /root/.android && cp /sdcard/... 不行——容器看不到 /sdcard，"
    echo "              改用 Termux 层 cp 到 bind 目录，再在容器里 cp 到 /root/.android/adbkey"
}

ac_status() {
    echo "root        : $(have_root && echo 有 || echo 无)"
    if have_root; then
        echo "adbd 端口   : $(as_root 'getprop service.adb.tcp.port' || true)"
        echo "分辨率      : $(as_root 'wm size' | tr '\n' ' ')"
        echo "屏幕超时    : $(as_root 'settings get system screen_off_timeout' || true)"
        echo "深色模式    : $(as_root 'cmd uimode night' || true)"
        echo "phantom 上限: $(as_root '/system/bin/device_config get activity_manager max_phantom_processes' || true)"
    fi
    echo "容器 $CONTAINER : $(proot-distro list 2>/dev/null | grep -w "$CONTAINER" >/dev/null && echo 已安装 || echo 未安装)"
}

case "${1:-status}" in
    enable) ac_enable ;;
    tune)   ac_tune ;;
    trust)  ac_trust ;;
    status) ac_status ;;
    *) echo "用法: bash 10-phone-adb.sh [enable|tune|trust|status]" >&2; exit 1 ;;
esac
