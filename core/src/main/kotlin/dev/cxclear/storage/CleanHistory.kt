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

data class DailyClean(val date: LocalDate, val bytes: Long)

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

    fun recentDaily(limit: Int = 7): List<DailyClean> =
        readAll()
            .groupBy { it.date }
            .map { (date, records) -> DailyClean(date, records.sumOf { it.freedBytes }) }
            .sortedBy { it.date }
            .takeLast(limit)
}
