package dev.cxclear.cli.commands

import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.FILE_FILTER_FLAGS
import dev.cxclear.cli.parseFileFilters
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.profileAndTarget
import dev.cxclear.cli.resolveTools
import dev.cxclear.cli.scanOnce
import dev.cxclear.cli.targetJson
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.Risk
import dev.cxclear.storage.CleanHistory
import kotlinx.coroutines.runBlocking

internal object CleanCommand : Command {
    override val name = listOf("clean")
    override val description = "按默认勾选或指定项清理"
    override val flags = FILE_FILTER_FLAGS + "targets"
    override val switches = setOf("json", "yes", "y", "preview", "safe-only")

    override fun execute(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val filters = parseFileFilters(args)
        val targetIds = args.value("targets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val snapshot = runBlocking { scanOnce(tools) }

        val selected = filters.apply(snapshot.results, System.currentTimeMillis()).filter { result ->
            if (!result.exists || result.bytes <= 0L || result.deletionPlan == null) return@filter false
            val (_, target) = profileAndTarget(result.toolId, result.targetId)

            (targetIds?.let { result.targetId in it } ?: target.defaultSelected) &&
                (!args.safeOnly || target.risk == Risk.SAFE)
        }

        val requests = selected.mapNotNull { result ->
            val (profile, target) = profileAndTarget(result.toolId, result.targetId)
            val plan = result.deletionPlan ?: return@mapNotNull null
            CleanRequest(profile, target, plan)
        }

        val planned = selected.map { targetJson(it) }
        if (args.preview || !args.yes) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "clean",
                    "preview" to true,
                    "matched" to planned.size,
                    "bytes" to selected.sumOf { it.bytes },
                    "targets" to planned,
                )
            )
            return Cli.EXIT_OK
        }

        if (requests.isEmpty()) {
            Cli.printJson(mapOf("ok" to true, "command" to "clean", "preview" to false, "freed_bytes" to 0L, "targets" to emptyList<Any>()))
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
                        "command" to "clean",
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
                    "command" to "clean",
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
