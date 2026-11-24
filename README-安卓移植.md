# 安卓移植入口（AlasApp_0.4.13_fullcn）

> 完整说明见 [`AzurLaneAutoScript/deploy/Termux/README.md`](AzurLaneAutoScript/deploy/Termux/README.md)。
> 这里是速查，全部命令都在**手机**上跑（Windows 这边的 `Alas.exe` / `console.bat` / `toolkit/` 不参与）。

**状态：已在 Redmi K40S（munch / Android 16 / arm64）真机上跑通。**
ALAS 在容器里常驻、WebUI 返回 200、ALAS 自己的 `Device` 类能连上设备并截出 1280x720 有效画面。

## 前提

- 手机已 root（Magisk / KernelSU），Termux 能拿到 `su`
- 内存 ≥ 8GB，可用存储 ≥ 6GB，建议加散热 + 插着充电器
- 游戏（碧蓝航线国服）已装在手机上

## 步骤

```bash
# 0) 把整个 AzurLaneAutoScript 目录（或它的 tar.gz）传到手机

# 1) Termux：建 Ubuntu 22.04 容器 + 放好项目（无需 root）
bash deploy/Termux/00-termux-setup.sh /sdcard/Download/AzurLaneAutoScript.tar.gz

# 2) Termux：root 打开 adbd 的 5555 端口 + 系统级设置（phantom / 电池白名单 / 常亮 / 1280x720）
bash deploy/Termux/10-phone-adb.sh enable

# 3) 进容器装环境（apt + pip + aarch64 mxnet + 依赖 + deploy.yaml）
proot-distro login ubuntu-base-22.04-base-arm64 --bind "$HOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript"
bash /root/AzurLaneAutoScript/deploy/Termux/20-install-alas.sh

# 3.5) 【必做】容器内用 root 预装 u2 的 ATX 服务（不装则截图/控制全废）
bash /root/AzurLaneAutoScript/deploy/Termux/25-install-u2.sh
exit

# 4) 授权 adb：把电脑上已授权的 adbkey 复用过来（或在手机弹窗里点【允许】）
bash deploy/Termux/10-phone-adb.sh trust

# 5) 启动（daemon = 后台常驻，会自动把分辨率临时切到 1280x720）
bash deploy/Termux/run-alas.sh daemon
bash deploy/Termux/run-alas.sh status     # 查状态 + WebUI 探测
bash deploy/Termux/run-alas.sh stop       # 停止，并自动把分辨率还原成原生
```

**分辨率怎么管（重要）**：ALAS 只认 1280x720，你的手机是 1080x2400。
720p 只能在挂机期间存在，**绝不能设成常驻**——那会把整机 UI 压在 16:9，日常根本没法用。

```bash
bash deploy/Termux/display.sh status    # 当前分辨率
bash deploy/Termux/display.sh 720p      # 手动切（run-alas daemon 会自动做）
bash deploy/Termux/display.sh native    # 手动还原（run-alas stop 会自动做）
```

`display.sh` 走的是**容器内的 adb**（设备 shell uid），不用 `su`——Termux 的 `su` 落在
Magisk 的 `magisk:s0` domain，调 `cmd window` 只会报
`cmd: Failure calling service window: Failed transaction`，改不动分辨率。

启动后浏览器打开 `http://127.0.0.1:22267`，实例设置应为
**Serial = `127.0.0.1:5555`**、**截图 = `uiautomator2`**、**控制 = `MaaTouch`**
（也可用 `deploy/Termux/apply-alas-config.py` 直接写进 `config/alas.json`，脚本默认值已经是这套）。

## 为什么这么设计（一句话版）

ALAS 的设备操作全走 adb：让手机 `adbd` 监听 `127.0.0.1:5555`，容器里的 adb 连自己，
于是设备层代码一行都不用改。真正的移植工作量集中在**依赖**：
`mxnet 1.6`（无 ARM 轮子）换成 aarch64 编译的 `mxnet 1.9.1` 轮子，
`numpy/scipy/opencv` 换成有 aarch64 轮子的版本，`av`、`alas-webapp`、`pywin32` 这类包删除。

## 三个必须知道的坑（其余见完整文档第 8 节）

1. **容器内单独 `nohup python gui.py &` 必死** —— 必须让 proot 本身常驻，
   容器内 `exec` 到 python。`run-alas.sh daemon` 已经这么做了。
2. **DroidCast 系列在 Android 12+ 不可用** —— 源码限定 SDK 23~32，实测 Android 16 返回 HTTP 500。
   改用 `uiautomator2` 截图（370~500ms/张，比 `ADB screencap` 的 580~610ms 还快）。
3. **分辨率只能临时切** —— `wm size 1280x720` 现在只在 `30-start-alas.sh` 里设、
   由 `run-alas.sh stop` 还原。`10-phone-adb.sh` 已经不再碰它。
   Termux 里用 `su` 改分辨率会失败，必须走 `display.sh`（容器内 adb）。
3. **密码锁屏解不掉** —— ALAS 只会滑动解锁。要么解锁一次后插着充电器保持常亮
   （`svc power stayon true` 已设），要么把锁屏改成无密码。

## 已知限制

- 上游已放弃真机方案（发热 / VMOS 闪退），这条路"能跑但需要折腾"
- 只能单开，无法使用"重启模拟器"功能，分辨率锁 1280x720
- 性能和 OCR 速度受手机 CPU 限制

详细排错、实机部署实录（14 条踩坑）、验证边界、依赖替换对照表都在
`AzurLaneAutoScript/deploy/Termux/README.md` 里。
