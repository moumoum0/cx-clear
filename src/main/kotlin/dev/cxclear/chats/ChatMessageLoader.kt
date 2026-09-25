package dev.cxclear.chats

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * 会话消息加载：按工具从 jsonl 文件或 SQLite 读出 ChatMessage 列表。
 * loadChatMessages 是对外入口（ChatsView / Cli 在用），各 loadXxxMessages 只在这里内部调度。
 */

/** 只读 IO，不改任何文件。 */
@Suppress("UNCHECKED_CAST")
fun loadChatMessages(session: ChatSessionSummary): List<ChatMessage> = runCatching {
    when (session.tool) {
        ChatTool.CLAUDE -> loadClaudeMessages(session.mainFile)
        ChatTool.CODEX -> loadCodexMessages(session.mainFile)
        ChatTool.CURSOR -> loadCursorMessages(session)
        ChatTool.OPENCODE -> loadOpenCodeMessages(session)
        ChatTool.DEEPSEEK_HERMES -> emptyList() // DeepSeek Hermes 暂不支持读取会话内容
    }
}.getOrDefault(emptyList())

internal fun loadClaudeMessages(file: Path): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(file).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            val type = obj.jsonStr("type") ?: continue
            val role = when (type) {
                "user" -> {
                    if (obj.jsonBool("isMeta") == true) continue
                    ChatRole.USER
                }
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            @Suppress("UNCHECKED_CAST")
            val content = (obj as? Map<String, Any?>)?.get("message")
                ?.let { (it as? Map<String, Any?>)?.get("content") }
            val text = when (content) {
                is String -> content.trim()
                is List<*> -> content
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { block ->
                        when (block.jsonStr("type")) {
                            "text" -> block.jsonStr("text")?.trim()
                            else -> null
                        }
                    }
                    .joinToString("\n").trim()
                else -> null
            }
            if (!text.isNullOrBlank()) {
                val ts = obj.jsonStr("timestamp")?.let { isoToMillis(it) }
                messages += ChatMessage(role, text, ts)
            }
        }
    }
    return messages
}

@Suppress("UNCHECKED_CAST")
internal fun loadCodexMessages(file: Path): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(file).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            if (obj.jsonStr("type") != "response_item") continue
            val payload = obj.jsonObj("payload") ?: continue
            if (payload.jsonStr("type") != "message") continue
            val role = when (payload.jsonStr("role")) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            val content = payload.jsonArr("content")
            val text = content?.filterIsInstance<Map<String, Any?>>()
                ?.mapNotNull { block ->
                    when (block.jsonStr("type")) {
                        "input_text", "output_text" -> block.jsonStr("text")?.trim()
                        else -> null
                    }
                }
                ?.joinToString("\n")?.trim()
            if (!text.isNullOrBlank()) {
                messages += ChatMessage(role, text, null)
            }
        }
    }
    return messages
}

@Suppress("UNCHECKED_CAST")
internal fun loadCursorMessages(session: ChatSessionSummary): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(session.mainFile, Charsets.UTF_8).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            val role = when (obj.jsonStr("role")) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            // 跳过元数据消息
            if (obj.jsonBool("isMeta") == true || obj.jsonBool("isSidechain") == true) {
                continue
            }
            val content = (obj as? Map<String, Any?>)?.get("message")
                ?.let { (it as? Map<String, Any?>)?.get("content") }
            val text = when (content) {
                is String -> content.trim()
                is List<*> -> content.asSequence()
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { block ->
                        when (block.jsonStr("type")) {
                            "text" -> block.jsonStr("text")?.trim()
                            else -> null
                        }
                    }
                    .joinToString("\n").trim()
                else -> null
            }
            if (!text.isNullOrBlank()) {
                val finalText = if (role == ChatRole.USER) unwrapCursorUserQuery(text) else text
                messages += ChatMessage(role, finalText, null)
            }
        }
    }
    return messages
}

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

                        // 跳过非消息类型
                        if (type in setOf("agent-switched", "model-switched", "context-compacted")) {
                            continue
                        }

                        val data = MiniJson.parse(dataJson) as? Map<String, Any?> ?: continue

                        // 提取角色和内容
                        val role = when (type) {
                            "user-message", "user" -> ChatRole.USER
                            "assistant-message", "assistant" -> ChatRole.ASSISTANT
                            else -> continue
                        }

                        // 从 data 中提取文本内容
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
                            is Map<*,*> -> {
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
