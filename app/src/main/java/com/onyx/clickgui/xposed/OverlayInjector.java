package com.onyx.clickgui.xposed;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.onyx.clickgui.feature.FeatureBus;
import com.onyx.clickgui.gui.ClickGUI;

import java.lang.ref.WeakReference;

/**
 * 把 ClickGUI 挂到游戏 DecorView 上。
 *
 * 这套挂载逻辑对齐 D:\C盘清理\SiyoX-1.0.3（同目标包名，UI 实测能显示）：
 *   ① 不挑 View 类型 —— 只要 DecorView 在就挂。
 *   ② onMeasure 由 ClickGUI 量成真实尺寸，避免量成 0。
 *   ③ 生命周期全钩 + watchdog 补挂，视图树被重建也不会丢。
 *   ④ 先装 UI，后拉 native，全程 try/catch —— UI 绝不被 native 拖累。
 *
 * 本版新增的**可判定性**（关键）：
 *   · 每一步都写 Diag 落盘日志（/sdcard/Android/data/com.netease.x19/files/onyx.log）
 *   · ClickGUI 构造失败 → 挂 FallbackView（纯自绘红条），屏幕上一定看得见东西
 *   · 挂载成功 → 自绘通知报出 View 的真实尺寸，证明"活着且有多大"
 *   · View 尺寸长时间为 0 → 写日志，区分"没挂上"和"挂了但量成 0"
 */
public final class OverlayInjector {

    private static final String VIEW_TAG = "ONYX_IN_GAME_OVERLAY";
    private static final String FALLBACK_TAG = "ONYX_FALLBACK";

    private static final OverlayInjector INSTANCE = new OverlayInjector();

    public static OverlayInjector get() { return INSTANCE; }

    private OverlayInjector() {}

    private final Object LOCK = new Object();
    private volatile Handler uiHandler;
    private volatile View gui;
    private volatile WeakReference<Activity> hostRef = new WeakReference<>(null);
    private volatile String attachedTo = "";
    private volatile boolean watchdogOn;
    private volatile boolean fbShown;
    /** 尺寸连续为 0 的次数（判断"挂了但没量出尺寸"） */
    private volatile int zeroSizeTicks;

    /**
     * 最近一次出现在生命周期回调里的 Activity。
     * ★★ 2.4.11：**必须比 hostRef 更靠前** —— hostRef 只在"挂载成功"后才更新，
     *   而 watchdog 要在"还没挂上"的时候也能重试，所以它记的是"见到过的最近一个"。
     */
    private volatile WeakReference<Activity> lastRef = new WeakReference<>(null);

    /** 可见性审计上一条输出 —— 只在内容变化时才写日志，避免刷屏 */
    private volatile String lastAudit = "";

    /** Scope 判定"还不是目标"的连续次数（= libminecraftpe.so 还没 map 进来） */
    private volatile int scopeMiss;

    /** 版本横幅只打一次 */
    private volatile boolean bannerDone;

    /** 「可见但不可见」的自救次数上限（防止反复 remove/add 引起闪动） */
    private volatile int visFixTries;

    private Handler ui() {
        Handler h = uiHandler;
        if (h != null) return h;
        synchronized (LOCK) {
            if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
            return uiHandler;
        }
    }

    // ═════════════════════════ 对外入口 ═════════════════════════

    /**
     * 启动后的静默期：在这之前一律不往游戏视图树里加东西。
     *
     * ★★ 2.4.10 新增。本类是挂在 onCreate / onPostCreate / onResume /
     *   onWindowFocusChanged **四个**生命周期点上的，也就是说"Activity 一出来就立刻挂"。
     *   而 Activity 刚出来那几秒正是游戏加载最凶的窗口：登录、资源解包、纹理上传、
     *   libminecraftpe 各种初始化全挤在一起。此时往 DecorView 塞一个全屏 View，会同时触发：
     *     ① RenderThread 立刻多要一层全屏 layer + 一块 buffer（显存最紧的时刻）；
     *     ② 游戏自己 SurfaceView 的重新布局（EGL surface 可能被重建）；
     *     ③ new ClickGUI 的全部开销（读 SharedPreferences + 建 ~40 个模块）压在主线程。
     *   用户实测的两个现象都指向这里：
     *     ·「游戏加载了一会直接闪退」= ① ② 在加载高峰叠加；
     *     ·「加载时点面板更容易立刻闪退」= 又叠了一次全屏重绘，同一时刻再压一份。
     *   推迟到启动高峰过去再挂，这三点同时消失，代价只是面板晚几秒出现。
     *
     * ★★ 2.4.12：**彻底取消静默期（= 0）**。
     *   用户第三次反馈"注入后不显示 UI"，并明确要求"改一下直接加载 UI"。
     *   复盘结论：那三点风险是真实的，但**用"什么都不挂"去规避是错的解法** ——
     *   它把全部代价转嫁给了用户（看不到界面 = 以为模块坏了），而收益只是
     *   "少挂几秒"。何况现在已经有三条独立的降载手段撑着启动期：
     *     ① 覆盖层按需重绘（needNextFrame，空闲退到 100ms 心跳）
     *     ② native 扫描线程逐级退让（400→4000ms）
     *     ③ inline hook 等 .so 稳定 1.5s 再装
     *   这三条已经覆盖了静默期想解决的全部问题，再叠一层"晚挂"纯属多余。
     *   ⇒ Activity 一出现就挂，不做任何延迟。
     */
    private static final long STARTUP_QUIET_MS = 0L;

    /** 首次 attach 的墙钟时间（进程级，不随 Activity 重建清零） */
    private static volatile long firstAttachMs;

    public void attach(final Activity a) {
        if (a == null) return;
        try {
            Diag.init(a);
            // ★ 2.4.11：日志开头打一次**版本号**。
            //   上次排查最坑的一点就是"拿到的 onyx.log 看不出是哪个版本跑的"
            //   （2.4.9 和 2.4.10 的行为差异很大），白白浪费一轮来回。
            if (!bannerDone) {
                bannerDone = true;
                Diag.log("════════ Onyx " + FeatureBus.VERSION + " 注入 " + a.getPackageName());
            }
            // ★ 2.4.11：无条件记住"最近见到的 Activity"。watchdog 靠它重试，
            //   所以必须在 Scope 判定**之前**就存下来（判失败的那个也照样存）。
            lastRef = new WeakReference<>(a);
            if (firstAttachMs == 0) firstAttachMs = System.currentTimeMillis();
            // ★★ 2.4.11：watchdog **无条件启动**，不再等"挂载成功"。
            //   原设计的死穴见 startWatchdog() 的注释 —— 它是"UI 一定会出现"的唯一保险。
            startWatchdog();
            // ★★ 2.4.11：**不再 postDelayed(静默期)**。
            //   原来延迟 5 秒再调用 attachNow(a,0)：延迟期间没有任何重试，
            //   到点执行时那个 Activity 很可能已经销毁 → attachNow 直接 return，
            //   而且不会有人再试 → **UI 永远不出现**（这就是本次报障的直接原因）。
            //   静默期判断挪进 attachNow，并且走 retry() 重试链，Activity 换了也能自动跟。
            ui().post(() -> attachNow(a, 0));
        } catch (Throwable t) {
            Diag.log("attach post 失败", t);
        }
    }

    public void onActivityDestroyed(Activity a) {
        Activity host = hostRef.get();
        if (host == a) {
            Diag.log("Activity 销毁 → 清引用 " + a.getClass().getName());
            hostRef = new WeakReference<>(null);
            attachedTo = "";
            gui = null;
            zeroSizeTicks = 0;
            FeatureBus.get().unbind();
        }
    }

    public ClickGUI view() { return (gui instanceof ClickGUI) ? (ClickGUI) gui : null; }

    /** 视图树被重建（切分辨率 / 分屏）→ 主动重挂一次 */
    public void onConfigChanged(Activity a) {
        if (a == null) return;
        Diag.log("onConfigurationChanged → 重挂");
        ui().post(() -> attachNow(a, 0));
    }

    // ═════════════════════════ 核心 ═════════════════════════

    private void attachNow(final Activity a, final int attempt) {
        if (a == null || a.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return;

        // ★ 2.4.12：静默期已取消（STARTUP_QUIET_MS = 0）—— 直接往下挂。
        if (STARTUP_QUIET_MS > 0
                && System.currentTimeMillis() - firstAttachMs < STARTUP_QUIET_MS) {
            retry(a, attempt);
            return;
        }

        // ★★★ 2.4.11：Scope 判定失败**必须重试，不能直接放弃**。
        //
        //   `Scope.isTargetActivity()` 对"改过包名的客户端"只能靠 `hasMcSo()`
        //   ——扫 /proc/self/maps 找 libminecraftpe.so。而游戏冷启动时，
        //   **libminecraftpe.so 往往比第一个 Activity 晚几百毫秒才 map 进来**：
        //   那一刻判 false，旧代码直接 return；如果这之后恰好没有新的 Activity
        //   生命周期回调（网易壳的启动页很常见就这么一个），就**再也没人调 attach**
        //   → 屏幕上永远什么都没有。
        //
        //   实测日志里的证据：连续 3 行 `跳过（非目标进程）com.neteasb.x67`
        //   —— 那个包名就是宿主自己，不是"非目标"，纯粹是 .so 还没加载完。
        //   改成递增间隔重试（最多 40 次 ≈ 20 秒），不占用 attempt 的重试预算。
        if (!Scope.isTargetActivity(a)) {
            if (scopeMiss == 0) {
                Diag.log("awaitScope：Activity 已就绪，但 libminecraftpe.so 还没 map → 持续重试");
            }
            if (++scopeMiss <= 40) {
                ui().postDelayed(() -> attachNow(a, attempt), scopeMiss < 5 ? 200L : 500L);
            } else {
                Diag.log("awaitScope：重试 40 次仍未等到 .so，放弃本次（等下一个 Activity）");
            }
            return;
        }
        scopeMiss = 0;

        try {
            Window win = a.getWindow();
            if (win == null) { retry(a, attempt); return; }

            // 刘海屏：允许内容铺到挖孔区
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    WindowManager.LayoutParams wlp = win.getAttributes();
                    wlp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    win.setAttributes(wlp);
                } catch (Throwable ignored) {
                }
            }

            View decorV = win.getDecorView();
            if (!(decorV instanceof ViewGroup)) {
                if (attempt == 0) Diag.log("DecorView 还不是 ViewGroup：" + decorV);
                retry(a, attempt);
                return;
            }
            ViewGroup decor = (ViewGroup) decorV;

            // ★ 2.4.10：DecorView 还没有尺寸 = 游戏这一轮布局还没跑完。
            //   这时候 addView 进去，会被卷进它正在进行的那次 measure/layout，
            //   而且我们的 onMeasure/onDraw 会在一个"半成品"的视图树上跑。
            //   等它稳定了再说（给足 30 次 ≈ 6 秒的重试额度，之后不再拦，
            //   否则某些把内容全交给 SurfaceView 的壳会让 DecorView 长期为 0）。
            if ((decor.getWidth() <= 0 || decor.getHeight() <= 0) && attempt < 30) {
                if (attempt == 0) Diag.log("DecorView 还没量出尺寸 → 等这轮布局跑完");
                retry(a, attempt);
                return;
            }

            // ★ 2.4.10：**不再改 DecorView / content 的任何属性**。
            //   原来这里动 setClipChildren / setClipToPadding / setPadding(0,0,0,0)，
            //   等于替游戏改它自己的视图树 —— 注入进程里这是纯粹的越界：
            //   网易壳的启动页/加载页依赖自己的 padding，被清零后会整屏重排；
            //   而游戏自己的 SurfaceView 一旦被牵连重布局，GL 线程就会在
            //   surfaceChanged 里重建 EGL surface，加载期干这件事风险极高。
            //   我们的 View 是 MATCH_PARENT、内容也已被 layoutAll 夹在屏幕内，
            //   根本不需要父容器开 clip=false。收益为零、风险不小，整段删掉。

            // 已经挂过 → 只认领 + 重绑，**不再 bringToFront()**
            //
            // ★★ 2.4.11：这里原来是 `existing.bringToFront()`。而本方法被 onCreate /
            //   onPostCreate / onResume / onWindowFocusChanged 四个点触发，
            //   实测一次冷启动会连续调 11 次 bringToFront —— 每次都会让
            //   DecorView 走一遍 requestLayout + invalidate，等于在游戏加载期
            //   反复逼它重排自己的视图树（SurfaceView 也跟着重排）。
            //   addView 本来就是"加到最上层"，之后只有掉出视图树才需要重新 add；
            //   平时没必要抢 z-order。真正的保险交给 watchdog。
            View existing = decor.findViewWithTag(VIEW_TAG);
            if (existing instanceof ClickGUI) {
                final boolean parentLost = (existing.getParent() == null);
                gui = existing;
                hostRef = new WeakReference<>(a);
                attachedTo = a.getClass().getName();
                FeatureBus.get().bind((ClickGUI) existing);
                Diag.log("已挂过（尺寸 " + existing.getWidth()
                        + "x" + existing.getHeight() + " 可见="
                        + (existing.getVisibility() == View.VISIBLE)
                        + (parentLost ? " 父=null→补挂" : "") + "）");
                if (parentLost) decor.addView(existing, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }
            if (existing != null) decor.removeView(existing);

            // ── 建 View：失败就顶上 FallbackView，保证屏幕一定有东西 ──
            View v;
            try {
                ClickGUI g = new ClickGUI(a);
                g.setTag(VIEW_TAG);
                g.setOverlayMode(true);
                try { g.setElevation(100f); } catch (Throwable ignored) { }
                v = g;
                Diag.log("ClickGUI 构造成功");
            } catch (Throwable t) {
                Diag.log("ClickGUI 构造抛异常 → 改挂 FallbackView", t);
                v = new FallbackView(a, "ClickGUI 构造失败：" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : (" " + t.getMessage())));
                v.setTag(FALLBACK_TAG);
                fbShown = true;
            }

            // addView：LayoutParams 类型不兼容时回退到通用类型，绝不因此挂不上
            try {
                decor.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t1) {
                Diag.log("addView(FrameLayout.LayoutParams) 失败，回退通用类型", t1);
                decor.addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            v.setVisibility(View.VISIBLE);
            v.bringToFront();
            decor.requestLayout();

            gui = v;
            hostRef = new WeakReference<>(a);
            attachedTo = a.getClass().getName();

            Diag.log("overlay ATTACHED → " + attachedTo
                    + " decor=" + decor.getClass().getName()
                    + " decorSize=" + decor.getWidth() + "x" + decor.getHeight()
                    + " attempt=" + attempt);

            if (v instanceof ClickGUI) {
                FeatureBus.get().bind((ClickGUI) v);
                probeLater(a);
            }
            startWatchdog();

        } catch (Throwable t) {
            Diag.log("overlay attach 失败 (attempt " + attempt + ")", t);
            retry(a, attempt);
        }
    }

    /**
     * 挂上 1.2 秒后报一次真实尺寸。
     * 这是**屏幕上可见的证据**：能看到这条通知 → View 活着、且尺寸是多少一目了然。
     */
    private void probeLater(final Activity a) {
        ui().postDelayed(() -> {
            try {
                View v = gui;
                if (!(v instanceof ClickGUI)) return;
                ClickGUI g = (ClickGUI) v;
                String msg = v.getWidth() + "x" + v.getHeight()
                        + (v.getWidth() < 2 ? "  ← 尺寸为 0，量测有问题" : "");
                if (!fbShown) g.toastOk("Onyx " + FeatureBus.VERSION + " 已挂载", msg);
                Diag.log("探针：view=" + msg + " 可见=" + (v.getVisibility() == View.VISIBLE)
                        + " 父=" + (v.getParent() != null));
            } catch (Throwable t) {
                Diag.log("探针失败", t);
            }
        }, 1200);
    }

    /** 前 10 次 200ms 快重试，之后 1s 低频兜底 */
    private void retry(final Activity a, final int attempt) {
        if (attempt > 120) {
            Diag.log("重试 120 次仍未挂上 → 上降级探针。attachedTo=" + attachedTo);
            forceFallback(a, "挂载重试 120 次仍未成功（DecorView 拿不到）");
            return;
        }
        long delay = attempt < 10 ? 200L : 1000L;
        try {
            ui().postDelayed(() -> attachNow(a, attempt + 1), delay);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 最后一道保险：撤掉 ClickGUI，换 FallbackView。
     * 保证任何情况下屏幕上**一定**有一块能看见的东西 —— 空白屏幕是最没信息量的状态。
     */
    private void forceFallback(final Activity a, final String why) {
        try {
            View decorV = a.getWindow().getDecorView();
            if (!(decorV instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorV;

            View old = decor.findViewWithTag(VIEW_TAG);
            if (old != null) decor.removeView(old);
            if (decor.findViewWithTag(FALLBACK_TAG) != null) return;

            FallbackView fv = new FallbackView(a, why);
            fv.setTag(FALLBACK_TAG);
            try {
                decor.addView(fv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) {
                decor.addView(fv, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            fv.bringToFront();
            gui = fv;
            fbShown = true;
            Diag.log("已降级为 FallbackView：" + why);
        } catch (Throwable t) {
            Diag.log("forceFallback 失败", t);
        }
    }

    /** watchdog 周期 */
    private static final long WATCHDOG_MS = 2000L;

    /**
     * watchdog —— 常驻健康检查 + **唯一的重挂推手**。
     *
     * ★★ 2.4.11：改成**无论有没有挂上都启动**（原来只在挂载成功后才 startWatchdog）。
     *   原设计有个死穴：如果第一次挂载就失败（最常见的原因是那一刻
     *   `libminecraftpe.so` 还没 map 进来，Scope 判"非目标"），watchdog 根本没启动，
     *   后面又恰好没有新的 Activity 生命周期回调 → **UI 永远不出现**。
     *   现在它是"UI 一定会出现"的那道保险：
     *     · 每 2 秒一次，用 lastRef（最近见到的 Activity）持续重试挂载；
     *     · 挂上了但掉出视图树 → 补挂；
     *     · 挂上了且正常 → 只做**可见性审计**，不再无脑 bringToFront。
     */
    private void startWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        ui().postDelayed(new Runnable() {
            @Override
            public void run() {
                try { watchdogTick(); } catch (Throwable t) { Diag.log("watchdog", t); }
                ui().postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private void watchdogTick() {
        final Activity host = lastRef.get();
        if (host == null || host.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && host.isDestroyed()) return;

        final View v = gui;
        if (v == null || v.getParent() == null) {
            // 还没挂上（或掉出视图树）→ 推一把。这是唯一会主动 addView 的地方。
            attachNow(host, 0);
            return;
        }

        auditVisibility(host, v);

        if (v.getWidth() < 2 || v.getHeight() < 2) {
            if (++zeroSizeTicks == 3) {
                Diag.log("watchdog：连续 3 次尺寸为 0 → 先 requestLayout 再重挂");
                try { v.requestLayout(); } catch (Throwable ignored) { }
                attachNow(host, 0);
            } else if (zeroSizeTicks >= 8) {
                zeroSizeTicks = 0;
                forceFallback(host, "ClickGUI 已挂上但尺寸一直是 0（量测失败）");
            }
        } else {
            zeroSizeTicks = 0;
        }
    }

    /**
     * ★★★ 2.4.11 新增：可见性审计。
     *
     * 「挂上了、onDraw 在跑、尺寸也对，但屏幕上看不到」这种情况，
     * 光看现有日志是**分辨不出来的** —— 必须问系统三个问题：
     *   ① `getGlobalVisibleRect()` —— 它有没有一块真正落在屏幕可见区？
     *      （方法内部会考虑所有祖先的裁剪与可见性；返回 false = 被裁掉/祖先不可见）
     *   ② 在 DecorView 里排第几个（index/childCount）？
     *      不是最后几个 ⇒ 被别的 View 盖在上面了。
     *   ③ `isAttachedToWindow()` + `getAlpha()` —— 真接进窗口了吗、透明度正常吗。
     * 只在结果变化时写一行，不会刷屏。
     */
    private void auditVisibility(final Activity host, final View v) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            final boolean vis = v.getGlobalVisibleRect(r);
            int idx = -1, cnt = -1;
            final View decorV = (host.getWindow() == null) ? null : host.getWindow().getDecorView();
            if (decorV instanceof ViewGroup) {
                idx = ((ViewGroup) decorV).indexOfChild(v);
                cnt = ((ViewGroup) decorV).getChildCount();
            }
            final String line = "audit vis=" + vis
                    + " rect=" + r.left + "," + r.top + "," + r.right + "," + r.bottom
                    + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " alpha=" + v.getAlpha()
                    + " z=" + idx + "/" + cnt
                    + " attached=" + v.isAttachedToWindow();
            if (!line.equals(lastAudit)) {
                lastAudit = line;
                Diag.log(line);
            }

            // ★★ 2.4.12：真的"看不见"就自救。
            //   判据必须严，否则会误伤正常情况：
            //     · `!vis`            —— getGlobalVisibleRect 完全落不到可见区
            //     · `host.hasWindowFocus()` —— 排除"游戏切到后台"这种正常的不可见
            //     · `v.isShown()`     —— 排除被 setVisibility(GONE) 的情况
            //   三条同时成立才动手：remove + addView 到父容器末尾 + bringToFront。
            if (!vis && host.hasWindowFocus() && v.isShown() && visFixTries < 3) {
                visFixTries++;
                Diag.log("audit：有焦点、isShown、但 getGlobalVisibleRect=false"
                        + " → 重新 addView 抢救（第 " + visFixTries + " 次）");
                try {
                    final ViewGroup p = (v.getParent() instanceof ViewGroup)
                            ? (ViewGroup) v.getParent() : null;
                    if (p != null) {
                        p.removeView(v);
                        p.addView(v, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        v.bringToFront();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ═════════════════════════ 降级探针 ═════════════════════════

    /**
     * 兜底 View —— 只在 ClickGUI 构造失败时使用。
     * 故意写得极简：不引用任何模型/主题/资源，只画一块红底白字。
     * 它的职责只有一个：**证明挂载链路是通的**，把问题钉死在"绘制层"而不是"挂载层"。
     */
    static final class FallbackView extends View {

        private final String text;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        FallbackView(Context c, String text) {
            super(c);
            this.text = text;
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = MeasureSpec.getSize(hSpec);
            if (w <= 0 || h <= 0) {
                w = getResources().getDisplayMetrics().widthPixels;
                h = getResources().getDisplayMetrics().heightPixels;
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            try {
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(230, 190, 20, 30));
                cv.drawRect(0, 0, getWidth(), 140f, p);
                p.setColor(Color.WHITE);
                p.setTextSize(20f);
                p.setTextAlign(Paint.Align.LEFT);
                cv.drawText("Onyx 挂载成功，但 GUI 构造失败：", 12f, 34f, p);
                String s = text == null ? "" : text;
                if (s.length() > 90) s = s.substring(0, 90);
                cv.drawText(s, 12f, 66f, p);
                cv.drawText("view=" + getWidth() + "x" + getHeight(), 12f, 110f, p);
            } catch (Throwable ignored) {
            }
        }
    }
}
