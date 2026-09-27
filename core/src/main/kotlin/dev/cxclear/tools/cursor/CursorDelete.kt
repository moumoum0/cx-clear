package dev.cxclear.tools.cursor

import dev.cxclear.clean.deleteSessionEntries
import dev.cxclear.model.ChatSessionSummary

/**
 * 删除 Cursor 会话。
 * 直接按 ComposerService.deleteComposer 的落盘顺序改 state.vscdb：
 * composerHeaders、composerData、bubble/checkpoint/ofs 前缀，以及 transcript。
 * Cursor 在跑时由进程检测整批跳过，避免 WAL 把改动冲掉。
 */
internal fun deleteCursorSession(session: ChatSessionSummary): Pair<Long, List<String>> {
    val dbPath = resolveCursorStateDb() ?: return 0L to listOf("Cursor 状态库不存在")
    val (dbFreed, errors) = deleteCursorComposerFromStateDb(dbPath, session.id)
    if (errors.isNotEmpty()) return 0L to errors

    val (fileFreed, fileErrors) = deleteSessionEntries(session)
    return (dbFreed + fileFreed) to fileErrors
}
