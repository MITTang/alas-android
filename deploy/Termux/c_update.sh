#!/bin/bash
# 容器内 ALAS 更新工具包装，供 _exec.sh 调用。
#
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_update.sh status
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_update.sh check
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_update.sh apply
#
# 输出同时落盘到 alas-update.out：无 tty 的调用路径（APP 的 ProcessBuilder、
# adb run-as）读不到 proot 会话 stdout，落盘后回读最稳。
OUT=/data/data/com.termux/files/home/alas-update.out

cd /root/AzurLaneAutoScript 2>/dev/null || {
    echo '{"ok": false, "error": "进入项目目录失败"}'
    exit 1
}

/usr/bin/python3.10 /root/AzurLaneAutoScript/deploy/Termux/alas_update.py "$@" > "$OUT" 2>&1
rc=$?
cat "$OUT"
exit $rc
