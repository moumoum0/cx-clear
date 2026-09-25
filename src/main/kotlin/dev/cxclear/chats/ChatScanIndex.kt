package dev.cxclear.chats

/**
 * 会话扫描总调度：把各工具的 scanXxxSessions 串起来，汇总进度后按更新时间倒序。
 */

/**
 * 枚举本机所有 [ChatTool.CODEX] + [ChatTool.CLAUDE] + [ChatTool.CURSOR] + [ChatTool.OPENCODE] 会话，按更新时间倒序。运行在 IO 线程。
 *
 * [onProgress] 每找到一条会话回调累计数量与字节，供 UI 实时展示「已找到」。
 */
fun scanAllChatSessions(
    tools: Set<ChatTool> = ChatTool.entries.toSet(),
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
    if (ChatTool.CODEX in tools) result += scanCodexSessions(onFound)
    if (ChatTool.CLAUDE in tools) result += scanClaudeSessions(onFound)
    if (ChatTool.CURSOR in tools) result += scanCursorSessions(onFound)
    if (ChatTool.OPENCODE in tools) result += scanOpenCodeSessions(onFound)
    return result.sortedByDescending { it.updatedMillis }
}
