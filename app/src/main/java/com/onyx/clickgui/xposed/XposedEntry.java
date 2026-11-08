package com.onyx.clickgui.xposed;

import android.app.Activity;
import android.os.Bundle;

import com.onyx.clickgui.feature.FeatureBus;
import com.onyx.clickgui.feature.Native;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口（assets/xposed_init 指向这个类）。
 *
 * 挂载策略：**六个生命周期点全钩，每个点都尝试挂一次**（刻意的四重冗余）。
 * 网易版启动流程里哪个 Activity 先起来、DecorView 什么时候长出来都不确定，
 * 只在 onResume 一个点试，很容易恰好错过。
 *
 * 顺序铁律：**先装 UI 钩子，后拉 native**。UI 是纯 Java，失败概率极低；
 * native 要 load so + 扫模块，任何一步炸掉都不能连累 UI —— 上一版就是把 native
 * 排前面且没 try/catch，一炸整条 hook 中断，UI 再也挂不上。
 */
public class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            XLog.i("zygote init · modulePath="
                    + (startupParam == null ? "?" : startupParam.modulePath));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null) return;

        // 三层识别（包名白名单 / Application·进程名特征 / 进程内有 libminecraftpe.so）
        // —— 改过包名的客户端照样进得来，别的 app 不会被误注入。见 Scope.java
        XLog.i("handleLoadPackage: " + Scope.describe(lp));

        if (!Scope.isTarget(lp)) return;

        XLog.i("════ Onyx " + FeatureBus.VERSION + " 注入 " + lp.packageName
                + " (" + lp.processName + ") ════");
        // ① UI 钩子 —— 必须最先、且独立 try/catch
        try {
            hookActivityLifecycle(lp);
        } catch (Throwable t) {
            XLog.e("hook Activity 失败", t);
        }

        // ② native —— 慢活，放最后，炸了也不影响 UI
        try {
            Native.ensureAsync();
        } catch (Throwable t) {
            XLog.e("native 启动失败（不影响 UI）", t);
        }

        // ③ 兜底：从 ActivityThread 直接捞"当前活着的 Activity"补挂一次。
        //
        //   ★★ 2.4.11：Activity 生命周期 hook 只能捕获**装上之后**发生的事件。
        //   如果模块是在游戏已经进到主界面之后才注入的（NPatch 注入时机完全可能这样），
        //   onCreate / onPostCreate / onResume / onWindowFocusChanged 全都已经过去了，
        //   就永远等不到回调 → attach 从不被调用 → **UI 永远不出现**。
        //   延迟 4 秒执行一次（那时 Application 和主 Activity 都已就绪）；
        //   如果已经挂上了，走的就是 attachNow 的"已挂过"分支，无害。
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            attachCurrentActivity();
                        }
                    }, 4000);
        } catch (Throwable t) {
            XLog.e("兜底补挂调度失败", t);
        }
    }

    /**
     * 从 ActivityThread 里捞出"当前活着的 Activity"。
     *
     * mActivities 在不同 Android 版本上是 Map / Collection / SparseArray，
     * 这里只要求和 Map 或 Collection 兼容的那两种；record 里的字段名
     * `activity` 自 API 1 至今没变。整段 try/catch —— 反射失败不能影响别的东西。
     */
    private static void attachCurrentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return;

            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object acts = f.get(thread);
            if (acts == null) return;

            java.util.Collection<?> records;
            if (acts instanceof java.util.Map) {
                records = ((java.util.Map<?, ?>) acts).values();
            } else if (acts instanceof java.util.Collection) {
                records = (java.util.Collection<?>) acts;
            } else {
                XLog.w("兜底: mActivities 类型不认识 " + acts.getClass().getName());
                return;
            }

            for (Object rec : records) {
                if (rec == null) continue;
                java.lang.reflect.Field af = rec.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Object a = af.get(rec);
                if (a instanceof Activity) {
                    XLog.i("兜底：从 ActivityThread 抓到 " + a.getClass().getName());
                    OverlayInjector.get().attach((Activity) a);
                    return;
                }
            }
            XLog.w("兜底：ActivityThread 里没有可用的 Activity");
        } catch (Throwable ignored) {
        }
    }

    // ═══════════════════════════════════════════════════════════

    private void hookActivityLifecycle(XC_LoadPackage.LoadPackageParam lp) {
        final String pkg = lp.packageName;

        // ── onCreate：拿到 Context 后立刻申请挂载 ──
        hook("onCreate", new Object[]{Bundle.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                try {
                    Native.setAppContext(a);
                } catch (Throwable ignored) {
                }
                XLog.i("onCreate: " + a.getClass().getName());
                OverlayInjector.get().attach(a);
            }
        });

        // ── onPostCreate：DecorView 到这时才基本长齐 ──
        hook("onPostCreate", new Object[]{Bundle.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        // ── onResume ──
        hook("onResume", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        // ── onWindowFocusChanged(true) ──
        hook("onWindowFocusChanged", new Object[]{boolean.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        // ── onPause：多窗口/分屏下 onResume 可能被跳过，这里再兜一次 ──
        hook("onPause", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        // ── onConfigurationChanged：切分辨率 / 分屏 / 折叠屏展开后视图树会重建，主动重挂 ──
        hook("onConfigurationChanged", new Object[]{android.content.res.Configuration.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().onConfigChanged(a);
            }
        });

        // ── onDestroy：清引用，别让旧 Activity 泄漏 ──
        hook("onDestroy", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null) return;
                OverlayInjector.get().onActivityDestroyed(a);
            }
        });
    }

    /** 把「参数列表 + 动作」包成一个 XC_MethodHook，省得写六遍匿名类 */
    private void hook(String method, Object[] extraArgs, final Runnable2 action) {
        try {
            Object[] args = new Object[extraArgs.length + 1];
            System.arraycopy(extraArgs, 0, args, 0, extraArgs.length);
            args[extraArgs.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Activity) {
                            action.run((Activity) param.thisObject);
                        }
                    } catch (Throwable t) {
                        XLog.e("after " + method + " 回调异常", t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Activity.class, method, args);
            XLog.i("hook Activity." + method + " ✓");
        } catch (Throwable t) {
            XLog.e("hook Activity." + method + " 失败", t);
        }
    }

    private interface Runnable2 {
        void run(Activity a);
    }
}
