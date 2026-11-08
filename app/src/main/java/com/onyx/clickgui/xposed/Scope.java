package com.onyx.clickgui.xposed;

import android.app.Activity;

import java.io.BufferedReader;
import java.io.FileReader;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 进程识别 —— 「这是不是该注入的 MC 进程」的**唯一**判据来源。
 *
 * ═══════════════════════ 为什么要有这个类 ═══════════════════════
 *
 * 2.4.6 之前，全模块靠一句 `TARGET_PACKAGE.equals(lp.packageName)` 做闸门，
 * 于是「改过包名的客户端」一律进不来。但**包名从来不是模块的技术依赖**：
 *
 *   · native 侧所有 RVA 偏移都是相对 `libminecraftpe.so` 的（见 cpp/offsets.hpp），
 *     native 找基址靠的是模块名，**跟包名毫无关系**；
 *   · 日志路径走 `getExternalFilesDir()`，包名变了自己跟着变；
 *   · overlay 挂的是当前 Activity 的 DecorView，也不看包名。
 *
 * 所以识别改成三层，任一层命中即放行：
 *
 *   ① 已知包名白名单          —— 零成本，官方包直通
 *   ② Application 类名 / 进程名特征 —— 零成本，**改包名后依然有效**
 *      （网易的 Application 是加固壳，类名带 netease/ntunisdk/UFProxy，
 *        改包名不会连带改它；真要连它一起改，加固自己就先崩了）
 *   ③ /proc/self/maps 里有 libminecraftpe.so —— 约 50µs，最终兜底
 *
 * 三层都不命中 = 不是 MC（微信、系统进程等），不注入。
 */
public final class Scope {

    /**
     * 激进模式：任何进程都放行，能不能用完全交给 native 自己判断。
     *
     * 默认 false。放行所有进程等于给每个 app 都挂一遍 Activity 生命周期 hook
     * 并起一条 native 扫描线程 —— 脏、没必要。只有那种「包名、Application 类名、
     * 进程名」全被改过、连 libminecraftpe.so 都改了名的定制端才需要开。
     */
    public static final boolean AGGRESSIVE = false;

    /** 官方主包名 —— 需要展示/上报的地方用这个 */
    public static final String PRIMARY_PACKAGE = "com.netease.x19";

    /** 已知包名：命中即零成本直通，不再走 ②③ */
    private static final String[] KNOWN = {
            "com.netease.x19",          // 网易官方
            "com.netease.mc",           // 网易其它渠道
            "com.mojang.minecraftpe",   // 国际版
            "com.mojang.minecraftedu",  // 教育版
    };

    /** Application / 进程名特征子串（按小写比较） */
    private static final String[] FEATURES = {
            "netease",      // 网易所有 package / class
            "minecraft",    // 国际版
            "ntunisdk",     // 网易 SDK（加固壳常用）
            "ufproxy",      // 网易加固的 UFProxyApplication
            "mgbase",       // 网易游戏基础库
    };

    private Scope() {
    }

    // ═══════════════════════════ 对外 ═══════════════════════════

    /** handleLoadPackage 阶段：这个进程要不要注入 */
    public static boolean isTarget(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return false;
        if (isKnownPackage(lp.packageName)) return true;
        if (looksLikeMc(lp)) return true;
        if (hasMcSo()) return true;
        return AGGRESSIVE;
    }

    /** Activity 阶段：这次挂载要不要做（挂 overlay 前再确认一次） */
    public static boolean isTargetActivity(Activity a) {
        if (a == null) return false;
        if (isKnownPackage(a.getPackageName())) return true;
        if (hasMcSo()) return true;
        return AGGRESSIVE;
    }

    public static boolean isKnownPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : KNOWN) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    // ═══════════════════════════ 内部 ═══════════════════════════

    private static boolean looksLikeMc(XC_LoadPackage.LoadPackageParam lp) {
        if (matchFeature(lp.processName)) return true;
        try {
            if (lp.appInfo != null && matchFeature(lp.appInfo.className)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean matchFeature(String s) {
        if (s == null) return false;
        final String l = s.toLowerCase();
        for (String f : FEATURES) {
            if (l.contains(f)) return true;
        }
        return false;
    }

    /**
     * 扫 /proc/self/maps 找 libminecraftpe.so —— 这是**唯一不可伪造的判据**。
     *
     * 成本约 50µs（一次 open + 顺序读几百行），且只在非白名单路径才会走到。
     * 命中后**永久缓存**：服务进程里 .so 一旦映射进来就不会卸载。
     */
    private static volatile boolean sSoSeen = false;

    public static boolean hasMcSo() {
        if (sSoSeen) return true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/maps"), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("libminecraftpe.so")) {
                    sSoSeen = true;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 把三层判据摊开写进日志。
     * 「为什么没注入」这个问题，看这一行就够了 —— 三个都是 0 就是真的不是 MC。
     */
    public static String describe(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null) return "lp=null";
        String cn = "?";
        try {
            if (lp.appInfo != null) cn = String.valueOf(lp.appInfo.className);
        } catch (Throwable ignored) {
        }
        return "pkg=" + lp.packageName
                + " proc=" + lp.processName
                + " app=" + cn
                + " | known=" + (isKnownPackage(lp.packageName) ? 1 : 0)
                + " feat=" + (looksLikeMc(lp) ? 1 : 0)
                + " so=" + (hasMcSo() ? 1 : 0)
                + " aggr=" + (AGGRESSIVE ? 1 : 0);
    }
}
