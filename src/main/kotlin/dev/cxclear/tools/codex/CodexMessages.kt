package dev.cxclear.tools.codex

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatRole
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonArr
import dev.cxclear.util.jsonObj
import dev.cxclear.util.jsonStr
import java.nio.file.Files
import java.nio.file.Path

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
