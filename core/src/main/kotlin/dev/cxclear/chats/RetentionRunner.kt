package dev.cxclear.chats

import dev.cxclear.model.ChatDeleteResult
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自动保留执行器：对话页拿到全量会话后跑一次（闸门在 [ChatScanCache.autoRunDone]）；空列表则什么都不做。 */
object RetentionRunner {
    suspend fun runIfNeeded(
        sessions: List<ChatSessionSummary>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatDeleteResult {
        val (config, prefs) = withContext(Dispatchers.IO) {
            RetentionStore.read() to AppPreferences.read()
        }
        if (!prefs.autoCleanEnabled || !config.isActive() || sessions.isEmpty()) {
            return ChatDeleteResult(deletedSessions = 0, freedBytes = 0L)
        }
        val targets = config.match(sessions, nowMillis)
        if (targets.isEmpty()) return ChatDeleteResult(deletedSessions = 0, freedBytes = 0L)
        return deleteSessions(targets)
    }
}
