package dev.cxclear.util

/**
 * 人类可读的大小，1 KB = 1024 B，和资源管理器一致。
 * 放在 util：UI 卡片、CLI、翻转动画都用，放 Scanner.kt 会让展示层依赖扫描实现。
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
