#!/data/data/com.termux/files/usr/bin/bash
# 通用容器执行器：进 proot 容器跑一段脚本。
#
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/xxx.sh [args...]
#
# 存在的原因：通过 `adb shell run-as com.termux` 远程调用时不会继承 Termux 的环境，
# 连 proot-distro 都找不到（报 "The program proot-distro is not installed"）。
# 这里把 PREFIX / HOME / PATH / TMPDIR 全部补齐。
set -u

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) export PATH="$PREFIX/bin:/system/bin:$PATH" ;;
esac

THOME=/data/data/com.termux/files/home
[ -d "$THOME" ] || THOME="$HOME"
CONTAINER="${CONTAINER:-ubuntu-base-22.04-base-arm64}"

# 第一个参数是 .py 就交给容器里的 python 解释，否则交给 bash。
# 加这个分支的原因：从 adb shell / APP 的 ProcessBuilder 调用时，
# 想传 `-c "python3 xxx.py arg"` 会因为多层引号解析把参数拆散，
# 结果只启动了 python 解释器、卡在交互模式。按后缀自动选解释器就绕开了整件事。
FIRST="${1:-}"
case "$FIRST" in
*.py)
    exec proot-distro login "$CONTAINER" \
        --bind "$THOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript" \
        -- /usr/bin/python3.10 "$@"
    ;;
*)
    exec proot-distro login "$CONTAINER" \
        --bind "$THOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript" \
        -- /bin/bash "$@"
    ;;
esac
