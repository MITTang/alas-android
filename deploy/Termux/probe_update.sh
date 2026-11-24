#!/bin/bash
# 探测手机容器里的更新能力：git 是否存在、是否有 .git 仓库、deploy.yaml 的更新配置、外网连通性
cd /root/AzurLaneAutoScript || { echo "NO_PROJECT"; exit 1; }

echo "=== git ==="
git --version 2>&1 | head -1
echo "=== .git ==="
if [ -d .git ]; then echo "HAS_GIT_DIR"; else echo "NO_GIT_DIR"; fi
echo "=== deploy.yaml update keys ==="
grep -E "AutoUpdate|^Repository|^Branch|CheckUpdateInterval|GitOverCdn|AutoRestartTime" deploy.yaml 2>&1
echo "=== net github ==="
python3.10 - <<'PY' 2>&1 | tail -3
import requests
for name, url in (("github", "https://api.github.com/"), ("gitee", "https://gitee.com/"), ("cdn", "https://alas-goc-1254325529.cos.ap-shanghai.myqcloud.com/")):
    try:
        r = requests.get(url, timeout=8)
        print(f"{name}: {r.status_code}")
    except Exception as e:
        print(f"{name}: FAIL {type(e).__name__}")
PY
echo "=== pip ==="
which pip3 2>&1
python3.10 -m pip --version 2>&1 | head -1
echo "=== DONE ==="
