package dev.cxclear.cli.commands

import dev.cxclear.cli.CHAT_FILTER_FLAGS
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.selectChats
import dev.cxclear.cli.sessionJson

internal object FindChatsCommand : Command {
    override val name = listOf("find", "chats")
    override val description = "find chat sessions"
    override val flags = CHAT_FILTER_FLAGS
    override val switches = setOf("json", "rules")

    override fun execute(args: ParsedArgs): Int {
        val selection = selectChats(args)
        Cli.printJson(
            buildMap {
                put("ok", true)
                put("command", "find chats")
                putAll(selection.rulesExtra)
                put("total", selection.sessions.size)
                put("bytes", selection.bytes)
                put("sessions", selection.sessions.map(::sessionJson))
            }
        )
        return Cli.EXIT_OK
    }
}
