package dev.cxclear.tools.opencode

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatRole
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.util.MiniJson
import java.sql.DriverManager

/**
 * 从 Open Code 数据库加载消息。
 * Open Code 将消息存储在 session_message 表中，type 字段区分不同类型。
 * 用户和助手消息的 data 字段为 JSON，包含消息内容。
 */
@Suppress("UNCHECKED_CAST")
internal fun loadOpenCodeMessages(session: ChatSessionSummary): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    val dbFile = opencodeDbFile() ?: return emptyList()

    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT type, data, time_created
                    FROM session_message
                    WHERE session_id = '${session.id}'
                    ORDER BY seq
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        val type = rs.getString("type")
                        val dataJson = rs.getString("data")
                        val timeCreated = rs.getLong("time_created")

                        if (type in setOf("agent-switched", "model-switched", "context-compacted")) {
                            continue
                        }

                        val data = MiniJson.parse(dataJson) as? Map<String, Any?> ?: continue

                        val role = when (type) {
                            "user-message", "user" -> ChatRole.USER
                            "assistant-message", "assistant" -> ChatRole.ASSISTANT
                            else -> continue
                        }

                        val content = data["content"] ?: data["message"]
                        val text = when (content) {
                            is String -> content.trim()
                            is List<*> -> content.asSequence()
                                .filterIsInstance<Map<String, Any?>>()
                                .mapNotNull { block ->
                                    when (block["type"]) {
                                        "text" -> block["text"] as? String
                                        else -> null
                                    }
                                }
                                .joinToString("\n").trim()
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                val contentMap = content as? Map<String, Any?>
                                contentMap?.get("text") as? String ?: ""
                            }
                            else -> null
                        }

                        if (!text.isNullOrBlank()) {
                            messages += ChatMessage(role, text, timeCreated)
                        }
                    }
                }
            }
        }
    }.onFailure { e ->
        println("Failed to load Open Code messages for session ${session.id}: ${e.message}")
    }

    return messages
}
