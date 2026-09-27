package dev.cxclear.tools.cursor

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatRole
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonStr
import java.nio.file.Files

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
