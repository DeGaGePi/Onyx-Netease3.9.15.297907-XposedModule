package com.onyx.clickgui.gui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

 
























public class ClickGUI extends View {

    

    public interface Listener {
        void onGuiToggled(boolean open);

        void onStateChanged();
    }

    

    private static final int ACCENT    = 0xFF8B5CF6;
    private static final int ACCENT_2  = 0xFF6D46D9;
    private static final int PANEL_BG  = 0xFF141418;
    private static final int HEADER_BG = 0xFF202026;
    private static final int SUB_BG    = 0xFF0D0D11;
    private static final int TXT_ON    = 0xFFEEEEF2;
    private static final int TXT_OFF   = 0xFFAAAAB6;
    private static final int TXT_DIM   = 0xFF8A8A96;
    private static final int TRACK_OFF = 0xFF3E3E46;
    private static final int TRACK_BG  = 0xFF2C2C34;
    private static final int OK_DOT    = 0xFF50DC82;
    private static final int NA_TXT    = 0xFFD08080;
    private static final int DROP_BG   = 0xFF1C1C22;
    private static final int TAB_BG    = 0xFF16161A;
    private static final int LOCK_TRK  = 0xFF212127;
    private static final int DROP_OPEN = 0xFF343440;
    private static final int KNOB      = 0xFFF5F5FA;

    private static final int C_MASK = 0x6B000000;   

    

    private static final float D_HEADER = 32f;
    private static final float D_MOD    = 26f;
    private static final float D_SET    = 26f;
    private static final float D_SLD    = 34f;
    private static final float D_GAP    = 14f;
    private static final float D_RAD    = 6f;
    private static final float D_INDENT = 14f;
    private static final float D_PADR   = 12f;
    private static final float D_TOP    = 88f;    
    private static final float D_MARGIN = 20f;
    private static final float D_BOT    = 92f;    
    private static final float W_MAX    = 220f;
    private static final float W_MIN    = 128f;

    private static final float TAB_Y     = 16f;
    private static final float TAB_FS    = 17f;
    private static final float TAB_PAD   = 26f;

    private static final float MIN_U = 0.9f;
     








    private static final float MAX_U = 1.6f;

    

    private static final int N_INFO = 0, N_OK = 1, N_WARN = 2, N_ERR = 3;
    private static final int[] N_ACCENT = {ACCENT, OK_DOT, 0xFFFFB020, 0xFFE5484D};
    private static final String[] N_ICON = {"i", "v", "!", "x"};
    private static final float NOTIF_W = 178f, NOTIF_H = 26f, NOTIF_GAP = 6f;

    
    private static final String[][] THEMES = {
            {"Onyx",  "#8B5CF6", "#6D46D9"},
            {"Cyan",  "#22D3EE", "#0891B2"},
            {"Mint",  "#50DC82", "#2F9E5A"},
            {"Rose",  "#FB7185", "#E11D48"},
            {"Amber", "#FBBF24", "#D97706"},
            {"Blue",  "#3B82F6", "#1D4ED8"},
            {"Lime",  "#A3E635", "#65A30D"},
            {"Slate", "#94A3B8", "#475569"},
    };

    

    private static final class Category {
        final String name;
        final List<Feature> mods = new ArrayList<>();
        boolean open = true;
         
        float x, y, w;
         
        float ox, oy;

        Category(String n) { this.name = n; }
    }

    private static final int H_CAT = 1, H_MOD = 2, H_ARROW = 3, H_SET = 4,
                             H_SLIDER = 5, H_ENUM = 6, H_DROP = 7, H_HUD = 8, H_ACT = 9;

    private static final class Hit {
        int kind;
        Category c;
        Feature m;
        Feature.Setting s;
        float x, y, w, h;
        int idx;
    }

    private static final class Drop {
        Feature.Setting s;
        float x, y, w, itemH;
        int n;
    }

    private static final class Notif {
        String title, msg;
        int type;
        long born = System.currentTimeMillis();
        long life = 2400;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bld  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rc   = new RectF();
    private final Path  clip = new Path();

    private final List<Category> cats = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Notif> notifs = new ArrayList<>();

     
    private float U = 1.4f;
    private float lastW = -1f, lastH = -1f;

     




    private static float sUiScale = 1.0f;
     
    private boolean scaleDirty = false;
    private static android.content.SharedPreferences sPrefs;

    private float screenW, screenH;          
    private float catW = W_MAX;              

    private boolean guiOpen = true;
    private boolean overlayMode = false;
    private float mx = -1e9f, my = -1e9f;    

    private float anim = 0f;
     
    private boolean animBusy = false;
     
    private static final long IDLE_MS = 250L;
    private long lastTick = 0;
    private float fps = 0f;
    private int frameCnt = 0;
    private long fpsAt = 0;

    private Drop drop;
    private Feature.Setting slider;
    private Hit pressed;
    private float pressX, pressY;

     
    private Category dragCat;
    private float dragLastX, dragLastY, dragDist;
    private boolean dragMoved;

    
    
    
    
    private static final int HUD_WM = 0, HUD_COORD = 1, HUD_FPS = 2, HUD_LIST = 3,
                             HUD_NET = 4, HUD_TPS = 5, HUD_N = 6;
    private static final float[] sHudX = new float[HUD_N];
    private static final float[] sHudY = new float[HUD_N];
    
    
    static { for (int i = 0; i < HUD_N; i++) { sHudX[i] = Float.NaN; sHudY[i] = Float.NaN; } }

    private final List<Hit> hudHits = new ArrayList<>();
    private Hit dragHud;
    private float dragHudGX, dragHudGY;
     
    private boolean dragHudMoved;
     
    private boolean dragHudEdit;

    private float tabUx = 0f, tabUw = 0f;    
    private String[] tabNames = {"Onyx"};
    private int tabIdx = 0;

    private float fabX, fabY;                
    private boolean fabPlaced = false;
    private boolean fabPressed = false, fabDragged = false;

     
    private int c1 = ACCENT, c2 = ACCENT_2;
     
    private static int sThemeIdx = 0;

    private Listener listener;
    private String nativeStatus = "native: -";
    private String playerPos = "";
     
    private float[] espData;
     
    private float[] tagData;
     
    private float[] netData;

    
    
    
    private long beatUntil;
    private final Paint beat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean beatLogged;

    

    public ClickGUI(Context c) {
        super(c);
        init();
    }

    public ClickGUI(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);

        txt.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        bld.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        try { com.onyx.clickgui.feature.KeyBinds.init(getContext()); } catch (Throwable ignored) {
        }
        
        try {
            sPrefs = getContext().getApplicationContext()
                    .getSharedPreferences("onyx_ui", android.content.Context.MODE_PRIVATE);
            final float v = sPrefs.getFloat("uiScale", 1.0f);
            if (v >= 0.6f && v <= 1.6f) sUiScale = v;
        } catch (Throwable ignored) {
        }
        buildModel();
        lastTick = System.currentTimeMillis();
        fpsAt = lastTick;
        beatUntil = lastTick + 40000L;
        com.onyx.clickgui.xposed.Diag.log("ClickGUI init 完成，模块 " + cats.size() + " 个分类");
        postInvalidate();
    }

    

    private void buildModel() {
        cats.clear();

        
        
        
        
        
        
        
        
        

        Category combat = new Category("Combat");
        combat.mods.add(Feature.mod("Kill Aura", "killaura", "自动攻击范围内实体")
                .fnum("Range", 3.5, 2.0, 6.0, 0.05)
                .en("Mode", new String[]{"Single", "Multi"}, 0)
                
                
                .en("Target", new String[]{"Living", "Players", "Both"}, 2)
                .inum("Delay", 100, 30, 500)
                .hk());
        combat.mods.add(Feature.mod("Reach", "reach", "延长攻击距离（hook getAttackRange + getAttackRangeSqr）")
                .fnum("Value", 6.0, 2.0, 12.0, 0.5)
                .hk());
        combat.mods.add(Feature.mod("Criticals", "criticals", "攻击瞬间伪装成下落中 → 判定暴击")
                .hk());
        combat.mods.add(Feature.mod("Velocity", "velocity", "击退抑制：单 tick 位移超阈值就拉回")
                .fnum("Threshold", 0.45, 0.20, 1.50, 0.05)
                .hk());
        cats.add(combat);

        
        Category hud = new Category("HUD");
        hud.mods.add(Feature.ui("HUD Editor", "拖动 HUD 元素摆位置").off());
        hud.mods.add(Feature.ui("Watermark", "客户端名胶囊").bool("Shadow", true));
        hud.mods.add(Feature.ui("ArrayList", "列出已开启的功能").bool("Rainbow", false));
        hud.mods.add(Feature.ui("FPS Display", "帧率显示"));
        hud.mods.add(Feature.ui("Coords", "玩家所在方块坐标"));
        hud.mods.add(Feature.mod("Ping", "ping", "网络包速率 / 相邻包间隔抖动（客户端测不到真 RTT）"));
        hud.mods.add(Feature.mod("TPS", "tps", "客户端 tick 速率（hook normalTick 实测）"));
        cats.add(hud);

        

        Category movement = new Category("Movement");
        movement.mods.add(Feature.mod("Scaffold", "scaffold", "脚底自动放置方块，不用右键")
                .inum("Delay", 100, 20, 500)
                .hk());
        movement.mods.add(Feature.mod("Fly", "fly", "打开 Flying/MayFly 能力位")
                .hk());
        movement.mods.add(Feature.mod("Auto Jump", "autojump", "着地即跳")
                .inum("Delay", 300, 50, 2000)
                .hk());
        movement.mods.add(Feature.mod("Teleport", "teleport", "同区块内位移 · 上升 / 前移")
                .en("Mode", new String[]{"Up", "Forward"}, 0)
                .fnum("Dist", 4.0, 0.5, 16.0, 0.5)
                .hk());
        movement.mods.add(Feature.mod("Speed", "speed", "沿视线持续前移（每 50ms 一步）")
                .fnum("Dist", 0.30, 0.05, 1.20, 0.05)
                .hk());
        movement.mods.add(Feature.mod("No Fall", "nofall", "免疫摔落（hook isOnGround 恒真）")
                .hk());
        movement.mods.add(Feature.mod("Sprint", "sprint", "自动前进，跑图用（每 100ms 一步）")
                .fnum("Dist", 0.80, 0.20, 2.00, 0.10)
                .hk());
        cats.add(movement);

        Category world = new Category("World");
        world.mods.add(Feature.mod("Nuker", "nuker", "销毁周围方块（轮转扫描）")
                .inum("Range", 3, 1, 5)
                .hk());
        world.mods.add(Feature.mod("Auto Break", "autobreak", "沿视线自动挖方块")
                .hk());
        world.mods.add(Feature.mod("Auto Tool", "autotool", "手上不是方块时自动滚快捷栏找方块")
                .hk());
        world.mods.add(Feature.mod("Xray", "xray", "扫出稀有方块并框出来（按类型频次判稀有）")
                .inum("Range", 8, 3, 16)
                .hk());
        cats.add(world);

        

        Category player = new Category("Player");
        player.mods.add(Feature.mod("Game Mode", "gamemode", "切换游戏模式")
                .en("Type", new String[]{"Survival", "Creative", "Adventure"}, 1)
                .hk());
        player.mods.add(Feature.mod("Hitbox", "hitbox", "放大碰撞箱（自己 + 可选的目标）")
                
                
                .en("Target", new String[]{"Self", "Living", "Players", "Both"}, 0)
                .fnum("Width", 0.9, 0.2, 6.0, 0.1)
                .fnum("Height", 2.2, 0.4, 8.0, 0.1)
                .hk());
        player.mods.add(Feature.mod("Auto Respawn", "respawn", "死亡 / 重生后自动回到记录的安全点")
                .hk());
        cats.add(player);

        Category visual = new Category("Visual");
        visual.mods.add(Feature.mod("ESP", "esp", "透视 · 把附近实体框出来")
                .en("Mode", new String[]{"Box", "Outline"}, 0)
                .fnum("Range", 32.0, 8.0, 96.0, 1.0)
                .fnum("FOV", 70.0, 40.0, 110.0, 1.0)
                .hk());
        visual.mods.add(Feature.mod("Chest ESP", "chestesp", "箱子 / 方块实体框")
                .inum("Range", 16, 4, 24)
                .hk());
        visual.mods.add(Feature.mod("Full Bright", "fullbright", "去掉雾效 → 夜里 / 洞里全亮")
                .hk());
        visual.mods.add(Feature.mod("FOV Changer", "fov", "改视场角（hook LevelRendererPlayer::getFov）")
                .fnum("FOV", 90, 30, 160, 1)
                .hk());
        visual.mods.add(Feature.mod("Freecam", "freecam", "相机穿墙 · 跳过遮挡规避系统")
                .hk());
        visual.mods.add(Feature.mod("Name Tags", "nametags", "实体头顶画名字 + 距离")
                .hk());
        cats.add(visual);

        

        Category client = new Category("Client");
        
        client.mods.add(Feature.ui("Theme", "面板配色")
                .lock("Always on \u00b7 use Color to switch")
                .en("Color", themeNames(), sThemeIdx));
        client.mods.add(Feature.ui("Language", "界面语言 · 开 = 中文 / 关 = 英文").off());
        client.mods.add(Feature.ui("Notifications", "开关功能时弹右下角提示"));
        
        
        client.mods.add(Feature.ui("UI Scale", "面板整体缩放 · 1.0 = 自适应")
                .lock("Always on \u00b7 drag Scale to resize")
                .fnum("Scale", sUiScale, 0.6, 1.6, 0.05));
        cats.add(client);

        for (Category c : cats) {
            for (Feature m : c.mods) {
                
                
                if (m.backend == null && m.implemented && !m.startOff) m.enabled = true;
                m.anim = m.enabled ? 1f : 0f;
                m.expanded = false;          
                m.anim = 0f;
            }
        }

        Lang.zh = isOnUnsafe("Language");    
        applyTheme(sThemeIdx);               
    }

    private static String[] themeNames() {
        String[] n = new String[THEMES.length];
        for (int i = 0; i < THEMES.length; i++) n[i] = THEMES[i][0];
        return n;
    }

    private Feature byName(String name) {
        if (name == null) return null;
        for (Category c : cats) {
            for (Feature m : c.mods) {
                if (m.name.equalsIgnoreCase(name)) return m;
            }
        }
        return null;
    }

    private boolean isOnUnsafe(String name) {
        Feature m = byName(name);
        return m != null && m.enabled;
    }

    

    public boolean isOverlayMode() { return overlayMode; }

    public void setOverlayMode(boolean overlay) {
        if (overlayMode == overlay) return;
        overlayMode = overlay;
        if (overlay) {
            guiOpen = false;
            anim = 0f;
            fabPlaced = false;
            maybeAutoOpenOnce();
        }
        requestLayout();
        invalidate();
    }

     












    private void maybeAutoOpenOnce() {
        try {
            final android.content.SharedPreferences p = getContext().getApplicationContext()
                    .getSharedPreferences("onyx_ui", android.content.Context.MODE_PRIVATE);
            if (p.getBoolean("guideShown", false)) return;
            p.edit().putBoolean("guideShown", true).apply();
        } catch (Throwable t) {
            return;
        }
        postDelayed(new Runnable() {
            @Override public void run() {
                try { setGuiOpen(true); invalidate(); } catch (Throwable ignored) { }
            }
        }, 1200);
        postDelayed(new Runnable() {
            @Override public void run() {
                try { setGuiOpen(false); invalidate(); } catch (Throwable ignored) { }
            }
        }, 7200);
    }

    public boolean isGuiOpen() { return guiOpen; }

    public void setGuiOpen(boolean open) {
        if (guiOpen == open) return;
        guiOpen = open;
        if (!open) {
            drop = null;
        }
        if (listener != null) listener.onGuiToggled(open);
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    public void setNativeStatus(String s) {
        final String v = (s == null || s.isEmpty()) ? "native: -" : s;
        
        
        
        
        
        if (v.equals(nativeStatus)) return;
        nativeStatus = v;
        invalidate();
    }

     
    public void setPlayerPos(String s) {
        playerPos = (s == null) ? "" : s;
    }

    public boolean isOn(String name) { return isOnUnsafe(name); }

    public double num(String name, String prop, double def) {
        Feature m = byName(name);
        Feature.Setting s = m == null ? null : m.set(prop);
        if (s == null) return def;
        if (s.type == Feature.FLOAT) return s.f;
        if (s.type == Feature.INT) return s.i;
        if (s.type == Feature.BOOL) return s.b ? 1 : 0;
        return def;
    }

    public int mode(String name, String prop, int def) {
        Feature m = byName(name);
        Feature.Setting s = m == null ? null : m.set(prop);
        return (s != null && s.type == Feature.ENUM) ? s.idx : def;
    }

    public void revert(String name, String why) {
        Feature m = byName(name);
        if (m == null || !m.enabled) return;
        m.enabled = false;
        toast(name, why == null ? "N/A" : why, N_WARN);
        if (listener != null) listener.onStateChanged();
        invalidate();
    }

    public void toast(String title, String msg, int type) {
        
        
        final Feature nf = byName("Notifications");
        if (nf != null && !nf.enabled) return;

        
        
        
        
        
        Lang.zh = isOnUnsafe("Language");

        Notif n = new Notif();
        n.title = Lang.t(title);
        n.msg = Lang.t(msg);
        n.type = Math.max(0, Math.min(3, type));
        n.born = System.currentTimeMillis();
        notifs.add(n);
        while (notifs.size() > 4) notifs.remove(0);
        postInvalidate();
    }

    public void toastInfo(String t, String m) { toast(t, m, N_INFO); }
    public void toastOk(String t, String m)   { toast(t, m, N_OK); }
    public void toastWarn(String t, String m) { toast(t, m, N_WARN); }
    public void toastErr(String t, String m)  { toast(t, m, N_ERR); }

    

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        
        
        
        
        
        
        try {
            measureOverlay(wSpec, hSpec);
        } catch (Throwable t) {
            reportCrash("measure", t);
            try {
                setMeasuredDimension(Math.max(1, MeasureSpec.getSize(wSpec)),
                                     Math.max(1, MeasureSpec.getSize(hSpec)));
            } catch (Throwable ignored) {
            }
        }
    }

    private void measureOverlay(int wSpec, int hSpec) {
        if (!overlayMode) {
            super.onMeasure(wSpec, hSpec);
            return;
        }
        
        
        
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = 0;
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) h = 0;

        
        if (w <= 0 || h <= 0) {
            try {
                WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    DisplayMetrics dm = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    if (w <= 0) w = dm.widthPixels;
                    if (h <= 0) h = dm.heightPixels;
                }
            } catch (Throwable ignored) {
            }
        }
        
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            if (w <= 0) w = dm.widthPixels;
            if (h <= 0) h = dm.heightPixels;
        }
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        screenW = w;
        screenH = h;
        fabPlaced = false;
        com.onyx.clickgui.xposed.Diag.log("onSizeChanged " + w + "x" + h
                + "（旧 " + ow + "x" + oh + "）");
        
        
        
        try { computeScale(); } catch (Throwable t) { reportCrash("computeScale", t); }
        try { layoutAll(); }   catch (Throwable t) { reportCrash("layoutAll", t); }
        try { placeFab(); }    catch (Throwable t) { reportCrash("placeFab", t); }
    }

    

    private float designW() { return U > 0.01f ? screenW / U : screenW; }

    private float designH() { return U > 0.01f ? screenH / U : screenH; }

    private void computeScale() {
        if (screenW < 2 || screenH < 2) return;
        if (!scaleDirty && screenW == lastW && screenH == lastH) return;
        scaleDirty = false;
        lastW = screenW;
        lastH = screenH;

        final int cols = Math.max(1, columnCount());

        
        final float needW = D_MARGIN + cols * W_MAX + (cols - 1) * D_GAP;
        final float uW = screenW / needW;

        
        
        
        final float needH = D_TOP + baseColumnH() + D_BOT;
        final float uH = screenH / needH;

        float u = Math.min(uW, uH);
        if (u > MAX_U) u = MAX_U;          
        if (u < MIN_U) u = MIN_U;

        
        u *= sUiScale;
        if (u > 2.4f) u = 2.4f;
        if (u < 0.5f) u = 0.5f;
        U = u;
        com.onyx.clickgui.xposed.Diag.log("computeScale " + (int) screenW + "x"
                + (int) screenH + " -> U=" + String.format(java.util.Locale.US, "%.3f", U)
                + " (autoW=" + String.format(java.util.Locale.US, "%.2f", uW)
                + " autoH=" + String.format(java.util.Locale.US, "%.2f", uH)
                + " scale=" + sUiScale + ")");
    }

     
    private void requestScale() {
        scaleDirty = true;
        computeScale();
        layoutAll();
        invalidate();
    }

    private int columnCount() {
        int max = 0;
        for (Category c : cats) max = Math.max(max, colOf(c.name) + 1);
        return Math.max(1, max);
    }

     
    private int colOf(String name) {
        if (name == null) return 0;
        if (name.equals("Combat") || name.equals("HUD"))                 return 0;
        if (name.equals("Movement") || name.equals("World"))             return 1;
        if (name.equals("Player") || name.equals("Visual")
                || name.equals("Render"))                                return 2;
        if (name.equals("Client") || name.equals("Misc"))                return 3;
        return 3;
    }

    private void placeFab() {
        if (screenW <= 0 || screenH <= 0) return;
        final float r = fabR(), m = 10 * U;
        if (!fabPlaced) {
            fabX = screenW - r - m;
            fabY = screenH * 0.5f;
            fabPlaced = true;
        }
        fabX = Math.max(m + r, Math.min(screenW - m - r, fabX));
        fabY = Math.max(m + r, Math.min(screenH - m - r, fabY));
    }

    

    private float settingH(Feature.Setting s) {
        return (s.type == Feature.FLOAT || s.type == Feature.INT) ? D_SLD : D_SET;
    }

    private float settingsBlockH(Feature m) {
        float h = 5f;
        for (Feature.Setting s : m.settings) h += settingH(s);
        return h + 3f;
    }

    private float moduleH(Feature m) {
        float h = D_MOD;
        if (m.anim > 0.001f) h += settingsBlockH(m) * m.anim;
        return h;
    }

    private float columnH(Category c) {
        if (!c.open) return D_HEADER;
        float h = D_HEADER + 4f;
        for (Feature m : c.mods) h += moduleH(m);
        return h + 5f;
    }

     











    private float baseColumnH() {
        
        
        
        final int cols = Math.max(1, columnCount());
        final float[] h = new float[cols];
        for (int i = 0; i < cols; i++) h[i] = 0f;
        for (Category c : cats) {
            final int col = Math.min(cols - 1, Math.max(0, colOf(c.name)));
            h[col] += columnHBase(c) + D_GAP;
        }
        float best = D_HEADER + 9f;
        for (float v : h) if (v > best) best = v;
        return best;
    }

     
    private float columnHBase(Category c) {
        if (!c.open) return D_HEADER;
        return D_HEADER + 4f + c.mods.size() * D_MOD + 5f;
    }

    private float contentX(Category c) { return c.w - D_PADR - 4f; }

    

    private void layoutAll() {
        if (screenW < 2 || screenH < 2 || cats.isEmpty()) return;

        final int cols = Math.max(1, columnCount());
        final float sw = designW();

        float w = (sw - D_MARGIN - (cols - 1) * D_GAP) / cols;
        if (w > W_MAX) w = W_MAX;
        if (w < W_MIN) w = W_MIN;
        catW = w;

        final float total = cols * w + (cols - 1) * D_GAP;
        float x0 = (sw - total) * 0.5f;
        if (x0 < 8f) x0 = 8f;

        float[] cy = new float[cols];
        for (int i = 0; i < cols; i++) cy[i] = D_TOP;

        for (Category c : cats) {
            int col = Math.min(cols - 1, Math.max(0, colOf(c.name)));
            c.w = w;

            final float baseX = x0 + col * (w + D_GAP);
            final float baseY = cy[col];
            cy[col] += columnH(c) + D_GAP;

            
            final float ch   = Math.max(D_HEADER, columnH(c));
            final float maxX = Math.max(2f, sw - w - 2f);
            final float maxY = Math.max(2f, designH() - ch - 2f);

            float nx = baseX + c.ox;
            float ny = baseY + c.oy;
            if (nx < 2f)  nx = 2f;
            if (nx > maxX) nx = maxX;
            if (ny < 2f)  ny = 2f;
            if (ny > maxY) ny = maxY;

            c.ox = nx - baseX;    
            c.oy = ny - baseY;
            c.x = nx;
            c.y = ny;
        }
    }

    

    @Override
    protected void onDraw(Canvas canvas) {
        
        
        try {
            drawBeat(canvas);
        } catch (Throwable ignored) {
        }
        
        
        
        
        Lang.zh = isOnUnsafe("Language");
        try {
            frame(canvas);
        } catch (Throwable t) {
            
            
            reportCrash("draw", t);
            try { drawCrashBanner(canvas); } catch (Throwable ignored) {
            }
        }
        try {
            
            
            if (needNextFrame()) postInvalidateOnAnimation();
            else                  postInvalidateDelayed(IDLE_MS);
        } catch (Throwable ignored) {
        }
    }

     








    private void drawBeat(Canvas cv) {
        final long now = System.currentTimeMillis();
        if (now > beatUntil) return;
        if (!beatLogged) {
            beatLogged = true;
            com.onyx.clickgui.xposed.Diag.log("首帧 onDraw 到达：view=" + getWidth() + "x"
                    + getHeight() + " screen=" + screenW + "x" + screenH + " overlay=" + overlayMode);
        }
        beat.setShader(null);
        beat.setStyle(Paint.Style.FILL);
        beat.setColor(0xE000C853);
        cv.drawRect(0f, 0f, 16f, 16f, beat);
        beat.setColor(0xEE101014);
        cv.drawRect(0f, 16f, 900f, 88f, beat);

        beat.setTextAlign(Paint.Align.LEFT);
        beat.setColor(0xFFFFFFFF);
        beat.setTextSize(20f);
        cv.drawText("Onyx " + com.onyx.clickgui.feature.FeatureBus.VERSION
                + " 已挂载    view=" + getWidth() + "x" + getHeight() + "    U=" + U, 8f, 46f, beat);

        beat.setTextSize(17f);
        beat.setColor(0xFF8FE8B0);
        cv.drawText("点右侧那个白色圆圈圆球 → 打开功能面板", 8f, 76f, beat);
    }

    private void frame(Canvas canvas) {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - lastTick) / 1000f));
        lastTick = now;

        frameCnt++;
        if (now - fpsAt >= 500) {
            fps = frameCnt * 1000f / (now - fpsAt);
            frameCnt = 0;
            fpsAt = now;
        }

        stepAnims(dt);

        if (screenW < 2 || screenH < 2) {
            
            drawSizeHint(canvas);
            return;
        }
        computeScale();
        layoutAll();
        placeFab();
        
        
        
        
        try { refreshEspData(); } catch (Throwable ignored) {
        }
        try { refreshTagData(); } catch (Throwable ignored) {
        }
        try { refreshNetData(); } catch (Throwable ignored) {
        }

        final float s = 0.965f + 0.035f * anim;
        final float dcx = designW() * 0.5f, dcy = designH() * 0.5f;

        
        int save = canvas.save();
        canvas.scale(U, U);
        if (s < 0.9999f) canvas.scale(s, s, dcx, dcy);

        if (anim > 0.002f) {
            drawMenu(canvas, now);
        } else {
            hits.clear();
        }
        
        if (anim < 0.45f || hudEdit()) drawHud(canvas);
        
        drawNotifs(canvas, now);
        
        drawEsp(canvas);
        
        drawTags(canvas);

        canvas.restoreToCount(save);

        
        drawFab(canvas);

        if (!crashLine.isEmpty()) drawCrashBanner(canvas);
    }

    

    private String crashLine = "";

    private void reportCrash(String where, Throwable t) {
        String s = where + ": " + t.getClass().getSimpleName()
                 + (t.getMessage() == null ? "" : (" " + t.getMessage()));
        if (!s.equals(crashLine)) {
            crashLine = s;
            com.onyx.clickgui.xposed.XLog.e("ClickGUI 异常(" + where + ")", t);
        }
    }

     
    private void drawSizeHint(Canvas cv) {
        try {
            beat.setShader(null);
            beat.setStyle(Paint.Style.FILL);
            beat.setColor(0xE0B00020);
            cv.drawRect(0f, 0f, 780f, 124f, beat);
            beat.setColor(0xFFFFFFFF);
            beat.setTextSize(22f);
            beat.setTextAlign(Paint.Align.LEFT);
            cv.drawText("Onyx：View 尺寸未就绪（量测问题）", 12f, 34f, beat);
            beat.setTextSize(17f);
            cv.drawText("screen=" + screenW + "x" + screenH
                    + "   view=" + getWidth() + "x" + getHeight(), 12f, 66f, beat);
            cv.drawText("详情见 onyx.log", 12f, 96f, beat);
        } catch (Throwable ignored) {
        }
    }

     
    private void drawCrashBanner(Canvas cv) {
        if (crashLine.isEmpty()) return;
        String s = crashLine;
        if (s.length() > 70) s = s.substring(0, 70);
        txt.setShader(null);
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setTextSize(24f);
        float w = txt.measureText(s) + 24f;
        fill.setShader(null);
        fill.setColor(0xE0B00020);
        cv.drawRect(0, 0, w, 38f, fill);
        fill.setColor(0xFFFFFFFF);
        cv.drawText(s, 12f, 26f, txt);
    }

    private void stepAnims(float dt) {
        final float k = 1f - (float) Math.exp(-dt * 15f);
        boolean busy = false;

        float t = guiOpen ? 1f : 0f;
        anim += (t - anim) * k;
        if (Math.abs(t - anim) < 0.004f) anim = t; else busy = true;

        for (Category c : cats) {
            for (Feature m : c.mods) {
                float et = m.expanded ? 1f : 0f;
                m.anim += (et - m.anim) * (1f - (float) Math.exp(-dt * 17f));
                if (Math.abs(et - m.anim) < 0.004f) m.anim = et; else busy = true;

                for (Feature.Setting st : m.settings) {
                    if (st.type == Feature.FLOAT || st.type == Feature.INT) {
                        st.shown += (st.t() - st.shown) * 0.28f;
                        if (Math.abs(st.t() - st.shown) < 0.002f) st.shown = st.t(); else busy = true;
                    }
                }
            }
        }
        animBusy = busy;
    }

     











    private boolean needNextFrame() {
        if (animBusy) return true;                       
        if (!notifs.isEmpty()) return true;              
        if (hudEdit()) return true;                      
        if (dragHud != null || dragHudMoved) return true;
        if (dragCat != null || dragMoved) return true;   
        if (fabPressed || fabDragged) return true;
        if (mx > -1e8f || my > -1e8f) return true;       
        
        try {
            if (isOnUnsafe("ESP") || isOnUnsafe("Nametags")) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void drawMenu(Canvas cv, long now) {
        
        fRect(cv, 0, 0, designW(), designH(), A(C_MASK, anim));

        
        drawTabs(cv);

        
        hits.clear();
        for (Category c : cats) if (c != dragCat) drawColumn(cv, c);
        if (dragCat != null) drawColumn(cv, dragCat);

        
        drawDropdown(cv);

        
        drawStatusBar(cv);

        
    }

    

    private void drawTabs(Canvas cv) {
        final float fs = TAB_FS, pad = TAB_PAD;

        float totalW = 0f;
        for (String n : tabNames) totalW += mw(txt, n, fs) + pad;
        totalW -= pad;

        float x = (designW() - totalW) * 0.5f;
        final float y = TAB_Y;
        final float bandH = fs * 1.6f;

        fRound(cv, x - 14f, y - 6f, totalW + 28f, bandH + 12f, 9f, A(TAB_BG, 0.78f * anim));

        float activeX = x, activeW = 0f;
        for (int i = 0; i < tabNames.length; i++) {
            final float w = mw(txt, tabNames[i], fs) + pad;
            final boolean act = (i == tabIdx);
            fTextV(cv, act ? bld : txt, tabNames[i], x + pad * 0.5f, y, bandH, fs,
                   A(act ? TXT_ON : TXT_OFF, anim), Paint.Align.LEFT);
            if (act) { activeX = x; activeW = w; }
            x += w;
        }

        final float t = 1f - (float) Math.exp(-0.016f * 12f);
        tabUx += (activeX - tabUx) * t;
        tabUw += (activeW - tabUw) * t;
        fRound(cv, tabUx, y + bandH - 1f, tabUw, 2f, 1f, A(c1, anim));
    }

    

    private void drawColumn(Canvas cv, Category c) {
        final float h = columnH(c);

        fRound(cv, c.x, c.y, c.w, h, D_RAD, A(PANEL_BG, 0.97f * anim));
        strokeRound(cv, c.x, c.y, c.w, h, D_RAD, A(0xFFFFFFFF, 0.06f * anim), 1f);

        
        fRound(cv, c.x, c.y, c.w, D_HEADER, D_RAD, A(HEADER_BG, anim));
        fRect(cv, c.x, c.y + D_HEADER - D_RAD, c.w, D_RAD, A(HEADER_BG, anim));

        
        fRound(cv, c.x + 9f, c.y + D_HEADER * 0.5f - 7f, 3f, 14f, 1.5f, A(c1, anim));

        
        fTextV(cv, bld, Lang.t(c.name), c.x + 19f, c.y, D_HEADER, 15f, A(TXT_ON, anim), Paint.Align.LEFT);

        
        fRound(cv, c.x + c.w - 22f, c.y + D_HEADER * 0.5f - 1.5f, 10f, 3f, 1.5f, A(TXT_OFF, anim));

        Hit hc = new Hit();
        hc.kind = H_CAT;
        hc.c = c;
        hc.x = c.x; hc.y = c.y; hc.w = c.w; hc.h = D_HEADER;
        hits.add(hc);

        if (!c.open) return;

        float ry = c.y + D_HEADER + 3f;
        for (Feature m : c.mods) {
            drawModule(cv, c, m, ry);
            ry += moduleH(m);
        }
    }

    private void drawModule(Canvas cv, Category c, Feature m, float ry) {
        final float rowX = c.x + 4f;
        final float rowW = c.w - 8f;
        final boolean hot = (pressed != null && pressed.m == m);

        if (hot) {
            fRound(cv, rowX, ry + 1f, rowW, D_MOD - 2f, 4f, A(0xFFFFFFFF, 0.06f * anim));
        }

        
        final String nm = Lang.t(m.name);
        int col = m.enabled ? TXT_ON : TXT_OFF;
        if (!m.implemented) col = TXT_DIM;
        fTextV(cv, txt, nm, c.x + 15f, ry, D_MOD, 13f, A(col, anim), Paint.Align.LEFT);

        
        {
            float nameW = mw(txt, nm, 13f);
            float dotX = c.x + 17f + nameW + 6f;
            final float cap = c.x + contentX(c) - 46f;
            if (dotX > cap) dotX = cap;

            if (m.implemented) {
                final float d = 5f;
                fRound(cv, dotX, ry + (D_MOD - d) * 0.5f, d, d, d * 0.5f, A(OK_DOT, 0.92f * anim));
            } else {
                fTextV(cv, txt, "N/A", dotX - 2f, ry, D_MOD, 11f, A(NA_TXT, 0.9f * anim), Paint.Align.LEFT);
            }
        }

        
        if (m.hasSettings()) {
            final float ax = c.x + contentX(c) - 40f;
            final float ay = ry + D_MOD * 0.5f;
            final float s = 4f;
            Path p = new Path();
            if (m.expanded) {
                p.moveTo(ax - s, ay + s * 0.6f);
                p.lineTo(ax + s, ay + s * 0.6f);
                p.lineTo(ax, ay - s * 0.8f);
            } else {
                p.moveTo(ax - s, ay - s * 0.6f);
                p.lineTo(ax + s, ay - s * 0.6f);
                p.lineTo(ax, ay + s * 0.8f);
            }
            p.close();
            fill.setShader(null);
            fill.setColor(A(TXT_OFF, anim));
            cv.drawPath(p, fill);
        }

        
        {
            final float tw = 30f, th = 15f;
            final float tx = c.x + contentX(c) - tw;
            final float ty = ry + (D_MOD - th) * 0.5f;
            final int trk = m.enabled ? c1 : (m.implemented ? TRACK_OFF : LOCK_TRK);
            fRound(cv, tx, ty, tw, th, th * 0.5f, A(trk, anim));

            final float kd = th - 5f;
            final float kx = m.enabled ? (tx + tw - kd - 2.5f) : (tx + 2.5f);
            fRound(cv, kx, ty + 2.5f, kd, kd, kd * 0.5f, A(KNOB, anim));
        }

        
        Hit hr = new Hit();
        hr.kind = H_MOD; hr.c = c; hr.m = m;
        hr.x = c.x; hr.y = ry; hr.w = c.w; hr.h = D_MOD;
        hits.add(hr);

        if (m.hasSettings()) {
            Hit ha = new Hit();
            ha.kind = H_ARROW; ha.c = c; ha.m = m;
            ha.x = c.x + contentX(c) - 46f; ha.y = ry;
            ha.w = 16f; ha.h = D_MOD;
            hits.add(ha);
        }

        
        if (m.anim > 0.01f && m.hasSettings()) {
            final float blockH = settingsBlockH(m);
            final float sy0 = ry + D_MOD;
            final float bh = blockH * m.anim;

            fRound(cv, c.x + 6f, sy0, c.w - 12f, bh, 4f, A(SUB_BG, 0.9f * m.anim * anim));

            int save = cv.save();
            clip.reset();
            rectOf(rc, c.x + 6f, sy0, c.w - 12f, bh);
            clip.addRoundRect(rc, 4f, 4f, Path.Direction.CW);
            cv.clipPath(clip);

            float sy = sy0 + 5f;
            for (Feature.Setting st : m.settings) {
                drawSetting(cv, c, m, st, sy, anim * m.anim);
                sy += settingH(st);
            }
            cv.restoreToCount(save);
        }
    }

    

    private void drawSetting(Canvas cv, Category c, Feature m, Feature.Setting s, float sy, float a) {
        final float rowX = c.x + D_INDENT + 4f;
        final float rowW = c.w - D_INDENT - D_PADR - 8f;

        switch (s.type) {
            case Feature.BOOL: {
                fTextV(cv, txt, Lang.t(s.name), rowX, sy, D_SET, 12.5f,
                       A(s.b ? TXT_ON : TXT_OFF, a), Paint.Align.LEFT);

                final float sw = 26f, sh = 14f;
                final float sx = c.x + contentX(c) - sw;
                final float syy = sy + (D_SET - sh) * 0.5f;

                fRound(cv, sx, syy, sw, sh, sh * 0.5f, A(s.b ? c1 : TRACK_OFF, a));
                final float kd = sh - 5f;
                final float kx = s.b ? (sx + sw - kd - 2.5f) : (sx + 2.5f);
                fRound(cv, kx, syy + 2.5f, kd, kd, kd * 0.5f, A(KNOB, a));

                Hit h = new Hit();
                h.kind = H_SET; h.c = c; h.m = m; h.s = s;
                h.x = sx - 6f; h.y = sy; h.w = sw + 12f; h.h = D_SET;
                hits.add(h);
                break;
            }

            case Feature.FLOAT:
            case Feature.INT: {
                fTextV(cv, txt, Lang.t(s.name), rowX, sy, 17f, 12.5f, A(TXT_OFF, a), Paint.Align.LEFT);
                fTextV(cv, bld, s.text(), c.x + contentX(c), sy, 17f, 12.5f, A(c1, a), Paint.Align.RIGHT);

                final float slX = rowX;
                final float slW = rowW;
                final float slY = sy + 17f;
                final float slH = 7f;

                fRound(cv, slX, slY, slW, slH, slH * 0.5f, A(TRACK_BG, a));

                final float t = clamp01((float) s.shown);
                if (t > 0.001f) {
                    fRound(cv, slX, slY, slW * t, slH, slH * 0.5f, A(c1, a));
                }

                final float hd = 13f;
                final float hx = slX + slW * t - hd * 0.5f;
                fRound(cv, hx, slY + slH * 0.5f - hd * 0.5f, hd, hd, hd * 0.5f, A(0xFFFAFAFE, a));

                Hit h = new Hit();
                h.kind = H_SLIDER; h.c = c; h.m = m; h.s = s;
                h.x = slX - 3f; h.y = sy; h.w = slW + 6f; h.h = D_SLD;
                hits.add(h);
                break;
            }

            case Feature.ENUM: {
                fTextV(cv, txt, Lang.t(s.name), rowX, sy, D_SET, 12.5f, A(TXT_OFF, a), Paint.Align.LEFT);

                final float bw = 104f, bh = 18f;
                final float bx = c.x + contentX(c) - bw;
                final float by = sy + (D_SET - bh) * 0.5f;
                final boolean open = (drop != null && drop.s == s);

                fRound(cv, bx, by, bw, bh, 4f, A(open ? DROP_OPEN : TRACK_BG, a));

                String cur = Lang.t(s.text());
                final float avail = bw - 24f;
                if (mw(txt, cur, 11.5f) > avail) {
                    while (cur.length() > 1 && mw(txt, cur + "\u2026", 11.5f) > avail) {
                        cur = cur.substring(0, cur.length() - 1);
                    }
                    cur = cur + "\u2026";
                }
                fTextV(cv, txt, cur, bx + 8f, by, bh, 11.5f, A(TXT_ON, a), Paint.Align.LEFT);

                fRound(cv, bx + bw - 14f, by + bh * 0.5f - 1f, 7f, 2f, 1f, A(TXT_OFF, a));

                Hit h = new Hit();
                h.kind = H_ENUM; h.c = c; h.m = m; h.s = s;
                h.x = bx - 4f; h.y = by - 4f; h.w = bw + 8f; h.h = bh + 8f;
                hits.add(h);
                break;
            }

            case Feature.ACT: {
                
                
                final boolean isBind = "bind".equals(s.action);
                final boolean has = (m.bind != null && m.bind.on);

                fTextV(cv, txt, Lang.t(s.name), rowX, sy, D_SET, 12.5f, A(TXT_OFF, a), Paint.Align.LEFT);

                final float bw = isBind ? 78f : 30f, bh = 17f;
                final float bx = c.x + contentX(c) - bw;
                final float by = sy + (D_SET - bh) * 0.5f;

                int bg = TRACK_BG;
                if (isBind && has) bg = LOCK_TRK;
                fRound(cv, bx, by, bw, bh, 4f, A(bg, a));

                final String val;
                if (isBind) val = has ? Lang.t("SHOWN") : Lang.t("ADD");
                else        val = "\u2715";
                final int vcol = isBind ? (has ? c1 : TXT_OFF) : (has ? TXT_ON : TXT_DIM);
                fTextV(cv, bld, val, bx + bw * 0.5f, by, bh, 11f, A(vcol, a), Paint.Align.CENTER);

                Hit h = new Hit();
                h.kind = H_ACT; h.c = c; h.m = m; h.s = s;
                h.x = bx - 6f; h.y = sy; h.w = bw + 12f; h.h = D_SET;
                hits.add(h);
                break;
            }
        }
    }

    

    private void drawDropdown(Canvas cv) {
        if (drop == null || drop.n <= 0) return;

        final float H = drop.itemH * drop.n + 5f;

        fRound(cv, drop.x, drop.y, drop.w, H, 5f, A(DROP_BG, 0.97f * anim));
        strokeRound(cv, drop.x, drop.y, drop.w, H, 5f, A(0xFFFFFFFF, 0.10f * anim), 1f);

        for (int i = 0; i < drop.n; i++) {
            final float iy = drop.y + 2.5f + drop.itemH * i;
            final boolean sel = (i == drop.s.idx);
            final boolean hov = mx >= drop.x && mx < drop.x + drop.w
                             && my >= iy && my < iy + drop.itemH;

            if (hov) fRect(cv, drop.x + 3f, iy, drop.w - 6f, drop.itemH, A(0xFFFFFFFF, 0.08f * anim));

            final String it = (drop.s.options != null && i < drop.s.options.length)
                              ? Lang.t(drop.s.options[i]) : "";
            fTextV(cv, sel ? bld : txt, it, drop.x + 9f, iy, drop.itemH, 11.5f,
                   A(sel ? c1 : TXT_ON, anim), Paint.Align.LEFT);

            Hit h = new Hit();
            h.kind = H_DROP; h.s = drop.s; h.idx = i;
            h.x = drop.x; h.y = iy; h.w = drop.w; h.h = drop.itemH;
            hits.add(h);
        }
    }

    

    private void drawStatusBar(Canvas cv) {
        final float cx = designW() * 0.5f;
        final float sh = designH();

        fTextV(cv, bld, "Onyx  v" + com.onyx.clickgui.feature.FeatureBus.VERSION, cx, sh - 46f, 16f, 14f,
               A(0xFFCFCFDA, 0.8f * anim), Paint.Align.CENTER);

        int impl = 0, total = 0;
        for (Category c : cats) {
            for (Feature m : c.mods) { total++; if (m.implemented) impl++; }
        }
        fTextV(cv, txt, Lang.t("backends") + ": " + impl + " / " + total
                        + "      \u2022  " + Lang.t("fps") + " " + Math.round(fps),
               cx, sh - 80f, 14f, 12f, A(TXT_OFF, 0.85f * anim), Paint.Align.CENTER);

        String st = nativeStatus;
        if (st.length() > 72) st = st.substring(0, 72) + "\u2026";
        fTextV(cv, txt, st, cx, sh - 26f, 14f, 12f,
               A(0xFF8FE8B0, 0.85f * anim), Paint.Align.CENTER);
    }

    
    
    

    private boolean hudEdit() {
        Feature e = byName("HUD Editor");
        return e != null && e.enabled;
    }

    private float hudX(int id, float def) {
        final float v = sHudX[id];
        return Float.isNaN(v) ? def : v;
    }

    private float hudY(int id, float def) {
        final float v = sHudY[id];
        return Float.isNaN(v) ? def : v;
    }

     
    private void markHud(Canvas cv, int id, float x, float y, float w, float h, boolean edit) {
        if (!edit) return;
        Hit t = new Hit();
        t.kind = H_HUD;
        t.idx = id;
        t.x = x;
        t.y = y;
        t.w = w + 6f;
        t.h = h + 6f;
        hudHits.add(t);
        
        fRect(cv, x - 3f, y - 3f, w + 6f, h + 6f, A(c1, 0.16f));
        strokeRound(cv, x - 3f, y - 3f, w + 6f, h + 6f, 3f, A(c1, 0.95f), 1.5f);
    }

    private Hit hitHud(float x, float y) {
        for (int i = hudHits.size() - 1; i >= 0; i--) {
            Hit h = hudHits.get(i);
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) return h;
        }
        return null;
    }

    private void drawHud(Canvas cv) {
        final boolean edit = hudEdit();
        final float a = edit ? 1f : clamp01(1f - anim / 0.45f);
        if (a <= 0.01f) return;

        hudHits.clear();

        drawHudWatermark(cv, a, edit);
        drawHudCoords(cv, a, edit);
        drawHudArrayList(cv, a, edit);
        drawHudFps(cv, a, edit);
        drawHudNet(cv, a, edit);
        drawHudTps(cv, a, edit);
        drawHudQuickToggles(cv, a, edit);

        if (edit) drawHudEditTip(cv);
    }

     








    private void drawHudQuickToggles(Canvas cv, float a, boolean edit) {
        
        
        float autoY = 88f;

        for (Category c : cats) {
            for (Feature m : c.mods) {
                if (m.bind == null || !m.bind.on) continue;

                float sc = m.bind.size;
                if (Float.isNaN(sc) || sc < 0.5f || sc > 2.5f) sc = 1.0f;

                final float fs = 10.5f * sc;
                final float h  = fs + 12f;
                final String t = Lang.t(m.name);
                final float w  = mw(bld, t, fs) + 16f + fs * 0.9f + 8f;

                float x = m.bind.x, y = m.bind.y;
                if (Float.isNaN(x) || Float.isNaN(y)) {
                    x = 6f;
                    y = autoY;
                    autoY += h + 3f;
                }
                if (x < 0f) x = 0f;
                if (y < 0f) y = 0f;

                final boolean on = m.enabled;

                fRound(cv, x, y, w, h, 4f, A(on ? 0xCC101018 : 0x99000000, a));
                fRect(cv, x, y, 3f, h, A(on ? c1 : 0xFF55555E, a));
                if (on) strokeRound(cv, x, y, w, h, 4f, A(c1, 0.7f * a), 1.2f);

                fTextV(cv, bld, t, x + 8f, y, h, fs,
                       A(on ? TXT_ON : TXT_OFF, a), Paint.Align.LEFT);

                
                final float dr = Math.max(2f, fs * 0.3f);
                final float dcx = x + w - dr - 6f, dcy = y + h * 0.5f;
                fill.setShader(null);
                fill.setColor(A(on ? c1 : 0xFF44444C, a));
                cv.drawCircle(dcx, dcy, dr, fill);

                
                Hit hit = new Hit();
                hit.kind = H_HUD;
                hit.idx = -2;                      
                hit.m = m;
                hit.x = x - 3f; hit.y = y - 3f;
                hit.w = w + 6f; hit.h = h + 6f;
                hudHits.add(hit);

                if (edit) {
                    fRect(cv, x - 3f, y - 3f, w + 6f, h + 6f, A(c1, 0.16f));
                    strokeRound(cv, x - 3f, y - 3f, w + 6f, h + 6f, 3f, A(c1, 0.95f), 1.5f);
                }
            }
        }
    }

    private void drawHudWatermark(Canvas cv, float a, boolean edit) {
        Feature wm = byName("Watermark");
        if (wm == null || !(wm.enabled || edit)) return;

        Feature.Setting sh = wm.set("Shadow");
        final String t = "Onyx " + com.onyx.clickgui.feature.FeatureBus.VERSION;
        final float fs = 14f;
        final float tw = mw(bld, t, fs) + 20f, th = 22f;
        final float x = hudX(HUD_WM, 7f), y = hudY(HUD_WM, 7f);

        if (sh == null || sh.b) fRound(cv, x + 2f, y + 2f, tw, th, 4f, A(0x66000000, a));
        fill.setShader(new LinearGradient(x, y, x + tw, y + th, c1, c2, Shader.TileMode.CLAMP));
        fill.setColor(A(0xFFFFFFFF, a));
        rectOf(rc, x, y, tw, th);
        cv.drawRoundRect(rc, 4f, 4f, fill);
        fill.setShader(null);
        fTextV(cv, bld, t, x + 10f, y, th, fs, A(0xFFFFFFFF, a), Paint.Align.LEFT);
        markHud(cv, HUD_WM, x, y, tw, th, edit);
    }

    private void drawHudCoords(Canvas cv, float a, boolean edit) {
        Feature cd = byName("Coords");
        if (cd == null || !(cd.enabled || edit)) return;

        final String t = playerPos.isEmpty() ? "- / - / -" : playerPos;
        final float w = mw(txt, t, 12.5f) + 14f, h = 20f;
        Feature wm = byName("Watermark");
        final float defY = (wm != null && wm.enabled) ? 34f : 7f;   
        final float x = hudX(HUD_COORD, 6f), y = hudY(HUD_COORD, defY);

        fRect(cv, x, y, w, h, A(0x88000000, a));
        fTextV(cv, txt, t, x + 7f, y, h, 12.5f, A(TXT_ON, a), Paint.Align.LEFT);
        markHud(cv, HUD_COORD, x, y, w, h, edit);
    }

    private void drawHudFps(Canvas cv, float a, boolean edit) {
        Feature fp = byName("FPS Display");
        if (fp == null || !(fp.enabled || edit)) return;

        
        
        final String t = Lang.t("fps") + " " + Math.round(fps);
        final float w = mw(txt, t, 12.5f) + 14f, h = 20f;
        final float x = hudX(HUD_FPS, 6f), y = hudY(HUD_FPS, designH() - 26f);

        fRect(cv, x, y, w, h, A(0x88000000, a));
        fTextV(cv, txt, t, x + 7f, y, h, 12.5f, A(TXT_ON, a), Paint.Align.LEFT);
        markHud(cv, HUD_FPS, x, y, w, h, edit);
    }

    private void drawHudArrayList(Canvas cv, float a, boolean edit) {
        Feature al = byName("ArrayList");
        if (al == null || !(al.enabled || edit)) return;

        Feature.Setting rb = al.set("Rainbow");
        final boolean rainbow = (rb != null && rb.b);
        final float fs = 12.5f, rh = 18f;

        
        float maxW = 60f;
        for (Category c : cats) {
            for (Feature m : c.mods) {
                if (!m.enabled || m == al) continue;
                final float w = mw(bld, Lang.t(m.name), fs) + 14f;
                if (w > maxW) maxW = w;
            }
        }
        final float x = hudX(HUD_LIST, designW() - maxW - 6f);
        final float y = hudY(HUD_LIST, 10f);

        float yy = y;
        for (Category c : cats) {
            for (Feature m : c.mods) {
                if (!m.enabled || m == al) continue;
                final String nm = Lang.t(m.name);
                final float w = mw(bld, nm, fs) + 14f;
                fRect(cv, x, yy, w, rh, A(0x88000000, a));
                fRect(cv, x + w - 3f, yy, 3f, rh, A(rainbow ? hueColor(yy, 0.9f) : c1, a));
                fTextV(cv, bld, nm, x + 6f, yy, rh, fs,
                       A(rainbow ? hueColor(yy, 0.95f) : TXT_ON, a), Paint.Align.LEFT);
                yy += rh + 1f;
            }
        }
        markHud(cv, HUD_LIST, x, y, maxW, Math.max(rh, yy - y), edit);
    }

     
    private void drawHudEditTip(Canvas cv) {
        final String t = Lang.t("HUD Editor — drag boxes to move · turn it off to exit");
        final float fs = 13f;
        final float w = mw(bld, t, fs) + 28f, h = 28f;
        final float x = (designW() - w) * 0.5f;
        fRound(cv, x, 6f, w, h, 6f, A(c1, 0.95f));
        fTextV(cv, bld, t, designW() * 0.5f, 6f, h, fs, A(0xFFFFFFFF, 1f), Paint.Align.CENTER);
    }

    

    private float espFov() {
        Feature e = byName("ESP");
        Feature.Setting s = (e == null) ? null : e.set("FOV");
        if (s == null) return 70f;
        return (float) (s.type == Feature.FLOAT ? s.f : 70);
    }

     
    private void refreshEspData() {
        
        
        final boolean on = isOnUnsafe("ESP") || isOnUnsafe("Xray") || isOnUnsafe("Chest ESP");
        if (!on) {
            espData = null;
            return;
        }
        espData = com.onyx.clickgui.feature.Native.esp(
                (int) screenW, (int) screenH, viewFov());
    }

     
    private void refreshTagData() {
        if (!isOnUnsafe("Name Tags")) {
            tagData = null;
            return;
        }
        tagData = com.onyx.clickgui.feature.Native.tags(
                (int) screenW, (int) screenH, viewFov());
    }

     
    private void refreshNetData() {
        if (!isOnUnsafe("Ping") && !isOnUnsafe("TPS")) return;   
        float[] v = com.onyx.clickgui.feature.Native.netStats();
        if (v != null && v.length >= 3) netData = v;
    }

     
    private float viewFov() {
        Feature f = byName("FOV Changer");
        if (f != null && f.enabled) {
            Feature.Setting s = f.set("FOV");
            if (s != null && s.type == Feature.FLOAT) return (float) s.f;
        }
        return espFov();
    }

     



    private void drawEsp(Canvas cv) {
        final float[] d = espData;
        if (d == null || d.length < 4) return;

        Feature e = byName("ESP");
        final boolean espOn = (e != null && e.enabled);
        Feature.Setting ms = (e == null) ? null : e.set("Mode");
        final boolean outline = espOn && ms != null && ms.idx == 1;
        final float dw = designW(), dh = designH();

        line.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.8f);
        line.setColor(A(c1, 0.95f));
        fill.setShader(null);
        fill.setColor(A(c1, 0.13f));

        for (int i = 0; i + 3 < d.length; i += 4) {
            final float x0 = d[i] * dw, y0 = d[i + 1] * dh;
            final float x1 = d[i + 2] * dw, y1 = d[i + 3] * dh;
            if (x1 - x0 < 0.5f || y1 - y0 < 0.5f) continue;
            rectOf(rc, x0, y0, x1 - x0, y1 - y0);
            if (!outline) cv.drawRect(rc, fill);
            cv.drawRect(rc, line);
        }
    }

     
    private void drawTags(Canvas cv) {
        final float[] d = tagData;
        if (d == null || d.length < 3) return;
        final float dw = designW(), dh = designH();

        for (int i = 0; i + 2 < d.length; i += 3) {
            final float x = d[i] * dw, y = d[i + 1] * dh;
            final float dist = d[i + 2];

            final String t = String.format(java.util.Locale.US, "%.1fm", dist);
            final float fs = 11f;
            final float w = mw(txt, t, fs) + 10f, h = 14f;
            if (x < -w || y < -h || x > dw + w || y > dh + h) continue;

            fRound(cv, x - w * 0.5f, y - h, w, h, 3f, A(0x99000000, 1f));
            fTextV(cv, txt, t, x - w * 0.5f + 5f, y - h, h, fs, A(0xFFFFFFFF, 1f), Paint.Align.LEFT);
        }
    }

     
    private void drawHudNet(Canvas cv, float a, boolean edit) {
        Feature p = byName("Ping");
        if (p == null || !(p.enabled || edit)) return;

        final float jit = (netData != null && netData.length > 0) ? netData[0] : 0f;
        final float pps = (netData != null && netData.length > 1) ? netData[1] : 0f;
        final String t = Lang.t("net") + " " + Math.round(pps) + "pps  \u0394" + Math.round(jit) + "ms";

        final float w = mw(txt, t, 12.5f) + 14f, h = 20f;
        final float x = hudX(HUD_NET, 6f), y = hudY(HUD_NET, designH() - 48f);

        fRect(cv, x, y, w, h, A(0x88000000, a));
        fTextV(cv, txt, t, x + 7f, y, h, 12.5f, A(TXT_ON, a), Paint.Align.LEFT);
        markHud(cv, HUD_NET, x, y, w, h, edit);
    }

     
    private void drawHudTps(Canvas cv, float a, boolean edit) {
        Feature p = byName("TPS");
        if (p == null || !(p.enabled || edit)) return;

        final float tps = (netData != null && netData.length > 2) ? netData[2] : 0f;
        final String t = "TPS " + Math.round(tps);

        final float w = mw(txt, t, 12.5f) + 14f, h = 20f;
        final float x = hudX(HUD_TPS, 6f), y = hudY(HUD_TPS, designH() - 70f);

        fRect(cv, x, y, w, h, A(0x88000000, a));
        fTextV(cv, txt, t, x + 7f, y, h, 12.5f, A(TXT_ON, a), Paint.Align.LEFT);
        markHud(cv, HUD_TPS, x, y, w, h, edit);
    }

    private static int hueColor(float y, float v) {
        try {
            float hue = (y * 2.2f) % 360f;
            if (hue < 0f) hue += 360f;
            return 0xFF000000 | (android.graphics.Color.HSVToColor(
                    new float[]{hue, 0.65f, clamp01(v)}) & 0x00FFFFFF);
        } catch (Throwable t) {
            return 0xFFFFFFFF;
        }
    }

    

    private void drawNotifs(Canvas cv, long now) {
        
        final float x = designW() - NOTIF_W - 12f;
        float bottom = designH() - 44f;

        for (int i = notifs.size() - 1; i >= 0; i--) {
            Notif n = notifs.get(i);
            long age = now - n.born;
            if (age > n.life) { notifs.remove(i); continue; }

            float a = 1f;
            if (age < 130) a = age / 130f;
            else if (age > n.life - 380) a = Math.max(0f, (n.life - age) / 380f);

            final float w = NOTIF_W, h = NOTIF_H;
            final float top = bottom - h;

            final int accent = (n.type == N_INFO) ? c1 : N_ACCENT[n.type];

            fRound(cv, x, top, w, h, 4f, A(DROP_BG, 0.97f * a));
            fRect(cv, x, top + 4f, 1.6f, h - 8f, A(accent, a));

            fRound(cv, x + 6f, top + h * 0.5f - 6f, 12f, 12f, 6f, A(accent, a));
            fTextV(cv, bld, N_ICON[n.type], x + 6f + 6f - mw(bld, N_ICON[n.type], 8f) * 0.5f,
                   top, h, 8f, A(0xFF0D0D11, a), Paint.Align.LEFT);

            fTextV(cv, bld, n.title, x + 24f, top, h * 0.5f + 2f, 10f,
                   A(TXT_ON, a), Paint.Align.LEFT);

            String msg = n.msg == null ? "" : n.msg;
            final float avail = w - 30f;
            if (mw(txt, msg, 9.5f) > avail) {
                while (msg.length() > 1 && mw(txt, msg + "\u2026", 9.5f) > avail) {
                    msg = msg.substring(0, msg.length() - 1);
                }
                msg = msg + "\u2026";
            }
            fTextV(cv, txt, msg, x + 24f, top + h * 0.5f - 2f, h * 0.5f + 2f, 9.5f,
                   A(TXT_OFF, a), Paint.Align.LEFT);

            bottom = top - NOTIF_GAP;
        }
    }

    

     








    private float fabR() {
        return Math.max(26f, 17f * U);
    }

    private void drawFab(Canvas cv) {
        final float r = fabR(), x = fabX, y = fabY;
        final float a = fabPressed ? 0.9f : 1f;

        fill.setShader(null);
        fill.setColor(A(0x66000000, a));
        cv.drawCircle(x + 1.5f * U, y + 2.5f * U, r, fill);

        fill.setShader(new LinearGradient(x - r, y - r, x + r, y + r, c1, c2, Shader.TileMode.CLAMP));
        fill.setColor(A(0xFFFFFFFF, a));
        cv.drawCircle(x, y, r, fill);
        fill.setShader(null);

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);

        
        
        line.setStrokeWidth(3.0f * U);
        line.setColor(A(0x99000000, a));
        cv.drawCircle(x, y, r + 1.5f * U, line);
        line.setStrokeWidth(2.2f * U);
        line.setColor(A(0xFFFFFFFF, a));
        cv.drawCircle(x, y, r, line);

        line.setStrokeWidth(1.8f * U);
        line.setColor(0xFFFFFFFF);
        if (guiOpen) {
            final float d = 5.5f * U;
            cv.drawLine(x - d, y - d, x + d, y + d, line);
            cv.drawLine(x + d, y - d, x - d, y + d, line);
        } else {
            final float w = 7 * U, g = 4 * U;
            cv.drawLine(x - w, y - g, x + w, y - g, line);
            cv.drawLine(x - w, y, x + w, y, line);
            cv.drawLine(x - w, y + g, x + w, y + g, line);
        }
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    
    
    
    
    
    


    

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            return handleTouch(e);
        } catch (Throwable t) {
            reportCrash("touch", t);
            
            slider = null;
            pressed = null;
            drop = null;
            dragCat = null;
            dragHud = null;
            dragMoved = false;
            fabPressed = false;
            fabDragged = false;
            invalidate();
            return true;
        }
    }

    private boolean handleTouch(MotionEvent e) {
        final float sx = e.getX(), sy = e.getY();
        final float fr = fabR() * 1.3f;
        final boolean onFab = Math.hypot(sx - fabX, sy - fabY) <= fr;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (onFab) {
                    fabPressed = true;
                    fabDragged = false;
                    pressX = sx;
                    pressY = sy;
                    invalidate();
                    return true;
                }
                final float ux = toUIx(sx), uy = toUIy(sy);
                final boolean edit = hudEdit();

                
                
                
                
                if (!guiOpen) {
                    final Hit hh = hitHud(ux, uy);
                    if (hh != null) {
                        
                        
                        
                        
                        if (edit || (hh.idx == -2 && hh.m != null)) {
                            dragHud = hh;
                            dragHudGX = ux - hh.x;
                            dragHudGY = uy - hh.y;
                            dragHudMoved = false;
                            dragHudEdit = edit;
                            pressX = sx;
                            pressY = sy;
                            return true;
                        }
                    }
                    return false;
                }

                if (drop != null) {
                    Hit dh = hitTest(ux, uy);
                    if (dh != null && dh.kind == H_DROP) {
                        pressed = dh;
                        pressX = sx;
                        pressY = sy;
                        return true;
                    }
                    drop = null;
                    invalidate();
                    return true;
                }

                Hit h = hitTest(ux, uy);

                
                
                if (edit && h == null) {
                    Hit hh = hitHud(ux, uy);
                    if (hh != null) {
                        dragHud = hh;
                        dragHudGX = ux - hh.x;
                        dragHudGY = uy - hh.y;
                        dragHudMoved = false;
                        dragHudEdit = true;
                        pressX = sx;
                        pressY = sy;
                        return true;
                    }
                }

                
                dragCat = (h != null && h.c != null) ? h.c : catAt(ux, uy);
                if (dragCat != null) {
                    dragLastX = ux;
                    dragLastY = uy;
                    dragDist = 0f;
                    dragMoved = false;
                }

                if (h == null) return inAnyColumn(ux, uy) || anim > 0.5f;

                pressed = h;
                pressX = sx;
                pressY = sy;

                if (h.kind == H_SLIDER) {
                    slider = h.s;
                    h.s.dragging = true;
                    applySlider(h, ux);
                    return true;
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                final float ux = toUIx(sx), uy = toUIy(sy);
                mx = ux;
                my = uy;

                if (fabPressed) {
                    if (!fabDragged && Math.hypot(sx - pressX, sy - pressY) > 5 * U) fabDragged = true;
                    if (fabDragged) {
                        fabX = sx;
                        fabY = sy;
                        placeFab();
                        invalidate();
                    }
                    return true;
                }
                if (dragHud != null) {
                    
                    
                    if (!dragHudMoved && Math.hypot(sx - pressX, sy - pressY) > 5f * U) {
                        dragHudMoved = true;
                    }
                    if (!dragHudMoved) return true;

                    final int id = dragHud.idx;
                    float nx = ux - dragHudGX;
                    float ny = uy - dragHudGY;
                    
                    final float ew = dragHud.w, eh = dragHud.h;
                    if (nx < 0f) nx = 0f;
                    if (ny < 0f) ny = 0f;
                    if (nx > designW() - ew) nx = Math.max(0f, designW() - ew);
                    if (ny > designH() - eh) ny = Math.max(0f, designH() - eh);
                    if (dragHud.m != null && dragHud.m.bind != null) {
                        
                        dragHud.m.bind.x = nx;
                        dragHud.m.bind.y = ny;
                    } else {
                        sHudX[id] = nx;
                        sHudY[id] = ny;
                    }
                    invalidate();
                    return true;
                }
                if (slider != null) {
                    Hit h = new Hit();
                    h.s = slider;
                    h.c = findCat(slider);
                    if (h.c != null) applySlider(h, ux);
                    return true;
                }
                if (!guiOpen) return false;

                
                if (dragCat != null) {
                    final float dx = ux - dragLastX, dy = uy - dragLastY;
                    dragDist += Math.abs(dx) + Math.abs(dy);
                    dragLastX = ux;
                    dragLastY = uy;

                    if (!dragMoved && dragDist > 5f) {
                        dragMoved = true;
                        pressed = null;          
                    }
                    if (dragMoved) {
                        dragCat.ox += dx;
                        dragCat.oy += dy;
                        layoutAll();
                        invalidate();
                        return true;
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (fabPressed) {
                    final boolean tap = !fabDragged && e.getActionMasked() == MotionEvent.ACTION_UP;
                    fabPressed = false;
                    fabDragged = false;
                    if (tap) setGuiOpen(!guiOpen);
                    invalidate();
                    return true;
                }
                if (dragHud != null) {
                    final Hit hd = dragHud;
                    final boolean moved  = dragHudMoved;
                    final boolean wasEd  = dragHudEdit;
                    final boolean wasBind = (hd.m != null && hd.m.bind != null);
                    dragHud = null;
                    dragHudMoved = false;
                    dragHudEdit = false;
                    if (moved && wasBind) {                 
                        try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
                        }
                    }
                    
                    if (!moved && !wasEd && hd.idx == -2 && hd.m != null) {
                        toggleFeature(hd.m);
                    }
                    invalidate();
                    return true;
                }
                if (slider != null) {
                    slider.dragging = false;
                    slider = null;
                    notifyChanged();
                    return true;
                }
                
                if (dragCat != null) {
                    final boolean moved = dragMoved;
                    dragCat = null;
                    dragMoved = false;
                    dragDist = 0f;
                    if (moved) {                 
                        pressed = null;
                        mx = my = -1e9f;
                        invalidate();
                        return true;
                    }
                }
                if (pressed != null) {
                    act(pressed);
                    pressed = null;
                }
                mx = my = -1e9f;
                invalidate();
                return true;
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    private float xfScale() { return 0.965f + 0.035f * anim; }

    private float toUIx(float sx) {
        final float s = xfScale();
        final float dcx = designW() * 0.5f;
        float x = sx / U;
        return dcx + (x - dcx) / s;
    }

    private float toUIy(float sy) {
        final float s = xfScale();
        final float dcy = designH() * 0.5f;
        float y = sy / U;
        return dcy + (y - dcy) / s;
    }

    private Category findCat(Feature.Setting s) {
        for (Category c : cats) {
            for (Feature m : c.mods) {
                for (Feature.Setting x : m.settings) if (x == s) return c;
            }
        }
        return null;
    }

    private boolean inAnyColumn(float x, float y) {
        for (Category c : cats) {
            if (x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + columnH(c)) return true;
        }
        return false;
    }

     
    private Category catAt(float x, float y) {
        for (Category c : cats) {
            if (x >= c.x - 4f && x <= c.x + c.w + 4f
                    && y >= c.y - 4f && y <= c.y + columnH(c) + 4f) {
                return c;
            }
        }
        return null;
    }

    private Hit hitTest(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) return h;
        }
        return null;
    }

    private void act(Hit h) {
        switch (h.kind) {
            case H_CAT:
                h.c.open = !h.c.open;
                layoutAll();
                notifyChanged();
                break;

            case H_MOD:
                toggleFeature(h.m);
                break;

            case H_ARROW:
                h.m.expanded = !h.m.expanded;
                notifyChanged();
                break;

            case H_SET:
                h.s.b = !h.s.b;
                notifyChanged();
                break;

            case H_ENUM: {
                final float bw = 104f, bh = 18f;
                final float bx = h.c.x + contentX(h.c) - bw;
                final float by = h.y + 4f;

                Drop d = new Drop();
                d.s = h.s;
                d.x = bx;
                d.y = by + bh + 2f;
                d.w = bw;
                d.itemH = 20f;
                d.n = (h.s.options == null) ? 0 : h.s.options.length;
                drop = d;
                invalidate();
                break;
            }

            case H_DROP: {
                if (h.s != null) {
                    final int n = (h.s.options == null) ? 0 : h.s.options.length;
                    if (n > 0) {
                        h.s.idx = Math.max(0, Math.min(n - 1, h.idx));
                    }
                    if ("Color".equals(h.s.name) && byName("Theme") != null) {
                        applyTheme(h.s.idx);
                    }
                }
                drop = null;
                notifyChanged();
                break;
            }

            case H_ACT: {
                final Feature m = h.m;
                if (m == null || m.bind == null || h.s == null) break;

                if ("bind".equals(h.s.action)) {
                    
                    if (!m.bind.on) {
                        m.bind.on = true;
                        try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
                        }
                        toast(m.name, Lang.t("Quick toggle added"), N_OK);
                    } else {
                        toast(m.name, Lang.t("Already on screen"), N_INFO);
                    }
                } else {
                    
                    if (m.bind.on) {
                        m.bind.on = false;
                        try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
                        }
                        toast(m.name, Lang.t("Quick toggle removed"), N_OK);
                    } else {
                        toast(m.name, Lang.t("Not on screen"), N_INFO);
                    }
                }
                notifyChanged();
                break;
            }

            default:
                break;
        }
    }

     
    private void toggleFeature(Feature m) {
        if (m == null) return;
        if (!m.implemented) {
            toast(m.name, "N/A — no backend for this feature", N_WARN);
            return;
        }
        if (m.lockOn) {                 
            m.enabled = true;
            toast(m.name, m.lockHint != null ? m.lockHint : "Always on", N_INFO);
            return;
        }
        m.enabled = !m.enabled;
        
        
        
        com.onyx.clickgui.xposed.Diag.log("toggle " + m.name + " = " + m.enabled);

        
        if ("HUD Editor".equalsIgnoreCase(m.name)) {
            if (m.enabled) setGuiOpen(false);
            toast(m.name, m.enabled ? "Edit mode · drag HUD elements" : "HUD layout saved",
                  m.enabled ? N_INFO : N_OK);
            notifyChanged();
            return;
        }

        toast(m.name, m.enabled ? "Enabled" : "Disabled", m.enabled ? N_OK : N_INFO);
        notifyChanged();
    }

    private void applySlider(Hit h, float ux) {
        Feature.Setting s = h.s;
        Category c = h.c != null ? h.c : findCat(s);
        if (s == null || c == null) return;

        final float slX = c.x + D_INDENT + 4f;
        final float slW = c.w - D_INDENT - D_PADR - 8f;
        final float t = slW > 1f ? (ux - slX) / slW : 0f;
        s.setT(t);
        s.shown = s.t();

        
        if ("Size".equals(s.name) && h.m != null && h.m.bind != null) {
            h.m.bind.size = (float) s.f;
            try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
            }
        }
        
        if (h.m != null && "UI Scale".equals(h.m.name) && "Scale".equals(s.name)) {
            sUiScale = (float) s.f;
            try { if (sPrefs != null) sPrefs.edit().putFloat("uiScale", sUiScale).apply(); }
            catch (Throwable ignored) { }
            requestScale();
            return;
        }
        invalidate();
    }

    private void applyTheme(int idx) {
        try {
            final int i = Math.max(0, Math.min(THEMES.length - 1, idx));
            c1 = android.graphics.Color.parseColor(THEMES[i][1]);
            c2 = android.graphics.Color.parseColor(THEMES[i][2]);
            sThemeIdx = i;
        } catch (Throwable t) {
            reportCrash("theme", t);
            c1 = ACCENT;
            c2 = ACCENT_2;
            sThemeIdx = 0;
        }
        invalidate();
    }

    private void notifyChanged() {
        Lang.zh = isOnUnsafe("Language");    
        layoutAll();
        invalidate();
        if (listener != null) listener.onStateChanged();
    }

    

    private void rectOf(RectF r, float x, float y, float w, float h) {
        r.set(x, y, x + w, y + h);
    }

    private void fRect(Canvas cv, float x, float y, float w, float h, int color) {
        if (w <= 0f || h <= 0f) return;
        fill.setShader(null);
        fill.setColor(color);
        rectOf(rc, x, y, w, h);
        cv.drawRect(rc, fill);
    }

    private void fRound(Canvas cv, float x, float y, float w, float h, float r, int color) {
        if (w <= 0f || h <= 0f) return;
        fill.setShader(null);
        fill.setColor(color);
        rectOf(rc, x, y, w, h);
        float rr = Math.min(r, Math.min(w, h) * 0.5f);
        cv.drawRoundRect(rc, rr, rr, fill);
    }

    private void strokeRound(Canvas cv, float x, float y, float w, float h, float r,
                             int color, float sw) {
        line.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(sw);
        line.setColor(color);
        rectOf(rc, x, y, w, h);
        float rr = Math.min(r, Math.min(w, h) * 0.5f);
        cv.drawRoundRect(rc, rr, rr, line);
    }

    private void fTextV(Canvas cv, Paint p, String s, float x, float rowY, float rowH,
                        float size, int color, Paint.Align al) {
        p.setShader(null);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(al);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = rowY + rowH * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        cv.drawText(s == null ? "" : s, x, base, p);
    }

    private float mw(Paint p, String s, float size) {
        p.setTextSize(size);
        return p.measureText(s == null ? "" : s);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

     
    private static int A(int color, float a) {
        int al = (int) (((color >>> 24) & 0xFF) * clamp01(a));
        return (color & 0x00FFFFFF) | (al << 24);
    }
}
