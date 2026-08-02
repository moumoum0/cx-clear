package dev.cxclear.chats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 自动保留执行器：应用启动与进入对话页时各跑一次。
 * 只在策略开启时运行；不后台驻留，不定时触发。
 *
 * 需要传入当前已扫描的会话列表：如果页面还没扫描，传空列表则什么都不做，
 * 等用户刷新后再根据新列表执行。
 */
object RetentionRunner {
    /**
     * 按当前策略删除过期会话。
     *
     * @param sessions 已扫描到的会话列表（空列表时跳过）
     * @return 执行结果；策略关闭或列表为空时返回无操作的零值结果
     */
    suspend fun runIfNeeded(sessions: List<ChatSessionSummary>): ChatDeleteResult {
        val policy = withContext(Dispatchers.IO) { RetentionStore.read() }
        if (!policy.enabled || sessions.isEmpty()) {
            return ChatDeleteResult(deletedSessions = 0, freedBytes = 0L)
        }
        val cutoffMillis = Instant.now().minusSeconds(policy.days * 86400L).toEpochMilli()
        return deleteSessionsBefore(sessions, cutoffMillis)
    }
}
