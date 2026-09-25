package dev.cxclear.storage

import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import java.io.File

/** 系统盘占用快照：总容量、已用、可用（字节）。totalBytes <= 0 表示读取失败。 */
data class DiskUsage(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val hasData: Boolean get() = totalBytes > 0L
}

/**
 * 读取系统盘占用。Windows 取 SystemDrive（通常是 C:），其他系统取根分区。
 *
 * 已用 = 总容量 - 可用；用 usableSpace（考虑配额）而非 freeSpace，贴近资源管理器口径。
 * 任何异常都返回空快照（totalBytes = 0），由 UI 走占位态，绝不抛出。
 */
object DiskUsageReader {
    fun readSystemDrive(): DiskUsage {
        return runCatching {
            val root = systemVolumeRoot() ?: return DiskUsage(0L, 0L, 0L)
            val total = root.totalSpace
            if (total <= 0L) return DiskUsage(0L, 0L, 0L)
            val free = root.usableSpace
            DiskUsage(totalBytes = total, usedBytes = (total - free).coerceAtLeast(0L), freeBytes = free)
        }.getOrDefault(DiskUsage(0L, 0L, 0L))
    }

    private fun systemVolumeRoot(): File? = when (currentOs()) {
        HostOs.WINDOWS -> {
            val drive = System.getenv("SystemDrive")?.takeIf { it.isNotBlank() } ?: "C:"
            File(drive + File.separator)
        }
        else -> File("/")
    }
}
