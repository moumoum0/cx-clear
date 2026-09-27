package dev.cxclear.chats

import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.tools.chatTools
import dev.cxclear.tools.toolPlugin
import dev.cxclear.tools.tools as registeredTools

fun scanAllChatSessions(
    tools: Set<ChatTool> = chatTools().toSet(),
    onProgress: (count: Int, bytes: Long) -> Unit = { _, _ -> },
): List<ChatSessionSummary> {
    val result = mutableListOf<ChatSessionSummary>()
    var count = 0
    var bytes = 0L
    val onFound: (ChatSessionSummary) -> Unit = { session ->
        count++
        bytes += session.sizeBytes
        onProgress(count, bytes)
    }
    for (plugin in registeredTools()) {
        if (plugin.chat in tools) result += plugin.scan(onFound)
    }
    return result.sortedByDescending { it.updatedMillis }
}

fun loadChatMessages(session: ChatSessionSummary): List<ChatMessage> = runCatching {
    toolPlugin(session)?.load?.invoke(session).orEmpty()
}.getOrDefault(emptyList())
