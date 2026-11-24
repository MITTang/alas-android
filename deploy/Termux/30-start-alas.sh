#!/bin/bash
# =============================================================================
# 容器内的 ALAS 启动入口（由 run-alas.sh 在 Termux 层通过 proot-distro login 调起）
#
# 两个必须遵守的点：
#   1) 最后必须 exec 到 python —— 容器内 nohup 出来的进程会随 proot 会话退出而死，
#      只有 exec 才能让 python 继承 proot 的进程身份、跟着 proot 一起常驻。
#   2) 每个新容器会话都必须重新 adb start-server + adb connect —— adb server
#      是会话级的，上个会话退了它也没了。
# =============================================================================
set -uo pipefail

ALAS_DIR="${ALAS_DIR:-/root/AzurLaneAutoScript}"
ADB_PORT="${ADB_TCP_PORT:-5555}"
WEBUI_PORT="${WEBUI_PORT:-22267}"
SERIAL="127.0.0.1:${ADB_PORT}"
PY="${PY:-/usr/bin/python3.10}"

cd "$ALAS_DIR" || exit 1

# 环境变量兜底（20-install-alas.sh 已生成 /root/.alas-env.sh）
if [ -f /root/.alas-env.sh ]; then
    # shellcheck disable=SC1091
    . /root/.alas-env.sh
fi
export MXNET_LIBRARY_PATH="${MXNET_LIBRARY_PATH:-/usr/local/mxnet/libmxnet.so}"
export LD_LIBRARY_PATH="/usr/lib/aarch64-linux-gnu:/usr/local/mxnet${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PYTHONUNBUFFERED=1

echo "==> adb 连本机 adbd"
/usr/bin/adb start-server >/dev/null 2>&1 || true
for i in $(seq 1 10); do
    /usr/bin/adb connect "$SERIAL" >/dev/null 2>&1 || true
    state=$(/usr/bin/adb devices 2>/dev/null | awk -v s="$SERIAL" '$1 == s {print $2}' | head -1)
    [ "$state" = "device" ] && { echo "    连接成功：$SERIAL"; break; }
    if [ "$state" = "unauthorized" ]; then
        echo "    !!! 未授权：容器里的 adb 密钥设备不认。" >&2
        echo "        解决：把本机 %USERPROFILE%\\.android\\adbkey 拷到 /root/.android/adbkey，或执行 10-phone-adb.sh trust" >&2
        exit 1
    fi
    sleep 2
    if [ "$i" -eq 10 ]; then
        echo "    !!! 20 秒内没连上 $SERIAL，adb devices 输出：" >&2
        /usr/bin/adb devices >&2
        exit 1
    fi
done

echo "==> 分辨率：720x1280（竖屏基准；游戏横屏即 1280x720）"
# `wm size` 设的是**竖屏基准**尺寸，这点很关键：
#   720x1280（9:16 竖）→ 桌面正常竖排；打开碧蓝航线系统自动横屏 → 逻辑 1280x720，
#                        正好是 ALAS 原生要求的尺寸，截图直接可用、内容铺满无黑边。
#   1280x720（横）  → 整个桌面被压成横向排版，日常根本没法用（真实踩过三次的坑）。
# density 必须同步 293：720x1280 @ 293dpi = 393dp 宽，和出厂 1080x2400 @ 440dpi
# 完全一致，所以图标 / 字体 / 布局的视觉尺寸都不变，只是像素精度低一点。
# 只改 size 不改 density → 720x1280 @ 440dpi 只有 262dp 宽，UI 会直接崩掉。
# 这两个值会写进 Settings.Global（display_size_forced / display_density_forced），
# 重启由 DisplayManagerService 自动恢复，不需要开机脚本。
/usr/bin/adb -s "$SERIAL" shell "wm size; wm density" 2>/dev/null | tr -d '\r' | sed 's/^/    /' || true
# 渲染区裁剪层现在应当关掉：截图已经是原生 1280x720，再裁一次会裁错
SA_CFG="$ALAS_DIR/config/screen_adapt.json"
if [ -f "$SA_CFG" ] && grep -q '"Enable": true' "$SA_CFG"; then
    echo "    !!! $SA_CFG 里 Enable=true，会把 1280x720 再裁一次。" >&2
    echo "        执行 deploy/Termux/start-game.sh 会自动修正为 false。" >&2
fi

echo "==> 启动 ALAS 调度器"

# 这里跑 alas.py，不是 gui.py —— 这是整个移植里最容易搞错的一点：
#   gui.py  只是 WebUI 外壳（pywebio + fastapi），起来后「开会话、能渲染页面」，
#           但调度器并没有跑。PC 版还要在网页上点那个「启动」大按钮，
#           它才去读 config 里的任务队列。而 pywebio 1.6.2 的前端 JS 点击在这套
#           环境里是失效的（手机 WebView 和电脑 Chrome 行为一致），所以只跑 gui.py
#           的结果就是：HTTP 200 一切正常、界面显示「挂机运行中」，
#           实际一次任务都没跑，日志里只有 uvicorn 的请求记录。
#   alas.py 的 __main__ 就是 AzurLaneAutoScript().loop()，直接进调度循环，
#           不需要任何人点按钮。
# 注意 cwd 必须是项目根：config 路径是相对 ./config/alas.json 拼的（上面已 cd）。
# 想看 WebUI 就把 ALAS_GUI 设成 1，但那时调度器不会跑。
if [ "${ALAS_GUI:-0}" = "1" ]; then
    echo "    （ALAS_GUI=1，只起 WebUI，调度器不会运行）"
    exec "$PY" gui.py --host 0.0.0.0 --port "$WEBUI_PORT"
fi

# 跑 alas_hot.py 而不是 alas.py：
#   alas.py 的 loop 本来就会在任务结束后重读配置，但它判断「要不要换任务」时
#   只看任务名（task_switched）。改关卡时任务名仍是 Main，它认为「继续」，
#   于是接着刷旧图 —— 表现就是「改了没反应」。
#   alas_hot.py 只是给 task_switched 加了一条「关卡名变了也切」的判定，
#   命中后仍走 ALAS 原生流程：抛 TaskEnd → 外层重读配置 → 重新派发 Main。
#   结果就是下一轮直接换图，不重启 proot、不重启游戏。
#   细节见 deploy/Termux/alas_hot.py 的文件头。
exec "$PY" "$ALAS_DIR/deploy/Termux/alas_hot.py"
