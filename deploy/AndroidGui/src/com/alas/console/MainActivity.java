package com.alas.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.drawerlayout.widget.DrawerLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ALAS 手机控制台
 *
 * 界面按 iOS 的分组列表思路重做：纯黑底、深灰卡片、大圆角、细分隔线、
 * 分段控件切换页面，操作项做成列表行而不是一排彩块。
 *
 * 两页：
 *   1) 控制台：状态、开始/结束/恢复、任务列表、实时日志
 *   2) 管理界面：内嵌 ALAS 官方 WebUI（127.0.0.1:22267）
 *
 * 任务执行走 alas_cli.py，不经过网页界面 —— 那条前端在当前环境下点击不可靠。
 */
public class MainActivity extends Activity {

    // ---- Termux 侧路径 ----
    private static final String THOME = "/data/data/com.termux/files/home";
    private static final String ALAS_DIR = THOME + "/alas/AzurLaneAutoScript/deploy/Termux";
    private static final String RUN = ALAS_DIR + "/run-alas.sh";
    private static final String DISPLAY = ALAS_DIR + "/display.sh";
    private static final String START_GAME = ALAS_DIR + "/start-game.sh";
    /**
     * 确保 adbd 的 TCP 端口可用，见 deploy/Termux/ensure_adbd.sh。
     *
     * `service.adb.tcp.port` 手机一重启就归零，容器里的 ALAS 就连不上本机，
     * 表现是「启动失败」+「恢复屏幕按了没反应」两个看着无关的故障。
     * 「开始」和「恢复屏幕」之前都先跑一遍它，把端口补回来。
     */
    private static final String ENSURE_ADBD = ALAS_DIR + "/ensure_adbd.sh";
    private static final String LOG = THOME + "/alas-daemon.log";
    private static final String TASK_LOG = THOME + "/alas-task.log";
    private static final String TASK_FLAG = THOME + "/alas-task.running";
    private static final String CONTAINER = "ubuntu-base-22.04-base-arm64";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    /** 读 Termux 私有目录必须切到 Termux 的 uid，光靠 su 会被 SELinux 拦 */
    private static final String RUN_AS = "run-as com.termux ";

    /** 配置工具的落盘位置，见 deploy/Termux/c_config.sh */
    private static final String CFG_OUT = THOME + "/alas-config.out";
    private static final String CFG_INNER = "/root/AzurLaneAutoScript/deploy/Termux/c_config.sh";
    /**
     * 「立即生效」的触发脚本，见 deploy/Termux/c_hot.sh。
     * 它只写一个标记文件，由 alas_hot.py 在下一轮地图结束时消费 ——
     * 不杀 proot、不重启游戏。以前这里是 run-alas.sh restart，会把
     * 整个进程和游戏一起重开，挂机被打断，用户明确不要这种。
     */
    private static final String HOT_INNER = "/root/AzurLaneAutoScript/deploy/Termux/c_hot.sh";
    private static final String UPD_OUT = THOME + "/alas-update.out";
    private static final String UPD_INNER = "/root/AzurLaneAutoScript/deploy/Termux/c_update.sh";

    private static final String WEB_URL = "http://127.0.0.1:22267/?app=alas";

    private static final int TAB_CONSOLE = 0;
    private static final int TAB_TASK = 1;
    private static final int TAB_PARAM = 2;
    private static final int TAB_UPDATE = 3;
    private static final int TAB_WEB = 4;

    // ---- iOS 深色配色 ----
    private static final int C_BG = 0xFF000000;      // 纯黑底
    private static final int C_CARD = 0xFF1C1C1E;    // 分组卡片
    private static final int C_CARD2 = 0xFF2C2C2E;   // 卡片内次级元素
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xFF8E8E93;     // iOS 灰字
    private static final int C_SEP = 0xFF38383A;     // 分隔线
    private static final int C_BLUE = 0xFF0A84FF;
    private static final int C_GREEN = 0xFF30D158;
    private static final int C_RED = 0xFFFF453A;
    private static final int C_ORANGE = 0xFFFF9F0A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * 必须用多线程池。
     * 用单线程池时，配置读写（proot 启动要十几秒）会把每 4 秒的状态轮询全堵在队列里，
     * 积压的 ui.post 一多，主线程消息队列被塞满，表现就是整个界面点不动。
     */
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** 轮询防重入：上一次还没跑完就别再排一个进去 */
    private volatile boolean refreshing = false;

    private TextView tvDot;
    private TextView tvState;
    private TextView tvDetail;
    /** 控制台那对「开始 / 结束」，要随运行状态变样式和可用性 */
    private TextView btnStart;
    private TextView btnStop;
    private TextView tvLog;
    private ScrollView logScroll;
    /**
     * 侧滑抽屉。
     *
     * 用官方的 androidx.drawerlayout：边缘手势、跟手拖动、遮罩淡入、松手后按
     * 速度/位移判定开合、返回键关闭，这些它都处理好了，比自己拼 GestureDetector
     * 靠谱。抽屉的外观完全自己画，保持 iOS 那种简洁分组的感觉。
     */
    private SwipeDrawer drawer;
    private LinearLayout drawerPanel;
    /** 顶栏中间显示的当前页名字 */
    private TextView tvTopTitle;

    /** 抽屉里的五个菜单项，索引就是 TAB_CONSOLE .. TAB_WEB */
    private static final String[] TAB_NAMES = {"控制台", "任务", "参数", "更新", "网页"};
    private final LinearLayout[] drawerRow = new LinearLayout[5];
    private final View[] drawerBar = new View[5];
    private final TextView[] drawerLabel = new TextView[5];

    private View consoleRoot;
    private ScrollView taskScroll;
    private LinearLayout taskListBox;
    private ScrollView paramScroll;
    private LinearLayout paramListBox;
    private ScrollView updateScroll;
    private LinearLayout updateListBox;
    private WebView webView;
    private boolean webReady = false;
    private boolean busy = false;
    private boolean lastRunning = false;
    private boolean taskRunning = false;
    private boolean autoSwitched = false;

    /** 当前页签，配置读写要知道现在该刷哪一页 */
    private int curTab = TAB_CONSOLE;
    /** 任务页 / 参数页的数据是否已经拉过。proot 启动慢，避免来回切页就重跑 */
    private boolean taskLoaded = false;
    private boolean paramLoaded = false;
    // 当前是不是停在某个任务块的浏览页（null = 停在常用参数）。
    // 改完一个值要重画界面时靠它决定画哪一页。
    private String curBlock = null;
    private boolean updateLoaded = false;
    /** 最近一次 su() 的退出码，-1 表示没跑成或还没跑；worker 线程写、UI 线程读，所以 volatile */
    private volatile int lastExit = -1;
    /** 启动自动检查发现新版后置 true，「更新」页签保持橙色提示 */
    private boolean hasPendingUpdate = false;
    /** 上次自动检查拿到的结果，进更新页时可以直接显示，省一次容器往返 */
    private JSONObject pendingUpdate = null;

    /**
     * 「主线」两项高频设置（打哪一关 / 打多少次）的本地缓存。
     *
     * 每次读配置都要起一次 proot，往返 5~25 秒，所以不能为了在首页显示两个数字
     * 就去查一遍。这里启动后查一次（maininfo 一次拿全），之后改动都本地更新，
     * 打开 APP 就是即时的。
     */
    private String curChapter = "";
    /** 周回次数：0 = 一直打；>0 = 打这么次就停。对应 StopCondition.RunCount */
    private int curRunCount = 0;
    /**
     * 停刷条件：对应 StopCondition.MapAchievement。
     * PC 端那张下拉里的「100%通关 / 100%通关+三星 / 安全海域」就是这一项，
     * 手机版之前没暴露出来，等于丢了一个开关。
     */
    private String curStopCond = "";
    private boolean mainLoaded = false;
    /** 首页和参数页各有一份，改完要同步更新 */
    private TextView tvChapterHome = null;
    private TextView tvChapterParam = null;
    private TextView tvRunHome = null;
    private TextView tvRunParam = null;
    private TextView tvStopHome = null;
    private TextView tvStopParam = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refresh();
        ui.postDelayed(poller, 3500);
        // 启动后读一次主线速览（关卡 + 次数），首页那张卡才有数字显示。
        // 放到 6 秒之后：前面 refresh 已经在占容器，同时挤两个 proot 会更慢。
        ui.postDelayed(new Runnable() {
            public void run() { loadMainInfo(); }
        }, 6000);
        // 原版在 PC 上每次启动都会自动检查一次更新，这里同样在启动后静默查一次。
        // 放到 12 秒之后：前两屏要读配置挂任务，容器是串行处理的，同时挤三个 proot 会卡。
        ui.postDelayed(new Runnable() {
            public void run() { autoCheckUpdate(); }
        }, 12000);
    }

    // ============================================== 启动自动检查更新

    /**
     * 原版行为对照：module/webui/updater.py 在启动时按 CheckUpdateInterval（默认 5 天）
     * 判断要不要检查一次；这里把间隔收紧到 24 小时，并且只在有新版时才打扰用户。
     */
    private void autoCheckUpdate() {
        android.content.SharedPreferences sp =
                getSharedPreferences("alas_prefs", MODE_PRIVATE);
        long last = sp.getLong("last_auto_check", 0L);
        long gap = System.currentTimeMillis() - last;
        if (last != 0L && gap < 24L * 60L * 60L * 1000L) {
            return;
        }
        pool.execute(new Runnable() {
            public void run() {
                final String out = upd("check");
                ui.post(new Runnable() {
                    public void run() {
                        getSharedPreferences("alas_prefs", MODE_PRIVATE).edit()
                                .putLong("last_auto_check", System.currentTimeMillis())
                                .apply();
                        onAutoCheckResult(out);
                    }
                });
            }
        });
    }

    /** 自动检查的结果只做轻提示：有新版才亮出来，没新版就当什么都没发生 */
    private void onAutoCheckResult(String json) {
        if (json == null || !json.trim().startsWith("{")) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json.trim());
            if (!o.optBoolean("ok", false) || o.optBoolean("first", false)) {
                return;
            }
            if (!o.optBoolean("has_update", false)) {
                return;
            }
            JSONObject remote = o.optJSONObject("remote");
            String sha = remote == null ? "" : remote.optString("sha", "");
            String shortSha = sha.length() > 8 ? sha.substring(0, 8) : sha;

            // 抽屉里的「更新」变橙，用户一眼能看到「有新版」
            hasPendingUpdate = true;
            if (drawerLabel[TAB_UPDATE] != null) {
                drawerLabel[TAB_UPDATE].setTextColor(C_ORANGE);
            }
            pendingUpdate = o;
            toast("发现新版 ALAS（" + shortSha + "），点「更新」查看");
        } catch (Exception ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Exception ignore) {
            }
        }
        super.onDestroy();
    }

    // ================================================================= UI

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);

        // ---- 顶栏：汉堡 + ALAS + 当前页名 ----
        // 去掉原来那排五段的分段控件：那是「把五个页面挤在一行」的做法，
        // 段落越长字越小，手指也难点准。改成左侧汉堡唤出抽屉，一页一屏。
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(10), dp(20), dp(6));

        View burger = new Burger(this);
        burger.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                drawer.openDrawer(drawerPanel);
            }
        });
        top.addView(burger, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView title = new TextView(this);
        title.setText("ALAS");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        top.addView(title);

        tvTopTitle = new TextView(this);
        tvTopTitle.setText(TAB_NAMES[curTab]);
        tvTopTitle.setTextColor(C_SUB);
        tvTopTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTopTitle.setGravity(Gravity.END);
        top.addView(tvTopTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        page.addView(top);

        // 页面容器（原来大标题那 20dp 内边距挪到这里，页面内部布局不受影响）
        FrameLayout body = new FrameLayout(this);
        body.setPadding(dp(20), dp(2), dp(20), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        page.addView(body, blp);

        consoleRoot = buildConsole();
        body.addView(consoleRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        taskScroll = buildScrollPage();
        taskListBox = (LinearLayout) taskScroll.getChildAt(0);
        taskScroll.setVisibility(View.GONE);
        body.addView(taskScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        paramScroll = buildScrollPage();
        paramListBox = (LinearLayout) paramScroll.getChildAt(0);
        paramScroll.setVisibility(View.GONE);
        body.addView(paramScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateScroll = buildScrollPage();
        updateListBox = (LinearLayout) updateScroll.getChildAt(0);
        updateScroll.setVisibility(View.GONE);
        body.addView(updateScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = buildWebView();
        webView.setVisibility(View.GONE);
        body.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 抽屉 ----
        // 宽度 78% 屏宽、最大 300dp：留一条右边让用户看得见下面的页面还在，
        // 也保证平板或大屏上不至于宽得离谱。这是 Material 侧栏和 iOS 弹出层的通行做法。
        drawerPanel = buildDrawerPanel();
        DrawerLayout.LayoutParams dlp = new DrawerLayout.LayoutParams(
                drawerPanelWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;

        drawer = new SwipeDrawer(this);
        // 遮罩 60% 黑：Material 规范写的是 32%，但那是浅色主题下的值。
        // 这里是纯黑底 + 深灰卡片，32% 的遮罩几乎看不出层次，60% 也和
        // DrawerLayout.setScrimColor 的官方默认一致。
        drawer.setScrimColor(0x99000000);
        // 10dp 是 Material 给 DrawerLayout 的默认海拔（NavigationView 是 16dp）
        drawer.setDrawerElevation(dp(10));
        drawer.addView(page);
        drawer.addView(drawerPanel, dlp);
        drawer.panel = drawerPanel;

        // ---- 关于「从左往右滑开菜单」----
        //
        // 这里曾经叠过一个 20dp 宽的自建边缘热区，结果踩了两个坑：
        // ① DrawerLayout 会把非抽屉子 View 强行铺满全屏（实测 uiautomator 拿到的
        //    bounds 是 [0,80][720,1280]），所以那个「20dp 热区」实际上盖住了整个
        //    界面，把所有点击都吃掉了；
        // ② 就算铺好了，MIUI 手势导航还会把最靠左约 35px 的右滑当成系统「返回」
        //    直接消费掉，事件压根到不了 APP。
        // 现在改成两层：手势判定统一放在 Activity.dispatchTouchEvent（所有触摸必经，
        // 不受子 View 命中影响），再配合下面的系统手势排除声明。
        if (Build.VERSION.SDK_INT >= 29) {
            drawer.post(new Runnable() {
                @Override
                public void run() {
                    List<Rect> exclude = new ArrayList<Rect>();
                    exclude.add(new Rect(0, 0, dp(48), drawer.getHeight()));
                    drawer.setSystemGestureExclusionRects(exclude);
                }
            });
        }
        // 抽屉打开时把底层内容标成不可访问，读屏和点击都不会误穿到底下
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        setContentView(drawer);

        styleDrawerRow(TAB_CONSOLE, true);
    }

    /**
     * 带「整页横滑开菜单」的 DrawerLayout。
     *
     * 为什么要在 DrawerLayout 上做这一层：
     *  - MIUI 手势导航把最靠左约 35px 的右滑当成系统「返回」直接消费掉，事件
     *    到不了 APP（实测从 x=10 起手滑，APP 被退回桌面），所以画面最边缘那一条
     *    靠不住；
     *  - 试过在 Activity.dispatchTouchEvent 里判定，实测 Android 16 上这个方法
     *    根本不在这条触摸链路里（加了日志一次都没打印），不能依赖；
     *  - 也试过叠一个「边缘热区」子 View，结果 DrawerLayout 会把非抽屉子 View
     *    强行铺满全屏（uiautomator 实测 bounds 是 [0,80][720,1280]），反而把
     *    所有点击都吃掉了。
     *
     * 所以判定放在 DrawerLayout 自己的 dispatchTouchEvent：页面内所有触摸必经此处，
     * 不依赖子 View 命中，也不会遮挡任何控件。起手位置放宽到屏幕左侧 40% 以内、
     * 横移超过 70dp、竖直抖动小于 28dp 才算 —— 竖直抖动这一条是为了不跟页面的
     * 上下滚动打架。
     */
    private static class SwipeDrawer extends DrawerLayout {
        private float downX = 0f;
        private float downY = 0f;
        private boolean fired = false;
        View panel;

        SwipeDrawer(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            int act = ev.getActionMasked();
            float den = getResources().getDisplayMetrics().density;
            if (act == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                fired = false;
            } else if (act == MotionEvent.ACTION_MOVE) {
                if (!fired && panel != null && getWidth() > 0 && !isDrawerOpen(panel)) {
                    float dx = ev.getX() - downX;
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx > 70f * den && dy < 28f * den && downX < getWidth() * 0.4f) {
                        fired = true;
                        openDrawer(panel);
                    }
                }
            } else {
                fired = false;
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    /**
     * 抽屉宽度：78% 屏宽，封顶 280dp。
     *
     * 280dp 取自 Material 对 NavigationView 的 maxWidth 默认值；78% 是让手机上
     * 「右边留一条能看见底下的页面」，用户知道下面没消失。两种约束取小的那个。
     */
    private int drawerPanelWidth() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int want = (int) (w * 0.78f);
        int cap = dp(280);
        return want < cap ? want : cap;
    }

    /**
     * 抽屉内容：标题区 + 五个菜单项 + 底部版本行。
     *
     * 选中项用「左侧 3dp 蓝竖条 + 白字 + 浅蓝底」三重标记，一眼分得出在哪页；
     * 未选中是灰字、无竖条、无底色，视觉安静。
     */
    private LinearLayout buildDrawerPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(C_CARD);
        p.setPadding(0, dp(28), 0, dp(16));

        TextView t = new TextView(this);
        t.setText("ALAS");
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setLetterSpacing(-0.02f);
        t.setPadding(dp(24), 0, dp(24), 0);
        p.addView(t);

        TextView s = new TextView(this);
        s.setText("碧蓝航线自动化 · 手机本地运行");
        s.setTextColor(C_SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setPadding(dp(24), dp(3), dp(24), dp(16));
        p.addView(s);

        p.addView(separator());

        for (int i = 0; i < 5; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(12), 0);

            View bar = new View(this);
            bar.setBackgroundColor(C_BLUE);
            bar.setVisibility(View.GONE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3), dp(18));
            blp.rightMargin = dp(13);
            row.addView(bar, blp);

            TextView lab = new TextView(this);
            lab.setText(TAB_NAMES[i]);
            lab.setTextColor(C_SUB);
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.addView(lab);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    drawer.closeDrawer(drawerPanel);
                    showTab(idx);
                }
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            rlp.leftMargin = dp(8);
            rlp.rightMargin = dp(8);
            p.addView(row, rlp);

            drawerRow[i] = row;
            drawerBar[i] = bar;
            drawerLabel[i] = lab;
        }

        p.addView(separator());

        TextView foot = new TextView(this);
        foot.setText("ALAS 0.4.13 · 手机本地独立运行");
        foot.setTextColor(C_SUB);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setPadding(dp(24), dp(14), dp(24), 0);
        p.addView(foot);

        return p;
    }

    /** 抽屉项样式：选中的加蓝竖条 + 白字 + 10% 蓝底，其余置灰 */
    private void styleDrawerRow(int index, boolean active) {
        if (index < 0 || index >= 5) return;
        LinearLayout row = drawerRow[index];
        if (row == null) return;

        View bar = drawerBar[index];
        if (bar != null) bar.setVisibility(active ? View.VISIBLE : View.GONE);

        TextView lab = drawerLabel[index];
        if (lab != null) {
            lab.setTextColor(active ? C_TEXT : C_SUB);
            // 查到新版时，即使没停在更新页，抽屉里的「更新」也保持橙色，别被重置掉
            if (!active && index == TAB_UPDATE && hasPendingUpdate) {
                lab.setTextColor(C_ORANGE);
            }
            lab.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
        }
        // 激活项底纹：Material 给的是 primary 色 + 12% 透明度
        int tint = ((C_BLUE & 0x00FFFFFF) | 0x1F000000);
        row.setBackground(rippleRounded(active ? tint : 0, 10));
    }

    /**
     * 汉堡图标：三条 18x2dp 圆角横线，自己画。
     *
     * 不用 "☰" 之类的字符 —— 它们在部分机型的字体里会缺字或宽窄不一，
     * 画出来的线条在任何机器上都一样。
     */
    private static class Burger extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Burger(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            float u = getResources().getDisplayMetrics().density;
            float lw = 18 * u;
            float r = 1 * u;      // 2dp 高的线，圆角半径一半
            float gap = 7 * u;
            float x0 = (getWidth() - lw) / 2f;
            float x1 = x0 + lw;
            float cy = getHeight() / 2f;
            paint.setColor(0xFFFFFFFF);
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * gap;
                cv.drawRoundRect(new RectF(x0, y - r, x1, y + r), r, r, paint);
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 抽屉开着时返回键先关抽屉，不要直接退出 APP
        if (drawer != null && drawerPanel != null
                && drawer.isDrawerOpen(drawerPanel)) {
            drawer.closeDrawer(drawerPanel);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 任务页 / 参数页共用的滚动容器。
     *
     * 这里可以安全用 ScrollView：里面放的是「标题 + 开关 / 标题 + 值」这种简单行，
     * 不是上次把状态卡压成 0 高的那种 wrap_content 复合卡片。
     */
    private ScrollView buildScrollPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 底部留 28dp：列表最后一行贴着手势条/导航栏很难点，也让页面显得被截断
        box.setPadding(0, 0, 0, dp(28));
        sv.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 控制台：状态卡 + 操作按钮 + 任务列表 + 日志 */
    private View buildConsole() {
        // 不用 ScrollView 包整页：实测 ScrollView 里 wrap_content 的复合卡片会被量成 0 高，
        // 状态卡直接消失。竖屏 720x1280 下固定布局刚好排得下，日志吃掉剩余高度。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- 状态卡 ----
        // 横向两栏：左边状态 + 详情，右边「恢复屏幕」。
        // 「恢复屏幕」是低频动作（只在 ALAS 停在游戏画面、想拿回手机时用），
        // 原来和「开始/结束」三个并排，占掉三分之一宽度还容易误点；
        // Apple HIG 也明确说一行里带文字的按钮别超过两个。降级成卡内小字入口。
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setBackground(round(C_CARD, 14));
        status.setPadding(dp(16), dp(12), dp(12), dp(12));
        status.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);

        tvState = new TextView(this);
        tvState.setText("● 检测中");
        tvState.setTextColor(C_TEXT);
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvState.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusCol.addView(tvState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvDetail = new TextView(this);
        tvDetail.setText("");
        tvDetail.setTextColor(C_SUB);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDetail.setPadding(0, dp(3), 0, 0);
        statusCol.addView(tvDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status.addView(statusCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「恢复屏幕」小字入口：命中区给到 48dp 高，别让它小得点不中
        TextView bRestore = new TextView(this);
        bRestore.setText("恢复屏幕");
        bRestore.setTextColor(C_BLUE);
        bRestore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bRestore.setPadding(dp(12), dp(10), dp(2), dp(10));
        bRestore.setBackground(rippleRounded(0, 8));
        bRestore.setClickable(true);
        bRestore.setFocusable(true);
        status.addView(bRestore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        status.setMinimumHeight(dp(64));
        root.addView(status, slp);

        tvDot = tvState;

        // ---- 两个操作按钮：开始（主，绿实心）/ 结束（次，描边） ----
        // 高 48dp 是 Material 的最小触控目标；原来 44dp 贴着下限。
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        alp.topMargin = dp(10);

        TextView bStart = pill("开始", C_GREEN);
        TextView bStop = pill("结束", C_CARD2);
        // 次级按钮做成透明底 + 20% 白描边：在纯黑底上比实心深灰看得清，
        // 又不至于和绿色主按钮抢注意力
        bStop.setBackground(roundOutline(0x00000000, 0x33FFFFFF, 12));
        btnStart = bStart;
        btnStop = bStop;

        actions.addView(bStart, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p2.leftMargin = dp(10);
        actions.addView(bStop, p2);
        root.addView(actions, alp);

        bStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { act("start"); }
        });
        bStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { act("stop"); }
        });
        bRestore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { act("restore"); }
        });

        // 任务列表不放在这一页了，统一挪到「任务」页签，
        // 控制台的日志区因此能占到大半屏，读日志更舒服。

        // ---- 当前主线关卡（首页第一眼就能看到，点一下就改） ----
        root.addView(chapterCard(true), chapterCardLp());

        // ---- 日志（吃掉剩余高度） ----
        root.addView(groupTitle("运行日志"));

        logScroll = new ScrollView(this);
        logScroll.setBackground(round(C_CARD, 14));
        logScroll.setPadding(dp(14), dp(10), dp(14), dp(10));

        tvLog = new TextView(this);
        tvLog.setTextColor(0xFFAEAEB2);
        // 日志是这一页的主体内容，10sp 太细；11sp + 行距，等宽字体保持对齐
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvLog.setLineSpacing(dp(2), 1.15f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setText("等待日志");
        tvLog.setTextIsSelectable(true);
        logScroll.addView(tvLog);

        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lsp.bottomMargin = dp(10);
        root.addView(logScroll, lsp);

        return root;
    }

    /** 一行任务：左标题+副标题，右侧箭头 */
    private View taskRow(String name, String desc, final String task) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(ripple());
        row.setPadding(dp(16), dp(11), dp(14), dp(11));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(C_SUB);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setPadding(0, dp(1), 0, 0);
        texts.addView(d);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        // 原来用的 0xFF48484A，在 1C1C1E 的卡片上几乎看不见；
        // 右侧那个「›」是「这行能进去」的唯一提示，必须得看得清
        arrow.setTextColor(C_SUB);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.addView(arrow);

        // 注意：这里不要再加 setOnTouchListener 做按压反馈。
        // 实测「OnTouchListener 返回 false + OnClickListener」在这个布局里点击不触发，
        // 任务按钮点了没任何反应。按压反馈交给 stateListAnimator/背景即可，点击优先。
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTask(task, name); }
        });
        return row;
    }

    private View separator() {
        View line = new View(this);
        line.setBackgroundColor(C_SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        line.setLayoutParams(lp);
        return line;
    }

    private TextView groupTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_SUB);
        // 12sp 在 720x1280 上偏小，分组标题是扫读的锚点，给到 13sp
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLetterSpacing(0.02f);
        t.setAllCaps(false);
        t.setPadding(dp(16), dp(18), dp(16), dp(6));
        return t;
    }

    /** 管理页：内嵌 ALAS 官方 WebUI */
    private WebView buildWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(C_BG);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        w.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://127.0.0.1:22267")
                        || url.startsWith("http://localhost:22267"))) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)));
                } catch (Exception ignore) {
                }
                return true;
            }
        });
        return w;
    }

    /**
     * 枚举值的中文显示。
     *
     * 配置里存的是英文枚举（non_stop / 100_percent_clear / fleet1_mob_fleet2_boss…），
     * 直接甩给用户就是一串蛇形命名，既认不出来也容易选错。
     * 这里只做**展示层**翻译：写回配置的仍然是英文原值，不改存储格式。
     * 没收录的值原样显示，不会因为漏一条就变成空白。
     */
    private static final String[][] ENUM_ZH = {
            {"non_stop", "否"},
            {"100_percent_clear", "100% 通关"},
            {"map_3_stars", "100% 通关 + 三星"},
            {"threat_safe", "100% 通关 + 三星 + 安全海域"},
            {"threat_safe_without_3_stars", "100% 通关 + 安全海域"},
            {"normal", "普通"},
            {"hard", "困难"},
            {"campaign_main", "主线"},
            {"do_not_use", "不使用"},
            {"hunt_mode", "狩猎模式"},
            {"boss_mode", "BOSS 模式"},
            {"standby_mode", "待机模式"},
            {"combat_auto", "自动"},
            {"combat_manual", "手动"},
            {"standby", "待命"},
            {"line_ahead", "单纵"},
            {"double_line", "复纵"},
            {"diamond", "轮形"},
            {"calculate", "按心情值计算"},
            {"keep_exp_days", "按经验天数"},
            {"do_not_calculate", "不计算"},
            {"prevent_green_face", "防绿脸"},
            {"prevent_red_face", "防红脸"},
            {"prevent_yellow_face", "防黄脸"},
            {"not_in_dormitory", "不在宿舍才恢复"},
            {"no_recover", "不恢复"},
            {"to_boss_position", "直达 BOSS"},
            {"1_grid_to_boss", "距 BOSS 1 格"},
            {"2_grid_to_boss", "距 BOSS 2 格"},
            {"use_auto_search_keep", "自动寻敌保持"},
            {"sub_standby", "待机"},
            {"sub_auto_call", "自动召唤"},
            {"fleet1_mob_fleet2_boss", "一队道中 · 二队 BOSS"},
            {"fleet1_boss_fleet2_mob", "一队 BOSS · 二队道中"},
            {"fleet1_all", "只出一队"},
            {"fleet2_all", "只出二队"},
            {"default_mode", "默认"},
    };

    /** 英文枚举 → 中文；没收录就原样返回 */
    private static String enumZh(String v) {
        if (v == null) return "";
        for (String[] pair : ENUM_ZH) {
            if (pair[0].equals(v)) return pair[1];
        }
        return v;
    }

    private TextView pill(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(round(color, 12));
        // 实心按钮的按压反馈用前景水波：背景是实色圆角，叠一层浅白光才看得出来
        t.setForeground(ripple());
        return t;
    }

    private void showTab(int which) {
        curTab = which;

        consoleRoot.setVisibility(which == TAB_CONSOLE ? View.VISIBLE : View.GONE);
        taskScroll.setVisibility(which == TAB_TASK ? View.VISIBLE : View.GONE);
        paramScroll.setVisibility(which == TAB_PARAM ? View.VISIBLE : View.GONE);
        updateScroll.setVisibility(which == TAB_UPDATE ? View.VISIBLE : View.GONE);
        webView.setVisibility(which == TAB_WEB ? View.VISIBLE : View.GONE);

        for (int i = 0; i < 5; i++) styleDrawerRow(i, i == which);
        if (tvTopTitle != null) tvTopTitle.setText(TAB_NAMES[which]);

        // 懒加载：proot 起一次要好几秒，不切过去就不拉
        if (which == TAB_TASK && !taskLoaded) loadTasks();
        if (which == TAB_PARAM && !paramLoaded) loadParams();
        if (which == TAB_UPDATE && !updateLoaded) loadUpdate();

        if (which == TAB_WEB) {
            if (!lastRunning) {
                webView.loadDataWithBaseURL(null,
                        "<html><body style='background:#000;color:#8E8E93;"
                                + "font-family:-apple-system,sans-serif;text-align:center;padding-top:45%'>"
                                + "<div style='font-size:15px'>ALAS 还没运行</div>"
                                + "<div style='font-size:12px;margin-top:8px;color:#48484A'>"
                                + "先回控制台点「开始」</div></body></html>",
                        "text/html", "UTF-8", null);
                webReady = false;
            } else if (!webReady) {
                webView.loadUrl(WEB_URL);
                webReady = true;
            } else {
                webView.reload();
            }
        }
    }

    // ================================================================= 动作

    private void act(final String what) {
        if (busy) { toast("正在执行上一个操作"); return; }
        busy = true;

        if ("start".equals(what)) {
            txtState("启动中");
            tvDetail.setText("确保分辨率 · 打开游戏 · 拉起 ALAS");
            tvDot.setTextColor(C_ORANGE);
        } else if ("stop".equals(what)) {
            txtState("停止中");
            tvDetail.setText("结束容器，游戏与分辨率不动");
            tvDot.setTextColor(C_ORANGE);
        } else {
            txtState("恢复中");
            tvDetail.setText("切回出厂 1080x2400");
            tvDot.setTextColor(C_ORANGE);
        }

        pool.execute(new Runnable() {
            public void run() {
                String cmd;
                long timeout;
                if ("start".equals(what)) {
                    // 两个坑一起修，缺一个「开始」都不动：
                    // 1) 必须用 Termux bash 的绝对路径。`su -M -c` 的 PATH 是
                    //    /product/bin:...:/system/bin:...，里面没有 Termux 的 bin，
                    //    写相对 `bash` 直接 "setsid: exec bash: No such file or directory"，
                    //    脚本一行都没跑，界面刷一下又回到「已停止」。
                    // 2) 用 /system/bin/setsid **-w** 另开会话。脚本会把游戏拉到前台，APP 随即退到后台，
                    //    MIUI 按进程组回收后台 APP 时会把没脱离的 proot 一起收掉
                    //    （实测日志：18:37:54 起好 Uvicorn，18:38:59 就 Shutting down，正好 65 秒）。
                    //    这个 -w 千万别省：Toybox 的 setsid 不加 -w 时 fork 完父进程立刻退出，
                    //    su 的 shell 跟着结束、stdout 管道被 APP 关掉，脚本下一句 echo 就吃到 SIGPIPE
                    //    整个被杀。加上 -w 后 setsid 会等子进程并回传它的退出码，
                    //    既有完整输出、又能拿到真实 exit（实测 EXIT=0、proot 存活、HTTP=200）。
                    cmd = "/system/bin/setsid -w " + TERMUX_BASH + " " + START_GAME + " all";
                    // 前置：adbd 的 TCP 端口手机重启后会丢，不补上的话 start-game.sh
                    // 第一步就报「!!! adb 连不上 127.0.0.1:5555」，整个启动失败。
                    // 这步失败也不阻断（可能本来就在监听），所以用 `|| true` 兜住。
                    cmd = TERMUX_BASH + " " + ENSURE_ADBD + " || true; " + cmd;
                    // 实测这一步要 100 秒上下：连 adb + 开游戏 + 等横屏 + 加载 25 秒
                    // + 清理旧实例 + sleep 12 + sleep 20 + WebUI 探测。冷启动更久，给 300。
                    timeout = 300L;
                } else if ("stop".equals(what)) {
                    cmd = TERMUX_BASH + " " + RUN + " stop";
                    timeout = 90L;
                } else {
                    // 「恢复屏幕」同样要先补 adbd 端口：display.sh 是靠容器里的
                    // adb 去 wm size 的，端口丢了就只会打印「无法操作分辨率」。
                    cmd = TERMUX_BASH + " " + ENSURE_ADBD + " || true; "
                            + TERMUX_BASH + " " + DISPLAY + " native";
                    timeout = 90L;
                }
                final String out = su(cmd, timeout);
                final int exit = lastExit;
                final String w = what;
                // 把脚本输出原样落盘到 App 私有目录，别再像以前那样扔掉。
                // 之前「点开始没反应」就是因为它：脚本明明报错了（c_prepare_game.sh
                // 返回非 0、或者 bash 找不到），但 out 用完就丢，
                // 界面只显示一个「已停止」，谁也看不出为什么。
                writeOut("alas-" + what + ".out", out, exit);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (out == null) {
                            toast("执行超时或没有 root 权限，先看下方日志");
                            showLogTail();
                        } else if ("start".equals(w) && (exit != 0 || !looksOk(out))) {
                            toast("启动失败，日志区已显示脚本输出");
                            // 直接把脚本输出贴到日志区，用户一眼能看见卡在哪一步
                            tvLog.setText(scriptTail(out));
                        } else {
                            if ("start".equals(w)) {
                                toast("已启动");
                                webReady = false;
                                if (exit != 0) toast("脚本有报错，看日志");
                            } else if ("stop".equals(w)) {
                                // 脚本会打印「剩余 python3.10 进程：N」。N 不为 0 说明还有
                                // 容器内的实例没退干净（历史上就因为入口换了名、pkill 匹配
                                // 不到，留下孤儿继续写日志，界面一直显示「挂机运行中」）。
                                if (out.contains("剩余 python3.10 进程：0")) {
                                    toast("已结束");
                                } else {
                                    toast("已结束，但仍有残留进程 —— 再点一次结束");
                                }
                                webReady = false;
                            } else {
                                toast("已恢复出厂分辨率");
                            }
                            showLogTail();
                        }
                        refresh();
                    }
                });
            }
        });
    }

    /**
     * 在容器里跑一个 ALAS 任务。
     *
     * alas_cli.py 会先把游戏切到前台，所以点了之后屏幕会跳到游戏画面 —— 这是正常的。
     * 输出重定向到文件，界面靠轮询回读，任务跑多久都不影响 APP。
     */
    private void runTask(final String task, final String label) {
        if (taskRunning) {
            toast("已有任务在跑，等它结束");
            return;
        }
        taskRunning = true;
        txtState("执行中：" + label);
        tvDetail.setText("正在拉起游戏，屏幕会切到游戏画面");
        tvDot.setTextColor(C_ORANGE);
        tvLog.setText("任务刚启动，稍等几秒出日志");

        pool.execute(new Runnable() {
            public void run() {
                String cmd = TERMUX_BASH + " " + ALAS_DIR + "/_exec.sh "
                        + "/root/AzurLaneAutoScript/deploy/Termux/alas_task.sh "
                        + task + " > " + TASK_LOG + " 2>&1 &";
                final String out = su(cmd, 25L);
                ui.post(new Runnable() {
                    public void run() {
                        if (out == null) {
                            taskRunning = false;
                            toast("拿不到 root");
                        } else {
                            toast("已启动 " + label + "，屏幕会切到游戏");
                            showLogTail();
                        }
                        refresh();
                    }
                });
            }
        });
    }

    // ================================================================= 配置读写

    /**
     * 跑一次 c_config.sh，再把落盘结果读回来。
     *
     * 为什么要分两步：proot 会话的 stdout 在无 tty 的调用路径下拿不全，
     * c_config.sh 已经把结果写进 alas-config.out，直接读文件最稳。
     */
    private String cfg(String args) {
        String run = TERMUX_BASH + " " + ALAS_DIR + "/_exec.sh " + CFG_INNER + " " + args;
        su(run, 75L);
        String out = su("cat " + CFG_OUT, 15L);
        return out == null ? null : out.trim();
    }

    /**
     * 更新检查/应用。和 cfg() 同一套路，但超时给得很长：
     * check 走 GitHub API 几秒就回；apply 要下 80MB 源码包再覆盖几千个文件，
     * 实测下载 5 秒、解压 17 秒，但手机网络和 IO 都可能更慢，所以给到 15 分钟。
     */
    private String upd(String args) {
        String run = TERMUX_BASH + " " + ALAS_DIR + "/_exec.sh " + UPD_INNER + " " + args;
        su(run, 900L);
        String out = su("cat " + UPD_OUT, 20L);
        return out == null ? null : out.trim();
    }

    /** 任务页：挂机队列（每个任务块的 Scheduler.Enable）+ 单次执行 */
    private void loadTasks() {
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("scheduler");
                ui.post(new Runnable() {
                    public void run() { renderTasks(out); }
                });
            }
        });
    }

    private void renderTasks(String json) {
        taskListBox.removeAllViews();
        if (json == null || !json.trim().startsWith("[")) {
            taskListBox.addView(hint("读不到 ALAS 配置。先在控制台点「开始」把 ALAS 拉起来，再回到这里。"));
            return;
        }

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            taskListBox.addView(hint("配置解析失败：" + brief(json)));
            return;
        }

        // 分成「已启用 / 未启用」两组，已启用就是真正的挂机队列
        List<Integer> onIdx = new ArrayList<Integer>();
        List<Integer> offIdx = new ArrayList<Integer>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("enable", false)) onIdx.add(i);
            else offIdx.add(i);
        }

        // 换号场景：几个号轮着挂，进度各不相同，而 alas.json 里记的还是上一个号的
        // 时间戳 —— 直接启动会一路「等到明天再说」，看着像没跑。给一个一键重置。
        taskListBox.addView(groupTitle("换号 / 进度对不上"));
        taskListBox.addView(hint("把每个任务的「下次运行」一次改成现在，调度器随后按队列顺序从头重跑一遍。"
                + "正在跑的任务会在这一轮结束时收尾重排，不重启游戏。"));
        LinearLayout resetCard = card();
        TextView resetBtn = new TextView(this);
        resetBtn.setText("重置所有任务时间");
        resetBtn.setTextColor(C_BLUE);
        resetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        resetBtn.setPadding(dp(16), dp(13), dp(16), dp(13));
        resetBtn.setBackground(ripple());
        resetCard.addView(resetBtn);
        taskListBox.addView(resetCard);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmResetTimes(); }
        });

        taskListBox.addView(groupTitle("挂机队列 · 已启用 " + onIdx.size() + " 项"));
        taskListBox.addView(hint("点一行进它的参数页；只有右侧那个开关才是「停用/启用」。"));
        if (onIdx.isEmpty()) {
            taskListBox.addView(hint("当前没有任何任务在挂机队列里。下面打开需要的开关。"));
        } else {
            LinearLayout card = card();
            for (int j = 0; j < onIdx.size(); j++) {
                JSONObject o = arr.optJSONObject(onIdx.get(j));
                card.addView(schedRow(o, true));
                if (j < onIdx.size() - 1) card.addView(separator());
            }
            taskListBox.addView(card);
        }

        taskListBox.addView(groupTitle("未启用 " + offIdx.size() + " 项 · 点右侧开关加入挂机队列"));
        LinearLayout card2 = card();
        for (int j = 0; j < offIdx.size(); j++) {
            JSONObject o = arr.optJSONObject(offIdx.get(j));
            card2.addView(schedRow(o, false));
            if (j < offIdx.size() - 1) card2.addView(separator());
        }
        taskListBox.addView(card2);

        taskListBox.addView(groupTitle("单次执行 · 立即跑一次，不影响挂机队列"));
        taskListBox.addView(hint("点了之后游戏会切到前台，屏幕显示游戏画面属正常。"));
        LinearLayout card3 = card();
        String[][] oneshot = {
                {"日常收菜", "石油 金币 经验 任务奖励", "reward"},
                {"委托", "按过滤器收取并重新派发", "commission"},
                {"科研", "解析并开始新项目", "research"},
                {"战术教室", "技能书训练", "tactical"},
                {"宿舍", "收取与投喂", "dorm"},
                {"指挥喵", "购买与训练", "meowfficer"},
                {"大舰队", "后勤与作战", "guild"},
                {"演习", "每日演习", "exercise"},
                {"每日任务", "日常任务一条龙", "daily"},
                {"困难图", "困难关卡三次", "hard"},
        };
        for (int j = 0; j < oneshot.length; j++) {
            card3.addView(taskRow(oneshot[j][0], oneshot[j][1], oneshot[j][2]));
            if (j < oneshot.length - 1) card3.addView(separator());
        }
        taskListBox.addView(card3);

        taskLoaded = true;
    }

    /** 重置前先问一句：这个动作会让所有任务都立刻各跑一遍，不能手滑 */
    private void confirmResetTimes() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("重置所有任务时间")
                .setMessage("所有任务的「下次运行」都会改成现在，调度器随后从头重跑一遍。\n\n"
                        + "换号、或者换号后进度对不上时用这个。"
                        + "正在跑的任务会在这一轮结束时收尾重排，不会重启游戏。")
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doResetTimes(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doResetTimes() {
        if (busy) {
            toast("上一个操作还没跑完，稍等");
            return;
        }
        busy = true;
        toast("正在重置所有任务时间…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("reset");
                // 判断「有没有东西在跑」必须看容器进程数，不能用 taskRunningNow()：
                // 那个锚在 alas_task.sh 的 alas-task.running 标志上，只有「单次执行」
                // 才会写；常驻调度器（run-alas.sh daemon）根本没有这个标志，
                // 于是调度器明明在跑也会被判成没跑，提示语就说反了。
                final int procs = countProcs();
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (out != null && out.contains("\"ok\": true")) {
                            int n = 0;
                            try {
                                // c_config.sh 会在 JSON 前面带一两行说明，从最后一个 { 起解析
                                String t = out.trim();
                                int lb = t.lastIndexOf('{');
                                if (lb >= 0) t = t.substring(lb);
                                n = new JSONObject(t).optInt("count", 0);
                            } catch (Exception e) {
                                // 解析失败不影响主流程，toast 里就少个数字
                            }
                            if (procs > 0) {
                                toast("已重置 " + n + " 个任务 · 调度器已按新时间重排");
                            } else {
                                toast("已重置 " + n + " 个任务 · 回控制台点「开始」才会执行");
                            }
                        } else {
                            toast("重置失败：" + brief(out));
                        }
                        taskLoaded = false;
                        loadTasks();
                        // 参数页里显示的时间也过期了，下次进去重新读
                        paramLoaded = false;
                    }
                });
            }
        });
    }

    /** 一行挂机任务：名字 + 下次运行/间隔 + 开关 */
    private View schedRow(JSONObject o, boolean on) {
        final String block = o.optString("block");
        String command = o.optString("command");
        String next = o.optString("next_run", "");
        String success = o.optString("success", "");

        String desc;
        if (on) {
            desc = "间隔 " + success + " 分 · 下次 " + shortTime(next);
        } else {
            desc = command;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(9), dp(12), dp(9));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(zhName(block));
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(C_SUB);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setPadding(0, dp(1), 0, 0);
        texts.addView(d);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧「›」：告诉用户点这行是「进去」，不是「开关」
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(C_SUB);
        chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        chev.setPadding(dp(4), 0, dp(4), 0);
        row.addView(chev, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 开关单独包一层 56x48 的容器，只有这块面积算「开关」。
        // 原来整行都绑了 toggleEnable，想进去改参数的人一失手就把任务停了 ——
        // 这两件事不该共用一个命中区。
        LinearLayout swBox = new LinearLayout(this);
        swBox.setGravity(Gravity.CENTER);
        swBox.setClickable(true);
        swBox.setFocusable(true);

        Switch sw = new Switch(this);
        sw.setChecked(on);
        // Switch 自己不收点击，事件交给外面那层容器：命中区比 Switch 本体宽，
        // 手指点偏一点也还是开关，不会漏到行上变成「进参数页」。
        sw.setClickable(false);
        sw.setFocusable(false);
        swBox.addView(sw);

        swBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleEnable(block, !on); }
        });
        row.addView(swBox, new LinearLayout.LayoutParams(dp(56), dp(48)));

        // 行本身（开关以外的部分）点了进这个任务块的参数页
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBlockParams(block); }
        });
        return row;
    }

    /**
     * 从任务页点一行，直接进这个任务块的参数编辑。
     *
     * 参数页的「常用参数」要跑一次 proot + python（首屏 15~25 秒），那条路太慢；
     * 这里先把 paramLoaded 置真，跳过「常用参数」那一步，直接 dump 该块
     * （`dumpBlock` 只读一个块，返回快得多），渲染出来的就是可编辑的完整参数表，
     * 里面的「‹ 返回常用参数」还能退回原来的常用列表。
     */
    private void openBlockParams(final String block) {
        paramLoaded = true;   // 别让 showTab 又去跑一遍 loadParams()
        showTab(TAB_PARAM);
        dumpBlock(block);
    }

    private void toggleEnable(final String block, final boolean on) {
        toast((on ? "启用 " : "停用 ") + zhName(block) + "，写入需要几秒");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("enable " + block + " " + (on ? "1" : "0"));
                ui.post(new Runnable() {
                    public void run() {
                        if (out != null && out.contains("\"ok\"")) {
                            toast(zhName(block) + (on ? " 已加入挂机队列" : " 已移出挂机队列"));
                            taskLoaded = false;
                            loadTasks();
                        } else {
                            toast("写入失败：" + brief(out));
                            taskLoaded = false;
                            loadTasks();
                        }
                    }
                });
            }
        });
    }

    /** 参数页：常用参数 + 可编辑 */
    private void loadParams() {
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("common");
                ui.post(new Runnable() {
                    public void run() { renderParams(out); }
                });
            }
        });
    }

    // ========================================================== 主线关卡切换

    /**
     * 首页关卡选择器用的章 / 关上限。
     *
     * 参数页那份候选值是从 ALAS 的 campaign 目录扫出来的（ALAS 更新会自动变多），
     * 但首页要求「点一下立刻出格子」，不能等一次 proot 往返，所以这里放一份本地表。
     * ALAS 0.4.13 实测就是 1-1 ~ 16-4；万一将来出了第 17 章，去参数页的
     * 「打哪一关」仍然能选到（那份是动态的）。
     */
    private static final int CH_MAX = 16;
    private static final int ST_MAX = 4;
    /** 关卡选择弹窗，点中一项后要能关掉它 */
    private AlertDialog chapterDlg = null;

    private LinearLayout.LayoutParams chapterCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    /**
     * 「主线设置」卡：打哪一关 + 打多少次，两行并排，各自右侧一个改入口。
     *
     * 原来改关卡要一路点：参数页签 → 等 20 秒渲染 → 往下滚到「主线」组 →
     * 点那一项 → 在 64 项长列表里找；改次数同理。用户反馈「要一直点好麻烦」，
     * 所以这两项直接提到首页，进门第一眼就能看到，点一下出选择面板。
     */
    private View mainCard(final boolean home) {
        LinearLayout box = card();
        box.addView(settingRow("打哪一关",
                curChapter.length() > 0 ? curChapter : "读取中…",
                "改关卡 ›", true, home,
                new Runnable() {
                    public void run() { showChapterPicker(); }
                }));
        box.addView(separator());
        box.addView(settingRow("打多少次",
                runCountText(), "改次数 ›", false, home,
                new Runnable() {
                    public void run() { showRunCountPicker(); }
                }));
        box.addView(separator());
        // 停刷条件（PC 端叫「达成关卡成就后停止」）：和关卡、次数并列摆出来，
        // 不然想打满三星还得钻进参数页里翻。
        box.addView(settingRowKind("停刷条件",
                stopCondText(), "改条件 ›", home, 2,
                new Runnable() {
                    public void run() { showStopCondPicker(); }
                }));
        return box;
    }

    /**
     * 卡里的一行：左边「标签 / 值」上下叠，右边一个蓝色入口。
     *
     * isChapter 决定这行的值要登记到哪两个字段上（首页那份和参数页那份），
     * 这样改完能同时刷新两处，不会出现一页显示新值、另一页还是旧值。
     */
    private View settingRow(final String label, final String value, final String action,
                            final boolean isChapter, final boolean home,
                            final Runnable target) {
        return settingRowKind(label, value, action, home, isChapter ? 1 : 0, target);
    }

    /**
     * 同上，但显式指定这一行属于哪个字段：
     * 0 = 打多少次，1 = 打哪一关，2 = 停刷条件。
     * 每种各有一对引用（首页 / 参数页），paintMain() 改完一次刷两处。
     */
    private View settingRowKind(final String label, final String value, final String action,
                                final boolean home, final int kind, final Runnable target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(9), dp(10), dp(9));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(C_SUB);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        col.addView(lab);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(C_TEXT);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        val.setPadding(0, dp(1), 0, 0);
        col.addView(val);

        TextView btn = new TextView(this);
        btn.setText(action);
        btn.setTextColor(C_BLUE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setPadding(dp(16), dp(8), dp(6), dp(8));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                target.run();
            }
        });

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btn);

        if (kind == 1) {
            if (home) tvChapterHome = val; else tvChapterParam = val;
        } else if (kind == 2) {
            if (home) tvStopHome = val; else tvStopParam = val;
        } else {
            if (home) tvRunHome = val; else tvRunParam = val;
        }
        return row;
    }

    /** 停刷条件怎么显示：非 English 枚举原文，翻成「100% 通关 + 三星」这种人话 */
    private String stopCondText() {
        if (!mainLoaded && curStopCond.length() == 0) return "读取中…";
        return enumZh(curStopCond.length() > 0 ? curStopCond : "non_stop");
    }

    /** 次数怎么显示：0 在 ALAS 里是「不限次数」，直接写 0 用户看不懂 */
    private String runCountText() {
        if (!mainLoaded && curRunCount <= 0) return "读取中…";
        return curRunCount <= 0 ? "一直打（不限）" : (curRunCount + " 次");
    }

    /**
     * 次数选择面板。
     *
     * 用固定预设而不是弹输入框：想打多少次基本就是这几个数，
     * 给格子点一下就行，不用调键盘、也不会输成负数。
     */
    private void showRunCountPicker() {
        final int[] presets = {0, 1, 2, 3, 5, 10, 20, 30, 50, 100, 200, 500};
        String[] names = {"不限", "1", "2", "3", "5", "10", "20", "30", "50", "100", "200", "500"};

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(10), dp(10), dp(6));

        // 一行 4 个，12 项刚好 3 行
        for (int i = 0; i < presets.length; i += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            rlp.bottomMargin = dp(6);
            for (int j = i; j < i + 4 && j < presets.length; j++) {
                final int n = presets[j];
                final boolean on = mainLoaded && n == curRunCount;
                TextView b = new TextView(this);
                b.setText(names[j]);
                b.setGravity(Gravity.CENTER);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                b.setTextColor(on ? 0xFFFFFFFF : C_BLUE);
                b.setBackgroundColor(on ? C_BLUE : 0x14FFFFFF);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (chapterDlg != null) chapterDlg.dismiss();
                        setRunCount(n);
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        0, dp(46), 1f);
                blp.leftMargin = dp(5);
                blp.rightMargin = dp(5);
                row.addView(b, blp);
            }
            list.addView(row, rlp);
        }

        TextView tip = new TextView(this);
        tip.setText("「不限」= 只要石油够就一直打；填数字 = 打够这么次就停");
        tip.setTextColor(C_SUB);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setPadding(dp(8), dp(2), dp(8), dp(4));
        list.addView(tip);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("打多少次");
        b.setView(list);
        b.setNegativeButton("取消", null);
        chapterDlg = b.create();
        chapterDlg.show();
    }

    /**
     * 改周回次数。
     *
     * 和关卡一样不复用 setParam：那条路存完会重拉整页（15~25 秒），
     * 而这里只需要把本地数字换掉。
     */
    /**
     * 停刷条件面板。
     *
     * 这是 PC 端「达成关卡成就后停止」那张下拉：打满 100% 通关、或者刷到三星、
     * 或者刷出安全海域就收工，省得一直挂着浪费油。选项文字长，
     * 所以竖排 + 每项带一句人话说明，别让人对着 5 行英文枚举猜。
     */
    private void showStopCondPicker() {
        final String[] vals = {"non_stop", "100_percent_clear", "map_3_stars",
                "threat_safe", "threat_safe_without_3_stars"};
        final String[] subs = {
                "一直打，不因为成就停下",
                "这张图 100% 通关后停",
                "通关并且拿到三星后停",
                "通关 + 三星 + 安全海域齐全后停",
                "通关 + 安全海域后停（不强求三星）",
        };

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(6));

        for (int i = 0; i < vals.length; i++) {
            final String v = vals[i];
            // 还没读到配置时，把「否」当作当前选中，避免整列都是灰的
            final boolean on = curStopCond.equals(v)
                    || (curStopCond.length() == 0 && "non_stop".equals(v));

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setBackgroundColor(on ? 0x1F0A84FF : 0x0AFFFFFF);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView t1 = new TextView(this);
            t1.setText(enumZh(v));
            t1.setTextColor(on ? C_BLUE : C_TEXT);
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            TextView t2 = new TextView(this);
            t2.setText(subs[i]);
            t2.setTextColor(C_SUB);
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t2.setPadding(0, dp(2), 0, 0);

            col.addView(t1);
            col.addView(t2);

            item.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (on) {
                TextView mark = new TextView(this);
                mark.setText("✓");
                mark.setTextColor(C_BLUE);
                mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                item.addView(mark);
            }

            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (chapterDlg != null) chapterDlg.dismiss();
                    setStopCond(v);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            list.addView(item, lp);
        }

        TextView tip = new TextView(this);
        tip.setText("想让 ALAS 达成就自动往下一关开荒，还要去参数页把「自动升图」打开");
        tip.setTextColor(C_SUB);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setPadding(dp(8), dp(2), dp(8), dp(4));
        list.addView(tip);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("停刷条件");
        b.setView(list);
        b.setNegativeButton("取消", null);
        chapterDlg = b.create();
        chapterDlg.show();
    }

    /** 写停刷条件。和改关卡一样：值就是刚点的那个，不用重读整页 */
    private void setStopCond(final String v) {
        if (v.equals(curStopCond)) return;
        toast("正在写入…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("set Main.StopCondition.MapAchievement " + v);
                ui.post(new Runnable() {
                    public void run() {
                        if (out != null && out.contains("\"ok\"")) {
                            curStopCond = v;
                            mainLoaded = true;
                            paintMain();
                            // 别写「下一轮任务开始生效」：挂机中 ALAS 每轮结束会把
                            // 内存里的配置写回 alas.json，刚改的值可能被它自己覆盖。
                            // 想立刻用只能重启调度器（顶部「立即生效」）。
                            toast("停刷条件已设为「" + enumZh(v) + "」· 立即生效请点顶部按钮");
                        } else {
                            toast("写入失败：" + brief(out));
                        }
                    }
                });
            }
        });
    }

    private void setRunCount(final int n) {
        if (mainLoaded && n == curRunCount) return;
        toast("正在写入…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("set Main.StopCondition.RunCount " + n);
                ui.post(new Runnable() {
                    public void run() {
                        if (out != null && out.contains("\"ok\"")) {
                            curRunCount = n;
                            mainLoaded = true;
                            paintMain();
                            toast("已设为 " + (n <= 0 ? "不限次数" : n + " 次")
                                    + " · 下一轮地图跑完自动生效");
                        } else {
                            toast("写入失败：" + brief(out));
                        }
                    }
                });
            }
        });
    }

    /**
     * 兼容旧调用点：关卡已经并进 mainCard 一起显示，这里只是转发，
     * 避免同一页出现两张关卡卡（一张单行、一张两行）。
     */
    private View chapterCard(final boolean home) {
        return mainCard(home);
    }

    /**
     * 按章分行的关卡选择器。
     *
     * 一行一章、行内 4 个关号，比原来那条 64 项的竖列表短得多 ——
     * 16 行一次能看大半，想打哪关基本一眼就点到，不用滚。
     */
    private void showChapterPicker() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(6), dp(10), dp(6));

        for (int ch = 1; ch <= CH_MAX; ch++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            rlp.bottomMargin = dp(5);

            TextView chLabel = new TextView(this);
            chLabel.setText(ch + " 章");
            chLabel.setTextColor(C_SUB);
            chLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chLabel.setWidth(dp(46));
            chLabel.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(chLabel);

            for (int st = 1; st <= ST_MAX; st++) {
                final String key = ch + "-" + st;
                final boolean on = key.equals(curChapter);

                TextView b = new TextView(this);
                b.setText(String.valueOf(st));
                b.setGravity(Gravity.CENTER);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                b.setTextColor(on ? 0xFFFFFFFF : C_BLUE);
                b.setBackgroundColor(on ? C_BLUE : 0x14FFFFFF);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (chapterDlg != null) chapterDlg.dismiss();
                        setChapter(key);
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        0, dp(40), 1f);
                blp.leftMargin = dp(5);
                blp.rightMargin = dp(5);
                row.addView(b, blp);
            }
            list.addView(row, rlp);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 打开就滚到用户当前所在的那一章。
        // 不这么做的话，当前是 15-4 的人点开看到的是 1 章，还得往下翻十几行才找得到
        // 自己那一关 —— 那就从「少点几下」又变回「一直在滚」了。
        final int curCh = chapterNum(curChapter);
        if (curCh > 3) {
            sv.post(new Runnable() {
                public void run() {
                    // 一行 = 44dp + 5dp 间距；往上留两行，当前章不会贴在最顶上
                    sv.scrollTo(0, dp((curCh - 3) * 49));
                }
            });
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择主线关卡");
        b.setView(sv);
        b.setNegativeButton("取消", null);
        chapterDlg = b.create();
        chapterDlg.show();
    }

    /** 从 "12-4" 里取出章号 12；取不到返回 0 */
    private int chapterNum(String key) {
        if (key == null) return 0;
        int i = key.indexOf('-');
        if (i <= 0) return 0;
        try {
            return Integer.parseInt(key.substring(0, i).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 改主线关卡。
     *
     * 故意不复用 setParam：setParam 存成功后会 loadParams() 重新拉整页（要起
     * proot，15~25 秒），而改关卡根本不需要重读 —— 新值就是刚点的那个。
     * 这里直接把本地缓存和两处界面上的数字换掉，点完就是最终状态，零等待。
     */
    private void setChapter(final String key) {
        if (key.equals(curChapter)) return;
        toast("正在写入 " + key + "…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("set Main.Campaign.Name " + key);
                ui.post(new Runnable() {
                    public void run() {
                        if (out != null && out.contains("\"ok\"")) {
                            curChapter = key;
                            mainLoaded = true;
                            paintMain();
                            toast("已设为 " + key + " · 下一轮地图跑完自动换图");
                        } else {
                            toast("写入失败：" + brief(out));
                        }
                    }
                });
            }
        });
    }

    /** 把首页和参数页两处的关卡、次数一起刷新 */
    private void paintMain() {
        String ch = curChapter.length() > 0 ? curChapter : "—";
        if (tvChapterHome != null) tvChapterHome.setText(ch);
        if (tvChapterParam != null) tvChapterParam.setText(ch);
        String rc = runCountText();
        if (tvRunHome != null) tvRunHome.setText(rc);
        if (tvRunParam != null) tvRunParam.setText(rc);
        String sc = stopCondText();
        if (tvStopHome != null) tvStopHome.setText(sc);
        if (tvStopParam != null) tvStopParam.setText(sc);
    }

    /**
     * 启动后读一次当前关卡。
     *
     * 只读这一次：读一次要起 proot，往返好几秒，天天轮询太浪费。
     * 之后所有改动都由 setChapter / setRunCount 本地维护，打开 APP 就是即时显示。
     * 关卡和次数走 maininfo 一次拿全 —— 分两次 get 就要起两遍 proot。
     */
    private void loadMainInfo() {
        if (mainLoaded) return;
        pool.execute(new Runnable() {
            public void run() {
                String out = cfg("maininfo");
                String ch = "";
                int rc = 0;
                String sc = "";
                boolean okChapter = false;
                if (out != null) {
                    try {
                        JSONObject o = new JSONObject(out);
                        ch = o.optString("chapter");
                        rc = o.optInt("run_count", 0);
                        sc = o.optString("stop_cond", "");
                        okChapter = true;
                    } catch (Exception ignore) { }
                    if (!okChapter) {
                        // 输出里夹了别的东西时，退一步按正则抓
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("\"chapter\"\\s*:\\s*\"([0-9]+-[0-9]+)\"").matcher(out);
                        if (m.find()) { ch = m.group(1); okChapter = true; }
                        java.util.regex.Matcher m2 = java.util.regex.Pattern
                                .compile("\"run_count\"\\s*:\\s*(-?\\d+)").matcher(out);
                        if (m2.find()) {
                            try { rc = Integer.parseInt(m2.group(1)); } catch (Exception ignore) { }
                        }
                        java.util.regex.Matcher m3 = java.util.regex.Pattern
                                .compile("\"stop_cond\"\\s*:\\s*\"([a-z_0-9]+)\"").matcher(out);
                        if (m3.find()) sc = m3.group(1);
                    }
                }
                final String gotCh = (ch != null) ? ch.trim() : "";
                final String gotSc = (sc != null) ? sc.trim() : "";
                final int gotRc = rc;
                ui.post(new Runnable() {
                    public void run() {
                        if (gotCh.length() > 0 && gotCh.matches("\\d+-\\d+")) {
                            curChapter = gotCh;
                        }
                        if (gotSc.length() > 0 && !"null".equals(gotSc)) {
                            curStopCond = gotSc;
                        }
                        curRunCount = gotRc;
                        mainLoaded = true;
                        paintMain();
                    }
                });
            }
        });
    }

    /**
     * 参数页顶部的「立即生效」条。
     *
     * 为什么必须有它：ALAS 只在**每轮任务开始时**读一次 config/alas.json。
     * 「连续作战」开着的时候它会一直刷同一张图不换任务，所以刚改的值
     * 往往要等很久才被读到，用户看到的就成了「改了没用 / 没反应」。
     * 点这里直接重启调度器，几秒后就用新配置重新开跑。
     */
    private View applyBar() {
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("改完想立刻用上？");
        t1.setTextColor(C_TEXT);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TextView t2 = new TextView(this);
        t2.setText("下一轮地图跑完就换，不重启游戏");
        t2.setTextColor(C_SUB);
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t2.setPadding(0, dp(3), 0, 0);
        col.addView(t1);
        col.addView(t2);

        TextView btn = new TextView(this);
        btn.setText("立即生效");
        btn.setTextColor(C_BLUE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(9), dp(14), dp(9));
        // 12% 蓝底小胶囊：纯文字看着不像按钮，用户不确定这块能不能点
        btn.setBackground(rippleRounded(((C_BLUE & 0x00FFFFFF) | 0x1F000000), 9));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyParams();
            }
        });

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btn);
        box.addView(row);
        return box;
    }

    /**
     * 让新参数生效 —— 只写一个标记文件，不重启任何东西。
     *
     * 以前这里是 run-alas.sh restart（stop + daemon）：proot 和游戏被一起重开，
     * 挂机被打断，用户看到的就是「一改就重启游戏」。
     * PC 版其实不是这样：ALAS 每跑完一轮地图都会问一次「要不要换任务」
     * （module/campaign/run.py:470 → config.task_switched()），命中就把当前任务
     * 收尾，外层 loop 重读 config/alas.json 再重新派发，游戏停在地图界面不动。
     *
     * 移植版补了这一层（deploy/Termux/alas_hot.py）：改关卡会被自动识别，
     * 而其它参数靠这个标记文件把「请求换任务」传进去，同样是下一轮结束才切，
     * 全程不重启。
     */
    private void applyParams() {
        if (busy) {
            toast("上一个操作还没跑完，稍等");
            return;
        }
        busy = true;
        toast("正在通知调度器…");
        pool.execute(new Runnable() {
            public void run() {
                String run = TERMUX_BASH + " " + ALAS_DIR + "/_exec.sh " + HOT_INNER;
                // 用命令自己的 stdout 判断结果，文件只作兜底。
                // 早期版本这里 `su(run, 40L);` 把返回值直接丢了，只去 cat
                // alas-config.out —— 而 c_hot.sh 当时根本没写那个文件，
                // 于是读到的是上一次 c_config.sh 的旧内容，界面固定报「没写进标记文件」。
                final String direct = su(run, 40L);
                final String out = su("cat " + CFG_OUT, 15L);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        // stdout 优先，文件兜底；两边都没有才算真失败
                        String t = direct == null ? "" : direct.trim();
                        if (!t.contains("\"ok\"")) t = out == null ? "" : out.trim();
                        if (t.contains("\"ok\": true")) {
                            toast("已请求生效：下一轮地图跑完自动换图，不重启游戏");
                        } else {
                            tvLog.setText(scriptTail(t));
                            toast("没写进标记文件，日志已贴到控制台");
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void renderParams(String json) {
        paramListBox.removeAllViews();
        curBlock = null;
        if (json == null || !json.trim().startsWith("[")) {
            paramListBox.addView(hint("读不到配置。先在控制台点「开始」把 ALAS 拉起来，再回到这里。"));
            return;
        }

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            paramListBox.addView(hint("参数解析失败：" + brief(json)));
            return;
        }

        // 顶栏：改完参数想立刻生效就点这里，不用去控制台关一遍再开一遍
        paramListBox.addView(applyBar());

        // 关卡单独提一张卡放在最上面：它是改得最频繁的一项，
        // 而按任务块浏览那 1300 多项里它排得很靠后，很难找。
        paramListBox.addView(chapterCard(false));

        // 按 group 分段，每段一张 iOS 风格卡片
        String lastGroup = "";
        LinearLayout card = null;
        int rowsInCard = 0;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;

            String group = o.optString("group");
            String name = o.optString("name");
            String path = o.optString("path");
            String value = fmtValue(o.opt("value"), o.optString("type"));

            // 关卡已经在顶部那张卡上了，列表里不再重复一遍，
            // 免得同一个项出现两次、改完还让人以为有两处不同的值
            if ("Main.Campaign.Name".equals(path)) continue;

            if (!group.equals(lastGroup)) {
                if (card != null) paramListBox.addView(card);
                paramListBox.addView(groupTitle(group));
                card = card();
                lastGroup = group;
                rowsInCard = 0;
            }

            if (rowsInCard > 0) card.addView(separator());
            Object raw = o.opt("value");
            Boolean bv = (raw instanceof Boolean) ? (Boolean) raw : null;
            card.addView(paramRow(name, path, value, o.optJSONArray("options"), bv));
            rowsInCard++;
        }
        if (card != null) paramListBox.addView(card);

        paramListBox.addView(groupTitle("全部参数 · 按任务块浏览"));
        paramListBox.addView(hint("常用项只有 20 多个，官方配置里有 1300 多项。"
                + "点这里选一个任务块，可以编辑它下面的每一个参数。"));

        LinearLayout browseCard = card();
        TextView browse = new TextView(this);
        browse.setText("选择任务块…");
        browse.setTextColor(C_BLUE);
        browse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        browse.setPadding(dp(16), dp(13), dp(16), dp(13));
        browse.setBackground(ripple());
        browseCard.addView(browse);
        paramListBox.addView(browseCard);

        browse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { browseBlock(); }
        });

        paramLoaded = true;
        // 归零滚动位置，一进来就是「立即生效」和第一张卡
        paramScroll.scrollTo(0, 0);
    }

    /** 选一个任务块，把它下面所有参数列出来 */
    private void browseBlock() {
        final String[] blocks = {
                "Alas", "General", "Main", "Main2", "Main3", "Reward", "Commission", "Research",
                "Dorm", "Tactical", "Exercise", "Meowfficer", "Guild", "Hard", "Daily", "Event",
                "EventShop", "OpsiDaily", "OpsiExplore", "OpsiShop", "OpsiVoucher", "OpsiObscure",
                "OpsiAbyssal", "Restart", "Freebies", "Gacha", "Shipyard", "Awaken", "Raid",
                "Coalition", "WarArchives", "PrivateQuarters", "ShopFrequent", "ShopOnce",
                "IslandBusiness", "IslandCollect", "IslandOrder", "IslandProduction"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("选择任务块")
                .setItems(blocks, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        dumpBlock(blocks[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dumpBlock(final String block) {
        dumpBlock(block, false);
    }

    /** quiet=true：静默重读（写完值刷新用），不弹「读取 xx 的参数…」 */
    private void dumpBlock(final String block, final boolean quiet) {
        if (!quiet) toast("读取 " + zhName(block) + " 的参数…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("dump " + block);
                ui.post(new Runnable() {
                    public void run() { renderBlockParams(block, out); }
                });
            }
        });
    }

    private void renderBlockParams(String block, String json) {
        paramListBox.removeAllViews();
        curBlock = block;

        // 返回入口，必须有，不然进了这个列表就回不去
        LinearLayout backCard = card();
        TextView back = new TextView(this);
        back.setText("‹ 返回常用参数");
        back.setTextColor(C_BLUE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        back.setPadding(dp(16), dp(13), dp(16), dp(13));
        backCard.addView(back);
        paramListBox.addView(backCard);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                paramLoaded = false;
                curBlock = null;
                loadParams();
            }
        });

        if (json == null || !json.trim().startsWith("[")) {
            paramListBox.addView(hint("读不到参数：" + brief(json)));
            return;
        }

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            paramListBox.addView(hint("参数解析失败：" + brief(json)));
            return;
        }

        paramListBox.addView(groupTitle(zhName(block) + " · 共 " + arr.length() + " 项"));

        // 搜索框：一个块动辄 50 多项，靠肉眼滚到「打哪一关」很费劲。
        // 输入就即时过滤，命中不区分大小写，中文名和英文路径都能搜。
        EditText search = new EditText(this);
        search.setHint("搜索参数名或路径");
        search.setHintTextColor(0xFF636366);
        search.setTextColor(C_TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setBackground(round(C_CARD2, 10));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.leftMargin = dp(16);
        shlp.rightMargin = dp(16);
        shlp.topMargin = dp(6);
        shlp.bottomMargin = dp(2);
        paramListBox.addView(search, shlp);

        final LinearLayout card = card();
        int rows = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String path = o.optString("path");
            Object val = o.opt("value");
            String valStr;
            if (val instanceof Boolean) {
                valStr = ((Boolean) val) ? "开" : "关";
            } else if (val == null) {
                valStr = "null";
            } else {
                valStr = String.valueOf(val).replace("\n", " ");
            }
            if (valStr.length() > 34) valStr = valStr.substring(0, 32) + "…";

            // 只显示最后两段路径，前面一长串反而看不清
            String shortPath = path;
            int dot = path.lastIndexOf('.');
            if (dot > 0 && path.length() > 26) {
                int prev = path.lastIndexOf('.', dot - 1);
                shortPath = (prev > 0 ? path.substring(prev + 1) : path);
            }

            // 配置层给了中文名就用中文，没收录的才退回英文路径
            String zh = o.optString("zh");
            String label = (zh != null && zh.length() > 0) ? zh : shortPath;

            // 每行挂一份可搜索文本（中文名 + 短路径 + 完整路径），搜索时直接比对
            String searchText = (label + " " + shortPath + " " + path).toLowerCase();

            if (rows > 0) {
                View sep = separator();
                sep.setTag("S|" + searchText);
                card.addView(sep);
            }
            Boolean bv = (val instanceof Boolean) ? (Boolean) val : null;
            View rowView = paramRow(label, block + "." + path, valStr,
                    o.optJSONArray("options"), bv);
            rowView.setTag("R|" + searchText);
            card.addView(rowView);
            rows++;
        }
        paramListBox.addView(card);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }

            public void onTextChanged(CharSequence s, int st, int b, int c) { }

            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase();
                for (int i = 0; i < card.getChildCount(); i++) {
                    View ch = card.getChildAt(i);
                    Object tag = ch.getTag();
                    if (tag == null) continue;
                    String t = String.valueOf(tag);
                    // tag 形如 "R|…" / "S|…"，去掉前缀再比
                    boolean show = q.length() == 0 || t.substring(2).contains(q);
                    ch.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            }
        });

        // 重新画一页就把位置归零。否则从上一个列表滚到一半的位置会被沿用，
        // 一进来就停在中间，「返回常用参数」和第一行都看不见（真机踩过）。
        paramScroll.scrollTo(0, 0);
    }

    // ============================================== 更新

    private void loadUpdate() {
        updateLoaded = true;
        // 启动时自动查过一次且发现新版，直接把那份结果摆出来；
        // 否则还要再等一次容器往返才能看到内容
        if (pendingUpdate != null) {
            renderUpdate(pendingUpdate.toString(), true);
            return;
        }
        updateListBox.removeAllViews();
        updateListBox.addView(hint("读取更新状态…"));
        pool.execute(new Runnable() {
            public void run() {
                final String out = upd("status");
                ui.post(new Runnable() {
                    public void run() { renderUpdate(out, false); }
                });
            }
        });
    }

    /**
     * 检查更新。等价原版的 updater._check_update()：
     * git init（首次）+ git fetch --depth=1 + 比较远端最新 commit 与本地记录。
     */
    private void checkUpdate() {
        updateListBox.removeAllViews();
        updateListBox.addView(groupTitle("检查更新"));
        updateListBox.addView(hint("正在连 GitHub 拉取最新提交…\n第一次要在手机上浅拉一次仓库，"
                + "可能要一两分钟，别退出。"));
        toast("检查更新中…");

        pool.execute(new Runnable() {
            public void run() {
                final String out = upd("check");
                ui.post(new Runnable() {
                    public void run() { renderUpdate(out, true); }
                });
            }
        });
    }

    private void renderUpdate(String json, boolean afterCheck) {
        updateListBox.removeAllViews();
        updateLoaded = true;

        JSONObject o = null;
        if (json != null && json.trim().startsWith("{")) {
            try {
                o = new JSONObject(json.trim());
            } catch (Exception ignore) {
            }
        }

        if (o == null) {
            updateListBox.addView(groupTitle("更新"));
            updateListBox.addView(hint("读不到更新状态：" + brief(json)));
            updateListBox.addView(actionRow("重新读取", C_BLUE, new Runnable() {
                public void run() { loadUpdate(); }
            }));
            return;
        }

        if (!o.optBoolean("ok", false)) {
            updateListBox.addView(groupTitle("检查失败"));
            updateListBox.addView(hint(String.valueOf(o.optString("error", "未知错误"))));
            updateListBox.addView(actionRow("重新检查", C_BLUE, new Runnable() {
                public void run() { checkUpdate(); }
            }));
            return;
        }

        // ---- 本地已知版本
        String known = o.optString("known_sha", "");
        String knownTime = o.optString("known_time", "");
        String appliedAt = o.optString("applied_at", "");
        String checkedAt = o.optString("checked_at", "");

        updateListBox.addView(groupTitle("当前版本"));
        LinearLayout c1 = card();
        c1.addView(infoRow("本地记录的版本", known.isEmpty() ? "还没建立基点" : known.substring(0, Math.min(12, known.length())), C_TEXT));
        c1.addView(separator());
        c1.addView(infoRow("该版本时间", knownTime.isEmpty() ? "—" : knownTime, C_SUB));
        c1.addView(separator());
        c1.addView(infoRow("上次更新于", appliedAt.isEmpty() ? "—" : appliedAt, C_SUB));
        c1.addView(separator());
        c1.addView(infoRow("上次检查于", checkedAt.isEmpty() ? "—" : checkedAt, C_SUB));
        updateListBox.addView(c1);

        // ---- 检查结果
        JSONObject remote = o.optJSONObject("remote");
        if (afterCheck && remote != null) {
            boolean hasUpdate = o.optBoolean("has_update", false);
            boolean first = o.optBoolean("first", false);

            String sha = remote.optString("sha", "");
            String time = remote.optString("time", "");
            String msg = remote.optString("message", "");

            if (first || !hasUpdate) {
                updateListBox.addView(groupTitle(first ? "已建立更新基点" : "已是最新"));
                LinearLayout c2 = card();
                c2.addView(infoRow("远端最新提交", sha.length() > 12 ? sha.substring(0, 12) : sha, C_GREEN));
                c2.addView(separator());
                c2.addView(infoRow("提交时间", time, C_SUB));
                c2.addView(separator());
                c2.addView(infoRow("提交说明", msg, C_TEXT));
                updateListBox.addView(c2);
                if (first) {
                    updateListBox.addView(hint("手机上的 ALAS 是复制安装的、没有 git 历史，"
                            + "所以第一次只能把当前远端当成基准。之后再有新提交就会报出来。"));
                }
            } else {
                updateListBox.addView(groupTitle("发现新版本"));
                LinearLayout c3 = card();
                c3.addView(infoRow("远端最新提交", sha.length() > 12 ? sha.substring(0, 12) : sha, C_ORANGE));
                c3.addView(separator());
                c3.addView(infoRow("提交时间", time, C_SUB));
                c3.addView(separator());
                c3.addView(infoRow("提交说明", msg, C_TEXT));
                updateListBox.addView(c3);

                updateListBox.addView(actionRow("立即更新到最新", C_ORANGE, new Runnable() {
                    public void run() { confirmApply(); }
                }));
                updateListBox.addView(hint("更新会 git reset --hard 覆盖官方文件，"
                        + "deploy/Termux、deploy/AndroidGui、alas_cli.py、config/alas.json "
                        + "这几样移植层会先备份再放回，不会被冲掉。"
                        + "更新完建议回控制台点一次「结束」再「开始」。"));
            }
        }

        updateListBox.addView(actionRow("检查更新", C_BLUE, new Runnable() {
            public void run() { checkUpdate(); }
        }));

        // ---- 说明
        updateListBox.addView(groupTitle("说明"));
        updateListBox.addView(hint("这是原版启动时自动检查更新的手机移植版。"
                + "原版在 WebUI 里由 updater 定时比对 git 提交，这里搬到容器里跑。"
                + "区别只有一处：手机网络连不通 github.com 的 git 端口，"
                + "所以检查更新改走 GitHub API 读最新提交，"
                + "真正更新时下载源码快照覆盖，"
                + "deploy/Termux、deploy/AndroidGui、alas_cli.py、config/alas.json "
                + "这些移植层会自动保留。"));
        updateListBox.addView(hint("仓库：github.com/LmeSzinc/AzurLaneAutoScript · 分支 master"));
    }

    private void confirmApply() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("更新到最新版本？")
                .setMessage("会把官方文件整体替换成仓库最新提交，移植层自动保留。\n"
                        + "更新期间不要动手机，可能要几分钟。")
                .setPositiveButton("开始更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doApply(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doApply() {
        updateListBox.removeAllViews();
        updateListBox.addView(groupTitle("更新中"));
        updateListBox.addView(hint("正在备份移植层 → 拉取 → 覆盖官方文件 → 还原移植层…"));
        toast("更新中，别退出");
        pool.execute(new Runnable() {
            public void run() {
                final String out = upd("apply");
                ui.post(new Runnable() {
                    public void run() { renderApply(out); }
                });
            }
        });
    }

    private void renderApply(String json) {
        updateListBox.removeAllViews();
        JSONObject o = null;
        if (json != null && json.trim().startsWith("{")) {
            try {
                o = new JSONObject(json.trim());
            } catch (Exception ignore) {
            }
        }
        if (o == null) {
            updateListBox.addView(groupTitle("更新结果"));
            updateListBox.addView(hint("没读到结果：" + brief(json)));
            updateListBox.addView(actionRow("重新读取", C_BLUE, new Runnable() {
                public void run() { loadUpdate(); }
            }));
            return;
        }
        if (!o.optBoolean("ok", false)) {
            updateListBox.addView(groupTitle("更新失败"));
            updateListBox.addView(hint(String.valueOf(o.optString("error", "未知错误"))));
            updateListBox.addView(hint("移植层已经自动还原，当前状态应当可用。"));
            updateListBox.addView(actionRow("重新读取状态", C_BLUE, new Runnable() {
                public void run() { loadUpdate(); }
            }));
            return;
        }
        JSONObject applied = o.optJSONObject("applied");
        // 已经更新到最新了，橙色提示该收了，下次启动重新检查再说
        hasPendingUpdate = false;
        pendingUpdate = null;
        if (drawerLabel[TAB_UPDATE] != null) {
            drawerLabel[TAB_UPDATE].setTextColor(
                    curTab == TAB_UPDATE ? C_TEXT : C_SUB);
        }
        updateListBox.addView(groupTitle("更新完成"));
        LinearLayout c = card();
        if (applied != null) {
            String sha = applied.optString("sha", "");
            c.addView(infoRow("已更新到", sha.length() > 12 ? sha.substring(0, 12) : sha, C_GREEN));
            c.addView(separator());
            c.addView(infoRow("提交时间", applied.optString("time", ""), C_SUB));
            c.addView(separator());
            c.addView(infoRow("提交说明", applied.optString("message", ""), C_TEXT));
            c.addView(separator());
        }
        c.addView(infoRow("保留的移植层", brief(o.optString("kept", "")), C_SUB));
        updateListBox.addView(c);
        updateListBox.addView(hint("回控制台点「结束」再点「开始」，让容器用新代码重启一次。"));
        updateListBox.addView(actionRow("重新读取状态", C_BLUE, new Runnable() {
            public void run() { loadUpdate(); }
        }));
    }

    /** 更新页的「一行：左边标题，右边值」 */
    private View infoRow(String name, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView v = new TextView(this);
        v.setText(value == null ? "—" : value);
        v.setTextColor(valueColor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setGravity(Gravity.RIGHT);
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);

        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        return row;
    }

    /** 更新页里可点的动作行（蓝/橙色文字，居中偏左） */
    private View actionRow(String text, int color, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(round(C_CARD, 12));
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View paramRow(final String name, final String path, final String value,
                          final JSONArray options) {
        return paramRow(name, path, value, options, null);
    }

    /**
     * 参数行。
     *
     * boolValue 不为 null 时渲染成一个「开/关」胶囊，点一下直接取反并写盘 ——
     * 以前布尔项也弹输入框让用户输 true/false，既容易输错又难看清当前状态，
     * 而挂机开关这一类恰恰是最常点的，必须做成一点就切。
     */
    private View paramRow(final String name, final String path, final String value,
                          final JSONArray options, final Boolean boolValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple());
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        // 键名可能很长（Fleet.Fleet2Formation），折行会把行高撑乱，所以单行截断
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);

        if (boolValue != null) {
            final boolean cur = boolValue.booleanValue();
            final TextView pill = new TextView(this);
            pill.setText(cur ? "开" : "关");
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            pill.setTextColor(cur ? 0xFF30D158 : 0xFF8E8E93);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(5), dp(14), dp(5));
            pill.setBackgroundColor(cur ? 0x1F30D158 : 0x1FFFFFFF);
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 直接翻转：不用弹框，也不给"输错了"的机会
                    setParam(path, cur ? "false" : "true");
                }
            });

            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(pill);
            return row;
        }

        TextView v = new TextView(this);
        v.setText(enumZh(value));
        v.setTextColor(C_BLUE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setGravity(Gravity.RIGHT);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);

        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(C_SUB);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = dp(6);
        row.addView(arrow, al);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v2) { editParam(name, path, value, options); }
        });
        return row;
    }

    private void editParam(final String name, final String path, final String cur,
                           final JSONArray options) {
        // 「下次运行」是时间字段。裸文本框输入 2026-11-22 20:30:00 太难打，
        // 更要命的是打错了 ALAS 一声不吭：它只是永远不触发，用户看到的就是
        // 「定时没用」。这类字段改用专门的时间对话框，边输边校验。
        if (isTimePath(path)) {
            editTimeParam(name, path, cur);
            return;
        }
        // 枚举型字段给选择列表：手打 "fleet1_mob_fleet2_boss" 这种值太容易打错，
        // 打错了 set 会失败、用户还以为是 APP 坏了。「打哪一关」就走这条路，
        // 点一下就换图。
        if (options != null && options.length() > 0) {
            // raw 存英文原值（写回配置要用），items 存中文（给用户看）
            final String[] raw = new String[options.length()];
            final String[] items = new String[options.length()];
            int sel = -1;
            for (int i = 0; i < options.length(); i++) {
                raw[i] = options.optString(i);
                items[i] = enumZh(raw[i]);
                if (raw[i].equals(cur)) sel = i;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(name)
                    .setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            d.dismiss();
                            setParam(path, raw[which]);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final EditText et = new EditText(this);
        et.setText(cur);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_SUB);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setMessage(path)
                .setView(wrap)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setParam(path, et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 时间字段判定：目前只有「下次运行」，编辑时走时间对话框而不是裸文本框 */
    private static boolean isTimePath(String path) {
        return path != null && path.endsWith("Scheduler.NextRun");
    }

    /** 当前时间，格式和 ALAS 的 NextRun 一致 */
    private static String nowStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static int intOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /**
     * 把用户输入的时间规范化成 ALAS 认的 yyyy-MM-dd HH:mm:ss。
     *
     * 认这几种写法（全角冒号、斜杠、多空格都容忍）：
     *   20:30              今天 20:30
     *   20:30:00           同上，带秒
     *   11-22 20:30        今年 11 月 22 日
     *   2026-11-22 20:30   完整写法
     *
     * 返回 ""  = 用户清空了 → 语义是「立刻运行」
     * 返回 null = 格式不对 → 调用方必须拒绝写入，别把脏值丢给 ALAS
     * 这两种返回值是分开的，不能合并成 null。
     */
    private static String normTime(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replace('/', '-').replace('：', ':').replace('　', ' ');
        s = s.replaceAll("\\s+", " ");
        if (s.length() == 0) return "";

        String datePart = null;
        String timePart;
        String[] seg = s.split(" ");
        if (seg.length >= 2) {
            datePart = seg[0];
            timePart = seg[1];
        } else {
            timePart = seg[0];
        }

        String[] t = timePart.split(":");
        if (t.length < 2 || t.length > 3) return null;
        int hh = intOr(t[0], -1);
        int mm = intOr(t[1], -1);
        int ss = t.length == 3 ? intOr(t[2], -1) : 0;
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59 || ss < 0 || ss > 59) return null;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (datePart != null) {
            String[] d = datePart.split("-");
            if (d.length == 3) {
                int y = intOr(d[0], -1);
                int mo = intOr(d[1], -1);
                int da = intOr(d[2], -1);
                if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || da < 1 || da > 31) return null;
                cal.set(java.util.Calendar.YEAR, y);
                cal.set(java.util.Calendar.MONTH, mo - 1);
                cal.set(java.util.Calendar.DAY_OF_MONTH, da);
            } else if (d.length == 2) {
                int mo = intOr(d[0], -1);
                int da = intOr(d[1], -1);
                if (mo < 1 || mo > 12 || da < 1 || da > 31) return null;
                cal.set(java.util.Calendar.MONTH, mo - 1);
                cal.set(java.util.Calendar.DAY_OF_MONTH, da);
            } else {
                return null;
            }
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, hh);
        cal.set(java.util.Calendar.MINUTE, mm);
        cal.set(java.util.Calendar.SECOND, ss);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(cal.getTime());
    }

    /**
     * 「下次运行」编辑框。
     *
     * 和普通输入框的区别就三点：
     *   1. 边输边校验，格式不对时下面红字说明，点「保存」也不会关框；
     *   2. 多一个「立即运行」：清空并写入当前时间，ALAS 见时间已到就直接跑；
     *   3. 留空保存 = 立即运行，不再弹「值不能为空」。
     */
    private void editTimeParam(final String name, final String path, final String cur) {
        final EditText et = new EditText(this);
        et.setText(cur != null && !"null".equals(cur) ? cur : "");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_SUB);
        et.setHint("20:30 或 2026-11-22 20:30:00");
        // 时间本来就是一行，锁成单行；键盘右下角给一个「完成」，
        // 因为自动弹出的输入法会把对话框底部的「保存」按钮盖住（真机实测），
        // 输完直接按完成就等于保存，不用先把键盘收起来再腾出手点按钮。
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);

        final TextView tip = new TextView(this);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(dp(28), dp(6), dp(28), dp(2));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(tip);

        // 定时能不能生效，还取决于这个任务有没有进挂机队列。很多人只改时间、
        // 开关没开，然后觉得"定时没用"，所以把这句常驻提示放在框里。
        TextView note = new TextView(this);
        note.setText("前提：这个任务要在「任务」页启用，到点才会真的跑");
        note.setTextColor(C_SUB);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setPadding(dp(28), dp(2), dp(28), dp(8));
        wrap.addView(note);

        final Runnable check = new Runnable() {
            public void run() {
                String v = normTime(et.getText().toString());
                if (v == null) {
                    tip.setTextColor(0xFFFF453A);
                    tip.setText("格式不对 · 可写 20:30 / 11-22 20:30 / 2026-11-22 20:30:00");
                } else if (v.length() == 0) {
                    tip.setTextColor(C_SUB);
                    tip.setText("留空 = 立刻运行一次");
                } else {
                    tip.setTextColor(0xFF30D158);
                    tip.setText("将设为 " + v);
                }
            }
        };
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { check.run(); }
            public void afterTextChanged(Editable s) { }
        });
        check.run();

        final AlertDialog dlg = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setView(wrap)
                .setPositiveButton("保存", null)
                .setNeutralButton("立即运行", null)
                .setNegativeButton("取消", null)
                .create();

        final Runnable save = new Runnable() {
            public void run() {
                String t = normTime(et.getText().toString());
                if (t == null) {
                    toast("时间格式不对，没写入");
                    return;
                }
                dlg.dismiss();
                setParam(path, t);
            }
        };

        // 键盘上的「完成」= 保存。输入法一弹出来就把对话框底部的按钮盖住了，
        // 不接这个键的话，用户必须先把键盘收下去才点得到「保存」。
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    save.run();
                    return true;
                }
                return false;
            }
        });

        // 用自己的 OnClickListener 覆盖默认行为：默认点「保存」无条件关框，
        // 那样格式错了也只能关了重来，等于白校验。
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                // 弹框时不自动弹键盘（先让「保存 / 立即运行 / 取消」露出来），
                // 用户点输入框再弹；弹出后让窗口随键盘上移，按钮不会被顶到屏外。
                // 注意 d 是 DialogInterface，没有 getWindow()，必须走 dlg。
                if (dlg.getWindow() != null) {
                    dlg.getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
                }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            public void onClick(View v) { save.run(); }
                        });
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                        new View.OnClickListener() {
                            public void onClick(View v) {
                                dlg.dismiss();
                                // 传空值而不是 nowStr()：让 setParam 走「清空 = 立刻运行」
                                // 那条分支，提示语才能说清「是立刻，不是定时」。
                                setParam(path, "");
                            }
                        });
            }
        });
        dlg.show();
    }

    private void setParam(final String path, final String val) {
        String raw = val == null ? "" : val.trim();
        // 「清空」和「填了个时间」是两种意图：前者要立刻跑，后者要等到点。
        // 存下来只为了最后那一下 toast 说清楚，不影响写进去的值。
        final boolean immediate = isTimePath(path) && raw.length() == 0;
        final String trimmed;
        if (isTimePath(path)) {
            // 时间字段兜底：对话框里已经校验过一遍，这里再挡一次，
            // 免得别的入口（立即生效链路、以后加的快捷按钮）把脏值写进配置。
            // 清空 = 立即运行：写成当前时间，ALAS 判断「时间已到」就会执行。
            if (raw.length() == 0) {
                trimmed = nowStr();
            } else {
                String n = normTime(raw);
                if (n == null || n.length() == 0) {
                    toast("时间格式不对，没写入");
                    return;
                }
                trimmed = n;
            }
        } else {
            if (raw.length() == 0) {
                toast("值不能为空");
                return;
            }
            trimmed = raw;
        }
        toast("正在写入…");
        pool.execute(new Runnable() {
            public void run() {
                final String out = cfg("set " + path + " " + trimmed);
                // 「调度器在不在跑」在后台线程算好：countProcs() 要起一次 su，
                // 放到 ui 回调里做会把主线程卡住一两秒。
                final int procs = immediate ? countProcs() : 0;
                ui.post(new Runnable() {
                    public void run() {
                        if (out != null && out.contains("\"ok\"")) {
                            // 光说「已保存」会让人以为已经在跑了。ALAS 只在每轮任务
                            // 开始时读配置，所以必须提示还要点「立即生效」才真的换关卡。
                            // 时间字段不一样：它本来就是"到点才动"，套这句话会让人误解。
                            if (isTimePath(path)) {
                                if (immediate) {
                                    // 「立刻运行」还依赖调度器在跑，没跑就等于只写了个过去时间
                                    toast(procs > 0
                                            ? "已设为现在 · 调度器会马上接手"
                                            : "已设为现在 · 但调度器没在跑，回控制台点「开始」");
                                } else {
                                    toast("已设为 " + trimmed + " · 到点自动执行");
                                }
                            } else {
                                toast("已保存 · 想马上换图点顶部「立即生效」");
                            }
                        } else {
                            toast("保存失败：" + brief(out));
                        }
                        paramLoaded = false;
                        // 值改完必须重读一遍配置再画，不能只重画旧数据，
                        // 否则界面上还挂着旧值，看着就像「根本没写进去」。
                        // 如果正停在某个任务块的浏览页，就刷那一页，别把人踢回常用参数。
                        if (curBlock != null) {
                            dumpBlock(curBlock, true);
                        } else {
                            loadParams();
                        }
                    }
                });
            }
        });
    }

    /** 值展示：布尔转成开/关，其余原样，过长截断 */
    private String fmtValue(Object v, String type) {
        if (v == null) return "—";
        String s;
        if ("boolean".equals(type) || v instanceof Boolean) {
            s = ((Boolean) v) ? "开" : "关";
        } else {
            s = String.valueOf(v).replace("\n", " ");
        }
        return s.length() > 34 ? s.substring(0, 32) + "…" : s;
    }

    private String shortTime(String s) {
        if (s == null) return "";
        // 官方格式是 2020-01-01 00:00:00，只留时分
        if (s.length() >= 16 && s.contains(" ")) return s.substring(11, 16);
        return s;
    }

    private String brief(String s) {
        if (s == null) return "无输出";
        s = s.replace("\n", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 任务块英文名 → 中文。配置里的键是 Alas 的命令名，直接给用户看太生硬 */
    private String zhName(String block) {
        if ("Reward".equals(block)) return "日常收菜";
        if ("Commission".equals(block)) return "委托";
        if ("Tactical".equals(block)) return "战术教室";
        if ("Research".equals(block)) return "科研";
        if ("Dorm".equals(block)) return "宿舍";
        if ("Meowfficer".equals(block)) return "指挥喵";
        if ("Guild".equals(block)) return "大舰队";
        if ("Exercise".equals(block)) return "演习";
        if ("Daily".equals(block)) return "每日任务";
        if ("Hard".equals(block)) return "困难图";
        if ("Main".equals(block)) return "主线 1";
        if ("Main2".equals(block)) return "主线 2";
        if ("Main3".equals(block)) return "主线 3";
        if ("Restart".equals(block)) return "重启游戏";
        if ("Freebies".equals(block)) return "每日免费";
        if ("Awaken".equals(block)) return "觉醒";
        if ("Gacha".equals(block)) return "建造";
        if ("Shipyard".equals(block)) return "科研船";
        if ("Event".equals(block)) return "活动图";
        if ("EventA".equals(block)) return "活动 A 图";
        if ("EventB".equals(block)) return "活动 B 图";
        if ("EventC".equals(block)) return "活动 C 图";
        if ("EventD".equals(block)) return "活动 D 图";
        if ("EventSp".equals(block)) return "活动 SP 图";
        if ("Event2".equals(block)) return "活动图 2";
        if ("EventShop".equals(block)) return "活动商店";
        if ("Raid".equals(block)) return "共斗";
        if ("RaidDaily".equals(block)) return "共斗日常";
        if ("Coalition".equals(block)) return "塞壬战役";
        if ("CoalitionSp".equals(block)) return "塞壬 SP";
        if ("Hospital".equals(block)) return "医院";
        if ("WarArchives".equals(block)) return "作战档案";
        if ("Minigame".equals(block)) return "小游戏";
        if ("PrivateQuarters".equals(block)) return "宿舍信标";
        if ("ShopFrequent".equals(block)) return "商店常买";
        if ("ShopOnce".equals(block)) return "商店一次性";
        if ("GemsFarming".equals(block)) return "刷红尖尖";
        if ("OpsiDaily".equals(block)) return "大世界日常";
        if ("OpsiExplore".equals(block)) return "大世界探索";
        if ("OpsiShop".equals(block)) return "大世界商店";
        if ("OpsiVoucher".equals(block)) return "大世界兑换";
        if ("OpsiObscure".equals(block)) return "隐秘海域";
        if ("OpsiAbyssal".equals(block)) return "深渊";
        if ("OpsiArchive".equals(block)) return "大世界档案";
        if ("OpsiStronghold".equals(block)) return "要塞";
        if ("OpsiMonthBoss".equals(block)) return "月度 Boss";
        if ("OpsiAshBeacon".equals(block)) return "余烬信标";
        if ("OpsiAshAssist".equals(block)) return "余烬支援";
        if ("OpsiHazard1Leveling".equals(block)) return "侵蚀 1 练级";
        if ("OpsiMeowfficerFarming".equals(block)) return "大世界指挥喵";
        if ("OpsiCrossMonth".equals(block)) return "跨月大世界";
        if ("IslandBusiness".equals(block)) return "岛屿餐厅";
        if ("IslandCollect".equals(block)) return "岛屿采集";
        if ("IslandFreebie".equals(block)) return "岛屿免费";
        if ("IslandOrder".equals(block)) return "岛屿订单";
        if ("IslandProduction".equals(block)) return "岛屿生产";
        if ("IslandSeasonTask".equals(block)) return "岛屿季节任务";
        if ("IslandTransport".equals(block)) return "岛屿运输";
        return block;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, 14));
        return c;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_SUB);
        // 说明文字 12sp 太小，长时间读费眼；13sp + 1.25 倍行距
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLineSpacing(dp(3), 1.25f);
        t.setPadding(dp(16), dp(4), dp(16), dp(10));
        return t;
    }

    private Runnable poller = new Runnable() {
        public void run() {
            if (!busy) refresh();
            ui.postDelayed(this, 4000);
        }
    };

    // ================================================================= 状态

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        pool.execute(new Runnable() {
            public void run() {
                try {
                    final int procs = countProcs();
                    final boolean taskNow = taskRunningNow();
                    final String tail = tailLog(12);
                    ui.post(new Runnable() {
                        public void run() {
                            boolean was = lastRunning;
                            lastRunning = procs > 0;
                            taskRunning = taskNow;

                            if (taskRunning) {
                                tvDot.setTextColor(C_ORANGE);
                                txtState("任务运行中");
                                tvDetail.setText("ALAS 正在执行 · 屏幕此时是游戏画面");
                            } else if (lastRunning) {
                                tvDot.setTextColor(C_GREEN);
                                txtState("挂机运行中");
                                tvDetail.setText("容器进程 " + procs + " 个 · " + CONTAINER);
                            } else {
                                tvDot.setTextColor(C_SUB);
                                txtState("已停止");
                                tvDetail.setText("手机处于原生状态");
                            }

                            if (!was && lastRunning) webReady = false;

                            applyRunState(lastRunning || taskRunning);

                            if (tail != null) {
                                tvLog.setText(tail);
                                // 必须延后一点再滚：紧接着 setText 调 fullScroll 时
                                // TextView 还没完成测量，滚动会无效，日志停在中间。
                                logScroll.postDelayed(new Runnable() {
                                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                                }, 150);
                            }
                        }
                    });
                } catch (Throwable ignore) {
                } finally {
                    refreshing = false;
                }
            }
        });
    }

    private void showLogTail() {
        pool.execute(new Runnable() {
            public void run() {
                final String t = tailLog(14);
                if (t == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        tvLog.setText(t);
                        logScroll.post(new Runnable() {
                            public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                        });
                    }
                });
            }
        });
    }

    private int countProcs() {
        // 千万别再写成 `pgrep -f <容器名> | wc -l`。
        // pgrep -f 是按「完整命令行」匹配的，而执行它的 `su -M -c pgrep -f ubuntu-...`
        // 本身命令行里就带着容器名，于是永远至少匹配到自己 —— 实测 ALAS 已经全停干净了，
        // 这条还是返回 3，界面就一直挂着「挂机运行中」，看起来像启动没反应。
        // 改成 -x 精确匹配进程名：proot 是容器本身，python3.10 是容器里的 ALAS。
        String out = su("pgrep -x proot | wc -l; pgrep -x python3.10 | wc -l", 15L);
        if (out == null) return 0;
        int sum = 0;
        for (String s : out.trim().split("\\s+")) {
            try { sum += Integer.parseInt(s.trim()); } catch (Exception ignore) { }
        }
        if (sum > 0) return sum;

        // 进程数 0 就等于停了，不再用「日志 mtime 很新」来兜底。
        //
        // 这个兜底以前是必要的：那时 su() 走的是 App 自己的 mount namespace，
        // 里面看不到 Termux 起的那批进程，pgrep 全是 0，于是靠日志还在写来判活。
        // 现在 su() 固定用 `su -M`（master namespace），`run-alas.sh status` 实测
        // 能直接列出容器里的 python，进程数是可信的。
        //
        // 而兜底留着反而是个 bug：用户点「结束」、脚本刚写完日志的那一刻，
        // mtime 是「刚刚」，于是界面又把状态翻回「挂机运行中」—— 这就是
        // 「点了结束程序没结束」的观感来源之一。
        return 0;
    }

    private boolean taskRunningNow() {
        // 用 alas_task.sh 留下的标志文件判断，不用 pgrep：
        // pgrep -f 会把 `su -c pgrep` 自身和 Magisk 日志进程一起匹上，永远非零。
        //
        // 但只看文件在不在不够：alas_task.sh 是靠 trap 删标志的，被 -9 强杀
        // （或者点「结束」时容器被整体拔掉）trap 跑不到，就会留下一个孤儿文件。
        // 于是进程全没了，界面还挂「任务运行中」，用户看到的就是
        // 「点了结束还在跑」。所以再加一道交叉校验：真的有 python3.10 才算在跑，
        // 没 python 就把孤儿标志顺手删掉。
        String out = su("[ -f " + TASK_FLAG + " ] && { pgrep -x python3.10 >/dev/null 2>&1"
                + " && echo RUN || { rm -f " + TASK_FLAG + "; echo IDLE; }; } || echo IDLE", 12L);
        return out != null && out.contains("RUN");
    }

    private String tailLog(int n) {
        // 顺序必须是「任务日志优先」还是「调度日志优先」，这里踩过一次：
        // 原来是先读 alas-task.log，只要它有内容就直接返回 —— 但 alas-task.log
        // 里一直留着上次手动跑任务的老内容（16:57 那次 reward 的记录），
        // 于是点「开始」之后 ALAS 在容器里明明已经开始跑调度了，
        // 界面日志区却永远停在那份旧记录上，看起来像「什么都没发生、用不了」。
        // 现在反过来：只有任务真在跑（alas_task.sh 的标志文件存在）才看任务日志，
        // 否则一律看 alas-daemon.log —— 那才是调度器实时在写的那份。
        if (taskRunningNow()) {
            String taskOut = su("tail -" + n + " " + TASK_LOG, 15L);
            if (taskOut != null) {
                String t = stripAnsi(taskOut.trim());
                if (t.length() > 0 && !t.contains("No such file")) return t;
            }
        }
        return tailDaemonLog(n);
    }

    private String tailDaemonLog(int n) {
        // 读日志踩过的坑，留档：
        //   1) su -c "tail <Termux私有目录>"        → 空。App 的 mount namespace 里看不到 Termux
        //   2) su -c "... 2>/dev/null"             → Magisk 把整段输出一起吞掉
        //   3) su -c "run-as com.termux ..."       → 报 /data/user has wrong owner，root 下不能用
        //   4) cp 到本 App 私有目录                  → Permission denied（SELinux 双向隔离）
        // 最终依赖 su 的 --mount-master（见 su() 方法），之后直接 tail 即可。
        String out = su("tail -" + n + " " + LOG, 15L);
        if (out == null) return "（读不到日志：root 未授权）";
        out = stripAnsi(out.trim());
        if (out.length() == 0 || out.contains("No such file")) return "（暂无日志）";
        return out;
    }

    /** 读取本地文件末尾 n 行 */
    private String readTail(String path, int n, String empty) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            LinkedList<String> buf = new LinkedList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                buf.addLast(line);
                if (buf.size() > n) buf.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String s : buf) sb.append(s).append('\n');
            String t = stripAnsi(sb.toString().trim());
            return t.length() == 0 ? empty : t;
        } catch (Exception e) {
            return empty;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ================================================================= root

    private String su(String cmd, long timeoutSec) {
        Process p = null;
        lastExit = -1;
        try {
            // -M / --mount-master 是必须的。
            // App 进程有自己的 mount namespace，里面看不到 /data/data/com.termux，
            // 直接 su 会报 "No such file or directory"；切到 master namespace 才能访问 Termux。
            ProcessBuilder pb = new ProcessBuilder("su", "-M", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();

            final Process fp = p;
            final StringBuilder sb = new StringBuilder();

            // 必须开独立线程读 stdout。
            // 之前是主线程 while(readLine) 读完再 waitFor，结果超时形同虚设：
            // start-game.sh 用 nohup 起了常驻 proot，只要有后代继承了这个管道，
            // readLine 就永远等不到 EOF，worker 线程卡死、busy 永远不复位，
            // 界面就一直停在「启动中」。改成先让读线程自己跑，主线程只管 waitFor。
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(new InputStreamReader(fp.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Exception ignore) {
                    } finally {
                        try { if (r != null) r.close(); } catch (Exception ignore) { }
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return null;
            }
            lastExit = p.exitValue();
            reader.join(2000);  // 给读线程一点时间把尾巴收完，收不到也不影响结果
            return sb.toString();
        } catch (Exception e) {
            if (p != null) p.destroy();
            return null;
        }
    }

    /** 判断脚本输出是否像"跑成功"，用于 start 这种带后台启动的长命令 */
    private static boolean looksOk(String out) {
        if (out == null || out.length() == 0) return false;
        if (out.contains("准备失败") || out.contains("!!! 还没起来")) return false;
        return out.contains("完成") || out.contains("HTTP=200");
    }

    /** 把脚本输出落盘到 /data/data/<pkg>/files/conf/ 下，出问题能直接 adb pull 出来看 */
    private void writeOut(String name, String out, int exit) {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "conf");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, name);
            String head = "# exit=" + exit + "\n";
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write((head + (out == null ? "(null)" : out)).getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignore) { }
    }

    /** 脚本输出的尾部，用来贴到日志区给人看 */
    private static String scriptTail(String out) {
        if (out == null) return "(无输出)";
        String t = stripAnsi(out).trim();
        if (t.length() > 4000) t = "…(前面省略)…\n" + t.substring(t.length() - 4000);
        return t;
    }

    // ================================================================= 杂项

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
    }

    private void txtState(String s) { tvState.setText("● " + s); }

    /**
     * 让「开始 / 结束」跟着运行状态走。
     *
     * 之前两个按钮永远是同一副样子：ALAS 明明在跑，「开始」还是亮绿实心，点下去
     * 只是重跑一遍启动脚本；而「结束」看着像正常可用，其实没在跑时点了毫无意义。
     * 这就是典型的不人性化 —— 界面上该只有「此刻能做的事」是亮的。
     * 现在：运行时「开始」压成灰、不可点，「结束」变成红色可点；停止时反过来。
     */
    private void applyRunState(boolean running) {
        if (btnStart == null || btnStop == null) return;
        if (running) {
            btnStart.setBackground(round(C_CARD2, 12));
            btnStart.setTextColor(0x59FFFFFF);
            btnStart.setEnabled(false);
            btnStop.setBackground(round(0xFFE5484D, 12));
            btnStop.setTextColor(Color.WHITE);
            btnStop.setEnabled(true);
        } else {
            btnStart.setBackground(round(C_GREEN, 12));
            btnStart.setTextColor(Color.WHITE);
            btnStart.setEnabled(true);
            btnStop.setBackground(roundOutline(0x00000000, 0x33FFFFFF, 12));
            btnStop.setTextColor(0x66FFFFFF);
            btnStop.setEnabled(false);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 圆角 + 1dp 描边：次级按钮用，底色给透明就是描边按钮 */
    private GradientDrawable roundOutline(int fill, int stroke, int radiusDp) {
        GradientDrawable d = round(fill, radiusDp);
        d.setStroke(dp(1), stroke);
        return d;
    }

    /**
     * 可点行的按压反馈：手指按下时从触点扩散出一圈浅白光。
     *
     * 之前所有列表行都只有 setOnClickListener、背景全透明，点了没有任何视觉反馈 ——
     * 用户根本判断不出「我到底点中了没」，这是最容易让人觉得难用的地方。
     * 用 framework 自带的 RippleDrawable（API 21+），不依赖 AndroidX。
     *
     * mask 传 null：水波铺满整个行区域（行本身就在圆角卡片里，不需要再裁）。
     */
    private Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null);
    }

    /**
     * 抽屉条目用的按压反馈。
     *
     * 和 ripple() 的区别是这里带圆角裁剪：条目选中时本身有 10dp 圆角底纹，
     * 水波必须裁在同一个圆角里，否则矩形水波会从圆角外溢出去。
     * mask 用不透明白色 —— 它只当裁剪形状用，颜色不参与绘制。
     */
    private Drawable rippleRounded(int contentColor, int radiusDp) {
        Drawable content = contentColor == 0 ? null : round(contentColor, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                content, round(0xFFFFFFFF, radiusDp));
    }
}
