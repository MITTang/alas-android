"""
ALAS 配置读写工具（在容器内运行，供 APP 调用）

为什么单独做这个：
    APP 是手写 Java、没有 gradle，不适合在手机里做复杂的 JSON 编辑。
    alas.json 是嵌套结构（Reward → Scheduler → Enable），手写 JSON 字符串极易出错。
    所以把读写都放在容器侧用 python 的 json 模块做，APP 只负责发指令和显示结果。

用法：
    python3.10 alas_config.py scheduler              # 所有任务的调度状态，输出 JSON
    python3.10 alas_config.py dump <Block>           # 某个任务块的全部参数，输出 JSON
    python3.10 alas_config.py get <Dotted.Path>      # 读单个值
    python3.10 alas_config.py set <Dotted.Path> <v>  # 写单个值，自动转 bool/int/float/str
    python3.10 alas_config.py enable <Block> <0|1>   # 开关某个挂机任务
    python3.10 alas_config.py reset                  # 所有任务的「下次运行」改为现在（换号用）
"""
import datetime
import json
import os
import re
import shutil
import sys

BASE = "/root/AzurLaneAutoScript"
CFG = os.path.join(BASE, "config", "alas.json")
TPL = os.path.join(BASE, "config", "template.json")


def load():
    if not os.path.exists(CFG):
        # 首次运行前 alas.json 还不存在，用官方模板生成一份，保证结构完整
        if not os.path.exists(TPL):
            die("找不到 %s，也没有模板 %s" % (CFG, TPL))
        d = json.load(open(TPL, encoding="utf-8"))
        write(d)
        return d
    return json.load(open(CFG, encoding="utf-8"))


def write(d):
    os.makedirs(os.path.dirname(CFG), exist_ok=True)
    if os.path.exists(CFG):
        try:
            shutil.copy2(CFG, CFG + ".bak")
        except Exception:
            pass
    # indent 保持和官方一致，避免整个文件被压平导致 diff 巨大
    with open(CFG, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)


def die(msg):
    print("ERROR: " + msg)
    sys.exit(2)


def scheduler(d):
    """列出所有带 Scheduler 的任务块。这就是 WebUI 里『调度器 / 等待中 / 队列中』的数据源"""
    out = []
    for name, blk in d.items():
        if not isinstance(blk, dict):
            continue
        s = blk.get("Scheduler")
        if not isinstance(s, dict) or "Command" not in s:
            continue
        cmd = s.get("Command")
        if not cmd:
            continue
        out.append({
            "block": name,
            "command": cmd,
            "enable": bool(s.get("Enable")),
            "next_run": str(s.get("NextRun", "")),
            "success": str(s.get("SuccessInterval", "")),
            "failure": str(s.get("FailureInterval", "")),
            "server_update": str(s.get("ServerUpdate", "")),
        })
    out.sort(key=lambda x: x["block"])
    return out


# 手机端最常改的参数。APP 不做完整的 1300 项表单，只把这些做成可编辑列表，
# 其余参数走 `dump <Block>` 按任务块浏览。
#
# 主线那一组要放全：用户最常干的事就是「换一关打」，如果只丢一个开关进去，
# 看起来就像根本没有这个功能。下面把 Campaign / StopCondition / Fleet /
# Submarine 里真正会被改的项都列出来，名字用中文，用户不用去啃 Fleet.FleetOrder。
COMMON = [
    ("设备", "截图方式", "Alas.Emulator.ScreenshotMethod", None),
    ("设备", "控制方式", "Alas.Emulator.ControlMethod", None),
    ("设备", "设备串号", "Alas.Emulator.Serial", None),
    ("设备", "游戏包名", "Alas.Emulator.PackageName", None),
    ("设备", "服务器", "Alas.Emulator.ServerName", ["cn", "en", "jp", "kr", "tw"]),
    ("设备", "截图间隔", "Alas.Optimization.ScreenshotInterval", None),

    # ---- 主线打捞 ----
    ("主线", "打哪一关", "Main.Campaign.Name", "CHAPTER"),
    ("主线", "关卡难度", "Main.Campaign.Mode", ["normal", "hard"]),
    ("主线", "周回次数", "Main.StopCondition.RunCount", None),
    ("主线", "石油下限", "Main.StopCondition.OilLimit", None),
    ("主线", "停刷条件", "Main.StopCondition.MapAchievement",
     ["non_stop", "100_percent_clear", "map_3_stars", "threat_safe",
      "threat_safe_without_3_stars"]),
    ("主线", "自动升图", "Main.StopCondition.StageIncrease", None),
    ("主线", "出新船就换图", "Main.StopCondition.GetNewShip", None),
    ("主线", "道中舰队", "Main.Fleet.Fleet1", None),
    ("主线", "BOSS 舰队", "Main.Fleet.Fleet2", None),
    ("主线", "出击顺序", "Main.Fleet.FleetOrder",
     ["fleet1_mob_fleet2_boss", "fleet1_boss_fleet2_mob", "fleet1_all", "fleet2_all"]),
    ("主线", "自动寻敌", "Main.Campaign.UseAutoSearch", None),
    ("主线", "连续作战", "Main.Campaign.UseClearMode", None),
    ("主线", "防伏击", "Main.Campaign.AmbushEvade", None),
    ("主线", "潜艇模式", "Main.Submarine.Mode",
     ["do_not_use", "hunt_mode", "boss_mode", "standby_mode"]),
    ("主线", "潜艇舰队", "Main.Submarine.Fleet", None),
    ("主线", "心情控制", "Main.Emotion.Fleet1Control",
     ["keep_exp_days", "calculate", "prevent_green_face", "prevent_red_face"]),
    ("主线", "主线挂机", "Main.Scheduler.Enable", None),

    ("日常收菜", "收石油", "Reward.Reward.CollectOil", None),
    ("日常收菜", "收金币", "Reward.Reward.CollectCoin", None),
    ("日常收菜", "收经验", "Reward.Reward.CollectExp", None),
    ("日常收菜", "收任务奖励", "Reward.Reward.CollectMission", None),
    ("日常收菜", "挂机开关", "Reward.Scheduler.Enable", None),
    ("委托", "过滤器预设", "Commission.Commission.PresetFilter", None),
    ("委托", "筛选大委托", "Commission.Commission.DoMajorCommission", None),
    ("科研", "魔方使用", "Research.Research.UseCube", None),
    ("科研", "金币使用", "Research.Research.UseCoin", None),
    ("科研", "过滤器预设", "Research.Research.PresetFilter", None),
    ("宿舍", "收取", "Dorm.Dorm.Collect", None),
    ("宿舍", "投喂", "Dorm.Dorm.Feed", None),
    ("战术教室", "挂机开关", "Tactical.Scheduler.Enable", None),
    ("战术教室", "自动加学员", "Tactical.AddNewStudent.Enable", None),
    ("演习", "挂机开关", "Exercise.Scheduler.Enable", None),
    ("演习", "对手选择", "Exercise.Exercise.OpponentChooseMode", None),
    ("指挥喵", "挂机开关", "Meowfficer.Scheduler.Enable", None),
    ("大舰队", "挂机开关", "Guild.Scheduler.Enable", None),
]

# 「打哪一关」的候选。ALAS 官方的候选是跟着账号已通关进度动态变的，
# 在手机上算不动，所以直接列成静态表 —— 覆盖常规 10~15 章，
# 用户在 APP 里点一下就换图，不用手打 "12-4" 这种还得记格式。
def _scan_main_chapters():
    """
    直接扫 ALAS 自己的关卡文件生成关卡列表，而不是硬编码。

    campaign/campaign_main/ 下每张图一个 campaign_<章>_<关>.py，ALAS 支持到哪
    就列到哪。原来这里写死 10-1~15-4，结果 16-1~16-4 和 1-1~9-4 都选不到，
    用户报「看不到 16 关以后的内容」就是这个原因。以后游戏加新章节，只要
    ALAS 更新带上了新文件，这里自动跟上，不用再改代码。
    """
    out = []
    d = os.path.join(BASE, "campaign", "campaign_main")
    try:
        names = os.listdir(d)
    except OSError:
        names = []
    pairs = set()
    for n in names:
        m = re.match(r"campaign_(\d+)_(\d+)\.py$", n)
        if m:
            pairs.add((int(m.group(1)), int(m.group(2))))
    for ch, st in sorted(pairs):
        out.append("%d-%d" % (ch, st))
    return out


CHAPTERS = _scan_main_chapters()
if not CHAPTERS:
    # 扫不到（例如 ALAS 目录结构变了）就退回一份覆盖全主线的兜底列表
    for _ch in range(1, 17):
        for _st in (1, 2, 3, 4):
            CHAPTERS.append("%d-%d" % (_ch, _st))


def _scan_events():
    """
    活动图的合法值就是 campaign/ 下除 campaign_main 以外的目录名
    （event_20231221_cn 这种），同样走扫描，避免写死过期活动。
    """
    out = ["campaign_main"]
    try:
        for n in sorted(os.listdir(os.path.join(BASE, "campaign"))):
            if n.startswith("event_") and os.path.isdir(os.path.join(BASE, "campaign", n)):
                out.append(n)
    except OSError:
        pass
    return out


# 事件图（活动）入口
EVENT_CHAPTERS = _scan_events()


def options_for(path, options):
    """
    把 COMMON 里声明的候选展开成真正的列表。

    特殊值 "CHAPTER" 表示「关卡图」，用静 CHAPTERS。
    其余 None 表示自由输入（数字/字符串），返回 None 让 APP 弹输入框。
    """
    if options is None:
        return None
    if options == "CHAPTER":
        return CHAPTERS
    if isinstance(options, list):
        return options
    return None



def safe_get(d, path):
    """取不到就返回 None，不打印错误 —— common() 的输出必须是干净的 JSON"""
    cur = d
    for seg in path.split("."):
        if not isinstance(cur, dict) or seg not in cur:
            return None
        cur = cur[seg]
    return cur


def common(d):
    """一次性返回所有常用参数，避免手机端为每个参数各起一次 proot"""
    out = []
    for group, name, path, opts in COMMON:
        val = safe_get(d, path)
        if val is None:
            continue
        item = {"group": group, "name": name, "path": path,
                "value": val, "type": type(val).__name__}
        o = options_for(path, opts)
        if o:
            item["options"] = o
        out.append(item)
    return out


# =============================================================================
# 字段汉化表
#
# 按「块内相对路径」写 key，这样 common（完整路径）和 dump（块内路径）
# 都能复用同一份表。没收录的字段会退回英文原名，不会崩。
# 主力覆盖 Main / 通用 Scheduler，这是用户真正会去改的两处；
# 其余任务块按需补，别为了凑数写一堆没人改的翻译。
# =============================================================================
LEAF_ZH = {
    # ---- 调度器通用 ----
    "Scheduler.Enable": "挂机开关",
    "Scheduler.NextRun": "下次运行",
    "Scheduler.Command": "任务名",
    "Scheduler.SuccessInterval": "成功间隔（秒）",
    "Scheduler.FailureInterval": "失败间隔（秒）",
    "Scheduler.ServerUpdate": "每日刷新时间",

    # ---- 主线 Campaign ----
    "Campaign.Name": "打哪一关",
    "Campaign.Event": "活动名",
    "Campaign.Mode": "关卡难度",
    "Campaign.UseClearMode": "连续作战",
    "Campaign.UseFleetLock": "舰队锁定",
    "Campaign.UseAutoSearch": "自动寻敌",
    "Campaign.Use2xBook": "双倍经验书",
    "Campaign.AmbushEvade": "规避伏击",

    # ---- 停止条件 ----
    "StopCondition.OilLimit": "石油下限",
    "StopCondition.RunCount": "周回次数",
    "StopCondition.MapAchievement": "停刷条件",
    "StopCondition.StageIncrease": "自动升图",
    "StopCondition.GetNewShip": "出新船就换图",
    "StopCondition.ReachLevel": "练到多少级",

    # ---- 舰队 ----
    "Fleet.Fleet1": "道中舰队",
    "Fleet.Fleet2": "BOSS 舰队",
    "Fleet.Fleet1Formation": "一队阵型",
    "Fleet.Fleet2Formation": "二队阵型",
    "Fleet.Fleet1Mode": "一队模式",
    "Fleet.Fleet2Mode": "二队模式",
    "Fleet.Fleet1Step": "一队步数",
    "Fleet.Fleet2Step": "二队步数",
    "Fleet.FleetOrder": "出击顺序",

    # ---- 潜艇 ----
    "Submarine.Fleet": "潜艇舰队",
    "Submarine.Mode": "潜艇模式",
    "Submarine.AutoSearchMode": "潜艇待机方式",
    "Submarine.DistanceToBoss": "距 BOSS 距离",

    # ---- 心情 ----
    "Emotion.Mode": "心情计算方式",
    "Emotion.Fleet1Value": "一队心情值",
    "Emotion.Fleet2Value": "二队心情值",
    "Emotion.Fleet1Record": "一队记录时间",
    "Emotion.Fleet2Record": "二队记录时间",
    "Emotion.Fleet1Control": "一队心情控制",
    "Emotion.Fleet2Control": "二队心情控制",
    "Emotion.Fleet1Recover": "一队恢复方式",
    "Emotion.Fleet2Recover": "二队恢复方式",
    "Emotion.Fleet1Oath": "一队全员婚舰",
    "Emotion.Fleet2Oath": "二队全员婚舰",

    # ---- 血量控制 ----
    "HpControl.UseHpBalance": "血量均衡",
    "HpControl.UseEmergencyRepair": "紧急维修",
    "HpControl.UseLowHpRetreat": "低血量撤退",
    "HpControl.HpBalanceThreshold": "均衡阈值",
    "HpControl.HpBalanceWeight": "均衡权重",
    "HpControl.RepairUseSingleThreshold": "单体维修阈值",
    "HpControl.RepairUseMultiThreshold": "群体维修阈值",
    "HpControl.LowHpRetreatThreshold": "撤退阈值",

    # ---- 敌人筛选 ----
    "EnemyPriority.EnemyScaleBalanceWeight": "敌人强度权重",

    # ---- 日常收菜 ----
    "Reward.CollectOil": "收石油",
    "Reward.CollectCoin": "收金币",
    "Reward.CollectExp": "收经验",
    "Reward.CollectMission": "收任务奖励",

    # ---- 委托 ----
    "Commission.PresetFilter": "委托过滤器预设",
    "Commission.DoMajorCommission": "筛选大委托",

    # ---- 科研 ----
    "Research.UseCube": "魔方使用",
    "Research.UseCoin": "金币使用",
    "Research.UsePart": "图纸使用",
    "Research.PresetFilter": "科研过滤器预设",

    # ---- 宿舍 ----
    "Dorm.Collect": "收取",
    "Dorm.Feed": "投喂",

    # ---- 演习 ----
    "Exercise.OpponentChooseMode": "对手选择",

    # ---- 设备 ----
    "Emulator.ScreenshotMethod": "截图方式",
    "Emulator.ControlMethod": "控制方式",
    "Emulator.Serial": "设备串号",
    "Emulator.PackageName": "游戏包名",
    "Emulator.ServerName": "服务器",
    "Emulator.ScreenshotInterval": "截图间隔",
    "Optimization.ScreenshotInterval": "截图间隔",
    "Optimization.WhenTaskQueueEmpty": "队列空时",
    "Optimization.WhenScheduleTaskQueueEmpty": "调度队列空时",
}

# 块名中文（APP 侧也有一份，但数据层统一后 dump 的标题直接可用）
BLOCK_ZH = {
    "Alas": "全局",
    "General": "通用",
    "Main": "主线 1",
    "Main2": "主线 2",
    "Main3": "主线 3",
    "Reward": "日常收菜",
    "Commission": "委托",
    "Research": "科研",
    "Dorm": "宿舍",
    "Tactical": "战术教室",
    "Exercise": "演习",
    "Meowfficer": "指挥喵",
    "Guild": "大舰队",
    "Hard": "困难图",
    "Daily": "每日任务",
    "Event": "活动",
    "EventShop": "活动商店",
    "OpsiDaily": "大世界日常",
    "OpsiExplore": "大世界探索",
    "OpsiShop": "大世界商店",
    "OpsiVoucher": "大世界兑换",
    "OpsiObscure": "大世界隐秘海域",
    "OpsiAbyssal": "大世界深渊海域",
    "Restart": "重启游戏",
    "Freebies": "免费福利",
    "Gacha": "建造",
    "Shipyard": "研发船坞",
    "Awaken": "觉醒",
    "Raid": "共斗",
    "Coalition": "联合行动",
    "WarArchives": "作战档案",
    "PrivateQuarters": "宿舍区",
    "ShopFrequent": "常驻商店",
    "ShopOnce": "限购商店",
    "IslandBusiness": "岛屿商业",
    "IslandCollect": "岛屿收获",
    "IslandOrder": "岛屿订单",
    "IslandProduction": "岛屿生产",
}


def leaf_zh(path):
    """块内相对路径 → 中文名；没有就返回 None 让调用方退回原名"""
    return LEAF_ZH.get(path)


# dump（按任务块浏览）时的枚举候选，key 同样是块内相对路径。
# 和 COMMON 的那一份分开写，因为这里要覆盖到每个块自己的枚举字段。
DUMP_OPTIONS = {
    "Campaign.Name": CHAPTERS,
    "Campaign.Mode": ["normal", "hard"],
    "Campaign.Event": ["campaign_main", "event_20231221_cn"],
    "StopCondition.MapAchievement":
        # 取自 argument.yaml 的真实枚举，别再用 stop_when_finished /
        # stop_when_all_cleared 那两个旧值：它们是 ALAS 老版本的写法，
        # 新版配置只认下面这 5 个，传旧值 ALAS 会当无效值忽略掉。
        ["non_stop", "100_percent_clear", "map_3_stars", "threat_safe",
         "threat_safe_without_3_stars"],
    "Fleet.FleetOrder":
        ["fleet1_mob_fleet2_boss", "fleet1_boss_fleet2_mob", "fleet1_all", "fleet2_all"],
    "Fleet.Fleet1Formation":
        ["line_ahead", "double_line", "diamond"],
    "Fleet.Fleet2Formation":
        ["line_ahead", "double_line", "diamond"],
    "Fleet.Fleet1Mode": ["combat_auto", "combat_manual", "standby"],
    "Fleet.Fleet2Mode": ["combat_auto", "combat_manual", "standby"],
    "Submarine.Mode": ["do_not_use", "hunt_mode", "boss_mode", "standby_mode"],
    "Submarine.AutoSearchMode": ["sub_standby", "sub_auto_call"],
    "Submarine.DistanceToBoss":
        ["to_boss_position", "1_grid_to_boss", "2_grid_to_boss", "use_auto_search_keep"],
    "Emotion.Mode": ["calculate", "keep_exp_days", "do_not_calculate"],
    "Emotion.Fleet1Control":
        ["keep_exp_days", "calculate", "prevent_green_face", "prevent_red_face",
         "prevent_yellow_face"],
    "Emotion.Fleet2Control":
        ["keep_exp_days", "calculate", "prevent_green_face", "prevent_red_face",
         "prevent_yellow_face"],
    "Emotion.Fleet1Recover": ["not_in_dormitory", "no_recover"],
    "Emotion.Fleet2Recover": ["not_in_dormitory", "no_recover"],
    "EnemyPriority.EnemyScaleBalanceWeight":
        ["default_mode", "S3_empy", "S3_light", "S3_priority", "S3_only"],
    "Emulator.ScreenshotMethod": ["ADB", "uiautomator2", "aas", "DroidCast",
                                  "DroidCast_raw", "nemu_ipc"],
    "Emulator.ControlMethod": ["MaaTouch", "ADB", "uiautomator2", "minitouch"],
    "Emulator.ServerName": ["cn", "en", "jp", "kr", "tw"],
}


def dump_options(path):
    return DUMP_OPTIONS.get(path)


def flatten(o, pre=""):
    """打平成 点路径 → 值，APP 端直接展示成可编辑列表"""
    out = []
    if isinstance(o, dict):
        for k, v in o.items():
            p = (pre + "." + k) if pre else k
            if isinstance(v, dict):
                if v:
                    out += flatten(v, p)
                else:
                    out.append((p, {}))
            elif isinstance(v, list):
                out.append((p, v))
            else:
                out.append((p, v))
    return out


def get_path(d, path):
    cur = d
    for seg in path.split("."):
        if not isinstance(cur, dict) or seg not in cur:
            die("路径不存在: " + path)
        cur = cur[seg]
    return cur


def normalize_time(raw):
    """把用户输入的时间补成 ALAS 认的 "%Y-%m-%d %H:%M:%S"。

    为什么必须补零：ALAS 用 datetime.fromisoformat 解析配置里的时间，
    它只认两位的小时。界面手输「9:30」写成「2026-11-22 9:30:00」，
    fromisoformat 直接 ValueError，next_run 保持字符串，
    alas.py 的 `task.next_run > datetime.now()` 就 TypeError，调度器一启动就崩。

    接受：20:30 / 20:30:00 / 11-22 20:30 / 2026-11-22 20:30[:00]
    空字符串 = 立刻运行（写成当前时间）。
    其它一律报错，不给写进配置的机会。
    """
    s = (raw or "").strip()
    s = s.replace("/", "-").replace("：", ":").replace("　", " ")
    s = re.sub(r"\s+", " ", s)
    if not s:
        return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    parts = s.split(" ")
    date_part = parts[0] if len(parts) >= 2 else None
    time_part = parts[1] if len(parts) >= 2 else parts[0]

    t = time_part.split(":")
    if len(t) < 2 or len(t) > 3:
        die("时间格式不对（要 20:30 或 2026-11-22 20:30:00）：" + raw)
    try:
        hh, mm = int(t[0]), int(t[1])
        ss = int(t[2]) if len(t) == 3 else 0
    except ValueError:
        die("时间的时分秒必须是数字：" + raw)
    if not (0 <= hh <= 23 and 0 <= mm <= 59 and 0 <= ss <= 59):
        die("时间超出范围（时 0-23、分秒 0-59）：" + raw)

    now = datetime.datetime.now()
    y, mo, da = now.year, now.month, now.day
    if date_part:
        d = date_part.split("-")
        try:
            if len(d) == 3:
                y, mo, da = int(d[0]), int(d[1]), int(d[2])
            elif len(d) == 2:
                mo, da = int(d[0]), int(d[1])
            else:
                die("日期格式不对（要 2026-11-22 或 11-22）：" + raw)
        except ValueError:
            die("日期里的年月日必须是数字：" + raw)
        if not (1 <= mo <= 12 and 1 <= da <= 31):
            die("日期超出范围：" + raw)
    try:
        dt = datetime.datetime(y, mo, da, hh, mm, ss)
    except ValueError:
        die("这个日期不存在：" + raw)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def set_path(d, path, raw):
    segs = path.split(".")
    cur = d
    for seg in segs[:-1]:
        if not isinstance(cur, dict):
            die("路径不可写: " + path)
        if seg not in cur or not isinstance(cur[seg], dict):
            cur[seg] = {}
        cur = cur[seg]
    last = segs[-1]
    if not isinstance(cur, dict):
        die("路径不可写: " + path)

    old = cur.get(last)
    # 「下次运行」是时间字段：一律过 normalize_time（补零 + 校验），
    # 空值就写成当前时间 = 立刻运行。绝不把脏时间写进配置。
    if last == "NextRun":
        cur[last] = normalize_time(raw)
        return old, cur[last]
    # 类型跟着原值走，避免把 "120-240" 这种字符串区间写成数字
    if isinstance(old, bool):
        val = raw.strip().lower() in ("1", "true", "yes", "on")
    elif isinstance(old, int):
        try:
            val = int(raw)
        except ValueError:
            die("原值是整数，给的不是: " + raw)
    elif isinstance(old, float):
        try:
            val = float(raw)
        except ValueError:
            die("原值是小数，给的不是: " + raw)
    elif isinstance(old, list):
        val = [x.strip() for x in raw.split(",")]
    else:
        val = raw
    cur[last] = val
    return old, val


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    op = sys.argv[1]

    # scheduler 只读，不需要备份写入
    d = load()

    if op == "scheduler":
        print(json.dumps(scheduler(d), ensure_ascii=False))
        return 0

    if op == "common":
        print(json.dumps(common(d), ensure_ascii=False, default=str))
        return 0

    if op == "dump":
        if len(sys.argv) < 3:
            die("缺 Block 名")
        blk = sys.argv[2]
        if blk not in d:
            die("没有这个任务块: " + blk)
        items = flatten(d[blk])
        # 每个叶子都带上中文名和候选值：APP 直接显示中文，
        # 点进去如果是枚举就给选择列表，不用背 "fleet1_mob_fleet2_boss" 这种值。
        out = []
        for k, v in items:
            row = {"path": k, "value": v}
            zh = leaf_zh(k)
            if zh:
                row["zh"] = zh
            opts = dump_options(k)
            if opts:
                row["options"] = opts
            out.append(row)
        print(json.dumps(out, ensure_ascii=False, default=str))
        return 0

    if op == "get":
        if len(sys.argv) < 3:
            die("缺路径")
        print(json.dumps({"value": get_path(d, sys.argv[2])}, ensure_ascii=False, default=str))
        return 0

    if op == "maininfo":
        # 首页「主线设置」卡要同时显示「打哪一关」和「打多少次」。分开调两次 get
        # 就要起两遍 proot（每遍好几秒），这里合成一次读，APP 端只往返一次。
        # 用 safe_get：某个键万一不在，也不该让整张卡读不出来。
        out = {
            "chapter": safe_get(d, "Main.Campaign.Name"),
            "run_count": safe_get(d, "Main.StopCondition.RunCount"),
            "stop_cond": safe_get(d, "Main.StopCondition.MapAchievement"),
            "stage_increase": safe_get(d, "Main.StopCondition.StageIncrease"),
            "oil_limit": safe_get(d, "Main.StopCondition.OilLimit"),
            "enable": safe_get(d, "Main.Scheduler.Enable"),
            "mode": safe_get(d, "Main.Campaign.Mode"),
        }
        print(json.dumps(out, ensure_ascii=False, default=str))
        return 0

    if op == "set":
        if len(sys.argv) < 3:
            die("用法: set <Dotted.Path> <value>")
        path = sys.argv[2]
        raw = " ".join(sys.argv[3:])
        # 「下次运行」允许不给值：留空就是「立刻运行」，由 set_path 里的
        # normalize_time 写成当前时间。别的字段留空是没有意义的，仍然报错。
        if not raw.strip() and not path.endswith("NextRun"):
            die("用法: set <Dotted.Path> <value>")
        old, val = set_path(d, path, raw)
        write(d)
        print(json.dumps({"ok": True, "path": path, "old": old, "new": val},
                         ensure_ascii=False, default=str))
        return 0

    if op == "reset":
        # 一键把所有任务的「下次运行」改成现在。
        #
        # 为什么需要：换号之后进度不一样（这个号后宅没喂、周补给没领、主线停在别的图），
        # 但 alas.json 里记的还是上一个号的时间戳 —— 直接启动会一路「等到明天再说」，
        # 看起来就像什么都没跑。全改成现在，调度器下一次重读配置时就按队列顺序
        # 从头重跑一遍。
        #
        # 顺带写 hot_reload 标记：ALAS 原生在任务成功后 del_cached_property('config')
        # 会重读配置，但正在跑的任务不会中途退出。写这个标记能让它在下一个安全点
        # 收尾（alas_hot.py 打过补丁），不用重启容器、也不用重启游戏。
        now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        blocks = []
        for name, blk in d.items():
            if not isinstance(blk, dict):
                continue
            s = blk.get("Scheduler")
            if not isinstance(s, dict) or "NextRun" not in s:
                continue
            # Alas 块里那个 Scheduler 是调度器自己，重置它没有意义
            if str(s.get("Command", "")) == "Alas":
                continue
            s["NextRun"] = now
            blocks.append(name)

        write(d)

        # 标记写没写上是可观测的：写不进去就说明 bind 挂载有问题，APP 里要能看出来
        marked = False
        try:
            flag = os.path.join(BASE, "config", "hot_reload")
            with open(flag, "w", encoding="utf-8") as f:
                f.write(str(int(datetime.datetime.now().timestamp())))
            marked = os.path.exists(flag)
        except Exception:
            pass

        print(json.dumps({"ok": True, "now": now, "count": len(blocks),
                          "marked": marked, "blocks": blocks}, ensure_ascii=False))
        return 0

    if op == "enable":
        if len(sys.argv) < 4:
            die("用法: enable <Block> <0|1>")
        blk, flag = sys.argv[2], sys.argv[3].strip()
        if blk not in d or "Scheduler" not in d[blk]:
            die("没有这个任务块或它没有 Scheduler: " + blk)
        s = d[blk]["Scheduler"]
        s["Enable"] = flag in ("1", "true", "on")
        # 打开时把 NextRun 拉到现在，否则会等旧时间点、看起来像没生效
        if s["Enable"]:
            s["NextRun"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        write(d)
        print(json.dumps({"ok": True, "block": blk, "enable": s["Enable"],
                          "next_run": str(s.get("NextRun"))}, ensure_ascii=False))
        return 0

    die("未知操作: " + op)
    return 2


if __name__ == "__main__":
    sys.exit(main())
