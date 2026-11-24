# ALAS 控制台（Android 原生 APP）

红米 K40S 上「游戏 + ALAS 同机本地独立运行」的操作入口。不经过浏览器、不需要记端口，
点图标就是全部操作。

包名 `com.alas.console`｜minSdk 24 / targetSdk 34｜竖屏锁定｜约 24 KB

---

## 一、这套东西的分工

手机上其实是三个进程层次，APP 只是最外面那层壳：

| 层 | 干什么 | 位置 |
| --- | --- | --- |
| `com.alas.console` | 控制台：拉起/停止、点任务、看真实日志 | 本目录 |
| Termux + proot 容器（`ubuntu-base-22.04-base-arm64`） | 跑真正的 Python 版 ALAS | Termux 私有目录 |
| `com.bilibili.azurlane` | 游戏本体，ALAS 通过 adb 截图和点击它 | 系统应用 |

APP 不自己实现任何业务逻辑，所有自动化都在容器里的原版 ALAS 代码执行 ——
这也是「移植」而不是「仿造」的关键。

---

## 二、项目结构

```
deploy/AndroidGui/
├── src/com/alas/console/MainActivity.java   # 全部界面与逻辑，单 Activity
├── res/mipmap-xxxhdpi/ic_launcher.png
├── AndroidManifest.xml
├── build.ps1          # 无 gradle 的完整构建脚本
├── debug.keystore     # 签名用，硬编码在 build.ps1 里
└── AlasConsole.apk    # 构建产物
```

配套脚本在 `deploy/Termux/`：

- `_exec.sh` — 容器执行包装（补 PREFIX/HOME/PATH/TMPDIR，bind 挂载工程目录）
- `alas_task.sh` — 单任务入口（`.running` 标志文件 + `trap cleanup`）
- `alas_config.py` — 配置读写（`scheduler` / `common` / `dump` / `get` / `set` / `enable`）
- `c_config.sh` — 容器内包装，把 `alas_config.py` 输出落盘再 `cat`
- `start-game.sh`（`all|game|stop|status`）、`run-alas.sh`、`c_prepare_game.sh`

`alas_cli.py` 在工程根目录（`AzurLaneAutoScript/alas_cli.py`），直接调
`AzurLaneAutoScript.run(command)`，先 `goto_main` 再跑任务。

---

## 三、环境依赖

**构建机（Windows）**

- JDK 11（本项目用的是 11.0.29）
- Android SDK 构建工具放在 `C:\Users\<用户名>\AppData\Local\Temp\alas-apk\bt\android-14\`
  以及 `plat\android-34\android.jar`
- **不需要 gradle、不需要 Android Studio**

**手机**

- 已 root（Magisk 30.7），APP 需要 `su` 权限
- Termux（`com.termux`，uid 10331）
- proot-distro 容器 `ubuntu-base-22.04-base-arm64`
- 容器内 `/usr/bin/python3.10`，ALAS 在 `/root/AzurLaneAutoScript`

---

## 四、编译与安装

```powershell
# 必须用独立 pwsh 进程，不能用 & .\build.ps1（脚本内 $PSScriptRoot 取不到）
pwsh -NoProfile -File "E:\Users\LIANG\Downloads\AlasApp_0.4.13_fullcn\AzurLaneAutoScript\deploy\AndroidGui\build.ps1"
```

构建链是手写的六步：`aapt2 compile res` → `aapt2 link` → `javac` →
`d8` → 把 classes.dex 注入 APK → `zipalign + apksigner`。

安装走 root 的 pm（MIUI / HyperOS 会拦普通 `adb install`）：

```powershell
adb push AlasConsole.apk /data/local/tmp/AlasConsole.apk
adb shell su -c "pm install -r -d /data/local/tmp/AlasConsole.apk"
```

---

## 五、界面结构与功能对照表

APP 分五个页面，全部是原生控件（不依赖 WebUI）。顶栏左侧是汉堡按钮，**从左边缘向右滑出左侧抽屉**切换页面（Material DrawerLayout 的手势骨架 + iOS 深色外观）：

| 页面 | 内容 |
| --- | --- |
| 控制台 | 状态卡（已停止 / 挂机运行中 / 任务运行中）+ 开始 / 结束 / 恢复屏幕三个按钮 + **「主线设置」两行卡（打哪一关 / 打多少次，各带改入口）** + 运行日志（每 4 秒刷新） |
| 任务 | 「挂机队列 · 已启用 N 项」实时读配置，每行名字 + 间隔 + 下次运行时间 + 开关；下面是「未启用 M 项」；再下面是 10 个单次执行任务 |
| 参数 | 按分组列常用参数（设备 6 / **主线 16** / 日常收菜 5 / 委托 2 / 科研 3 / 宿舍 2 / 战术教室 2 / 演习 2 / 指挥喵 1 / 大舰队 1），全部中文名。顶部有「**立即生效**」条（重启调度器让刚改的值马上生效）。**枚举项点一行弹单选列表直接选**（「打哪一关」64 个关卡、出击顺序 4 种），**布尔项右侧是「开/关」胶囊，一点即切**；底部「全部参数 · 按任务块浏览」可浏览并编辑 1300+ 项中的任意一项，叶子名已汉化 |
| 更新 | 等价原版启动时的自动检查更新：本地版本 / 版本时间 / 上次更新于 / 上次检查于；「检查更新」拉 GitHub 最新提交；有新版时给出「立即更新到最新」 |
| 网页 | 内嵌官方 pywebio WebUI，只作查看用（按钮不可靠，见第六节第 7 条） |

### PC 版 vs Android 版

| 功能 | PC 版 | Android 版 | 说明 |
| --- | --- | --- | --- |
| 自动化任务执行 | WebUI 按钮 / 调度器 | 「任务」页单次执行 10 项 | 走 `alas_cli.py`，等价原版 `alas.run(task)`，`TASKS` 里 19 项都可用 |
| 挂机队列开关 | WebUI「调度器」逐个开关 | 「任务」页点行切换，写 `Scheduler.Enable` | 打开时自动把 `NextRun` 拉到当前时间，避免看着像没生效 |
| 参数修改 | WebUI 设置页 | 「参数」页：枚举项弹单选列表、布尔项点胶囊、其余弹输入框 | 写盘前自动备份 `.bak`，`indent=2` 保持官方格式 |
| **改打哪一关** | WebUI 设置页里翻 `Campaign.Name` | **首页卡片「改关卡 ›」→ 按章网格一格即选** | 打开自动定位到当前章并高亮；参数页顶部也有同一张卡 |
| **改打多少次** | WebUI 设置页里翻 `StopCondition.RunCount` | **首页卡片「改次数 ›」→ 12 个预设格子一格即选** | `0` 显示为「一直打（不限）」；当前值蓝底高亮 |
| **改完立即生效** | WebUI 改完下一轮就读 | 关卡改动**自动生效**；其它参数点「参数」页顶部「立即生效」 | 走 `deploy/Termux/alas_hot.py` 的热切换：给 `config.task_switched()` 补「关卡名变了也切」的判定，命中后仍用 ALAS 原生流程（抛 `TaskEnd` → 主循环重读 `config/alas.json` → 重新派发 Main），**不重启 proot、不重启游戏**。「立即生效」只写 `config/hot_reload` 标记文件，同样在下一轮地图结束时切 |
| 全量参数编辑 | WebUI 设置页全部 1300+ 项 | 「参数」页底部「按任务块浏览」 | 切页/切块时按需加载，不在启动时全量拉；叶子名走 `LEAF_ZH` / `BLOCK_ZH` 汉化 |
| 任务间隔 / 下次时间 | WebUI 显示 | 「任务」页每行显示 | 直接读 `Scheduler.SuccessInterval` / `NextRun` |
| 实时运行日志 | WebUI 日志面板 | 「控制台」底部日志区 | 读 `alas-daemon.log` / `alas-task.log`，靠 `su -M` |
| 一键启动游戏 | 手动开模拟器 | 「开始」按钮 | `start-game.sh all`：起游戏 → 等横屏 → 校验截图 → 起 ALAS |
| 停止 | 关模拟器 | 「结束」按钮 | `run-alas.sh stop` |
| 分辨率 | 1280x720 窗口 | 系统 720x1280 + density 293，游戏横屏自然 1280x720 铺满 | 见第六节第 1 条 |
| **启动自动检查更新** | `updater.py` 按 `CheckUpdateInterval`（默认 5 天）检查 | APP 启动 12 秒后静默检查一次，24 小时一次 | 有新版时抽屉里的「更新」项变橙 + toast，没新版完全不打扰 |
| **手动检查更新** | WebUI「更新」按钮 | 「更新」页「检查更新」 | 走 GitHub REST API，不依赖 git |
| **应用更新** | `git_install()` + `pip_install()` | 「立即更新到最新」→ 下载源码快照覆盖 + 补依赖 | 移植层自动备份还原，任务运行中禁止更新 |
| 远程控制 / 工具 | 有 | 无（「网页」页签内嵌官方 WebUI） | pywebio 前端点击在此环境失效 |

---


## 六、关键实现事实（踩过的坑，改代码前必读）

**1. 分辨率主线**

系统基准设成 `wm size 720x1280` + `wm density 293`（720/293×160 = 393dp，
与出厂 1080x2400@440dpi 的 393dp 一致）。这样竖屏桌面所有元素位置和原来一样，
游戏进入横屏后自然变成 1280x720，铺满且无黑边。

持久化靠 `Settings.Global` 的 `display_size_forced=720,1280`、
`display_density_forced=293`。

注意：`wm size 1280x720` 是错的，那设的是竖屏基准，会把桌面压成横排。
设备层的裁剪方案（`module/device/screen_adapt.py`）已经 `Enable=false` 永久关闭。

**2. 截图必须用 ADB**

`alas.json` 里必须 `Alas.Emulator.ScreenshotMethod = "ADB"`。
uiautomator2 在 Android 16 横屏下截图返回空图（`ImageTruncated: Empty image`），
会一路失败到 `RequestHumanTakeover`。

`alas.json` 是嵌套结构，正确路径是 `Alas` → `Emulator` → `ScreenshotMethod`；
顶层写 `ScreenshotMethod` 无效，`deploy.yaml` 里也没有 `Emulator:` 段。

**3. APP 读日志必须用 `su -M`**

这是最费时间的一个坑。App 进程的 root 有自己的 mount namespace，
里面根本看不到 `/data/data/com.termux`，直接 `su -c "tail ..."` 会报
`No such file or directory`，而同样的命令在 `adb shell su -c` 下完全正常。

试过并且**都不可行**的路径：

- `su -c "tail <Termux私有目录>"` → 空
- `su -c "... 2>/dev/null"` → Magisk 会把整段输出一起吞掉
- `su -c "run-as com.termux ..."` → `run-as: /data/user has wrong owner: 0/0, not 1000`
- root `cp` 到 App 私有目录 → `Permission denied`（SELinux 双向隔离）

最终解法：`ProcessBuilder("su", "-M", "-c", cmd)`，`-M` 即 `--mount-master`，
切到全局 mount namespace 后就能正常读。

**4. 布局不能用 ScrollView 包整页，但列表页可以**

`wrap_content` 的复合卡片放进 ScrollView 会被量成 0 高，状态卡整块消失
（试过 `setMinimumHeight`、固定高度、改纵向文本都没用）。
所以「控制台」是纯固定 `LinearLayout`，状态卡 64dp，日志区 `weight=1` 吃剩余高度。

「任务」「参数」两页可以套 ScrollView —— 它们装的是「名字 + 值 + 开关」这种
简单行，不会触发那个 0 高问题。

**4.1 单线程池 + 每 4 秒轮询 = 整个界面点不动**

这个坑最容易被误判成布局或注入问题。原因：`Executors.newSingleThreadExecutor()`
被一次配置读写（proot 启动要十几秒）占满，期间每 4 秒的状态轮询全部排在队列里，
积压的 `ui.post` 把主线程消息队列塞爆 —— 表现是连页签都点不动，但**没有任何 ANR 报错**。

现在用 `Executors.newCachedThreadPool()` + `volatile boolean refreshing` 防重入，
`finally` 里复位。轮询超时也从 150s 收到 75s。

**5. 触摸反馈不能用 OnTouchListener**

`setOnTouchListener`（返回 false）+ `setOnClickListener` 组合在这套布局里
点击**完全不触发**，任务按钮点了没任何反应。现在只保留 `OnClickListener`。

同理，开关也**不要**让 `Switch` 自己接点击：它的命中区太窄。
现在的做法是整行 `setClickable(true)` 自己接 `toggleEnable`，
`Switch` 设 `setClickable(false)` / `setFocusable(false)` 只当状态指示。

**6. 开发调试时 `input tap` 是坏的**

这台机器上 `adb shell input tap` 完全无效，必须用：

```powershell
adb shell "input motionevent DOWN 354 672"
adb shell "input motionevent UP 354 672"
```

同样的误判也曾让人以为 WebUI 页面点不动是移植层的问题 —— 后面确认 pywebio 前端
在电脑 Chrome 里点也一样没反应，才判定是前端环境问题。

**7. WebUI 那层不要指望**

「网页」页签内嵌的是官方 pywebio WebUI（`http://127.0.0.1:22267/?app=alas`）。
实测手机 WebView 和电脑 Chrome 里所有 pywebio 按钮都点不动，页面停在语言选择页；
ws 上下行通道实测通畅（python websockets 客户端能连能收发），
说明是 pywebio 1.6.2 前端 JS 层的问题，属于环境级不可控。

所以控制台是主入口，任务和参数全靠 APP 内的原生控件。

**8. 配置读写链路（`alas_config.py` + `c_config.sh`）**

`alas.json` 是嵌套结构（`Reward` → `Scheduler` → `Enable`），而且每个任务块
都有自己独立的一份 `Scheduler`（不是 `Alas.Scheduler`）。手写 JSON 极易出错，
所以读写都放在容器侧用 python 的 json 模块做，APP 只发指令、读结果：

| 操作 | 作用 |
| --- | --- |
| `scheduler` | 列出所有带 `Scheduler.Command` 的块，输出 block / command / enable / next_run 等 |
| `common` | 一次返回常用参数（group / name / path / value / type） |
| `dump <Block>` | 把某个任务块打平成 `[{path, value}]`，路径相对于该块 |
| `get <path>` | 读单个值 |
| `set <path> <value>` | 写单个值，类型跟着原值走（bool 认 1/true/yes/on，int/float 强转，list 逗号切分） |
| `enable <Block> 0\|1` | 改 `Scheduler.Enable`，打开时同时把 `NextRun` 拉到当前时间 |

写盘前自动 `alas.json.bak` 备份，`json.dump(indent=2, ensure_ascii=False)` 保持官方格式
（实测写回后 54306 字节，与原文一致）。

`c_config.sh` 是容器内包装，必须把输出同时落盘到
`/data/data/com.termux/files/home/alas-config.out` 再 `cat` ——
无 tty 的调用路径（adb run-as、APP 的 ProcessBuilder）读不到 proot 会话的 stdout。

APP 侧 `cfg(String args)` 因此是两步：先 `su(bash _exec.sh c_config.sh <args>, 75s)`
执行，再 `su("cat <alas-config.out>", 15s)` 读回。

**9. 自动检查更新（`alas_update.py` + `c_update.sh`）**

原版在 `module/webui/updater.py`：启动时按 `CheckUpdateInterval` 判断要不要检查，
`_check_update()` 走 `git fetch origin <Branch>` + `git log ..origin/<Branch>` 比 commit，
`update()` = `git_install()`（`deploy/git.py` 里 `git init` / `remote set-url` / `fetch` /
`reset --hard origin/branch`）+ `pip_install()`（`deploy/pip.py` 比 requirements 的 pin 与已装 dist-info，只补缺的）。

手机上照搬这套行不通，实测踩到三个硬障碍：

| 障碍 | 实测结果 | 处理 |
| --- | --- | --- |
| git 走 443 连不上 | `Failed to connect to github.com port 443 after 31663 ms`，另一次是 `GnuTLS recv error (-110)`；3 次重试 + 关 `sslVerify` + 调 `postBuffer` 全无效 | 放弃 git，检查更新改走 GitHub REST API |
| Gitee 镜像不可用 | `could not read Username for 'https://gitee.com'`（该镜像已失效/需认证） | 不进 fallback 链，直接走 API |
| `git init` 后 dubious ownership | `fatal: detected dubious ownership in repository` | 加 `git config --global --add safe.directory`（代码里保留，git 路径仍可用） |
| `_exec.sh <xx.py>` 被当 shell 脚本跑 | `import: command not found` | `_exec.sh` 改为按后缀自动选解释器（`.py` → `python3.10`，其余 → `bash`） |

改后的实际实现：

| 子命令 | 做什么 |
| --- | --- |
| `status` | 只读本地状态，不走网络（版本 sha / 版本时间 / 上次检查 / 上次更新） |
| `check` | `GET https://api.github.com/repos/LmeSzinc/AzurLaneAutoScript/commits?per_page=1`，取 `sha` / `commit.author.date` / `commit.message`，和 `config/alas_update_state.json` 里的 `known_sha` 比 |
| `apply` | 从 `https://codeload.github.com/LmeSzinc/AzurLaneAutoScript/zip/<sha>` 下源码快照（实测 82MB / 5.4 秒），解压 7863 个文件覆盖（实测 17 秒），移植层先备份到 `/tmp/alas_keep` 再还原 |
| `pip` | 用 `deploy/Termux/requirements-android.txt` 比对 `pip list --format=freeze`，只补缺的包，带 `--no-deps` |
| `stamp` | 把当前远端记为已知版本 |

三条保护：

1. **移植层保全**：`KEEP = [deploy/Termux, deploy/AndroidGui, alas_cli.py, config/alas.json, config/screen_adapt.json, deploy.yaml]`
2. **任务运行中禁止更新**：检测 `alas-task.running` 存在就直接拒绝，避免覆盖正在执行的代码
3. **首次建立基准**：手机上的 ALAS 是复制安装、没有 git 历史，第一次 check 只能把当前远端记成基准（返回 `first: true`），之后再有新提交才会报「发现新版本」

APP 侧：启动 12 秒后静默跑一次 `check`（24 小时内不重复），发现有新版时把「更新」页签文字变橙 + toast；
进「更新」页时若已有结果直接渲染，省一次容器往返。

`c_update.sh` 和 `c_config.sh` 一样，输出落盘 `alas-update.out` 再 `cat`。
`upd()` 的超时给到 900 秒（apply 要下 82MB 再解压几千个文件）。

---

## 七、已知问题

| 问题 | 状态 | 说明 |
| --- | --- | --- |
| **点「开始」跑不起来** | **已修复** | 四个问题叠加，见下面「点开始失效的四个真凶」。现在真机点一次即可：脚本全流程跑完、游戏横屏 1280x720、ALAS 起在容器里、界面显示「挂机运行中 · 容器进程 3 个」 |
| **起来了但一次任务都没跑，界面看不到运行内容** | **已修复** | 两个独立问题，见下面「挂机看似正常但实际没在跑」。**这一条最容易被误判成"移植不完整"**：WebUI HTTP 200、进程数正常、界面显示「挂机运行中」，但 ALAS 一次任务都没执行过 |
| 任务只端到端跑过 `reward` | 部分验证 | `日常收菜` 实测完整通过（进 `page_reward`、收石油/金币/经验、领任务奖励、回主页，日志末尾 `任务 reward 结果：成功`）。其余 9 个单次执行项没逐个跑过，代码路径相同 |
| 挂机开关的点击没在真机上点成功过 | 待用户手动确认 | 开关背后的 `enable` 命令已独立验证正确（`{"ok": true, "block": "Exercise", "enable": true, "next_run": "..."}`，配置确实写入并恢复）。**adb 注入点击在真机上不可靠**（`input tap` 完全失效、`motionevent` 偶发丢事件、且必须和 `screencap` 写在同一次 `adb shell` 里才稳），所以自动化没点中过。用户手指点不受影响 |
| 点任务后屏幕会跳到游戏 | 正常现象 | ALAS 截图抓的是屏幕本身，游戏必须在最前；APP 会自动退到后台不遮挡 |
| 任务运行时看实时日志 | 需回到 APP | ALAS 要游戏前台、用户要 App 前台看日志 —— 本质冲突。当前方案是任务跑完回 APP 看完整日志；后续可做悬浮窗 |
| `zhName()` 与 `BLOCK_ZH` 重复 | 待统一 | APP 里硬编码了约 60 个块中文名，`alas_config.py` 里又有一份 38 条的 `BLOCK_ZH`，两处内容有交叠。后续应让 APP 只从服务端取，删掉本地表 |
| 参数页常用项 | 已扩到 42 项 | 主线从 3 项扩到 16 项（含「打哪一关」24 个候选）；底部「按任务块浏览」覆盖全部 1300+ 项且已汉化 |
| 参数页首屏空白 15–25 秒 | 正常现象 | 每一次读配置都要起 proot + python 往返，这段时间界面是空白（没有 loading 提示）。已考虑加骨架屏 |
| `apply-alas-config.py` 默认值 | 有隐患 | `--screenshot` 默认还是 `uiautomator2`，重跑会把已生效的 ADB 覆盖回去 |
| Termux:Boot 开机自启 | 未做 | 重启后需要手动进 Termux 起一次 |
| `preflight.py --full` 的 OCR 项 | 未跑通 | cnocr 默认模型要联网下载 |
| 长按菜单 / 文件导入导出 | 未做 | 需求文档里的「右键菜单转长按」和文件选择器还没接；`alas.json` 目前只靠自动 `.bak` 备份，没有手动导入导出入口 |
| `ServerName=disabled` 不在候选里 | 小瑕疵 | 单选列表的 `options` 只有 `cn/en/jp/kr/tw`，当前值 `disabled` 匹配不上，打开时无预选项（仍可正常选并保存） |

| `apply-alas-config.py` 默认值 | 有隐患 | `--screenshot` 默认还是 `uiautomator2`，重跑会把已生效的 ADB 覆盖回去 |
| Termux:Boot 开机自启 | 未做 | 重启后需要手动进 Termux 起一次 |
| `preflight.py --full` 的 OCR 项 | 未跑通 | cnocr 默认模型要联网下载 |
| 长按菜单 / 文件导入导出 | 未做 | 需求文档里的「右键菜单转长按」和文件选择器还没接；`alas.json` 目前只靠自动 `.bak` 备份，没有手动导入导出入口 |

### 点开始失效的四个真凶（2026-11-15 定位并修复）

现象：手动在 Termux 跑 `start-game.sh all` 完全成功，APP 点「开始」却毫无反应，`alas-daemon.log` 不更新。四个问题叠在一起，任何一个都能单独让它失效：

| # | 问题 | 真因 | 修法 |
| --- | --- | --- | --- |
| 1 | 用相对 `bash` 拼命令 | `su -M -c` 的 PATH 是 `/product/bin:...:/system/bin:...`，**不含 Termux 的 bin**。`bash` 直接 `inaccessible or not found`，`setsid: exec bash: No such file or directory`，脚本一行没跑 | `act()` 三处命令全改用 `TERMUX_BASH` 绝对路径。注意 `cfg()`/`upd()` 本来就用的是绝对路径，所以「参数、更新能读，点开始却不动」 |
| 2 | `countProcs()` 用 `pgrep -f <容器名>` | `pgrep -f` 匹配完整命令行，而执行它的 `su -M -c pgrep -f ubuntu-...` **自己命令行里就带容器名** → 永远至少匹配到自己。实测 ALAS 已全停干净，它仍返回 3，界面就一直挂「挂机运行中」 | 改 `pgrep -x proot` + `pgrep -x python3.10` 精确匹配进程名。实测起前 0/0、起后 1/2 |
| 3 | `setsid` 没加 `-w` | Toybox 的 `setsid` 不加 `-w` 时 fork 完**父进程立刻退出** → su 的 shell 结束 → APP 的 `waitFor` 立即返回并关闭管道 → 脚本下一句 `echo` 写已关闭管道吃到 **SIGPIPE**，整个 bash 被杀。落盘证据：`# exit=0` 但输出只有 3 行标题 | 改 `/system/bin/setsid -w <bash> <start-game.sh> all`。`-w` = 等子进程并回传它的退出码，既有完整输出又能拿真实 exit |
| 4 | `su()` 先同步读完 stdout 再 `waitFor` | `while(readLine() != null)` 卡在 EOF 才轮到 `waitFor`，超时形同虚设；只要有任何后代持有管道，worker 线程永久阻塞、`busy` 永不复位、界面僵在「启动中」 | 读流放独立 daemon 线程，主线程先 `waitFor(timeout)`，之后 `reader.join(2000)` 收尾 |

配套改动：

- **`run-alas.sh` 的 daemon 分支加 `setsid`**（不加 `-w`，因为它是 `nohup ... &` 后台任务、日志重定向到文件不会 SIGPIPE）。原因：从 APP 拉起时整条链和 APP 同进程组，游戏一上前台 APP 就退后台，MIUI 按进程组回收会把 proot 一起收掉 —— 实测 `18:37:54` 起好 Uvicorn、`18:38:59` 就 `Shutting down`，正好 65 秒。从 Termux 手动跑没这问题，所以一直没暴露。
- **`su()` 的输出不再丢弃**：落盘到 `/data/data/com.alas.console/files/conf/alas-<动作>.out`（首行 `# exit=N`），启动失败时直接把输出尾部贴到界面日志区。排查时 `adb shell su -c "cat /data/data/com.alas.console/files/conf/alas-start.out"` 就能看到脚本卡在哪一步。
- **`act("start")` 超时 180s → 300s**（实测跑完一轮约 100 秒，冷启动更久）。
- **失败不再假报「已启动」**：`looksOk()` + 退出码双重判断，失败弹「启动失败，日志区已显示脚本输出」。

### 挂机看似正常但实际没在跑（2026-11-15 定位并修复）

这一条比「点开始没反应」更隐蔽，因为**所有表象都是绿的**：点开始成功、进程数正常、界面「挂机运行中」、日志也有内容 —— 但 ALAS **一次任务都没真正执行过**。用户看到的就是「游戏在跑但 ALAS 什么都不干、什么也看不到」。

| # | 问题 | 真因 | 修法 |
| --- | --- | --- | --- |
| 1 | `30-start-alas.sh` 里 `exec python3.10 gui.py` | **`gui.py` 只是 WebUI 外壳（pywebio + fastapi），调度器根本没启动。** PC 版还要在网页上点那个「启动」大按钮，它才去读 config 的任务队列 —— 而 pywebio 前端点击在这套环境里是失效的（手机 WebView 与电脑 Chrome 表现一致），所以永远点不到那一下。日志里只有 uvicorn 的 `GET /js/xxx` 和 `Start task handler`，**没有任何 `Scheduler: Start task`** | 改成 `exec python3.10 alas.py`。`alas.py` 的 `__main__` 就是 `AzurLaneAutoScript().loop()`，直接进调度循环，不需要任何人点按钮。保留 `ALAS_GUI=1` 开关可切回 WebUI |
| 2 | `tailLog()` 先读 `alas-task.log`，有内容就返回 | `alas-task.log` 里一直留着上次手动跑任务的老内容（那次 16:57 的 reward 记录），于是界面日志区**永远**停在那份旧记录上，调度器实时写的新日志根本不会被读 | 反过来：只有任务真在跑（`alas-tasks.running` 标志存在）才看任务日志，否则一律看 `alas-daemon.log` |

配套：

- **`run-alas.sh` 的存活探测停用了 WebUI 那套**（不再 `curl 127.0.0.1:22267` 判 200）。改为查日志里有没有 `Start scheduler loop` —— 那是 `alas.py` 进循环时打的**唯一可靠信号**；HTTP 200 只能证明外壳活着。
- **`stop_alas` 的 pkill 串写成 `python3.10 alas.py`** 而不是 `alas.py`：`pkill -f` 同样扫描自己的命令行，串太短会把执行它的进程一起匹配上。
- **`status` 的 python 计数改用 `ps -ef | grep '[p]ython3.10'`**：`pgrep -x python3.10` 在 Termux 层看不到容器里的进程（它挂在 proot 名下），要 `su` 进去才看得到。

修复后的日志证据（真机）：

```
Pending tasks: ['Restart', 'Commission', 'Tactical', 'Research',
                'Meowfficer', 'Guild', 'Reward', 'Freebies', 'Event', 'Main']
Scheduler: Start task `Restart`
<<< APP RESTART >>> / App stop: com.bilibili.azurlane / App start: ...
<<< APP LOGIN >>>  [Device Orientation] 1 (HOME key on the right)
Click ( 645, 586) @ RESEARCH_QUEUE_ADD
[RESEARCH 0.284s] ['C-038-RF', 'G-412-MI', 'D-766-MI', 'G-236-MI', 'H-387-MI']
Research project: S9 G-412-MI → <<< RESEARCH PROJECT START >>> → RESEARCH_START
```

注意日志里那句 `[Package_name] com.alas.console` + `Game not running` **不是 bug**：ALAS 检测的是**当前前台应用**，那时前台正好是控制台 APP，于是它判定游戏没在跑、自动触发 `Restart` 把游戏拉起来 —— 这是 ALAS 的自愈逻辑在正常工作。配置里的 `Emulator.PackageName` 一直是正确的 `com.bilibili.azurlane`。

---


## 八、后续维护建议

1. **加单次任务**：`MainActivity.buildTaskPage()` 里 `single` 数组加一行，
   第三个字段填 `alas_cli.py` 的 `TASKS` 方法名（19 项都能用）。
2. **加常用参数**：改 `deploy/Termux/alas_config.py` 的 `COMMON` 列表
   （group / 中文名 / 点路径 三个字段），APP 侧不用改，重新加载就出现。
   不要把这个列表做太大 —— 有「按任务块浏览」兜底。
3. **改配置**：一律走 `alas_config.py`，别在 APP 里拼 JSON 字符串。
4. **悬浮窗看实时日志**：把任务输出重定向到文件后，用 `TYPE_APPLICATION_OVERLAY`
   做个小窗读同一个文件，能解决「看日志」和「游戏必须前台」的冲突。
5. **开机自启**：装 Termux:Boot，里面放 `nohup ... start-game.sh all &`。
6. **别动的东西**：`screen_adapt` 保持 `Enable=false`；`ScreenshotMethod` 保持 `ADB`；
   分辨率保持 `720x1280 @ 293`（换机型要按 `宽/density×160` 重算一遍 dp）；
   线程池必须多线程 + 轮询防重入。

---

## 九、验证记录（本轮实测）

| 验证项 | 结果 |
| --- | --- |
| 参数页读到真实值 | `ADB` / `MaaTouch` / `127.0.0.1:5555` / `com.bilibili.azurlane` / `12-4` / `1000` 全部与手机一致 |
| 参数修改写盘 | 石油下限改 1500 → UI 刷新 → `alas.json` 第 103 行确实变成 `"OilLimit": 1500`，`.bak` 同步生成，文件仍是 54306 字节；随后改回 1000 成功 |
| 挂机队列读取 | 读出 9 项已启用（Commission 30-60 / Dorm 278 / Freebies 30 / Guild 30 / Meowfficer 30 / Research 30-60 / Restart 0 / Reward 120-240 / Tactical 30-60） |
| 挂机开关命令 | `enable Exercise 1` → `{"ok":true,"enable":true,"next_run":"2026-11-15 17:26:24"}`；`enable Exercise 0` 恢复后 `get` 返回 `false` |
| 全量参数浏览 | 选 `Main` 块读出 Fleet / Submarine / Emotion 全部叶子项并可编辑 |
| 单任务执行 | 日常收菜端到端通过，日志末尾 `任务 reward 结果：成功` |
| 更新检查（首次） | API 返回 `{"ok":true,"first":true,"has_update":false,"remote":{"sha":"1ed81bac…","time":"2026-11-13T17:46:08Z","message":"Merge pull request #6152 from LmeSzinc/bug_fix","source":"api"}}`，基点已写入 state |
| 更新检查（已是最新） | 第二次返回 `has_update:false`；人工把 `known_sha` 改成假值后返回 `has_update:true`，界面正确显示「发现新版本」 |
| 启动自动检查 | 重装清数据后启动，45 秒内 `checked_at` 自动变成 `2026-11-15 18:21:23`；界面上更新页签保持白色（无新版不打扰） |
| 一键更新（真跑） | `{"ok":true,"written":7863,"skipped":0,"kept":"deploy/Termux, alas_cli.py, config/alas.json, config/screen_adapt.json"}`，82MB 下载 5.4 秒、解压覆盖 17 秒 |
| 更新后可用性 | 19 个任务全部正常列出，`config/alas.json` 仍 54306 字节未被破坏 |
| 依赖补齐 | `{"ok":true,"missing":[],"source":"requirements-android.txt","note":"依赖已齐全，无需补装"}` |
| 任务运行中禁止更新 | 检测 `alas-task.running` 后直接拒绝，不会覆盖正在执行的代码 |
| **点「开始」端到端（修后）** | 真机点一次 → `alas-start.out` 记录 `# exit=0` 且 `[0/3]`→`[3/3]` 全流程完整 → `launched pid=32503`、`proot 存活：32503`、`WebUI 探测：HTTP=200`、`完成`；游戏横屏 `cur=1280x720`；`ps` 里 proot=1；界面回到 APP 显示「● 挂机运行中 · 容器进程 3 个」 |
| 相对 `bash` 不可用（实测） | `su -M -c "bash -c echo"` → `bash: inaccessible or not found`；`su -M -c "/system/bin/setsid bash -c echo"` → `setsid: exec bash: No such file or directory`；换成 `/data/data/com.termux/files/usr/bin/bash` 才能起 |
| `pgrep -f` 自匹配（实测） | ALAS 全停干净后 `pgrep -f ubuntu-base-22.04-base-arm64 \| wc -l` 仍返回 **3**；`pgrep -x proot` / `pgrep -x python3.10` 返回 **0/0**，起来后返回 **1/2** |
| `setsid` 少 `-w`（实测） | 落盘 `# exit=0` 但输出只有 3 行标题，脚本在第一个 `echo` 处吃 SIGPIPE 被杀；加 `-w` 后完整跑完并 `EXIT=0` |
| **调度器真跑起来（修后）** | 日志出现 `Start scheduler loop: alas`、`Pending tasks`（10 项）、`Scheduler: Start task Restart`、`APP RESTART`/`APP LOGIN`、`[Device Orientation] 1`；`run-alas.sh status` 报 `调度器 : 已进入调度循环`，`python : 24014` |
| **ALAS 真的在操作游戏（修后）** | 日志连续出现真实点击与识别：`Click ( 645, 586) @ RESEARCH_QUEUE_ADD`、`[RESEARCH 0.284s]` 认出 5 个科研项目、`Research project: S9 G-412-MI`、`<<< RESEARCH PROJECT START >>>`、`RESEARCH_START`、`RESEARCH QUEUE ADD`，科研队列真的被填进去了 |
| **界面能看到实时内容（修后）** | 控制台日志区显示 `19:30:04` 的调度实时输出（`<<< RESEARCH PROJECT START >>>`、`Click ( 439, 254) @ ENTRANCE_2`），不再是那份 16:57 的旧任务记录；状态「● 挂机运行中 · 容器进程 2 个」 |

### 本轮：汉化 + 参数简化的实测

| 验证项 | 结果 |
| --- | --- |
| 主线组从 3 项扩到 16 项 | `c_config.sh common` 返回的 `group=="主线"` 项数 = 16（打哪一关／关卡难度／周回次数／石油下限／停刷条件／自动升图／出新船就换图／道中舰队／BOSS 舰队／出击顺序／自动寻敌／连续作战／防伏击／潜艇模式／潜艇舰队／心情控制／主线挂机） |
| 「打哪一关」候选列表 | `Main.Campaign.Name` 带 `options` 共 **64 个**（`1-1`…`16-4`，运行时扫 ALAS 自己的 `campaign/campaign_main/campaign_<章>_<关>.py` 生成）；界面点该行弹出单选列表并可选中 |
| 关卡列表覆盖完整（修） | 早先硬编码 `10-1~15-4` 共 24 个，漏掉 `1-1~9-4` 和 `16-1~16-4`，用户报「看不到 16 关以后的内容」；改为扫描后 `1-1`…`16-4` 全在，`16-1`/`16-4`/`1-1` 均 `True` |
| 选中即写盘（真跑） | 误点中方圆内某一项后值从 `12-4` 变成 `15-1`；`c_config.sh set Main.Campaign.Name 12-4` 返回 `{"ok":true,"old":"15-1","new":"12-4"}`，说明「点选 → 写入 → 生效」整条链路通 |
| 枚举字段全部带候选 | `common()` 里 `Campaign.Mode`／`FleetOrder`／`Submarine.Mode`／`Emotion.Fleet1Control`／`StopCondition.MapAchievement` 等均返回 `options`，不必手打 |
| 按块浏览汉化 | `dump Main` 共 54 项，**53 项带 `zh`**（仅 `Storage.Storage` 是空字典没有译名），**18 项带 `options`**；`Campaign.Name` → 「打哪一关」、`HpControl.LowHpRetreatThreshold` → 「撤退阈值」 |
| 布尔项改胶囊开关 | 布尔叶子渲染成「开／关」胶囊，点一下直接 `setParam(path, cur?"false":"true")`，不再弹输入框让用户输 `true/false` |
| **改完立刻生效（新增）** | `run-alas.sh restart` = stop + daemon（不重拉游戏，比「开始」快）；实测输出 `launched pid=24203`、`调度器：已进入调度循环` |
| **restart 后读的是新值（实测）** | 把 `Campaign.Name` 设为 `15-4` 再 restart，日志立刻出现 `Start scheduler loop: alas`、`Pending tasks: ['Main']`、`Scheduler: Start task Main`、`CAMPAIGN_15_4`、`Campaign run: event="campaign_main", name=campaign_15_4` —— 新关卡真的生效了 |
| **参数页「立即生效」条** | 参数页顶部显示「改完想立刻用上？／重启调度器，几秒后用新配置重新开跑／立即生效」，真机点中后弹出「正在重启调度器…」并真的重启 |
| 保存后的提示 | 原来只 toast「已保存」，容易被理解成已经在跑；改成「已保存 · 想马上换图点顶部『立即生效』」 |
| **跨 su 会话误报「已停止」（修）** | 从另一个 su 会话（adb 或上一轮 APP）起的 proot/python，`pgrep` 在 App 自己的 mount namespace 里看不到，`countProcs()` 返回 0 → 界面显示「已停止」，可日志里 `CAMPAIGN_15_4` 正在一行行地打。**这就是「点了没反应」的一大来源**。修法：进程数为 0 时再用 `alas-daemon.log` 的 mtime 兜底，120 秒内还在写就判活着 |
| mtime 兜底实测 | `run-alas.sh status` 新增一行 `存活 : 日志 6s 前还在写（ps 看不到时以此为准）`；APP 控制台同期显示「● 挂机运行中 · 容器进程 2 个」 |
| 控制台如实反映运行内容 | 截图确认日志区在滚动真实调度输出：`[Map_clear_percentage] 99%`、`[Map_info] 99%, star_1, star_2, star_3, 100_percent_clear, 3_stars, threat_safe, clear_mode`、`[StopCondition_MapAchievement] non_stop`、`[Clear_Mode] on`、`[Auto_Search] on`、`Click (1018, 522) @ MAP_PREPARATION` |

### 本轮：改关卡挪到首页（用户反馈「要一直点好麻烦」）

原来的路径：控制台 → 参数页签 → 等 20 秒渲染 → 往下滚到「主线」组 → 点「打哪一关」→ 在 64 项竖列表里翻找。**5 步 + 两次滚动**。

| 改动 | 说明 |
| --- | --- |
| 首页「主线 · 打哪一关」卡 | 控制台状态卡和三个按钮下方，11sp 灰标题「主线 · 打哪一关」+ **19sp 大字关卡号** + 右侧蓝色「改关卡 ›」。进 APP 第一眼就看得见 |
| 参数页同一张卡 | 放在「立即生效」条下面，成为页面第二块内容；同时 `Main.Campaign.Name` 从「主线」组里过滤掉，避免同一项出现两次 |
| 按章分行的网格选择器 | `AlertDialog` 里一行一章（`1 章` + `1 2 3 4` 四个格子），一次能看 11 章；不再是一条 64 项的竖列表 |
| **打开就定位到当前章** | `sv.scrollTo` 按章号预滚，往上留两行。实测当前 `15-4` 时打开直接显示 6~16 章，**15 章 4 关蓝底白字高亮**，不用自己翻 |
| 点一格即写入 | 点中后 `cfg("set Main.Campaign.Name <章>-<关>")`，成功立刻改两处界面上的数字；真机实测误触第 5 章第 4 关后值变 `5-4`，随即用 `set` 改回 `15-4`（返回 `{"ok":true,"old":"5-4","new":"15-4"}`） |
| 本地缓存、零等待 | 关卡号只在启动后 6 秒查一次（`get Main.Campaign.Name`），之后全靠 `curChapter` 本地维护 —— 打开 APP 立刻有数字，改完也是立刻变，不再为了显示一个关卡号去起 proot |
| 改关卡不走 `setParam` | `setParam` 存完会 `loadParams()` 重拉整页（15~25 秒）；关卡用独立的 `setChapter`，只更新缓存和两处文字，点完就是最终状态 |
| 首页选择器用本地章表 | `CH_MAX=16 / ST_MAX=4`。参数页那份候选是从 ALAS 目录扫的（会随更新自动变多），首页要零等待所以用本地表；将来真出第 17 章，参数页仍能选到 |

点击次数：**5 步 + 2 次滚动 → 2 次点击（首页「改关卡」+ 点格子）**，且无需滚动。

### 本轮：两行「主线设置」卡（用户要求「打多少次也要显眼」）

用户继续加码：不只关卡，「打多少次」（周回次数 `StopCondition.RunCount`）也得一眼看见、能直接改。于是把原来那张单行关卡卡换成一张两行卡，两项并排。

```
┌─────────────────────────────────────┐
│ 打哪一关                    改关卡 › │
│ 16-3                                │
│ ─────────────────────────────────── │
│ 打多少次                    改次数 › │
│ 一直打（不限）                       │
└─────────────────────────────────────┘
```

| 改动 | 说明 |
| --- | --- |
| 一张卡两行 | 首页和参数页都换成 `mainCard`：第一行关卡、第二行次数，各带独立入口。`chapterCard` 保留为转发，避免同页出现两张卡 |
| 次数用预设格子 | `0 1 2 3 5 10 20 30 50 100 200 500` 共 12 项，一行 4 个共 3 行。点一下就定，不用弹输入框、不会输成负数 |
| `0` 显示成「一直打（不限）」 | ALAS 里 `RunCount=0` 是「不限次数」，直接显示 `0` 用户看不懂 |
| 面板跟着当前值高亮 | 打开时把当前值那一格刷成蓝底白字。实测当前 `0` 时「不限」高亮 |
| `maininfo` 一次读全 | `alas_config.py` 新增该操作，一次返回 `chapter / run_count / oil_limit / enable / mode`。原来两次 `get` 要起两遍 proot，现在**一次往返**。实测返回 `{"chapter":"16-3","run_count":0,"oil_limit":1000,"enable":true,"mode":"normal"}` |
| 加载期不摆误导值 | 没读到之前显示「读取中…」，不会先放一个 `0` 让人误以为是不限次数 |
| `paintMain` 统一刷新 | 关卡和次数改完都走这一个方法，同时刷首页和参数页两处，不会一页新一页旧 |
| 高度取舍 | `chapterCardLp` 从 64dp 改成 `WRAP_CONTENT`，卡片按内容撑开；日志区少几行，换两行常驻入口值得 |

真机实测（截图确认）：首页状态卡下方依次是「开始/结束/恢复屏幕」→ 两行主线卡 → 运行日志；卡片显示 `16-3` / `3 次`，次数面板 12 格齐全且当前值高亮。

操作教训（复现过两次）：**验证 APP 界面必须先 `run-alas.sh stop`**。调度器在跑时 ALAS 不断把游戏切前台，`input motionevent` 落到游戏上（截图里出现游戏自己的「服务器连接失败 [NetworkDown]」弹窗，那是游戏切后台触发的，与 ALAS 无关）。

| d8 命令行超长（已修） | 加了匿名内部类后 `MainActivity$N.class` 暴增，直接把 `$classFiles` 铺在命令行会撞 Windows 8191 字符上限报 `The command line is too long`；改成写 `d8args.txt` + `@argfile` 后恢复，输出 `BUILD OK` |
| 容器往返耗时 | 参数页首次进入要起 proot + python，**往返 15–25 秒**；10 秒截图会看到整页空白（不是 bug），等 25 秒即正常渲染 |
| 点击 APP 的前置条件 | ALAS 调度器不断把游戏切前台，`input motionevent` 会落到游戏上（实测点到的是游戏启动画面）；验证界面必须先 `run-alas.sh stop` |

## 本轮：改关卡自动生效，不再重启游戏（对标 PC 版行为）

用户反馈：PC 版把主线从 `15-1` 改成 `16-1`，程序「直接响应、不重启游戏」，像堆栈一样排到下面，上面跑完接着跑；而移植版要么整个重启，要么什么都不做。

### 根因（读源码确认，不是猜）

三点凑在一起才出这个问题：

1. **ALAS 本来就有热切换流程**。`module/campaign/run.py:470-472` 每跑完一轮地图都会调一次 `config.task_switched()`：

   ```python
   # Scheduler
   if self.config.task_switched():
       self.campaign.ensure_auto_search_exit()
       self.config.task_stop()          # 抛 TaskEnd，收尾当前任务
   ```

   任务收尾后，`alas.py` 主循环第 613-615 行：

   ```python
   if success:
       del_cached_property(self, 'config')   # 丢掉 config 缓存
       continue                              # 下一圈重建 → 重读 config/alas.json
   ```

   重建时 `__init__` → `load()` + `bind()` 会把新的 `Campaign_Name` 装进内存，于是**下一轮直接去打新图，游戏停在地图界面不动、进程不重启**。这就是用户说的「堆栈」行为。

2. **但 `task_switched()` 只看「下一个该跑谁」**（`module/config/config.py:614-622`）：

   ```python
   prev = self.task
   self.load()          # 重读磁盘，但这只更新 self.data
   new = self.get_next()
   if prev == new: return False    # ← 改关卡时任务名还是 Main，走这里
   ```

   改关卡时任务名依然是 `Main`，判定成「继续」，日志里就是 `Continue task 'Main'`，接着刷旧图 → **「什么都不做」**。

3. **配置是快照**。`bind()` 用 `super().__setattr__` 把值写成实例属性；`__setattr__` 之后只往 `modified` 里记、不更新实例属性。所以 `self.Campaign_Name` 一直是 bind 时那一刻的值，磁盘改了内存也不知道。

   而原来的「立即生效」按钮走的是 `run-alas.sh restart`（stop + daemon），**把 proot 和游戏一起重开** → 这就是用户看到的「一改就重启游戏」。

### 修法：移植层加一层 patch，不改上游代码

新增 `deploy/Termux/alas_hot.py` 作为启动入口（替代直跑 `alas.py`），只给 `task_switched` 补两条判定，命中后**仍走 ALAS 原生收尾流程**：

| 判定 | 触发条件 |
| --- | --- |
| 关卡被改 | 磁盘 `Main.Campaign.Name` ≠ 内存正在跑的 `Campaign_Name`（归一化 `_`/`-`、大小写、首尾空格后比较） |
| 手动请求 | 存在 `config/hot_reload` 标记文件（APP 的「立即生效」写它，消费后删除） |

两条都只在 `self.task.command == 'Main'` 时生效，别的任务不会因为改了关卡被打断；异常一律吞掉并记 warning —— 热切换是锦上添花，不能让它把挂机搞崩。

**`RunCount` 为什么不做同样的比对**：`StopCondition.RunCount` 会被 Campaign 自己每轮 `-= 1` 并写盘（`run.py:453-454`），拿磁盘值和内存值比，每一轮都「变化」→ 会变成每轮都切任务、无限来回切。关卡名只由用户改、ALAS 自己不动它，所以只有它适合当信号。次数走「点『立即生效』→ 标记文件 → 切一次 → 重读配置」，一次切换把新次数一起带进去。

### 死循环推演（重点核对过）

- 切之前：磁盘 `16-4`，内存 `16-3` → 不等 → 切
- `TaskEnd` → 主循环 `del_cached_property(config)` → **新实例**（`overridden` 是空的，`CampaignRun.run()` 里的 `override(Campaign_Name=...)` 不会残留到下一轮）→ `bind` 后内存 = `16-4`
- 再跑一轮：磁盘 `16-4` = 内存 `16-4` → 不切 ✓ 不会来回切

### 配套改动

| 改动 | 说明 |
| --- | --- |
| `30-start-alas.sh` | `exec "$PY" alas.py` → `exec "$PY" "$ALAS_DIR/deploy/Termux/alas_hot.py"` |
| `alas_hot.py` 放 `deploy/Termux` | `alas_update.py` 的 KEEP 清单包含 `deploy/Termux`，更新时会备份还原；放项目根会被 zip 覆盖掉，启动脚本就找不到入口了 |
| `c_hot.sh` | 只 `date +%s > config/hot_reload`，不杀进程、不重启游戏 |
| APP「立即生效」 | `applyParams()` 从 `run-alas.sh restart` 改为调 `c_hot.sh`；文案由「重启调度器，几秒后用新配置重新开跑」改为「下一轮地图跑完就换，不重启游戏」 |
| 关卡/次数改完的提示 | 由「点『立即生效』马上换图」改为「下一轮地图跑完自动换图」（关卡改动本来就自动生效，不用再点） |

### 部署状态（本轮未完成）

改动已在电脑上完成并通过编译（`BUILD OK ... (44 KB)`），但**尚未推到手机**：用户当前没有连接 adb。待恢复连接后需要：

1. push `alas_hot.py` / `c_hot.sh` / `30-start-alas.sh` 到 `deploy/Termux/` 并 `chmod 755`
2. 安装新 APK
3. `run-alas.sh restart` 一次（这次重启是必须的：启动入口从 `alas.py` 换成 `alas_hot.py`，只有重启才会加载 patch）；确认日志出现 `[热切换] 已装载`
4. 验证：改关卡 → 等这一轮地图跑完 → 日志应出现 `[热切换] 关卡被改动` + `Switch task`，随后是新地图的 `Campaign run: event="campaign_main", name=campaign_xx_x`，且游戏没有被拉起重启

## 本轮：五段页签改成左侧滑抽屉

### 为什么改

原来顶部一条五段的分段控件（控制台/任务/参数/更新/网页）。问题很清楚：五个中文词挤在 393dp 宽的一行里，每段只有 70dp 出头，文字被迫压到 13sp，点击区域又矮又小；而且这排东西常年占着一行高度，控制台的可视区被挤压。用户的要求是把菜单改成「从左往右滑出来」。

### 参考了什么

查了 Material Design 官方的 Navigation drawer 规范（`m2.material.io/components/navigation-drawer`），把能用的直接采纳：

| 规范项 | 官方值 | 本项目取值 | 说明 |
| --- | --- | --- | --- |
| 抽屉最大宽度 | NavigationView `maxWidth` 默认 280dp | **280dp** | 手机 720px≈393dp，78% ≈ 306dp，取小值 280dp |
| 抽屉宽度 | — | **78% 屏宽** | 右边留一条看得见底层页面，用户知道下面没消失 |
| 遮罩 | DrawerLayout 默认黑 60%；规范写 32%（浅色主题） | **黑 60%** | 纯黑底 + 深灰卡片，32% 几乎看不出层次 |
| 抽屉海拔 | DrawerLayout 默认 10dp | **10dp** | 照官方默认 |
| 激活项底纹 | primary + **12%** | 蓝色 + **12%**（`0x1F` alpha） | 原来我拍的 10%，改成规范值 |
| item 高度 | 48dp（listPreferredItemHeightSmall） | **52dp** | 中文行给高一点更好点，触控目标远超 48dp 下限 |
| 关闭方式 | 点条目 / 点遮罩 / 向锚定边反向滑 | 全部保留 | DrawerLayout 自带，不用自己写 |
| 边缘手势 | 左边缘起手 | DrawerLayout 默认 20dp 边缘区 | 不用手写 GestureDetector |

Visual 层面没有照搬 Material（Material 是浅色大图标 + 彩色选中块），这里仍按用户要的 iOS 深色：深灰面板、左侧 3dp 蓝竖条、白字、12% 浅蓝底、细分组线。

### 实现

| 改动 | 说明 |
| --- | --- |
| 装 AndroidX | 这套工程没有 gradle，直接下 AAR 解出 `classes.jar`：`drawerlayout 1.1.1` / `customview 1.1.0`（ViewDragHelper）/ `core 1.12.0` / `annotation 1.7.1`，另外补了 `lifecycle-common`、`lifecycle-runtime`、`versionedparcelable`、`concurrent-futures`、`annotation-experimental` |
| 只取 classes.jar | **不编译 AAR 里的 res**：DrawerLayout 只读 framework 的 `layout_gravity`，不引用自定义 attr，用不到那些资源 |
| jar 固化到项目内 | 放 `deploy/AndroidGui/libs/`，原件 AAR 留在 `libs/aar/`。原来放 `%TEMP%` 下，临时目录一清就编不了 —— 这个坑踩过一次（删掉 temp 后 javac 直接 `找不到符号 DrawerLayout`） |
| `build.ps1` 自动收集 | javac 的 `-cp` 和 d8 的输入列表都从 `libs\*.jar` 扫（非递归，不会把 `aar/` 里的东西当 jar） |
| 顶栏 | 汉堡 + `ALAS` + 右侧当前页名（13sp 灰）。汉堡是自绘的三条 18x2dp 圆角横线，不用 `☰` 字符 —— 那个字符在部分机型字体里会缺字或宽窄不一 |
| 抽屉内容 | 标题区（ALAS 28sp / 副标题）+ 五条菜单项 + 底部版本行，`separator()` 分组 |
| 选中态 | 左侧 3dp 蓝竖条 + 白字 + `sans-serif-medium` + 12% 蓝底，未选中灰字无条无底 |
| 页面切换 | `showTab()` 里同步刷 5 个抽屉项 + 顶栏标题；原 `seg()` / `styleSeg()` 删除 |
| 更新提示 | 查到新版时抽屉里「更新」变橙，更新完成后恢复（原来挂在 `tvSegUpdate` 上，迁到 `drawerLabel[TAB_UPDATE]`） |
| 返回键 | `onBackPressed` 里先判断抽屉开着就关抽屉，不直接退出 APP |
| 页面内边距 | 原大标题区的 20dp 左右内边距挪到 `body` 容器，五个页面内部布局完全不用改 |

APK 体积：44 KB → **484 KB**（AndroidX core 约 1.3 MB 的类全量 dex 化，d8 不做裁剪；对手机没影响）。

### 已验证 / 未验证

已通过：`javac` 编译、`d8` 转 dex（**0 条 warning**，说明没有缺失类的引用）、`apksigner` 验签、`BUILD OK ... (484 KB)`。这两个数值改动的取值都对着官方规范核对过。

**未验证**：真机效果（滑动手感、抽屉宽度观感、汉堡点击）。用户当前没有连接 adb，这一轮无法部署和截图。待插线后需要确认：
1. 从屏幕左边缘往右滑能跟手拉出抽屉，松手按位移/速度判定开合
2. 抽屉打开时点遮罩、点条目、反向滑都能关
3. 返回键先关抽屉
4. 抽屉里五个条目的选中态跟随页面切换

## 本轮：任务行的「进」和「开关」拆成两个命中区

### 问题

任务页每一行（挂机队列里的一项任务）整行都绑着 `toggleEnable`，点哪都是「启用/停用」。用户实际想干的是「进去改这个任务的参数」，结果一碰行就把任务停了 —— 这是把两个不同意图塞进同一个命中区的典型错。

### 改法

| 区域 | 尺寸 | 点击行为 |
| --- | --- | --- |
| 行左侧文字区 + 中间的「›」 | 行内剩余全部宽度 | `openBlockParams(block)` → 切到参数页，直接 dump 这个块 |
| 右侧开关容器 | **56 × 48 dp** | `toggleEnable(block, !on)` 启用/停用 |

- `Switch` 仍设 `setClickable(false)`，触摸交给外面那层 56×48 的容器：命中区比 Switch 本体宽，手指点偏一点也还是开关，不会漏到行上变成「进参数页」。
- 行里加了个灰色 `›`，让「点这行能进去」这件事看得出来。
- 分组的提示文案同步改了：「挂机队列 · 已启用 N 项」下面加一行「点一行进它的参数页；只有右侧那个开关才是「停用/启用」」。

### 为什么不是直接弹窗改参数

参数页的「常用参数」要跑一次 proot + python，首屏 15~25 秒。所以 `openBlockParams()` 先把 `paramLoaded` 置真跳过那一步，直接走 `dumpBlock(block)` —— 只读一个块，快得多，渲染出来的就是该块全部可编辑参数；里面的「‹ 返回常用参数」还能退回原列表。

### 已验证 / 未验证

已通过：`javac` / `d8`（0 warning）/ 签名 / `BUILD OK ... (484 KB)`。
**未验证**：真机手感 —— 56×48 的开关区是否够点准、点行进参数页是否顺畅。用户当前没有连接 adb，本轮无法部署。装上去后如果开关还是容易点偏，把 `dp(56)` 加到 64 即可。

## 本轮：按 Apple HIG / Material 过一遍可用性

对照 Apple Human Interface Guidelines 的 Layout 章（`developer.apple.com/design/human-interface-guidelines/layout`）和 Material 的 Navigation drawer 规范逐条检查，下面这些是真问题，改了：

### 1. 所有可点行都没有按压反馈（最影响手感的一条）

原来 `taskRow` / `schedRow` / `paramRow` / 进度入口都只有 `setOnClickListener`，背景全透明 —— 按下时屏幕毫无变化，用户判断不出「我到底点中没有」。iOS 和 Material 的列表行按下都有高亮/水波。

现在统一用 framework 的 `RippleDrawable`（API 21+，不需要 AndroidX）：

| helper | 用途 |
| --- | --- |
| `ripple()` | 矩形水波（30% 白），给卡片内的列表行 |
| `rippleRounded(contentColor, radiusDp)` | 带圆角裁剪的水波，给抽屉条目和淡底按钮（矩形水波会从 10dp 圆角外溢） |
| `pill()` 里 `setForeground(ripple())` | 实心胶囊按钮叠一层前景水波，实色背景才看得出按压 |

### 2. 一行三个并排按钮（违反 HIG）

HIG 明确：一行里带文字的按钮别超过两个。「开始 / 结束 / 恢复屏幕」三个并排各拿三分之一宽度，而「恢复屏幕」是低频动作（只在 ALAS 停在游戏画面、要拿回手机时点一次），却占了同样大的地方，还挨着「结束」容易误点。

现在：
- 状态卡改横向两栏 —— 左边状态 + 详情，右边「恢复屏幕」小字入口（命中区 pad 到 48dp 高）
- 操作区只剩两个按钮，各占一半，高 **48dp**（Material 最小触控目标；原来 44dp 贴着下限）
- 「结束」从实心深灰改成**透明底 + 20% 白描边**：纯黑底上 `#2C2C2E` 实心几乎糊在一起，描边既看得清又不跟绿色主按钮抢注意力

### 3. 三处字太小

| 位置 | 原值 | 现值 |
| --- | --- | --- |
| 分组标题 `groupTitle` | 12sp | **13sp** + 2% 字距（标题是扫读锚点） |
| 说明文字 `hint` | 12sp / 行距 1.0 | **13sp** / 行距 1.25 |
| 运行日志 `tvLog` | 10sp | **11sp** + 行距 1.15（日志是控制台的主体内容） |

### 4. 箭头几乎看不见

`taskRow` 和 `paramRow` 右侧那个 `›` 用的是 `0xFF48484A`，在 `#1C1C1E` 的卡片上基本融进背景。而它是「这行点进去有东西」的唯一提示。统一改成 `C_SUB`（`#8E8E93`）。

### 5. 底部被手势条压住

`buildScrollPage` 的容器底部只留 10dp，列表最后一行贴着手势条/导航栏，很难点。改成 **28dp**；控制台日志区底部 margin 6 → 10dp。

### 6. 「立即生效」不像按钮

原来是纯蓝文字，用户不确定这块能不能点。改成 12% 蓝底 + 圆角 9dp 的小胶囊，带水波。

### 已验证 / 未验证

已通过：`javac` / `d8`（0 warning）/ 签名 / `BUILD OK ... (484 KB)`。
**未验证**：真机手感（水波是否够明显、描边按钮在纯黑底上的观感、`›` 改色后是否够亮）。没有 adb，本轮仍无法部署。

### 还该做、但这轮没做的（列出来，不装作做了）

- **状态指示的 `●` 字符**：现在是给 `tvState` 整体上色 + 文本前缀 `● `，圆点颜色和文字同色。更干净的做法是自绘一个 8dp 圆点 View，但 `tvDot` 被 8 处引用（都靠 `setTextColor` 联动），改动面大，先留着。
- **动态字号（Dynamic Type）**：HIG 要求跟随系统字号缩放，现在全部是写死的 sp，系统调大字号不会跟着变。
- **页面切换动画**：抽屉关闭到页面出现是硬切。
- **长按菜单**（长按任务行复制配置等）：没做。

---

# 真机部署记录：崩溃修复、边缘手势、按钮联动（2026-11-20）

这一节记录的都是**在真机上跑出来、并且踩过坑才定案**的东西，不是设想。

## 1. 装包即崩：三个坑连着踩

第一次把编好的 APK 装到 Redmi K40S（Android 16 / SDK 36）上，启动立刻闪退。`adb logcat -b crash` 一层层剥出来：

1. **`NoClassDefFoundError: androidx/drawerlayout/R$attr`** —— 只把 AAR 里的 `classes.jar` 加进 classpath，AAR 自己的 `res` 没编，库里的 `R` 类根本不存在。
2. 手写一份占位 `R` 之后 → **`NoClassDefFoundError: androidx/core/R$id`**，把 6 个依赖库的知识全要补一遍。
3. 按各库 `R.txt` 生成占位 `R` 之后 → 运行期 **`Resources$NotFoundException: Resource ID #0x7f0f3019`**。根因是 `DrawerLayout` 构造函数里真的去取 `R.dimen.def_drawer_elevation`，占位 id 不是真资源。

**正解**：把 AAR 的 `res` 真正编进 APK。`build.ps1` 现在这样走：

- `[1b]` 用 `aapt2 compile --dir <aar>/res -o build\res_<lib>.zip` 把依赖资源编成 zip；
- `[2/6]` `aapt2 link` 把这些 zip 一起作为输入，并加 `--output-text-symbols build\R.txt`；
- `[2b]` 从 `R.txt` 里读出 `drawerLayoutStyle` / `elevation` / `def_drawer_elevation` / 对应的 styleable，现算生成 `build\gen\androidx\drawerlayout\R.java`。

顺手记一个 PowerShell 的坑：`$pkg -replace '.', '\'` 里 `.` 是正则通配符，会把每个字符都换成反斜杠，6 个库的 `R.java` 于是全写进同一个文件互相覆盖。要用 `$pkg.Replace('.', '\')`（String 字面替换）。

## 2. 「从左往右滑开菜单」：三套方案全失败，第四套才对

这是整轮最难受的一块，失败了三次：

| 方案 | 做法 | 结果 |
| --- | --- | --- |
| ① DrawerLayout 原生手势 | 直接用库自带的边缘拖动 | 真机上一次都没触发 |
| ② 手写 motionevent | 自己发 DOWN/MOVE…/UP 七段 | 没触发 |
| ③ 自建边缘热区 View | 在内容页上叠一条 20dp 全高 View | 把整个界面的点击都吃掉了 |
| ④ 继承 DrawerLayout 重写 dispatchTouchEvent | 见下 | **成功** |

问题根源在两个层面：

- **系统层**：MIUI 手势导航把最靠左约 35px 的右滑当成「返回」直接消费。实测 `input swipe 10 640 → 400 640` 不但没开抽屉，还把 APP 退回了桌面 —— 事件压根没进 APP。已按官方 API 加了 `setSystemGestureExclusionRects`（API 29+，声明左侧 48dp 不参与系统手势），但注入事件在最边缘 10px 处仍被拦，实测 **x≥40 起手才可靠**。
- **布局层**：那个「20dp 边缘热区」放进 DrawerLayout 后其实被铺成了全屏 —— `uiautomator dump` 拿到的 bounds 是 `[0,80][720,1280]`。DrawerLayout 会强制把非抽屉子 View 拉满，所以它盖住了所有控件，点哪都没反应。

**最终实现**（`MainActivity.SwipeDrawer`）：继承 `DrawerLayout`，重写 `dispatchTouchEvent`。页面内所有触摸必经这里，不依赖子 View 命中，也不遮挡任何控件。判定条件是「起手在屏幕左侧 40% 以内 + 横移 > 70dp + 竖直抖动 < 28dp」，竖直那一条是为了不和页面上下滚动打架。

顺带否掉一条死路：**`Activity.dispatchTouchEvent` 在 Android 16 上不在这条触摸链路里**（加了日志，swipe 之后一次都没打印），不能依赖它做全局手势。

## 3. 按钮跟着状态走

原来「开始 / 结束」永远是同一副样子：ALAS 明明在跑，「开始」还是亮绿实心，点一下只是把启动脚本重跑一遍；没在跑时「结束」看着能用，点了毫无意义。

现在（`applyRunState`）：

- 运行中：**开始** 压成深灰、禁用；**结束** 变红实心、可点。
- 已停止：**开始** 亮绿、可点；**结束** 变描边灰、禁用。

真机截图为证：`挂机运行中 · 容器进程 2 个` 的同一屏上，「开始」是灰的、「结束」是红的。

## 4. 参数页加搜索

一个任务块动辄 54 项（主线 1 就是），靠肉眼滚到「打哪一关」很费劲。现在块内列表顶部有搜索框，输入即时过滤，中文名和英文路径都能搜（每行挂了「中文名 + 短路径 + 完整路径」作为匹配文本，分隔线跟着所属行一起隐藏）。实测输入 `campaign` 后只剩 `打哪一关 / 活动名 / 关卡难度 / 连续作战 …` 这些路径里带 Campaign 的行。

## 5. 热切换启动器的致命 import 错误

`deploy/Termux/alas_hot.py` 里写的 `from module.config.utils import deep_get` 是错的 —— `deep_get` 在 **`module.config.deep`**。后果不是「热切换不生效」而是**整个调度器起不来**：

```
Traceback (most recent call last):
  File "/root/AzurLaneAutoScript/deploy/Termux/alas_hot.py", line 54, in <module>
    from module.config.utils import deep_get
ImportError: cannot import name 'deep_get' from 'module.config.utils'
```

`run-alas.sh daemon` 只会报一句「调度器没进循环」，看不出原因。改成 `from module.config.deep import deep_get` 后，真机 `run-alas.sh status` 回到「调度器：已进入调度循环」。

## 6. 真机验证过的清单

- 抽屉点开 / 返回键关闭 / 遮罩压暗：[已验证]
- 左滑开抽屉（起手 x≥40，横移 70dp）：[已验证，`input swipe 200 700 → 520 700` 成功]
- 边缘 10px 起手左滑：[已知不可用，系统手势抢占，不再追]
- 任务行命中区拆分（点文字进参数页、只有右侧开关才改开关）：[已验证]
- 参数页搜索过滤：[已验证]
- 运行 / 停止两态的按钮联动：[已验证]
- 调度器 `daemon` 起来后 APP 自动变「挂机运行中」：[已验证]

## 7. 仍然没做的

- 动态字号（跟随系统字号缩放）—— 全部写死 sp。
- 页面切换动画 —— 抽屉关掉是硬切。
- 长按菜单、悬浮窗实时日志、开机自启。
- 「改关卡 → 下一轮自动换图且不重启游戏」的端到端验证：热切换脚本现在能加载了，但还没在跑图过程中改关卡实测一次。
- 状态卡在调度器刚停的瞬间仍可能短暂显示「挂机运行中」：`countProcs()` 跨 namespace 判活有延迟，靠日志 mtime 兜底。

---

# 重启后 adb 连不上：启动失败 + 恢复屏幕没反应的真因（2026-11-21）

## 现象

- 点「开始」→ 脚本第一步就报 `!!! adb 连不上 127.0.0.1:5555`，整个启动失败。
- 点「恢复屏幕」→ `!!! adb 连不上 127.0.0.1:5555，无法操作分辨率`，按钮像没反应。

两个看起来无关的故障，其实是同一个原因。

## 根因

容器里的 ALAS 和 `display.sh` 都不走 `su` 改分辨率 / 截屏，而是走**容器内的 adb 连本机 adbd**（`adb connect 127.0.0.1:5555`）—— 因为 `su -c "wm size ..."` 会被 Magisk 的 `u:r:magisk:s0` 域挡住，报 `Failed transaction`；只有设备 shell uid 才有权限。

而 `service.adb.tcp.port` 这个属性**手机每次重启都会归零**。归零之后 adbd 只监听 USB，容器就 connect 不上，于是启动和恢复屏幕一起废掉。

## 两层自愈

**① 开机自动（根治）**：`deploy/Termux/adbd-tcp-boot.sh` → 装到 `/data/adb/service.d/adbd-tcp.sh`。
Magisk 在 late_start service 阶段以 root 执行它：等 `sys.boot_completed=1` → `setprop service.adb.tcp.port 5555` → `stop adbd; start adbd`。不用开 App、不用插电脑。

真机重启验证（2026-11-21）：

```
$ adb shell "getprop sys.boot_completed; getprop service.adb.tcp.port"
1
5555
$ adb shell "logcat -d -s ALAS_ADBD"
I ALAS_ADBD: adbd restarted with tcp port 5555
$ 容器内 c_display.sh status
Physical size: 1080x2400   Override size: 720x1280 …
```

副作用只有一条：adbd 进程重启会让 USB 调试断两三秒后自己恢复。

**② 运行期兜底**：`deploy/Termux/ensure_adbd.sh`。
它用 bash 的 `/dev/tcp` 探一下 5555：通则直接退出；不通就 `setprop` + 重启 adbd，最多等 15 秒。三个地方调用它：

- `start-game.sh` 的第 0 步（Termux 手动跑也生效）；
- `display.sh` 进容器之前（所以「恢复屏幕」不再依赖端口状态）；
- APP 的「开始」与「恢复屏幕」动作前置（`MainActivity` 的 `ENSURE_ADBD`），命令是 `bash ensure_adbd.sh || true; <原命令>`，失败也不阻断。

## 这轮没验证到的

收尾时手机处于 Dozing（息屏 + 锁屏，`mCurrentFocus=NotificationShade`），注入的点击全落在锁屏上，所以「APP 内点开始 → 容器起来」这条链路**没有留下干净的截图证据**。需要解锁后手动点一次「开始」才算落实 —— 端口那一半已经重启验证过，剩下的只是 UI 触发。

---

# 「改参数没写入」其实全链路是通的，错在界面读错了文件（2026-11-21）

## 现象

改完参数点「立即生效」，固定弹「没写进标记文件，日志已贴到控制台」；用户因此认为参数根本没写进配置。

## 真因

`c_hot.sh` 里声明了 `OUT=/data/data/com.termux/files/home/alas-config.out`，但**从头到尾没用过它** —— 脚本只 `printf` 到 stdout。而 APP 那边的写法是：

```java
su(run, 40L);                      // 脚本输出丢掉了
final String out = su("cat " + CFG_OUT, 15L);   // 去读那个没人写的文件
if (t.contains("\"ok\": true")) …   // 读到的永远是上一次 c_config.sh 的旧内容
```

所以「成功与否」取决于上一次 `c_config.sh` 留下的内容，跟这次点没点根本无关 —— 界面必然误报。

## 修法（两边都改，互为兜底）

1. `c_hot.sh`：结果同时 `tee` 进 `$OUT` 和 stdout；并且写入后**回读确认** `[ -f "$FLAG" ]`，因为容器和宿主是 bind 挂载，写成功但宿主看不到的情况真实存在。失败时把具体原因（找不到目录 / 不可写 / 回读不到）写进 JSON 的 `msg`。
2. `MainActivity`：用 `su()` 的**返回值**判断，文件只作兜底（`stdout 优先，文件次之`）。

## 真机验证（2026-11-21 09:22–09:24）

```
$ _exec.sh c_hot.sh
{"ok": true, "msg": "已请求立即生效：下一轮地图结束后自动换图，不重启游戏"}
$ ls -l config/hot_reload
-rw-r--r-- 1 root root 11 2026-11-21 09:24
```

参数写链路本身一直是好的（用 16-1 / 16-4 来回试）：

```
$ c_config.sh set Main.Campaign.Name 16-1
{"ok": true, "path": "Main.Campaign.Name", "old": "16-4", "new": "16-1"}
$ c_config.sh get Main.Campaign.Name
{"value": "16-1"}
```

界面上点「改关卡」→ 选 16-1 → toast 直接是「已设为 16-1 · 下一轮地图跑完自动换图」（改关卡本身就会写标记，不必再点「立即生效」），磁盘回读 `16-1`，再点回 16-4 也一致。

## 仍待验证

标记文件写得进去是确定的；「alas_hot.py 在下一轮地图结束时真的消费它并换图」还差一次实际跑图（需要游戏在战斗、调度器在循环）。



---

# 停刷条件（100% 通关 / 三星）——补回 PC 端那个开关

用户反馈：PC 版 ALAS 有「100%通关 / 100%通关+三星 / 安全海域」的停止条件，手机版找不到。

## 根因：移植层把枚举写死成老版本的值

`deploy/Termux/alas_config.py` 里两处硬编码（`COMMON` 常用参数表 + `OPTIONS` 候选字典）写的是：

```python
["non_stop", "stop_when_finished", "stop_when_all_cleared"]
```

`stop_when_finished` / `stop_when_all_cleared` 是 ALAS 老版本的写法。0.4.13 的
`module/config/argument/argument.yaml` 第 164–166 行真实枚举是：

```yaml
MapAchievement:
  value: non_stop
  option: [ non_stop, 100_percent_clear, map_3_stars, threat_safe, threat_safe_without_3_stars ]
```

中文名见 `module/config/i18n/zh-CN.json` 第 925–933 行，name 是「达成关卡成就后停止」。
传给 ALAS 非法值时它只当没写，所以界面上等于少了这个功能。

已把两处改成真实 5 个值。

## APP 侧三处改动

1. `ENUM_ZH` 枚举中文映射表（约 38 条）+ `enumZh()`：参数行、选择弹窗都显示中文，
   写回配置的仍是英文原值。
2. 首页「主线设置」卡加第三行「停刷条件」，和「打哪一关 / 打多少次」并列 ——
   想刷三星不用再钻参数页。
3. `maininfo` 增加 `stop_cond` / `stage_increase` 两个字段，一次往返拿全。

弹窗里每项带一句人话说明（"这张图 100% 通关后停"），底部提示：
想让 ALAS 达成就自动往下一关开荒，还要去参数页打开「自动升图」。

## 真机验证（2026-11-21 09:39–09:42）

```
$ c_config.sh maininfo
{"chapter": "16-4", "run_count": 0, "stop_cond": "non_stop", "stage_increase": false, ...}
$ c_config.sh dump Main          # MapAchievement 的 options
"options": ["non_stop", "100_percent_clear", "map_3_stars", "threat_safe", "threat_safe_without_3_stars"]
`$ c_config.sh set Main.StopCondition.MapAchievement threat_safe   # 界面误触后
{"ok": true, ..., "old": "threat_safe", "new": "non_stop"}
```

界面：主页三行显示 `16-4 / 一直打（不限）/ 否`，点「改条件 ›」弹出 5 项竖排列表
（选中项带 ✓），点选后 toast「停刷条件已设为「…」· 下一轮任务开始生效」，参数页同步。

踩坑记录：注入点击验证弹窗时**只点一次**。连续重试 3 次 DOWN/UP，第 2、3 下
会落在已弹出的列表项上，直接把配置误改成「通关+三星+安全海域」。

---

# 「点结束还是显示运行中」——两个独立原因，都修了

用户反馈点「结束」后界面仍是「挂机运行中 / 任务运行中」。实测发现是两件事叠加。

## 一、Termux 自带的 pkill 匹配不到容器里的 python（真凶）

真机证据（停止后进程还在，PID 从启动起就没变过）：

```
$ ps -ef | grep python
root 11134 11129 66 10:42:05 python3.10 /root/AzurLaneAutoScript/deploy/Termux/alas_hot.py
$ cat alas-stop.out
==> 停止 ALAS
    剩余 python3.10 进程：0          # 脚本说 0，实际还有 1 个
```

`run-alas.sh` 里 `pkill -f "alas_hot.py"` 走的是 PATH 上的 Termux procps 版
（脚本第 25 行把 `$PREFIX/bin` 放最前）。它在 Magisk 环境下看不到挂在 proot
名下的子进程，于是**一个都没匹配到、静默返回 1**，整段停止逻辑等于空跑；
后面的 `echo` 全是 `pkill && echo`，所以一行都没打印出来 —— 这就是为什么
以前每次点结束都「脚本跑完了但没反应」。

`/system/bin/pgrep -x python3.10` 能正常列出 11134，说明系统版是可见的。

修法（`deploy/Termux/run-alas.sh`）：

```bash
PGREP=/system/bin/pgrep
PKILL=/system/bin/pkill
[ -x "$PGREP" ] || PGREP=pgrep
[ -x "$PKILL" ] || PKILL=pkill
```

所有 pkill/pgrep 改成绝对路径调用；`stop` 末尾再加一道 ps 复核，若还剩
python3.10 就逐个 `kill -9`，不再只信 pkill 的返回值。

修完立即生效，第一次就打印出了以前从没出现过的行：

```
==> 停止 ALAS
    已停 alas_hot.py 调度器
    剩余 python3.10 进程：0
$ ps -ef | grep python3.10      # 空
```

## 二、alas-task.running 孤儿标志

`alas_task.sh` 靠 `trap cleanup EXIT/INT/TERM` 删标志，但脚本被 `-9` 强杀时
trap 跑不到，留下一个 0 字节文件；APP 只判断「文件在不在」，于是进程早没了、
界面还挂「任务运行中」。

两处加固：

- `alas_task.sh` 开跑前先 `rm -f` 再写 PID，孤儿不会累积；
- `taskRunningNow()` 交叉校验：标志在 + 真有 `python3.10` 才算在跑，
  没 python 就顺手把标志删掉；
- `run-alas.sh stop` 收尾时也清一次标志。

## 端到端验收（2026-11-21 10:50）

启动 → APP 显示「挂机运行中 · 容器进程 2 个」→ 点结束 → 12 秒后
「已停止 · 手机处于原生状态」，开始按钮变绿、结束变灰，`ps` 里 python3.10 为空。

附带确认：挂机中 ALAS 每轮结束会把内存里的配置写回 `config/alas.json`，
界面改成「100%通关+三星」这类值时，若只等下一轮可能被它自己覆盖。
所以 toast 已改成「立即生效请点顶部按钮」（重启调度器才稳）。

# 定时运行（`Scheduler.NextRun`）——校验、清空即跑、写完即时刷新

原先 APP 里根本没有定时 UI：任务页只显示「间隔 X 分 · 下次 HH:MM」，
想改时间得去参数页编辑 `Scheduler.NextRun` —— 裸文本框、无校验、
清空会被 `setParam` 以「值不能为空」直接拒绝。落在配置里的脏值
（`2026-11-22 9:30:00`，小时不补零）还会让 ALAS 崩：

```
TypeError: '>' not supported between instances of 'str' and 'datetime.datetime'
  alas.py:510 get_next_task -> if task.next_run > datetime.now()
```

根因是 `Function.next_run = deep_get(...)` 不做类型转换，而
`module/config/utils.py: parse_value()` 用 `datetime.fromisoformat()`，
只认两位小时。三层都补了补丁：

## 一、APP：时间对话框（`editTimeParam`）

- 边输边校验：绿字「将设为 2026-11-22 09:30:00」、红字「格式不对…」、
  留空时灰字「留空 = 立刻运行一次」；
- 认的写法比官方宽：`20:30`、`20:30:00`、`11-22 20:30`、
  `2026-11-22 20:30[:00]`，容忍全角冒号、斜杠、多空格，`9:30` 自动补零；
- 格式不对时点「保存」不关框，只给 toast，不让脏值进去；
- 「立即运行」按钮：传空值给 `setParam` → 写成当前时间，ALAS 判断
  「时间已到」立刻接手，toast 分两种（调度器在跑 / 没在跑，让用户知道该点「开始」）；
- 常驻提示「前提：这个任务要在「任务」页启用，到点才会真的跑」——
  很多人只改时间、开关没开，然后觉得定时没用；
- 输入法软键盘的「完成」= 保存（`IME_ACTION_DONE`），弹框时不自动弹键盘
  （`SOFT_INPUT_STATE_HIDDEN`），因为自动弹出的键盘会把对话框底部
  「保存 / 立即运行 / 取消」整条盖住，真机实测点不到。

## 二、`alas_config.py`：写盘前强制规范化

`set_path()` 里 `last == "NextRun"` 分支改走 `normalize_time(raw)`：

- 空值 → 当前时间（= 立刻运行）；
- `9:30` → 补零成 `09:30:00`；
- 越界/乱写 → `die("时间格式不对（要 20:30 或 2026-11-22 20:30:00）")`。

`set` 分支同时放宽到 `len(sys.argv) >= 3`：只有 `NextRun` 允许不给值，
别的字段留空仍然报用法错误。

## 三、`alas_hot.py`：`Function.__init__` 兜底

patch 掉 `Function.__init__`，把 `next_run` 统一转成 `datetime`：
能解析的照常，解析不了的（历史脏值）按「立刻运行」兜底并打一行 warning。
这样即使有人绕过 APP 直接改 `alas.json`，调度器也不会因为比较 str 和
datetime 而整个挂掉。

## 四、写完的值必须重读，否则看着像「没写进去」

`setParam` 成功后原来一律 `loadParams()`，会把人从「按任务块浏览」页
踢回常用参数页，而且行上的值可能还挂旧的 —— 用户看到的就是
「改了没生效 / 显示没写入」。改成记住当前块（`curBlock`），
写成功后静默 `dumpBlock(curBlock, true)` 重读服务器上的真实值再画。
顺带把两个参数页渲染结尾都加了 `paramScroll.scrollTo(0, 0)`，
换页后不会沿用上一页滚到一半的位置。

## 真机验收（2026-11-22 10:33–10:43）

- `set Main.Scheduler.NextRun 9:30` → `new = "2026-11-22 09:30:00"`（自动补零）；
- `set ... abc` → 拒绝，报「时间格式不对（要 20:30 或 2026-11-22 20:30:00）」；
- 只给 path 不给值 → `new` = 当前时间（立刻运行）；
- 手动往 `alas.json` 写脏值 `2026-11-22 9:30:00` 后启动调度器：
  日志回显 `[Task] Main (Enable, 2026-11-22 09:30:00)`，
  **不再抛 TypeError**，任务正常入队执行；
- APP 对话框：`9:30` → 绿字「将设为 2026-11-22 09:30:00」，
  `abc` → 红字，清空 → 灰字「留空 = 立刻运行一次」，
  键盘回车即保存，保存后行上值立刻变成 `2026-11-22 09:30:00`。

## 构建环境踩坑（2026-11-22）

`%TEMP%\alas-apk` 里的编译链会被系统清理，一清就报
`missing tool: ...\bt\android-14\aapt2.exe`。重建办法：

1. `%TEMP%\alas-apk\build-tools.zip` → 解到 `bt\`（内含 `android-14\`）；
2. `platform.zip` → 解到 `plat\`（得到 `android-34\android.jar`）；
3. `aar\*.aar` 里的 `res/` 解到 `aar-res\<name>\`（`[1b]` 要编依赖 res，
   DrawerLayout 少一个 `dimen.def_drawer_elevation` 就 `Resources$NotFoundException`）；
4. `aar\*.aar` 里的 `classes.jar` 抽到 `libs\<name>.jar`（缺了直接 javac 报找不到类）。

本机另有完整 SDK 在 `C:\Users\LIANG\Android\Sdk`（build-tools 30.0.3 +
platforms android-34），也可把 `build.ps1` 的 `$bt` / `$ajar` 指过去。

# 默认该跑的任务（宿舍 / 每日免费）

PC 端「默认打开」的任务写在 `module/config/argument/default.yaml` 里，
一共 8 个：`Commission`、`Tactical`、`Research`、`Dorm`、`Meowfficer`、
`Guild`、`Reward`、`Freebies`。把它和手机 `config/alas.json` 一对比：

- `Dorm.Scheduler.Enable` = **false** —— 手机端这一项掉了，所以后宅从来不收取、
  也从来不投喂（`Dorm.Collect` / `Dorm.Feed` 参数本身是 true，但任务没进队列）。
  用 `c_config.sh enable Dorm 1` 恢复，`enable` 会把 `NextRun` 一起设成当前时间，
  启用即刻跑一次。
- `Freebies.Scheduler.Enable` = true，但 `NextRun` 是「明天 00:00」——
  这是 ALAS 的 ServerUpdate 语义：任务的 `ServerUpdate` 是 00:00 时，
  过了当天 00:00 还没跑就被顺手推到次日，等于**当天跳过**。机器只在白天开、
  或当天启动得晚，每周补给包/军功邮件这类就领不到。临时补一次的办法是
  `c_config.sh set Freebies.Scheduler.NextRun`（不给值 = 立刻）。
- 另外注意队列按 `NextRun` 升序执行：如果 `Main` 的 `NextRun` 是已经过去的
  时刻，一启动它会先抢着打图，后宅/免费要排在后面。想让福利任务先跑，
  就把 `Main.Scheduler.NextRun` 往后挪一段。

真机验收（2026-11-22 11:00–11:03，日志 `alas-daemon.log`）：

```
Pending tasks: ['Research', 'Dorm', 'Freebies']
Click ( 279, 591) @ DORM_FEED_ENTER
═════════════════════════════════ DORM COLLECT ═════════════════════════════════
Click (1226, 514) @ DORM_QUICK_COLLECT
[Task] Freebies (Enable, 2026-11-22 11:00:12)
<<< SUPPLY PACK BUY >>>          ← 每周补给包这次真的买了
```

# 一键重置所有任务时间（换号用）

多个号轮着挂时，进度各不一样：这个号后宅没喂、周补给没领、主线停在别的图。
但 `config/alas.json` 里记的是**上一个号**的时间戳，直接启动会一路
「Wait until …」等到明天，看着像什么都没跑。

任务页顶部因此加了一张「换号 / 进度对不上」卡，一个按钮：

- 弹框二次确认（这个动作会让所有任务各跑一遍，不能手滑）；
- 确认后调 `c_config.sh reset`。

## 服务端 `alas_config.py: reset`

- 遍历所有带 `Scheduler.NextRun` 的块，统一写成当前时间；
  跳过 `Command == Alas` 的块（那个 Scheduler 是调度器自己，重置没意义）；
- 顺手写 `config/hot_reload` 标记，返回值里带 `marked` 供排查 bind 挂载；
- 返回 `{"ok":true,"now":...,"count":N,"marked":true,"blocks":[...]}`。

## 为什么改完时间就会重排

ALAS 的重读有两处入口，都靠 `alas.json` 的 **mtime**（`module/config/watcher.py:
ConfigWatcher.should_reload()`，不是靠 `hot_reload` 文件）：

- 任务成功后 `alas.py:613 del_cached_property(self,'config')` → 下一轮
  `get_next_task()` 重新构造 config，读到新 `next_run`；
- 正在「等某个未来任务」时，`wait_until()` 每 5 秒检查一次 mtime，
  一变就返回 False，上层 `del_cached_property(self,'config')` 重排。

所以只改时间就够了；写 `hot_reload` 是为了让**正在跑的那个任务**在下一个
安全点收尾（alas_hot.py 打过补丁），不用重启容器、也不用重启游戏。

## APP 侧两处注意

- 「有没有东西在跑」不能用 `taskRunningNow()`：它锚在 `alas_task.sh` 的
  `alas-task.running` 标志上，只有「单次执行」才写；常驻调度器
  （`run-alas.sh daemon`）根本没这个标志，会把「在跑」误判成「没跑」，
  提示语说反。改用 `countProcs()`（`pgrep -x proot` + `pgrep -x python3.10`），
  并且在 `pool` 线程里算好、再 `ui.post`，避免 su 卡主线程。
- 重置后 `loadTasks()` 重读 `scheduler`，行上的「下次」全部变成刚重置的时刻，
  用户能直接看见生效。`paramLoaded` 也置 false，参数页里那些时间不再是旧值。

真机验收（2026-11-22 12:42 / 12:44）：

```
$ c_config.sh reset
{"ok": true, "now": "2026-11-22 12:42:36", "count": 58, "marked": true, "blocks": [...]}

alas-daemon.log（15 秒内）：
Config "alas" changed at 2026-11-22 12:42:36
Pending tasks: ['Restart', 'Commission', ...]
[Task] Restart (Enable, 2026-11-22 12:42:36)
Scheduler: Start task `Restart`
```

APP 任务页：点按钮 → 二次确认 → toast「已重置 58 个任务 · 调度器已按新时间重排」，
队列里每一行的「下次」同步刷成 12:44。

