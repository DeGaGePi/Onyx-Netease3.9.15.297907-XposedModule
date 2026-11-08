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

        
        
        XLog.i("handleLoadPackage: " + Scope.describe(lp));

        if (!Scope.isTarget(lp)) return;

        XLog.i("════ Onyx " + FeatureBus.VERSION + " 注入 " + lp.packageName
                + " (" + lp.processName + ") ════");
        
        try {
            hookActivityLifecycle(lp);
        } catch (Throwable t) {
            XLog.e("hook Activity 失败", t);
        }

        
        try {
            Native.ensureAsync();
        } catch (Throwable t) {
            XLog.e("native 启动失败（不影响 UI）", t);
        }

        
        
        
        
        
        
        
        
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

    

    private void hookActivityLifecycle(XC_LoadPackage.LoadPackageParam lp) {
        final String pkg = lp.packageName;

        
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

        
        hook("onPostCreate", new Object[]{Bundle.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        
        hook("onResume", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        
        hook("onWindowFocusChanged", new Object[]{boolean.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        
        hook("onPause", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().attach(a);
            }
        });

        
        hook("onConfigurationChanged", new Object[]{android.content.res.Configuration.class}, new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null || !pkg.equals(a.getPackageName())) return;
                OverlayInjector.get().onConfigChanged(a);
            }
        });

        
        hook("onDestroy", new Object[0], new Runnable2() {
            @Override
            public void run(Activity a) {
                if (a == null) return;
                OverlayInjector.get().onActivityDestroyed(a);
            }
        });
    }

     
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
