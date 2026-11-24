"""游戏渲染区适配：不改系统分辨率，把截图裁到 16:9 渲染区再缩回 1280x720。

为什么需要
----------
ALAS 的 UI 坐标、模板、OCR 全部基于 1280x720（16:9）。而手机原生是
1080x2400（9:20），直接跑 ALAS 会因为比例不符而全部失效。

改 `wm size 1280x720` 能让 ALAS 工作，但那是**全局**逻辑分辨率：
桌面、通知栏、所有 App 都会被压成 16:9，手机日常没法用（真实踩过的坑）。

实测发现碧蓝航线自己会按 16:9 画布渲染并居中显示：
    屏幕 2400x1080（1080x2400 横屏），游戏渲染区 x=240..2159 → 1920x1080
    1920x1080 恰好是 1280x720 的 1.5 倍

所以只要在设备层做两件事：
    · 截图：从屏幕截图上裁出渲染区，缩放回 1280x720 → ALAS 看到的就是 720p
    · 触摸：把 720p 坐标按同样比例反算回屏幕坐标

系统分辨率一行都不用改，桌面永远原生。

配置
----
读 `config/screen_adapt.json`（相对项目根目录）：

    {
      "Enable": true,
      "OffsetX": 240,        // 渲染区在屏幕上的左上角 x
      "OffsetY": 0,          // 渲染区在屏幕上的左上角 y
      "RenderWidth": 1920,   // 渲染区宽（必须 16:9）
      "RenderHeight": 1080   // 渲染区高
    }

其中 RenderWidth:RenderHeight 必须是 16:9，否则缩放出来的画面会变形、
模板匹配会失败。不同机型用 `detect_render_area.py` 量一次即可。
"""
import json
import os

import cv2

from module.logger import logger

BASE_WIDTH = 1280
BASE_HEIGHT = 720

_config_file = os.path.abspath(
    os.path.join(os.path.dirname(__file__), '..', '..', 'config', 'screen_adapt.json')
)

_default = {
    'Enable': False,
    'OffsetX': 0,
    'OffsetY': 0,
    'RenderWidth': 0,
    'RenderHeight': 0,
}

_cached = None
_warned = False


def config_path():
    return _config_file


def load_config(force=False):
    """
    Returns:
        dict: 配置字典。读不到时返回默认（Enable=False）。
    """
    global _cached
    if _cached is not None and not force:
        return _cached

    cfg = dict(_default)
    try:
        if os.path.exists(_config_file):
            with open(_config_file, 'r', encoding='utf-8') as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                cfg.update(loaded)
    except Exception as e:
        logger.warning(f'ScreenAdapt: failed to load {_config_file}: {e}')

    _cached = cfg
    return _cached


def enabled():
    """适配是否生效。"""
    global _warned
    cfg = load_config()
    if not cfg.get('Enable'):
        return False

    rw = int(cfg.get('RenderWidth') or 0)
    rh = int(cfg.get('RenderHeight') or 0)
    if rw <= 0 or rh <= 0:
        if not _warned:
            logger.warning('ScreenAdapt: Enable=true but RenderWidth/RenderHeight invalid, disabled')
            _warned = True
        return False

    # 必须是 16:9，否则缩放会变形
    if abs(rw / rh - BASE_WIDTH / BASE_HEIGHT) > 0.02:
        if not _warned:
            logger.warning(
                f'ScreenAdapt: RenderWidth/RenderHeight={rw}x{rh} is not 16:9, disabled')
            _warned = True
        return False

    return True


def adapt_image(image):
    """把屏幕截图裁成渲染区并缩放到 1280x720。

    Args:
        image (np.ndarray): 屏幕原始截图，形状 (H, W, 3)。

    Returns:
        np.ndarray: 1280x720 的截图；未启用或尺寸不匹配时原样返回。
    """
    if image is None or not enabled():
        return image

    cfg = load_config()
    ox = int(cfg.get('OffsetX') or 0)
    oy = int(cfg.get('OffsetY') or 0)
    rw = int(cfg.get('RenderWidth') or 0)
    rh = int(cfg.get('RenderHeight') or 0)

    h, w = image.shape[:2]
    if w < ox + rw or h < oy + rh:
        # 截图比渲染区还小，说明方向或尺寸和预期不符，别硬裁
        if not _warned:
            logger.warning(
                f'ScreenAdapt: screenshot {w}x{h} smaller than render area '
                f'（offset {ox},{oy} + {rw}x{rh}），skip')
        return image

    region = image[oy:oy + rh, ox:ox + rw]
    if region.shape[1] == BASE_WIDTH and region.shape[0] == BASE_HEIGHT:
        return region
    return cv2.resize(region, (BASE_WIDTH, BASE_HEIGHT), interpolation=cv2.INTER_AREA)


def adapt_point(x, y):
    """把一个 1280x720 坐标系下的点换算成屏幕坐标。

    Args:
        x (int): 720p 坐标系下的 x。
        y (int): 720p 坐标系下的 y。

    Returns:
        tuple[int, int]: 屏幕坐标。
    """
    if not enabled():
        return int(x), int(y)

    cfg = load_config()
    ox = int(cfg.get('OffsetX') or 0)
    oy = int(cfg.get('OffsetY') or 0)
    rw = int(cfg.get('RenderWidth') or 0)
    rh = int(cfg.get('RenderHeight') or 0)

    return (
        int(x * rw / BASE_WIDTH + ox),
        int(y * rh / BASE_HEIGHT + oy),
    )


def status():
    """给启动脚本打印一行状态用。"""
    cfg = load_config()
    if not enabled():
        return f'ScreenAdapt disabled (config={_config_file})'
    return ('ScreenAdapt enabled: render '
            f"{int(cfg['RenderWidth'])}x{int(cfg['RenderHeight'])} "
            f"at offset ({int(cfg['OffsetX'])},{int(cfg['OffsetY'])}) "
            f"-> {BASE_WIDTH}x{BASE_HEIGHT}")
