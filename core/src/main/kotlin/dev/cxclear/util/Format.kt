package dev.cxclear.util

fun formatBytes(bytes: Long): String {
    // 内存单位转化，最小单位 KB，不足 1KB 的非零值兜底 0.1 免得显示成 0.0
    if (bytes <= 0) return "0 KB"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = (bytes / 1024.0).coerceAtLeast(0.1)
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return when {
        value >= 100 -> "${value.toInt()} ${units[unit]}"
        else -> String.format("%.1f %s", value, units[unit])
    }
}
