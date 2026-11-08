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

/**
 * 落盘诊断日志。
 *
 * ★ 为什么需要它：注入进游戏进程后，LSPosed 日志要连电脑才能看，
 *   而"UI 到底是没挂上、还是挂上了没尺寸、还是画了看不见"这三种情况
 *   在屏幕上表现完全一样（都是空的）。所以必须有一个**从进程里带得出来**的证据。
 *
 * 落点：context.getExternalFilesDir(null)/onyx.log
 *   → 注入时 Context 是游戏（com.netease.x19），路径即
 *     /sdcard/Android/data/com.netease.x19/files/onyx.log
 *   → 这是应用自己的外部目录，**不需要任何权限**，用文件管理器 / MT管理器 直接能取。
 *
 * ★★ 2.4.9 起：日志**滚动裁剪**（原来只在进程启动时判一次大小，一次会话内无限涨）。
 *   · 每写 {@link #CHECK_EVERY} 条查一次：超过 {@link #MAX_BYTES} 或 {@link #MAX_LINES} 行
 *     → 只保留最后 {@link #KEEP_LINES} 行，其余丢掉（写一行 [trim] 标记）
 *   · **连续重复的行自动折叠**：只写一次，等出现不同的行时补一行「上一条重复 N 次」
 *     （watchdog / computeScale 这类高频输出最能瘦身，往往一次能砍掉一半行数）
 *   全部在单线程 IO 池里做，不占 UI 线程。
 */
public final class Diag {

    private static final String FILE_NAME = "onyx.log";

    /** 超过这个字节数就裁 */
    private static final long MAX_BYTES = 96 * 1024;
    /** 行数上限（用户反馈 589 行太多 —— 这里卡在 400） */
    private static final int MAX_LINES = 400;
    /** 裁剪后保留的尾部行数 */
    private static final int KEEP_LINES = 250;
    /** 每写 N 条检查一次（检查要 stat + 偶尔读全文，别每条都做） */
    private static final int CHECK_EVERY = 16;

    private static final Object LOCK = new Object();

    private static volatile File logFile;
    private static volatile boolean initTried;
    /** ★ 2.4.9：留给 logMem() 用（ApplicationContext，不持有 Activity 以免泄漏） */
    private static volatile Context appCtx;

    /** ↓↓↓ 以下三个只在 IO 单线程池里访问 ↓↓↓ */
    private static int writes;
    private static int dupPending;
    /** ↑↑↑ 以上三个只在 IO 单线程池里访问 ↑↑↑ */

    /** 上一条消息（跨线程读 → 用 LOCK 保护） */
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
            // 上一轮可能跑到一半被杀，先裁一次再接着写（不整份删，保留上下文）
            IO.execute(() -> trim(f, true));
            log("════════ Diag 就绪 " + f.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    /** 诊断日志路径（给界面显示用），未就绪返回 null */
    public static String path() {
        File f = logFile;
        return f == null ? null : f.getAbsolutePath();
    }

    // 上次打内存水位的时间（只在 20Hz tick 里访问，不用同步）
    private static long lastMemAt;

    /**
     * ★ 2.4.9：定期记录**客人机内存水位**。
     *
     * 为什么要它：实机"注入后过一会闪退"最终都表现为 surfaceflinger
     * `no suitable EGLConfig found` —— 那是 GPU 后端分配不出 EGL config 的报错，
     * 而最可能的诱因是内存（宿主只有 7.7G 物理内存、常态占用 90%+，
     * 而 MuMu 这台虚拟机只分了 3G）。
     * 把 availMem / lowMemory 打进日志，就能直接判断"崩之前内存是不是已经见底"，
     * 不用再猜。默认 20 秒一条。
     */
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
        // XLog 通道照常走，LSPosed 日志里也有
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

    /** 带异常 */
    public static void log(String s, Throwable t) {
        XLog.e(s, t);
        final File f = logFile;
        if (f == null) return;

        String name = (s == null ? "" : s);
        synchronized (LOCK) {
            lastMsg = name;   // 异常行不参与折叠
        }

        StringBuilder sb = new StringBuilder(stamp() + "  [E] " + name + " : " + t + "\n");
        try {
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("        at ").append(el).append('\n');
            }
        } catch (Throwable ignored) {
        }
        // 异常行独立成块，前面若挂着待折叠计数先吐出来
        submit(f, sb.toString(), false);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /**
     * @param dup true = 与上一条完全相同 → 不落盘，只累加计数
     */
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

    /**
     * 超限就裁剪：保留最后 KEEP_LINES 行。
     * 全程只在 IO 线程跑；文件本来就被我们卡在 ~96KB，整读进内存没问题。
     */
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

            // 行数没超、且不是强制 → 不用动
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
