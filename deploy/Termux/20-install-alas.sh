#!/bin/bash
# =============================================================================
# 步骤 3/5：在 Ubuntu 容器内安装 ALAS 运行环境（aarch64 / Android 实机版）
#
# 前置：
#   proot-distro login ubuntu-base-22.04-base-arm64 \
#     --bind $HOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript
# 用法：
#   bash /root/AzurLaneAutoScript/deploy/Termux/20-install-alas.sh
#
# 本脚本可重复执行（幂等）。以下每一条都是实机踩过的坑，不要"优化"掉：
#   1) 必须 unset 从 Termux 继承的 ANDROID_* —— 否则 pip 走 platformdirs 的
#      android 分支，报 OSError: Cannot find path to android app folder。
#   2) 不用 venv —— Ubuntu 22.04 的 python3.10 -m venv 里没有 pip，ensurepip
#      报 CalledProcessError。容器本身隔离，用系统 Python 全局装。
#   3) cnocr 1.2.2 硬 pin numpy<1.20.0，必须过滤后单独 --no-deps 装。
#   4) 绝不装 gluoncv 依赖树 —— 会把 numpy 顶到 2.x、scipy 顶到 1.15、
#      拖进 opencv-python 5.x，与 numpy 1.23 的 ABI 全面冲突。
#   5) libmxnet.so 依赖 libcblas.so.3 和 libopencv_imgcodecs.so.4.5，
#      Ubuntu 22.04 的 SONAME 对不上，必须建软链。
#   6) websockets 是 WebUI 硬依赖，删了 uvicorn 会直接崩。
#   7) 任何 --force-reinstall 都要带 --no-deps，否则 numpy 会被顶到 2.x
#      （numpy 2 没有 np.Inf，ALAS 全线 import 失败）。
# =============================================================================
set -uo pipefail

ALAS_DIR="${ALAS_DIR:-/root/AzurLaneAutoScript}"
MIRROR="${MIRROR:-https://mirrors.aliyun.com/pypi/simple}"
MXNET_WHEEL="${MXNET_WHEEL:-https://raw.githubusercontent.com/binss/python-wheel/main/mxnet-1.9.1-py3-none-any.whl}"
PY="${PY:-/usr/bin/python3.10}"
MXLIB="${MXLIB:-/usr/local/mxnet}"

# ---- [0] 清掉从 Termux 继承的 Android/py 环境变量（坑 1）--------------------
for v in ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT \
         ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT EXTERNAL_STORAGE \
         ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH \
         LD_PRELOAD LD_LIBRARY_PATH PYTHONHOME PYTHONPATH; do
    unset "$v" 2>/dev/null || true
done
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8 LC_ALL=C.UTF-8 HOME=/root
export XDG_CACHE_HOME=/root/.cache PIP_CACHE_DIR=/root/.cache/pip
export DEBIAN_FRONTEND=noninteractive

if [ ! -f "$ALAS_DIR/alas.py" ]; then
    echo "!!! 在 $ALAS_DIR 里找不到 alas.py" >&2
    echo "    proot-distro login 时必须 --bind \$HOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript" >&2
    exit 1
fi
if [ "$(uname -m)" != "aarch64" ]; then
    echo "!!! 当前架构是 $(uname -m)，本脚本只针对 aarch64 手机" >&2
    exit 1
fi

echo "==> [1/8] apt 基础依赖"
apt-get update
apt-get install -y --no-install-recommends \
    python3 python3-pip python3-dev \
    git adb curl ca-certificates \
    build-essential gfortran \
    libopenblas-dev liblapack-dev libzmq3-dev \
    libopencv-dev \
    tzdata nano procps net-tools
ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime || true
echo "Asia/Shanghai" > /etc/timezone || true
date -R

echo "==> [2/8] 升级 pip / setuptools / wheel"
$PY -m pip install -U pip setuptools wheel -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -3

echo "==> [3/8] 先装 aarch64 版 mxnet（wheel 自带 libmxnet.so）"
# 顺序很重要：cnocr 的 install_requires 里有 mxnet，必须先就位，
# 否则 pip 会去 PyPI 找 mxnet（aarch64 无轮子）然后源码编译失败。
$PY -m pip install "$MXNET_WHEEL" 2>&1 | tail -3

# 把 libmxnet.so 统一放到 /usr/local/mxnet，路径固定好写进环境变量
MXLIB_REAL=$($PY -c "import mxnet, os; print(os.path.join(os.path.dirname(mxnet.__file__), 'libmxnet.so'))" 2>/dev/null)
mkdir -p "$MXLIB"
if [ -f "$MXLIB_REAL" ]; then
    cp -f "$MXLIB_REAL" "$MXLIB/libmxnet.so"
    echo "    libmxnet.so -> $MXLIB/libmxnet.so ($(du -h "$MXLIB/libmxnet.so" | cut -f1))"
else
    echo "!!! 找不到 libmxnet.so（$MXLIB_REAL），OCR 会起不来" >&2
fi

echo "==> [4/8] 修 libmxnet.so 缺的两个系统库（坑 5）"
# 4a. libcblas.so.3：Ubuntu 22.04 的 openblas 没有这个 SONAME，需要从 libopenblas* 软链
CBLAS=$(ls /usr/lib/aarch64-linux-gnu/openblas-pthread/libopenblas*.so.* 2>/dev/null | head -1)
if [ -z "${CBLAS:-}" ]; then
    CBLAS=$(ls /usr/lib/aarch64-linux-gnu/libopenblas*.so.* 2>/dev/null | head -1)
fi
if [ -n "${CBLAS:-}" ] && [ ! -e /usr/lib/aarch64-linux-gnu/libcblas.so.3 ]; then
    ln -sf "$CBLAS" /usr/lib/aarch64-linux-gnu/libcblas.so.3
    echo "    libcblas.so.3 -> $CBLAS"
fi
# 4b. libopencv_imgcodecs.so.4.5：Ubuntu 的库带 d 后缀（libopencv_imgcodecs.so.4.5d），批量建别名
if ! ls /usr/lib/aarch64-linux-gnu/libopencv_imgcodecs.so.4.5 >/dev/null 2>&1; then
    for f in /usr/lib/aarch64-linux-gnu/libopencv_*.so.*d; do
        [ -e "$f" ] || continue
        alias="${f%d}"
        ln -sf "$f" "$alias" 2>/dev/null || true
    done
    echo "    libopencv_*.so.4.5 别名已批量建好"
fi

echo "==> [5/8] 装 numpy 1.23.5 打底（其余包就不会把它顶上去）"
$PY -m pip install --no-deps "numpy==1.23.5" -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -2

echo "==> [6/8] 装其余依赖（过滤 cnocr，坑 3）"
grep -vi '^[[:space:]]*cnocr' "$ALAS_DIR/deploy/Termux/requirements-android.txt" > /tmp/req-nocnocr.txt
$PY -m pip install -r /tmp/req-nocnocr.txt -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -8
echo "    --- cnocr 单独 --no-deps ---"
$PY -m pip install --no-deps "cnocr==1.2.2" -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -2

echo "==> [7/8] 校准版本：把 numpy 拉回 1.23.5，并检查 cv2 完整性（坑 7）"
$PY -m pip install --no-deps --force-reinstall "numpy==1.23.5" -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -2
if ! $PY -c "import cv2; assert hasattr(cv2,'imdecode')" 2>/dev/null; then
    echo "    cv2 不完整（常见于先装过 opencv-python 5.x 又被卸载），重装 headless"
    $PY -m pip uninstall -y opencv-python opencv-python-headless 2>&1 | tail -2
    rm -rf /usr/local/lib/python3.10/dist-packages/cv2 \
           /usr/local/lib/python3.10/dist-packages/cv2*.dist-info
    $PY -m pip install --no-deps --force-reinstall "opencv-python-headless==4.10.0.84" \
        -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -2
    $PY -m pip install --no-deps --force-reinstall "numpy==1.23.5" \
        -i "$MIRROR" --trusted-host mirrors.aliyun.com 2>&1 | tail -2
fi

cat > /root/.alas-env.sh <<EOF
# ALAS on Android —— 每个容器会话都要 source 这个文件
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8
export HOME=/root
export MXNET_LIBRARY_PATH=$MXLIB/libmxnet.so
export LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:$MXLIB
export XDG_CACHE_HOME=/root/.cache
export PIP_CACHE_DIR=/root/.cache/pip
EOF
grep -q "alas-env.sh" /root/.bashrc 2>/dev/null || \
    printf '\n# ---- ALAS on Android ----\n. /root/.alas-env.sh\n' >> /root/.bashrc
echo "    环境变量已写入 /root/.alas-env.sh（并挂进 .bashrc）"
. /root/.alas-env.sh

echo "==> [8/8] 落地 deploy.yaml"
if [ -f "$ALAS_DIR/config/deploy.yaml" ]; then
    cp "$ALAS_DIR/config/deploy.yaml" "$ALAS_DIR/config/deploy.yaml.bak.$(date +%s)"
    echo "    旧 deploy.yaml 已备份"
fi
cp "$ALAS_DIR/deploy/Termux/deploy.template-Termux.yaml" "$ALAS_DIR/config/deploy.yaml"
mkdir -p "$ALAS_DIR/log"
/usr/bin/adb start-server >/dev/null 2>&1 || true

echo
echo "==> 自检（24 个模块 + mxnet 实算 + cv2 完整性）"
$PY - <<'PY'
import importlib, sys
mods = ["numpy","scipy","cv2","PIL","yaml","pydantic","adbutils","uiautomator2",
        "pywebio","starlette","uvicorn","rich","mxnet","cnocr","lz4","jellyfish",
        "zmq","gevent","aiofiles","onepush","psutil","tqdm","imageio","Crypto"]
ok, bad = [], []
for m in mods:
    try:
        importlib.import_module(m); ok.append(m)
    except Exception as e:
        bad.append(f"{m}: {type(e).__name__}: {str(e)[:120]}")
print(f"   {len(ok)}/{len(mods)} 导入成功 -> {', '.join(ok)}")
if bad:
    print("   FAIL:")
    for b in bad: print("        -", b)
    sys.exit(1)
import numpy as np
print("   numpy", np.__version__, "| np.Inf 存在:", hasattr(np, "Inf"))
import cv2
need = ["imdecode","imencode","resize","cvtColor","matchTemplate","imread"]
miss = [f for f in need if not hasattr(cv2, f)]
print("   cv2", cv2.__version__, "| 关键函数缺失:", miss or "无")
assert not miss and hasattr(np, "Inf")
import mxnet as mx
x = mx.nd.array([1.0,2.0,3.0])
print("   mxnet", mx.__version__, "1+2+3 =", float(x.sum().asscalar()))
import websockets
print("   websockets", websockets.__version__, "(WebUI 硬依赖)")
PY

echo
echo "全部完成。"
echo "下一步（都在 Termux 里执行）："
echo "   bash 10-phone-adb.sh enable      # 开 adbd 5555 + 系统级设置 + root 装 u2 服务"
echo "   bash run-alas.sh                 # 常驻启动 ALAS（注意：proot 必须常驻，见 30-start-alas.sh）"
