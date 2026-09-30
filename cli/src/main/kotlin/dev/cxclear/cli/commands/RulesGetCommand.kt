package dev.cxclear.cli.commands

import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionStore
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs

internal object RulesGetCommand : Command {
    override val name = listOf("rules", "get")
    override val description = "read the auto-clean policy"

    override fun execute(args: ParsedArgs): Int {
        Cli.printJson(mapOf("ok" to true, "config" to RetentionJson.toMap(RetentionStore.read())))
        return Cli.EXIT_OK
    }
}
