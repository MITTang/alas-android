#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ALAS 实例配置改成「真机自带挂机」需要的值（改 config/alas.json）

用法：
    /usr/bin/python3.10 deploy/Termux/apply-alas-config.py
    /usr/bin/python3.10 deploy/Termux/apply-alas-config.py --package com.bilibili.azurlane --serial 127.0.0.1:5555
    /usr/bin/python3.10 deploy/Termux/apply-alas-config.py --dry-run

关于截图方案（实测结论，Android 16 / SDK 36 真机）：
    DroidCast 与 DroidCast_raw 都不要用 —— ALAS 源码注释写明只支持 SDK 23~32，
    在 Android 16 上会返回 HTTP 500（"Failed to generate the screenshot on device"）。
    实测速度（1280x720）：
        uiautomator2  370~500 ms   <-- 本脚本默认，最快
        ADB (screencap -p)  580~610 ms
    两者都必须先把 u2 的 ATX 服务装上（见 25-install-u2.sh）。
"""
import argparse
import json
import os
import shutil
import sys

ALAS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CONFIG_DIR = os.path.join(ALAS_DIR, "config")
ALAS_JSON = os.path.join(CONFIG_DIR, "alas.json")
TEMPLATE_JSON = os.path.join(CONFIG_DIR, "template.json")


def set_path(cfg, dotted, value, changes):
    node = cfg
    keys = dotted.split(".")
    for k in keys[:-1]:
        node = node.setdefault(k, {})
    old = node.get(keys[-1], "<不存在>")
    if old != value:
        node[keys[-1]] = value
        changes.append((dotted, old, value))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="127.0.0.1:5555", help="ALAS 连接用的 serial（默认 127.0.0.1:5555）")
    ap.add_argument("--package", default="com.bilibili.azurlane", help="游戏包名（国服 com.bilibili.azurlane）")
    ap.add_argument("--screenshot", default="uiautomator2",
                    help="截图方案。真机 Android 12+ 用 uiautomator2（默认）或 ADB；DroidCast 系列不支持 SDK>32")
    ap.add_argument("--control", default="MaaTouch", help="控制方案，真机推荐 MaaTouch")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(ALAS_JSON):
        if not os.path.exists(TEMPLATE_JSON):
            print(f"!!! 找不到 {TEMPLATE_JSON}，项目目录不对？", file=sys.stderr)
            return 1
        shutil.copyfile(TEMPLATE_JSON, ALAS_JSON)
        print(f"已从 template.json 生成 {ALAS_JSON}")

    with open(ALAS_JSON, encoding="utf-8") as f:
        cfg = json.load(f)
    if "Alas" not in cfg:
        print("!!! alas.json 结构不认识（缺少 Alas 节点）", file=sys.stderr)
        return 1

    changes = []
    set_path(cfg, "Alas.Emulator.Serial", args.serial, changes)
    set_path(cfg, "Alas.Emulator.PackageName", args.package, changes)
    set_path(cfg, "Alas.Emulator.ScreenshotMethod", args.screenshot, changes)
    set_path(cfg, "Alas.Emulator.ControlMethod", args.control, changes)
    set_path(cfg, "Alas.Emulator.ScreenshotDedithering", False, changes)
    set_path(cfg, "Alas.Emulator.AdbRestart", False, changes)
    # 真机没有「重启模拟器」这回事，避免 ALAS 去拉起不存在的实例
    # 注意：EmulatorInfo 里的键名是小写的 name / path
    set_path(cfg, "Alas.EmulatorInfo.Emulator", "auto", changes)
    set_path(cfg, "Alas.EmulatorInfo.name", "", changes)
    set_path(cfg, "Alas.EmulatorInfo.path", "", changes)

    if not changes:
        print("配置本来就是目标值，无需修改。")
        return 0

    for key, old, new in changes:
        print(f"  {key}: {old!r} -> {new!r}")

    if args.dry_run:
        print("(--dry-run，未写盘)")
        return 0

    with open(ALAS_JSON, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"已写入 {ALAS_JSON}")
    print("提示：ALAS 运行中改这个文件不会即时生效，需要重启 ALAS 或在 WebUI 里改。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
