package dev.cxclear.cli

import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionParseResult
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.effectiveConditions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 自动清理策略：--rules / --rules-file / --rule 读取、rules validate 输入、策略告警说明 */

internal fun readInputText(file: String?): String {
    val text = if (file != null && file != "-") {
        val path = Path.of(file)
        if (!Files.isRegularFile(path)) throw CliUsageException("file not found: $file")
        Files.readString(path)
    } else {
        System.`in`.readBytes().toString(StandardCharsets.UTF_8)
    }
    // Windows 上 PowerShell/记事本写的文件常带 UTF-8 BOM，剥掉再解析
    return text.removePrefix("\uFEFF")
}

internal fun readConfigJson(args: ParsedArgs): RetentionParseResult {
    val text = readInputText(args.value("file"))
    if (text.isBlank()) return RetentionParseResult.Fail(listOf("rule JSON is empty"))
    return RetentionJson.parse(text)
}

internal fun ruleSelection(args: ParsedArgs): List<String> = args.csv("rule")

// --rules 读已保存策略，--rules-file 读候选 JSON（"-" 走 stdin）；只算 enabled 的规则，与自动清理一致
// --rule <id> 用于预览单条规则：挑中的规则强制视为启用，好让还没开启的新规则也能试跑
internal fun loadRulesConfig(args: ParsedArgs): RetentionConfig? {
    val file = args.value("rules-file")
    val ids = ruleSelection(args)
    if (file != null && args.rules) {
        throw CliUsageException("--rules and --rules-file cannot be combined")
    }
    val config = when {
        file != null -> when (val parsed = RetentionJson.parse(readInputText(file))) {
            is RetentionParseResult.Ok -> parsed.config
            is RetentionParseResult.Fail ->
                throw CliUsageException("invalid rules file: ${parsed.errors.joinToString("; ")}")
        }
        args.rules || ids.isNotEmpty() -> RetentionStore.read()
        else -> return null
    }
    if (ids.isEmpty()) return config
    val known = config.rules.map { it.id }.toSet()
    val unknown = ids.filter { it !in known }
    if (unknown.isNotEmpty()) throw CliUsageException("unknown rule: ${unknown.joinToString(",")}")
    return RetentionConfig(config.rules.filter { it.id in ids }.map { it.copy(enabled = true) })
}

internal fun rulesSource(args: ParsedArgs): String = when {
    args.rules -> "saved"
    args.value("rules-file") == "-" -> "stdin"
    else -> "file:${args.value("rules-file")}"
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

// --rules 输出的策略说明；禁用规则不进匹配但要列出来，否则 matched:0 没法解释
internal fun rulesInfo(config: RetentionConfig, source: String, selected: List<String> = emptyList()): Map<String, Any?> {
    val warnings = ruleWarnings(config).toMutableList()
    if (config.rules.isEmpty()) warnings += "no rules configured"
    for (rule in config.rules) {
        if (!rule.enabled) warnings += "${rule.id} is disabled and matches nothing"
    }
    return buildMap {
        put("source", source)
        if (selected.isNotEmpty()) put("selected", selected)
        put("rules_total", config.rules.size)
        put("rules_enabled", config.rules.count { it.enabled })
        put("warnings", warnings)
    }
}
