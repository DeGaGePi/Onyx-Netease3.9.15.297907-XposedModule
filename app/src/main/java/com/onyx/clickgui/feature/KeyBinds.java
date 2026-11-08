package com.onyx.clickgui.feature;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 快捷开关表 —— 按「功能名」存：这个功能要不要在屏幕上挂一个快捷开关、开关多大、摆在哪。
 *
 * ★★ 什么叫「快捷开关」（用户要的，**不是键盘按键绑定**）：
 *   在功能的下拉菜单里点 **Bind** → 屏幕（HUD 层）上**多出一个该功能的浮动开关**
 *   —— 手机控制中心那种「小胶囊按钮」。**点它一下就开/关这个功能**，不用再开面板。
 *   点 **Unbind** 就把它从屏幕上撤掉。
 *   **Size** 是这个开关的缩放滑块（0.5 ~ 2.5）。
 *   （上一版理解成"按键绑定"，方向错了，整条键盘捕获链路已删。）
 *
 * 为什么要独立一张静态表：
 *   ClickGUI 每次重建都会重新 buildModel() 造一批**新的** Feature 对象，
 *   如果只写在 Feature 上，关一次面板就全没了。真身在 KeyBinds 的静态 Map 里，
 *   Feature 只是持一个引用。
 *
 * 落盘用 SharedPreferences（存到游戏自己的数据目录），重启游戏开关还在。
 *   ★ 存 "v2"：v1 是上一版「键盘按键绑定」的格式（第一段是 keyCode），语义已经换掉了，
 *     读进来只会错位，所以直接不读 —— 旧键位数据作废。
 */
public final class KeyBinds {

    /** 一个功能的快捷开关 */
    public static final class Bind {
        /** ★ 是否在屏幕上显示这个功能的快捷开关（Bind 打开 / Unbind 关闭） */
        public boolean on = false;
        /** 屏幕上这个开关的缩放（0.5 ~ 2.5） */
        public float size = 1.0f;
        /** 屏幕位置（设计坐标）；NaN = 没拖过 → 自动从上往下排 */
        public float x = Float.NaN;
        public float y = Float.NaN;

        public boolean isOn() { return on; }
    }

    private static final Map<String, Bind> M = new HashMap<>();
    private static SharedPreferences sp;
    private static boolean loaded;

    private KeyBinds() {}

    /** 幂等；ClickGUI 构造时调一次 */
    public static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        if (c == null) return;
        try {
            sp = c.getApplicationContext().getSharedPreferences("onyx_binds", Context.MODE_PRIVATE);
            final String all = sp.getString("v2", "");
            if (all == null || all.isEmpty()) return;
            for (String row : all.split(";")) {
                if (row.isEmpty()) continue;
                final String[] p = row.split(",");
                if (p.length < 5) continue;
                Bind b = new Bind();
                try {
                    b.on   = (Integer.parseInt(p[1]) != 0);
                    b.size = Float.parseFloat(p[2]);
                    b.x    = Float.parseFloat(p[3]);
                    b.y    = Float.parseFloat(p[4]);
                } catch (Throwable ignored) {
                    continue;
                }
                if (Float.isNaN(b.size) || b.size < 0.5f || b.size > 2.5f) b.size = 1.0f;
                M.put(p[0], b);
            }
        } catch (Throwable ignored) {
            sp = null;
        }
    }

    /** 取（必要时创建）某个功能的快捷开关记录 */
    public static synchronized Bind get(String name) {
        Bind b = (name == null) ? null : M.get(name);
        if (b == null) {
            b = new Bind();
            if (name != null) M.put(name, b);
        }
        return b;
    }

    /** 当前开关表（只读遍历用） */
    public static synchronized Map<String, Bind> all() { return M; }

    /** 有没有任何一个开关挂在屏幕上（扫描/绘制可以据此早退） */
    public static synchronized boolean anyOn() {
        for (Bind b : M.values()) {
            if (b != null && b.on) return true;
        }
        return false;
    }

    public static synchronized void save() {
        if (sp == null) return;
        try {
            final StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Bind> e : M.entrySet()) {
                final Bind b = e.getValue();
                if (b == null || !b.on) continue;      // 没挂出来的不占位
                sb.append(e.getKey()).append(',')
                  .append(b.on ? 1 : 0).append(',')
                  .append(b.size).append(',')
                  .append(b.x).append(',')
                  .append(b.y).append(';');
            }
            sp.edit().putString("v2", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
