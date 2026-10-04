package dev.cxclear.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CleanRecord(val epochMillis: Long, val freedBytes: Long) {
    val date: LocalDate get() = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

// 按天聚合后的清理量，用于柱状图。没有清理的天不会出现在列表里。
data class DailyClean(val date: LocalDate, val bytes: Long)

/**
 * 累计清理历史：`~/.cxclear/clean-history.csv`，一行一条 `epochMillis,freedBytes`。
 * 读写都容错：文件不存在、某行损坏都只跳过。
 */
object CleanHistory {
    private const val FILE_NAME = "clean-history.csv"

    private fun file(): Path? = AppDir.dir()?.resolve(FILE_NAME)

    fun append(freedBytes: Long, epochMillis: Long = System.currentTimeMillis()) {
        if (freedBytes <= 0L) return
        val path = file() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.write(
                path,
                listOf("$epochMillis,$freedBytes"),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun readAll(): List<CleanRecord> {
        val path = file() ?: return emptyList()
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size != 2) return@mapNotNull null
                val millis = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                val bytes = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                CleanRecord(millis, bytes)
            }
        }.getOrDefault(emptyList())
    }

    fun totalBytes(): Long = readAll().sumOf { it.freedBytes }

    fun clear() {
        val path = file() ?: return
        runCatching { Files.deleteIfExists(path) }
    }

    /**
     * 按天聚合，只返回有清理记录的天，按日期升序（最新在末尾，柱状图从左到右即时间顺序）。
     * 最多返回最近 [limit] 天。
     */
    fun recentDaily(limit: Int = 7): List<DailyClean> =
        readAll()
            .groupBy { it.date }
            .map { (date, records) -> DailyClean(date, records.sumOf { it.freedBytes }) }
            .sortedBy { it.date }
            .takeLast(limit)
}
