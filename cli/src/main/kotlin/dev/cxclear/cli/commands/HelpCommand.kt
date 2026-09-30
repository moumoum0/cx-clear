package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.CliSchema
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs

internal object HelpCommand : Command {
    override val name = listOf("help")
    override val description = "print usage"

    override fun execute(args: ParsedArgs): Int {
        Cli.printJson(CliSchema.helpPayload())
        return Cli.EXIT_OK
    }
}
