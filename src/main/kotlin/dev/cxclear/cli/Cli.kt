package dev.cxclear.cli

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
import dev.cxclear.model.ChatTool
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.tools.ALL_PROFILES
import dev.cxclear.tools.chatToolById
import dev.cxclear.tools.chatTools
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
import dev.cxclear.util.MiniJson
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** 给 AI 调的无头入口。统一查找-删除模型，stdout 只出 JSON。 */
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
        listOf("help") -> cmdHelp()
        listOf("schema") -> cmdSchema()
        listOf("find", "chats") -> cmdFindChats(args)
        listOf("find", "files") -> cmdFindFiles(args)
        listOf("delete", "chats") -> cmdDeleteChats(args)
        listOf("delete", "files") -> cmdDeleteFiles(args)
        listOf("scan") -> cmdScan(args)
        listOf("clean") -> cmdClean(args)
        listOf("status") -> cmdStatus(args)
        listOf("history") -> cmdHistory(args)
        listOf("rules", "get") -> cmdRulesGet()
        listOf("rules", "validate") -> cmdRulesValidate(args)
        listOf("rules", "put") -> cmdRulesPut(args)
        else -> {
            printError("未知命令：${args.command.joinToString(" ").ifBlank { "(空)"} }")
            EXIT_USAGE
        }
    }

    // === 核心：find / delete ===

    private fun cmdFindChats(args: ParsedArgs): Int {
        val tools = resolveChatTools(args)
        val sessions = scanAllChatSessions(tools)
        val filters = parseChatFilters(args)
        val filtered = filters.apply(sessions, System.currentTimeMillis())
        
        printJson(
            mapOf(
                "ok" to true,
                "command" to "find chats",
                "total" to filtered.size,
                "bytes" to filtered.sumOf { it.sizeBytes },
                "sessions" to filtered.map { sessionJson(it) },
            )
        )
        return EXIT_OK
    }

    private fun cmdFindFiles(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val typeFilter = args.value("type")
        val sizeGt = args.value("size-gt")?.let { parseSize(it) }
        val sizeLt = args.value("size-lt")?.let { parseSize(it) }
        
        val snapshot = runBlocking { scanOnce(tools) }
        var results = snapshot.results.filter { it.exists && it.bytes > 0L }
        
        // 按 type 过滤（匹配清理项 id 关键字）
        if (typeFilter != null) {
            results = results.filter { it.targetId.contains(typeFilter, ignoreCase = true) }
        }
        
        // 按大小过滤
        if (sizeGt != null) {
            results = results.filter { it.bytes > sizeGt }
        }
        if (sizeLt != null) {
            results = results.filter { it.bytes < sizeLt }
        }
        
        printJson(
            mapOf(
                "ok" to true,
                "command" to "find files",
                "total" to results.size,
                "bytes" to results.sumOf { it.bytes },
                "targets" to results.map { targetJson(it) },
            )
        )
        return EXIT_OK
    }

    private fun cmdDeleteChats(args: ParsedArgs): Int {
        val tools = resolveChatTools(args)
        val sessions = scanAllChatSessions(tools)
        val filters = parseChatFilters(args)
        val filtered = filters.apply(sessions, System.currentTimeMillis())
        
        if (filtered.isEmpty()) {
            printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete chats",
                    "preview" to true,
                    "matched" to 0,
                    "bytes" to 0L,
                )
            )
            return EXIT_OK
        }
        
        if (args.preview || !args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete chats",
                    "preview" to true,
                    "matched" to filtered.size,
                    "bytes" to filtered.sumOf { it.sizeBytes },
                    "sessions" to filtered.map { sessionJson(it) },
                )
            )
            return EXIT_OK
        }
        
        val result = runBlocking { deleteSessions(filtered) }
        val blocked = result.blockedTools.isNotEmpty()
        printJson(
            mapOf(
                "ok" to (!blocked && result.errors.isEmpty()),
                "command" to "delete chats",
                "preview" to false,
                "deleted" to result.deletedSessions,
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

    private fun cmdDeleteFiles(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val typeFilter = args.value("type")
        val sizeGt = args.value("size-gt")?.let { parseSize(it) }
        val sizeLt = args.value("size-lt")?.let { parseSize(it) }
        val targetIds = args.value("targets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        
        val snapshot = runBlocking { scanOnce(tools) }
        var results = snapshot.results.filter { it.exists && it.bytes > 0L && it.deletionPlan != null }
        
        // 按 targets 过滤（精确匹配）
        if (targetIds != null) {
            results = results.filter { it.targetId in targetIds }
        }
        
        // 按 type 过滤（关键字匹配）
        if (typeFilter != null) {
            results = results.filter { it.targetId.contains(typeFilter, ignoreCase = true) }
        }
        
        // 按大小过滤
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
            printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete files",
                    "preview" to true,
                    "matched" to 0,
                    "bytes" to 0L,
                )
            )
            return EXIT_OK
        }
        
        if (args.preview || !args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "command" to "delete files",
                    "preview" to true,
                    "matched" to results.size,
                    "bytes" to results.sumOf { it.bytes },
                    "targets" to results.map { targetJson(it) },
                )
            )
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
                        "command" to "delete files",
                        "preview" to false,
                        "blocked_tools" to blocked,
                        "error" to "检测到 ${blocked.joinToString("、")} 仍在运行",
                    )
                )
                return@runBlocking EXIT_BLOCKED
            }
            printJson(
                mapOf(
                    "ok" to errors.isEmpty(),
                    "command" to "delete files",
                    "preview" to false,
                    "freed_bytes" to freed,
                    "targets" to done,
                    "errors" to errors,
                )
            )
            if (errors.isEmpty()) EXIT_OK else EXIT_FAIL
        }
    }

    // === 独立命令：scan / clean（首页扫描） ===

    private fun cmdScan(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val snapshot = runBlocking { scanOnce(tools) }
        var results = snapshot.results.filter { it.exists && it.bytes > 0L }
        
        if (args.safeOnly) {
            results = results.filter {
                val (_, target) = profileAndTarget(it.toolId, it.targetId)
                target.risk == Risk.SAFE
            }
        }
        
        printJson(
            mapOf(
                "ok" to true,
                "command" to "scan",
                "tools" to tools.toList(),
                "spaces" to snapshot.spaces.map { spaceJson(it) },
                "targets" to results.map { targetJson(it) },
            )
        )
        return EXIT_OK
    }

    private fun cmdClean(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val targetIds = args.value("targets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val snapshot = runBlocking { scanOnce(tools) }
        
        val selected = snapshot.results.filter { result ->
            if (!result.exists || result.bytes <= 0L || result.deletionPlan == null) return@filter false
            val (_, target) = profileAndTarget(result.toolId, result.targetId)
            
            when {
                targetIds != null -> result.targetId in targetIds
                args.safeOnly -> target.risk == Risk.SAFE && target.defaultSelected
                else -> target.defaultSelected
            }
        }
        
        val requests = selected.mapNotNull { result ->
            val (profile, target) = profileAndTarget(result.toolId, result.targetId)
            val plan = result.deletionPlan ?: return@mapNotNull null
            CleanRequest(profile, target, plan)
        }
        
        val planned = selected.map { targetJson(it) }
        if (args.preview || !args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "command" to "clean",
                    "preview" to true,
                    "matched" to planned.size,
                    "bytes" to selected.sumOf { it.bytes },
                    "targets" to planned,
                )
            )
            return EXIT_OK
        }
        
        if (requests.isEmpty()) {
            printJson(mapOf("ok" to true, "command" to "clean", "preview" to false, "freed_bytes" to 0L, "targets" to emptyList<Any>()))
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
                        "command" to "clean",
                        "preview" to false,
                        "blocked_tools" to blocked,
                        "error" to "检测到 ${blocked.joinToString("、")} 仍在运行",
                    )
                )
                return@runBlocking EXIT_BLOCKED
            }
            printJson(
                mapOf(
                    "ok" to errors.isEmpty(),
                    "command" to "clean",
                    "preview" to false,
                    "freed_bytes" to freed,
                    "targets" to done,
                    "errors" to errors,
                )
            )
            if (errors.isEmpty()) EXIT_OK else EXIT_FAIL
        }
    }

    // === 状态与历史 ===

    private fun cmdStatus(args: ParsedArgs): Int {
        val tools = resolveTools(args)
        val snapshot = runBlocking { scanOnce(tools) }
        val chatTools = chatTools().filter { it.id in tools }
        val chatCounts = chatTools.associate { tool ->
            val sessions = scanAllChatSessions(setOf(tool))
            tool.id to sessions.size
        }
        
        printJson(
            mapOf(
                "ok" to true,
                "command" to "status",
                "spaces" to snapshot.spaces.map { space ->
                    mapOf(
                        "tool" to space.toolId,
                        "bytes" to space.bytes,
                        "files" to space.fileCount,
                        "bytes_label" to formatBytes(space.bytes),
                        "chats" to (chatCounts[space.toolId] ?: 0),
                    )
                },
            )
        )
        return EXIT_OK
    }

    private fun cmdHistory(args: ParsedArgs): Int {
        val limit = args.value("limit")?.toIntOrNull() ?: 10
        val records = CleanHistory.readAll().takeLast(limit)
        printJson(
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
        return EXIT_OK
    }

    // === 自动策略（保留旧命令，兼容性） ===

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
        if (args.preview || !args.yes) {
            printJson(
                mapOf(
                    "ok" to true,
                    "preview" to true,
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
                "preview" to false,
                "config" to RetentionJson.toMap(RetentionStore.read()),
                "warnings" to ruleWarnings(config),
            )
        )
        return EXIT_OK
    }

    // === Help / Schema ===

    private fun cmdHelp(): Int {
        printJson(CliSchema.helpPayload())
        return EXIT_OK
    }

    private fun cmdSchema(): Int {
        printJson(CliSchema.schemaPayload())
        return EXIT_OK
    }

    // === 工具函数 ===

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
        if (raw.isEmpty()) return chatTools().toSet()
        val ids = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val tools = ids.map { id ->
            chatToolById(id) ?: throw CliUsageException("未知工具：$id")
        }
        return tools.toSet()
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
