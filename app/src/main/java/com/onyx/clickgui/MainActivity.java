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

 















public class MainActivity extends Activity {

     
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

        
        gui = new ClickGUI(this);
        root.addView(gui, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        
        
        
        
        
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
