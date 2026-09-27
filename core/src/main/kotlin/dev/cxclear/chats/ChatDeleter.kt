package dev.cxclear.chats

import dev.cxclear.clean.isToolProcessRunning
import dev.cxclear.model.ChatDeleteResult
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.tools.ALL_PROFILES
import dev.cxclear.tools.toolPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun profileForTool(tool: ChatTool) =
    ALL_PROFILES.firstOrNull { it.id == tool.id }

internal val defaultChatToolIsRunning: (ChatTool) -> Boolean = { tool ->
    val profile = profileForTool(tool)
    if (profile == null) false else isToolProcessRunning(profile)
}

suspend fun deleteSession(
    session: ChatSessionSummary,
    toolIsRunning: (ChatTool) -> Boolean = defaultChatToolIsRunning,
): ChatDeleteResult = deleteSessions(listOf(session), toolIsRunning)

suspend fun deleteSessions(
    sessions: List<ChatSessionSummary>,
    toolIsRunning: (ChatTool) -> Boolean = defaultChatToolIsRunning,
): ChatDeleteResult = withContext(Dispatchers.IO) {
    val blocked = sessions.map { it.tool }.distinct().filter(toolIsRunning)
    val blockedTools = blocked.map { it.displayName }
    val blockedToolIds = blocked.map { it.id }.toSet()

    val toDelete = sessions.filter { it.tool.id !in blockedToolIds }

    var freed = 0L
    var count = 0
    val errors = mutableListOf<String>()

    for (session in toDelete) {
        val plugin = toolPlugin(session)
        val (sessionFreed, sessionErrors) = if (plugin == null) {
            0L to listOf("未登记的工具：${session.tool.id}")
        } else {
            plugin.delete(session)
        }
        freed += sessionFreed
        if (sessionErrors.isEmpty()) {
            count++
        }
        errors += sessionErrors
    }

    ChatDeleteResult(
        deletedSessions = count,
        freedBytes = freed,
        blockedTools = blockedTools,
        errors = errors.distinct(),
    )
}
