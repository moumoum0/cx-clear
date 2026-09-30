package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.isSafeTarget
import dev.cxclear.cli.parseFileFilters
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.targetJson
import kotlinx.coroutines.runBlocking

internal object FindFilesCommand : Command {
    override val name = listOf("find", "files")
    override val description = "查找可清理文件"
    override val flags = FILE_FILTER_FLAGS
    override val switches = setOf("json", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val filters = parseFileFilters(args)

        val snapshot = runBlocking { scanOnce(tools) }
        var results = filters.apply(snapshot.results, System.currentTimeMillis())

        if (args.safeOnly) results = results.filter(::isSafeTarget)


        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "find files",
                "total" to results.size,
                "bytes" to results.sumOf { it.bytes },
                "targets" to results.map { targetJson(it) },
            )
        )
        return Cli.EXIT_OK
    }
}
