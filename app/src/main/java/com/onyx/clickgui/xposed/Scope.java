package com.onyx.clickgui.xposed;

import android.app.Activity;

import java.io.BufferedReader;
import java.io.FileReader;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

 






















public final class Scope {

     






    public static final boolean AGGRESSIVE = false;

     
    public static final String PRIMARY_PACKAGE = "com.netease.x19";

     
    private static final String[] KNOWN = {
            "com.netease.x19",          
            "com.netease.mc",           
            "com.mojang.minecraftpe",   
            "com.mojang.minecraftedu",  
    };

     
    private static final String[] FEATURES = {
            "netease",      
            "minecraft",    
            "ntunisdk",     
            "ufproxy",      
            "mgbase",       
    };

    private Scope() {
    }

    

     
    public static boolean isTarget(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return false;
        if (isKnownPackage(lp.packageName)) return true;
        if (looksLikeMc(lp)) return true;
        if (hasMcSo()) return true;
        return AGGRESSIVE;
    }

     
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
