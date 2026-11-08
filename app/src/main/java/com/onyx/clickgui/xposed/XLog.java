package com.onyx.clickgui.xposed;

import java.util.ArrayDeque;

 








public final class XLog {

    public static final String TAG = "Onyx";
    private static final int RING = 12;

    private static final ArrayDeque<String> RING_BUF = new ArrayDeque<>();

    private XLog() {}

    public static void i(String msg) { out("I", msg, null); }

    public static void w(String msg) { out("W", msg, null); }

    public static void e(String msg) { out("E", msg, null); }

    public static void e(String msg, Throwable t) { out("E", msg, t); }

     
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

        
        try {
            de.robv.android.xposed.XposedBridge.log("[" + TAG + "] " + line);
        } catch (Throwable ignored) {
        }

        
        try {
            android.util.Log.println("E".equals(lvl) ? android.util.Log.ERROR
                                                     : android.util.Log.INFO, TAG, body);
        } catch (Throwable ignored) {
        }

        
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
