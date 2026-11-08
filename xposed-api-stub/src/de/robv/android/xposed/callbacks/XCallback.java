package de.robv.android.xposed.callbacks;

/**
 * 编译期占位：运行期由框架提供真身。
 */
public abstract class XCallback {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public XCallback() {}

    public XCallback(int priority) {}

    public static class Param {
        public Param() {}
    }
}
