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

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  Onyx ClickGUI —— 与 NewDLL 的 Solstice ClickGui 同款
 *
 *  坐标系：**设计坐标系 = NewDLL 的像素值**（1:1 照搬）
 *    · 所有布局/尺寸/字号都写 NewDLL 里的原数字，不做任何换算
 *    · 绘制时统一 canvas.scale(U) 映射到屏幕，命中时统一 /U 逆映射
 *    · 这样位置、比例、间距和桌面上那份完全一致 —— 不会"位置都不一样"
 *
 *  U 的取法（关键，之前就是这步不对才导致看不清/出屏）：
 *    uW = 屏宽 / (左右边距 + N 列 × 220 + (N-1) × 14)   ← 保证所有列塞得进
 *    uH = 屏高 / (顶部 88 + 最高列(全展开) + 底部 92)    ← 保证内容不被切
 *    U  = clamp(min(uW, uH), 0.9, 2.6)
 *    桌面 1080p → U≈1.0，手机横屏 → U≈2.x，字号随之等比放大，看得清。
 *
 *  结构逐条对齐 NewDLL ui/ClickGui.cpp：
 *    · 全屏压暗 rgba(0,0,0,0.42*anim)
 *    · 顶部居中标签栏（胶囊 + accent 下划线）
 *    · 并排多列：分类面板 → 标题栏(accent 竖条 + 折叠横线)
 *                     └ 模块行：名称 + 绿点/N/A + 展开三角 + 胶囊开关
 *                         └ 行内设置区(SubBg)：Bool 胶囊 / Float·Int 双行滑块 / Enum 下拉
 *    · 底部居中状态栏（版本 / backends N/M / native 状态）
 *    · 整块以屏幕中心 0.965→1.0 缩放；命中做逆变换
 * ══════════════════════════════════════════════════════════════════════════
 */
public class ClickGUI extends View {

    // ═══════════════════════════ 对外回调 ═══════════════════════════

    public interface Listener {
        void onGuiToggled(boolean open);

        void onStateChanged();
    }

    // ═══════════════════════════ 主题（照抄 NewDLL） ═══════════════════════════

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

    private static final int C_MASK = 0x6B000000;   // 0.42 * 255

    // ═══════════════════════════ 设计常量（NewDLL 原值，勿改） ═══════════════════════════

    private static final float D_HEADER = 32f;
    private static final float D_MOD    = 26f;
    private static final float D_SET    = 26f;
    private static final float D_SLD    = 34f;
    private static final float D_GAP    = 14f;
    private static final float D_RAD    = 6f;
    private static final float D_INDENT = 14f;
    private static final float D_PADR   = 12f;
    private static final float D_TOP    = 88f;    // 标签栏下面
    private static final float D_MARGIN = 20f;
    private static final float D_BOT    = 92f;    // 底栏 + 呼吸空间
    private static final float W_MAX    = 220f;
    private static final float W_MIN    = 128f;

    private static final float TAB_Y     = 16f;
    private static final float TAB_FS    = 17f;
    private static final float TAB_PAD   = 26f;

    private static final float MIN_U = 0.9f;
    /**
     * ★ 自动缩放的**硬上限**。
     *
     *   2.4.1 之前这里写 2.6，结果是：屏幕一大（2560x1440），自适应算出来的
     *   U 就被推到 2.5~2.6 —— 面板铺满整屏、字 33px，视觉上「全部变大」。
     *   UI 缩放不该随屏幕无限放大（和 PC 游戏的 UI scale 一个道理），
     *   这里顶死在 1.6：字 ≈ 21px，4 列面板约占屏宽 73%。
     *   嫌小/嫌大再拖 Client → UI Scale 的滑块（倍率 0.6 ~ 1.6）。
     */
    private static final float MAX_U = 1.6f;

    // ═══════════════════════════ 通知 ═══════════════════════════

    private static final int N_INFO = 0, N_OK = 1, N_WARN = 2, N_ERR = 3;
    private static final int[] N_ACCENT = {ACCENT, OK_DOT, 0xFFFFB020, 0xFFE5484D};
    private static final String[] N_ICON = {"i", "v", "!", "x"};
    private static final float NOTIF_W = 178f, NOTIF_H = 26f, NOTIF_GAP = 6f;

    // Theme 模块的 Enum 选项
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

    // ═══════════════════════════ 状态 ═══════════════════════════

    private static final class Category {
        final String name;
        final List<Feature> mods = new ArrayList<>();
        boolean open = true;
        /** 设计坐标（= 默认列位 + 拖动偏移） */
        float x, y, w;
        /** 手动拖动的偏移量（设计坐标）—— 0 = 默认列位，可整列拖走 */
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

    /** 设计 → 像素 的统一缩放 */
    private float U = 1.4f;
    private float lastW = -1f, lastH = -1f;

    /**
     * ★ 用户手动倍率（Client → UI Scale）。
     *   = 1.0 时完全用自适应算出来的值；往上/往下微调整体大小。
     *   乘在自适应结果之后，跨 View 重建保留（静态）。
     */
    private static float sUiScale = 1.0f;
    /** 倍率改过 → 强制 computeScale 重算（否则会命中「尺寸没变就早退」的缓存） */
    private boolean scaleDirty = false;
    private static android.content.SharedPreferences sPrefs;

    private float screenW, screenH;          // 像素
    private float catW = W_MAX;              // 设计

    private boolean guiOpen = true;
    private boolean overlayMode = false;
    private float mx = -1e9f, my = -1e9f;    // 设计坐标下的指针

    private float anim = 0f;
    /** ★ 2.4.9：stepAnims 里有没有"还没停"的动画（决定要不要续 60fps） */
    private boolean animBusy = false;
    /** ★ 2.4.9：空闲时的心跳间隔（ms）。60fps 只在画面真会变时才要。 */
    private static final long IDLE_MS = 250L;
    private long lastTick = 0;
    private float fps = 0f;
    private int frameCnt = 0;
    private long fpsAt = 0;

    private Drop drop;
    private Feature.Setting slider;
    private Hit pressed;
    private float pressX, pressY;

    /** 整列拖动（手指按住任意一列拖动，位移超过阈值才算拖，否则仍是点击） */
    private Category dragCat;
    private float dragLastX, dragLastY, dragDist;
    private boolean dragMoved;

    // ═══ HUD 元素摆位（HUD Editor）═══
    //  0=Watermark 1=Coords 2=FPS Display 3=ArrayList 4=Ping 5=TPS
    //  x/y 为设计坐标；NaN = 还没拖过 → 用各自的默认位置。
    //  static：跨 View 重建记忆，摆好的位置重开面板不会丢。
    private static final int HUD_WM = 0, HUD_COORD = 1, HUD_FPS = 2, HUD_LIST = 3,
                             HUD_NET = 4, HUD_TPS = 5, HUD_N = 6;
    private static final float[] sHudX = new float[HUD_N];
    private static final float[] sHudY = new float[HUD_N];
    //  ★ 两个数组都要填 NaN。只填 X 的话 Y 默认是 0.0f，hudY() 拿到 0 会当成
    //    "用户拖到了 y=0"，6 个元素全挤在屏幕顶部同一条线上 → 命中检测只能选到最后一个。
    static { for (int i = 0; i < HUD_N; i++) { sHudX[i] = Float.NaN; sHudY[i] = Float.NaN; } }

    private final List<Hit> hudHits = new ArrayList<>();
    private Hit dragHud;
    private float dragHudGX, dragHudGY;
    /** ★ 2.4.4：这次按下有没有真的拖起来（没拖起来 → 抬手算「点击」） */
    private boolean dragHudMoved;
    /** ★ 2.4.4：这次拖动是从「编辑态」进来的吗（非编辑态拖动 = 直接摆快捷开关） */
    private boolean dragHudEdit;

    private float tabUx = 0f, tabUw = 0f;    // 下划线平滑位置（设计）
    private String[] tabNames = {"Onyx"};
    private int tabIdx = 0;

    private float fabX, fabY;                // 像素
    private boolean fabPlaced = false;
    private boolean fabPressed = false, fabDragged = false;

    /** 主题强调色（由 Theme→Color 下拉驱动）。面板所有的开关/滑块/下划线/高亮都取自 c1，改这里整块 UI 一起变 */
    private int c1 = ACCENT, c2 = ACCENT_2;
    /** 跨 View 重建记忆的主题索引（切完主题重开 GUI 不会跳回紫色） */
    private static int sThemeIdx = 0;

    private Listener listener;
    private String nativeStatus = "native: -";
    private String playerPos = "";
    /** ESP 框（每 4 个一组：x0,y0,x1,y1，0..1 归一化）—— 每帧从 native 拉 */
    private float[] espData;
    /** Name Tags 数据（每 3 个一组：x0,y0,dist）—— 每帧从 native 拉 */
    private float[] tagData;
    /** 网络统计 { 抖动ms, pps, tps } */
    private float[] netData;

    // ═══ 存活心跳：开局 15 秒内在左上角画一块底标 + 一行尺寸读数 ═══
    //  诊断用。价值在于把"完全没挂上"和"挂上了但量成 0 / 画不出来"这两种
    //  在屏幕上表现完全一样的情况区分开 —— 前者连心跳都没有。
    private long beatUntil;
    private final Paint beat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean beatLogged;

    // ═══════════════════════════ 构造 ═══════════════════════════

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
        // UI Scale 落盘（改了倍率重开面板/重启游戏都还在）
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

    // ═══════════════════════════ 模型 ═══════════════════════════

    private void buildModel() {
        cats.clear();

        // ═══════════ 第 0 列：Combat → HUD ═══════════
        //
        //  ★ 2.3.0：这里已经**没有任何 plan() / N/A**。每个模块都接了真后端：
        //     · 直接调用型 —— GameMode::attack / buildBlock / destroyBlock …（33 函数表）
        //     · Hook 型    —— Reach某 getAttackRangeSqr、Criticals/NoFall→isOnGround、
        //                     Full Bright→setupFog、FOV→getFov、Freecam→CameraAvoidance、
        //                     Ping/TPS→sendToServer / normalTick
        //     · 位移型     —— Speed / Sprint / Velocity / Auto Respawn → Actor::teleportTo
        //     · 自绘型     —— ESP / Name Tags / HUD 系列（投影链路自己算）

        Category combat = new Category("Combat");
        combat.mods.add(Feature.mod("Kill Aura", "killaura", "自动攻击范围内实体")
                .fnum("Range", 3.5, 2.0, 6.0, 0.05)
                .en("Mode", new String[]{"Single", "Multi"}, 0)
                // ★ 2.4.5：攻击对象筛选。Living = 只打生物（怪物/动物），
                //   Players = 只打玩家，Both = 全打（默认，= 老行为）
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

        // HUD —— 紧贴 Combat 下面（colOf("HUD") 与 colOf("Combat") 同为第 0 列）
        Category hud = new Category("HUD");
        hud.mods.add(Feature.ui("HUD Editor", "拖动 HUD 元素摆位置").off());
        hud.mods.add(Feature.ui("Watermark", "客户端名胶囊").bool("Shadow", true));
        hud.mods.add(Feature.ui("ArrayList", "列出已开启的功能").bool("Rainbow", false));
        hud.mods.add(Feature.ui("FPS Display", "帧率显示"));
        hud.mods.add(Feature.ui("Coords", "玩家所在方块坐标"));
        hud.mods.add(Feature.mod("Ping", "ping", "网络包速率 / 相邻包间隔抖动（客户端测不到真 RTT）"));
        hud.mods.add(Feature.mod("TPS", "tps", "客户端 tick 速率（hook normalTick 实测）"));
        cats.add(hud);

        // ═══════════ 第 1 列：Movement → World ═══════════

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

        // ═══════════ 第 2 列：Player → Visual ═══════════

        Category player = new Category("Player");
        player.mods.add(Feature.mod("Game Mode", "gamemode", "切换游戏模式")
                .en("Type", new String[]{"Survival", "Creative", "Adventure"}, 1)
                .hk());
        player.mods.add(Feature.mod("Hitbox", "hitbox", "放大碰撞箱（自己 + 可选的目标）")
                // ★ 2.4.5：作用对象筛选。Self = 只改自己（默认，= 老行为），
                //   Living = 额外放大周围生物，Players = 额外放大周围玩家，Both = 都要
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

        // ═══════════ 第 3 列：Client ═══════════

        Category client = new Category("Client");
        // Theme 常开：面板配色不能关，只能靠 Color 下拉切换（lock() 会把 enabled 固定为 true）
        client.mods.add(Feature.ui("Theme", "面板配色")
                .lock("Always on \u00b7 use Color to switch")
                .en("Color", themeNames(), sThemeIdx));
        client.mods.add(Feature.ui("Language", "界面语言 · 开 = 中文 / 关 = 英文").off());
        client.mods.add(Feature.ui("Notifications", "开关功能时弹右下角提示"));
        // ★ 面板整体缩放。1.0 = 自适应值（已顶死在 MAX_U=1.6），往上/往下微调。
        //   ★★ 不能关闭：lock() 让开关恒为"开"且点不动，只留 Scale 滑块可拖。
        client.mods.add(Feature.ui("UI Scale", "面板整体缩放 · 1.0 = 自适应")
                .lock("Always on \u00b7 drag Scale to resize")
                .fnum("Scale", sUiScale, 0.6, 1.6, 0.05));
        cats.add(client);

        for (Category c : cats) {
            for (Feature m : c.mods) {
                // ★ 只有「确实能工作」的纯自绘模块才默认开；
                //   带后端的模块（cheat 开关）一律从「关」开始 —— 不能一进面板就是一片绿的。
                if (m.backend == null && m.implemented && !m.startOff) m.enabled = true;
                m.anim = m.enabled ? 1f : 0f;
                m.expanded = false;          // 同 NewDLL：默认全折叠
                m.anim = 0f;
            }
        }

        Lang.zh = isOnUnsafe("Language");    // 语言状态跟着 Language 模块走
        applyTheme(sThemeIdx);               // 恢复上次选的主题
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

    // ═══════════════════════════ 对外 API ═══════════════════════════

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

    /**
     * ★★★ 2.4.12：**首次运行自动展开一次面板**（6 秒后自动收起）。
     *
     * 为什么必须做：用户连续三次反馈"注入之后不显示 UI"。
     * 而日志每次都显示 View 挂上了、尺寸对、onDraw 在跑 —— 技术上没有问题。
     * 问题出在**产品默认值**上：面板默认收起，屏幕上只剩下右侧一个悬浮球。
     * 对一个第一次用的人来说，"什么都没有"和"只有一个不显眼的小球"
     * 在体感上完全没有区别，他的结论必然就是"模块没生效"。
     *
     * 所以：第一次挂载后把面板自动展开，让他**亲眼看到界面**；
     * 只做一次（记在 onyx_ui/guideShown），之后不再打扰。
     * 拿不到 SharedPreferences 时选择**不展开**（宁可少做一次，也不要每次启动都弹）。
     */
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
        // ★★ 2.4.10：值没变 → 直接返回，**不要 invalidate()**。
        //   这条曾经是无条件的，而 FeatureBus 的主线程 tick 是 20Hz
        //   → 覆盖层被强行 20fps 全屏重绘，把 2.4.9 的 needNextFrame()
        //   省电优化整条抵消掉；游戏启动/加载那几秒 GPU 本来就在抢，
        //   我们等于又叠了一层持续负载。状态串 99% 的时候是同一个值。
        if (v.equals(nativeStatus)) return;
        nativeStatus = v;
        invalidate();
    }

    /** 玩家所在方块坐标（"x / y / z"），空串 = 还没读到 —— 由 FeatureBus 以 20Hz 推过来 */
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
        // ★★ 2.4.5：Client → Notifications 关掉 = 屏幕上不再出现任何右下角提示。
        //   用户实测反馈过：关了 Notifications，开功能还是弹 —— 因为旧代码压根没读这个开关。
        final Feature nf = byName("Notifications");
        if (nf != null && !nf.enabled) return;

        // ★★ 2.4.5：**先把语言同步过来，再翻译**。
        //   Lang.zh 原来只在 notifyChanged() 里更新，而 toggleFeature 是
        //   「先 toast(...) 再 notifyChanged()」—— 于是**切语言那一下自己**的通知
        //   用的是旧语言：开中文时弹 "Language / Enabled"（英文），关中文时弹中文。
        //   用户看到的现象就是"我开了中文，点一下还是有没翻译的"。
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

    // ═══════════════════════════ 测量 ═══════════════════════════

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // ★★ 2.4.10：整条包 try/catch。
        //   onMeasure 是**唯一**没被兜住的 View 回调 —— 它由 ViewRootImpl.performMeasure
        //   直接调用，那一层没有 try/catch，异常会一路冒到 Choreographer.doFrame 的
        //   未捕获处理器，直接干掉游戏进程。而这条路径在 Activity 刚起来、
        //   DecorView 还没量出尺寸时会走 getDefaultDisplay()/资源查询这些
        //   「加载期才可能炸」的分支 —— 正好对应用户报的「加载时点面板容易直接闪退」。
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
        // ① 首选父容器给的规格 —— DecorView 分给 MATCH_PARENT 子 View 的就是当前
        //    真实可用区域（已扣掉导航栏/挖孔），比 getRealMetrics 更准；也保证
        //    View 尺寸和父容器一致，不会出现"量出来比父还大、内容被挤出屏幕"。
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = 0;
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) h = 0;

        // ② 父还没给出尺寸 → 退回物理屏幕
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
        // ③ 最后兜底
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
        // ★ 2.4.10：三步各自独立兜底 —— 原来 placeFab() 落在 try 块**外面**，
        //   computeScale/layoutAll 一炸，placeFab 就不会执行（FAB 停在 0,0 左上角，
        //   表现为「点不到悬浮球」）；而且它自己在 try 外抛异常同样会带走游戏进程。
        try { computeScale(); } catch (Throwable t) { reportCrash("computeScale", t); }
        try { layoutAll(); }   catch (Throwable t) { reportCrash("layoutAll", t); }
        try { placeFab(); }    catch (Throwable t) { reportCrash("placeFab", t); }
    }

    // ═══════════════════════════ 缩放 ═══════════════════════════

    private float designW() { return U > 0.01f ? screenW / U : screenW; }

    private float designH() { return U > 0.01f ? screenH / U : screenH; }

    private void computeScale() {
        if (screenW < 2 || screenH < 2) return;
        if (!scaleDirty && screenW == lastW && screenH == lastH) return;
        scaleDirty = false;
        lastW = screenW;
        lastH = screenH;

        final int cols = Math.max(1, columnCount());

        // 宽度：N 列（含间隔）必须塞得进屏幕
        final float needW = D_MARGIN + cols * W_MAX + (cols - 1) * D_GAP;
        final float uW = screenW / needW;

        // 高度：最高的一列。★ 按「分类展开 + 模块折叠」算 —— 不是「全部展开」。
        //   用「全部展开」估算会把 needH 顶到 2500+ 设计单位，uH = screenH/needH 掉到 0.4，
        //   被 MIN_U=0.9 夹住 → **整个面板所有字缩到 45%**。这就是"UI 全都变小了"。
        final float needH = D_TOP + baseColumnH() + D_BOT;
        final float uH = screenH / needH;

        float u = Math.min(uW, uH);
        if (u > MAX_U) u = MAX_U;          // ★ 硬顶，别让大屏把 UI 撑爆
        if (u < MIN_U) u = MIN_U;

        // 用户倍率（Client → UI Scale），乘在自适应结果之后
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

    /** 面板大小改了 → 下一帧重算 U 并重排 */
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

    /** 分类固定落哪一列 —— 照 NewDLL 的 colOfName，位置永不漂移（HUD 跟 Combat 同列，排它下面） */
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

    // ═══════════════════════════ 尺寸（全设计单位） ═══════════════════════════

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

    /**
     * U（整体缩放）的基准高度 —— ★ 只按「分类展开、模块折叠」估算。
     *
     * 为什么不能用「全部展开」（这是 NewDLL 当年的 bug，本版一模一样复现了一遍）：
     *   估算一旦把每个模块的设置块都算进去，needH 会到 2500+ 设计单位，
     *   uH = screenH / needH 直接掉到 0.4 → 被 MIN_U 夹成 0.9
     *   → **整个面板所有字缩到 0.9/2.0 ≈ 45%**，玩家看到的就是"所有 UI 都变小了"。
     *
     * 真实默认状态就是「分类展开 + 模块折叠」（buildModel 末尾 m.expanded = false），
     * 所以按这个算出来的 U 才是玩家真正看到的比例。
     * 展开某个模块时面板会往下长，但 U 不变 —— 字永远不会因为展开而缩水。
     */
    private float baseColumnH() {
        // ★ 必须按**列**累加：一列里可能叠着多个分类（Combat + HUD 都在第 0 列，
        //   Movement + World 都在第 1 列）。按「单个分类取最大」会严重低估真实高度
        //   （223 vs 实际 382）→ uH 偏大 → U 被顶到上限 → 面板和屏幕对不上。
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

    /** 单个分类在「展开但模块全折叠」下的高度（≈ columnH，但 anim 不动） */
    private float columnHBase(Category c) {
        if (!c.open) return D_HEADER;
        return D_HEADER + 4f + c.mods.size() * D_MOD + 5f;
    }

    private float contentX(Category c) { return c.w - D_PADR - 4f; }

    // ═══════════════════════════ 排版 ═══════════════════════════

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

            // 默认列位 + 手动拖动偏移，再夹进屏幕 —— 拖走的列不会被甩出去找不回来
            final float ch   = Math.max(D_HEADER, columnH(c));
            final float maxX = Math.max(2f, sw - w - 2f);
            final float maxY = Math.max(2f, designH() - ch - 2f);

            float nx = baseX + c.ox;
            float ny = baseY + c.oy;
            if (nx < 2f)  nx = 2f;
            if (nx > maxX) nx = maxX;
            if (ny < 2f)  ny = 2f;
            if (ny > maxY) ny = maxY;

            c.ox = nx - baseX;    // 回写：夹住之后手指往回拖立刻响应，不会"卡住一段"
            c.oy = ny - baseY;
            c.x = nx;
            c.y = ny;
        }
    }

    // ═══════════════════════════ 绘制 ═══════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        // ① 心跳 —— 第一条语句，不依赖任何模型/主题/尺寸字段。
        //    它能画出来 = onDraw 在跑、View 有尺寸；画不出来 = 挂载或量测层没通。
        try {
            drawBeat(canvas);
        } catch (Throwable ignored) {
        }
        // ★★ 2.4.5：每帧把语言状态同步过来再画。
        //   原来只在 notifyChanged() 里更新，而那条路径要等一次点击才走 ——
        //   结果是「切了语言，这一帧还在用旧语言画」。
        //   byName 是 7 个分类的线性查找（~40 次字符串比较），60fps 下可忽略。
        Lang.zh = isOnUnsafe("Language");
        try {
            frame(canvas);
        } catch (Throwable t) {
            // 注入进游戏进程后，任何未捕获异常 = 整个游戏进程崩。
            // UI 层的任何意外都不许外泄，记日志 + 屏幕上留一行字。
            reportCrash("draw", t);
            try { drawCrashBanner(canvas); } catch (Throwable ignored) {
            }
        }
        try {
            // ★ 2.4.9：不再是无条件 60fps。空闲时退到 IDLE_MS 心跳，
            //   把"覆盖层永久全屏重绘"这笔持续开销直接砍掉。
            if (needNextFrame()) postInvalidateOnAnimation();
            else                  postInvalidateDelayed(IDLE_MS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开局 40 秒的存活心跳：左上角一块绿底 + 两行读数（诊断 + 操作提示）。
     *
     * ★ 2.4.11：从 15 秒延到 40 秒、并加一行操作指引。
     *   上版用户反馈"注入之后不显示 UI"，而当时面板默认收起，
     *   屏幕上唯一的东西是右侧一个低对比度的小球 —— 他的结论自然就是"没显示"。
     *   现在这 40 秒里，屏幕左上角会明确写出**版本号 + View 真实尺寸 + 怎么打开面板**，
     *   既是一眼可辨的"模块活着"证据，也顺手解决了"找不到入口"。
     */
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
            // 不再静默返回 —— 尺寸为 0 必须在屏幕上看得见，否则和"没挂上"无法区分
            drawSizeHint(canvas);
            return;
        }
        computeScale();
        layoutAll();
        placeFab();
        // ★ 这三个 native 取数调用在 drawMenu 之前 —— 一旦抛异常，整帧就断在这里，
        //   面板一个字都画不出来（只剩上层状态条那行字），表现为"功能全打不开、面板拖不动"。
        //   Native 侧虽有 catch(Throwable)，但 isOnUnsafe/viewFov/byName 都在这条链上，
        //   这一层再兜一次，保证 UI 绘制永不被数据源拖垮。
        try { refreshEspData(); } catch (Throwable ignored) {
        }
        try { refreshTagData(); } catch (Throwable ignored) {
        }
        try { refreshNetData(); } catch (Throwable ignored) {
        }

        final float s = 0.965f + 0.035f * anim;
        final float dcx = designW() * 0.5f, dcy = designH() * 0.5f;

        // ── 设计坐标 → 像素 ──
        int save = canvas.save();
        canvas.scale(U, U);
        if (s < 0.9999f) canvas.scale(s, s, dcx, dcy);

        if (anim > 0.002f) {
            drawMenu(canvas, now);
        } else {
            hits.clear();
        }
        // 面板收起 → HUD 露出来；HUD Editor 打开时更得常显（不然没法拖它）
        if (anim < 0.45f || hudEdit()) drawHud(canvas);
        // 通知不跟着面板开合 —— 注入提示、模块开关提示在面板收起时也必须看得见
        drawNotifs(canvas, now);
        // ESP 框：屏幕空间，压在最上面（面板/HUD 之上）
        drawEsp(canvas);
        // Name Tags：同样是屏幕空间，跟 ESP 一层
        drawTags(canvas);

        canvas.restoreToCount(save);

        // ── 悬浮球：屏幕像素坐标，永远压最上层 ──
        drawFab(canvas);

        if (!crashLine.isEmpty()) drawCrashBanner(canvas);
    }

    // ─────────────────────── 兜底 ───────────────────────

    private String crashLine = "";

    private void reportCrash(String where, Throwable t) {
        String s = where + ": " + t.getClass().getSimpleName()
                 + (t.getMessage() == null ? "" : (" " + t.getMessage()));
        if (!s.equals(crashLine)) {
            crashLine = s;
            com.onyx.clickgui.xposed.XLog.e("ClickGUI 异常(" + where + ")", t);
        }
    }

    /** 尺寸未就绪时的诊断条 —— 让"量成 0"在屏幕上可见 */
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

    /** 屏幕左上角一条红底白字的崩溃条 —— 让用户能直接截图，不用连 adb */
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

    /**
     * ★★ 2.4.9：本帧画完，需不需要**立刻**再来一帧？
     *
     * 背景：旧代码无条件 `postInvalidateOnAnimation()` → 覆盖层永远 60fps 全屏重绘。
     * 在 MuMu 这种"虚拟 GPU + Houdini 翻译层"的环境里，这是实打实的持续开销：
     * 每一帧都要 RenderThread 走一遍绘制 + SurfaceFlinger 全屏合成一次 1600x900 图层。
     * CPU/GPU 长期满载 → 宿主 GPU 后端更容易撑不住（实机表现就是过一会 surfaceflinger
     * 报 `no suitable EGLConfig found` 然后整机崩）。
     *
     * 所以：**只在「这一帧之后画面真的会变」时才续 60fps**，否则退到 {@link #IDLE_MS}
     * 的低频心跳（FPS 计数、状态刷新仍然够用）。任何用户交互都会立刻回到 60fps。
     */
    private boolean needNextFrame() {
        if (animBusy) return true;                       // 面板开合 / 展开 / 滑块回弹
        if (!notifs.isEmpty()) return true;              // 通知在走生命周期
        if (hudEdit()) return true;                      // HUD 编辑态要跟手
        if (dragHud != null || dragHudMoved) return true;
        if (dragCat != null || dragMoved) return true;   // 正在拖分类 / 拖动过
        if (fabPressed || fabDragged) return true;
        if (mx > -1e8f || my > -1e8f) return true;       // 屏幕上有指针（悬停/按下）
        // ESP / 名称标签跟视角，必须 60fps
        try {
            if (isOnUnsafe("ESP") || isOnUnsafe("Nametags")) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void drawMenu(Canvas cv, long now) {
        // ① 压暗
        fRect(cv, 0, 0, designW(), designH(), A(C_MASK, anim));

        // ② 顶部标签栏
        drawTabs(cv);

        // ③ 分类列（正在拖的那一列最后画，永远压在最上层）
        hits.clear();
        for (Category c : cats) if (c != dragCat) drawColumn(cv, c);
        if (dragCat != null) drawColumn(cv, dragCat);

        // ④ 下拉浮层（必须晚于所有列）
        drawDropdown(cv);

        // ⑤ 底部状态栏
        drawStatusBar(cv);

        // ⑥ 通知由 onDraw 统一画（面板收起时也要显示），这里不再画
    }

    // ─────────────────────── 顶部标签栏 ───────────────────────

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

    // ─────────────────────── 分类列 ───────────────────────

    private void drawColumn(Canvas cv, Category c) {
        final float h = columnH(c);

        fRound(cv, c.x, c.y, c.w, h, D_RAD, A(PANEL_BG, 0.97f * anim));
        strokeRound(cv, c.x, c.y, c.w, h, D_RAD, A(0xFFFFFFFF, 0.06f * anim), 1f);

        // 标题栏（下面两个角补方）
        fRound(cv, c.x, c.y, c.w, D_HEADER, D_RAD, A(HEADER_BG, anim));
        fRect(cv, c.x, c.y + D_HEADER - D_RAD, c.w, D_RAD, A(HEADER_BG, anim));

        // accent 竖条
        fRound(cv, c.x + 9f, c.y + D_HEADER * 0.5f - 7f, 3f, 14f, 1.5f, A(c1, anim));

        // 标题
        fTextV(cv, bld, Lang.t(c.name), c.x + 19f, c.y, D_HEADER, 15f, A(TXT_ON, anim), Paint.Align.LEFT);

        // 右上折叠指示横线
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

        // 名称
        final String nm = Lang.t(m.name);
        int col = m.enabled ? TXT_ON : TXT_OFF;
        if (!m.implemented) col = TXT_DIM;
        fTextV(cv, txt, nm, c.x + 15f, ry, D_MOD, 13f, A(col, anim), Paint.Align.LEFT);

        // 绿点 / N/A —— 夹在箭头左侧，三者永不重叠
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

        // 展开三角
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

        // 开关胶囊
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

        // 命中区
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

        // 展开的设置区
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

    // ─────────────────────── 设置控件 ───────────────────────

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
                // Bind：点一下进入「等按键」态；再点一下取消等待。
                // Unbind：清掉已绑的键（没绑时显示成暗色 ✕）。
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

    // ─────────────────────── 下拉浮层 ───────────────────────

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

    // ─────────────────────── 底部状态栏 ───────────────────────

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

    // ─────────────────────── HUD ───────────────────────
    //  面板收起时露出来；HUD Editor 打开时**常显**（否则没法拖它）。
    //  每个元素的位置记在 sHudX/sHudY（static，跨 View 重建），NaN = 还没拖过 → 用默认位。

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

    /** 编辑模式下把元素登记成可拖目标，并画一个框提示 */
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
        // 编辑模式下给可拖区域加一层淡底 + 描边 —— 只看描边太细，实战里找不着边
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

    /**
     * ★ 快捷开关 —— 每个 Bind 过的功能，在屏幕（HUD 层）上挂一个**可点的胶囊开关**。
     *
     *   · **非编辑态：点一下 = 开 / 关这个功能**（就是手机控制中心那种快捷开关，
     *     不用再开面板）。所以命中框在非编辑态**也必须登记**，否则点了没反应。
     *   · 编辑态：和其他 HUD 元素一样拖动摆位置。
     *   · 大小由该功能的 Size 滑块决定（0.5 ~ 2.5）。
     *   · Client → Keybinds 关掉就全部隐藏（编辑模式下仍然显示，否则没法调位置）。
     */
    private void drawHudQuickToggles(Canvas cv, float a, boolean edit) {
        // ★ 2.4.4：Client → Keybinds 模块已删除（用户："不知道有啥用"）。
        //   快捷开关现在默认常显 —— 想收起来就点该功能自己的 Unbind。
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

                // 右边的状态点 —— 主题色 = 开，灰 = 关
                final float dr = Math.max(2f, fs * 0.3f);
                final float dcx = x + w - dr - 6f, dcy = y + h * 0.5f;
                fill.setShader(null);
                fill.setColor(A(on ? c1 : 0xFF44444C, a));
                cv.drawCircle(dcx, dcy, dr, fill);

                // ★ 命中框：**编辑态和非编辑态都要登记**（非编辑态靠它响应点击）
                Hit hit = new Hit();
                hit.kind = H_HUD;
                hit.idx = -2;                      // -2 = 快捷开关（HUD 元素是 >= 0）
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
        final float defY = (wm != null && wm.enabled) ? 34f : 7f;   // 默认贴在 Watermark 下面
        final float x = hudX(HUD_COORD, 6f), y = hudY(HUD_COORD, defY);

        fRect(cv, x, y, w, h, A(0x88000000, a));
        fTextV(cv, txt, t, x + 7f, y, h, 12.5f, A(TXT_ON, a), Paint.Align.LEFT);
        markHud(cv, HUD_COORD, x, y, w, h, edit);
    }

    private void drawHudFps(Canvas cv, float a, boolean edit) {
        Feature fp = byName("FPS Display");
        if (fp == null || !(fp.enabled || edit)) return;

        // ★ 2.4.6：标签过 Lang.t —— 注意**必须在构造 t 的时候就翻**，
        //   因为上面 mw(...) 是按 t 量宽度、下面 fTextV 画的也是 t，同一个串才对得齐。
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

        // 先量最宽的一行 —— 默认位置要贴着屏幕右边缘
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

    /** 编辑模式提示 —— 屏幕中上方一条，告诉用户怎么退出 */
    private void drawHudEditTip(Canvas cv) {
        final String t = Lang.t("HUD Editor — drag boxes to move · turn it off to exit");
        final float fs = 13f;
        final float w = mw(bld, t, fs) + 28f, h = 28f;
        final float x = (designW() - w) * 0.5f;
        fRound(cv, x, 6f, w, h, 6f, A(c1, 0.95f));
        fTextV(cv, bld, t, designW() * 0.5f, 6f, h, fs, A(0xFFFFFFFF, 1f), Paint.Align.CENTER);
    }

    // ─────────────────────── ESP ───────────────────────

    private float espFov() {
        Feature e = byName("ESP");
        Feature.Setting s = (e == null) ? null : e.set("FOV");
        if (s == null) return 70f;
        return (float) (s.type == Feature.FLOAT ? s.f : 70);
    }

    /** 每帧拉一次 ESP 框（native 侧算世界→屏幕投影） */
    private void refreshEspData() {
        // ★ ESP / Xray / Chest ESP 三个共用同一条框通道，
        //   只要有一个开着就得拉 —— 不然箱子框画不出来。
        final boolean on = isOnUnsafe("ESP") || isOnUnsafe("Xray") || isOnUnsafe("Chest ESP");
        if (!on) {
            espData = null;
            return;
        }
        espData = com.onyx.clickgui.feature.Native.esp(
                (int) screenW, (int) screenH, viewFov());
    }

    /** Name Tags 数据（实体头顶坐标 + 距离） */
    private void refreshTagData() {
        if (!isOnUnsafe("Name Tags")) {
            tagData = null;
            return;
        }
        tagData = com.onyx.clickgui.feature.Native.tags(
                (int) screenW, (int) screenH, viewFov());
    }

    /** 网络统计（Ping / TPS 两个 HUD 元素共用） */
    private void refreshNetData() {
        if (!isOnUnsafe("Ping") && !isOnUnsafe("TPS")) return;   // 关着就沿用上一份，省 JNI
        float[] v = com.onyx.clickgui.feature.Native.netStats();
        if (v != null && v.length >= 3) netData = v;
    }

    /** 投影用视场角 —— FOV Changer 开着就跟随它，否则画出来的框会跟实际画面错位 */
    private float viewFov() {
        Feature f = byName("FOV Changer");
        if (f != null && f.enabled) {
            Feature.Setting s = f.set("FOV");
            if (s != null && s.type == Feature.FLOAT) return (float) s.f;
        }
        return espFov();
    }

    /**
     * ESP 框 —— 屏幕空间（归一化坐标 × 设计尺寸），跟面板、HUD 都不相干。
     * 数据源是 native 的同一根通道：ESP（实体）+ Chest ESP（方块实体）+ Xray（稀有方块）。
     */
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

    /** Name Tags —— 实体头顶「Onyx 名字牌」：一小条底 + 距离，纯屏幕空间 */
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

    /** Ping HUD：包速率 + 相邻包间隔抖动（客户端拿不到真 RTT，如实标 NET/Δ） */
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

    /** TPS HUD：客户端 tick 速率（hook LocalPlayer::normalTick 实测，20 = 满帧） */
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

    // ─────────────────────── 通知（右下角） ───────────────────────

    private void drawNotifs(Canvas cv, long now) {
        // 右下角：从底往上堆。状态栏那几行是居中的，右边这一条不会跟它打架。
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

    // ─────────────────────── 悬浮球（像素坐标） ───────────────────────

    /**
     * 悬浮球半径（屏幕像素）。
     *
     * ★★ 2.4.11：加**绝对值下限 26px**（原来的 `17 * U` 在高分屏上会缩到 27px 左右，
     *   直径才 54px —— 在 2408x1080 这种大屏上只占宽度的 2.2%，
     *   配色又是低对比的渐变，用户实测反馈就是"注入之后不显示 UI"：
     *   面板默认收起，屏幕上唯一的东西就是这个几乎看不见的小球）。
     *   placeFab / drawFab / 触摸命中**必须用同一个函数**，否则球和命中框会错位。
     */
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

        // ★ 2.4.11：白色描边。渐变填充在天空/水面这种明亮又花哨的画面上
        //   几乎没有对比度，加一圈纯白 + 一圈半透明黑，任何背景上都能立刻找到它。
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

    // ═══════════════════════════ （已移除）键盘捕获 ═══════════════════════════
    //
    //  ★ 2.4.1 删掉：用户要的「快捷键」是**屏幕上那个快捷开关**（手机控制中心那种），
    //    不是键盘按键绑定。原来那套「抢焦点 + 捕获 keyCode + DecorView unhandled-key」
    //    整条链路（含 startCapture/finishCapture/installKeySink/dispatchKeyEvent）
    //    方向就是错的，一并删除 —— 顺带消掉一个会抢游戏输入的不确定因素。


    // ═══════════════════════════ 触摸 ═══════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            return handleTouch(e);
        } catch (Throwable t) {
            reportCrash("touch", t);
            // 状态复位，别让一次异常把交互卡死
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

                // ① 面板收起时（= 平时看 HUD 的状态）：
                //    编辑态 → 拖 HUD 元素（**这是主用法**，打开 HUD Editor 时面板会被
                //    顺手收起，所以这段必须排在最前面，放到 guiOpen 检查后面就永远够不到）；
                //    非编辑态 → **点快捷开关直接开/关功能**（不用开面板）。
                if (!guiOpen) {
                    final Hit hh = hitHud(ux, uy);
                    if (hh != null) {
                        // 编辑态：任何 HUD 元素都能拖。
                        // ★ 2.4.4：**非编辑态也能拖快捷开关**（用户要求：不用专门开
                        //   HUD Editor 才能摆位置）。所以这里不再直接 toggle，而是进入
                        //   「按下待定」：拖起来 = 移动位置，原地抬手 = 开/关功能。
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

                // ② 面板开着时也允许调 HUD，但**只在没压到面板的地方** ——
                //    否则 HUD 框会抢走面板的触摸（ArrayList 贴右边缘，最容易压到列）。
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

                // 先把"这一列可能要被拖走"记下来；到底拖不拖看后面的位移够不够
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
                    // ★ 2.4.4：先判「有没有真的拖起来」。没到阈值就什么都不做 ——
                    //   抬手时才算作为一次点击（快捷开关的开/关）。
                    if (!dragHudMoved && Math.hypot(sx - pressX, sy - pressY) > 5f * U) {
                        dragHudMoved = true;
                    }
                    if (!dragHudMoved) return true;

                    final int id = dragHud.idx;
                    float nx = ux - dragHudGX;
                    float ny = uy - dragHudGY;
                    // 按元素自身尺寸夹边界（原来写死 20/14，宽元素能拖出屏幕）
                    final float ew = dragHud.w, eh = dragHud.h;
                    if (nx < 0f) nx = 0f;
                    if (ny < 0f) ny = 0f;
                    if (nx > designW() - ew) nx = Math.max(0f, designW() - ew);
                    if (ny > designH() - eh) ny = Math.max(0f, designH() - eh);
                    if (dragHud.m != null && dragHud.m.bind != null) {
                        // ★ 热键标签：位置存在 KeyBinds 里，跨 View 重建不丢
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

                // 整列拖动：位移够大就把这一列拖着走，并取消本次点击
                if (dragCat != null) {
                    final float dx = ux - dragLastX, dy = uy - dragLastY;
                    dragDist += Math.abs(dx) + Math.abs(dy);
                    dragLastX = ux;
                    dragLastY = uy;

                    if (!dragMoved && dragDist > 5f) {
                        dragMoved = true;
                        pressed = null;          // 已经是拖动了，别再当成点击
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
                    if (moved && wasBind) {                 // 位置变了 → 落盘
                        try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
                        }
                    }
                    // ★ 没拖动 + 非编辑态 + 命中快捷开关 → 这一次点击 = 开/关功能
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
                // 整列拖动收尾
                if (dragCat != null) {
                    final boolean moved = dragMoved;
                    dragCat = null;
                    dragMoved = false;
                    dragDist = 0f;
                    if (moved) {                 // 拖过了 → 这一下不再算点击
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

    /** 设计坐标落在哪一列上（带 4 的容差，方便抓边缘把整列拖走） */
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
                    // ★ Bind = 把这个功能的**快捷开关**摆到屏幕上（点一下就能开关功能）
                    if (!m.bind.on) {
                        m.bind.on = true;
                        try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
                        }
                        toast(m.name, Lang.t("Quick toggle added"), N_OK);
                    } else {
                        toast(m.name, Lang.t("Already on screen"), N_INFO);
                    }
                } else {
                    // Unbind = 从屏幕上撤掉
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

    /** 开关一个功能 —— 面板点击和屏幕热键走的是同一条路，行为完全一致 */
    private void toggleFeature(Feature m) {
        if (m == null) return;
        if (!m.implemented) {
            toast(m.name, "N/A — no backend for this feature", N_WARN);
            return;
        }
        if (m.lockOn) {                 // 常开模块（Theme / UI Scale）：开关拨不动，只能改设置
            m.enabled = true;
            toast(m.name, m.lockHint != null ? m.lockHint : "Always on", N_INFO);
            return;
        }
        m.enabled = !m.enabled;
        // ★ 2.4.11：把"用户点了哪个功能"落盘。
        //   用户报的正是"点面板就闪退"，而崩溃要看日志时人已经在下一次进程启动之后了
        //   —— 只有把操作序列写进 onyx.log，才能在事后回答"崩之前他到底点了什么"。
        com.onyx.clickgui.xposed.Diag.log("toggle " + m.name + " = " + m.enabled);

        // HUD Editor：开了就顺手把面板收起来，好让 HUD 露出来拖
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

        // ★ Size 滑块直接写回热键表 —— 屏幕上的快捷键标签下一帧就跟着变
        if ("Size".equals(s.name) && h.m != null && h.m.bind != null) {
            h.m.bind.size = (float) s.f;
            try { com.onyx.clickgui.feature.KeyBinds.save(); } catch (Throwable ignored) {
            }
        }
        // ★ UI Scale 滑块 —— 改倍率后必须**重算 U + 重排**，否则只是数值变了、画面不动
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
        Lang.zh = isOnUnsafe("Language");    // ★ 语言状态跟着 Language 模块走
        layoutAll();
        invalidate();
        if (listener != null) listener.onStateChanged();
    }

    // ═══════════════════════════ 绘制小工具（设计坐标） ═══════════════════════════

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

    /** 取颜色并叠加 alpha（a 为 0..1 的额外系数） */
    private static int A(int color, float a) {
        int al = (int) (((color >>> 24) & 0xFF) * clamp01(a));
        return (color & 0x00FFFFFF) | (al << 24);
    }
}
