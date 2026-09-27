package dev.cxclear.cli.commands

import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.isSafeTarget
import dev.cxclear.cli.parseSize
import dev.cxclear.cli.profileAndTarget
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.targetJson
import dev.cxclear.model.CleanEvent
import dev.cxclear.storage.CleanHistory
import kotlinx.coroutines.runBlocking

internal object DeleteFilesCommand : Command {
    override val name = listOf("delete", "files")
    override val description = "删除匹配的文件"
    override val flags = setOf("tool", "type", "size-gt", "size-lt", "targets")
    override val switches = setOf("json", "yes", "y", "preview", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val typeFilter = args.value("type")
        val sizeGt = args.value("size-gt")?.let { parseSize(it) }
        val sizeLt = args.value("size-lt")?.let { parseSize(it) }
        val targetIds = args.value("targets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

        val snapshot = runBlocking { scanOnce(tools) }
        var results = snapshot.results.filter { it.exists && it.bytes > 0L && it.deletionPlan != null }

        if (args.safeOnly) results = results.filter(::isSafeTarget)

        if (targetIds != null) {
            results = results.filter { it.targetId in targetIds }
        }

        if (typeFilter != null) {
            results = results.filter { it.targetId.contains(typeFilter, ignoreCase = true) }
        }

        if (sizeGt != null) {
            results = results.filter { it.bytes > sizeGt }
        }
        if (sizeLt != null) {
            results = results.filter { it.bytes < sizeLt }
        }

        val requests = results.mapNotNull { result ->
            val (profile, target) = profileAndTarget(result.toolId, result.targetId)
            val plan = result.deletionPlan ?: return@mapNotNull null
            CleanRequest(profile, target, plan)
        }

        if (results.isEmpty()) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete files",
                    "preview" to true,
                    "matched" to 0,
                    "bytes" to 0L,
                )
            )
            return Cli.EXIT_OK
        }

        if (args.preview || !args.yes) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete files",
                    "preview" to true,
                    "matched" to results.size,
                    "bytes" to results.sumOf { it.bytes },
                    "targets" to results.map { targetJson(it) },
                )
            )
            return Cli.EXIT_OK
        }

        return runBlocking {
            var blocked: List<String> = emptyList()
            var freed = 0L
            val done = mutableListOf<Map<String, Any?>>()
            val errors = mutableListOf<String>()
            clean(requests).collect { event ->
                when (event) {
                    is CleanEvent.Blocked -> blocked = event.tools
                    is CleanEvent.TargetDone -> {
                        done += mapOf(
                            "target_id" to event.targetId,
                            "label" to event.label,
                            "freed_bytes" to event.freedBytes,
                            "error" to event.error,
                        )
                        event.error?.let { errors += "${event.label}：$it" }
                    }
                    is CleanEvent.AllDone -> {
                        freed = event.totalFreedBytes
                        if (event.totalFreedBytes > 0L) CleanHistory.append(event.totalFreedBytes)
                    }
                    is CleanEvent.Started -> Unit
                }
            }
            if (blocked.isNotEmpty()) {
                Cli.printJson(
                    mapOf(
                        "ok" to false,
                        "command" to "delete files",
                        "preview" to false,
                        "blocked_tools" to blocked,
                        "error" to "检测到 ${blocked.joinToString("、")} 仍在运行",
                    )
                )
                return@runBlocking Cli.EXIT_BLOCKED
            }
            Cli.printJson(
                mapOf(
                    "ok" to errors.isEmpty(),
                    "command" to "delete files",
                    "preview" to false,
                    "freed_bytes" to freed,
                    "targets" to done,
                    "errors" to errors,
                )
            )
            if (errors.isEmpty()) Cli.EXIT_OK else Cli.EXIT_FAIL
        }
    }
}
