
-keep class com.opal.clickgui.xposed.** { *; }
-keep class com.opal.clickgui.feature.Native { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
