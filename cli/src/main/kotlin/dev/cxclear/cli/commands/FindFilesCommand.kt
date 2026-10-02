package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.selectFiles
import dev.cxclear.cli.targetJson

internal object FindFilesCommand : Command {
    override val name = listOf("find", "files")
    override val description = "find cleanable files"
    override val flags = FILE_FILTER_FLAGS
    override val switches = setOf("json", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val results = selectFiles(args).results
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "find files",
                "total" to results.size,
                "bytes" to results.sumOf { it.bytes },
                "targets" to results.map(::targetJson),
            )
        )
        return Cli.EXIT_OK
    }
}
