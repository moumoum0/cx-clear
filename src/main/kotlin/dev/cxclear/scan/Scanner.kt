package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.ScanResult
import dev.cxclear.model.ToolProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** 一个 target 实际会被删掉的东西。Scanner 与 Cleaner 共用同一套解析结果，避免两边规则不一致。 */
data class ResolvedTarget(
    val target: CleanTarget,
    /** 待删除的路径。DIRECTORY_CONTENTS 展开成一级子项，GLOB 展开成匹配到的文件。 */
    val paths: List<Path>,
)

/** 一个工具目录的完整占用，不区分是否允许清理。 */
data class ToolSpaceResult(
    val toolId: String,
    val bytes: Long,
    val fileCount: Int,
)

/**
 * 把 [CleanTarget] 解析成具体路径列表。不做任何删除。
 *
 * relPath 允许带一层子目录，最后一段可以是 glob（配合 GLOB / STALE_VERSIONS）。
 */
fun resolveTarget(baseDir: Path, target: CleanTarget): ResolvedTarget {
    val paths: List<Path> = when (target.kind) {
        MatchKind.DIRECTORY, MatchKind.FILE -> {
            val p = baseDir.resolve(target.relPath)
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) listOf(p) else emptyList()
        }

        MatchKind.DIRECTORY_CONTENTS -> {
            val dir = baseDir.resolve(target.relPath)
            // listDirectoryEntries 会带上点开头的条目 —— Codex 的大头（.plugin-appserver 等）
            // 正是点开头的，用 shell glob 会漏掉。
            if (dir.isDirectory()) runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())
            else emptyList()
        }

        MatchKind.GLOB, MatchKind.STALE_VERSIONS -> {
            val relative = target.relPath.replace('\\', '/')
            val slash = relative.lastIndexOf('/')
            val dir = if (slash < 0) baseDir else baseDir.resolve(relative.substring(0, slash))
            val pattern = if (slash < 0) relative else relative.substring(slash + 1)
            if (!dir.isDirectory()) {
                emptyList()
            } else {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                val matched = runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())
                    .filter { matcher.matches(it.fileName) }
                if (target.kind == MatchKind.STALE_VERSIONS) {
                    // 保留 mtime 最新的一份，它很可能是当前正在使用的版本。
                    val newest = matched.maxByOrNull { lastModifiedOrZero(it) }
                    matched.filter { it != newest }
                } else {
                    matched
                }
            }
        }
    }
    return ResolvedTarget(target, paths)
}

private fun lastModifiedOrZero(p: Path): Long =
    runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)

/** 递归统计大小与文件数。软链接不跟随，避免重复计算或走出目标范围。 */
private fun measure(path: Path): Pair<Long, Int> {
    if (Files.isSymbolicLink(path)) return 0L to 1
    if (path.isRegularFile()) return (runCatching { path.fileSize() }.getOrDefault(0L)) to 1
    if (!path.isDirectory()) return 0L to 0

    var bytes = 0L
    var count = 0
    val stack = ArrayDeque<Path>()
    stack.addLast(path)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val entries = runCatching { dir.listDirectoryEntries() }.getOrNull() ?: continue
        for (e in entries) {
            when {
                Files.isSymbolicLink(e) -> count++
                e.isDirectory() -> stack.addLast(e)
                else -> {
                    bytes += runCatching { e.fileSize() }.getOrDefault(0L)
                    count++
                }
            }
        }
    }
    return bytes to count
}

/** 扫描单个 target。已解析过路径的话直接复用，省一次目录遍历。 */
fun scanResolved(toolId: String, resolved: ResolvedTarget): ScanResult {
    var bytes = 0L
    var files = 0
    for (p in resolved.paths) {
        val (b, c) = measure(p)
        bytes += b
        files += c
    }
    return ScanResult(
        toolId = toolId,
        targetId = resolved.target.id,
        bytes = bytes,
        fileCount = files,
        exists = resolved.paths.isNotEmpty(),
    )
}

/**
 * 并行扫描所有 profile 的所有 target。纯磁盘 IO，交给 IO 调度器。
 * 工具未安装（baseDir 不存在）时返回全 0 且 exists=false 的结果，由 UI 决定是否显示。
 */
suspend fun scanAll(profiles: List<ToolProfile>): List<ScanResult> = withContext(Dispatchers.IO) {
    coroutineScope {
        profiles.flatMap { profile ->
            val base = profile.baseDir()
            profile.targets.map { target ->
                async {
                    if (base == null) {
                        ScanResult(profile.id, target.id, 0L, 0, exists = false)
                    } else {
                        scanResolved(profile.id, resolveTarget(base, target))
                    }
                }
            }
        }.awaitAll()
    }
}

/**
 * 统计每个工具目录的完整占用。这个结果用于空间柱，和“可清理项”扫描相互独立：
 * 柱子的总高度始终代表应用真实占用，而不是只代表垃圾大小。
 */
suspend fun scanToolSpaces(profiles: List<ToolProfile>): List<ToolSpaceResult> = withContext(Dispatchers.IO) {
    coroutineScope {
        profiles.map { profile ->
            async {
                val base = profile.baseDir()
                if (base == null) {
                    ToolSpaceResult(profile.id, 0L, 0)
                } else {
                    val (bytes, files) = measure(base)
                    ToolSpaceResult(profile.id, bytes, files)
                }
            }
        }.awaitAll()
    }
}

/** 人类可读的大小。Windows 资源管理器口径，1 KB = 1024 B。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return when {
        unit == 0 -> "${bytes} B"
        value >= 100 -> "${value.toInt()} ${units[unit]}"
        else -> String.format("%.1f %s", value, units[unit])
    }
}

/** 调试用：打印某个路径的名字，避免在日志里泄露完整路径。 */
internal fun Path.displayName(): String = name
