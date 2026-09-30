package dev.cxclear.cli

import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionParseResult
import dev.cxclear.chats.effectiveConditions
import dev.cxclear.model.ChatTool
import dev.cxclear.model.ScanResult
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.tools.ALL_PROFILES
import dev.cxclear.tools.chatToolById
import dev.cxclear.tools.chatTools
import dev.cxclear.util.MiniJson
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
        return try {
            val parsed = parseArgs(args) ?: ParsedArgs(listOf("help"), emptyMap(), emptySet())
            dispatch(parsed)
        } catch (e: CliUsageException) {
            printError(e.message ?: "invalid arguments")
            EXIT_USAGE
        } catch (e: Exception) {
            printError(e.message ?: e::class.simpleName ?: "unknown error")
            EXIT_FAIL
        }
    }

    fun runAndExit(args: Array<String>): Nothing {
        exitProcess(run(args))
    }

    private fun dispatch(args: ParsedArgs): Int {
        val command = ALL_COMMANDS.firstOrNull { it.name == args.command }
        return if (command != null) {
            command.validate(args)
            command.execute(args)
        } else {
            printError("unknown command: ${args.command.joinToString(" ").ifBlank { "(empty)" }}")
            EXIT_USAGE
        }
    }

    fun printJson(value: Any?) {
        println(MiniJson.stringify(value))
    }

    fun printError(message: String) {
        System.err.println(message)
        printJson(mapOf("ok" to false, "error" to message))
    }
}

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
    val raw = args.values("tool")
    if (raw.isEmpty()) {
        val prefs = AppPreferences.read().defaultTools
        return prefs.ifEmpty { ALL_PROFILES.map { it.id }.toSet() }
    }
    val known = ALL_PROFILES.map { it.id }.toSet()
    val ids = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = ids.filter { it !in known }
    if (unknown.isNotEmpty()) throw CliUsageException("unknown tool: ${unknown.joinToString(",")}")
    return ids.toSet()
}

internal fun resolveChatTools(args: ParsedArgs): Set<ChatTool> {
    val raw = args.values("tool")
    if (raw.isEmpty()) return chatTools().toSet()
    val ids = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    val tools = ids.map { id ->
        chatToolById(id) ?: throw CliUsageException("unknown tool: $id")
    }
    return tools.toSet()
}

internal fun readConfigJson(args: ParsedArgs): RetentionParseResult {
    val text = readInputText(args)
    if (text.isBlank()) return RetentionParseResult.Fail(listOf("rule JSON is empty"))
    return RetentionJson.parse(text)
}

internal fun readInputText(args: ParsedArgs): String {
    val file = args.value("file")
    return if (file != null && file != "-") {
        val path = Path.of(file)
        if (!Files.isRegularFile(path)) throw CliUsageException("file not found: $file")
        Files.readString(path)
    } else {
        System.`in`.readBytes().toString(StandardCharsets.UTF_8)
    }
}

internal fun ruleWarnings(config: RetentionConfig): List<String> {
    val warnings = mutableListOf<String>()
    for (rule in config.rules) {
        if (rule.enabled && rule.effectiveConditions().isEmpty()) {
            warnings += "${rule.id} is enabled but has no complete condition, so it matches nothing"
        }
    }
    return warnings
}
