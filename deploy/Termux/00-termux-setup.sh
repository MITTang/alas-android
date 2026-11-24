#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# 步骤 1/5：Termux 侧准备（不需要 root）
# 用途：装 proot-distro、创建 Ubuntu 22.04 容器、把项目放进 Termux 私有目录
# 用法：bash 00-termux-setup.sh [项目压缩包路径]
# =============================================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="ubuntu-base-22.04-base-arm64"
SRC_TAR="${1:-}"
ALAS_HOST_DIR="$HOME/alas/AzurLaneAutoScript"

echo "==> [1/4] 防止 Termux 被系统杀掉（唤醒锁）"
termux-wake-lock 2>/dev/null || echo "    跳过（未安装 termux-api，可稍后 pkg install termux-api）"

echo "==> [2/4] 安装 Termux 软件包"
pkg update -y
pkg install -y proot-distro termux-api git tar

echo "==> [3/4] 创建 Ubuntu 22.04 容器"
# 实机结论（2026-11-15）：本机连不上 registry-1.docker.io（返回 000），
# proot-distro 的 ubuntu:22.04 安装会卡死。所以这里优先用清华镜像的
# ubuntu-base 22.04 rootfs 手工建容器，docker 方式只作 fallback。
BASE_URL_DEFAULT="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
BASE_NAME="ubuntu-base-22.04-base-arm64"

install_from_base_tar() {
    local url="$1" name="$2" tar_path="$HOME/.proot-distro-cache/ubuntu-base.tar.gz"
    mkdir -p "$(dirname "$tar_path")"
    echo "    下载 rootfs（清华镜像）：$url"
    curl -L --retry 3 -o "$tar_path" "$url" || return 1
    echo "    已下载 $(du -h "$tar_path" | cut -f1)，开始建容器 $name"
    # proot-distro install --override-alias <name> <local tar>
    proot-distro install --override-alias "$name" "$tar_path" && return 0
    return 1
}

if proot-distro list 2>/dev/null | grep -qw "$BASE_NAME"; then
    CONTAINER="$BASE_NAME"; echo "    已存在 $BASE_NAME"
elif proot-distro list 2>/dev/null | grep -qw "ubuntu22"; then
    CONTAINER="ubuntu22"; echo "    已存在 ubuntu22"
elif proot-distro list 2>/dev/null | grep -qw "ubuntu"; then
    CONTAINER="ubuntu"; echo "    已存在 ubuntu（旧版 proot-distro）"
else
    echo "    优先方案：用 ubuntu-base 22.04 rootfs 建容器"
    if install_from_base_tar "${BASE_URL:-$BASE_URL_DEFAULT}" "$BASE_NAME"; then
        CONTAINER="$BASE_NAME"
    else
        echo "    rootfs 方案失败，退回 proot-distro 官方安装"
        if proot-distro install ubuntu:22.04 --name ubuntu22 2>/dev/null; then
            CONTAINER="ubuntu22"
        else
            proot-distro install ubuntu
            CONTAINER="ubuntu"
        fi
    fi
fi
# 把容器名记下来，后面的脚本自动读它
echo "$CONTAINER" > "$SELF_DIR/.container"
echo "    容器名：$CONTAINER"

echo "==> 容器检查"
proot-distro login "$CONTAINER" -- /bin/sh -c 'cat /etc/os-release | head -2; python3 --version 2>/dev/null || echo "python3 未安装（下一步会装）"'

echo "==> [4/4] 准备项目目录：$ALAS_HOST_DIR"
mkdir -p "$(dirname "$ALAS_HOST_DIR")"
if [ -n "$SRC_TAR" ]; then
    if [ ! -f "$SRC_TAR" ]; then
        echo "!!! 找不到压缩包：$SRC_TAR" >&2
        exit 1
    fi
    # 安全护栏：不要在目标目录内部执行本脚本（解包会移动/覆盖这个目录）
    case "$SELF_DIR" in
        "$ALAS_HOST_DIR"*) echo "!!! 不要在 $ALAS_HOST_DIR 里面执行本脚本，先 cd ~ 再跑" >&2; exit 1 ;;
    esac
    if [ -d "$ALAS_HOST_DIR" ] && [ -n "$(ls -A "$ALAS_HOST_DIR" 2>/dev/null || true)" ]; then
        BAK="$ALAS_HOST_DIR.bak.$(date +%Y%m%d-%H%M%S)"
        echo "    目标目录已存在，改名备份（不删除）：$BAK"
        mv "$ALAS_HOST_DIR" "$BAK"
    fi
    mkdir -p "$ALAS_HOST_DIR"
    tar -xf "$SRC_TAR" -C "$ALAS_HOST_DIR" --strip-components=1
    echo "    已解压到 $ALAS_HOST_DIR"
elif [ -d "$ALAS_HOST_DIR" ]; then
    echo "    目录已存在，沿用：$ALAS_HOST_DIR"
else
    echo "!!! 还没有项目文件。请任选一种方式：" >&2
    echo "    A. 把 AzurLaneAutoScript 压缩包放好后执行：" >&2
    echo "       bash 00-termux-setup.sh /sdcard/Download/AzurLaneAutoScript.tar.gz" >&2
    echo "    B. 手动把整个 AzurLaneAutoScript 目录复制到 $ALAS_HOST_DIR" >&2
    exit 1
fi

echo
echo "完成。下一步：bash 10-phone-adb.sh enable"
