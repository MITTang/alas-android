"""
ALAS 单任务命令行入口。

为什么需要它：
    alas.py 的 `__main__` 只跑 `loop()`，也就是空闲轮询调度器，没有命令行参数。
    官方 WebUI 的"启动"按钮本质上是让调度器去读 config 里的任务队列，
    而 pywebio 前端在当前环境下点击不可靠。
    这里直接调用 AzurLaneAutoScript.run(command)，跳过 GUI 直接把一个任务跑起来，
    APP 只需要执行 `python3.10 alas_cli.py <任务名>` 即可。

用法：
    python3.10 alas_cli.py reward
    python3.10 alas_cli.py commission
    python3.10 alas_cli.py list          # 列出可用任务名
"""
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
sys.path.insert(0, os.path.join(BASE, "deploy", "Termux"))
os.chdir(BASE)

import inflection  # noqa: E402

from alas import AzurLaneAutoScript  # noqa: E402

# 常见任务，按"日常一条龙"的执行先后排列。名字对应 alas.py 里的同名方法。
TASKS = [
    "reward",
    "commission",
    "tactical",
    "research",
    "dorm",
    "meowfficer",
    "guild",
    "exercise",
    "dailymission",
    "daily",
    "hard",
    "event",
    "main",
    "opsi_daily",
    "opsi_explore",
    "opsi_shop",
    "opsi_voucher",
    "opsi_obscure",
    "opsi_abyssal",
]


def list_tasks():
    print("可用任务：")
    for t in TASKS:
        print("  " + t)
    print("\n用法：python3.10 alas_cli.py <任务名>")


def main():
    if len(sys.argv) < 2:
        list_tasks()
        return 2

    task = sys.argv[1].strip()
    if task in ("list", "-l", "--list"):
        list_tasks()
        return 0

    if not hasattr(AzurLaneAutoScript, inflection.underscore(task)):
        print(f"未知任务：{task}")
        list_tasks()
        return 2

    print(f"[ALAS] 准备运行任务：{task}", flush=True)
    alas = AzurLaneAutoScript(config_name="alas")

    # 先确保游戏在前台主界面。ALAS 跑任务时截图抓的是屏幕本身，
    # 如果游戏在后台、屏幕上是别的应用，截图内容和预期完全不符会直接失败。
    print("[ALAS] 先把游戏切到前台并回到主界面 ...", flush=True)
    ok = alas.run("goto_main")
    if not ok:
        print("[ALAS] goto_main 失败，任务中止", flush=True)
        return 1

    print(f"[ALAS] 开始执行任务 {task} ...", flush=True)
    ok = alas.run(inflection.underscore(task))
    print(f"[ALAS] 任务 {task} 结果：{'成功' if ok else '失败'}", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
