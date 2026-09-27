package dev.cxclear.util

/**
 * 人类可读的大小。Windows 资源管理器口径，1 KB = 1024 B。
 *
 * 住在 util 而不是 scan：UI 卡片、CLI 输出、翻转动画都在用它，
 * 挂在 Scanner.kt 里等于让展示层为了格式化数字去依赖扫描实现。
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return when {
        unit == 0 -> "${bytes} B"
        value >= 100 -> "${value.toInt()} ${units[unit]}"
        else -> String.format("%.1f %s", value, units[unit])
    }
}
