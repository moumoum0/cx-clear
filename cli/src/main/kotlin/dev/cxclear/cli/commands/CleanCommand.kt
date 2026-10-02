package dev.cxclear.cli.commands

import dev.cxclear.cli.Command
import dev.cxclear.cli.DELETE_SWITCHES
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.deleteFiles
import dev.cxclear.cli.selectFiles

internal object CleanCommand : Command {
    override val name = listOf("clean")
    override val description = "clean default or named targets"
    override val flags = FILE_FILTER_FLAGS + "targets"
    override val switches = DELETE_SWITCHES + "safe-only"

    override fun execute(args: ParsedArgs): Int =
        deleteFiles("clean", args, selectFiles(args, defaultOnly = true).results)
}
