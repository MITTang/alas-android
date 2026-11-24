#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ALAS 安卓真机移植 —— 环境自检

用法（容器内、项目根目录）：
    /usr/bin/python3.10 deploy/Termux/preflight.py --quick   # 启动前快检
    /usr/bin/python3.10 deploy/Termux/preflight.py           # 完整自检（含 OCR 实跑）

退出码：0 全部通过；1 有硬性失败项（--full 模式下 OCR 失败只警告不算硬失败）。
"""
import argparse
import io
import os
import subprocess
import sys
import traceback

ALAS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ADB = os.environ.get("ADB", "/usr/bin/adb")
PORT = os.environ.get("ADB_TCP_PORT", "5555")
SERIAL = os.environ.get("ALAS_SERIAL", f"127.0.0.1:{PORT}")
EXPECTED = (1280, 720)

results = []


def check(name, func, hard=True):
    print(f"[*] {name} ... ", end="", flush=True)
    try:
        detail = func()
        print("OK" + (f"   {detail}" if detail else ""))
        results.append((name, True, hard, detail))
        return True
    except Exception as e:
        print("FAIL")
        print(f"    {type(e).__name__}: {e}")
        if os.environ.get("PREFLIGHT_TRACE"):
            traceback.print_exc()
        results.append((name, False, hard, f"{type(e).__name__}: {e}"))
        return False


def sh(cmd, timeout=20, check_rc=True):
    p = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
    if check_rc and p.returncode != 0:
        raise RuntimeError(f"命令失败({p.returncode}): {cmd}\n{(p.stderr or p.stdout).strip()[:400]}")
    return p.stdout


def t_platform():
    import platform
    machine = platform.machine()
    if machine not in ("aarch64", "arm64"):
        raise RuntimeError(f"架构是 {machine}，本移植只针对 aarch64")
    return f"{sys.version.split()[0]} / {machine}"


def t_imports():
    mods = ["numpy", "scipy", "cv2", "PIL", "yaml", "pydantic", "adbutils",
            "uiautomator2", "pywebio", "rich", "zerorpc", "gevent", "zmq"]
    bad = []
    for m in mods:
        try:
            __import__(m)
        except Exception as e:
            bad.append(f"{m}({type(e).__name__})")
    if bad:
        raise RuntimeError("导入失败: " + ", ".join(bad))
    import numpy
    return f"numpy {numpy.__version__}"


def t_mxnet():
    import mxnet
    from mxnet import nd
    total = float(nd.array([1.0, 2.0, 3.0]).sum().asscalar())
    if total != 6.0:
        raise RuntimeError(f"mxnet 计算结果异常: {total}")
    return f"mxnet {mxnet.__version__} (1+2+3={total:.0f})"


def t_adb_binary():
    if not os.path.exists(ADB):
        raise RuntimeError(f"找不到 adb: {ADB}")
    out = sh([ADB, "version"], timeout=15)
    return out.splitlines()[0].strip()


def t_adb_connect():
    sh(f'"{ADB}" connect {SERIAL}', timeout=20, check_rc=False)
    state = ""
    for _ in range(20):
        out = sh([ADB, "devices"], timeout=15)
        for line in out.splitlines()[1:]:
            parts = line.split()
            if parts and parts[0] == SERIAL:
                state = parts[1] if len(parts) > 1 else ""
        if state == "device":
            return f"{SERIAL} device"
        if state == "unauthorized":
            raise RuntimeError("未授权：请在手机屏幕点【允许 USB 调试】，或执行 10-phone-adb.sh trust")
        import time
        time.sleep(1)
    raise RuntimeError(f"连不上 {SERIAL}（当前状态: {state or '未出现'}）")


def t_shell():
    out = sh([ADB, "-s", SERIAL, "shell", "echo ok"], timeout=15).strip()
    if out != "ok":
        raise RuntimeError(f"shell 返回异常: {out!r}")
    return "shell 可用"


def t_resolution():
    out = sh([ADB, "-s", SERIAL, "shell", "wm size"], timeout=15)
    # 输出形如：Physical size: 1080x2400 / Override size: 1280x720
    override = ""
    physical = ""
    for line in out.splitlines():
        if "Override size" in line:
            override = line.split(":")[-1].strip()
        if "Physical size" in line:
            physical = line.split(":")[-1].strip()
    current = override or physical
    if current != "1280x720":
        if "--fix" in sys.argv:
            sh([ADB, "-s", SERIAL, "shell", "wm", "size", "1280x720"], timeout=15)
            return "已修正为 1280x720"
        raise RuntimeError(f"当前是 {current}，需要 1280x720（执行 adb shell wm size 1280x720）")
    return current


def _grab():
    from PIL import Image
    p = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                       capture_output=True, timeout=30)
    if p.returncode != 0 or len(p.stdout) < 1000:
        raise RuntimeError(f"screencap 失败，返回 {len(p.stdout)} 字节")
    return Image.open(io.BytesIO(p.stdout)).convert("RGB")


def t_screenshot():
    import numpy as np
    img = _grab()
    if img.size != EXPECTED:
        raise RuntimeError(f"截图尺寸 {img.size}，需要 {EXPECTED}")
    arr = np.asarray(img)
    if int(arr[:100, :100].sum()) == 0:
        raise RuntimeError("截图是纯黑（游戏没在前台，或屏幕被锁）")
    return f"{img.size[0]}x{img.size[1]}"


def t_ocr_model():
    """加载 ALAS 专用 OCR 模型并对真实截图做一次推理（最容易被忽略的坑就是这里）。"""
    os.chdir(ALAS_DIR)
    if ALAS_DIR not in sys.path:
        sys.path.insert(0, ALAS_DIR)
    from module.ocr.models import OCR_MODEL
    ocr = OCR_MODEL.azur_lane
    img = _grab()
    crop = img.crop((600, 0, 700, 40))  # 顶部中段一小块，只要能跑通即可
    import numpy as np
    result = ocr.ocr(np.asarray(crop))
    return f"模型可推理，返回 {len(result if isinstance(result, list) else [result])} 项"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true", help="只做启动前快检（不含 OCR）")
    ap.add_argument("--fix", action="store_true", help="自动修正分辨率")
    args = ap.parse_args()

    print(f"ALAS_DIR = {ALAS_DIR}")
    print(f"ADB      = {ADB}")
    print(f"SERIAL   = {SERIAL}\n")

    check("Python / 架构", t_platform)
    check("Python 依赖", t_imports)
    check("mxnet 推理库", t_mxnet)
    check("adb 可执行", t_adb_binary)
    check("adb 连接本机", t_adb_connect)
    check("adb shell", t_shell)
    check("分辨率 1280x720", t_resolution)
    check("截图", t_screenshot)
    if not args.quick:
        check("OCR 模型 + 实图推理", t_ocr_model, hard=False)

    print()
    failed_hard = [r for r in results if not r[1] and r[2]]
    warned = [r for r in results if not r[1] and not r[2]]
    if warned:
        print("警告（不阻塞启动）：")
        for name, _, _, detail in warned:
            print(f"  - {name}: {detail}")
    if failed_hard:
        print("硬性失败项：")
        for name, _, _, detail in failed_hard:
            print(f"  - {name}: {detail}")
        return 1
    print("自检通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
