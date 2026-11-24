#!/bin/bash
# 容器内「立即生效」触发脚本，供 _exec.sh 调用。
#
#   bash _exec.sh /root/AzurLaneAutoScript/deploy/Termux/c_hot.sh
#
# 只放一个标记文件，不杀进程、不重启游戏。
# 调度器（alas_hot.py 打过补丁）在下一轮地图结束时看到这个标记，就按
# ALAS 原生流程收尾当前任务、重读 config/alas.json、重新派发 Main ——
# 新关卡和新周回次数一起生效。
#
# 为什么不像以前那样 run-alas.sh restart：
# 重启会连 proot 和游戏一起重开，挂机被打断、游戏要重新进图，
# 用户要的是「像堆栈一样排到下面，上面跑完接着跑」，不是重启。
#
# 2026-11-21 修正：原来这里只 printf 到 stdout，而 APP 那边执行完是去
# `cat alas-config.out` 读结果的 —— 这个文件根本没被写过，于是永远读到
# 上一次 c_config.sh 留下的旧内容，界面就咬死显示「没写进标记文件」。
# 现在结果同时写进 $OUT（APP 读的那个文件）和 stdout，两边都能拿到。
OUT=/data/data/com.termux/files/home/alas-config.out

ALAS_DIR="${ALAS_DIR:-/root/AzurLaneAutoScript}"
FLAG="$ALAS_DIR/config/hot_reload"

emit() {
    # $1 = ok|false, $2 = msg
    printf '{"ok": %s, "msg": "%s"}\n' "$1" "$2" | tee "$OUT" 2>/dev/null \
        || printf '{"ok": %s, "msg": "%s"}\n' "$1" "$2"
}

if [ ! -d "$ALAS_DIR/config" ]; then
    emit false "找不到项目目录 $ALAS_DIR"
    exit 1
fi

# 写标记文件，并且**回读确认**：光看写入命令返回 0 不够，
# 容器和宿主之间是 bind 挂载，权限/只读挂载都会让写成功但宿主看不到。
if ! date +%s > "$FLAG"; then
    emit false "写标记文件失败：$FLAG 不可写"
    exit 1
fi

if [ ! -f "$FLAG" ]; then
    emit false "写入后回读不到 $FLAG"
    exit 1
fi

emit true "已请求立即生效：下一轮地图结束后自动换图，不重启游戏"
exit 0
