package com.onyx.clickgui.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * 极简 i18n —— 英文是**源串**（代码里写的都是英文），中文靠这张表。
 *
 *   Lang.zh == false → 原样返回（英文）
 *   Lang.zh == true  → 查表，查不到就返回原串
 *
 * 由 ClickGUI 的 Client → Language 模块驱动（开 = 中文 / 关 = 英文）。
 * 所有会画到屏幕上的文案都必须过一遍 Lang.t()，否则切了语言还挂着英文。
 */
public final class Lang {

    /** 当前是否中文 —— 由 ClickGUI 在状态变化时同步 */
    public static volatile boolean zh = false;

    private static final Map<String, String> M = new HashMap<>();

    private Lang() {}

    private static void p(String en, String cn) { M.put(en, cn); }

    static {
        // ── 分类 ──
        p("Combat",   "战斗");
        p("HUD",      "界面");
        p("Movement", "移动");
        p("World",    "世界");
        p("Player",   "玩家");
        p("Visual",   "视觉");
        p("Client",   "客户端");

        // ── 功能名 ──
        p("Kill Aura",    "杀戮光环");
        p("Reach",        "攻击距离");
        p("Criticals",    "暴击");
        p("Velocity",     "击退抑制");
        p("HUD Editor",   "HUD 编辑");
        p("Watermark",    "水印");
        p("ArrayList",    "功能列表");
        p("FPS Display",  "帧率显示");
        p("Coords",       "坐标");
        p("Scaffold",     "搭桥");
        p("Fly",          "飞行");
        p("Auto Jump",    "自动跳跃");
        p("Teleport",     "传送");
        p("Speed",        "加速");
        p("No Fall",      "免摔落");
        p("Sprint",       "疾跑");
        p("Nuker",        "范围破坏");
        p("Auto Break",   "自动挖掘");
        p("Auto Tool",    "自动选工具");
        p("Xray",         "矿物透视");
        p("Game Mode",    "游戏模式");
        p("Hitbox",       "碰撞箱");
        p("Auto Respawn", "自动重生");
        p("ESP",          "实体透视");
        p("Chest ESP",    "箱子透视");
        p("Full Bright",  "夜视");
        p("FOV Changer",  "视场角");
        p("Freecam",      "自由视角");
        p("Name Tags",    "实体名牌");
        p("Ping",         "网络延迟");
        p("TPS",          "服务器 TPS");
        p("Theme",        "主题");
        p("Notifications","通知");
        p("Keybinds",     "快捷开关");
        p("UI Scale",     "界面缩放");
        p("Scale",        "缩放");
        p("Language",     "语言");

        // ── 设置项 ──
        p("Range",     "范围");
        p("Mode",      "模式");
        p("Target",    "目标");
        p("Delay",     "延迟");
        p("Value",     "数值");
        p("Threshold", "阈值");
        p("Dist",      "距离");
        p("Width",     "宽度");
        p("Height",    "高度");
        p("Type",      "类型");
        p("FOV",       "视场角");
        p("Shadow",    "阴影");
        p("Rainbow",   "彩虹");
        p("Color",     "配色");
        // ★ Bind / Unbind / Size 故意不翻译 —— 用户要求这三项保持英文原样。

        // ── 枚举选项 ──
        p("Single",    "单体");
        p("Multi",     "多体");
        p("Up",        "上升");
        p("Forward",   "前进");
        p("Survival",  "生存");
        p("Creative",  "创造");
        p("Adventure", "冒险");
        p("Box",       "方框");
        p("Outline",   "描边");
        // ★ Kill Aura / Hitbox 的 Target 下拉
        p("Living",    "生物");
        p("Players",   "玩家");
        p("Both",      "全部");
        p("Self",      "自己");

        // ── 主题名（THEMES[i][0]，走 Lang.t 后画到下拉里）──
        p("Cyan",      "青色");
        p("Mint",      "薄荷");
        p("Rose",      "玫瑰");
        p("Amber",     "琥珀");
        p("Blue",      "蓝色");
        p("Lime",      "青柠");
        p("Slate",     "石板");

        // ── 控件/状态文案 ──
        p("On",        "开");
        p("Off",       "关");
        // ★ Bind 那一行的两个动作按钮（只有 Bind 被点开时才出现）
        p("ADD",       "添加");
        p("SHOWN",     "已显示");

        p("backends",  "后端");
        p("fps",       "帧率");
        // ★ HUD 上 Ping 模块那一行的前缀（"NET 120pps Δ3ms"）。
        //   TPS / Onyx 故意不翻 —— 一个是通用术语，一个是品牌名，翻了反而看不懂。
        p("net",       "网络");

        p("N/A — no backend for this feature",  "N/A —— 该功能还没有后端");
        p("Always on",                          "常开");
        p("Always on · use Color to switch",    "常开 · 用 Color 切换配色");
        p("Always on · drag Scale to resize",   "常开 · 拖动 Scale 调整大小");
        p("Edit mode · drag HUD elements",      "编辑模式 · 拖动 HUD 元素摆位置");
        p("HUD layout saved",                   "HUD 位置已记住");
        p("HUD Editor — drag boxes to move · turn it off to exit",
                                                "HUD 编辑模式 · 直接拖动方框摆位置 · 关掉 HUD Editor 退出");
        p("Enabled",   "已开启");
        p("Disabled",  "已关闭");
        // ── 快捷开关（Bind / Unbind 的通知）──
        p("Quick toggle added",   "快捷开关已挂到屏幕上");
        p("Quick toggle removed", "快捷开关已移出屏幕");
        p("Already on screen",    "已经在屏幕上了");
        p("Not on screen",        "还没挂到屏幕上");
    }

    /** 英文源串 → 按当前语言返回显示文本 */
    public static String t(String s) {
        if (s == null || !zh) return s;
        if (s.isEmpty()) return s;
        final String v = M.get(s);
        return v == null ? s : v;
    }
}
