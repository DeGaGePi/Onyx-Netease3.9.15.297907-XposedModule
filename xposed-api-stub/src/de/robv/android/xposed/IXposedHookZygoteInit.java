package de.robv.android.xposed;

/**
 * 编译期占位：运行期由框架提供真身。
 */
public interface IXposedHookZygoteInit extends IXposedMod {
    void initZygote(StartupParam startupParam) throws Throwable;

    final class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
