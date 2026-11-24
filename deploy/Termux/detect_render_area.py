#!/usr/bin/env python3.10
"""自动测量碧蓝航线的渲染区，生成 config/screen_adapt.json。

为什么需要这个
--------------
ALAS 只认 1280x720（16:9），手机原生是 1080x2400（9:20）。
用 `wm size 1280x720` 会把整个系统压成 16:9，桌面都跟着变形（手机就没法用了）。

碧蓝航线自己是按 16:9 画布渲染、居中显示的，其余部分是黑边。所以正确做法是：
把游戏那 16:9 区域量出来，交给 ALAS 在设备层裁剪+缩放，系统分辨率一行都不动。

用法（容器内，游戏要已经在前台运行）：

    python3.10 /root/AzurLaneAutoScript/deploy/Termux/detect_render_area.py

它会：
    1. 用 adb 截一张当前屏幕
    2. 扫出非黑区域（游戏渲染区）
    3. 校验宽高比是否 16:9
    4. 写 config/screen_adapt.json
"""
import json
import os
import subprocess
import sys

import cv2
import numpy as np

ADB = os.environ.get('ADB', '/usr/bin/adb')
SERIAL = os.environ.get('SERIAL', '127.0.0.1:5555')
CONFIG = os.path.abspath(
    os.path.join(os.path.dirname(__file__), '..', '..', 'config', 'screen_adapt.json')
)

BLACK_THRESHOLD = 14       # 单通道超过这个值就算"不是黑边"
RATIO_TOLERANCE = 0.03     # 允许的 16:9 比例误差


def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL] + list(args), stderr=subprocess.STDOUT)


def screenshot():
    raw = adb('exec-out', 'screencap', '-p')
    img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise RuntimeError('截图解码失败')
    return img


def scan_line(brightness, index, axis):
    """在一条线/列上找非黑区间。

    Args:
        brightness: 2D 数组（最大通道值）
        index: 行号或列号
        axis: 'x' 表示沿横向扫（返回左右边界），'y' 表示沿纵向扫

    Returns:
        (start, end) 或 None
    """
    line = brightness[index] if axis == 'x' else brightness[:, index]
    idx = np.where(line > BLACK_THRESHOLD)[0]
    if len(idx) == 0:
        return None
    return int(idx[0]), int(idx[-1])


def detect(img):
    h, w = img.shape[:2]
    brightness = img.max(axis=2)

    # 横竖各扫三条线，取最一致的结果（避开 UI 元素干扰）
    xs = []
    for y in (int(h * 0.35), int(h * 0.5), int(h * 0.65)):
        r = scan_line(brightness, y, 'x')
        if r:
            xs.append(r)
    ys = []
    for x in (int(w * 0.35), int(w * 0.5), int(w * 0.65)):
        r = scan_line(brightness, x, 'y')
        if r:
            ys.append(r)

    if not xs:
        raise RuntimeError('横向扫描找不到非黑区域')

    # 取中位数，抗噪
    left = int(np.median([r[0] for r in xs]))
    right = int(np.median([r[1] for r in xs]))
    region_w = right - left + 1

    top, bottom = 0, h - 1
    if ys:
        t = int(np.median([r[0] for r in ys]))
        b = int(np.median([r[1] for r in ys]))
        # 只有在明显有上下黑边时才采用纵向结果
        if (b - t + 1) < h * 0.95:
            top, bottom = t, b
    region_h = bottom - top + 1

    return {
        'screen': (w, h),
        'OffsetX': left,
        'OffsetY': top,
        'RenderWidth': region_w,
        'RenderHeight': region_h,
    }


def main():
    print(f'[1] 截图: {ADB} -s {SERIAL}')
    img = screenshot()
    print(f'    屏幕 {img.shape[1]}x{img.shape[0]}')

    info = detect(img)
    rw, rh = info['RenderWidth'], info['RenderHeight']
    ratio = rw / rh if rh else 0
    print(f'[2] 渲染区 offset=({info["OffsetX"]},{info["OffsetY"]}) 尺寸 {rw}x{rh} 比例 {ratio:.3f}')

    expected = 16 / 9
    if abs(ratio - expected) > RATIO_TOLERANCE:
        print(f'!!! 渲染区比例 {ratio:.3f} 不是 16:9（{expected:.3f}）')
        print('    可能游戏不在前台，或这个游戏不是按 16:9 画布渲染的。')
        print('    先确保游戏停在主界面再跑本脚本。')
        return 1

    scale = rw / 1280
    print(f'[3] 缩放倍率 {scale:.4f}（横向 {rw/1280:.4f} / 纵向 {rh/720:.4f}）')

    cfg = {
        'Enable': True,
        'OffsetX': info['OffsetX'],
        'OffsetY': info['OffsetY'],
        'RenderWidth': rw,
        'RenderHeight': rh,
    }
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    with open(CONFIG, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f'[4] 已写入 {CONFIG}')
    print(json.dumps(cfg, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
