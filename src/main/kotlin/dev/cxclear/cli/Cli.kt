package dev.cxclear.cli

import dev.cxclear.chats.ChatTool
import dev.cxclear.chats.MiniJson
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionParseResult
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.deleteSessions
import dev.cxclear.chats.effectiveConditions
import dev.cxclear.chats.isActive
import dev.cxclear.chats.match
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** 给 AI 调的无头入口。stdout 只出 JSON；破坏性操作默认预览。 */
object Cli {
    const val EXIT_OK = 0
    const val EXIT_FAIL = 1
    const val EXIT_BLOCKED = 2
    const val EXIT_USAGE = 3

    fun run(args: Array<String>): Int {
        val parsed = parseArgs(args) ?: return EXIT_USAGE
        return try {
            dispatch(parsed)
        } catch (e: CliUsageException) {
            printError(e.message ?: "参数错误")
            EXIT_USAGE
        } catch (e: Exception) {
            printError(e.message ?: e::class.simpleName ?: "未知错误")
            EXIT_FAIL
        }
    }

    fun runAndExit(args: Array<String>): Nothing {
        exitProcess(run(args))
    }

    private fun dispatch(args: ParsedArgs): Int = when (args.command) {
        listOf("help") -> {
            printJson(CliSchema.helpPayload())
            EXIT_OK
        }
        listOf("schema") -> {
            printJson(CliSchema.schemaPayload())
            EXIT_OK
        }
        listOf("scan") -> cmdScan(args)
        listOf("clean") -> cmdClean(args)
        listOf("chats", "list") -> cmdChatsList(args)
        listOf("chats", "preview") -> cmdChatsPreview(args)
        listOf("chats", "delete") -> cmdChatsDelete(args)
        listOf("rules", "get") -> cmdRulesGet()
        listOf("rules", "validate") -> cmdRulesValidate(args)
        listOf("rules", "put") -> cmdRulesPut(args)
        else -> {
            printError("未知命令：${args.command.joinToString(" ").ifBlank { "(空)"} }")
            EXIT_USAGE
        }
    }

    private fun cmdScan(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val snapshot = runBlocking { scanOnce(tools) }
        printJson(
            mapOf(
                "ok" to true,
                "tools" to tools.toList(),
                "spaces" to snapshot.spaces.map { spaceJson(it) },
                "targets" to snapshot.results.filter { it.exists && it.bytes > 0L }.map { targetJson(it) },
            )
        )
        return EXIT_OK
    }

    private fun cmdClean(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val risk = parseRisk(args)
        val snapshot = runBlocking { scanOnce(tools) }
        val selected = snapshot.results.filter { result ->
            if (!result.exists || result.bytes <= 0L || result.deletionPlan == null) return@filter false
            val (_, target) = profileAndTarget(result.toolId, result.targetId)
            when (risk) {
                null -> target.defaultSelected
                else -> target.risk == risk
            }
        }
        val requests = selected.mapNotNull { result ->
            val (profile, target) = profileAndTarget(result.toolId, result.targetId)
            val plan = result.deletionPlan ?: return@mapNotNull null
            CleanRequest(profile, target, plan)
        }
        val planned = selected.map { targetJson(it) }
        if (!args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "dry_run" to true,
                    "planned" to planned,
                    "bytes" to selected.sumOf { it.bytes },
                )
            )
            return EXIT_OK
        }
        if (requests.isEmpty()) {
            printJson(mapOf("ok" to true, "dry_run" to false, "freed_bytes" to 0L, "targets" to emptyList<Any>()))
            return EXIT_OK
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
                printJson(
                    mapOf(
                        "ok" to false,
                        "blocked_tools" to blocked,
                        "error" to "检测到 ${blocked.joinToString("、")} 仍在运行",
                    )
                )
                return@runBlocking EXIT_BLOCKED
            }
            printJson(
                mapOf(
                    "ok" to errors.isEmpty(),
                    "dry_run" to false,
                    "freed_bytes" to freed,
                    "targets" to done,
                    "errors" to errors,
                )
            )
            if (errors.isEmpty()) EXIT_OK else EXIT_FAIL
        }
    }

    private fun cmdChatsList(args: ParsedArgs): Int {
        val chatTools = resolveChatTools(args)
        val sessions = scanAllChatSessions(chatTools)
        printJson(mapOf("ok" to true, "sessions" to sessions.map { sessionJson(it) }))
        return EXIT_OK
    }

    private fun cmdChatsPreview(args: ParsedArgs): Int {
        val chatTools = resolveChatTools(args)
        val sessions = scanAllChatSessions(chatTools)
        val config = RetentionStore.read()
        val matched = config.match(sessions, System.currentTimeMillis())
        printJson(
            mapOf(
                "ok" to true,
                "dry_run" to true,
                "active" to config.isActive(),
                "matched" to matched.map { sessionJson(it) },
                "bytes" to matched.sumOf { it.sizeBytes },
            )
        )
        return EXIT_OK
    }

    private fun cmdChatsDelete(args: ParsedArgs): Int {
        val ids = args.values("id")
        if (ids.isEmpty()) throw CliUsageException("chats delete 需要 --id tool:session")
        val wanted = ids.map { parseSessionRef(it) }
        val tools = wanted.map { it.first }.toSet()
        val sessions = scanAllChatSessions(tools)
        val byRef = sessions.associateBy { it.tool to it.id }
        val missing = wanted.filter { it !in byRef }
        if (missing.isNotEmpty()) {
            printJson(
                mapOf(
                    "ok" to false,
                    "error" to "找不到会话",
                    "missing" to missing.map { "${it.first.id}:${it.second}" },
                )
            )
            return EXIT_USAGE
        }
        val targets = wanted.map { byRef.getValue(it) }
        if (!args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "dry_run" to true,
                    "planned" to targets.map { sessionJson(it) },
                    "bytes" to targets.sumOf { it.sizeBytes },
                )
            )
            return EXIT_OK
        }
        val result = runBlocking { deleteSessions(targets) }
        val blocked = result.blockedTools.isNotEmpty()
        printJson(
            mapOf(
                "ok" to (!blocked && result.errors.isEmpty()),
                "dry_run" to false,
                "deleted_sessions" to result.deletedSessions,
                "freed_bytes" to result.freedBytes,
                "blocked_tools" to result.blockedTools,
                "errors" to result.errors,
            )
        )
        return when {
            blocked -> EXIT_BLOCKED
            result.errors.isNotEmpty() -> EXIT_FAIL
            else -> EXIT_OK
        }
    }

    private fun cmdRulesGet(): Int {
        printJson(mapOf("ok" to true, "config" to RetentionJson.toMap(RetentionStore.read())))
        return EXIT_OK
    }

    private fun cmdRulesValidate(args: ParsedArgs): Int {
        val parsed = readConfigJson(args)
        return when (parsed) {
            is RetentionParseResult.Fail -> {
                printJson(mapOf("ok" to false, "errors" to parsed.errors))
                EXIT_USAGE
            }
            is RetentionParseResult.Ok -> {
                printJson(
                    mapOf(
                        "ok" to true,
                        "config" to RetentionJson.toMap(parsed.config),
                        "warnings" to ruleWarnings(parsed.config),
                    )
                )
                EXIT_OK
            }
        }
    }

    private fun cmdRulesPut(args: ParsedArgs): Int {
        val parsed = readConfigJson(args)
        if (parsed is RetentionParseResult.Fail) {
            printJson(mapOf("ok" to false, "errors" to parsed.errors))
            return EXIT_USAGE
        }
        val config = (parsed as RetentionParseResult.Ok).config
        if (!args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "dry_run" to true,
                    "config" to RetentionJson.toMap(config),
                    "warnings" to ruleWarnings(config),
                )
            )
            return EXIT_OK
        }
        RetentionStore.write(config)
        printJson(
            mapOf(
                "ok" to true,
                "dry_run" to false,
                "config" to RetentionJson.toMap(RetentionStore.read()),
                "warnings" to ruleWarnings(config),
            )
        )
        return EXIT_OK
    }

    private fun readConfigJson(args: ParsedArgs): RetentionParseResult {
        val text = readInputText(args)
        if (text.isBlank()) return RetentionParseResult.Fail(listOf("规则 JSON 为空"))
        return RetentionJson.parse(text)
    }

    private fun readInputText(args: ParsedArgs): String {
        val file = args.value("file")
        return if (file != null && file != "-") {
            val path = Path.of(file)
            if (!Files.isRegularFile(path)) throw CliUsageException("找不到文件：$file")
            Files.readString(path)
        } else {
            System.`in`.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun ruleWarnings(config: RetentionConfig): List<String> {
        val warnings = mutableListOf<String>()
        for (rule in config.rules) {
            if (rule.enabled && rule.effectiveConditions().isEmpty()) {
                warnings += "${rule.id} 已启用但没有完整条件，不会命中任何会话"
            }
        }
        return warnings
    }

    private suspend fun scanOnce(toolIds: Set<String>): ScanSnapshot {
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

    private fun resolveTools(args: ParsedArgs): Set<String> {
        val raw = args.values("tool")
        if (raw.isEmpty()) {
            val prefs = AppPreferences.read().defaultTools
            return prefs.ifEmpty { ALL_PROFILES.map { it.id }.toSet() }
        }
        val known = ALL_PROFILES.map { it.id }.toSet()
        val ids = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = ids.filter { it !in known }
        if (unknown.isNotEmpty()) throw CliUsageException("未知工具：${unknown.joinToString(",")}")
        return ids.toSet()
    }

    private fun resolveChatTools(args: ParsedArgs): Set<ChatTool> {
        val raw = args.values("tool")
        if (raw.isEmpty()) return ChatTool.entries.toSet()
        val ids = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val tools = ids.map { id ->
            ChatTool.entries.firstOrNull { it.id == id }
                ?: throw CliUsageException("未知工具：$id")
        }
        return tools.toSet()
    }

    private fun parseRisk(args: ParsedArgs): Risk? = when (val raw = args.value("risk")) {
        null, "all" -> null
        "safe" -> Risk.SAFE
        "optional" -> Risk.OPTIONAL
        else -> throw CliUsageException("--risk 只能是 safe / optional / all")
    }

    private fun parseSessionRef(raw: String): Pair<ChatTool, String> {
        val split = raw.indexOf(':')
        if (split <= 0 || split == raw.lastIndex) {
            throw CliUsageException("--id 格式为 tool:session，例如 cursor:abc")
        }
        val toolId = raw.substring(0, split)
        val sessionId = raw.substring(split + 1)
        val tool = ChatTool.entries.firstOrNull { it.id == toolId }
            ?: throw CliUsageException("未知工具：$toolId")
        return tool to sessionId
    }

    private fun printJson(value: Any?) {
        println(MiniJson.stringify(value))
    }

    private fun printError(message: String) {
        System.err.println(message)
        printJson(mapOf("ok" to false, "error" to message))
    }
}

private data class ScanSnapshot(
    val spaces: List<ToolSpaceResult>,
    val results: List<ScanResult>,
)

