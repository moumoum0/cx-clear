package dev.cxclear.cli

import dev.cxclear.chats.deleteSessions
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.ScanResult
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.storage.CleanHistory
import kotlinx.coroutines.runBlocking

// 文件 / 会话命令共用的筛选流水线与删除执行；scan、find、delete、clean 只决定选哪些、输出什么
internal class FileSelection(
    val tools: Set<String>,
    val spaces: List<ToolSpaceResult>,
    val results: List<ScanResult>,
)

// 没给 --targets 时：defaultOnly=true 只取默认勾选项（clean），否则取全部匹配项
internal fun selectFiles(args: ParsedArgs, defaultOnly: Boolean = false): FileSelection {
    val tools = resolveTools(args)
    val filters = parseFileFilters(args)
    val targetIds = resolveTargetIds(args, tools)
    val snapshot = runBlocking { scanOnce(tools) }
    var results = filters.apply(snapshot.results, System.currentTimeMillis())
    if (args.safeOnly) results = results.filter(::isSafeTarget)
    results = when {
        targetIds != null -> results.filter { it.targetId in targetIds }
        defaultOnly -> results.filter { profileAndTarget(it.toolId, it.targetId).second.defaultSelected }
        else -> results
    }
    return FileSelection(tools, snapshot.spaces, results)
}

internal fun deleteFiles(command: String, args: ParsedArgs, results: List<ScanResult>): Int {
    val deletable = results.filter { it.deletionPlan != null }
    if (args.isPreview) {
        Cli.printJson(previewJson(command, deletable.size, deletable.sumOf { it.bytes }, "targets", deletable.map(::targetJson)))
        return Cli.EXIT_OK
    }
    val requests = deletable.map { result ->
        val (profile, target) = profileAndTarget(result.toolId, result.targetId)
        CleanRequest(profile, target, result.deletionPlan!!)
    }
    var blocked: List<String> = emptyList()
    var freed = 0L
    val done = mutableListOf<Map<String, Any?>>()
    val errors = mutableListOf<String>()
    if (requests.isNotEmpty()) runBlocking {
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
    }
    if (blocked.isNotEmpty()) {
        Cli.printJson(
            mapOf(
                "ok" to false,
                "command" to command,
                "preview" to false,
                "blocked_tools" to blocked,
                "error" to "${blocked.joinToString(", ")} is still running",
            )
        )
        return Cli.EXIT_BLOCKED
    }
    Cli.printJson(
        mapOf(
            "ok" to errors.isEmpty(),
            "command" to command,
            "preview" to false,
            "freed_bytes" to freed,
            "targets" to done,
            "errors" to errors,
        )
    )
    return if (errors.isEmpty()) Cli.EXIT_OK else Cli.EXIT_FAIL
}

internal class ChatSelection(
    val sessions: List<ChatSessionSummary>,
    val rules: Map<String, Any?>?,
) {
    val bytes: Long get() = sessions.sumOf { it.sizeBytes }
    val rulesExtra: Map<String, Any?> get() = rules?.let { mapOf("rules" to it) }.orEmpty()
}

internal fun selectChats(args: ParsedArgs): ChatSelection {
    val tools = resolveChatTools(args)
    val filters = parseChatFilters(args)
    val sessions = filters.apply(scanAllChatSessions(tools), System.currentTimeMillis())
    val rules = filters.rulesConfig?.let { rulesInfo(it, rulesSource(args), ruleSelection(args)) }
    return ChatSelection(sessions, rules)
}

internal fun deleteChats(command: String, args: ParsedArgs, selection: ChatSelection): Int {
    val sessions = selection.sessions
    if (args.isPreview) {
        Cli.printJson(previewJson(command, sessions.size, selection.bytes, "sessions", sessions.map(::sessionJson), selection.rulesExtra))
        return Cli.EXIT_OK
    }
    // 空集不进 deleteSessions，免得为了删 0 条还去枚举进程、误报 blocked
    val result = if (sessions.isEmpty()) null else runBlocking { deleteSessions(sessions) }
    val blockedTools = result?.blockedTools.orEmpty()
    val errors = result?.errors.orEmpty()
    Cli.printJson(
        mapOf(
            "ok" to (blockedTools.isEmpty() && errors.isEmpty()),
            "command" to command,
            "preview" to false,
            "deleted" to (result?.deletedSessions ?: 0),
            "freed_bytes" to (result?.freedBytes ?: 0L),
            "blocked_tools" to blockedTools,
            "errors" to errors,
        )
    )
    return when {
        blockedTools.isNotEmpty() -> Cli.EXIT_BLOCKED
        errors.isNotEmpty() -> Cli.EXIT_FAIL
        else -> Cli.EXIT_OK
    }
}
