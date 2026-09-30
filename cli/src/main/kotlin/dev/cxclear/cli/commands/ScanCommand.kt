package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.parseFileFilters
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.profileAndTarget
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.spaceJson
import dev.cxclear.cli.targetJson
import dev.cxclear.model.Risk
import kotlinx.coroutines.runBlocking

internal object ScanCommand : Command {
    override val name = listOf("scan")
    override val description = "scan tool disk usage and clean targets"
    override val flags = FILE_FILTER_FLAGS
    override val switches = setOf("json", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val filters = parseFileFilters(args)
        val snapshot = runBlocking { scanOnce(tools) }
        var results = filters.apply(snapshot.results, System.currentTimeMillis())

        if (args.safeOnly) {
            results = results.filter {
                val (_, target) = profileAndTarget(it.toolId, it.targetId)
                target.risk == Risk.SAFE
            }
        }

        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "scan",
                "tools" to tools.toList(),
                "spaces" to snapshot.spaces.map { spaceJson(it) },
                "targets" to results.map { targetJson(it) },
            )
        )
        return Cli.EXIT_OK
    }
}
