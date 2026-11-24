#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ALAS 热切换入口：代替直跑 `alas.py`，让改关卡像 PC 版那样自动生效。

为什么需要这个文件
------------------
PC 版改完「打哪一关」不需要重启：ALAS 每跑完一轮地图都会调一次
`config.task_switched()`（module/campaign/run.py:470），命中就抛 TaskEnd 把
当前任务收尾，外层 loop 再 `del_cached_property(self, 'config')`
（alas.py:614）重读 config/alas.json、重新派发 Main —— 于是下一轮直接去打
新图，游戏停在地图界面不动，不重启 proot、也不重启游戏。
任务队列本来就是「上面跑完接着跑下面」，不需要额外机制。

那移植版为什么不行
------------------
`task_switched()` 只比较「下一个该跑的任务是谁」（config.py:614-622）。
改关卡时任务名依然是 Main，它判断成「继续」，于是继续刷旧图 ——
这就是「改了没反应，只能整个重启」的原因；而重启是 APP 那个
「立即生效」按钮做的（run-alas.sh restart 会把 proot 和游戏一起重开）。

这里补两条判定，命中后就照原样抛 TaskEnd，剩下的交给 ALAS 自己的
re-read 流程，**换图全程不重启**：

  1. 磁盘上 `Main.Campaign.Name` 和内存里正在跑的不一样 → 切
  2. 存在 `config/hot_reload` 标记文件（APP 的「立即生效」写它）→ 切

为什么次数 RunCount 不做同样的比对
----------------------------------
`StopCondition.RunCount` 会被 Campaign 自己每轮 `-= 1` 并写盘
（run.py:453-454）。拿磁盘值跟内存值比，每一轮都会「变化」，
会变成每一轮都切任务、无限来回切。关卡名只由用户改，ALAS 自己不动它，
所以只有它适合当热切换信号。
次数改成「点『立即生效』→ 标记文件 → 下一轮结束切一次 → 重读配置」，
一次切换就把新次数一起带进去。

放 deploy/Termux 而不是项目根
-----------------------------
alas_update.py 的 KEEP 清单里有 `deploy/Termux`，更新时先备份再还原；
放项目根会被 zip 覆盖掉，启动脚本就找不到这个文件了。
"""
import os
import sys
import datetime as _dt
import re as _re

# 定位 ALAS 项目根。30-start-alas.sh 已经 cd 到 $ALAS_DIR，但运行脚本时
# sys.path[0] 是脚本所在目录（deploy/Termux），不是 cwd，所以要显式补进去，
# 否则 import module.xxx 会失败。
BASE = os.environ.get('ALAS_DIR') or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
os.chdir(BASE)
if BASE not in sys.path:
    sys.path.insert(0, BASE)

from module.config.config import AzurLaneConfig
# 注意：deep_get 在 module.config.deep，不在 module.config.utils。
# 写错模块名会让整个调度器在 import 阶段就崩（实测 ImportError 后 run-alas.sh
# 直接报「调度器没进循环」，挂机根本起不来）。
from module.config.deep import deep_get
from module.logger import logger

FLAG = os.path.join(BASE, 'config', 'hot_reload')

# =============================================================================
# 定时（Scheduler.NextRun）兜底：让脏值不会把调度器打死
#
# ALAS 的 config_update 用 datetime.fromisoformat 把配置里的时间字符串转成
# datetime，而 fromisoformat 只认「2026-11-22 09:30:00」这种两位小时。
# 只要写了「2026-11-22 9:30:00」（小时没补零，界面手输很容易这样），
# 转换就失败、next_run 保持字符串，alas.py:510 拿它跟 datetime.now() 比较：
#     TypeError: '>' not supported between instances of 'str' and 'datetime.datetime'
# 整个调度器在第一轮就崩，日志刷一屏 traceback，看起来就是「定时一设就挂」。
#
# 这里把 Function 的 next_run 统一过一道：能解析就转 datetime，解析不了
# 就当「立刻运行」（绝不保留字符串）。属于兜底，正常写入路径由
# alas_config.py 的 normalize_time 保证格式正确。
# =============================================================================
from module.config.config import Function as _Function

_orig_function_init = _Function.__init__

_TIME_RE = _re.compile(
    r'^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?')


def _time_to_datetime(v):
    if isinstance(v, _dt.datetime):
        return v
    if not isinstance(v, str):
        # None / 数字之类：当作立刻就绪，别让它参与类型比较
        return _dt.datetime.now()
    s = v.strip().replace('/', '-').replace('T', ' ')
    m = _TIME_RE.match(s)
    if m:
        try:
            return _dt.datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)),
                                int(m.group(4)), int(m.group(5)), int(m.group(6) or 0))
        except ValueError:
            pass
    try:
        return _dt.datetime.fromisoformat(s)
    except ValueError:
        pass
    logger.warning(f'[定时] NextRun 解析不了（{v!r}），按「立刻运行」处理')
    return _dt.datetime.now()


def _function_init(self, data):
    _orig_function_init(self, data)
    self.next_run = _time_to_datetime(self.next_run)


_Function.__init__ = _function_init

# 原方法要留着：它内部会 self.load() 重读磁盘、并判断「该不该换任务」，
# 换任务（比如到点了该跑别的）这件事仍然全靠它。
_orig_task_switched = AzurLaneConfig.task_switched


def _chapter_changed(config):
    """磁盘上的主线关卡是否和内存里正在跑的不一样。

    只比关卡名，理由见文件头：RunCount 会被 ALAS 自己递减，不能当信号。
    config.data 在 _orig_task_switched 里已经 reload 过，是最新的磁盘内容。
    """
    disk = deep_get(config.data, keys='Main.Campaign.Name', default=None)
    if disk is None:
        return False
    running = getattr(config, 'Campaign_Name', None)
    if running is None:
        return False
    # 归一化一点：'16-1' 与 '16_1'、首尾空格都不该算改动
    norm = lambda v: str(v).strip().replace('_', '-').lower()
    if norm(disk) != norm(running):
        logger.info(f'[热切换] 关卡被改动：正在跑 {running} → 磁盘 {disk}')
        return True
    return False


def _flag_set():
    """APP 的「立即生效」写下的标记文件。消费掉它，避免下次启动又触发一次。"""
    if not os.path.exists(FLAG):
        return False
    try:
        os.remove(FLAG)
    except OSError:
        pass
    logger.info('[热切换] 收到「立即生效」请求，本轮结束后应用新配置')
    return True


def task_switched(self):
    # 原逻辑优先：它已经 load() 过配置，也处理了更新事件和到点换任务
    if _orig_task_switched(self):
        return True
    try:
        # 只有正在跑主线时才关心这两条，别的任务改了关卡不该被它打断
        if getattr(self.task, 'command', '') != 'Main':
            return False
        if _flag_set():
            return True
        if _chapter_changed(self):
            return True
    except Exception as e:
        # 热切换是锦上添花，出任何岔子都不该让挂机崩掉
        logger.warning(f'[热切换] 检查失败，按原逻辑继续：{e!r}')
    return False


AzurLaneConfig.task_switched = task_switched


if __name__ == '__main__':
    from alas import AzurLaneAutoScript

    logger.info('[热切换] 已装载：改关卡会在下一轮地图结束后自动生效，不重启游戏')
    alas = AzurLaneAutoScript()
    alas.loop()
