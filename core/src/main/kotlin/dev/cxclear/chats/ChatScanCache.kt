package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary

/**
 * 对话扫描结果的进程内缓存：切走再回来、切筛选、手动↔自动切换都不重扫，
 * 只有缓存为空或 [invalidate]（删除后）才重新扫全量，展示层按筛选裁剪。[autoRunDone] 同样进程级。
 */
object ChatScanCache {
    @Volatile
    private var sessions: List<ChatSessionSummary>? = null

    @Volatile
    var autoRunDone: Boolean = false
        private set

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
