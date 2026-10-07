package dev.cxclear.tools.deepseekhermes

import com.github.luben.zstd.ZstdInputStream
import dev.cxclear.util.MiniJson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

internal class DeepSeekHermesLog(val path: Path) : AutoCloseable {
    private val reader: BufferedReader = openReader(path)

    fun lines(): Sequence<String> = reader.lineSequence()

    override fun close() {
        reader.close()
    }

    private fun openReader(file: Path): BufferedReader {
        val input = Files.newInputStream(file)
        val decoded = if (file.fileName.toString().endsWith(".zstd")) {
            // 一个文件里拼了多帧，不开 setContinuous 解完第一帧就停。
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
