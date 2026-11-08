package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

 


public abstract class XC_MethodHook implements Comparable<XC_MethodHook> {

    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;

    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    @Override
    public int compareTo(XC_MethodHook another) { return 0; }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;

        public Object getResult() { return result; }

        public void setResult(Object result) { this.result = result; }

        public Throwable getThrowable() { return throwable; }

        public boolean hasThrowable() { return throwable != null; }

        public void setThrowable(Throwable throwable) { this.throwable = throwable; }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public class Unhook {
        public Unhook(Member hookMethod, XC_MethodHook callback) {}

        public Member getHookedMethod() { return null; }

        public XC_MethodHook getCallback() { return null; }

        public void unhook() {}
    }
}
