package dev.cxclear.cli

import dev.cxclear.AppMeta
import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ChatSessionSummary
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
import dev.cxclear.scan.formatBytes
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
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
            printJson(helpPayload())
            EXIT_OK
        }
        listOf("schema") -> {
            printJson(schemaPayload())
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
            val profile = ALL_PROFILES.first { it.id == result.toolId }
            val target = profile.targets.first { it.id == result.targetId }
            when (risk) {
                null -> target.defaultSelected
                else -> target.risk == risk
            }
        }
        val requests = selected.mapNotNull { result ->
            val profile = ALL_PROFILES.first { it.id == result.toolId }
            val target = profile.targets.first { it.id == result.targetId }
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

    private fun spaceJson(space: ToolSpaceResult) = mapOf(
        "tool" to space.toolId,
        "bytes" to space.bytes,
        "files" to space.fileCount,
        "bytes_label" to formatBytes(space.bytes),
    )

    private fun targetJson(result: ScanResult): Map<String, Any?> {
        val profile = ALL_PROFILES.first { it.id == result.toolId }
        val target = profile.targets.first { it.id == result.targetId }
        return mapOf(
            "tool" to result.toolId,
            "target_id" to result.targetId,
            "label" to target.label,
            "risk" to target.risk.name.lowercase(),
            "default_selected" to target.defaultSelected,
            "bytes" to result.bytes,
            "files" to result.fileCount,
            "bytes_label" to formatBytes(result.bytes),
        )
    }

    private fun sessionJson(session: ChatSessionSummary) = mapOf(
        "id" to "${session.tool.id}:${session.id}",
        "tool" to session.tool.id,
        "session_id" to session.id,
        "title" to session.title,
        "project" to session.project,
        "updated_millis" to session.updatedMillis,
        "bytes" to session.sizeBytes,
        "bytes_label" to formatBytes(session.sizeBytes),
    )

    private fun schemaPayload(): Map<String, Any?> = mapOf(
        "name" to AppMeta.NAME,
        "version" to AppMeta.VERSION,
        "exit_codes" to mapOf(
            "0" to "成功",
            "1" to "执行失败",
            "2" to "目标工具仍在运行，未删除",
            "3" to "参数或规则校验失败",
        ),
        "tools" to ALL_PROFILES.map { it.id },
        "chat_tools" to ChatTool.entries.map { it.id },
        "risks" to Risk.entries.map { it.name.lowercase() },
        "condition_types" to ChatConditionType.entries.map {
            mapOf(
                "id" to it.id,
                "label" to it.label,
                "kind" to it.kind.name.lowercase(),
                "unit" to it.kind.unit,
            )
        },
        "commands" to listOf(
            "scan",
            "clean",
            "chats list",
            "chats preview",
            "chats delete",
            "rules get",
            "rules validate",
            "rules put",
            "schema",
        ),
    )

    private fun helpPayload(): Map<String, Any?> = mapOf(
        "ok" to true,
        "usage" to listOf(
            "cxclear scan [--tool id,id]",
            "cxclear clean [--tool id,id] [--risk safe|optional|all] [--yes]",
            "cxclear chats list [--tool id,id]",
            "cxclear chats preview [--tool id,id]",
            "cxclear chats delete --id tool:session [--yes]",
            "cxclear rules get",
            "cxclear rules validate [--file path]",
            "cxclear rules put [--file path] [--yes]",
            "cxclear schema",
        ),
        "notes" to listOf(
            "无 --yes 只预览，不删文件、不写规则",
            "stdout 为 JSON，人话走 stderr",
        ),
    )

    private fun printJson(value: Any?) {
        println(MiniJson.stringify(value))
    }

    private fun printError(message: String) {
        System.err.println(message)
        printJson(mapOf("ok" to false, "error" to message))
    }
}

internal class CliUsageException(message: String) : RuntimeException(message)

private data class ScanSnapshot(
    val spaces: List<ToolSpaceResult>,
    val results: List<ScanResult>,
)

internal data class ParsedArgs(
    val command: List<String>,
    val flags: Map<String, List<String>>,
    val switches: Set<String>,
) {
    val yes: Boolean get() = "yes" in switches || "y" in switches

    fun value(name: String): String? = flags[name]?.last()

    fun values(name: String): List<String> = flags[name].orEmpty()
}

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    if (args.isEmpty()) return null
    val command = mutableListOf<String>()
    val flags = linkedMapOf<String, MutableList<String>>()
    val switches = linkedSetOf<String>()
    var i = 0
    fun takeValue(flag: String): String {
        if (i >= args.size) throw CliUsageException("缺少 $flag 的值")
        return args[i++]
    }
    while (i < args.size) {
        val token = args[i++]
        when {
            token == "--" -> {
                command += args.drop(i)
                break
            }
            token == "-h" || token == "--help" -> return ParsedArgs(listOf("help"), emptyMap(), emptySet())
            token.startsWith("--") -> {
                val eq = token.indexOf('=')
                val name: String
                val value: String?
                if (eq > 2) {
                    name = token.substring(2, eq)
                    value = token.substring(eq + 1)
                } else {
                    name = token.substring(2)
                    value = null
                }
                if (name.isEmpty()) throw CliUsageException("空的选项")
                when (name) {
                    "yes", "y" -> switches += name
                    "tool", "risk", "id", "file" -> {
                        val v = value ?: takeValue("--$name")
                        flags.getOrPut(name) { mutableListOf() }.add(v)
                    }
                    else -> throw CliUsageException("未知选项：--$name")
                }
            }
            token.startsWith("-") && token != "-" -> throw CliUsageException("未知选项：$token")
            else -> command += token
        }
    }
    if (command.isEmpty()) return ParsedArgs(listOf("help"), flags, switches)
    return ParsedArgs(command, flags, switches)
}
