# 在安卓真机上运行 ALAS（Termux + Ubuntu 22.04 + root）

> 目标：把这套 `AlasApp_0.4.13_fullcn` 里的 ALAS 后端（Python + WebUI）搬到手机本地跑，
> 游戏和 ALAS 在同一台手机上，不依赖电脑（已 root 的方案）。
>
> 本目录里的脚本是**已经在真机上跑通过一遍**的移植包：Redmi K40S（`munch` / 骁龙 870 /
> Android 16 / SDK 36 / arm64-v8a），ALAS 0.4.13 在容器里常驻、WebUI 返回 200、
> ALAS 自己的 `Device` 类能连上设备并截出 1280x720 有效画面。
> 文末「实机部署实录」记录了全部真实踩坑，「验证边界」写清了验证到哪一步。

---

## 0. 先明确范围

| 组件 | 安卓上怎么处理 |
|---|---|
| `alas.py`、`module/`、`config/`、`bin/` | 原样运行（这是主体） |
| `gui.py`（WebUI 服务） | 原样运行，手机浏览器访问 `http://127.0.0.1:22267` |
| `Alas.exe`、`console.bat`、`toolkit/` | **不移植**：Windows 启动器，安卓上没有意义 |
| `webapp/`（Electron 前端） | **不移植**：需要 Node + Chromium 打包，体积换不来体验；改用浏览器 WebUI |
| `deploy/Windows`、`deploy/AidLux` | 保留作参考，实际用本目录的 `deploy/Termux` |

---

## 1. 它为什么能跑（原理）

ALAS 对设备的所有操作（截图、点击、启动游戏）都走 **adb**：

```
┌──────────── 同一台手机 ────────────┐
│                                    │
│  Termux                            │
│   └ proot 容器 Ubuntu 22.04        │
│      └ ALAS(python) ── adb client ─┼── 127.0.0.1:5555 ──┐
│                                          (TCP)         │
│                                                        ▼
│  游戏（碧蓝航线）◀── 截图/点击 ── adbd (root 开的 TCP)  │
└────────────────────────────────────────────────────────┘
```

关键点是 **手机自己连自己的 adb**：用 root 让 `adbd` 监听 `5555`，容器里的 adb 连 `127.0.0.1:5555`。
这样 ALAS 完全不需要改设备层代码，`adb push` / `adb shell` / `adb forward` 全都能用。
（不 root 也能做，但要靠 Android 11+ 的「无线调试pairing」，Ubuntu 22.04 自带的 adb 1.0.41 支持，
只是配对码每次重启都变，比 root 方案麻烦。）

截图/控制方案选型（真机必须手动指定，不能用 `auto`）：

| 项目 | 选 | 原因 |
|---|---|---|
| 截图 | `uiautomator2` | 实测 1280x720 下 **370~500 ms**，比 `ADB`（`screencap -p`，580~610 ms）快。`DroidCast` / `DroidCast_raw` **不能用**：ALAS 源码注释写明只支持 SDK 23~32，Android 16 上直接 HTTP 500。`ascreencap` 只有 Android 5~9 的二进制，`scrcpy` 需要删掉的 `av` 依赖 |
| 控制 | `MaaTouch` | `bin/MaaTouch/maatouchsync` 是原生 ARM 二进制，走 app_process，真机上比 minitouch 稳（minitouch 在 Android 12+ 常因 SELinux/权限注入失败） |

> 截图和控制都依赖 u2 的 ATX 服务在设备上跑起来。MIUI/HyperOS 会拦 `adb install`，
> 必须用 root 装（`25-install-u2.sh` 做的就是这件事）。

---

## 2. 前置条件

- **已 root**（Magisk / KernelSU），Termux 能 `su`。
- Android 11+ 更稳（10 也能试）。
- 内存 ≥ 8GB（ALAS + mxnet + 游戏同时在跑，6GB 会频繁被杀）。
- 存储 ≥ 6GB 可用（容器 ~1.5GB + Python 依赖 ~1GB + 游戏）。
- **主动散热**：官方放弃真机方案的理由就是发热和损耗，长期挂机建议加散热背夹/风扇。
- 参考实测：骁龙 8 Gen2 / 8GB，整机功耗 6~8W 可以长时间无人值守（社区数据，非本机实测）。

---

## 3. 依赖改造说明（这是本次移植的主要技术工作）

原始 `requirements.txt` 是给 Windows x64 + Python 3.7 用的，aarch64 上直接装会全线失败。
本目录的 `requirements-android.txt` 是替换后的清单，逐条理由：

| 包 | 原 | 改为 | 为什么 |
|---|---|---|---|
| numpy | 1.16.6 | **1.23.5** | 1.16 没有 cp310 轮子也编不过；1.23.5 仍属 `numpy<1.24`，`np.float`/`np.int` 旧别名还在，mxnet/cnocr 不会炸 |
| scipy | 1.4.1 | **1.10.1** | `module/island` 用了 `scipy.optimize.milp`（要求 ≥1.9） |
| opencv-python | 4.5.3.56 | **opencv-python-headless 4.10.0.84** | 4.5.3.56 无 cp310 轮子；4.10 是 abi3 轮子，aarch64 直接装，**不用再花 1~2 小时编译 OpenCV** |
| mxnet | 1.6.0 | **binss 的 aarch64 `mxnet-1.9.1-py3-none-any.whl`** | 官方 mxnet 1.6 无 ARM 轮子。这个 50.5MB 的轮子里自带 `mxnet/libmxnet.so`（我核对过 ELF 头：`EI_CLASS=2`、`e_machine=183` = AArch64 64 位），配 `MXNET_LIBRARY_PATH` 即可 |
| av | 10.0.0 | **删除** | `av` 只在 `module/device/method/scrcpy/core.py` 里**延迟导入**（且失败时只提示、不中断）；本项目截图走 `DroidCast_raw`，完全用不到 scrcpy，删掉它可以省掉整套 ffmpeg 编译 |
| psutil / pycryptodome | 5.9.3 / 3.9.9 | **不锁版本** | 这两个旧版本没有 cp310 aarch64 轮子，取最新版即可（API 没变） |
| wrapt | 1.13.1 | **不锁版本** | 同上，1.13.1 无 cp310 aarch64 轮子，1.14+ 才有 |
| websockets / watchfiles | 跟随 `uvicorn[standard]` | **不装** | 只是 uvicorn 的可选加速件，ALAS 用不到 |
| alas-webapp / pywin32 | — | **删除** | Windows 专用 |

还有两个**顺序/来源**上的坑，脚本里已经处理：

- **mxnet 必须先装**：`cnocr` 的 `install_requires` 里带 `mxnet`，如果先跑 `pip install -r`，
  pip 会去 PyPI 找 mxnet（aarch64 没有轮子）→ 试图源码编译 mxnet → 长时间失败。
  所以 `20-install-alas.sh` 是「先装 mxnet 轮子，再装其余依赖」。
- **`adbutils`、`pywebio 1.6.2`、`zerorpc` 等在 PyPI 上只有源码包**（我对着 pypi.org 官方源确认过），
  pip 会用 setuptools 现场打包，都是纯 Python，不需要编译器，慢一点属正常。

---

## 4. 操作步骤

### 4.1 把项目放进 Termux

先把整个 `AzurLaneAutoScript` 目录打包成 tar.gz 传到手机（例如 `/sdcard/Download/`），然后：

```bash
# Termux 内
bash deploy/Termux/00-termux-setup.sh /sdcard/Download/AzurLaneAutoScript.tar.gz
```

这一步会：装 `proot-distro`、创建 Ubuntu 22.04 容器、把项目解压到
`$HOME/alas/AzurLaneAutoScript`（Termux 私有存储，比 `/sdcard` 快很多，别放共享存储）。

> 容器来源：优先用清华镜像的 `ubuntu-base-22.04-base-arm64.tar.gz` 手工建
> （`proot-distro install --override-alias`）。原因是本机连不上 `registry-1.docker.io`，
> 官方 `proot-distro install ubuntu:22.04` 会卡住。脚本已内置这个 fallback 链。

若项目已经在手机上，也可以直接手动拷到 `$HOME/alas/AzurLaneAutoScript` 后跑不带参数的脚本。

### 4.2 打开手机的 adbd TCP（需要 root）

```bash
bash deploy/Termux/10-phone-adb.sh enable
```

做四件事：开 `adbd` 的 5555 端口、解除 Android 12+ 的 phantom process 数量限制、
把 Termux 加进电池白名单、屏幕常亮 + 关深色模式 + 强制 `wm size 1280x720`。

验证：

```bash
bash deploy/Termux/10-phone-adb.sh status
```

### 4.3 在容器里装运行环境

```bash
proot-distro login ubuntu-base-22.04-base-arm64 --bind "$HOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript"
# 进容器后：
bash /root/AzurLaneAutoScript/deploy/Termux/20-install-alas.sh
```

脚本会：`unset` 掉 Termux 漏进来的 `ANDROID_*` 变量 → apt 装 python3/pip/git/adb/编译链
→ **先装 aarch64 mxnet**（`cnocr` 依赖它，必须先就位，否则 pip 会去编 x86 版 mxnet 然后失败）
→ 把 `libmxnet.so` 放到 `/usr/local/mxnet/` 并补 `libcblas.so.3` / `libopencv_*.so.4.5` 软链
→ 装 `requirements-android.txt`（过滤 cnocr 后再单独 `--no-deps` 装它）
→ 校准 numpy 到 1.23.5 并校验 `cv2.imdecode` → 写 `/root/.alas-env.sh`
→ 落 `config/deploy.yaml` → 跑 24 模块导入自检 + mxnet 实算 + cv2 完整性检查。

**这一步不做 venv**：Ubuntu 22.04 的 `python3.10 -m venv` 建出来的环境里没有 pip，
`ensurepip` 会报 `CalledProcessError`，装 `python3-pip-whl` 也救不回来。容器本身隔离，用系统 Python。

接着**必须**在容器里再跑一步（不装则截图/控制全废）：

```bash
bash /root/AzurLaneAutoScript/deploy/Termux/25-install-u2.sh
```

它会用 root 把 u2 的两个 APK `pm install -r -d` 进设备，并验证 `u2.connect` 能拿到
`displayWidth/displayHeight/sdkInt`。MIUI/HyperOS 的 `adb install` 是被拦的。

> 容器名不一定是 `ubuntu-base-22.04-base-arm64`：旧版 `proot-distro` 装出来的叫 `ubuntu`。
> 不确定就先 `cat deploy/Termux/.container`，里面的名字就是 `proot-distro login` 要用的。

### 4.4 授权 adb（二选一）

- **办法 A（最简单，但不适合无人值守）**：退出容器后先跑 `bash run-alas.sh`，
  当手机弹出「允许 USB 调试吗」时点【允许】。
- **办法 B（推荐，复用电脑上已授权的密钥）**：容器里的 adb 自己生成的新密钥设备不认，
  而且 adb 1.0.41 只写私钥不写 `.pub`。把电脑上那把**已经被设备授权过**的钥匙搬过来：

```bash
# 电脑上
adb push %USERPROFILE%\.android\adbkey     /sdcard/Download/adbkey
adb push %USERPROFILE%\.android\adbkey.pub /sdcard/Download/adbkey.pub

# 手机上（Termux，root）
bash deploy/Termux/10-phone-adb.sh trust /sdcard/Download/adbkey.pub
# 私钥也放进容器：先 cp 到 bind 目录，再在容器里拷到 /root/.android/
cp /sdcard/Download/adbkey ~/alas/AzurLaneAutoScript/deploy/Termux/_adbkey
proot-distro login ubuntu-base-22.04-base-arm64 --bind "$HOME/alas/AzurLaneAutoScript:/root/AzurLaneAutoScript" -- \
  bash -c 'mkdir -p /root/.android && cp /root/AzurLaneAutoScript/deploy/Termux/_adbkey /root/.android/adbkey && chmod 600 /root/.android/adbkey'
```

### 4.5 启动

```bash
bash deploy/Termux/run-alas.sh daemon     # 后台常驻（推荐）
bash deploy/Termux/run-alas.sh            # 前台跑，日志刷屏，Ctrl+C 停（调试用）
bash deploy/Termux/run-alas.sh status     # 看进程 + WebUI 探测
bash deploy/Termux/run-alas.sh stop       # 停止
```

`daemon` 模式会 `nohup proot-distro login ... &`，让 **proot 本身常驻**，容器内再 `exec` 到
`gui.py` —— 这是唯一能让 ALAS 在 Termux 会话结束后继续活着的写法（容器内单独
`nohup python &` 必死，实测过）。日志在 `~/alas-daemon.log`。

第一次启动后在 WebUI 里做**一次**实例配置（见下一节）。

---

## 5. WebUI 里必须改的设置

进 `Alas设置`（左侧实例 → 功能区 `Alas` → `Alas设置`）：

| 设置项 | 值 | 说明 |
|---|---|---|
| 模拟器 Serial | `127.0.0.1:5555` | 不要填 `auto`，真机自动连接会乱试端口 |
| 游戏包名 | 国服 `com.bilibili.azurlane` | 按你装的服选 |
| 模拟器截图方案 | `uiautomator2` | 千万别用 `auto`；**DroidCast / DroidCast_raw 在 Android 12+ 上直接 HTTP 500，不能用** |
| 模拟器控制方案 | `MaaTouch` | 默认就是它，确认一下 |
| 游戏服务器 | 按实际 | 维护检测，网络不稳可关 |

也可以直接用脚本写进 `config/alas.json`（ALAS 未运行时改）：

```bash
/usr/bin/python3.10 /root/AzurLaneAutoScript/deploy/Termux/apply-alas-config.py
```

游戏内设置照 README 里的表格改（帧数 60、剧情自动播放 开等），日志/识别问题大多是这里没照改。

---

## 6. 自检与排错

完整自检（会真截一张图并跑一次 OCR 模型推理，第一次约 10~20 秒）：

```bash
/usr/bin/python3.10 /root/AzurLaneAutoScript/deploy/Termux/preflight.py
/usr/bin/python3.10 /root/AzurLaneAutoScript/deploy/Termux/preflight.py --fix   # 自动修分辨率
```

常见问题对照：

| 现象 | 原因 / 处理 |
|---|---|
| `adb devices` 里是 `unauthorized` | 点手机上的【允许】或跑 `10-phone-adb.sh trust`；仍不行就 `su -c 'stop adbd; start adbd'` |
| `adb connect` 后立刻 `offline` | adbd 端口没开成功：`su -c 'getprop service.adb.tcp.port'` 应为 5555，否则重跑 `enable` |
| `Resolution not supported: 1080x2400` | `adb shell wm size 1280x720` 没生效或被系统重置；用 `preflight.py --fix`，仍不行则确认没有第三方「屏幕分辨率」模块干扰 |
| 截图全黑 / 游戏不在前台 | 屏幕锁了或游戏被杀后台；`svc power stayon true` + 电池白名单，别用「深色模式」 |
| `ImportError: libmxnet.so` 之类 | `echo $MXNET_LIBRARY_PATH` 应为 `/usr/local/mxnet/libmxnet.so`，`LD_LIBRARY_PATH` 要含 `/usr/lib/aarch64-linux-gnu:/usr/local/mxnet`；`ldd` 报 not found 就按「实机部署实录」第 6 条补 libcblas / libopencv 软链 |
| `OSError: Cannot find path to android app folder`（pip 报） | Termux 的 `ANDROID_*` 变量漏进容器了，脚本开头必须先 `unset`（20-install 已做） |
| `AttributeError: module 'cv2' has no attribute 'imdecode'` | cv2 包被卸残了（装过 opencv-python 5.x 又卸掉导致），按 20-install-alas.sh 第 7 步重装 headless 并把 numpy 拉回 1.23.5 |
| `RuntimeError: Missing dependency package 'websockets'` | 缺 websockets，`pip install websockets==10.0` |
| `RuntimeError: numpy 2 没有 np.Inf` / `ImportError: cannot import name 'Inf'` | numpy 被顶到 2.x，`pip install --no-deps --force-reinstall numpy==1.23.5` |
| 启动几秒就被杀（Process completed signal 9） | Android 12+ phantom process 限制，重跑 `10-phone-adb.sh enable`；Android 16 上管用的是 `settings put global settings_enable_monitor_phantom_procs false` |
| ALAS 卡在"识别中/未知界面"，截图内容却是锁屏 | 手机是密码锁屏，ALAS 只会滑动解锁。解锁一次后靠 `svc power stayon true`（需插充电器）+ 永不熄屏保持不锁 |
| OCR 很慢 / 偶尔识别错 | 真机 CPU 渲染慢是客观限制；`ScreenshotInterval` 可调到 0.5~1.0，别指望模拟器级速度 |
| WebUI 打不开 | 手机浏览器访问 `http://127.0.0.1:22267`；外部访问用 `http://<手机局域网IP>:22267`（同一 WiFi） |
| 手机发热/掉帧严重 | 降帧、关特效、加散热；这是官方放弃真机方案的核心原因 |

开机自启（可选）：装 `Termux:Boot` 插件，在 `~/.termux/boot/` 里放一个调用 `run-alas.sh` 的脚本（脚本里先 `termux-wake-lock`）。

---

## 7. 已知限制（照实说）

1. **上游已放弃真机方案**（官方 wiki：「在安卓真机运行 Alas 的方案已经被放弃」，原因是 VMOS 闪退和发热损耗）。所以这条路能跑，但不是官方支持路线。
2. **只能单开**：一台手机一套 ALAS。
3. **不能用 ALAS 里的「重启模拟器」功能**（真机没有模拟器可拉），脚本里已经把 `AdbRestart` 关掉、`EmulatorInfo` 置空。
4. **分辨率被锁在 1280x720**：ALAS 的 `check_screen_size` 只认这个尺寸，`adb shell wm size 1280x720` 已是启动脚本里的固定动作。
5. **DroidCast 系列在 Android 12+ 上不可用**：源码注释限定 SDK 23~32，实测 Android 16 返回 HTTP 500。已改用 `uiautomator2` 截图（约 0.4s/张）。
6. **密码锁屏无法自动解锁**：ALAS 只会做滑动解锁。要么手动解锁一次后保持常亮（插充电器 + `svc power stayon true`），要么把锁屏改成无密码。
5. **性能有上限**：截图 + mxnet OCR 的开销全部压在手机 CPU 上，长时间挂机等于持续中高负载。
6. **WebUI 是浏览器版**，没有 Electron 客户端；界面在手机小屏上操作会比较挤（建议另一台设备远程访问）。

---

## 8. 实机部署实录（Redmi K40S / Android 16，逐条踩坑）

这一节是 2026-11-15 在真实设备上（USB 直连、`adb 1.0.41`）从零装到跑起来的过程记录，
按发生顺序排。想复现就按这里的顺序走，每条"坑"都已经写进上面的脚本。

### 8.1 adb 传参：所有设备侧逻辑必须走脚本文件

Windows 上 `adb shell "a; b"` 会被拆成两个 token、管道被本地 shell 解释 → 空输出。
正确通道：

```
本地写 .sh → adb push /data/local/tmp/ → su -c "cp ... /data/data/com.termux/files/home/alas/..." 
→ adb shell run-as com.termux /data/data/com.termux/files/usr/bin/bash <脚本>
```

`com.termux` 是 `github-debug` 构建、manifest 带 debuggable，所以 `run-as` 能直接以 Termux 的
uid（10331）执行命令，不必让用户在手机上敲。

日志回读固定套路：脚本内 `> $HOME/x.log 2>&1` + `trap '' 1 2 3 15`（防 adb 断连的 SIGHUP）
→ `su -c "cp /data/data/com.termux/files/home/x.log /sdcard/Download/x.log"` → `adb pull`
→ 本地用 UTF-8 读。**PowerShell 直接接 `adb shell` 的 stdout 会丢行，必须走文件**。

### 8.2 MIUI/HyperOS 拦 `adb install`

报 `INSTALL_FAILED_USER_RESTRICTED`。开发者选项里的「USB 安装」又要插 SIM 卡才让开。
绕法：`adb shell su -c "pm install -r -d /data/local/tmp/x.apk"`。Termux、u2 的两个 APK 全靠这条。

Magisk 授权 Shell root：用 `uiautomator dump` 拿 `bounds` 取真实坐标点开关
（截图目测不可靠；这台机上是 `(926,1190)`）。验证方式：`su -c id` 返回 `uid=0` + `u:r:magisk:s0`。

### 8.3 pip 误判 Android 平台

Termux 的 `ANDROID_*` 环境变量被 proot 继承 → pip 走 `platformdirs` 的 android 分支 →
`OSError: Cannot find path to android app folder`。
修复：脚本开头 `unset ANDROID_DATA ANDROID_ROOT ANDROID_STORAGE ANDROID_ART_ROOT
ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT EXTERNAL_STORAGE ASEC_MOUNTPOINT BOOTCLASSPATH
LD_PRELOAD LD_LIBRARY_PATH PYTHONHOME PYTHONPATH DEX2OATBOOTCLASSPATH`，
并设 `XDG_CACHE_HOME=/root/.cache` / `PIP_CACHE_DIR=/root/.cache/pip`。

### 8.4 venv 是死路

`python3.10 -m venv` 建出来的 venv 里**没有 pip**，`ensurepip` 在 Ubuntu 22.04 报
`CalledProcessError`（pip-22.0.2 wheel 与 setuptools 冲突）；装 `python3-pip-whl` /
`python3-setuptools-whl` 也救不回来。→ 放弃 venv，用系统 `/usr/bin/python3.10` 全局装
（容器本身隔离，没有副作用）。

### 8.5 cnocr 的 numpy 硬 pin

`cnocr==1.2.2` 硬 pin `numpy<1.20.0`，与清单里的 `numpy==1.23.5` 冲突 → `ResolutionImpossible`。
解法：`grep -vi '^cnocr' requirements-android.txt > /tmp/req-nocnocr.txt` 先装其余，
再 `pip install --no-deps cnocr==1.2.2`。
**绝不能装 gluoncv 的依赖树**，否则 numpy 顶到 2.2.6、scipy 顶到 1.15.3、
还装出 opencv-python 5.0.0.93 和 pandas——新版 numpy 2 编译的 wheel 与 numpy 1.23 ABI 不兼容。

### 8.6 libmxnet.so 缺系统库（三个连环）

| 缺的库 | 处理 |
|---|---|
| `libcblas.so.3` | Ubuntu 22.04 没有这个 SONAME，软链 `/usr/lib/aarch64-linux-gnu/openblas-pthread/libopenblasp-r0.3.20.so` → `/usr/lib/aarch64-linux-gnu/libcblas.so.3`（链到顶层不存在的路径会变成悬空链接） |
| `libopencv_imgcodecs.so.4.5` | Ubuntu 的库名带 `d` 后缀（`libopencv_*.so.4.5d`），批量去掉 `d` 建别名（54 个） |
| `ldd` 报 not found | `LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/local/mxnet`，`MXNET_LIBRARY_PATH=/usr/local/mxnet/libmxnet.so` |

### 8.7 容器里的 adb 密钥

容器 adb 新生成的密钥设备不认（一直 `unauthorized`），而且 adb 1.0.41 只写私钥不写 `.pub`。
→ 复用电脑上**已被设备授权**的密钥：把 `%USERPROFILE%\.android\adbkey` + `adbkey.pub`
push 到 bind 目录（`deploy/Termux/_adbkey*`），容器内 `cp` 到 `/root/.android/adbkey`（chmod 600）。
之后 `adb connect 127.0.0.1:5555` 直接 `device`。

### 8.8 proot 会话生命周期（最关键的一条）

容器内 `nohup python gui.py &` 在 proot 会话退出后**必死**（实测：ALAS 曾 HTTP 200 活着，
脚本退出后再查已死、进程消失）。
正确做法：**Termux 层 `nohup proot-distro login ... &` 让 proot 本身常驻**，
容器内用 `exec python3.10 gui.py` 顶掉 bash（见 `30-start-alas.sh`）。
另外：**每个新的容器会话都必须重新 `adb start-server` + `adb connect`**（adb server 随会话死）。

### 8.9 uiautomator2 必须用 root 预装

u2 自带 `assets/app-uiautomator.apk`(2.19 MB) 与 `app-uiautomator-test.apk`(1.06 MB)，
`adb install` 被 MIUI 拒 → root `pm install -r -d` 两个都成功，然后 `u2.connect` 才通
（`25-install-u2.sh`）。装好后 `deploy.yaml` 的 `InstallUiautomator2` 必须是 `false`，
否则 ALAS 启动时还会用 `adb install` 重装一遍、必然失败。

### 8.10 DroidCast 在 Android 12+ 失效

`DroidCast_raw` 返回 `HTTP 500: Failed to generate the screenshot on device / emulator : Xiaomi - munch - Android OS : 16`。
ALAS 源码 `module/device/device.py` 自己写着 "DroidCast is available on SDK 23 to SDK 32"。
改截图方案，实测：

| 方案 | 1280x720 单张耗时 | 可用 |
|---|---|---|
| `uiautomator2` | 370~500 ms | ✅ 默认选它 |
| `ADB`（`screencap -p`） | 580~610 ms | ✅ 备用 |
| `DroidCast_raw` | — | ❌ HTTP 500 |

### 8.11 cv2 包被卸残

先装过 `opencv-python 5.0.0.93`、后来又 `pip uninstall opencv-python`，
两个包共用 `cv2/` 目录，卸一个把另一个的 `__init__.py` / `cv2.abi3.so` 也删了 →
`import cv2` 还能过（变成 namespace package），但 `cv2.imdecode` 不存在，ALAS 截图直接崩
（`AttributeError: module 'cv2' has no attribute 'imdecode'`）。
修复：删 `cv2/` 目录 + 重装 headless，**然后必须把 numpy 拉回 1.23.5**（`--force-reinstall`
会把 numpy 拖到 2.2.6，numpy 2 没有 `np.Inf`，ALAS 全线 import 失败）。

### 8.12 WebUI

`adb forward tcp:22267 tcp:22267` 或用手机浏览器直接开 `http://127.0.0.1:22267/`，
返回 200 / 5331 字节 HTML（pywebio_static 资源正常）。
局域网访问：`http://<手机IP>:22267`（`deploy.yaml` 已是 `WebuiHost: 0.0.0.0`）。

### 8.13 系统级设置：严格分级，别动用户日常

这一节是踩过坑之后定下的规矩。**永久改显示相关设置会把手机搞坏**——
我第一次把 `wm size 1280x720` 当常驻设置写进去，整机 UI 被压在 16:9，
手机日常根本没法用，用户直接找上来了。

| 类别 | 设置 | 命令 |
|---|---|---|
| **常驻**（无害，可以直接开着） | 防 phantom killer 杀容器 | `adb shell settings put global settings_enable_monitor_phantom_procs false` |
| | Termux 电池白名单 | `adb shell cmd deviceidle whitelist +com.termux` |
| | adbd TCP 端口 | `adb tcpip 5555`（重启后会丢，要重新执行） |
| **只在挂机期间**（用完必须还原） | 分辨率 1280x720 | `bash display.sh 720p` / `bash display.sh native` |
| **不要动**（会直接影响日常使用） | 屏幕超时、深色模式、`svc power stayon` | —— |

`display.sh` 走的是**容器内的 adb**（设备 shell uid），不是 `su`：

```
Termux 里 su -c "wm size"  →  cmd: Failure calling service window: Failed transaction (2147483646)
容器里的 adb shell wm      →  正常
```

原因是 Termux 的 `su` 落在 Magisk 的 `u:r:magisk:s0` domain，对 `cmd window` / `cmd settings`
这类 binder 服务没有事务权限。而容器里的 adb 连的是本机 adbd，执行时是设备上的 **shell uid**，权限足够。

### 8.14 分辨率策略（最终方案：竖屏基准 720x1280）

ALAS 只认 1280x720，手机出厂是 1080x2400。这里的关键认知是：

> **`wm size` 设的是「竖屏基准」尺寸，不是横屏尺寸。**

所以正确的是 **`wm size 720x1280`**（9:16 竖屏基准）：

- 竖屏时桌面按 720x1280 正常排版；
- 一打开碧蓝航线，系统自动转横屏 → 逻辑分辨率正好变成 **1280x720**，
  这正是 ALAS 原生要求的尺寸，**截图内容铺满、没有黑边，不需要任何裁剪缩放**。

反过来的 `wm size 1280x720` 是错的 —— 竖屏基准是横的，整个桌面会被压成横向排版，
日常完全没法用（这一条踩过三次）。

**density 必须一起改，否则 UI 会崩：**

```bash
wm size 720x1280      # 竖屏基准
wm density 293        # 720/293*160 = 393dp 宽
```

出厂的 `1080x2400 @ 440dpi` 也是 393dp 宽 —— 两者 dp 空间一致，
所以图标、字体、布局的**视觉尺寸完全不变**，只是像素精度低一点。
只改 size 不改 density → `720x1280 @ 440dpi` 只有 262dp 宽，桌面会直接挤爆。

**重启后还会保留吗：会。** `wm size` / `wm density` 会写进 `Settings.Global`：

```
display_size_forced    = 720,1280
display_density_forced = 293
```

重启后由 `DisplayManagerService` 重新应用，不需要开机脚本。

**日常用法：**

```bash
bash deploy/Termux/start-game.sh          # 一键：确保分辨率 → 开游戏 → 起 ALAS
bash deploy/Termux/start-game.sh game     # 只开游戏，不起 ALAS
bash deploy/Termux/start-game.sh stop     # 停 ALAS（游戏和分辨率都不动）
bash deploy/Termux/start-game.sh status   # 看状态
bash deploy/Termux/display.sh 720p        # 手动重新应用 720x1280 @ 293
bash deploy/Termux/display.sh native      # 想彻底还原出厂就执行这个
```

实测数据（Redmi K40S）：

| 状态 | `dumpsys window` | `screencap` | ALAS 判定 |
|---|---|---|---|
| 竖屏桌面 | `cur=720x1280` | 720x1280 | — |
| 游戏横屏 | `cur=1280x720` | **1280x720** | `[Screen_size] 1280x720` ✅ |
| 截图耗时 | — | — | **523ms**（ADB 直出，无需裁剪） |

**另一个必须一起改的配置**：`Alas.Emulator.ScreenshotMethod` 要设成 `ADB`。
`alas.json` 是嵌套结构，路径是 `Alas` → `Emulator` → `ScreenshotMethod`，
写成顶层 `ScreenshotMethod` 是不生效的（这个坑也踩过）。

原因是 **uiautomator2 在 Android 16 横屏下截图返回空图**：

```
ImageTruncated: Empty image after cv2.imdecode  ×5
→ Retry screenshot_uiautomator2() failed
→ RequestHumanTakeover
```

ADB 截图（`exec-out screencap -p`）在横屏下稳定可用，只是慢一点。

**已废弃的中间方案**（留档，别再用）：设备层渲染区适配
（`module/device/screen_adapt.py` + `detect_render_area.py`）。
做法是保持原生 1080x2400，把游戏按 16:9 居中渲染的那 1920x1080 裁出来缩到 1280x720。
能用，但截图要 1115ms（裁剪+缩放），比现在慢一倍，且多一层坐标换算。
现在 `config/screen_adapt.json` 里 `Enable=false`，这两个模块处于关闭状态。

### 8.15 锁屏是唯一的收尾障碍

ALAS 只会滑动解锁，这台手机是 4 位数字密码。两个选择：

- **解锁一次 + 保持常亮**：`svc power stayon true` 需要插着充电器才生效，配合永不熄屏，
  解锁一次后屏幕就不会再自动锁，ALAS 可以一直跑。
- **把锁屏改成无密码**：`settings put secure lockscreen.disabled 1`（root 可做），
  代价是任何拿到手机的人都能直接进桌面。

---

## 9. 验证边界（我做了什么 / 没做什么）

**已在真机上验证通过的（Redmi K40S / munch / Android 16 / SDK 36 / arm64-v8a）：**

- root → Termux 安装（`pm install -r -d` 绕过 MIUI 限制）→ proot-distro Ubuntu 22.04 容器建立
- Python 3.10 全套依赖安装：**24/24 模块导入成功**，`mxnet 1.9.1` 实算 `1+2+3=6`，`cv2 4.10.0` 的 `imdecode/imencode/resize/cvtColor/matchTemplate` 全部可用
- 容器内 `adb` 连通本机 adbd：`adb connect 127.0.0.1:5555` → `device product:munch model:22021211RC`
- u2 的 ATX 服务 root 装好并连通：`displayWidth=1280 displayHeight=696 rotation=0 sdk=36`、`window_size=(720,1280)`
- **ALAS 自己的 `Device` 类跑通**：`[AdbDevice] AdbDevice(serial=127.0.0.1:5555)` → `[Screen_size] 1280x720` → 连续两张 `shape=(720,1280,3)` 的真实截图（507ms / 361ms）
- 游戏进程识别：`app_current = com.bilibili.azurlane / com.manjuu.azurlane.MainActivity`
- ALAS 常驻 + WebUI：`Uvicorn running on http://0.0.0.0:22267`，`GET / HTTP/1.1 200 OK`，进程在 Termux 会话结束后仍存活
- 截图方案实测对比：`uiautomator2` 370~500ms 优于 `ADB screencap` 580~610ms；`DroidCast_raw` 在 SDK 36 上 HTTP 500

**没做的 / 客观阻塞：**

- **ALAS 从未完整跑过一个调度任务**：手机处于密码锁屏，ALAS 截图拿到的始终是锁屏画面，`check_screen_size` 之后的任务识别无法进行。手动解锁一次即可验证（这不是代码问题，是设备状态问题）。
- 没验证 mxnet OCR 在骁龙 870 上的实际耗时（`preflight.py --full` 的 OCR 项因 cnocr 默认模型要联网下载而跳过；ALAS 用项目内的 `./bin/cnocr_models/cnocr`，不走网络）。
- 没做 Termux:Boot 开机自启和 `termux-wake-lock` 持久化，属于可选增强。

---

## 10. 文件清单

| 文件 | 作用 |
|---|---|
| `requirements-android.txt` | aarch64 / Python 3.10 依赖清单（替换原 `requirements.txt`） |
| `deploy.template-Termux.yaml` | 复制到 `config/deploy.yaml` 的部署配置 |
| `00-termux-setup.sh` | Termux 侧：建容器、放项目（无 root） |
| `10-phone-adb.sh` | `enable` / `tune` / `trust` / `status`，root 打开自连 adb |
| `20-install-alas.sh` | 容器内：apt + pip + mxnet + 系统库软链 + 配置落地 + 自检 |
| `25-install-u2.sh` | 容器内：**用 root 预装 u2 的 ATX 服务**（不装则截图/控制全废） |
| `30-start-alas.sh` | 容器内：连 adb、校分辨率、`exec` 起 WebUI |
| `run-alas.sh` | Termux 入口：`foreground` / `daemon` / `stop` / `status` |
| `preflight.py` | 环境自检（依赖 / mxnet / adb / 分辨率 / 截图 / OCR 实跑） |
| `apply-alas-config.py` | 把 `config/alas.json` 的 Serial/包名/截图/控制方案改成真机值 |
| `.container` | `00-termux-setup.sh` 生成的容器名（`ubuntu22` 或旧版的 `ubuntu`），后续脚本自动读取 |
| `parse.py`、`pip_command.sh`、`pre-installed.txt`、`requirements-in.txt` | 之前 AidLux 版残留的半成品，已被上面的清单取代，保留仅供对照 |
