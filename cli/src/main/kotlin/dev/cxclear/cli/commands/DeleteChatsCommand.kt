package dev.cxclear.cli.commands

import dev.cxclear.cli.CHAT_FILTER_FLAGS
import dev.cxclear.cli.Command
import dev.cxclear.cli.DELETE_SWITCHES
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.deleteChats
import dev.cxclear.cli.selectChats

internal object DeleteChatsCommand : Command {
    override val name = listOf("delete", "chats")
    override val description = "delete matching chats"
    override val flags = CHAT_FILTER_FLAGS
    override val switches = DELETE_SWITCHES + "rules"

    override fun execute(args: ParsedArgs): Int = deleteChats("delete chats", args, selectChats(args))
}
