package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.isSafeTarget
import dev.cxclear.cli.parseSize
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.targetJson
import kotlinx.coroutines.runBlocking

internal object FindFilesCommand : Command {
    override val name = listOf("find", "files")
    override val description = "查找可清理文件"
    override val flags = setOf("tool", "type", "size-gt", "size-lt")
    override val switches = setOf("json", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val typeFilter = args.value("type")
        val sizeGt = args.value("size-gt")?.let { parseSize(it) }
        val sizeLt = args.value("size-lt")?.let { parseSize(it) }

        val snapshot = runBlocking { scanOnce(tools) }
        var results = snapshot.results.filter { it.exists && it.bytes > 0L }

        if (args.safeOnly) results = results.filter(::isSafeTarget)

        if (typeFilter != null) {
            results = results.filter { it.targetId.contains(typeFilter, ignoreCase = true) }
        }

        if (sizeGt != null) {
            results = results.filter { it.bytes > sizeGt }
        }
        if (sizeLt != null) {
            results = results.filter { it.bytes < sizeLt }
        }

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
