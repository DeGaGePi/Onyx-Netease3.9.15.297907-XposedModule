package com.onyx.clickgui.feature;

import android.os.Handler;
import android.os.Looper;

import com.onyx.clickgui.gui.ClickGUI;
import com.onyx.clickgui.xposed.XLog;

/**
 * UI ↔ native 的总线。
 *
 * 职责就三件：
 *   ① 把 ClickGUI 里**每一个**模块的状态推给 native；
 *   ② 后端缺失时把开关弹回 + 提示 —— 但 2.3.0 起所有功能都有后端了，这道闸
 *      只作为「新增功能忘了登记 backend key」的兜底，正常路径永远不会触发；
 *   ③ 在主线程按 20Hz 驱动 Native.tick()。
 *
 * ★ 必须跑在「游戏主线程」的那批调用（attack / buildBlock / destroyBlock /
 *   setAbility / setSize / teleportTo / jumpFromGround / setPlayerGameType /
 *   selectSlot / Dimension::forEachPlayer）全部收在 native 的 nTick 里，
 *   由这里的 Handler 每 50ms 驱动一次 —— 这是上一版踩过线程亲和性坑之后定死的架构。
 *
 * 后端清单（backend key → 用到的游戏函数 / Hook 点，见 cpp/offsets.hpp）：
 *   ── 直接调用型（33 函数表）──
 *   killaura   GameMode::attack            0xDAC2664
 *   scaffold   GameMode::buildBlock        0xDAC4764
 *   esp        Actor 虚表[18][19] + 自投影
 *   chestesp   BlockSource::getBlockEntity 0xDD7B660
 *   fly        getAbilities + setAbility   0xDC3B9CC / 0xDC2B6C8
 *   autojump   isOnGround + jumpFromGround 0xDC216B4 / 0xCEF9270
 *   teleport   Actor::teleportTo           0xDC31E84
 *   hitbox     Actor::setSize              0xD8E8CB8
 *   nuker      destroyBlock + getBlock     0xDAC3388 / 0xDD7B3FC
 *   autobreak  同上
 *   gamemode   setPlayerGameType           0xDC4F3B0
 *   autotool   PlayerInventory::selectSlot 0xDC41130（虚表探针认 inventory）
 *   xray       getBlock + Block::isAir     0xDD7B3FC / 0xE575674
 *   nametags   Dimension::forEachPlayer    0xE72E390
 *   ── 位移型（Actor::teleportTo）──
 *   speed / sprint / velocity / respawn
 *   ── Hook 型（cpp 里的 AArch64 inline hook）──
 *   reach      getAttackRangeSqr           0xDAC72F0
 *   criticals  Actor::isOnGround           0xDC216B4（攻击窗口内强制 false）
 *   nofall     Actor::isOnGround           0xDC216B4（恒 true）
 *   fullbright LevelRendererCamera::setupFog 0x7B86B94
 *   fov        LevelRendererPlayer::getFov 0x7BA80A4
 *   freecam    CameraAvoidanceSystem::tick 0x81421A0
 *   ping/tps   sendToServer / normalTick   0xA7878A4 / 0x74C79FC
 *
 * HUD / Theme / Notifications / Keybinds 由 ClickGUI 自己绘制，不经过 native。
 */
public final class FeatureBus implements ClickGUI.Listener {

    /** ★ 必须与 app/build.gradle 的 versionName 逐字一致（改版本时两处一起改）。
     *  它显示在 Watermark HUD 上（"Onyx <VERSION>"）—— 上次忘了同步，
     *  2.4.5 的包里还印着 2.4.4。 */
    public static final String VERSION = "2.4.14";

    /**
     * 官方主包名 —— 仅用于展示 / 上报。
     *
     * ★ 2.4.6 起**不再**用它做注入闸门：改用
     *   {@link com.onyx.clickgui.xposed.Scope} 的三层识别
     *   （包名白名单 / Application·进程名特征 / 进程内有 libminecraftpe.so），
     *   所以「改过包名的客户端」也能正常注入。
     */
    public static final String TARGET_PACKAGE = com.onyx.clickgui.xposed.Scope.PRIMARY_PACKAGE;

    // UI 里的模块名 —— 与 ClickGUI.buildModel() 里登记的必须逐字一致
    private static final String UI_AURA      = "Kill Aura";
    private static final String UI_REACH     = "Reach";
    private static final String UI_CRIT      = "Criticals";
    private static final String UI_VEL       = "Velocity";
    private static final String UI_PING      = "Ping";
    private static final String UI_TPS       = "TPS";
    private static final String UI_SCAFFOLD  = "Scaffold";
    private static final String UI_FLY       = "Fly";
    private static final String UI_JUMP      = "Auto Jump";
    private static final String UI_TP        = "Teleport";
    private static final String UI_SPEED     = "Speed";
    private static final String UI_NOFALL    = "No Fall";
    private static final String UI_SPRINT    = "Sprint";
    private static final String UI_NUKER     = "Nuker";
    private static final String UI_ABREAK    = "Auto Break";
    private static final String UI_TOOL      = "Auto Tool";
    private static final String UI_XRAY      = "Xray";
    private static final String UI_GAMEMODE  = "Game Mode";
    private static final String UI_HITBOX    = "Hitbox";
    private static final String UI_RESPAWN   = "Auto Respawn";
    private static final String UI_ESP       = "ESP";
    private static final String UI_CHEST     = "Chest ESP";
    private static final String UI_FB        = "Full Bright";
    private static final String UI_FOV       = "FOV Changer";
    private static final String UI_FREECAM   = "Freecam";
    private static final String UI_TAGS      = "Name Tags";

    private static final FeatureBus INSTANCE = new FeatureBus();

    public static FeatureBus get() { return INSTANCE; }

    private FeatureBus() {}

    private volatile ClickGUI gui;
    private volatile boolean tickRunning;
    /** 只在状态真的变了才下发，省 JNI 调用 */
    private volatile String lastSnapshot = "";
    /** 状态串刷新节流（2.4.10 引入，2.4.13 从 500ms 放宽到 1000ms）；
     *  状态串是给人看的诊断信息，1Hz 完全够，没必要 2Hz。 */
    private static final long STATUS_PERIOD_MS = 1000L;
    private volatile long lastStatusMs;

    /** —— 以下两个只在主线程 tick 里访问 —— */
    private long lastTickAt;
    private long lastLagLogAt;
    /** 上一次看到的 hset= 片段（hook 装载状态） */
    private String lastHookSeg = "";
    /**
     * 防重入：guard() 里的 revert() 会回调 onStateChanged() → 又进 pushAll()。
     * 拿这个标志把内层调用挡掉 —— 外层会带着「修正后的值」继续往下推，语义正确。
     */
    private volatile boolean pushing = false;

    // ═════════════════════════ 绑定 ═════════════════════════

    /** 把已挂到视图树上的 GUI 接进总线（OverlayInjector 调用） */
    public void bind(ClickGUI v) {
        if (v == null) return;
        // ★★ 2.4.9：已经绑的是同一个 View → 直接返回。
        //   原来这里走 pushAll(true)，而 force 会绕过 lastSnapshot 去重
        //   → 26 个功能全部重推一遍。OverlayInjector 的 watchdog(3s) /
        //   onResume / onWindowFocusChanged / 「已挂过 → bringToFront」都会调 bind，
        //   一次会话能推 11 轮 = 286 行 "setFeature xxx = 0" 纯噪音。
        //   真正的状态变化由 setFeature/setNum/notifyChanged 走，不靠这里兜。
        if (gui == v) return;
        gui = v;
        v.setListener(this);
        // ★★ 2.4.10：**不要在这里同步 pushAll(true)**。
        //   bind() 是 OverlayInjector 在 Activity 刚起来时调的，此刻 native 十有八九
        //   还没载入（ensureAsync 是异步的），这一推 = ~45 次 JNI 全部落空，
        //   而且正好压在「游戏启动/加载最重的那几秒」的主线程上。
        //   改成延后 1.5s 推第一次（那时 so 基本已就绪），再兜一次防止第一次也落空。
        //   期间 UI 照常可用，只是初始开关状态晚 1.5 秒同步到 native。
        startMainThreadTick();
        Native.ensureAsync();
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (gui == null) return;
                try { pushAll(true); } catch (Throwable t) { XLog.e("bind 首推异常", t); }
            }
        }, 1500);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (gui == null) return;
                try { pushStatus(true); } catch (Throwable ignored) { }
            }
        }, 2500);
        XLog.i("FeatureBus bound, " + Native.status());
    }

    public void unbind() {
        gui = null;
        lastSnapshot = "";
    }

    /** 主线程每帧驱动 —— 只放必须跑在游戏主线程的调用 */
    private void startMainThreadTick() {
        if (tickRunning) return;
        tickRunning = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                final long now = System.currentTimeMillis();
                // ★★ 2.4.13：测量"两次 tick 之间的实际间隔"。
                //   postDelayed(50) 只是**期望值** —— 主线程一旦被别的东西占住
                //   （游戏生成地形 / GC / 我们自己的重活），实际间隔会远大于 50ms。
                //   把这它写进日志 = **"卡顿"第一次有了时间戳和幅度**。
                //   用户报的是"加载到 5/6 卡顿然后闪退"，有了这条就能回答
                //   "卡在哪一秒、卡了多久、当时内存什么水位"。
                final long gap = (lastTickAt == 0) ? 0 : (now - lastTickAt);
                lastTickAt = now;
                if (gap > 150 && now - lastLagLogAt > 1000) {
                    lastLagLogAt = now;
                    com.onyx.clickgui.xposed.Diag.log("主线程卡顿 gap=" + gap + "ms（预期 50）");
                }
                try {
                    Native.tick();
                    pushStatus();
                    // ★ 2.4.9：每 30 秒记一次内存水位（判断闪退是否与内存有关）
                    com.onyx.clickgui.xposed.Diag.logMem(30000);
                } catch (Throwable ignored) {
                }
                // ★ 2.4.10：so 还没载入 → 20Hz 纯空转（Native.tick 直接 return）。
                //   启动/加载期主线程本来就紧张，降到 4Hz；载入后立刻回 20Hz。
                h.postDelayed(this, Native.isLoaded() ? 50 : 250);   // 20Hz / 4Hz
            }
        }, 50);
        XLog.i("主线程 tick 已启动（20Hz）");
    }

    // ═════════════════════════ 回调 ═════════════════════════

    @Override
    public void onGuiToggled(boolean open) {
        // ★ 2.4.10：整条加 try/catch。它挂在 ClickGUI 的点击回调上，
        //   而 Native.status() 在 so 没载入时会走 ensure() → 各种异常路径；
        //   这里裸奔的话异常会顺着事件分发冒到 ViewRootImpl（那里没有 try/catch），
        //   直接把整个游戏进程带走 —— 注入进程没有"应用崩溃"，只有"游戏没了"。
        try {
            // ★ 2.4.11：落盘（原来只有 XLog，要连电脑才看得到）。
            //   用户报的是"点面板就闪退"，这行是事后判断"崩溃前他打开过面板吗"的唯一依据。
            com.onyx.clickgui.xposed.Diag.log("GUI " + (open ? "open" : "close")
                    + " · " + Native.status());
            XLog.i("GUI " + (open ? "展开" : "收起") + " | " + Native.status());
            pushStatus(true);
        } catch (Throwable t) {
            XLog.e("onGuiToggled 异常", t);
        }
    }

    @Override
    public void onStateChanged() {
        try {
            pushAll(false);
        } catch (Throwable t) {
            // 这里是从 UI 的点击回调进来的 —— 抛出去会顺着 View 的事件分发
            // 把整个游戏进程带崩（注入进程没有"应用崩溃"这一说，只有整个游戏没）。
            XLog.e("FeatureBus.onStateChanged 异常", t);
        }
    }

    // ═════════════════════════ 下发 ═════════════════════════

    /**
     * 开了但没有后端 → 把开关弹回并提示；返回**修正后**的状态。
     * 2.3.0 起所有功能都登记了后端，所以这里正常路径恒返回原值 ——
     * 它的价值变成了「新功能漏登记 key 时不会静默失效」的一道保险。
     */
    private boolean guard(ClickGUI g, String uiName, boolean on, String backend, String why) {
        if (!on) return false;
        if (Native.available(backend)) return true;
        g.revert(uiName, "no backend registered (" + backend + ")");
        return false;
    }

    private void pushAll(boolean force) {
        ClickGUI g = gui;
        if (g == null) return;
        if (pushing) return;          // 内层重入直接吞掉，外层会继续推修正后的值
        pushing = true;
        try {
            pushAllInner(g, force);
        } finally {
            pushing = false;
        }
    }

    private void pushAllInner(ClickGUI g, boolean force) {
        // ── Combat ──
        boolean aura    = g.isOn(UI_AURA);
        double  aRange  = g.num(UI_AURA, "Range", 3.5);
        int     aMode   = g.mode(UI_AURA, "Mode", 0);
        // ★ 2.4.5：攻击对象筛选 0=Living 1=Players 2=Both
        int     aTarget = g.mode(UI_AURA, "Target", 2);
        double  aDelay  = g.num(UI_AURA, "Delay", 100);

        boolean reach   = g.isOn(UI_REACH);
        double  rVal    = g.num(UI_REACH, "Value", 6.0);

        boolean crit    = g.isOn(UI_CRIT);

        boolean vel     = g.isOn(UI_VEL);
        double  vTh     = g.num(UI_VEL, "Threshold", 0.45);

        // ── HUD ──
        boolean ping    = g.isOn(UI_PING);
        boolean tps     = g.isOn(UI_TPS);

        // ── Movement ──
        boolean scaffold = g.isOn(UI_SCAFFOLD);
        int     sDelay   = (int) g.num(UI_SCAFFOLD, "Delay", 100);

        boolean fly     = g.isOn(UI_FLY);

        boolean jump    = g.isOn(UI_JUMP);
        int     jDelay  = (int) g.num(UI_JUMP, "Delay", 300);

        boolean tp      = g.isOn(UI_TP);
        int     tpMode  = g.mode(UI_TP, "Mode", 0);
        double  tpDist  = g.num(UI_TP, "Dist", 4.0);

        boolean speed   = g.isOn(UI_SPEED);
        double  spdDist = g.num(UI_SPEED, "Dist", 0.30);

        boolean nofall  = g.isOn(UI_NOFALL);

        boolean sprint  = g.isOn(UI_SPRINT);
        double  sprtDist = g.num(UI_SPRINT, "Dist", 0.80);

        // ── World ──
        boolean nuker   = g.isOn(UI_NUKER);
        int     nRange  = (int) g.num(UI_NUKER, "Range", 3);

        boolean abreak  = g.isOn(UI_ABREAK);

        boolean tool    = g.isOn(UI_TOOL);

        boolean xray    = g.isOn(UI_XRAY);
        int     xRange  = (int) g.num(UI_XRAY, "Range", 8);

        // ── Player ──
        boolean gamemode = g.isOn(UI_GAMEMODE);
        int     gmType   = g.mode(UI_GAMEMODE, "Type", 1);

        boolean hitbox  = g.isOn(UI_HITBOX);
        double  hbW     = g.num(UI_HITBOX, "Width", 0.9);
        double  hbH     = g.num(UI_HITBOX, "Height", 2.2);
        // ★ 2.4.5：碰撞箱作用对象 0=Self 1=Living 2=Players 3=Both
        int     hbTarget = g.mode(UI_HITBOX, "Target", 0);

        boolean respawn = g.isOn(UI_RESPAWN);

        // ── Visual ──
        boolean esp     = g.isOn(UI_ESP);
        double  eRange  = g.num(UI_ESP, "Range", 32.0);
        int     eMode   = g.mode(UI_ESP, "Mode", 0);

        boolean chest   = g.isOn(UI_CHEST);
        int     cRange  = (int) g.num(UI_CHEST, "Range", 16);

        boolean fb      = g.isOn(UI_FB);

        boolean fov     = g.isOn(UI_FOV);
        double  fovVal  = g.num(UI_FOV, "FOV", 90);

        boolean freecam = g.isOn(UI_FREECAM);
        boolean tags    = g.isOn(UI_TAGS);

        // ── 变化检测 ──
        //   ★ 新加的下拉/数值**必须**塞进这个快照，否则改了它 FeatureBus 认为
        //     "没变化" → 一整天都不下发（Target 就是这么容易写成摆设的）。
        String snap = aura + "|" + aRange + "|" + aMode + "|" + aTarget + "|" + aDelay
                + "|" + reach + "|" + rVal + "|" + crit
                + "|" + vel + "|" + vTh + "|" + ping + "|" + tps
                + "|" + scaffold + "|" + sDelay + "|" + fly
                + "|" + jump + "|" + jDelay + "|" + tp + "|" + tpMode + "|" + tpDist
                + "|" + speed + "|" + spdDist + "|" + nofall + "|" + sprint + "|" + sprtDist
                + "|" + nuker + "|" + nRange + "|" + abreak + "|" + tool
                + "|" + xray + "|" + xRange
                + "|" + gamemode + "|" + gmType + "|" + hitbox + "|" + hbTarget + "|" + hbW + "|" + hbH
                + "|" + respawn
                + "|" + esp + "|" + eRange + "|" + eMode + "|" + chest + "|" + cRange
                + "|" + fb + "|" + fov + "|" + fovVal + "|" + freecam + "|" + tags;
        if (!force && snap.equals(lastSnapshot)) return;
        lastSnapshot = snap;

        // ── 后端可用性闸门 ──
        aura    = guard(g, UI_AURA,     aura,     "killaura",   "attack 未定案");
        reach   = guard(g, UI_REACH,    reach,    "reach",      "getAttackRangeSqr 未定案");
        crit    = guard(g, UI_CRIT,     crit,     "criticals",  "isOnGround 未定案");
        vel     = guard(g, UI_VEL,      vel,      "velocity",   "teleportTo 未定案");
        ping    = guard(g, UI_PING,     ping,     "ping",       "sendToServer 未定案");
        tps     = guard(g, UI_TPS,      tps,      "tps",        "normalTick 未定案");
        scaffold = guard(g, UI_SCAFFOLD, scaffold, "scaffold",  "buildBlock 未定案");
        fly     = guard(g, UI_FLY,      fly,      "fly",        "setAbility 未定案");
        jump    = guard(g, UI_JUMP,     jump,     "autojump",   "jumpFromGround 未定案");
        tp      = guard(g, UI_TP,       tp,       "teleport",   "teleportTo 未定案");
        speed   = guard(g, UI_SPEED,    speed,    "speed",      "teleportTo 未定案");
        nofall  = guard(g, UI_NOFALL,   nofall,   "nofall",     "isOnGround 未定案");
        sprint  = guard(g, UI_SPRINT,   sprint,   "sprint",     "teleportTo 未定案");
        nuker   = guard(g, UI_NUKER,    nuker,    "nuker",      "destroyBlock 未定案");
        abreak  = guard(g, UI_ABREAK,   abreak,   "autobreak",  "destroyBlock 未定案");
        tool    = guard(g, UI_TOOL,     tool,     "autotool",   "selectSlot 未定案");
        xray    = guard(g, UI_XRAY,     xray,     "xray",       "getBlock 未定案");
        gamemode = guard(g, UI_GAMEMODE, gamemode, "gamemode",  "setPlayerGameType 未定案");
        hitbox  = guard(g, UI_HITBOX,   hitbox,   "hitbox",     "setSize 未定案");
        respawn = guard(g, UI_RESPAWN,  respawn,  "respawn",    "teleportTo 未定案");
        esp     = guard(g, UI_ESP,      esp,      "esp",        "投影链路未就绪");
        chest   = guard(g, UI_CHEST,    chest,    "chestesp",   "getBlockEntity 未定案");
        fb      = guard(g, UI_FB,       fb,       "fullbright", "setupFog 未定案");
        fov     = guard(g, UI_FOV,      fov,      "fov",        "getFov 未定案");
        freecam = guard(g, UI_FREECAM,  freecam,  "freecam",    "CameraAvoidance 未定案");
        tags    = guard(g, UI_TAGS,     tags,     "nametags",   "实体枚举未就绪");

        // ── 下发 ──
        Native.setFeature("killaura", aura);
        Native.setNum("killaura", "range", aRange);
        Native.setNum("killaura", "delay", aDelay);
        Native.setMode("killaura", "mode", aMode);
        Native.setMode("killaura", "target", aTarget);   // ★ 2.4.5

        Native.setFeature("reach", reach);
        Native.setNum("reach", "value", rVal);

        Native.setFeature("criticals", crit);

        Native.setFeature("velocity", vel);
        Native.setNum("velocity", "threshold", vTh);

        Native.setFeature("ping", ping);
        Native.setFeature("tps", tps);

        Native.setFeature("scaffold", scaffold);
        Native.setNum("scaffold", "delay", sDelay);

        Native.setFeature("fly", fly);

        Native.setFeature("autojump", jump);
        Native.setNum("autojump", "delay", jDelay);

        Native.setFeature("teleport", tp);
        Native.setMode("teleport", "mode", tpMode);
        Native.setNum("teleport", "dist", tpDist);

        Native.setFeature("speed", speed);
        Native.setNum("speed", "dist", spdDist);

        Native.setFeature("nofall", nofall);

        Native.setFeature("sprint", sprint);
        Native.setNum("sprint", "dist", sprtDist);

        Native.setFeature("nuker", nuker);
        Native.setNum("nuker", "range", nRange);

        Native.setFeature("autobreak", abreak);

        Native.setFeature("autotool", tool);

        Native.setFeature("xray", xray);
        Native.setNum("xray", "range", xRange);

        Native.setFeature("gamemode", gamemode);
        Native.setNum("gamemode", "type", gmType);

        Native.setFeature("hitbox", hitbox);
        Native.setNum("hitbox", "width", hbW);
        Native.setNum("hitbox", "height", hbH);
        Native.setMode("hitbox", "target", hbTarget);   // ★ 2.4.5

        Native.setFeature("respawn", respawn);

        Native.setFeature("esp", esp);
        Native.setNum("esp", "range", eRange);
        Native.setMode("esp", "mode", eMode);

        Native.setFeature("chestesp", chest);
        Native.setNum("chestesp", "range", cRange);

        Native.setFeature("fullbright", fb);

        Native.setFeature("fov", fov);
        Native.setNum("fov", "value", fovVal);

        Native.setFeature("freecam", freecam);

        Native.setFeature("nametags", tags);

        pushStatus();
    }

    /**
     * 把 native 状态串塞进 HUD —— 面板里能看到每个后端活着没。
     *
     * ★★ 2.4.10：**限频到 2Hz**。
     *   原来它挂在 20Hz 主线程 tick 里，每 50ms 走一次 `Native.status()`（JNI +
     *   字符串拼接 + 新分配 String）→ 配合 setNativeStatus 的无条件 invalidate，
     *   等于「每 50ms 一次跨语言调用 + 一次全屏重绘」。
     *   状态串是给人看的诊断信息，2Hz 完全够；玩家肉眼分辨不出 2Hz 和 20Hz。
     */
    private void pushStatus() { pushStatus(false); }

    private void pushStatus(boolean force) {
        ClickGUI g = gui;
        if (g == null) return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastStatusMs < STATUS_PERIOD_MS) return;
        lastStatusMs = now;
        try {
            final String st = Native.status();
            // ★★ 2.4.13：把 hook 装载状态的变化**落盘**。
            //   inline hook 是这份代码里唯一会**改动游戏代码段**的动作，
            //   而用户报的是"加载到 5/6 卡顿然后闪退" —— 必须先能排除它。
            //   `hook 状态变化 hset=0xff` 那一行 = 8 个 hook 全部装上的时刻；
            //   对照后面的 `mem` 心跳就能看出"装上之后游戏还活了几秒"。
            if (st != null) {
                final int i = st.indexOf("hset=");
                if (i >= 0) {
                    final String rest = st.substring(i);
                    final int sp = rest.indexOf(' ');
                    final String seg = (sp > 0) ? rest.substring(0, sp) : rest;
                    if (!seg.equals(lastHookSeg)) {
                        lastHookSeg = seg;
                        com.onyx.clickgui.xposed.Diag.log(
                                "hook 状态变化 " + seg + "（0x00 = 尚未装上）");
                    }
                }
            }
            g.setNativeStatus(st);
            g.setPlayerPos(Native.playerPos());   // Coords HUD
        } catch (Throwable ignored) {
        }
    }

    /** 供日志/调试反查 */
    public String shortStatus() {
        ClickGUI g = gui;
        if (g == null) return "ui=unbound";
        return "ui=" + (g.isGuiOpen() ? "open" : "closed")
                + " aura=" + (g.isOn(UI_AURA) ? 1 : 0)
                + " fly=" + (g.isOn(UI_FLY) ? 1 : 0)
                + " mtick=" + Native.mainTicks();
    }
}
