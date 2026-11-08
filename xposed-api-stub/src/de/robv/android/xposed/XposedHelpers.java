package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * 编译期占位：运行期由框架提供真身。
 * 这里只保留模块实际会用到的 API 面，方法体全部为空实现。
 */
public final class XposedHelpers {

    private XposedHelpers() {}

    // ───────────────────────── 类 ─────────────────────────

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    // ───────────────────────── 方法 ─────────────────────────

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }

    public static Method findMethodExact(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypes) {
        return null;
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName,
                                                 Object... parameterTypes) {
        return null;
    }

    public static Set<Method> findMethodsByName(Class<?> clazz, String methodName,
                                                boolean findBaseMethods) {
        return null;
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName,
                                             Object... parameterTypes) {
        return null;
    }

    // ───────────────────────── 构造 ─────────────────────────

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        return null;
    }

    public static Constructor<?> findConstructorExactIfExists(Class<?> clazz,
                                                              Object... parameterTypes) {
        return null;
    }

    // ───────────────────────── Hook ─────────────────────────

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz,
                                                             Object... parameterTypesAndCallback) {
        return null;
    }

    // ───────────────────────── 字段 ─────────────────────────

    public static Field findField(Class<?> clazz, String fieldName) { return null; }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) { return null; }

    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) { return null; }

    public static Object getObjectField(Object obj, String fieldName) { return null; }

    public static Object getObjectField(Object obj, String fieldName, Class<?> fieldType) { return null; }

    public static boolean getBooleanField(Object obj, String fieldName) { return false; }

    public static byte getByteField(Object obj, String fieldName) { return 0; }

    public static char getCharField(Object obj, String fieldName) { return 0; }

    public static short getShortField(Object obj, String fieldName) { return 0; }

    public static int getIntField(Object obj, String fieldName) { return 0; }

    public static long getLongField(Object obj, String fieldName) { return 0L; }

    public static float getFloatField(Object obj, String fieldName) { return 0f; }

    public static double getDoubleField(Object obj, String fieldName) { return 0d; }

    public static void setObjectField(Object obj, String fieldName, Object value) {}

    public static void setBooleanField(Object obj, String fieldName, boolean value) {}

    public static void setByteField(Object obj, String fieldName, byte value) {}

    public static void setCharField(Object obj, String fieldName, char value) {}

    public static void setShortField(Object obj, String fieldName, short value) {}

    public static void setIntField(Object obj, String fieldName, int value) {}

    public static void setLongField(Object obj, String fieldName, long value) {}

    public static void setFloatField(Object obj, String fieldName, float value) {}

    public static void setDoubleField(Object obj, String fieldName, double value) {}

    // ───────────────────────── 静态字段 ─────────────────────────

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) { return false; }

    public static int getStaticIntField(Class<?> clazz, String fieldName) { return 0; }

    public static long getStaticLongField(Class<?> clazz, String fieldName) { return 0L; }

    public static float getStaticFloatField(Class<?> clazz, String fieldName) { return 0f; }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {}

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {}

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {}

    public static void setStaticLongField(Class<?> clazz, String fieldName, long value) {}

    public static void setStaticFloatField(Class<?> clazz, String fieldName, float value) {}

    // ───────────────────────── 调用 ─────────────────────────

    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }

    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes,
                                    Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName,
                                          Class<?>[] parameterTypes, Object... args) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Object... args) { return null; }

    public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) {
        return null;
    }

    public static Object getSurroundingThis(Object obj) { return null; }
}
