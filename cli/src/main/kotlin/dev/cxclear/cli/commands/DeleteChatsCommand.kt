package dev.cxclear.cli.commands

import dev.cxclear.chats.deleteSessions
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.CHAT_FILTER_FLAGS
import dev.cxclear.cli.apply
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.parseChatFilters
import dev.cxclear.cli.resolveChatTools
import dev.cxclear.cli.sessionJson
import kotlinx.coroutines.runBlocking

internal object DeleteChatsCommand : Command {
    override val name = listOf("delete", "chats")
    override val description = "delete matching chats"
    override val flags = CHAT_FILTER_FLAGS
    override val switches = setOf("json", "yes", "y", "preview")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveChatTools(args)
        val sessions = scanAllChatSessions(tools)
        val filters = parseChatFilters(args)
        val filtered = filters.apply(sessions, System.currentTimeMillis())

        if (filtered.isEmpty()) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete chats",
                    "preview" to true,
                    "matched" to 0,
                    "bytes" to 0L,
                )
            )
            return Cli.EXIT_OK
        }

        if (args.preview || !args.yes) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete chats",
                    "preview" to true,
                    "matched" to filtered.size,
                    "bytes" to filtered.sumOf { it.sizeBytes },
                    "sessions" to filtered.map { sessionJson(it) },
                )
            )
            return Cli.EXIT_OK
        }

        val result = runBlocking { deleteSessions(filtered) }
        val blocked = result.blockedTools.isNotEmpty()
        Cli.printJson(
            mapOf(
                "ok" to (!blocked && result.errors.isEmpty()),
                "command" to "delete chats",
                "preview" to false,
                "deleted" to result.deletedSessions,
                "freed_bytes" to result.freedBytes,
                "blocked_tools" to result.blockedTools,
                "errors" to result.errors,
            )
        )
        return when {
            blocked -> Cli.EXIT_BLOCKED
            result.errors.isNotEmpty() -> Cli.EXIT_FAIL
            else -> Cli.EXIT_OK
        }
    }
}
