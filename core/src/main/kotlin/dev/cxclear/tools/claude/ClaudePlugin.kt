package dev.cxclear.tools.claude

import dev.cxclear.model.ChatTool
import dev.cxclear.model.NO_PROJECT_LABEL
import dev.cxclear.tools.ToolPlugin

internal val ClaudePlugin = ToolPlugin(
    profile = ClaudeCodeProfile,
    iconName = "claude",
    shortName = "Claude",
    chat = ChatTool(
        id = ClaudeCodeProfile.id,
        displayName = ClaudeCodeProfile.name,
        projectLabel = label@{ raw ->
            // 项目目录名把绝对路径整条编码进来（d--project-cxclear），只留最后一段。
            val text = raw?.takeIf { it.isNotBlank() } ?: return@label NO_PROJECT_LABEL
            text.trimEnd('-').substringAfterLast('-').ifBlank { text }
        },
    ),
    scan = ::scanClaudeSessions,
    load = { loadClaudeMessages(it.mainFile) },
)
