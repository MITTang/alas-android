#!/bin/bash
# 容器内 ALAS 配置工具包装，供 _exec.sh 调用。
#
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_config.sh scheduler
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_config.sh dump Reward
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_config.sh set Reward.Scheduler.Enable true
#
# 输出同时落盘到 alas-config.out：
# 有些调用路径（无 tty 的 adb run-as、APP 里的 ProcessBuilder）读不到 proot 会话的
# stdout，落盘后回读最稳，和 alas_task.sh 用日志文件回传是同一套思路。
OUT=/data/data/com.termux/files/home/alas-config.out

cd /root/AzurLaneAutoScript 2>/dev/null || {
    echo "ERROR: 进入项目目录失败"
    exit 1
}

/usr/bin/python3.10 /root/AzurLaneAutoScript/deploy/Termux/alas_config.py "$@" > "$OUT" 2>&1
rc=$?
cat "$OUT"
exit $rc
