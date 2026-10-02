package dev.cxclear.cli.commands

import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.spaceJson
import dev.cxclear.tools.chatTools
import kotlinx.coroutines.runBlocking

internal object StatusCommand : Command {
    override val name = listOf("status")
    override val description = "show tool disk usage and chat counts"
    override val flags = setOf("tool")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val snapshot = runBlocking { scanOnce(tools) }
        val chatTools = chatTools().filter { it.id in tools }
        val chatCounts = chatTools.associate { tool ->
            val sessions = scanAllChatSessions(setOf(tool))
            tool.id to sessions.size
        }

        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "status",
                "spaces" to snapshot.spaces.map { space ->
                    spaceJson(space) + ("chats" to (chatCounts[space.toolId] ?: 0))
                },
            )
        )
        return Cli.EXIT_OK
    }
}
