package dev.cxclear.cli

import dev.cxclear.AppMeta
import dev.cxclear.chats.ChatConditionType
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
    fun schemaPayload(): Map<String, Any?> = mapOf(
        "name" to AppMeta.NAME,
        "version" to AppMeta.VERSION,
        "exit_codes" to mapOf(
            "0" to "成功",
            "1" to "执行失败",
            "2" to "目标工具仍在运行，未删除",
            "3" to "参数或规则校验失败",
        ),
        "tools" to ALL_PROFILES.map { it.id },
        "chat_tools" to chatTools().map { it.id },
        "risks" to Risk.entries.map { it.name.lowercase() },
        "condition_types" to ChatConditionType.entries.map {
            mapOf(
                "id" to it.id,
                "label" to it.label,
                "kind" to it.kind.name.lowercase(),
                "unit" to it.kind.unit,
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
            "--tool <id>             指定工具（逗号分隔，如 cursor,codex）",
            "--type <type>           文件类型（如 cache, logs, downloads）",
            "--older-than <duration> 会话更新时间或文件修改时间（如 30d, 7h, 30m）",
            "--newer-than <duration> 相反的时间范围",
            "--size-gt <size>        会话或清理项总大小大于指定值（如 100MB, 1GB）",
            "--size-lt <size>        小于指定大小",
            "--keep-recent <n>       保留最近 N 条（会话专用）",
            "--keep-days <n>         保留 N 天内的（会话专用）",
            "--preview               预览不执行（delete / clean）",
            "--yes                   执行删除；省略时只预览",
            "--json                  兼容选项，所有命令始终输出 JSON",
            "--safe-only             只包含 SAFE 风险的清理项",
        ),
        "examples" to listOf(
            "# 查找 Cursor 30 天前的对话",
            "cxclear find chats --tool cursor --older-than 30d",
            "",
            "# 删除 90 天前的对话，但保留最近 10 条",
            "cxclear delete chats --older-than 90d --keep-recent 10 --preview",
            "",
            "# 查找缓存文件",
            "cxclear find files --type cache",
            "",
            "# 删除大于 1GB 的下载缓存",
            "cxclear delete files --type downloads --size-gt 1GB --yes",
            "",
            "# 扫描并清理安全项",
            "cxclear scan --safe-only",
            "cxclear clean --safe-only --yes",
            "",
            "# 查看状态和历史",
            "cxclear status",
            "cxclear history --limit 20",
        ),
        "retention_policy" to listOf(
            "自动清理策略配置文件：%USERPROFILE%\\.cxclear\\chat-retention.txt",
            "可以用 delete chats 的筛选条件测试策略效果",
            "按设置页提供的规则提示词直接编辑配置文件，CLI 不提供规则写入命令",
        ),
        "notes" to listOf(
            "删除命令无 --yes 或带 --preview 只预览，不删文件",
            "stdout 为 JSON，人类可读的消息走 stderr",
            "find 输出包含列表和统计（总数、总占用）",
            "文件时间筛选只删除命中的文件，保留目录和链接；大小按时间筛选后的清理项合计计算",
            "keep-recent 从匹配结果中保留最近 N 条，数量不足 N 时全部保留",
        ),
    )
}
