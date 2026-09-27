package dev.cxclear.tools.opencode

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.PathSnapshotKind
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager

/**
 * 删除 Open Code 会话。
 * Open Code 使用 SQLite 存储，需要：
 * 1. 从数据库中删除 session 记录（会级联删除 session_message）
 * 2. 删除 storage/session_diff/<session_id>.json 文件
 */
internal fun deleteOpenCodeSession(session: ChatSessionSummary): Pair<Long, List<String>> {
    val dbFile = opencodeDbFile()
    if (dbFile == null) {
        return 0L to listOf("Open Code 数据库文件不存在")
    }

    var freed = 0L
    val errors = mutableListOf<String>()

    runCatching {
        for (entry in session.entries) {
            if (entry.kind == PathSnapshotKind.FILE && Files.exists(entry.path)) {
                try {
                    Files.delete(entry.path)
                    freed += entry.size
                } catch (e: IOException) {
                    errors += "${entry.path.fileName}：${e.message ?: "删除失败"}"
                }
            }
        }

        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.autoCommit = false
            try {
                var messageDataSize = 0L
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT SUM(LENGTH(data)) as total FROM session_message WHERE session_id = '${session.id}'"
                    ).use { rs ->
                        if (rs.next()) {
                            messageDataSize = rs.getLong("total")
                        }
                    }
                }

                conn.createStatement().use { stmt ->
                    val deleted = stmt.executeUpdate(
                        "DELETE FROM session WHERE id = '${session.id}'"
                    )
                    if (deleted > 0) {
                        freed += messageDataSize
                        conn.commit()
                    } else {
                        errors += "会话 ${session.title} 在数据库中不存在"
                        conn.rollback()
                    }
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }.onFailure { e ->
        errors += "删除 Open Code 会话失败：${e.message}"
    }

    return freed to errors
}
