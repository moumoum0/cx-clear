package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.selectFiles
import dev.cxclear.cli.spaceJson
import dev.cxclear.cli.targetJson

internal object ScanCommand : Command {
    override val name = listOf("scan")
    override val description = "scan tool disk usage and clean targets"
    override val flags = FILE_FILTER_FLAGS
    override val switches = setOf("json", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val selection = selectFiles(args)
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "scan",
                "tools" to selection.tools.toList(),
                "spaces" to selection.spaces.map(::spaceJson),
                "targets" to selection.results.map(::targetJson),
            )
        )
        return Cli.EXIT_OK
    }
}
