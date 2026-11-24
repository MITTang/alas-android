#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# 步骤 4/5：启动 / 停止 ALAS（在 Termux 里执行）
#
# 用法：
#   bash run-alas.sh            前台运行（调试用，日志直接刷屏，Ctrl+C 停止）
#   bash run-alas.sh daemon     后台常驻（nohup，日志写 ~/alas-daemon.log）
#   bash run-alas.sh stop       停止 ALAS
#   bash run-alas.sh status     查看是否在跑 + WebUI 探测
#
# 关键机制（实测结论，别改）：
#   ALAS 的 python 进程必须由 proot 常驻带起来 —— 容器内单独 nohup python 会在
#   proot 会话退出时被一起杀掉。所以常驻的是 proot 本身（nohup proot-distro login &），
#   容器内再用 exec 顶到 python（见 30-start-alas.sh）。
# =============================================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

# Termux 环境兜底：在 Termux app 里已经设好，通过 `adb shell run-as` 远程调用时不会继承
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) export PATH="$PREFIX/bin:/system/bin:$PATH" ;;
esac

# Termux 的 home 固定是 /data/data/com.termux/files/home。不用 $HOME 直接拼，
# 是因为通过 `adb shell run-as com.termux` 远程调用时 $HOME 会被改写成
# /data/user/0/com.termux（不存在的路径），脚本会误判"找不到项目目录"。
THOME="/data/data/com.termux/files/home"
[ -d "$THOME" ] || THOME="${HOME:-$THOME}"

ALAS_HOST_DIR="${ALAS_HOST_DIR:-$THOME/alas/AzurLaneAutoScript}"
CONTAINER="${CONTAINER:-$(cat "$SELF_DIR/.container" 2>/dev/null || echo ubuntu-base-22.04-base-arm64)}"
ADB_PORT="${ADB_TCP_PORT:-5555}"
WEBUI_PORT="${WEBUI_PORT:-22267}"
LOG="$THOME/alas-daemon.log"
MODE="${1:-foreground}"

# pgrep / pkill 必须固定用 Android 自带的 /system/bin 版。
# Termux 的 procps 版在 Magisk 环境下看不到 proot 名下的子进程（容器里的 python
# 就挂在 proot 下面），于是 `pkill -f alas_hot.py` 一个都匹配不到、静默返回 1：
# 停止脚本"跑完了、报剩余 0 个"，容器里的 python 却还在跑，界面一直「挂机运行中」。
# 实测：Termux 版看不到，/system/bin 版能正常列出并杀掉。
PGREP=/system/bin/pgrep
PKILL=/system/bin/pkill
[ -x "$PGREP" ] || PGREP=pgrep
[ -x "$PKILL" ] || PKILL=pkill

if [ ! -d "$ALAS_HOST_DIR" ]; then
    echo "!!! 找不到项目目录：$ALAS_HOST_DIR" >&2
    echo "    用 ALAS_HOST_DIR=$HOME/alas/AzurLaneAutoScript bash run-alas.sh 指定" >&2
    exit 1
fi

termux-wake-lock 2>/dev/null || true

stop_alas() {
    # 第二个参数：是否顺便还原分辨率。默认 no ——
    # 现在 ALAS 不再靠 `wm size` 工作（改用设备层渲染区适配 screen_adapt），
    # 正常情况下根本没有 override 需要还原。只有你手动 `display.sh 720p` 过，
    # 才需要显式执行 `run-alas.sh stop --restore-display`。
    local restore_display="${1:-no}"
    # 真正的容器进程名是 proot 二进制，不是 proot-distro 脚本，
    # 所以匹配 "proot-distro login" 是查不到也杀不掉的（实测踩过）。
    # 用容器名匹配 proot 的 cmdline（里面有 rootfs/bind 路径），
    # 又不会误杀本脚本（本脚本 cmdline 不含容器名）。
    #
    # 2026-11-20 修正：**入口已经不是 alas.py 了**。
    # 30-start-alas.sh 末尾是 `exec alas_hot.py`（热切换启动器），所以进程实际长这样：
    #   python3.10 /root/AzurLaneAutoScript/deploy/Termux/alas_hot.py
    # 老规则 `pkill -f "python3.10 alas.py"` 一个字都匹配不上，等于只杀了 proot，
    # 容器里的 python 变成孤儿继续跑、日志继续写 —— 用户看到的就是「点了结束没反应」。
    "$PKILL" -f "alas_hot.py" 2>/dev/null && echo "    已停 alas_hot.py 调度器"
    # 兼容旧入口（万一还在跑 alas.py）
    "$PKILL" -f "python3.10 alas.py" 2>/dev/null && echo "    已停 alas.py 调度器"
    "$PKILL" -f "gui.py" 2>/dev/null && echo "    已停 gui.py"
    "$PKILL" -f "ubuntu-base-22.04-base-arm64" 2>/dev/null && echo "    已停 proot"
    "$PKILL" -f "30-start-alas.sh" 2>/dev/null && echo "    已停 entry"
    sleep 2
    # 兜底：proot 收到 SIGTERM 后，容器内的 python 偶尔会残留成孤儿（实测留过 1 个）。
    # 这里用容器内的绝对路径 "/root/AzurLaneAutoScript" 做匹配 —— 宿主机上的命令行
    # （包括执行本脚本的那个 bash）都走 /data/data/... 路径，不含这个串，不会误杀自己。
    LEFT="$("$PGREP" -f '/root/AzurLaneAutoScript' 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${LEFT:-0}" -gt 0 ]; then
        "$PKILL" -9 -f '/root/AzurLaneAutoScript' 2>/dev/null && echo "    强制清理 ${LEFT} 个残留进程"
        sleep 1
    fi
    # 再核一遍：python 真的没了才算停干净。之前 pkill 走 Termux 版、一个都没匹配，
    # 脚本照样打印「剩余 0 个」，用户看到的就是停不掉。
    STILL="$(ps -ef 2>/dev/null | grep -c '[p]ython3.10')"
    if [ "${STILL:-0}" -gt 0 ]; then
        echo "    !!! 还有 ${STILL} 个 python3.10 没杀掉，改用 ps 列表逐个 kill -9"
        ps -ef 2>/dev/null | grep '[p]ython3.10' | awk '{print $2}' | while read -r p; do
            kill -9 "$p" 2>/dev/null && echo "    已 kill -9 $p"
        done
        sleep 1
    fi
    # 标志文件是 alas_task.sh 靠 trap 删的。脚本被 -9 强杀（或者容器整体被
    # 拔掉）时 trap 根本跑不到，于是留下一个空文件；APP 见到文件在就当
    # 「任务运行中」显示。进程早就没了、界面还挂橙色 —— 用户看到的就是
    # 「点了结束还是显示运行中」。停止时顺手清掉，停止才真的彻底。
    if [ -f /data/data/com.termux/files/home/alas-task.running ]; then
        rm -f /data/data/com.termux/files/home/alas-task.running 2>/dev/null \
            && echo "    已清残留任务标志"
    fi
    if [ "$restore_display" = "yes" ] && [ -x "$SELF_DIR/display.sh" ]; then
        bash "$SELF_DIR/display.sh" native 2>&1 | tail -4
    fi
}

case "$MODE" in
    stop)
        echo "==> 停止 ALAS"
        stop_alas
        echo "    剩余 python3.10 进程：$(ps -ef 2>/dev/null | grep -c '[p]ython3.10')"
        exit 0
        ;;
    restart)
        # 改完参数想让新值立刻生效就走这个：ALAS 只在每轮任务开始时读一次
        # config/alas.json，连续作战会一直打不换图，光改文件不动进程要等很久。
        # 这里停掉再按 daemon 分支重启，几秒内就用新配置重新开跑。
        echo "==> 重启 ALAS（让新参数立即生效）"
        stop_alas
        sleep 2
        MODE=daemon
        ;;
    status)
        echo "==> ALAS 状态"
        # proot 的实际进程名是 proot 本身（proot-distro 只是它外面的一层脚本），
        # 所以匹配 "proot-distro login" 会查不到，用容器名匹配 cmdline。
        # python 一律用 pgrep -x 精确匹配进程名：写成 pgrep -f "alas.py"
        # 会匹配到 pgrep 自己（它命令行里就带着 alas.py），永远恒真。
        echo "    proot   : $("$PGREP" -f 'ubuntu-base-22.04-base-arm64' 2>/dev/null | tr '\n' ' ')"
        # python 用 ps + "[p]ython3.10" 这个写法：方括号让 grep 自己的 cmdline
        # （含 "python3.10"）匹配不到自己。另外 pgrep -x 在 Termux 层看不到
        # 容器里的进程（它挂在 proot 名下），所以这里必须走 ps。
        echo "    python  : $(ps -ef 2>/dev/null | grep '[p]ython3.10' | awk '{print $2}' | tr '\n' ' ')"
        # 调度器是否真的在跑，先看有没有活着的 python。
        # 老版本这里只看日志里那句 "Start scheduler loop" —— 但日志是追加写的，
        # 一旦跑过就永远命中，**停了也照样报「已进入调度循环」**，这就是
        # 「点了结束、状态卡还是挂机运行中」的另一半原因。
        PY_N="$(ps -ef 2>/dev/null | grep -c '[p]ython3.10')"
        if [ "${PY_N:-0}" -gt 0 ]; then
            echo "    调度器  : python 在跑（${PY_N} 个）"
        else
            echo "    调度器  : 未运行"
        fi
        echo "    日志    : $LOG"
        LOG_AGE=$(( $(date +%s) - $(stat -c %Y "$LOG" 2>/dev/null || echo 0) ))
        echo "    最近日志: ${LOG_AGE}s 前写入（仅供参考，不代表存活）"
        exit 0
        ;;
esac

echo "==> 检查 adbd TCP 端口"
if command -v su >/dev/null 2>&1 && su -c "id" 2>/dev/null | grep -q "uid=0"; then
    if [ "$(su -c "getprop service.adb.tcp.port" 2>/dev/null | tr -d '[:space:]')" != "$ADB_PORT" ]; then
        echo "    端口不是 $ADB_PORT，重新开启"
        bash "$SELF_DIR/10-phone-adb.sh" enable
    else
        bash "$SELF_DIR/10-phone-adb.sh" tune
    fi
else
    echo "    !!! 没有 root 权限，跳过 adbd 检查（ALAS 大概率连不上自己）" >&2
fi

START_CMD=(
    proot-distro login "$CONTAINER"
    --bind "$ALAS_HOST_DIR:/root/AzurLaneAutoScript"
    -- /bin/bash /root/AzurLaneAutoScript/deploy/Termux/30-start-alas.sh
)

if [ "$MODE" = "daemon" ]; then
    echo "==> 后台常驻启动容器 $CONTAINER"
    stop_alas no

    # 必须 setsid：从 APP 点「开始」时，这条脚本是 APP 经 `su -M -c` 拉起的子进程，
    # 和 APP 同属一个会话/进程组。游戏一被拉到前台，APP 就退到后台，
    # MIUI/HyperOS 清理后台时是按进程组清理的 —— 连带把 proot 一起收掉。
    # 实测日志：18:37:54 容器起好、Uvicorn 监听 22267，18:38:59 就 Shutting down，
    # 正好 65 秒，就是 APP 被回收的那一刻。
    # setsid 让 proot 另开一个会话，彻底脱离 APP 的进程组，APP 被杀也不影响它。
    # 从 Termux 手动跑时本来就没有这个问题，所以以前没暴露出来。
    if command -v setsid >/dev/null 2>&1; then
        nohup setsid "${START_CMD[@]}" > "$LOG" 2>&1 &
    else
        nohup "${START_CMD[@]}" > "$LOG" 2>&1 &
    fi
    echo "    launched pid=$!  日志：$LOG"
    sleep 12
    echo "    proot 存活：$("$PGREP" -f 'ubuntu-base-22.04-base-arm64' | tr '\n' ' ')"
    # 等一会儿再判：alas.py 要连模拟器、读配置、进循环，比 gui.py 起 WebUI 慢。
    for i in $(seq 1 6); do
        sleep 10
        if grep -q "Start scheduler loop" "$LOG" 2>/dev/null; then
            break
        fi
    done
    if grep -q "Start scheduler loop" "$LOG" 2>/dev/null; then
        echo "    调度器：已进入调度循环（gate 掉 gui.py，跑的是 alas.py）"
    else
        echo "    !!! 调度器没进循环，看日志：tail -40 $LOG" >&2
    fi
else
    echo "==> 前台启动容器 $CONTAINER（Ctrl+C 停止）"
    "${START_CMD[@]}"
fi
