package com.onyx.clickgui.gui;

import java.util.ArrayList;
import java.util.List;

 












public final class Feature {

    

    public static final int BOOL  = 0;
    public static final int FLOAT = 1;
    public static final int INT   = 2;
    public static final int ENUM  = 3;
     
    public static final int ACT   = 4;

    public static final class Setting {
        public final int type;
        public final String name;

        
        public boolean b;

        
        public double f, fmin, fmax, fstep;

        
        public int i, imin, imax;

        
        public String[] options;
        public int idx;

        
        public String action;

        
        public boolean dragging;
         
        public double shown;

        public Setting(int type, String name) {
            this.type = type;
            this.name = name;
        }

         
        public float t() {
            double v, lo, hi;
            if (type == FLOAT) { v = f; lo = fmin; hi = fmax; }
            else               { v = i; lo = imin; hi = imax; }
            if (hi - lo <= 0) return 0f;
            double k = (v - lo) / (hi - lo);
            return (float) (k < 0 ? 0 : (k > 1 ? 1 : k));
        }

         
        public String text() {
            switch (type) {
                case FLOAT: return String.format(java.util.Locale.US, "%.2f", f);
                case INT:   return String.valueOf(i);
                case ENUM:  return (options != null && idx >= 0 && idx < options.length)
                                    ? options[idx] : "?";
                case ACT:   return "";
                default:    return b ? "On" : "Off";
            }
        }

         
        public void setT(float k) {
            k = k < 0 ? 0 : (k > 1 ? 1 : k);
            if (type == FLOAT) {
                double v = fmin + (fmax - fmin) * k;
                if (fstep > 0) v = Math.round(v / fstep) * fstep;
                f = Math.max(fmin, Math.min(fmax, v));
            } else if (type == INT) {
                int v = (int) Math.round(imin + (imax - imin) * (double) k);
                i = Math.max(imin, Math.min(imax, v));
            }
        }
    }

    

    public final String name;
     
    public final String backend;
    public final String desc;

     
    public final boolean implemented;

    public boolean enabled;
    public boolean expanded;
     
    public float anim;

     



    public boolean lockOn;

     
    public String lockHint;

     
    public boolean startOff;

     



    public com.onyx.clickgui.feature.KeyBinds.Bind bind;

    public final List<Setting> settings = new ArrayList<>();

    private Feature(String name, String backend, String desc, boolean implemented) {
        this.name = name;
        this.backend = backend;
        this.desc = desc == null ? "" : desc;
        this.implemented = implemented;
    }

     
    public static Feature mod(String name, String backend, String desc) {
        return new Feature(name, backend, desc, true);
    }

     
    public static Feature ui(String name, String desc) {
        return new Feature(name, null, desc, true);
    }

     



    public static Feature plan(String name, String desc) {
        return new Feature(name, null, desc, false);
    }

     
    public Feature lock() { return lock(null); }

     
    public Feature lock(String hint) {
        this.lockOn = true;
        this.enabled = true;
        this.lockHint = hint;
        return this;
    }

     
    public Feature off() { this.startOff = true; this.enabled = false; return this; }

    

    public Feature add(Setting s) { settings.add(s); return this; }

    public Feature bool(String n, boolean v) {
        Setting s = new Setting(BOOL, n);
        s.b = v;
        return add(s);
    }

    public Feature fnum(String n, double def, double min, double max, double step) {
        Setting s = new Setting(FLOAT, n);
        s.f = def; s.fmin = min; s.fmax = max; s.fstep = step;
        s.shown = s.t();
        return add(s);
    }

    public Feature inum(String n, int def, int min, int max) {
        Setting s = new Setting(INT, n);
        s.i = def; s.imin = min; s.imax = max;
        s.shown = s.t();
        return add(s);
    }

    public Feature en(String n, String[] opts, int idx) {
        Setting s = new Setting(ENUM, n);
        s.options = opts;
        s.idx = Math.max(0, Math.min(opts.length - 1, idx));
        return add(s);
    }

     
    public Feature act(String n, String action) {
        Setting s = new Setting(ACT, n);
        s.action = action;
        return add(s);
    }

     





    public Feature hk() {
        this.bind = com.onyx.clickgui.feature.KeyBinds.get(this.name);
        act("Bind", "bind");
        act("Unbind", "unbind");
        double sz = this.bind.size;
        if (sz < 0.5 || sz > 2.5) sz = 1.0;
        fnum("Size", sz, 0.5, 2.5, 0.1);
        return this;
    }

    

    public Setting set(String n) {
        for (Setting s : settings) {
            if (s.name.equalsIgnoreCase(n)) return s;
        }
        return null;
    }

    public boolean hasSettings() { return !settings.isEmpty(); }
}
