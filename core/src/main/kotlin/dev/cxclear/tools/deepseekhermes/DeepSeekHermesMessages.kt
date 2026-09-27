package dev.cxclear.tools.deepseekhermes

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatRole
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.util.jsonArr
import dev.cxclear.util.jsonObj
import dev.cxclear.util.jsonStr

internal fun loadDeepSeekHermesMessages(session: ChatSessionSummary): List<ChatMessage> = runCatching {
    readDeepSeekLog(session.mainFile) { events ->
        buildList {
            for (event in events) {
                val role = when (event.jsonStr("type")) {
                    "user/message" -> {
                        if (event.jsonObj("data")?.jsonObj("source")?.jsonStr("kind") != "user") continue
                        ChatRole.USER
                    }
                    "assistant/message" -> ChatRole.ASSISTANT
                    else -> continue
                }
                val data = event.jsonObj("data") ?: continue
                val text = when (role) {
                    ChatRole.USER -> textBlocks(data)
                    ChatRole.ASSISTANT -> textBlocks(data.jsonObj("message"))
                } ?: continue
                if (text.isBlank() || text.trimStart().startsWith("<system-reminder>")) continue
                add(ChatMessage(role, text, jsonMillis(event["time"])))
            }
        }
    }
}.getOrDefault(emptyList())

/** content 数组里只取 type=text，工具调用和推理块不进详情。 */
internal fun textBlocks(owner: Map<String, Any?>?): String? {
    val blocks = owner?.jsonArr("content") ?: return null
    return blocks.filterIsInstance<Map<String, Any?>>()
        .mapNotNull { block ->
            if (block.jsonStr("type") != "text") null else block.jsonStr("text")?.trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
}

private fun jsonMillis(value: Any?): Long? = (value as? Number)?.toLong()?.takeIf { it > 0L }
