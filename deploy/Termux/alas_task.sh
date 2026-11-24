#!/bin/bash
# 容器内 ALAS 单任务入口包装，供 _exec.sh 调用。
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/alas_task.sh reward
#
# 运行期间在 Termux 家目录留一个 alas-task.running 标志文件，
# APP 靠它判断"有没有任务在跑"。比 pgrep 匹配命令行可靠得多，
# pgrep 会把 su 自身和 Magisk 的日志进程一起匹上，导致误判。
FLAG=/data/data/com.termux/files/home/alas-task.running
# 先删再建：上一轮如果被 SIGKILL 打断，trap 来不及跑，这个文件会留下来，
# APP 就会一直显示「任务运行中」。每轮开跑前清一次，孤儿标志不会再累积。
rm -f "$FLAG" 2>/dev/null
echo $$ > "$FLAG" 2>/dev/null

cleanup() {
    rm -f "$FLAG" 2>/dev/null
}
trap cleanup EXIT INT TERM

cd /root/AzurLaneAutoScript || exit 1
# 这里不能用 exec：exec 会把当前 shell 替换掉，trap 不会触发，标志文件就删不掉了
/usr/bin/python3.10 /root/AzurLaneAutoScript/alas_cli.py "$@"
rc=$?
cleanup
exit $rc
