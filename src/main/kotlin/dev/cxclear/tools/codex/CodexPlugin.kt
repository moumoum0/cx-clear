package dev.cxclear.tools.codex

import dev.cxclear.model.ChatTool
import dev.cxclear.resources.Res
import dev.cxclear.resources.codex
import dev.cxclear.tools.ToolPlugin

internal val CodexPlugin = ToolPlugin(
    profile = CodexProfile,
    icon = Res.drawable.codex,
    chat = ChatTool(id = CodexProfile.id, displayName = CodexProfile.name),
    scan = ::scanCodexSessions,
    load = { loadCodexMessages(it.mainFile) },
)
