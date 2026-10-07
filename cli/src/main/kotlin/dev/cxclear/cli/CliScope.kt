package dev.cxclear.cli

import dev.cxclear.model.ChatTool
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.ToolProfile
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.tools.ALL_PROFILES
import dev.cxclear.tools.chatToolById
import dev.cxclear.tools.chatTools

internal data class ScanSnapshot(
    val spaces: List<ToolSpaceResult>,
    val results: List<ScanResult>,
)

internal suspend fun scanOnce(toolIds: Set<String>): ScanSnapshot {
    val profiles = ALL_PROFILES.filter { it.id in toolIds }
    var results = emptyList<ScanResult>()
    var spaces = emptyList<ToolSpaceResult>()
    scanStream(profiles).collect { event ->
        when (event) {
            is ScanEvent.SpaceScanned -> spaces = event.spaces
            is ScanEvent.TargetsScanned -> results = event.results
            is ScanEvent.Started -> Unit
        }
    }
    return ScanSnapshot(spaces, results)
}

internal fun resolveTools(args: ParsedArgs): Set<String> {
    val ids = args.csv("tool")
    if (ids.isEmpty()) {
        val prefs = AppPreferences.read().defaultTools
        return prefs.ifEmpty { ALL_PROFILES.map { it.id }.toSet() }
    }
    val known = ALL_PROFILES.map { it.id }.toSet()
    val unknown = ids.filter { it !in known }
    if (unknown.isNotEmpty()) throw CliUsageException("unknown tool: ${unknown.joinToString(",")}")
    return ids.toSet()
}

internal fun resolveChatTools(args: ParsedArgs): Set<ChatTool> {
    val ids = args.csv("tool")
    if (ids.isEmpty()) return chatTools().toSet()
    return ids.map { id -> chatToolById(id) ?: throw CliUsageException("unknown tool: $id") }.toSet()
}

internal fun resolveTargetIds(args: ParsedArgs, tools: Set<String>): Set<String>? {
    // --targets 按所选工具下的清理项校验：写错 id 直接报错，放行会得到静默 matched:0
    val ids = args.csv("targets").ifEmpty { return null }
    val known = ALL_PROFILES.filter { it.id in tools }.flatMap { p -> p.targets.map { it.id } }.toSet()
    val unknown = ids.filter { it !in known }
    if (unknown.isNotEmpty()) throw CliUsageException("unknown target: ${unknown.joinToString(",")}")
    return ids.toSet()
}

internal fun profileAndTarget(toolId: String, targetId: String): Pair<ToolProfile, CleanTarget> {
    val profile = ALL_PROFILES.first { it.id == toolId }
    return profile to profile.targets.first { it.id == targetId }
}

internal fun isSafeTarget(result: ScanResult): Boolean =
    profileAndTarget(result.toolId, result.targetId).second.risk == Risk.SAFE
