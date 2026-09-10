package dev.cxclear.chats

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private const val COMPOSER_DATA_PREFIX = "composerData:"
private const val COMPOSER_HEADERS_VERSION_KEY = "composer.composerHeaders.version"
private const val COMPOSER_DATA_INDEX_KEY = "composer.composerData"

private val CURSOR_KV_PREFIXES = listOf(
    "bubbleId:",
    "checkpointId:",
    "ofsContent:",
    "codeBlockDiff:",
    "codeBlockPartialInlineDiffFates:",
    "agentKv:checkpoint:",
    "agentKv:bubbleCheckpoint:",
)

/**
 * 按 Cursor ComposerService.deleteComposer 的落盘顺序清 state.vscdb。
 * 不碰 agentKv:blob，那些键没有 composerId 前缀，无法安全归属到单条会话。
 */
internal fun deleteCursorComposerFromStateDb(dbPath: Path, composerId: String): Pair<Long, List<String>> {
    if (!Files.isRegularFile(dbPath)) {
        return 0L to listOf("Cursor 状态库不存在")
    }
    require(composerId.isNotBlank()) { "Cursor 会话 ID 为空" }

    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("PRAGMA busy_timeout = 3000") }
                val ids = linkedSetOf(composerId)
                collectSubComposerIds(conn, composerId, ids)
                var freed = 0L
                for (id in ids) {
                    freed += deleteOneComposer(conn, id)
                }
                bumpComposerHeadersVersion(conn)
                conn.commit()
                val errors = emptyList<String>()
                if (freed == 0L && !composerExists(conn, composerId)) {
                    0L to errors
                } else {
                    freed to errors
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }.getOrElse { e ->
        0L to listOf("删除 Cursor 会话失败：${e.message ?: "未知错误"}")
    }
}

private fun composerExists(conn: Connection, composerId: String): Boolean {
    conn.prepareStatement("SELECT 1 FROM cursorDiskKV WHERE key = ? LIMIT 1").use { stmt ->
        stmt.setString(1, COMPOSER_DATA_PREFIX + composerId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) return true
        }
    }
    if (!tableExists(conn, "composerHeaders")) return false
    conn.prepareStatement("SELECT 1 FROM composerHeaders WHERE composerId = ? LIMIT 1").use { stmt ->
        stmt.setString(1, composerId)
        stmt.executeQuery().use { rs ->
            return rs.next()
        }
    }
}

private fun collectSubComposerIds(conn: Connection, composerId: String, acc: MutableSet<String>) {
    val jsonText = kvText(conn, COMPOSER_DATA_PREFIX + composerId) ?: return
    val ids = MiniJson.parse(jsonText).jsonArr("subComposerIds")
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    for (id in ids) {
        if (acc.add(id)) collectSubComposerIds(conn, id, acc)
    }
}

private fun deleteOneComposer(conn: Connection, composerId: String): Long {
    val exactKeys = listOf(
        COMPOSER_DATA_PREFIX + composerId,
        "cloudAgentDraft:$composerId",
    )
    val prefixKeys = CURSOR_KV_PREFIXES.map { "$it$composerId" }

    var freed = 0L
    conn.prepareStatement(
        "SELECT COALESCE(SUM(LENGTH(value)), 0) FROM cursorDiskKV WHERE key = ?",
    ).use { stmt ->
        for (key in exactKeys) {
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) freed += rs.getLong(1)
            }
        }
    }
    conn.prepareStatement(
        "SELECT COALESCE(SUM(LENGTH(value)), 0) FROM cursorDiskKV WHERE key LIKE ?",
    ).use { stmt ->
        for (key in prefixKeys) {
            stmt.setString(1, "$key%")
            stmt.executeQuery().use { rs ->
                if (rs.next()) freed += rs.getLong(1)
            }
        }
    }

    conn.prepareStatement("DELETE FROM cursorDiskKV WHERE key = ?").use { stmt ->
        for (key in exactKeys) {
            stmt.setString(1, key)
            stmt.executeUpdate()
        }
    }
    conn.prepareStatement("DELETE FROM cursorDiskKV WHERE key LIKE ?").use { stmt ->
        for (key in prefixKeys) {
            stmt.setString(1, "$key%")
            stmt.executeUpdate()
        }
    }

    if (tableExists(conn, "composerHeaders")) {
        conn.prepareStatement("DELETE FROM composerHeaders WHERE composerId = ?").use { stmt ->
            stmt.setString(1, composerId)
            stmt.executeUpdate()
        }
    }
    filterComposerDataIndex(conn, composerId)
    return freed
}

private fun filterComposerDataIndex(conn: Connection, composerId: String) {
    if (!tableExists(conn, "ItemTable")) return
    val raw = itemText(conn, COMPOSER_DATA_INDEX_KEY) ?: return
    val root = MiniJson.parse(raw) as? MutableMap<String, Any?> ?: return
    var changed = false

    val selected = (root["selectedComposerIds"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it != composerId }
    if (selected != null && selected.size != (root["selectedComposerIds"] as? List<*>)?.size) {
        root["selectedComposerIds"] = selected
        changed = true
    }

    val focused = (root["lastFocusedComposerIds"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it != composerId }
    if (focused != null && focused.size != (root["lastFocusedComposerIds"] as? List<*>)?.size) {
        root["lastFocusedComposerIds"] = focused
        changed = true
    }

    if (!changed) return
    conn.prepareStatement("INSERT OR REPLACE INTO ItemTable (key, value) VALUES (?, ?)").use { stmt ->
        stmt.setString(1, COMPOSER_DATA_INDEX_KEY)
        stmt.setString(2, jsonStringify(root))
        stmt.executeUpdate()
    }
}

private fun bumpComposerHeadersVersion(conn: Connection) {
    if (!tableExists(conn, "ItemTable")) return
    conn.prepareStatement("INSERT OR REPLACE INTO ItemTable (key, value) VALUES (?, ?)").use { stmt ->
        stmt.setString(1, COMPOSER_HEADERS_VERSION_KEY)
        stmt.setString(2, "${System.currentTimeMillis()}-cxclear")
        stmt.executeUpdate()
    }
}

private fun kvText(conn: Connection, key: String): String? {
    conn.prepareStatement("SELECT value FROM cursorDiskKV WHERE key = ?").use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs ->
            if (!rs.next()) return null
            return rs.getString(1)
        }
    }
}

private fun itemText(conn: Connection, key: String): String? {
    conn.prepareStatement("SELECT value FROM ItemTable WHERE key = ?").use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs ->
            if (!rs.next()) return null
            return rs.getString(1)
        }
    }
}

private fun tableExists(conn: Connection, name: String): Boolean {
    conn.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
    ).use { stmt ->
        stmt.setString(1, name)
        stmt.executeQuery().use { rs -> return rs.next() }
    }
}

private fun jsonStringify(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> {
        val d = value.toDouble()
        if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
    is String -> jsonEscape(value)
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
        "${jsonEscape(k.toString())}:${jsonStringify(v)}"
    }
    is List<*> -> value.joinToString(",", "[", "]") { jsonStringify(it) }
    else -> "null"
}

private fun jsonEscape(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
