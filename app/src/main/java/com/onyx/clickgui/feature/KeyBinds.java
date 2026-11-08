package com.onyx.clickgui.feature;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

 


















public final class KeyBinds {

     
    public static final class Bind {
         
        public boolean on = false;
         
        public float size = 1.0f;
         
        public float x = Float.NaN;
        public float y = Float.NaN;

        public boolean isOn() { return on; }
    }

    private static final Map<String, Bind> M = new HashMap<>();
    private static SharedPreferences sp;
    private static boolean loaded;

    private KeyBinds() {}

     
    public static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        if (c == null) return;
        try {
            sp = c.getApplicationContext().getSharedPreferences("onyx_binds", Context.MODE_PRIVATE);
            final String all = sp.getString("v2", "");
            if (all == null || all.isEmpty()) return;
            for (String row : all.split(";")) {
                if (row.isEmpty()) continue;
                final String[] p = row.split(",");
                if (p.length < 5) continue;
                Bind b = new Bind();
                try {
                    b.on   = (Integer.parseInt(p[1]) != 0);
                    b.size = Float.parseFloat(p[2]);
                    b.x    = Float.parseFloat(p[3]);
                    b.y    = Float.parseFloat(p[4]);
                } catch (Throwable ignored) {
                    continue;
                }
                if (Float.isNaN(b.size) || b.size < 0.5f || b.size > 2.5f) b.size = 1.0f;
                M.put(p[0], b);
            }
        } catch (Throwable ignored) {
            sp = null;
        }
    }

     
    public static synchronized Bind get(String name) {
        Bind b = (name == null) ? null : M.get(name);
        if (b == null) {
            b = new Bind();
            if (name != null) M.put(name, b);
        }
        return b;
    }

     
    public static synchronized Map<String, Bind> all() { return M; }

     
    public static synchronized boolean anyOn() {
        for (Bind b : M.values()) {
            if (b != null && b.on) return true;
        }
        return false;
    }

    public static synchronized void save() {
        if (sp == null) return;
        try {
            final StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Bind> e : M.entrySet()) {
                final Bind b = e.getValue();
                if (b == null || !b.on) continue;      
                sb.append(e.getKey()).append(',')
                  .append(b.on ? 1 : 0).append(',')
                  .append(b.size).append(',')
                  .append(b.x).append(',')
                  .append(b.y).append(';');
            }
            sp.edit().putString("v2", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
