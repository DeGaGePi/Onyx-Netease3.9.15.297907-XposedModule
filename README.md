# Onyx — Netease MC 3.9.15.297907 Xposed 模块

Minecraft 基岩版网易客户端（`com.netease.x19`）的 Xposed 功能模块完整源码，版本 **2.4.14**，模块包名 `com.onyx.mc`。
包含 Java 层 ClickGUI / FeatureBus 与 native 层 `libonyxmc.so`。

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/` | Xposed 入口、Scope 注入识别、Overlay 挂载、ClickGUI、功能总线、多语言 |
| `app/src/main/cpp/` | inline hook、内存安全读写、实体/GameMode 定位、虚表自动识别 |
| `app/src/main/res/`、`assets/` | 资源与 `xposed_init` / `xposed_scope` |
| `xposed-api-stub/src/` | 编译期 Xposed API 桩（不打包进 APK） |
| `fast_pack.py` / `fast_pack.bat` | 免 Gradle 的手工打包链路 |
| `build_native.bat` | NDK 编译 `libonyxmc.so` |

## 构建

手工打包（约 10 秒，不启动 Gradle daemon）：

```
fast_pack.bat            :: 仅 Java，复用已有 .so
fast_pack.bat --native   :: 改动 cpp 后必须先重建 .so
```

链路：`aapt2 compile → link → javac --release 8 → d8 → zip → zipalign -p 4 → apksigner`。
产物输出到构建目录下的 `dist/`（具体路径见 `fast_pack.py`）。

native 需要 Android NDK 25.2.9519653 的 `aarch64-linux-android24-clang++`，
产物必须经 `llvm-strip --strip-unneeded` 后再放入 APK。

版本号需同步两处：`app/build.gradle` 的 `versionName` 与 `FeatureBus.java` 的 `VERSION`。

## 使用

1. 先安装模块 APK（`Onyx-2.4.14-debug-signed.apk`）。
2. 再用 NPatch 1.0.8 对目标 app 注入；打包时开启「输出日志到 Media」与「豁免环境校验」，签名校验设为 High，其余关闭。
3. 模块日志写在 `/sdcard/Android/data/<pkg>/files/onyx.log`。

## 兼容性

- native 库是 **arm64-v8a**，内部偏移针对该网易客户端 `libminecraftpe.so` 的特定版本；换客户端版本或改用国际版会导致 RVA 全部失效。
- 在 x86_64 模拟器上经 Houdini 翻译运行，inline hook 必须在加载时一次性全部安装；函数已被翻译后再改字节不会生效。

## 许可

GPL-3.0，见仓库 `LICENSE`。
