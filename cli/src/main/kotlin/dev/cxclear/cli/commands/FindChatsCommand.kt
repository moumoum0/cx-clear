package dev.cxclear.cli.commands

import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.apply
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.parseChatFilters
import dev.cxclear.cli.resolveChatTools
import dev.cxclear.cli.sessionJson

internal object FindChatsCommand : Command {
    override val name = listOf("find", "chats")
    override val description = "查找对话记录"
    override val flags = setOf("tool", "older-than", "newer-than", "keep-recent", "keep-days")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveChatTools(args)
        val sessions = scanAllChatSessions(tools)
        val filters = parseChatFilters(args)
        val filtered = filters.apply(sessions, System.currentTimeMillis())

        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "find chats",
                "total" to filtered.size,
                "bytes" to filtered.sumOf { it.sizeBytes },
                "sessions" to filtered.map { sessionJson(it) },
            )
        )
        return Cli.EXIT_OK
    }
}
