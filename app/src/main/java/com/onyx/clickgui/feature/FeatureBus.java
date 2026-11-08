package com.onyx.clickgui.feature;

import android.os.Handler;
import android.os.Looper;

import com.onyx.clickgui.gui.ClickGUI;
import com.onyx.clickgui.xposed.XLog;

 










































public final class FeatureBus implements ClickGUI.Listener {

     


    public static final String VERSION = "2.4.14";

     







    public static final String TARGET_PACKAGE = com.onyx.clickgui.xposed.Scope.PRIMARY_PACKAGE;

    
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
     
    private volatile String lastSnapshot = "";
     

    private static final long STATUS_PERIOD_MS = 1000L;
    private volatile long lastStatusMs;

     
    private long lastTickAt;
    private long lastLagLogAt;
     
    private String lastHookSeg = "";
     



    private volatile boolean pushing = false;

    

     
    public void bind(ClickGUI v) {
        if (v == null) return;
        
        
        
        
        
        
        if (gui == v) return;
        gui = v;
        v.setListener(this);
        
        
        
        
        
        
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

     
    private void startMainThreadTick() {
        if (tickRunning) return;
        tickRunning = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                final long now = System.currentTimeMillis();
                
                
                
                
                
                
                final long gap = (lastTickAt == 0) ? 0 : (now - lastTickAt);
                lastTickAt = now;
                if (gap > 150 && now - lastLagLogAt > 1000) {
                    lastLagLogAt = now;
                    com.onyx.clickgui.xposed.Diag.log("主线程卡顿 gap=" + gap + "ms（预期 50）");
                }
                try {
                    Native.tick();
                    pushStatus();
                    
                    com.onyx.clickgui.xposed.Diag.logMem(30000);
                } catch (Throwable ignored) {
                }
                
                
                h.postDelayed(this, Native.isLoaded() ? 50 : 250);   
            }
        }, 50);
        XLog.i("主线程 tick 已启动（20Hz）");
    }

    

    @Override
    public void onGuiToggled(boolean open) {
        
        
        
        
        try {
            
            
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
            
            
            XLog.e("FeatureBus.onStateChanged 异常", t);
        }
    }

    

     




    private boolean guard(ClickGUI g, String uiName, boolean on, String backend, String why) {
        if (!on) return false;
        if (Native.available(backend)) return true;
        g.revert(uiName, "no backend registered (" + backend + ")");
        return false;
    }

    private void pushAll(boolean force) {
        ClickGUI g = gui;
        if (g == null) return;
        if (pushing) return;          
        pushing = true;
        try {
            pushAllInner(g, force);
        } finally {
            pushing = false;
        }
    }

    private void pushAllInner(ClickGUI g, boolean force) {
        
        boolean aura    = g.isOn(UI_AURA);
        double  aRange  = g.num(UI_AURA, "Range", 3.5);
        int     aMode   = g.mode(UI_AURA, "Mode", 0);
        
        int     aTarget = g.mode(UI_AURA, "Target", 2);
        double  aDelay  = g.num(UI_AURA, "Delay", 100);

        boolean reach   = g.isOn(UI_REACH);
        double  rVal    = g.num(UI_REACH, "Value", 6.0);

        boolean crit    = g.isOn(UI_CRIT);

        boolean vel     = g.isOn(UI_VEL);
        double  vTh     = g.num(UI_VEL, "Threshold", 0.45);

        
        boolean ping    = g.isOn(UI_PING);
        boolean tps     = g.isOn(UI_TPS);

        
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

        
        boolean nuker   = g.isOn(UI_NUKER);
        int     nRange  = (int) g.num(UI_NUKER, "Range", 3);

        boolean abreak  = g.isOn(UI_ABREAK);

        boolean tool    = g.isOn(UI_TOOL);

        boolean xray    = g.isOn(UI_XRAY);
        int     xRange  = (int) g.num(UI_XRAY, "Range", 8);

        
        boolean gamemode = g.isOn(UI_GAMEMODE);
        int     gmType   = g.mode(UI_GAMEMODE, "Type", 1);

        boolean hitbox  = g.isOn(UI_HITBOX);
        double  hbW     = g.num(UI_HITBOX, "Width", 0.9);
        double  hbH     = g.num(UI_HITBOX, "Height", 2.2);
        
        int     hbTarget = g.mode(UI_HITBOX, "Target", 0);

        boolean respawn = g.isOn(UI_RESPAWN);

        
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

        
        Native.setFeature("killaura", aura);
        Native.setNum("killaura", "range", aRange);
        Native.setNum("killaura", "delay", aDelay);
        Native.setMode("killaura", "mode", aMode);
        Native.setMode("killaura", "target", aTarget);   

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
        Native.setMode("hitbox", "target", hbTarget);   

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

     








    private void pushStatus() { pushStatus(false); }

    private void pushStatus(boolean force) {
        ClickGUI g = gui;
        if (g == null) return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastStatusMs < STATUS_PERIOD_MS) return;
        lastStatusMs = now;
        try {
            final String st = Native.status();
            
            
            
            
            
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
            g.setPlayerPos(Native.playerPos());   
        } catch (Throwable ignored) {
        }
    }

     
    public String shortStatus() {
        ClickGUI g = gui;
        if (g == null) return "ui=unbound";
        return "ui=" + (g.isGuiOpen() ? "open" : "closed")
                + " aura=" + (g.isOn(UI_AURA) ? 1 : 0)
                + " fly=" + (g.isOn(UI_FLY) ? 1 : 0)
                + " mtick=" + Native.mainTicks();
    }
}
