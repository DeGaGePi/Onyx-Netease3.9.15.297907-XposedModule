package com.onyx.clickgui.feature;

/**
 * native 后端桥。
 *
 * 设计原则（吸取上一版「开了没反应」的教训）：
 *   1. so 缺失 / libminecraftpe.so 还没载入 / 地址没定位 → available() 一律返回 false，
 *      UI 侧据此把开关弹回 + 弹 N/A，绝不装作能用。
 *   2. JNI 全部包 try/catch，native 侧的异常绝不能顺着调用栈把 hook 流程带崩。
 *   3. so 从**模块自己的 APK** 里取（nativeLibraryDir 优先，取不到就解压到 codeCacheDir），
 *      因为注入进游戏进程后 System.loadLibrary 只会找游戏的 lib 目录。
 */
public final class Native {

    /* 与 build.gradle 的 applicationId 必须一致 */
    private static final String MODULE_PKG = "com.onyx.mc";
    private static final String LIB_NAME   = "onyxmc";

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile int attempts;
    private static volatile long lastTryMs;

    /** 最多试 40 次（≥1.2s 间隔 ≈ 48s），足够覆盖「Context 还没就绪」那段窗口 */
    private static final int MAX_ATTEMPTS = 40;

    private Native() {}

    // ───────────────────────── 加载 ─────────────────────────

    /**
     * 能否用 native。
     * 返回 true 只代表 so 已载入；具体某功能能不能用看 {@link #available(String)}。
     */
    public static boolean ensure() {
        if (loaded) return true;
        synchronized (LOCK) {
            if (loaded) return true;
            if (attempts >= MAX_ATTEMPTS) return false;

            final long now = System.currentTimeMillis();
            // 节流：失败后至少隔 1.2s 再试，别被 status()/available() 高频调用烧穿次数
            if (attempts > 0 && now - lastTryMs < 1200) return false;
            lastTryMs = now;
            attempts++;

            try {
                loadImpl();
                loaded = true;
                nInit();
                com.onyx.clickgui.xposed.XLog.i("native 就绪（第 " + attempts + " 次尝试成功）");
                return true;
            } catch (Throwable t) {
                com.onyx.clickgui.xposed.XLog.w("native 第 " + attempts + " 次载入失败: " + t);
                return false;
            }
        }
    }

    /**
     * so 是否已载入本进程。
     * ★ 2.4.10：给主线程 tick 用来决定跑 20Hz 还是 4Hz —— 没载入时
     * `tick()`/`status()` 全是空转，启动期没必要 20Hz 白烧主线程。
     * 注意它**不会触发载入**，纯读标志位，可以在高频路径上随便调。
     */
    public static boolean isLoaded() { return loaded; }

    /** 后台反复重试，直到 so 载入成功（或试满次数） */
    public static void ensureAsync() {
        if (ensure()) return;
        if (attempts >= MAX_ATTEMPTS) return;
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureAsync();
            }
        }, 1500);
    }

    private static void loadImpl() throws Throwable {
        // ① 最常见也最干净：LSPosed 会把模块 APK 挂进目标进程的 classloader，
        //    此时 APK 内嵌的 lib/<abi>/ 是可见的，按名字就能载入
        try {
            System.loadLibrary(LIB_NAME);
            com.onyx.clickgui.xposed.XLog.i("native: 载入方式 ① System.loadLibrary 成功");
            return;
        } catch (Throwable t) {
            com.onyx.clickgui.xposed.XLog.w("native ① loadLibrary 失败: " + t);
        }

        String apk = null;
        try {
            apk = moduleApkPath();
        } catch (Throwable t) {
            com.onyx.clickgui.xposed.XLog.w("native: 取模块 APK 路径失败: " + t);
        }

        // ② zip 路径直载：dlopen 支持 "<apk>!/lib/<abi>/libx.so"，无需解压
        if (apk != null) {
            String[] abis = {"arm64-v8a", "arm64"};
            for (String abi : abis) {
                try {
                    System.load(apk + "!/lib/" + abi + "/lib" + LIB_NAME + ".so");
                    com.onyx.clickgui.xposed.XLog.i("native: 载入方式 ② zip 直载成功 " + abi);
                    return;
                } catch (Throwable t) {
                    com.onyx.clickgui.xposed.XLog.w("native ② zip 直载(" + abi + ") 失败: " + t);
                }
            }
        }

        android.content.Context ctx = appContext();
        if (ctx == null) throw new RuntimeException("拿不到 Context，无法定位模块 so");

        // ③ 模块 APK 的 nativeLibraryDir（extractNativeLibs=true 时 so 已解出来）
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(MODULE_PKG, 0);
            java.io.File f = new java.io.File(ai.nativeLibraryDir, "lib" + LIB_NAME + ".so");
            if (f.exists()) {
                System.load(f.getAbsolutePath());
                com.onyx.clickgui.xposed.XLog.i("native: 载入方式 ③ nativeLibraryDir 成功 " + f);
                return;
            }
        } catch (Throwable t) {
            com.onyx.clickgui.xposed.XLog.w("native ③ nativeLibraryDir 失败: " + t);
        }

        // ④ 兜底：把 so 从模块 APK 里解到本进程私有目录再 load
        if (apk == null) throw new RuntimeException("找不到模块 APK 路径");
        java.io.File out = new java.io.File(ctx.getCodeCacheDir(), "lib" + LIB_NAME + ".so");
        extractSo(apk, out);
        System.load(out.getAbsolutePath());
        com.onyx.clickgui.xposed.XLog.i("native: 载入方式 ④ 解压到私有目录成功 " + out);
    }

    private static String moduleApkPath() {
        // ① 类加载器路径 —— Android 上 APK 的 CodeSource 就是 apk 文件路径
        try {
            java.security.CodeSource cs = Native.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String p = cs.getLocation().getPath();
                if (p != null && p.endsWith(".apk") && new java.io.File(p).exists()) return p;
            }
        } catch (Throwable ignored) {
        }
        // ② PackageManager
        android.content.Context ctx = appContext();
        if (ctx != null) {
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(MODULE_PKG, 0);
                if (ai.sourceDir != null && new java.io.File(ai.sourceDir).exists()) {
                    return ai.sourceDir;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void extractSo(String apkPath, java.io.File dst) throws Throwable {
        String[] abis = {"arm64-v8a", "arm64"};
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
        try {
            for (String abi : abis) {
                java.util.zip.ZipEntry e = zip.getEntry("lib/" + abi + "/lib" + LIB_NAME + ".so");
                if (e == null) continue;
                java.io.InputStream in = zip.getInputStream(e);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.flush();
                } finally {
                    try { fos.close(); } catch (Throwable ignored) {}
                    try { in.close(); } catch (Throwable ignored) {}
                }
                return;
            }
        } finally {
            try { zip.close(); } catch (Throwable ignored) {}
        }
        throw new RuntimeException("APK 里没有 lib/" + abis[0] + "/lib" + LIB_NAME + ".so");
    }

    /** 游戏 so 是否已经映射进本进程（native 侧能力探测的前提） */
    public static boolean gameLoaded() {
        if (!ensure()) return false;
        try {
            return nGameBase() != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long gameBase() {
        if (!ensure()) return 0L;
        try {
            return nGameBase();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 某功能在 native 侧有没有真实后端（地址是否已定位） */
    public static boolean available(String feature) {
        if (!ensure()) return false;
        try {
            return nAvailable(feature);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 状态串（给界面/日志看） */
    public static String status() {
        if (!ensure()) return "native: 未载入（lib" + LIB_NAME + ".so）";
        try {
            String s = nStatus();
            return s == null ? "native: 已载入（无状态）" : "native: " + s;
        } catch (Throwable t) {
            return "native: 已载入，但状态查询异常";
        }
    }

    /**
     * 玩家所在方块坐标，格式 "X / Y / Z"；读不到（玩家还没定位 / 异常）返回空串。
     * 数据源就是 native 侧 GameMode::buildBlock 内部用的那条 (Player+0x288) → BlockPos*，
     * 读不到时退回浮点位置向下取整。
     */
    public static String playerPos() {
        if (!loaded) return "";
        try {
            String s = nPlayerPos();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * ESP 数据 —— 每 4 个 float 一个屏幕矩形（x0,y0,x1,y1），0..1 归一化，左上为原点。
     * null / 空 = 这帧没有可画的。每帧调用没问题（只读内存）。
     *
     * @param sw  屏幕像素宽
     * @param sh  屏幕像素高
     * @param fov 垂直视场角（度）—— 用来反算焦距，跟游戏里设不一致会偏
     */
    public static float[] esp(int sw, int sh, float fov) {
        if (!loaded || sw <= 0 || sh <= 0) return null;
        try {
            return nEsp(sw, sh, fov);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Name Tags 数据 —— 每 3 个 float 一个实体：(x0, y0, dist)。
     * x0/y0 是 0..1 归一化屏幕坐标（左上为原点），dist 是格数。null = 这帧没有。
     */
    public static float[] tags(int sw, int sh, float fov) {
        if (!loaded || sw <= 0 || sh <= 0) return null;
        try {
            return nTags(sw, sh, fov);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 网络统计 —— float[]{ 相邻发包间隔抖动ms, 每秒包数, 客户端TPS }。
     * ★ 客户端观测不到真正的 RTT（收包路径这一版没有地址），这里给的是发包节奏。
     */
    public static float[] netStats() {
        if (!loaded) return null;
        try {
            return nNetStats();
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── 下发 ─────────────────────────

    public static void setFeature(String feature, boolean on) {
        if (!ensure()) return;
        try {
            nSetFeature(feature, on);
        } catch (Throwable ignored) {
        }
    }

    public static void setNum(String feature, String key, double v) {
        if (!ensure()) return;
        try {
            nSetNum(feature, key, v);
        } catch (Throwable ignored) {
        }
    }

    public static void setMode(String feature, String key, int idx) {
        if (!ensure()) return;
        try {
            nSetMode(feature, key, idx);
        } catch (Throwable ignored) {
        }
    }

    /** 主线程每帧驱动（attack / buildBlock 这类必须在游戏主线程调用的东西） */
    public static void tick() {
        if (!loaded) return;
        try {
            nTick();
        } catch (Throwable ignored) {
        }
    }

    /** 当前主线程 tick 次数（判断驱动有没有真在跑） */
    public static int mainTicks() {
        if (!loaded) return 0;
        try {
            return nMainTicks();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ───────────────────────── 环境 ─────────────────────────

    private static volatile android.content.Context sCtx;

    public static void setAppContext(android.content.Context c) {
        sCtx = c == null ? null : c.getApplicationContext();
    }

    private static android.content.Context appContext() {
        if (sCtx != null) return sCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                sCtx = ((android.content.Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return sCtx;
    }

    private static android.os.Handler main() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    // ───────────────────────── JNI ─────────────────────────

    private static native boolean nInit();

    private static native String nStatus();

    private static native String nPlayerPos();

    private static native long nGameBase();

    private static native boolean nAvailable(String feature);

    private static native void nSetFeature(String feature, boolean on);

    private static native void nSetNum(String feature, String key, double v);

    private static native void nSetMode(String feature, String key, int idx);

    private static native void nTick();

    private static native int nMainTicks();

    private static native float[] nEsp(int sw, int sh, float fov);

    private static native float[] nTags(int sw, int sh, float fov);

    private static native float[] nNetStats();
}
