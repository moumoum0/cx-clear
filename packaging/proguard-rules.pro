# Compose Desktop release 收缩规则。主要目标：砍掉 material-icons-extended 里未用的图标。

# 入口
-keep class dev.cxclear.MainKt { *; }
-keep class dev.cxclear.cli.Cli { *; }
-keep class dev.cxclear.cli.CliKt { *; }

# JNA 走反射调用 native，必须整体保留。
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# zstd-jni 按平台名加载 dll，收缩时不能改类名。
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Compose / Skiko 反射与 native 绑定
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-dontwarn org.jetbrains.skiko.**

# 协程内部通过反射访问的字段
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Kotlin 元数据与内建
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# 收缩可能误报的通用告警
-dontwarn org.slf4j.**
