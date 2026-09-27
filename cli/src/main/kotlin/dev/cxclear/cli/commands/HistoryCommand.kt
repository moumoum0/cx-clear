package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.CliUsageException
import dev.cxclear.storage.CleanHistory
import dev.cxclear.util.formatBytes

internal object HistoryCommand : Command {
    override val name = listOf("history")
    override val description = "查看清理历史"
    override val flags = setOf("limit")

    override fun execute(args: ParsedArgs): Int {
        val limit = args.value("limit")?.let {
            it.toIntOrNull()?.takeIf { value -> value >= 0 }
                ?: throw CliUsageException("--limit 必须是非负整数")
        } ?: 10
        val records = CleanHistory.readAll().takeLast(limit)
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "history",
                "records" to records.map {
                    mapOf(
                        "timestamp" to it.epochMillis,
                        "bytes" to it.freedBytes,
                        "bytes_label" to formatBytes(it.freedBytes),
                    )
                },
            )
        )
        return Cli.EXIT_OK
    }
}
