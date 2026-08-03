package dev.cxclear.chats

/**
 * 对话扫描结果的进程内缓存。
 *
 * 切走对话页再回来、切工具筛选、手动↔自动切换都不该触发重扫；
 * 只有缓存为空（首次）或被 [invalidate]（删除后）才重新扫全量。
 * 始终存 Codex + Claude 全集，展示层再按筛选裁剪。
 *
 * [autoRunDone] 同属进程级：自动保留每个进程只跑一次，不因导航重置。
 */
object ChatScanCache {
    @Volatile
    private var sessions: List<ChatSessionSummary>? = null

    @Volatile
    var autoRunDone: Boolean = false
        private set

    /** 当前缓存；尚未扫过或已失效时为 null。 */
    fun snapshot(): List<ChatSessionSummary>? = sessions

    fun update(list: List<ChatSessionSummary>) {
        sessions = list
    }

    fun invalidate() {
        sessions = null
    }

    fun markAutoRunDone() {
        autoRunDone = true
    }
}
