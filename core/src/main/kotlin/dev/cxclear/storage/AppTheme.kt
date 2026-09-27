package dev.cxclear.storage

enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式"),
}

enum class AppColorScheme(
    val displayName: String,
    val description: String,
) {
    APP_DEFAULT("应用默认", "使用应用默认配色方案"),
    CLOUD_FIELD("云野", "云野 - 自然清新的绿色主题"),
}
