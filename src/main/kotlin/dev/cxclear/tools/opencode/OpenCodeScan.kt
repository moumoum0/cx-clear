package dev.cxclear.tools.opencode

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.PathSnapshot
import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Open Code 会话扫描：读 opencode.db 的 session / project 表，
 * 外加 storage/session_diff/<session_id>.json 的体积。
 */

/**
 * 扫描 Open Code 会话。数据存储在 SQLite 数据库中：
 *   ~/.local/share/opencode/opencode.db
 *   - session 表：会话元数据（id, title, project_id, directory, time_updated 等）
 *   - session_message 表：消息内容（关联 session_id）
 *   - project 表：项目信息（id, worktree, name）
 *   ~/.local/share/opencode/storage/session_diff/<session_id>.json：会话的 diff 数据
 */
internal fun scanOpenCodeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val dbFile = opencodeDbFile() ?: return emptyList()
    val storageDir = opencodeStorageDir()
    val sessions = mutableListOf<ChatSessionSummary>()

    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            // 加载所有项目信息到内存，用于后续关联
            val projects = mutableMapOf<String, String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, worktree, name FROM project").use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val worktree = rs.getString("worktree")
                        val name = rs.getString("name")
                        // 从 worktree 路径提取项目名，如果有 name 就用 name
                        val projectName = name?.takeIf { it.isNotBlank() }
                            ?: worktree?.let { Path.of(it).fileName?.toString() }
                        if (projectName != null) {
                            projects[id] = projectName
                        }
                    }
                }
            }

            // 扫描所有会话
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT id, project_id, directory, title, time_created, time_updated
                    FROM session
                    WHERE time_archived IS NULL
                    ORDER BY time_updated DESC
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        runCatching {
                            val sessionId = rs.getString("id")
                            val projectId = rs.getString("project_id")
                            val directory = rs.getString("directory")
                            val title = rs.getString("title")
                            val timeUpdated = rs.getLong("time_updated")

                            // 项目名从 projects map 获取
                            val projectName = projects[projectId]

                            // 计算数据大小
                            val entries = mutableListOf<PathSnapshot>()
                            var totalSize = 0L

                            // session_diff 文件
                            storageDir?.resolve("session_diff")?.resolve("$sessionId.json")?.let { diffFile ->
                                if (Files.isRegularFile(diffFile)) {
                                    readPathSnapshot(diffFile)?.let {
                                        entries += it
                                        totalSize += it.size
                                    }
                                }
                            }

                            // 数据库中的消息数据（估算）
                            conn.createStatement().use { msgStmt ->
                                msgStmt.executeQuery(
                                    "SELECT COUNT(*) as cnt, SUM(LENGTH(data)) as total_len FROM session_message WHERE session_id = '$sessionId'"
                                ).use { msgRs ->
                                    if (msgRs.next()) {
                                        val dataSize = msgRs.getLong("total_len")
                                        totalSize += dataSize
                                    }
                                }
                            }

                            val summary = ChatSessionSummary(
                                tool = OpenCodePlugin.chat,
                                id = sessionId,
                                title = title,
                                project = projectName,
                                updatedMillis = timeUpdated,
                                sizeBytes = totalSize,
                                mainFile = dbFile, // Open Code 使用数据库，没有单独的文件
                                rootDir = dbFile.parent,
                                entries = entries,
                            )
                            sessions += summary
                            onFound(summary)
                        }.onFailure { e ->
                            println("Failed to process Open Code session: ${e.message}")
                        }
                    }
                }
            }
        }
    }.onFailure { e ->
        println("Failed to scan Open Code sessions: ${e.message}")
        e.printStackTrace()
    }

    return sessions.sortedByDescending { it.updatedMillis }
}
