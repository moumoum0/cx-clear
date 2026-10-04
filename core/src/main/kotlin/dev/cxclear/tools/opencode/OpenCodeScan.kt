package dev.cxclear.tools.opencode

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.PathSnapshot
import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * 扫描 Open Code 会话：读 opencode.db 的 session / project 表，
 * 外加 storage/session_diff/<session_id>.json 的体积。
 */
internal fun scanOpenCodeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val dbFile = opencodeDbFile() ?: return emptyList()
    val storageDir = opencodeStorageDir()
    val sessions = mutableListOf<ChatSessionSummary>()

    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            // 项目表一次读进内存，后面按 project_id 查项目名
            val projects = mutableMapOf<String, String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, worktree, name FROM project").use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val worktree = rs.getString("worktree")
                        val name = rs.getString("name")
                        val projectName = name?.takeIf { it.isNotBlank() }
                            ?: worktree?.let { Path.of(it).fileName?.toString() }
                        if (projectName != null) {
                            projects[id] = projectName
                        }
                    }
                }
            }

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

                            val projectName = projects[projectId]

                            val entries = mutableListOf<PathSnapshot>()
                            var totalSize = 0L

                            storageDir?.resolve("session_diff")?.resolve("$sessionId.json")?.let { diffFile ->
                                if (Files.isRegularFile(diffFile)) {
                                    readPathSnapshot(diffFile)?.let {
                                        entries += it
                                        totalSize += it.size
                                    }
                                }
                            }

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
