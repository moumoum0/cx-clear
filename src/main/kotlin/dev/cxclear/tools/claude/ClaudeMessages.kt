package dev.cxclear.tools.claude

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatRole
import dev.cxclear.tools.isoToMillis
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonStr
import java.nio.file.Files
import java.nio.file.Path

@Suppress("UNCHECKED_CAST")
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
