package dev.cxclear.chats

import dev.cxclear.storage.AppDir
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonInt
import dev.cxclear.util.jsonMap
import dev.cxclear.util.jsonObj
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 对话扫描的按文件元数据缓存。扫描为了拿标题/项目名/会话头，常要把整个会话文件
 * 逐行解析一遍，这里按「工具 + 主文件路径」把结果存下来：mtime 和 size 都没变
 * 就直接复用，变了才重读。结果落盘到 ~/.cxclear/chat-meta-cache.json，
 * CLI 新进程、GUI 重启、开机静默清理都能接着用。
 */
object ChatFileMetaCache {
    private const val FILE_NAME = "chat-meta-cache.json"
    private const val VERSION = 1

    // 条目上限，超过时按最近使用淘汰；会话删除后残留的旧条目也靠它兜底。
    private const val MAX_ENTRIES = 4096

    private class Entry(val mtime: Long, val size: Long, val fields: Map<String, Any?>)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var loaded = false
    private var dirty = false

    /** mtime 和 size 都对上时返回缓存的字段，否则返回 null。 */
    fun lookup(toolId: String, file: Path, mtime: Long, size: Long): Map<String, Any?>? {
        synchronized(lock) {
            ensureLoaded()
            return entries[key(toolId, file)]?.takeIf { it.mtime == mtime && it.size == size }?.fields
        }
    }

    /** 记录一次扫描读到的结果。fields 要写全所有相关键，拿不到的值也写 null，防止下次重读。 */
    fun remember(toolId: String, file: Path, mtime: Long, size: Long, fields: Map<String, Any?>) {
        synchronized(lock) {
            ensureLoaded()
            entries[key(toolId, file)] = Entry(mtime, size, fields)
            dirty = true
            while (entries.size > MAX_ENTRIES) {
                val eldest = entries.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
    }

    /** 一次扫描结束后统一落盘。全程命中缓存没有新写入时跳过；失败不影响扫描本身。 */
    fun flush() {
        synchronized(lock) {
            if (!dirty) return
            writeToDisk()
            dirty = false
        }
    }

    /** 清空内存态（不删磁盘文件），下次使用时重新读盘。测试用它配合 AppDir 切换。 */
    fun invalidate() {
        synchronized(lock) {
            entries.clear()
            loaded = false
            dirty = false
        }
    }

    private fun key(toolId: String, file: Path): String = "$toolId|$file"

    private fun file(): Path? = AppDir.dir()?.resolve(FILE_NAME)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val path = file() ?: return
        if (!Files.exists(path)) return
        runCatching {
            val root = MiniJson.parse(Files.readString(path)) ?: return
            if (root.jsonInt("version") != VERSION) return
            val stored = root.jsonObj("entries") ?: return
            for ((k, value) in stored) {
                val obj = value.jsonMap() ?: continue
                val mtime = (obj["mtime"] as? Number)?.toLong() ?: continue
                val size = (obj["size"] as? Number)?.toLong() ?: continue
                val fields = obj["fields"]?.jsonMap() ?: continue
                entries[k] = Entry(mtime, size, fields)
            }
        }
    }

    private fun writeToDisk() {
        val path = file() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val stored = LinkedHashMap<String, Any?>()
            for ((k, entry) in entries) {
                stored[k] = linkedMapOf(
                    "mtime" to entry.mtime,
                    "size" to entry.size,
                    "fields" to entry.fields,
                )
            }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, MiniJson.stringify(linkedMapOf("version" to VERSION, "entries" to stored)))
            runCatching {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
