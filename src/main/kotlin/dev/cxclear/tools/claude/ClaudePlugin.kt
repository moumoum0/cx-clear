package dev.cxclear.tools.claude

import dev.cxclear.model.ChatTool
import dev.cxclear.model.NO_PROJECT_LABEL
import dev.cxclear.resources.Res
import dev.cxclear.resources.claude
import dev.cxclear.tools.ToolPlugin

internal val ClaudePlugin = ToolPlugin(
    profile = ClaudeCodeProfile,
    icon = Res.drawable.claude,
    shortName = "Claude",
    chat = ChatTool(
        id = ClaudeCodeProfile.id,
        displayName = ClaudeCodeProfile.name,
        // 项目目录名把绝对路径整条编码进来（d--project-cxclear），只留最后一段。
        projectLabel = label@{ raw ->
            val text = raw?.takeIf { it.isNotBlank() } ?: return@label NO_PROJECT_LABEL
            text.trimEnd('-').substringAfterLast('-').ifBlank { text }
        },
    ),
    scan = ::scanClaudeSessions,
    load = { loadClaudeMessages(it.mainFile) },
)
