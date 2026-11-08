package com.onyx.clickgui.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据模型 —— 对齐 NewDLL 的 Module / Setting / Category 三层结构。
 *
 *   Category ── Module ── Setting
 *     │            │           ├ Bool  胶囊开关
 *     │            │           ├ Float 浮点滑块
 *     │            │           ├ Int   整数滑块
 *     │            │           └ Enum  下拉选择
 *     │            └ enabled / expanded / anim（展开动画）
 *     └ open（整列折叠）
 *
 * ★ implemented = false 的模块，UI 上显示 N/A、开关拨不动 —— 绝不装作能用。
 */
public final class Feature {

    // ═══════════════════════ Setting ═══════════════════════

    public static final int BOOL  = 0;
    public static final int FLOAT = 1;
    public static final int INT   = 2;
    public static final int ENUM  = 3;
    /** 动作按钮（Bind / Unbind）—— 没有"值"，点一下执行 action */
    public static final int ACT   = 4;

    public static final class Setting {
        public final int type;
        public final String name;

        // Bool
        public boolean b;

        // Float
        public double f, fmin, fmax, fstep;

        // Int
        public int i, imin, imax;

        // Enum
        public String[] options;
        public int idx;

        // Act
        public String action;

        // ── UI 交互态（不参与业务） ──
        public boolean dragging;
        /** 滑块手柄的平滑显示值 */
        public double shown;

        public Setting(int type, String name) {
            this.type = type;
            this.name = name;
        }

        /** 0..1 归一化进度（滑块用） */
        public float t() {
            double v, lo, hi;
            if (type == FLOAT) { v = f; lo = fmin; hi = fmax; }
            else               { v = i; lo = imin; hi = imax; }
            if (hi - lo <= 0) return 0f;
            double k = (v - lo) / (hi - lo);
            return (float) (k < 0 ? 0 : (k > 1 ? 1 : k));
        }

        /** 显示文本 */
        public String text() {
            switch (type) {
                case FLOAT: return String.format(java.util.Locale.US, "%.2f", f);
                case INT:   return String.valueOf(i);
                case ENUM:  return (options != null && idx >= 0 && idx < options.length)
                                    ? options[idx] : "?";
                case ACT:   return "";
                default:    return b ? "On" : "Off";
            }
        }

        /** 按 0..1 设值（滑块拖拽用） */
        public void setT(float k) {
            k = k < 0 ? 0 : (k > 1 ? 1 : k);
            if (type == FLOAT) {
                double v = fmin + (fmax - fmin) * k;
                if (fstep > 0) v = Math.round(v / fstep) * fstep;
                f = Math.max(fmin, Math.min(fmax, v));
            } else if (type == INT) {
                int v = (int) Math.round(imin + (imax - imin) * (double) k);
                i = Math.max(imin, Math.min(imax, v));
            }
        }
    }

    // ═══════════════════════ Module ═══════════════════════

    public final String name;
    /** native 功能键；null = 纯自绘（HUD / 配色这类，本来就 100% 可用） */
    public final String backend;
    public final String desc;

    /** 有没有真后端 —— 没后端的画 N/A 并把开关锁死 */
    public final boolean implemented;

    public boolean enabled;
    public boolean expanded;
    /** 展开动画 0..1 */
    public float anim;

    /**
     * 常开模块（Theme / UI Scale）—— 点开关不生效，只能通过它自己的设置项切换。
     * 面板上照样画成"开"，但点不动。
     */
    public boolean lockOn;

    /** lock() 时给出的提示语（点开关时 toast 显示），null = 用默认文案 */
    public String lockHint;

    /** 构建时默认关（普通 ui 模块默认是开的，HUD Editor 这种必须从关开始） */
    public boolean startOff;

    /**
     * ★ 快捷开关记录 —— 只有 hk() 标过的模块才有；null = 这个模块不需要快捷开关。
     *   真身在 KeyBinds 的静态表里（ClickGUI 重建不会丢），这里只是引用。
     */
    public com.onyx.clickgui.feature.KeyBinds.Bind bind;

    public final List<Setting> settings = new ArrayList<>();

    private Feature(String name, String backend, String desc, boolean implemented) {
        this.name = name;
        this.backend = backend;
        this.desc = desc == null ? "" : desc;
        this.implemented = implemented;
    }

    /** 有 native 后端的模块 */
    public static Feature mod(String name, String backend, String desc) {
        return new Feature(name, backend, desc, true);
    }

    /** 纯客户端自绘模块（HUD / 配色）—— 没有后端但确实能工作 */
    public static Feature ui(String name, String desc) {
        return new Feature(name, null, desc, true);
    }

    /**
     * 规划中 —— 该功能需要的游戏函数尚未定案（或链路未打通）。
     * UI 上画 N/A、开关拨不动，点一下弹提示。绝不装作能用。
     */
    public static Feature plan(String name, String desc) {
        return new Feature(name, null, desc, false);
    }

    /** 标记成常开 —— 开关拨不动 */
    public Feature lock() { return lock(null); }

    /** 标记成常开 + 自定义提示语 */
    public Feature lock(String hint) {
        this.lockOn = true;
        this.enabled = true;
        this.lockHint = hint;
        return this;
    }

    /** 标记成默认关闭 */
    public Feature off() { this.startOff = true; this.enabled = false; return this; }

    // ── 链式构造 ──

    public Feature add(Setting s) { settings.add(s); return this; }

    public Feature bool(String n, boolean v) {
        Setting s = new Setting(BOOL, n);
        s.b = v;
        return add(s);
    }

    public Feature fnum(String n, double def, double min, double max, double step) {
        Setting s = new Setting(FLOAT, n);
        s.f = def; s.fmin = min; s.fmax = max; s.fstep = step;
        s.shown = s.t();
        return add(s);
    }

    public Feature inum(String n, int def, int min, int max) {
        Setting s = new Setting(INT, n);
        s.i = def; s.imin = min; s.imax = max;
        s.shown = s.t();
        return add(s);
    }

    public Feature en(String n, String[] opts, int idx) {
        Setting s = new Setting(ENUM, n);
        s.options = opts;
        s.idx = Math.max(0, Math.min(opts.length - 1, idx));
        return add(s);
    }

    /** 动作按钮（Bind / Unbind） */
    public Feature act(String n, String action) {
        Setting s = new Setting(ACT, n);
        s.action = action;
        return add(s);
    }

    /**
     * ★ 给这个功能挂上热键 —— 追加 Bind(绑定) / Unbind(取消绑定) / Size(大小滑块) 三项。
     *   「不需要快捷键的功能」直接不调这个方法，下拉菜单里就不会出现这三项。
     *
     *   同一个功能被 buildModel 重建时会自动拿回上次绑的键和位置（KeyBinds 静态表）。
     */
    public Feature hk() {
        this.bind = com.onyx.clickgui.feature.KeyBinds.get(this.name);
        act("Bind", "bind");
        act("Unbind", "unbind");
        double sz = this.bind.size;
        if (sz < 0.5 || sz > 2.5) sz = 1.0;
        fnum("Size", sz, 0.5, 2.5, 0.1);
        return this;
    }

    // ── 查询 ──

    public Setting set(String n) {
        for (Setting s : settings) {
            if (s.name.equalsIgnoreCase(n)) return s;
        }
        return null;
    }

    public boolean hasSettings() { return !settings.isEmpty(); }
}
