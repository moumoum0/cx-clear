package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary

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
