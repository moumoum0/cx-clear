package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.ScanResult
import dev.cxclear.model.ToolProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

/**
 * 递归统计大小与文件数。软链接不跟随，避免重复计算或走出目标范围。
 *
 * [onProgress] 每处理一个条目回调一次增量（字节, 文件数），供调用方实时累计
 * 「正在遍历中」的进度；默认空实现，一次性取最终值的调用（如 Cleaner）无额外开销。
 */
private inline fun measure(
    path: Path,
    onProgress: (deltaBytes: Long, deltaCount: Int) -> Unit = { _, _ -> },
): Pair<Long, Int> {
    if (Files.isSymbolicLink(path)) {
        onProgress(0L, 1); return 0L to 1
    }
    if (path.isRegularFile()) {
        val b = runCatching { path.fileSize() }.getOrDefault(0L)
        onProgress(b, 1); return b to 1
    }
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
                Files.isSymbolicLink(e) -> {
                    count++; onProgress(0L, 1)
                }
                e.isDirectory() -> stack.addLast(e)
                else -> {
                    val b = runCatching { e.fileSize() }.getOrDefault(0L)
                    bytes += b; count++; onProgress(b, 1)
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
 * 单个 target 遍历过程中的实时累计。worker 边遍历边把增量累到这里，
 * 定时器随时读出「当前扫到多少」——包括仍在遍历、尚未收尾的项。
 */
private class TargetProgress(
    val toolId: String,
    val targetId: String,
) {
    val bytes = AtomicLong(0L)
    val files = AtomicInteger(0)
    val exists = AtomicBoolean(false)

    fun snapshot(): ScanResult = ScanResult(
        toolId = toolId,
        targetId = targetId,
        bytes = bytes.get(),
        fileCount = files.get(),
        exists = exists.get(),
    )
}

/**
 * 扫描一个 target，遍历途中把增量实时写进 [progress]。逻辑与 [scanResolved] 等价，
 * 区别只是「边扫边报」而非「扫完一次性返回」，因此不复用后者，避免给 Cleaner 引入回调开销。
 */
private fun scanWithProgress(base: Path, target: CleanTarget, progress: TargetProgress) {
    val paths = resolveTarget(base, target).paths
    if (paths.isNotEmpty()) progress.exists.set(true)
    for (p in paths) {
        measure(p) { deltaBytes, deltaCount ->
            if (deltaBytes != 0L) progress.bytes.addAndGet(deltaBytes)
            if (deltaCount != 0) progress.files.addAndGet(deltaCount)
        }
    }
}

/**
 * 扫描过程中的增量事件。
 *
 * 扫描以事件流而不是一次性返回值上报，是为了让 UI 能反映真实进度：
 * 每测完一项就能立刻更新，而不是扫完才一次性刷新。
 */
sealed interface ScanEvent {
    /** 本次扫描的工作项总数（可清理项 + 工具目录），用于算进度分母。 */
    data class Started(val total: Int) : ScanEvent

    /**
     * 到目前为止已测完的全部可清理项快照。
     *
     * 不再每测完一项推一次，而是由定时器按固定节拍推整份快照：worker 只管写结果，
     * 节流集中在一处，UI 拿到的是稳定节奏的批量更新，而不是随磁盘忽快忽慢的抖动。
     */
    data class TargetsScanned(val results: List<ScanResult>) : ScanEvent

    /** 单个工具目录的完整占用测量完成。 */
    data class SpaceScanned(val space: ToolSpaceResult) : ScanEvent
}

/**
 * 扫描所有 profile，逐项上报。纯磁盘 IO，每项各自跑在 IO 调度器上。
 *
 * 工具目录总占用和可清理项分别测量，口径互相独立：总占用代表应用真实体积，
 * 可清理项只代表其中能删的部分，两者相减就是必须保留的数据。
 * 工具未安装（baseDir 为 null）时上报全 0 且 exists=false，由 UI 决定是否显示。
 *
 * 分两个阶段：先测完总占用，再扫可清理项。总占用是分母，先定下来后续每测出一项
 * 都只是在已知总量里重新划分，占比不会被整体重算；若与可清理项并发上报，
 * 总占用（要遍历整个目录，最慢）最后才到，分母会突变一次。
 *
 * 阶段二不按「每项测完推一次」上报，而是让 worker 边遍历边把增量累进各自的
 * [TargetProgress]，另一个协程每 [SNAPSHOT_INTERVAL_MS] 读出全部 target 的当前累计
 * 推一份快照——包括仍在遍历、尚未收尾的项，所以柱子是随扫描过程平滑长起来的，
 * 而不是一项测完才跳一格。全部 worker 完成后立即补推一份收尾全量。
 */
private const val SNAPSHOT_INTERVAL_MS = 120L

fun scanStream(profiles: List<ToolProfile>): Flow<ScanEvent> = channelFlow {
    val bases = profiles.map { it to it.baseDir() }
    send(ScanEvent.Started(profiles.sumOf { it.targets.size } + profiles.size))

    bases.map { (profile, base) ->
        async(Dispatchers.IO) {
            if (base == null) {
                ToolSpaceResult(profile.id, 0L, 0)
            } else {
                val (bytes, files) = measure(base)
                ToolSpaceResult(profile.id, bytes, files)
            }
        }
    }.awaitAll().forEach { send(ScanEvent.SpaceScanned(it)) }

    // 每个 target 一份实时累计。worker 只往里写增量，定时器只读，互不阻塞。
    val progresses = bases.flatMap { (profile, _) ->
        profile.targets.map { TargetProgress(profile.id, it.id) }
    }
    val progressById = progresses.associateBy { it.targetId }

    val workers = buildList {
        for ((profile, base) in bases) {
            for (target in profile.targets) {
                val progress = progressById.getValue(target.id)
                add(
                    launch(Dispatchers.IO) {
                        if (base != null) scanWithProgress(base, target, progress)
                    },
                )
            }
        }
    }

    val allDone = CompletableDeferred<Unit>()
    launch {
        workers.forEach { it.join() }
        allDone.complete(Unit)
    }

    // 定时读全部 target 的实时累计并推快照。无条件推：遍历中的项每一拍都在长，
    // 快照内容基本每次都不同，不需要「变了才推」的判断。
    while (!allDone.isCompleted) {
        delay(SNAPSHOT_INTERVAL_MS)
        send(ScanEvent.TargetsScanned(progresses.map { it.snapshot() }))
    }
    // 收尾快照：保证最后一拍之后完成的增量也被 UI 收到。
    send(ScanEvent.TargetsScanned(progresses.map { it.snapshot() }))
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
