package com.onyx.clickgui.xposed;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.onyx.clickgui.feature.FeatureBus;
import com.onyx.clickgui.gui.ClickGUI;

import java.lang.ref.WeakReference;

 














public final class OverlayInjector {

    private static final String VIEW_TAG = "ONYX_IN_GAME_OVERLAY";
    private static final String FALLBACK_TAG = "ONYX_FALLBACK";

    private static final OverlayInjector INSTANCE = new OverlayInjector();

    public static OverlayInjector get() { return INSTANCE; }

    private OverlayInjector() {}

    private final Object LOCK = new Object();
    private volatile Handler uiHandler;
    private volatile View gui;
    private volatile WeakReference<Activity> hostRef = new WeakReference<>(null);
    private volatile String attachedTo = "";
    private volatile boolean watchdogOn;
    private volatile boolean fbShown;
     
    private volatile int zeroSizeTicks;

     




    private volatile WeakReference<Activity> lastRef = new WeakReference<>(null);

     
    private volatile String lastAudit = "";

     
    private volatile int scopeMiss;

     
    private volatile boolean bannerDone;

     
    private volatile int visFixTries;

    private Handler ui() {
        Handler h = uiHandler;
        if (h != null) return h;
        synchronized (LOCK) {
            if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
            return uiHandler;
        }
    }

    

     

























    private static final long STARTUP_QUIET_MS = 0L;

     
    private static volatile long firstAttachMs;

    public void attach(final Activity a) {
        if (a == null) return;
        try {
            Diag.init(a);
            
            
            
            if (!bannerDone) {
                bannerDone = true;
                Diag.log("════════ Onyx " + FeatureBus.VERSION + " 注入 " + a.getPackageName());
            }
            
            
            lastRef = new WeakReference<>(a);
            if (firstAttachMs == 0) firstAttachMs = System.currentTimeMillis();
            
            
            startWatchdog();
            
            
            
            
            
            ui().post(() -> attachNow(a, 0));
        } catch (Throwable t) {
            Diag.log("attach post 失败", t);
        }
    }

    public void onActivityDestroyed(Activity a) {
        Activity host = hostRef.get();
        if (host == a) {
            Diag.log("Activity 销毁 → 清引用 " + a.getClass().getName());
            hostRef = new WeakReference<>(null);
            attachedTo = "";
            gui = null;
            zeroSizeTicks = 0;
            FeatureBus.get().unbind();
        }
    }

    public ClickGUI view() { return (gui instanceof ClickGUI) ? (ClickGUI) gui : null; }

     
    public void onConfigChanged(Activity a) {
        if (a == null) return;
        Diag.log("onConfigurationChanged → 重挂");
        ui().post(() -> attachNow(a, 0));
    }

    

    private void attachNow(final Activity a, final int attempt) {
        if (a == null || a.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return;

        
        if (STARTUP_QUIET_MS > 0
                && System.currentTimeMillis() - firstAttachMs < STARTUP_QUIET_MS) {
            retry(a, attempt);
            return;
        }

        
        
        
        
        
        
        
        
        
        
        
        
        if (!Scope.isTargetActivity(a)) {
            if (scopeMiss == 0) {
                Diag.log("awaitScope：Activity 已就绪，但 libminecraftpe.so 还没 map → 持续重试");
            }
            if (++scopeMiss <= 40) {
                ui().postDelayed(() -> attachNow(a, attempt), scopeMiss < 5 ? 200L : 500L);
            } else {
                Diag.log("awaitScope：重试 40 次仍未等到 .so，放弃本次（等下一个 Activity）");
            }
            return;
        }
        scopeMiss = 0;

        try {
            Window win = a.getWindow();
            if (win == null) { retry(a, attempt); return; }

            
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    WindowManager.LayoutParams wlp = win.getAttributes();
                    wlp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    win.setAttributes(wlp);
                } catch (Throwable ignored) {
                }
            }

            View decorV = win.getDecorView();
            if (!(decorV instanceof ViewGroup)) {
                if (attempt == 0) Diag.log("DecorView 还不是 ViewGroup：" + decorV);
                retry(a, attempt);
                return;
            }
            ViewGroup decor = (ViewGroup) decorV;

            
            
            
            
            
            if ((decor.getWidth() <= 0 || decor.getHeight() <= 0) && attempt < 30) {
                if (attempt == 0) Diag.log("DecorView 还没量出尺寸 → 等这轮布局跑完");
                retry(a, attempt);
                return;
            }

            
            
            
            
            
            
            
            

            
            
            
            
            
            
            
            
            
            View existing = decor.findViewWithTag(VIEW_TAG);
            if (existing instanceof ClickGUI) {
                final boolean parentLost = (existing.getParent() == null);
                gui = existing;
                hostRef = new WeakReference<>(a);
                attachedTo = a.getClass().getName();
                FeatureBus.get().bind((ClickGUI) existing);
                Diag.log("已挂过（尺寸 " + existing.getWidth()
                        + "x" + existing.getHeight() + " 可见="
                        + (existing.getVisibility() == View.VISIBLE)
                        + (parentLost ? " 父=null→补挂" : "") + "）");
                if (parentLost) decor.addView(existing, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }
            if (existing != null) decor.removeView(existing);

            
            View v;
            try {
                ClickGUI g = new ClickGUI(a);
                g.setTag(VIEW_TAG);
                g.setOverlayMode(true);
                try { g.setElevation(100f); } catch (Throwable ignored) { }
                v = g;
                Diag.log("ClickGUI 构造成功");
            } catch (Throwable t) {
                Diag.log("ClickGUI 构造抛异常 → 改挂 FallbackView", t);
                v = new FallbackView(a, "ClickGUI 构造失败：" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : (" " + t.getMessage())));
                v.setTag(FALLBACK_TAG);
                fbShown = true;
            }

            
            try {
                decor.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t1) {
                Diag.log("addView(FrameLayout.LayoutParams) 失败，回退通用类型", t1);
                decor.addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            v.setVisibility(View.VISIBLE);
            v.bringToFront();
            decor.requestLayout();

            gui = v;
            hostRef = new WeakReference<>(a);
            attachedTo = a.getClass().getName();

            Diag.log("overlay ATTACHED → " + attachedTo
                    + " decor=" + decor.getClass().getName()
                    + " decorSize=" + decor.getWidth() + "x" + decor.getHeight()
                    + " attempt=" + attempt);

            if (v instanceof ClickGUI) {
                FeatureBus.get().bind((ClickGUI) v);
                probeLater(a);
            }
            startWatchdog();

        } catch (Throwable t) {
            Diag.log("overlay attach 失败 (attempt " + attempt + ")", t);
            retry(a, attempt);
        }
    }

     



    private void probeLater(final Activity a) {
        ui().postDelayed(() -> {
            try {
                View v = gui;
                if (!(v instanceof ClickGUI)) return;
                ClickGUI g = (ClickGUI) v;
                String msg = v.getWidth() + "x" + v.getHeight()
                        + (v.getWidth() < 2 ? "  ← 尺寸为 0，量测有问题" : "");
                if (!fbShown) g.toastOk("Onyx " + FeatureBus.VERSION + " 已挂载", msg);
                Diag.log("探针：view=" + msg + " 可见=" + (v.getVisibility() == View.VISIBLE)
                        + " 父=" + (v.getParent() != null));
            } catch (Throwable t) {
                Diag.log("探针失败", t);
            }
        }, 1200);
    }

     
    private void retry(final Activity a, final int attempt) {
        if (attempt > 120) {
            Diag.log("重试 120 次仍未挂上 → 上降级探针。attachedTo=" + attachedTo);
            forceFallback(a, "挂载重试 120 次仍未成功（DecorView 拿不到）");
            return;
        }
        long delay = attempt < 10 ? 200L : 1000L;
        try {
            ui().postDelayed(() -> attachNow(a, attempt + 1), delay);
        } catch (Throwable ignored) {
        }
    }

     



    private void forceFallback(final Activity a, final String why) {
        try {
            View decorV = a.getWindow().getDecorView();
            if (!(decorV instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorV;

            View old = decor.findViewWithTag(VIEW_TAG);
            if (old != null) decor.removeView(old);
            if (decor.findViewWithTag(FALLBACK_TAG) != null) return;

            FallbackView fv = new FallbackView(a, why);
            fv.setTag(FALLBACK_TAG);
            try {
                decor.addView(fv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) {
                decor.addView(fv, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            fv.bringToFront();
            gui = fv;
            fbShown = true;
            Diag.log("已降级为 FallbackView：" + why);
        } catch (Throwable t) {
            Diag.log("forceFallback 失败", t);
        }
    }

     
    private static final long WATCHDOG_MS = 2000L;

     











    private void startWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        ui().postDelayed(new Runnable() {
            @Override
            public void run() {
                try { watchdogTick(); } catch (Throwable t) { Diag.log("watchdog", t); }
                ui().postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private void watchdogTick() {
        final Activity host = lastRef.get();
        if (host == null || host.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && host.isDestroyed()) return;

        final View v = gui;
        if (v == null || v.getParent() == null) {
            
            attachNow(host, 0);
            return;
        }

        auditVisibility(host, v);

        if (v.getWidth() < 2 || v.getHeight() < 2) {
            if (++zeroSizeTicks == 3) {
                Diag.log("watchdog：连续 3 次尺寸为 0 → 先 requestLayout 再重挂");
                try { v.requestLayout(); } catch (Throwable ignored) { }
                attachNow(host, 0);
            } else if (zeroSizeTicks >= 8) {
                zeroSizeTicks = 0;
                forceFallback(host, "ClickGUI 已挂上但尺寸一直是 0（量测失败）");
            }
        } else {
            zeroSizeTicks = 0;
        }
    }

     











    private void auditVisibility(final Activity host, final View v) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            final boolean vis = v.getGlobalVisibleRect(r);
            int idx = -1, cnt = -1;
            final View decorV = (host.getWindow() == null) ? null : host.getWindow().getDecorView();
            if (decorV instanceof ViewGroup) {
                idx = ((ViewGroup) decorV).indexOfChild(v);
                cnt = ((ViewGroup) decorV).getChildCount();
            }
            final String line = "audit vis=" + vis
                    + " rect=" + r.left + "," + r.top + "," + r.right + "," + r.bottom
                    + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " alpha=" + v.getAlpha()
                    + " z=" + idx + "/" + cnt
                    + " attached=" + v.isAttachedToWindow();
            if (!line.equals(lastAudit)) {
                lastAudit = line;
                Diag.log(line);
            }

            
            
            
            
            
            
            if (!vis && host.hasWindowFocus() && v.isShown() && visFixTries < 3) {
                visFixTries++;
                Diag.log("audit：有焦点、isShown、但 getGlobalVisibleRect=false"
                        + " → 重新 addView 抢救（第 " + visFixTries + " 次）");
                try {
                    final ViewGroup p = (v.getParent() instanceof ViewGroup)
                            ? (ViewGroup) v.getParent() : null;
                    if (p != null) {
                        p.removeView(v);
                        p.addView(v, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        v.bringToFront();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    

     




    static final class FallbackView extends View {

        private final String text;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        FallbackView(Context c, String text) {
            super(c);
            this.text = text;
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = MeasureSpec.getSize(hSpec);
            if (w <= 0 || h <= 0) {
                w = getResources().getDisplayMetrics().widthPixels;
                h = getResources().getDisplayMetrics().heightPixels;
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            try {
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(230, 190, 20, 30));
                cv.drawRect(0, 0, getWidth(), 140f, p);
                p.setColor(Color.WHITE);
                p.setTextSize(20f);
                p.setTextAlign(Paint.Align.LEFT);
                cv.drawText("Onyx 挂载成功，但 GUI 构造失败：", 12f, 34f, p);
                String s = text == null ? "" : text;
                if (s.length() > 90) s = s.substring(0, 90);
                cv.drawText(s, 12f, 66f, p);
                cv.drawText("view=" + getWidth() + "x" + getHeight(), 12f, 110f, p);
            } catch (Throwable ignored) {
            }
        }
    }
}
