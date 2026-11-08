package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;

 


public final class XposedBridge {

    private XposedBridge() {}

    public static int getXposedVersion() { return 82; }

    public static void log(String text) {}

    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return new HashSet<>();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        return new HashSet<>();
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        return null;
    }

    public static Constructor<?>[] getConstructors(Class<?> aClass) { return null; }
}
