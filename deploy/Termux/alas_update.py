#!/usr/bin/env python3
"""ALAS 更新检查与更新应用（手机适配版）

原版流程在 module/webui/updater.py + deploy/git.py + deploy/pip.py，
这里把同样的流程搬到手机容器里跑，只改了三处环境差异：

1. GitExecutable —— 手机没有 toolkit/Git/mingw64/bin/git.exe，直接用系统 git
2. 没有 .git 仓库 —— 首次自动 git init + remote + 浅拉（--depth=1），
   手机上比全量 fetch 快一个数量级
3. reset --hard 会覆盖移植层 —— 更新前把 deploy/Termux / deploy/AndroidGui /
   alas_cli.py / config/alas.json 挪到 /tmp 暂存，更新完再放回

用法：
    python3.10 alas_update.py status     # 只读本地状态，不走网络
    python3.10 alas_update.py check      # 拉远端最新 commit 并比较
    python3.10 alas_update.py apply      # 真正更新（覆盖官方文件，移植层自动保留）
    python3.10 alas_update.py stamp      # 把当前远端记为已知版本
    python3.10 alas_update.py pip        # 只补装 requirements.txt 里缺的包
"""
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = "/root/AzurLaneAutoScript"
REPO = "https://github.com/LmeSzinc/AzurLaneAutoScript"
# 国内网络直连 GitHub 经常被掐断，原版 deploy/config.py 里就备了 Gitee 镜像
REPO_MIRROR = "https://gitee.com/LmeSzinc/AzurLaneAutoScript"
BRANCH = "master"
STATE = os.path.join(ROOT, "config", "alas_update_state.json")
KEEP_DIR = "/tmp/alas_keep"
# 结果同时落盘到 Termux home：APP 的 ProcessBuilder 路径读不到 proot 会话的 stdout。
# proot-distro 会把 Termux home 共享进容器，这个路径在容器内可直接写。
OUT_FILE = "/data/data/com.termux/files/home/alas-update.out"

# 更新时必须保住的移植层文件/目录（相对 ROOT）
KEEP = [
    "deploy/Termux",
    "deploy/AndroidGui",
    "alas_cli.py",
    "config/alas.json",
    "config/screen_adapt.json",
    "deploy.yaml",
]


def now():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def fmt_time(s):
    """把 GitHub 的 2026-11-13T17:46:08Z 转成手机能直接看懂的时间"""
    if not s:
        return ""
    t = s.strip()
    # 已经是 "2026-11-15 18:14:25" 这种本地格式的，原样返回，不能再加 8 小时
    if "T" not in t and not t.endswith("Z"):
        return s
    if t.endswith("Z"):
        t = t[:-1]
    t = t.replace("T", " ")
    try:
        d = datetime.datetime.strptime(t, "%Y-%m-%d %H:%M:%S")
        # GitHub 给的是 UTC，手机上看到的应该是本地时间
        d = d + datetime.timedelta(hours=8)
        return d.strftime("%Y-%m-%d %H:%M")
    except Exception:
        return s


def run(cmd, timeout=600):
    try:
        return subprocess.run(
            cmd, shell=True, cwd=ROOT, capture_output=True, text=True,
            timeout=timeout, encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired:
        class R:
            returncode = 124
            stdout = ""
            stderr = "命令超时"
        return R()


def out(obj):
    s = json.dumps(obj, ensure_ascii=False, default=str)
    print(s)
    try:
        with open(OUT_FILE, "w", encoding="utf-8") as f:
            f.write(s)
    except Exception:
        pass


def load_state():
    try:
        with open(STATE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(d):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)


def ensure_repo():
    """没有 .git 就建一个，并把 origin 指向官方仓库"""
    # proot 容器里的 root 与文件实际 uid 不一致时，git 会拒绝操作并报
    # "detected dubious ownership"，必须先把这个目录加进白名单
    run("git config --global --add safe.directory " + ROOT, timeout=60)
    run("git config --global --add safe.directory '*'", timeout=60)
    # 对应原版 deploy.yaml 的 SSLVerify: false，手机上证书链常有问题
    run("git config --global http.sslVerify false", timeout=60)
    # 拉大缓冲 + 关掉低速断开，避免大仓库 fetch 中途被踢
    run("git config --global http.postBuffer 524288000", timeout=60)
    run("git config --global http.lowSpeedLimit 0", timeout=60)
    run("git config --global http.lowSpeedTime 999999", timeout=60)

    if not os.path.isdir(os.path.join(ROOT, ".git")):
        r = run("git init")
        if r.returncode != 0:
            return "git init 失败：" + (r.stderr.strip()[-300:] or r.stdout.strip()[-300:])
    r = run("git remote set-url origin " + REPO)
    if r.returncode != 0:
        r = run("git remote add origin " + REPO)
        if r.returncode != 0:
            return "设置 remote 失败：" + (r.stderr.strip()[-300:] or r.stdout.strip()[-300:])
    return None


def fetch():
    """拉最新 commit。照原版 _check_update() 的做法试 3 次，失败再换 Gitee 镜像。"""
    last = ""
    for attempt in range(3):
        r = run("git fetch --depth=1 --no-tags origin " + BRANCH, timeout=900)
        if r.returncode == 0:
            return None
        last = (r.stderr.strip() or r.stdout.strip())[-500:]
        time.sleep(4)

    # GitHub 拉不到就换镜像，镜像地址只影响 origin 的指向
    run("git remote set-url origin " + REPO_MIRROR, timeout=60)
    r = run("git fetch --depth=1 --no-tags origin " + BRANCH, timeout=900)
    if r.returncode == 0:
        # 成功拉到之后就留在镜像上，下次检查更快
        return None

    # 两种都失败，把 origin 指回官方仓库，方便下次再试
    run("git remote set-url origin " + REPO, timeout=60)
    mirror_err = (r.stderr.strip() or r.stdout.strip())[-300:]
    return "GitHub: " + (last or "无输出") + " | 镜像: " + (mirror_err or "无输出")


def remote_info():
    r = run('git log -1 --pretty=format:"%H|%ad|%s" --date=iso origin/' + BRANCH, timeout=60)
    if r.returncode != 0:
        return None
    s = r.stdout.strip()
    if not s:
        return None
    parts = s.split("|", 2)
    return {
        "sha": parts[0],
        "time": parts[1] if len(parts) > 1 else "",
        "message": parts[2] if len(parts) > 2 else "",
    }


def cmd_status():
    st = load_state()
    out({
        "ok": True,
        "has_git": os.path.isdir(os.path.join(ROOT, ".git")),
        "known_sha": st.get("known_sha", ""),
        "known_time": fmt_time(st.get("known_time", "")),
        "checked_at": st.get("checked_at", ""),
        "applied_at": st.get("applied_at", ""),
        "baselined_at": st.get("baselined_at", ""),
        "repo": REPO,
        "branch": BRANCH,
    })
    return 0


def api_info():
    """
    走 GitHub REST API 读最新提交。

    为什么优先这条路：手机网络下 git 走 443 端口经常连不通
    （实测 "Failed to connect to github.com port 443: Connection timed out"），
    但 api.github.com 是通的。原版 updater 里也有一条同样思路的
    `_check_update_()`，只是被标成 Deprecated 了；在手机环境下它反而更可靠。
    """
    url = "https://api.github.com/repos/LmeSzinc/AzurLaneAutoScript/commits?per_page=1"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "ALAS-Android-Console"})
        with urllib.request.urlopen(req, timeout=25) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        c = data[0]
        cm = c.get("commit") or {}
        msg = (cm.get("message") or "").splitlines()
        return {
            "sha": c.get("sha", ""),
            "time": fmt_time((cm.get("author") or {}).get("date", "")),
            "message": msg[0] if msg else "",
            "source": "api",
        }
    except Exception as e:
        return {"error": "%s: %s" % (type(e).__name__, str(e)[:160])}


def cmd_check():
    # 先试 API，不通再退回 git
    info = api_info()
    git_err = ""

    if "error" in info:
        api_err = info["error"]
        err = ensure_repo()
        if err:
            out({"ok": False, "error": "API: " + api_err + " | " + err})
            return 1
        err = fetch()
        if err:
            out({"ok": False,
                 "error": "GitHub API 不可用（" + api_err + "），git 也不通（" + err + "）"})
            return 1
        info = remote_info()
        if not info:
            out({"ok": False, "error": "读不到远端版本"})
            return 1
        info["source"] = "git"
        git_err = api_err

    st = load_state()
    known = st.get("known_sha", "")
    st["checked_at"] = now()

    if not known:
        # 第一次检查：没有比较基准，就把当前远端记成基准
        st["known_sha"] = info["sha"]
        st["known_time"] = info["time"]
        st["baselined_at"] = now()
        save_state(st)
        out({"ok": True, "first": True, "has_update": False, "remote": info,
             "known_sha": info["sha"],
             "known_time": info["time"],
             "checked_at": st["checked_at"],
             "applied_at": st.get("applied_at", ""),
             "note": "已把当前远端版本记为基准"})
        return 0

    save_state(st)
    res = {"ok": True, "first": False, "has_update": info["sha"] != known,
           "remote": info, "known_sha": known,
           "known_time": fmt_time(st.get("known_time", "")),
           "checked_at": st.get("checked_at", ""),
           "applied_at": st.get("applied_at", "")}
    if git_err:
        res["api_failed"] = git_err
    out(res)
    return 0


def cmd_stamp():
    info = remote_info()
    if not info:
        out({"ok": False, "error": "本地没有远端信息，先跑一次 check"})
        return 1
    st = load_state()
    st["known_sha"] = info["sha"]
    st["known_time"] = info["time"]
    st["applied_at"] = now()
    save_state(st)
    out({"ok": True, "stamped": info})
    return 0


def _save_keep():
    shutil.rmtree(KEEP_DIR, ignore_errors=True)
    os.makedirs(KEEP_DIR, exist_ok=True)
    saved = []
    for rel in KEEP:
        src = os.path.join(ROOT, rel)
        if not os.path.exists(src):
            continue
        dst = os.path.join(KEEP_DIR, rel.replace("/", "__"))
        try:
            if os.path.isdir(src):
                shutil.copytree(src, dst)
            else:
                shutil.copy2(src, dst)
            saved.append(rel)
        except Exception:
            pass
    return saved


def _restore_keep(saved):
    for rel in saved:
        src = os.path.join(KEEP_DIR, rel.replace("/", "__"))
        dst = os.path.join(ROOT, rel)
        if not os.path.exists(src):
            continue
        try:
            if os.path.isdir(src):
                shutil.rmtree(dst, ignore_errors=True)
                shutil.copytree(src, dst)
            else:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
        except Exception:
            pass


def _is_kept(rel):
    for k in KEEP:
        if rel == k or rel.startswith(k + "/"):
            return True
    return False


def _download_zip(sha):
    """从 codeload 下指定提交的源码快照。

    为什么不用 git：这台手机的网络下 github.com:443 连不通，
    git fetch 必然失败；但 codeload.github.com 实测 200 可达。
    """
    tmp = "/tmp/alas_src_%s.zip" % sha[:8]
    url = "https://codeload.github.com/LmeSzinc/AzurLaneAutoScript/zip/" + sha
    req = urllib.request.Request(url, headers={"User-Agent": "ALAS-Android-Console"})
    with urllib.request.urlopen(req, timeout=900) as r:
        with open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
    return tmp


def _extract_zip(zip_path):
    """解压覆盖到 ROOT，跳过移植层。

    zip 顶层是 AzurLaneAutoScript-<sha>/ 这样的目录，剥掉一层再落盘。
    注意 ALAS 仓库里不含 config/ log/ screenshot/ 这些运行时目录，
    所以这里不会破坏运行数据；deploy/Termux 等已在 KEEP 里排除。
    """
    import zipfile
    written = 0
    skipped = 0
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            if n.endswith("/"):
                continue
            parts = n.split("/", 1)
            if len(parts) < 2 or not parts[1]:
                continue
            rel = parts[1]
            if _is_kept(rel):
                skipped += 1
                continue
            dst = os.path.join(ROOT, rel)
            d = os.path.dirname(dst)
            if d:
                try:
                    os.makedirs(d, exist_ok=True)
                except Exception:
                    pass
            try:
                with z.open(n) as src, open(dst, "wb") as f:
                    shutil.copyfileobj(src, f)
                written += 1
            except Exception:
                skipped += 1
    return written, skipped


def cmd_apply():
    # 任务跑着的时候覆盖代码会把正在执行的进程搞崩，先挡住
    running = "/data/data/com.termux/files/home/alas-task.running"
    if os.path.exists(running):
        out({"ok": False,
             "error": "ALAS 正在跑任务，先回控制台点「结束」再更新"})
        return 1

    info = api_info()
    if "error" in info:
        out({"ok": False,
             "error": "拿不到最新提交信息（" + info["error"] + "），无法更新"})
        return 1

    saved = _save_keep()

    zip_path = ""
    try:
        zip_path = _download_zip(info["sha"])
    except Exception as e:
        _restore_keep(saved)
        out({"ok": False, "error": "下载源码包失败：%s: %s" % (type(e).__name__, str(e)[:200])})
        return 1

    try:
        written, skipped = _extract_zip(zip_path)
    except Exception as e:
        _restore_keep(saved)
        out({"ok": False, "error": "解压覆盖失败：%s: %s" % (type(e).__name__, str(e)[:200])})
        return 1

    # 移植层放回原位，覆盖 zip 里可能同名的旧文件
    _restore_keep(saved)

    try:
        os.remove(zip_path)
    except Exception:
        pass

    st = load_state()
    st["known_sha"] = info["sha"]
    st["known_time"] = info["time"]
    st["applied_at"] = now()
    st["checked_at"] = now()
    save_state(st)

    out({"ok": True, "applied": info, "kept": ", ".join(saved),
         "written": written, "skipped": skipped,
         "note": "官方文件已覆盖为最新提交，移植层已还原"})


def cmd_apply_git():
    err = ensure_repo()
    if err:
        out({"ok": False, "error": err})
        return 1
    err = fetch()
    if err:
        out({"ok": False, "error": "拉取远端失败：" + err})
        return 1
    info = remote_info()
    if not info:
        out({"ok": False, "error": "读不到远端版本"})
        return 1

    saved = _save_keep()

    r = run("git reset --hard origin/" + BRANCH, timeout=600)
    if r.returncode != 0:
        _restore_keep(saved)
        out({"ok": False, "error": "git reset 失败：" + ((r.stderr.strip() or r.stdout.strip())[-400:])})
        return 1

    _restore_keep(saved)

    st = load_state()
    st["known_sha"] = info["sha"]
    st["known_time"] = info["time"]
    st["applied_at"] = now()
    st["checked_at"] = now()
    save_state(st)

    out({"ok": True, "applied": info, "kept": saved,
         "note": "官方文件已更新到最新，移植层已还原"})
    return 0


def cmd_pip():
    """
    只补缺失的包。

    照原版 deploy/pip.py 的思路：拿依赖清单和已装的 dist-info 比，缺什么装什么。
    但有两点手机特有的坑，不能直接 `pip install -r requirements.txt`：
    1. 官方 requirements.txt 里有 Windows 专属包（pywin32），手机上根本装不了
    2. 官方写 opencv-python，手机上装的是 opencv-python-headless，会误判成缺失
    所以优先用移植包里裁剪过的 requirements-android.txt，并额外跳过一组包。
    带 --no-deps 是为了不把 numpy 顶到 2.x。
    """
    req_path = os.path.join(ROOT, "deploy", "Termux", "requirements-android.txt")
    if not os.path.exists(req_path):
        req_path = os.path.join(ROOT, "requirements.txt")
    if not os.path.exists(req_path):
        out({"ok": False, "error": "找不到依赖清单"})
        return 1

    # 手机上装不了或已有等价替代的包，直接跳过
    SKIP = {
        "pywin32", "av", "opencv-python", "alas-webapp", "pywebio",
        "win10toast", "pywin32-ctypes",
    }

    r = run("/usr/bin/python3.10 -m pip list --format=freeze", timeout=300)
    installed = set()
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name = line.split("==")[0].split(" ")[0].strip().lower().replace("_", "-")
        if name:
            installed.add(name)
    # 装了 headless 版就等于满足了 opencv-python 的需求
    if "opencv-python-headless" in installed:
        installed.add("opencv-python")
    if "opencv-contrib-python-headless" in installed:
        installed.add("opencv-contrib-python")

    need = []
    try:
        with open(req_path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except Exception as e:
        out({"ok": False, "error": "读依赖清单失败：" + str(e)[:120]})
        return 1

    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("-"):
            continue
        name = re.split(r"[=<>!~\[\s]", line)[0].strip().lower().replace("_", "-")
        if not name or name in installed or name in SKIP:
            continue
        if name not in need:
            need.append(name)

    if not need:
        out({"ok": True, "missing": [],
             "source": os.path.basename(req_path),
             "note": "依赖已齐全，无需补装"})
        return 0

    r = run("/usr/bin/python3.10 -m pip install " + " ".join(need) +
            " --no-deps --disable-pip-version-check "
            "-i https://pypi.tuna.tsinghua.edu.cn/simple",
            timeout=1800)
    if r.returncode != 0:
        out({"ok": False, "missing": need,
             "source": os.path.basename(req_path),
             "error": ((r.stderr.strip() or r.stdout.strip())[-600:])})
        return 1
    out({"ok": True, "missing": need,
         "source": os.path.basename(req_path),
         "tail": (r.stdout or "").strip()[-300:]})
    return 0


def main():
    if len(sys.argv) < 2:
        out({"ok": False, "error": "用法: alas_update.py status|check|apply|stamp|pip"})
        return 2
    op = sys.argv[1]
    if op == "status":
        return cmd_status()
    if op == "check":
        return cmd_check()
    if op == "apply":
        return cmd_apply()
    if op == "stamp":
        return cmd_stamp()
    if op == "pip":
        return cmd_pip()
    out({"ok": False, "error": "未知操作: " + op})
    return 2


if __name__ == "__main__":
    sys.exit(main())
