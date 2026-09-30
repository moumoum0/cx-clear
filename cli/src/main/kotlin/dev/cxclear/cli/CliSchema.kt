package dev.cxclear.cli

import dev.cxclear.AppMeta
import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ConditionValueKind
import dev.cxclear.tools.chatTools
import dev.cxclear.model.Risk
import dev.cxclear.tools.ALL_PROFILES

/**
 * CLI 的自描述数据：`schema` 与 `help` 两个命令的 JSON 负载。
 *
 * 坑：这里的 condition_types 表、以及 50 条规则 / 20 个条件的上限，与
 * chats/RetentionStore 的落盘格式、chats/RetentionJson、chats/RetentionAiPrompt
 * 的提示词正文是几份平行副本。改落盘格式必须同时改这几处，编译器不会拦。
 */
internal object CliSchema {
    private fun conditionLabel(type: ChatConditionType) = when (type) {
        ChatConditionType.UPDATED_BEFORE_DAYS -> "not updated for more than"
        ChatConditionType.UPDATED_WITHIN_DAYS -> "updated within"
        ChatConditionType.SIZE_LARGER_MB -> "larger than"
        ChatConditionType.SIZE_SMALLER_MB -> "smaller than"
        ChatConditionType.TOOL_IS -> "tool is"
        ChatConditionType.PROJECT_CONTAINS -> "project contains"
        ChatConditionType.TITLE_CONTAINS -> "title contains"
    }

    private fun conditionUnit(kind: ConditionValueKind) = when (kind) {
        ConditionValueKind.DAYS -> "days"
        ConditionValueKind.MEGABYTES -> "MB"
        ConditionValueKind.TOOL,
        ConditionValueKind.TEXT -> ""
    }

    fun schemaPayload(): Map<String, Any?> = mapOf(
        "name" to AppMeta.NAME,
        "version" to AppMeta.VERSION,
        "exit_codes" to mapOf(
            "0" to "success",
            "1" to "execution failed",
            "2" to "target tool is still running; nothing was deleted",
            "3" to "invalid arguments or rule validation failed",
        ),
        "tools" to ALL_PROFILES.map { it.id },
        "chat_tools" to chatTools().map { it.id },
        "risks" to Risk.entries.map { it.name.lowercase() },
        "condition_types" to ChatConditionType.entries.map {
            mapOf(
                "id" to it.id,
                "label" to conditionLabel(it),
                "kind" to it.kind.name.lowercase(),
                "unit" to conditionUnit(it.kind),
            )
        },
        "commands" to ALL_COMMANDS.map { it.name.joinToString(" ") },
        "command_options" to ALL_COMMANDS.associate { command ->
            command.name.joinToString(" ") to mapOf(
                "flags" to command.flags.sorted(),
                "switches" to command.switches.sorted(),
            )
        },
    )

    fun helpPayload(): Map<String, Any?> = mapOf(
        "ok" to true,
        "usage" to ALL_COMMANDS.map { "cxclear ${it.name.joinToString(" ")} - ${it.description}" },
        "common_filters" to listOf(
            "--tool <id>             tool ids, comma-separated (cursor,codex)",
            "--type <type>           file type substring (cache, logs, downloads)",
            "--older-than <duration> session update time or file mtime (30d, 7h, 30m)",
            "--newer-than <duration> the opposite time window",
            "--size-gt <size>        session or target total size greater than (100MB, 1GB)",
            "--size-lt <size>        smaller than the given size",
            "--keep-recent <n>       keep the N most recently updated chats",
            "--keep-days <n>         keep chats updated within N days",
            "--preview               preview only (delete / clean)",
            "--yes                   delete; omitted means preview only",
            "--json                  accepted for compatibility; every command prints JSON",
            "--safe-only             include only SAFE clean targets",
        ),
        "examples" to listOf(
            "# find Cursor chats older than 30 days",
            "cxclear find chats --tool cursor --older-than 30d",
            "",
            "# delete chats older than 90 days, keeping the 10 newest",
            "cxclear delete chats --older-than 90d --keep-recent 10 --preview",
            "",
            "# find cache files",
            "cxclear find files --type cache",
            "",
            "# delete download caches larger than 1GB",
            "cxclear delete files --type downloads --size-gt 1GB --yes",
            "",
            "# scan and clean safe items",
            "cxclear scan --safe-only",
            "cxclear clean --safe-only --yes",
            "",
            "# status and history",
            "cxclear status",
            "cxclear history --limit 20",
        ),
        "retention_policy" to listOf(
            "Auto-clean policy file: %USERPROFILE%\\.cxclear\\chat-retention.txt",
            "Use delete chats filters to test what a policy would match",
            "Edit the file with the prompt from Settings; the CLI has no rule-write command",
        ),
        "notes" to listOf(
            "delete and clean preview only unless --yes is set; --preview never deletes",
            "stdout is JSON; human-readable messages go to stderr",
            "find output includes the list plus count and total bytes",
            "file time filters delete matching files only and keep directories and links; size is the remaining target total",
            "keep-recent drops the N newest matches; if fewer than N match, nothing is deleted",
        ),
    )
}
