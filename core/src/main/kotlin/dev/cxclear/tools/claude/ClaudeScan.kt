package dev.cxclear.tools.claude

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.tools.firstLineSummary
import dev.cxclear.tools.listDir
import dev.cxclear.tools.snapshotTree
import dev.cxclear.tools.treeSize
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonStr
import dev.cxclear.model.PathSnapshot
import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal val CLAUDE_SKIP_DIRS = setOf("memory", "settings", "todos")

internal val UUID_REGEX = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

internal fun isUuidFileName(name: String): Boolean = name.endsWith(".jsonl") &&
    UUID_REGEX.matches(name.dropLast(6))

internal fun readClaudeTitle(file: Path): String? {
    var aiTitle: String? = null
    var lastPrompt: String? = null
    var firstUserText: String? = null

    runCatching {
        Files.newBufferedReader(file, Charsets.UTF_8).use { br ->
            for (line in br.lineSequence()) {
                val obj = MiniJson.parse(line) ?: continue
                when (obj.jsonStr("type")) {
                    "ai-title" -> {
                        obj.jsonStr("aiTitle")?.takeIf { it.isNotBlank() }?.let { aiTitle = it }
                    }
                    "last-prompt" -> {
                        obj.jsonStr("lastPrompt")?.let(::firstLineSummary)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { lastPrompt = it }
                    }
                    "user" -> {
                        if (firstUserText == null && obj.jsonBool("isMeta") != true) {
                            val content = (obj as? Map<String, Any?>)?.get("message")
                                ?.let { (it as? Map<String, Any?>)?.get("content") }
                            val text = when (content) {
                                is String -> content.trim()
                                is List<*> -> content.asSequence()
                                    .filterIsInstance<Map<String, Any?>>()
                                    .mapNotNull { it.jsonStr("text") }
                                    .firstOrNull()?.trim()
                                else -> null
                            }
                            firstUserText = text?.let(::firstLineSummary)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }
    }

    return aiTitle ?: lastPrompt ?: firstUserText
}

internal fun scanClaudeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val projectsRoot = claudeProjectsRoot() ?: return emptyList()
    val sessions = mutableListOf<ChatSessionSummary>()

    for (projectDir in listDir(projectsRoot)) {
        if (!Files.isDirectory(projectDir)) continue
        val projectName = projectDir.fileName.toString()
        if (projectName in CLAUDE_SKIP_DIRS) continue

        for (entry in listDir(projectDir)) {
            val name = entry.fileName.toString()
            if (!Files.isRegularFile(entry) || !isUuidFileName(name)) continue

            runCatching {
                val uuid = name.dropLast(6)
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val updatedMs = attrs.lastModifiedTime().toMillis()

                val siblingDir = projectDir.resolve(uuid)
                val hasSiblingDir = Files.isDirectory(siblingDir)

                val fileSize = attrs.size()
                val dirSize = if (hasSiblingDir) treeSize(siblingDir) else 0L

                val entries = mutableListOf<PathSnapshot>()
                readPathSnapshot(entry)?.let { entries += it }
                if (hasSiblingDir) entries += snapshotTree(siblingDir)

                val title = readClaudeTitle(entry) ?: uuid

                val summary = ChatSessionSummary(
                    tool = ClaudePlugin.chat,
                    id = uuid,
                    title = title,
                    project = projectName,
                    updatedMillis = updatedMs,
                    sizeBytes = fileSize + dirSize,
                    mainFile = entry,
                    rootDir = projectsRoot,
                    entries = entries,
                )
                sessions += summary
                onFound(summary)
            }
        }
    }

    return sessions.sortedByDescending { it.updatedMillis }
}
