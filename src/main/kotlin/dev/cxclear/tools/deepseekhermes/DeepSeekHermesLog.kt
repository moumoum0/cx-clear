package dev.cxclear.tools.deepseekhermes

import com.github.luben.zstd.ZstdInputStream
import dev.cxclear.util.MiniJson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * 会话日志是多帧 zstd 拼在一个文件里（session.vN.jsonl.zstd），也可能是明文 jsonl。
 * 连续帧要开 setContinuous，否则解完第一帧就停。
 */
internal class DeepSeekHermesLog(val path: Path) : AutoCloseable {
    private val reader: BufferedReader = openReader(path)

    fun lines(): Sequence<String> = reader.lineSequence()

    override fun close() {
        reader.close()
    }

    private fun openReader(file: Path): BufferedReader {
        val input = Files.newInputStream(file)
        val decoded = if (file.fileName.toString().endsWith(".zstd")) {
            ZstdInputStream(input).apply { setContinuous(true) }
        } else {
            input
        }
        return BufferedReader(InputStreamReader(decoded, Charsets.UTF_8))
    }
}

internal inline fun <T> readDeepSeekLog(file: Path, block: (Sequence<Map<String, Any?>>) -> T): T =
    DeepSeekHermesLog(file).use { log ->
        block(log.lines().mapNotNull { MiniJson.parse(it) as? Map<String, Any?> })
    }

/** 目录里优先最新一代 session.vN.jsonl(.zstd)，没有再退回旧文件名。 */
internal fun findSessionLog(dir: Path): Path? {
    var best: Path? = null
    var bestVersion = -1
    var legacy: Path? = null
    Files.newDirectoryStream(dir).use { entries ->
        for (entry in entries) {
            if (!Files.isRegularFile(entry)) continue
            val name = entry.fileName.toString()
            val version = sessionLogVersion(name)
            if (version != null) {
                if (version > bestVersion) {
                    bestVersion = version
                    best = entry
                }
            } else if (name == "session.jsonl" || name == "session.jsonl.zstd") {
                legacy = entry
            }
        }
    }
    return best ?: legacy
}

private val SESSION_LOG_NAME = Regex("""^session\.v(\d+)\.jsonl(?:\.zstd)?$""")

private fun sessionLogVersion(name: String): Int? =
    SESSION_LOG_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
