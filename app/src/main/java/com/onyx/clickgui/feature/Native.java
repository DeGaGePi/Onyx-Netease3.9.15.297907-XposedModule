package com.onyx.clickgui.feature;

 









public final class Native {

     
    private static final String MODULE_PKG = "com.onyx.mc";
    private static final String LIB_NAME   = "onyxmc";

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile int attempts;
    private static volatile long lastTryMs;

     
    private static final int MAX_ATTEMPTS = 40;

    private Native() {}

    

     



    public static boolean ensure() {
        if (loaded) return true;
        synchronized (LOCK) {
            if (loaded) return true;
            if (attempts >= MAX_ATTEMPTS) return false;

            final long now = System.currentTimeMillis();
            
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

     





    public static boolean isLoaded() { return loaded; }

     
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

        
        if (apk == null) throw new RuntimeException("找不到模块 APK 路径");
        java.io.File out = new java.io.File(ctx.getCodeCacheDir(), "lib" + LIB_NAME + ".so");
        extractSo(apk, out);
        System.load(out.getAbsolutePath());
        com.onyx.clickgui.xposed.XLog.i("native: 载入方式 ④ 解压到私有目录成功 " + out);
    }

    private static String moduleApkPath() {
        
        try {
            java.security.CodeSource cs = Native.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String p = cs.getLocation().getPath();
                if (p != null && p.endsWith(".apk") && new java.io.File(p).exists()) return p;
            }
        } catch (Throwable ignored) {
        }
        
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

     
    public static boolean available(String feature) {
        if (!ensure()) return false;
        try {
            return nAvailable(feature);
        } catch (Throwable t) {
            return false;
        }
    }

     
    public static String status() {
        if (!ensure()) return "native: 未载入（lib" + LIB_NAME + ".so）";
        try {
            String s = nStatus();
            return s == null ? "native: 已载入（无状态）" : "native: " + s;
        } catch (Throwable t) {
            return "native: 已载入，但状态查询异常";
        }
    }

     




    public static String playerPos() {
        if (!loaded) return "";
        try {
            String s = nPlayerPos();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

     







    public static float[] esp(int sw, int sh, float fov) {
        if (!loaded || sw <= 0 || sh <= 0) return null;
        try {
            return nEsp(sw, sh, fov);
        } catch (Throwable t) {
            return null;
        }
    }

     



    public static float[] tags(int sw, int sh, float fov) {
        if (!loaded || sw <= 0 || sh <= 0) return null;
        try {
            return nTags(sw, sh, fov);
        } catch (Throwable t) {
            return null;
        }
    }

     



    public static float[] netStats() {
        if (!loaded) return null;
        try {
            return nNetStats();
        } catch (Throwable t) {
            return null;
        }
    }

    

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

     
    public static void tick() {
        if (!loaded) return;
        try {
            nTick();
        } catch (Throwable ignored) {
        }
    }

     
    public static int mainTicks() {
        if (!loaded) return 0;
        try {
            return nMainTicks();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    

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
