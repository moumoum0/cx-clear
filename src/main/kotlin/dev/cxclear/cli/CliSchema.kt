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

    fun helpPayload(): Map<String, Any?> = mapOf(
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
}
