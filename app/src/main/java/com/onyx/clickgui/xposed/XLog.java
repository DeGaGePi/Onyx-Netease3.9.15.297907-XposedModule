package com.onyx.clickgui.xposed;

import java.util.ArrayDeque;

/**
 * 统一日志出口。
 *
 * · 注入进游戏进程时 → XposedBridge.log（LSPosed 日志里能直接看）
 * · 作为普通 App 跑时 → android.util.Log（adb logcat）
 * · 两个都失败也不抛，最多是没日志
 *
 * 另外在内存里留一圈最近日志，方便在模块界面上直接看，不用连 adb。
 */
public final class XLog {

    public static final String TAG = "Onyx";
    private static final int RING = 12;

    private static final ArrayDeque<String> RING_BUF = new ArrayDeque<>();

    private XLog() {}

    public static void i(String msg) { out("I", msg, null); }

    public static void w(String msg) { out("W", msg, null); }

    public static void e(String msg) { out("E", msg, null); }

    public static void e(String msg, Throwable t) { out("E", msg, t); }

    /** 最近日志（新→旧） */
    public static String recent() {
        synchronized (RING_BUF) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String s : RING_BUF) {
                if (n++ > 0) sb.append('\n');
                sb.append(s);
            }
            return sb.toString();
        }
    }

    private static void out(String lvl, String msg, Throwable t) {
        String body = (t == null) ? msg : (msg + " : " + t);
        String line = "[" + lvl + "] " + body;

        synchronized (RING_BUF) {
            RING_BUF.addFirst(line);
            while (RING_BUF.size() > RING) RING_BUF.removeLast();
        }

        // ① Xposed 日志通道（不存在就静默跳过）
        try {
            de.robv.android.xposed.XposedBridge.log("[" + TAG + "] " + line);
        } catch (Throwable ignored) {
        }

        // ② logcat
        try {
            android.util.Log.println("E".equals(lvl) ? android.util.Log.ERROR
                                                     : android.util.Log.INFO, TAG, body);
        } catch (Throwable ignored) {
        }

        // ③ 真炸了的话把堆栈也灌进去，方便定位
        if (t != null) {
            try {
                for (StackTraceElement el : t.getStackTrace()) {
                    de.robv.android.xposed.XposedBridge.log("[" + TAG + "]     at " + el);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
