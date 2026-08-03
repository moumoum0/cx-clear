package dev.cxclear.chats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动保留执行器：应用启动与进入对话页时各跑一次。
 * 只在有生效规则时运行；不后台驻留，不定时触发。
 *
 * 需要传入当前已扫描的会话列表：如果页面还没扫描，传空列表则什么都不做，
 * 等用户刷新后再根据新列表执行。
 */
object RetentionRunner {
    /**
     * 按当前配置删除命中会话。规则之间取「或」，任一规则命中即删。
     *
     * @param sessions 已扫描到的会话列表（空列表时跳过）
     * @param nowMillis 判定时间基准，整次判定内保持一致
     * @return 执行结果；没有生效规则或列表为空时返回无操作的零值结果
     */
    suspend fun runIfNeeded(
        sessions: List<ChatSessionSummary>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatDeleteResult {
        val config = withContext(Dispatchers.IO) { RetentionStore.read() }
        if (!config.isActive() || sessions.isEmpty()) {
            return ChatDeleteResult(deletedSessions = 0, freedBytes = 0L)
        }
        val targets = config.match(sessions, nowMillis)
        if (targets.isEmpty()) return ChatDeleteResult(deletedSessions = 0, freedBytes = 0L)
        return deleteSessions(targets)
    }
}
