package dev.cxclear.tools.cursor

import dev.cxclear.clean.deleteSessionEntries
import dev.cxclear.model.ChatSessionSummary

internal fun deleteCursorSession(session: ChatSessionSummary): Pair<Long, List<String>> {
    val dbPath = resolveCursorStateDb() ?: return 0L to listOf("Cursor 状态库不存在")
    val (dbFreed, errors) = deleteCursorComposerFromStateDb(dbPath, session.id)
    if (errors.isNotEmpty()) return 0L to errors

    val (fileFreed, fileErrors) = deleteSessionEntries(session)
    return (dbFreed + fileFreed) to fileErrors
}
