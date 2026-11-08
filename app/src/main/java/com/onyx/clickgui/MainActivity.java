package com.onyx.clickgui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.onyx.clickgui.feature.FeatureBus;
import com.onyx.clickgui.feature.Native;
import com.onyx.clickgui.gui.ClickGUI;
import com.onyx.clickgui.xposed.Diag;

/**
 * 模块自己的壳 Activity —— 三个用途：
 *   1. **单独打开就能预览整套 ClickGUI**（不依赖注入）。
 *      这是判断故障位置的关键对照：
 *        · 这里能正常显示面板 → ClickGUI 绘制没问题，故障在"注入挂载"层
 *        · 这里也不显示       → ClickGUI 自身有问题
 *   2. 顶部一行小字显示 native 后端状态（**只读，不可点击**）。
 *   3. 注入侧日志路径固定为 IN_GAME_LOG（见下方常量），要用直接取，不在界面上显示。
 *
 * ★ 血泪教训：这个状态条**不能挂 OnClickListener**。
 *   Android 里一设点击监听，View 的 clickable 就变 true，该区域会吞掉触摸事件；
 *   而状态文字换行后会覆盖屏幕左上大片区域，正好压住 ClickGUI 的标题栏与左侧
 *   分类列表 → 表现为「模块面板拖不动、功能点不开，只看到一行字」。
 *
 * 注意：这里用的是 android.app.Activity（不引 androidx），保证工程完全离线可编。
 */
public class MainActivity extends Activity {

    /** 注入进游戏进程后，日志落在游戏自己的外部目录（无需权限） */
    private static final String IN_GAME_LOG =
            "/sdcard/Android/data/" + FeatureBus.TARGET_PACKAGE + "/files/onyx.log";

    private ClickGUI gui;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Diag.init(this);

        FrameLayout root = new FrameLayout(this);

        // ① ClickGUI 本体（预览模式：不注入，直接当普通 App 看着调）
        gui = new ClickGUI(this);
        root.addView(gui, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ② 左上角状态条
        // ★ 纯展示：**绝不能挂 OnClickListener**。
        //   一旦 clickable=true，这块区域就会吞掉所有触摸事件；而文字换行后
        //   覆盖屏幕左上大片区域，正好压住 ClickGUI 的标题栏与左侧分类 →
        //   表现为「面板拖不动、功能点不开」。保持不可点击，事件才能穿透给下层。
        status = new TextView(this);
        status.setTextColor(Color.parseColor("#B0FFFFFF"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        status.setPadding(24, 24, 24, 24);
        status.setClickable(false);
        status.setFocusable(false);
        status.setLongClickable(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // ★ 放右上角，别占左上 —— ClickGUI 的标题栏/拖动区就在左上，
        //   状态条压上去会挡住面板操作（视觉遮挡 + 观感上"点了没反应"）。
        slp.gravity = Gravity.TOP | Gravity.END;
        status.setAlpha(0.75f);
        root.addView(status, slp);

        setContentView(root);

        Native.ensureAsync();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        status.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (status == null) return;
                String s = Native.status();
                status.setText("Onyx " + FeatureBus.VERSION + "\n" + s
                        + "\n\n本页 = 预览模式（不注入）");
                status.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Native.ensureAsync();
        if (gui != null) gui.postInvalidate();
    }

    @Override
    protected void onDestroy() {
        status = null;
        super.onDestroy();
    }
}
