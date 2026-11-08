package com.onyx.clickgui.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

 


















public final class Diag {

    private static final String FILE_NAME = "onyx.log";

     
    private static final long MAX_BYTES = 96 * 1024;
     
    private static final int MAX_LINES = 400;
     
    private static final int KEEP_LINES = 250;
     
    private static final int CHECK_EVERY = 16;

    private static final Object LOCK = new Object();

    private static volatile File logFile;
    private static volatile boolean initTried;
     
    private static volatile Context appCtx;

     
    private static int writes;
    private static int dupPending;
     

     
    private static String lastMsg;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onyx-diag");
        t.setDaemon(true);
        return t;
    });

    private Diag() {}

    public static synchronized void init(Context c) {
        if (initTried) return;
        initTried = true;
        try {
            if (c != null) {
                try { appCtx = c.getApplicationContext(); } catch (Throwable ignored) { }
            }
            File dir = (c == null) ? null : c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, FILE_NAME);
            logFile = f;
            
            IO.execute(() -> trim(f, true));
            log("════════ Diag 就绪 " + f.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

     
    public static String path() {
        File f = logFile;
        return f == null ? null : f.getAbsolutePath();
    }

    
    private static long lastMemAt;

     









    public static void logMem(long everyMs) {
        final long now = System.currentTimeMillis();
        if (now - lastMemAt < everyMs) return;
        lastMemAt = now;

        Context c = appCtx;
        if (c == null) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);

            Runtime rt = Runtime.getRuntime();
            long jHeap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

            StringBuilder sb = new StringBuilder(96);
            sb.append("mem 可用=").append(mi.availMem / (1024 * 1024)).append("MB")
              .append(" 总=").append(mi.totalMem / (1024 * 1024)).append("MB")
              .append(" 告急线=").append(mi.threshold / (1024 * 1024)).append("MB")
              .append(" 低内存=").append(mi.lowMemory)
              .append(" 本进程Java=").append(jHeap).append("MB")
              .append(" 线程=").append(Thread.activeCount());
            log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    public static void log(String s) {
        
        XLog.i(s);

        final File f = logFile;
        if (f == null || s == null) return;

        final boolean dup;
        synchronized (LOCK) {
            dup = s.equals(lastMsg);
            lastMsg = s;
        }

        final String line = stamp() + "  " + s + "\n";
        submit(f, line, dup);
    }

     
    public static void log(String s, Throwable t) {
        XLog.e(s, t);
        final File f = logFile;
        if (f == null) return;

        String name = (s == null ? "" : s);
        synchronized (LOCK) {
            lastMsg = name;   
        }

        StringBuilder sb = new StringBuilder(stamp() + "  [E] " + name + " : " + t + "\n");
        try {
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("        at ").append(el).append('\n');
            }
        } catch (Throwable ignored) {
        }
        
        submit(f, sb.toString(), false);
    }

    

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

     


    private static void submit(File f, String line, boolean dup) {
        try {
            IO.execute(() -> {
                try (FileWriter w = new FileWriter(f, true)) {
                    if (dup) {
                        dupPending++;
                    } else {
                        if (dupPending > 0) {
                            w.write("                  └─ 同上重复 " + dupPending + " 次\n");
                            dupPending = 0;
                        }
                        w.write(line);
                    }
                } catch (Throwable ignored) {
                }
                if ((++writes % CHECK_EVERY) == 0) trim(f, false);
            });
        } catch (Throwable ignored) {
        }
    }

     



    private static void trim(File f, boolean force) {
        try {
            if (!f.exists()) return;
            long len = f.length();
            if (!force && len <= MAX_BYTES) return;

            int cap = (int) Math.min(len, MAX_BYTES * 4L);
            byte[] buf = new byte[cap];
            int n;
            try (FileInputStream in = new FileInputStream(f)) {
                n = in.read(buf);
            }
            if (n <= 0) return;

            String all = new String(buf, 0, n, "UTF-8");
            String[] lines = all.split("\n", -1);

            
            if (!force && lines.length <= MAX_LINES) return;

            int from = Math.max(0, lines.length - KEEP_LINES);
            StringBuilder sb = new StringBuilder(16 * 1024);
            if (from > 0) {
                sb.append(stamp()).append("  [trim] 日志超过 ")
                        .append(MAX_BYTES / 1024).append("KB / ").append(MAX_LINES)
                        .append(" 行 → 丢弃前 ").append(from).append(" 行\n");
            }
            for (int i = from; i < lines.length; i++) {
                if (lines[i].isEmpty() && i == lines.length - 1) break;
                sb.append(lines[i]).append('\n');
            }
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }
}
