package dev.cxclear.cli.commands

import dev.cxclear.AppMeta
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs

internal object VersionCommand : Command {
    override val name = listOf("version")
    override val description = "print the application version (--version / -v)"

    override fun execute(args: ParsedArgs): Int {
        Cli.printJson(mapOf("name" to AppMeta.NAME, "version" to AppMeta.VERSION))
        return Cli.EXIT_OK
    }
}
