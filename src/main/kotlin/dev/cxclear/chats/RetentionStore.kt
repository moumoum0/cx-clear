package dev.cxclear.chats

import dev.cxclear.profiles.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 自动保留策略落盘。
 * 格式：`~/.cxclear/chat-retention.txt`，每行一个 `key=value`，容错解析。
 */
object RetentionStore {
    private const val FILE_NAME = "chat-retention.txt"

    private fun file(): Path? =
        homeDir()?.resolve(".cxclear")?.resolve(FILE_NAME)

    fun read(): RetentionPolicy {
        val path = file() ?: return RetentionPolicy()
        if (!Files.exists(path)) return RetentionPolicy()
        val props = runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
        val enabled = props["enabled"]?.toBooleanStrictOrNull() ?: false
        val days = props["days"]?.toIntOrNull()?.coerceIn(1, 3650) ?: 30
        return RetentionPolicy(enabled, days)
    }

    fun write(policy: RetentionPolicy) {
        val path = file() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.write(
                path,
                listOf("enabled=${policy.enabled}", "days=${policy.days}"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }
}
