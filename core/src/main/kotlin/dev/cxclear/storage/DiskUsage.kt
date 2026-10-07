package dev.cxclear.storage

import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import java.io.File

data class DiskUsage(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val hasData: Boolean get() = totalBytes > 0L
}

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
