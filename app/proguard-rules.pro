# 保持 Xposed 入口类不被混淆（release 用；debug 不需要但留着无害）
-keep class com.opal.clickgui.xposed.** { *; }
-keep class com.opal.clickgui.feature.Native { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
