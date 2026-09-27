package dev.cxclear.tools.codex

import dev.cxclear.storage.homeDir
import dev.cxclear.storage.userCacheDir
import dev.cxclear.tools.directMatches
import dev.cxclear.tools.homeSubdir
import java.nio.file.Files
import java.nio.file.Path

internal fun codexHome(): Path? = homeSubdir(".codex")

/** `~/.cache/codex-runtimes`：primary-runtime 的 python/node/native 依赖，体积通常远大于 `~/.codex`。 */
internal fun codexRuntimesCache(): Path? =
    userCacheDir()?.resolve("codex-runtimes")?.takeIf { Files.isDirectory(it) }

internal fun codexSessionsRoot(): Path? =
    homeDir()?.resolve(".codex")?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun codexIndexFile(): Path? =
    homeDir()?.resolve(".codex")?.resolve("session_index.jsonl")?.takeIf { Files.isRegularFile(it) }

internal fun codexProtectedPaths(): List<Path> {
    val root = codexHome() ?: return emptyList()
    val fixed = listOf(
        "sqlite",
        "auth.json",
        "config.toml",
        "AGENTS.md",
        "installation_id",
        "session_index.jsonl",
        ".codex-global-state.json",
        ".codex-global-state.json.bak",
        ".sandbox-secrets",
        "memories",
        "rules",
        "skills",
    ).map(root::resolve)
    return fixed + directMatches(root, "state_*.sqlite*", "goals_*.sqlite*", "memories_*.sqlite*")
}
