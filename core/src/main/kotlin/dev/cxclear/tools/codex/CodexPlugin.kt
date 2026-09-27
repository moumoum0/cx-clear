package dev.cxclear.tools.codex

import dev.cxclear.model.ChatTool
import dev.cxclear.tools.ToolPlugin

internal val CodexPlugin = ToolPlugin(
    profile = CodexProfile,
    iconName = "codex",
    chat = ChatTool(id = CodexProfile.id, displayName = CodexProfile.name),
    scan = ::scanCodexSessions,
    load = { loadCodexMessages(it.mainFile) },
)
