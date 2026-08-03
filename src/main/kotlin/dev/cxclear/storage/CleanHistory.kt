package dev.cxclear.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一次清理的落库记录：什么时候、真删掉了多少字节。 */
data class CleanRecord(val epochMillis: Long, val freedBytes: Long) {
    val date: LocalDate get() = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** 按天聚合后的清理量，用于柱状图。没有清理的天不会出现在列表里。 */
data class DailyClean(val date: LocalDate, val bytes: Long)

/**
 * 累计清理历史。存成一行一条的 CSV（`epochMillis,freedBytes`），
 * 放在 `~/.cxclear/clean-history.csv`，避免为这点数据引入序列化依赖。
 *
 * 读写都容错：文件不存在、某行损坏都只跳过，不让 UI 因为记录问题崩掉。
 */
object CleanHistory {
    private const val FILE_NAME = "clean-history.csv"

    private fun file(): Path? = AppDir.dir()?.resolve(FILE_NAME)

    /** 追加一条记录。freedBytes <= 0 不记（没删掉东西不算一次有效清理）。 */
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

    /** 清空累计历史。文件不存在也视为成功。 */
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
