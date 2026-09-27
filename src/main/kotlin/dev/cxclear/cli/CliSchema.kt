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
        "commands" to listOf(
            "find chats",
            "find files",
            "delete chats",
            "delete files",
            "scan",
            "clean",
            "status",
            "history",
            "rules get",
            "rules validate",
            "rules put",
            "schema",
        ),
    )

    fun helpPayload(): Map<String, Any?> = mapOf(
        "ok" to true,
        "usage" to listOf(
            "cxclear find chats [筛选条件]",
            "cxclear find files [筛选条件]",
            "cxclear delete chats [筛选条件] [--preview|--yes]",
            "cxclear delete files [筛选条件] [--preview|--yes]",
            "",
            "cxclear scan [--tool id,id] [--safe-only]",
            "cxclear clean [--tool id,id] [--targets id,id] [--safe-only] [--preview|--yes]",
            "",
            "cxclear status [--tool id,id]",
            "cxclear history [--limit n]",
            "",
            "cxclear rules get",
            "cxclear rules validate [--file path]",
            "cxclear rules put [--file path] [--preview|--yes]",
            "",
            "cxclear schema",
        ),
        "common_filters" to listOf(
            "--tool <id>             指定工具（逗号分隔，如 cursor,codex）",
            "--type <type>           文件类型（如 cache, logs, downloads）",
            "--older-than <duration> 时间范围（如 30d, 7h, 30m）",
            "--newer-than <duration> 相反的时间范围",
            "--size-gt <size>        大于指定大小（如 100MB, 1GB）",
            "--size-lt <size>        小于指定大小",
            "--keep-recent <n>       保留最近 N 条（会话专用）",
            "--keep-days <n>         保留 N 天内的（会话专用）",
            "--preview               预览不执行（delete 专用）",
            "--yes                   跳过确认直接执行",
            "--json                  JSON 输出",
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
            "然后手动编辑配置文件，或让 AI 助手生成策略 JSON",
        ),
        "notes" to listOf(
            "无 --yes 或带 --preview 只预览，不删文件、不写规则",
            "stdout 为 JSON，人类可读的消息走 stderr",
            "find 输出包含列表和统计（总数、总占用）",
        ),
    )
}
