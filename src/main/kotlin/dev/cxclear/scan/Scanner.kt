package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.MatchKind
import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.ScanResult
import dev.cxclear.model.TargetEntryType
import dev.cxclear.model.TargetKey
import dev.cxclear.model.ToolProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/** 一个 target 实际会被删掉的东西。Scanner 与 Cleaner 共用同一套解析结果，避免两边规则不一致。 */
data class ResolvedTarget(
    val target: CleanTarget,
    /** 本项允许删除的根目录。Cleaner 会在实际删除前再次用它校验路径边界。 */
    val baseDir: Path,
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
 * 解析 target 实际使用的根目录。
 *
 * target 一旦声明了自己的 [CleanTarget.baseDir]，解析失败就必须返回 null，不能回退到
 * profile 根目录，否则空 relPath 等规则可能把整个工具数据目录当成独立缓存清掉。
 */
fun resolveBase(profile: ToolProfile, target: CleanTarget): Path? {
    val targetBase = target.baseDir
    val resolved = if (targetBase != null) targetBase() else profile.baseDir()
    return resolved?.toAbsolutePath()?.normalize()
}

/**
 * 把 [CleanTarget] 解析成具体路径列表。不做任何删除。
 *
 * relPath 允许带 `*` 路径段（展开为每层子目录），最后一段可以是文件名 glob
 *（配合 GLOB / STALE_VERSIONS）。
 */
fun resolveTarget(baseDir: Path, target: CleanTarget): ResolvedTarget {
    val safeBase = baseDir.toAbsolutePath().normalize()
    val paths: List<Path> = when (target.kind) {
        MatchKind.DIRECTORY, MatchKind.FILE -> {
            expandPathPattern(safeBase, target.relPath)
                .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { isSafeDeletionPath(safeBase, it) }
                .filter { matchesExpectedType(it, target.entryType) }
        }

        MatchKind.DIRECTORY_CONTENTS -> {
            // listDirectoryEntries 会带上点开头的条目 —— Codex 的大头（.plugin-appserver 等）
            // 正是点开头的，用 shell glob 会漏掉。
            expandPathPattern(safeBase, target.relPath).flatMap { dir ->
                if (isSafeTraversalDirectory(safeBase, dir)) {
                    directoryEntries(dir)
                        .filter { isSafeDeletionPath(safeBase, it) }
                } else {
                    emptyList()
                }
            }
        }

        MatchKind.GLOB, MatchKind.STALE_VERSIONS -> {
            val relative = target.relPath.replace('\\', '/')
            val slash = relative.lastIndexOf('/')
            val dirPattern = if (slash < 0) null else relative.substring(0, slash)
            val pattern = if (slash < 0) relative else relative.substring(slash + 1)
            val dirs = if (dirPattern == null) {
                listOf(safeBase)
            } else {
                expandPathPattern(safeBase, dirPattern)
            }
            dirs.flatMap { dir ->
                if (!isSafeTraversalDirectory(safeBase, dir)) {
                    emptyList()
                } else {
                    val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                    val matched = directoryEntries(dir)
                        .filter { matcher.matches(it.fileName) }
                        .filter { isSafeDeletionPath(safeBase, it) }
                        .filter { matchesExpectedType(it, target.entryType) }
                    if (target.kind == MatchKind.STALE_VERSIONS) {
                        // 每个父目录内各自保留全部并列最新项；mtime 读不出来的也一律保留。
                        val knownTimes = matched.mapNotNull { path ->
                            lastModifiedOrNull(path)?.let { path to it }
                        }
                        val newestTime = knownTimes.maxOfOrNull { it.second }
                        if (newestTime == null) emptyList()
                        else knownTimes.filter { it.second < newestTime }.map { it.first }
                    } else {
                        matched
                    }
                }
            }
        }
    }
    return ResolvedTarget(target, safeBase, paths.distinct())
}

/**
 * 解析相对路径；单独的路径段 `*` 展开为该层每个非符号链接子目录。
 * 不含 `*` 时与 [Path.resolve] 等价（返回单元素列表）。
 */
internal fun expandPathPattern(baseDir: Path, relPath: String): List<Path> {
    val safeBase = baseDir.toAbsolutePath().normalize()
    val parts = relPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.any { it == "." || it == ".." }) return emptyList()
    if (parts.isEmpty()) return listOf(safeBase)
    var currents = listOf(safeBase)
    for (part in parts) {
        currents = if (part == "*") {
            currents.flatMap { dir ->
                if (!isSafeTraversalDirectory(safeBase, dir)) {
                    emptyList()
                } else {
                    runCatching {
                        directoryEntries(dir).filter {
                            Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                                isSafeDeletionPath(safeBase, it)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        } else {
            currents.mapNotNull { current ->
                val candidate = current.resolve(part).toAbsolutePath().normalize()
                candidate.takeIf { it.startsWith(safeBase) }
            }
        }
    }
    return currents
}

/** 最终条目可以是链接（删除链接本身是安全的），但 base 到其父目录之间不能经过链接。 */
internal fun isSafeDeletionPath(baseDir: Path, candidate: Path): Boolean {
    val base = baseDir.toAbsolutePath().normalize()
    val path = candidate.toAbsolutePath().normalize()
    if (path == base || !path.startsWith(base)) return false
    if (linkState(base) != LinkState.PLAIN) return false

    val parent = path.parent ?: return false
    if (parent == base) return true
    val relativeParent = runCatching { base.relativize(parent) }.getOrNull() ?: return false
    var current = base
    for (part in relativeParent) {
        current = current.resolve(part)
        if (linkState(current) != LinkState.PLAIN) return false
    }
    return true
}

/** 只允许真实目录；符号链接和 Windows 目录联接点都不能作为遍历入口。 */
internal fun isSafeTraversalDirectory(baseDir: Path, candidate: Path): Boolean {
    val base = baseDir.toAbsolutePath().normalize()
    val path = candidate.toAbsolutePath().normalize()
    if (!path.startsWith(base)) return false
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    if (linkState(base) != LinkState.PLAIN) return false

    val relative = runCatching { base.relativize(path) }.getOrNull() ?: return false
    var current = base
    for (part in relative) {
        current = current.resolve(part)
        if (linkState(current) != LinkState.PLAIN) return false
    }
    return true
}

private enum class LinkState { PLAIN, LINK_LIKE, UNKNOWN }

private data class PathInspection(
    val path: Path,
    val attributes: BasicFileAttributes,
    val linkState: LinkState,
)

/** 一次属性读取同时完成类型和链接判断；仅对无法分类的特殊项做跟随目录兜底检查。 */
private fun inspectPath(path: Path): PathInspection? {
    val normalized = path.toAbsolutePath().normalize()
    val attrs = runCatching {
        Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull() ?: return null
    val state = when {
        attrs.isSymbolicLink || attrs.isOther -> LinkState.LINK_LIKE
        attrs.isDirectory || attrs.isRegularFile -> LinkState.PLAIN
        Files.isDirectory(normalized) -> LinkState.LINK_LIKE
        else -> LinkState.PLAIN
    }
    return PathInspection(normalized, attrs, state)
}

/** 属性读取失败时返回 UNKNOWN；所有安全判断都把 UNKNOWN 当成拒绝，而不是放行。 */
private fun linkState(path: Path): LinkState {
    return inspectPath(path)?.linkState ?: LinkState.UNKNOWN
}

private fun matchesExpectedType(path: Path, expected: TargetEntryType): Boolean {
    val inspection = inspectPath(path) ?: return false
    if (inspection.linkState != LinkState.PLAIN) return false
    val attrs = inspection.attributes
    return when (expected) {
        TargetEntryType.FILE -> attrs.isRegularFile
        TargetEntryType.DIRECTORY -> attrs.isDirectory
    }
}

private fun lastModifiedOrNull(path: Path): Long? =
    runCatching { Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrNull()

/** Cleaner 复用的 NOFOLLOW_LINKS 身份读取。读取失败或特殊类型不生成可删除快照。 */
internal fun readPathSnapshot(path: Path): PathSnapshot? {
    val inspection = inspectPath(path) ?: return null
    val normalized = inspection.path
    val attrs = inspection.attributes
    val kind = when {
        inspection.linkState == LinkState.LINK_LIKE -> PathSnapshotKind.LINK
        attrs.isDirectory -> PathSnapshotKind.DIRECTORY
        attrs.isRegularFile -> PathSnapshotKind.FILE
        else -> return null
    }
    return PathSnapshot(
        path = normalized,
        kind = kind,
        fileKey = attrs.fileKey()?.toString(),
        size = if (kind == PathSnapshotKind.FILE) attrs.size() else 0L,
        creationMillis = attrs.creationTime().toMillis(),
        lastModifiedMillis = attrs.lastModifiedTime().toMillis(),
    )
}

private inline fun forEachDirectoryEntry(directory: Path, action: (Path) -> Unit) {
    val entries = runCatching { Files.newDirectoryStream(directory) }.getOrNull() ?: return
    try {
        val iterator = entries.iterator()
        while (true) {
            val entry = try {
                if (iterator.hasNext()) iterator.next() else break
            } catch (_: Exception) {
                break
            }
            action(entry)
        }
    } finally {
        runCatching { entries.close() }
    }
}

private fun directoryEntries(directory: Path): List<Path> = buildList {
    forEachDirectoryEntry(directory) { add(it) }
}

private class ProgressBatch(
    private val publish: (deltaBytes: Long, deltaCount: Int) -> Unit,
) {
    private var bytes = 0L
    private var files = 0
    private var entries = 0

    fun add(deltaBytes: Long, deltaCount: Int) {
        bytes += deltaBytes
        files += deltaCount
        entries++
        if (entries >= PROGRESS_BATCH_SIZE) flush()
    }

    fun flush() {
        if (bytes != 0L || files != 0) publish(bytes, files)
        bytes = 0L
        files = 0
        entries = 0
    }
}

private const val PROGRESS_BATCH_SIZE = 128

/**
 * 递归统计大小与文件数。软链接不跟随，避免重复计算或走出目标范围。
 *
 * [onProgress] 每处理一个条目回调一次增量（字节, 文件数），供调用方实时累计
 * 「正在遍历中」的进度；默认空实现，一次性取最终值的调用（如 Cleaner）无额外开销。
 */
private fun measure(
    path: Path,
    onProgress: (deltaBytes: Long, deltaCount: Int) -> Unit = { _, _ -> },
): Pair<Long, Int> {
    val progress = ProgressBatch(onProgress)
    val root = readPathSnapshot(path) ?: return 0L to 0
    if (root.kind == PathSnapshotKind.LINK) {
        progress.add(0L, 1); progress.flush(); return 0L to 1
    }
    if (root.kind == PathSnapshotKind.FILE) {
        progress.add(root.size, 1); progress.flush(); return root.size to 1
    }

    var bytes = 0L
    var count = 0
    val stack = ArrayDeque<Path>()
    stack.addLast(path)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        forEachDirectoryEntry(dir) { e ->
            when (val snapshot = readPathSnapshot(e)) {
                null -> Unit
                else -> when (snapshot.kind) {
                    PathSnapshotKind.LINK -> {
                        count++; progress.add(0L, 1)
                    }
                    PathSnapshotKind.DIRECTORY -> stack.addLast(e)
                    PathSnapshotKind.FILE -> {
                        bytes += snapshot.size; count++; progress.add(snapshot.size, 1)
                    }
                }
            }
        }
        progress.flush()
    }
    return bytes to count
}

private data class PlanBuild(
    val plan: DeletionPlan,
    val bytes: Long,
    val files: Int,
)

/** 单个 target 内缓存已确认的真实目录，避免每个文件都从 base 重新检查整条父路径。 */
private class TraversalSafety(baseDir: Path) {
    private val base = baseDir.toAbsolutePath().normalize()
    private val safeDirectories = hashSetOf<Path>()

    init {
        val inspection = inspectPath(base)
        if (inspection?.linkState == LinkState.PLAIN && inspection.attributes.isDirectory) {
            safeDirectories.add(base)
        }
    }

    fun allowsEntry(candidate: Path): Boolean {
        val path = candidate.toAbsolutePath().normalize()
        if (path == base || !path.startsWith(base)) return false
        return ensureSafeDirectory(path.parent ?: return false)
    }

    fun trustDirectory(snapshot: PathSnapshot): Boolean {
        if (snapshot.kind != PathSnapshotKind.DIRECTORY || !allowsEntry(snapshot.path)) return false
        safeDirectories.add(snapshot.path)
        return true
    }

    private fun ensureSafeDirectory(directory: Path): Boolean {
        val path = directory.toAbsolutePath().normalize()
        if (!path.startsWith(base) || base !in safeDirectories) return false
        if (path in safeDirectories) return true

        val unchecked = ArrayDeque<Path>()
        var current = path
        while (current !in safeDirectories) {
            if (current == base || !current.startsWith(base)) return false
            unchecked.addFirst(current)
            current = current.parent ?: return false
        }
        for (candidate in unchecked) {
            val inspection = inspectPath(candidate) ?: return false
            if (inspection.linkState != LinkState.PLAIN || !inspection.attributes.isDirectory) return false
            safeDirectories.add(candidate)
        }
        return true
    }
}

/**
 * 在扫描阶段把每个实际条目及身份完整冻结下来。Cleaner 只消费这份计划，绝不重新展开目录/glob。
 */
private fun buildDeletionPlan(
    toolId: String,
    resolved: ResolvedTarget,
    onProgress: (deltaBytes: Long, deltaCount: Int) -> Unit = { _, _ -> },
): PlanBuild {
    val snapshots = linkedMapOf<Path, PathSnapshot>()
    val safety = TraversalSafety(resolved.baseDir)
    val progress = ProgressBatch(onProgress)
    var bytes = 0L
    var files = 0

    fun record(snapshot: PathSnapshot) {
        if (snapshots.putIfAbsent(snapshot.path, snapshot) != null) return
        when (snapshot.kind) {
            PathSnapshotKind.FILE -> {
                bytes += snapshot.size
                files++
                progress.add(snapshot.size, 1)
            }
            PathSnapshotKind.LINK -> {
                files++
                progress.add(0L, 1)
            }
            PathSnapshotKind.DIRECTORY -> Unit
        }
    }

    for (root in resolved.paths) {
        if (!safety.allowsEntry(root)) continue
        val rootSnapshot = readPathSnapshot(root) ?: continue
        record(rootSnapshot)
        if (rootSnapshot.kind != PathSnapshotKind.DIRECTORY) continue
        if (!safety.trustDirectory(rootSnapshot)) continue

        val stack = ArrayDeque<Path>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            forEachDirectoryEntry(dir) { entry ->
                if (!safety.allowsEntry(entry)) return@forEachDirectoryEntry
                val snapshot = readPathSnapshot(entry) ?: return@forEachDirectoryEntry
                record(snapshot)
                if (safety.trustDirectory(snapshot)) {
                    stack.addLast(entry)
                }
            }
            progress.flush()
        }
    }
    progress.flush()

    return PlanBuild(
        plan = DeletionPlan(
            toolId = toolId,
            targetId = resolved.target.id,
            baseDir = resolved.baseDir,
            entries = snapshots.values.toList(),
        ),
        bytes = bytes,
        files = files,
    )
}

/** 扫描单个 target。已解析过路径的话直接复用，省一次目录遍历。 */
fun scanResolved(toolId: String, resolved: ResolvedTarget): ScanResult {
    val built = buildDeletionPlan(toolId, resolved)
    return ScanResult(
        toolId = toolId,
        targetId = resolved.target.id,
        bytes = built.bytes,
        fileCount = built.files,
        exists = built.plan.entries.isNotEmpty(),
        deletionPlan = built.plan,
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
    val key = TargetKey(toolId, targetId)
    val bytes = AtomicLong(0L)
    val files = AtomicInteger(0)
    val exists = AtomicBoolean(false)
    val deletionPlan = AtomicReference<DeletionPlan?>(null)

    fun snapshot(): ScanResult = ScanResult(
        toolId = toolId,
        targetId = targetId,
        bytes = bytes.get(),
        fileCount = files.get(),
        exists = exists.get(),
        deletionPlan = deletionPlan.get(),
    )
}

/**
 * 扫描一个 target，遍历途中把增量实时写进 [progress]。逻辑与 [scanResolved] 等价，
 * 区别只是「边扫边报」而非「扫完一次性返回」，因此不复用后者，避免给 Cleaner 引入回调开销。
 */
private fun scanWithProgress(profile: ToolProfile, target: CleanTarget, progress: TargetProgress) {
    val base = resolveBase(profile, target) ?: return
    val resolved = resolveTarget(base, target)
    progress.exists.set(resolved.paths.isNotEmpty())
    val built = buildDeletionPlan(profile.id, resolved) { deltaBytes, deltaCount ->
        if (deltaBytes != 0L) progress.bytes.addAndGet(deltaBytes)
        if (deltaCount != 0) progress.files.addAndGet(deltaCount)
    }
    progress.deletionPlan.set(built.plan)
    progress.exists.set(built.plan.entries.isNotEmpty())
}

/**
 * 单个工具目录总占用遍历过程中的实时累计。与 [TargetProgress] 同理：
 * worker 边遍历边写增量，定时器随时读出「当前已找到多少」。
 */
private class SpaceProgress(val toolId: String) {
    val bytes = AtomicLong(0L)
    val files = AtomicInteger(0)

    fun snapshot(): ToolSpaceResult = ToolSpaceResult(
        toolId = toolId,
        bytes = bytes.get(),
        fileCount = files.get(),
    )
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
     * 到目前为止各工具目录总占用快照。
     *
     * 与 [TargetsScanned] 一样由定时器按固定节拍推整份快照：阶段一测量
     * spaceDirs 时边遍历边累加，UI 的「已找到」数字随拍更新，而不是等整阶段结束。
     */
    data class SpaceScanned(val spaces: List<ToolSpaceResult>) : ScanEvent

    /**
     * 到目前为止已测完的全部可清理项快照。
     *
     * 不再每测完一项推一次，而是由定时器按固定节拍推整份快照：worker 只管写结果，
     * 节流集中在一处，UI 拿到的是稳定节奏的批量更新，而不是随磁盘忽快忽慢的抖动。
     */
    data class TargetsScanned(val results: List<ScanResult>) : ScanEvent
}

/**
 * 扫描所有 profile，逐项上报。纯磁盘 IO，每项各自跑在 IO 调度器上。
 *
 * 工具目录总占用和可清理项分别测量，口径互相独立：总占用代表应用真实体积，
 * 可清理项只代表其中能删的部分，两者相减就是必须保留的数据。
 * 工具未安装（spaceDirs 为空）时上报全 0，由 UI 决定是否显示。
 *
 * 分两个阶段：先测完总占用，再扫可清理项。总占用是分母，阶段一内边测边报
 * （「已找到」随拍增长）；阶段一结束后分母固定，阶段二每测出一项都只是在已知
 * 总量里重新划分，占比不会被整体重算。若两阶段并发上报，总占用（要遍历整个
 * 目录，最慢）最后才到，分母会突变一次。
 *
 * 两个阶段都不按「每项测完推一次」上报，而是让 worker 边遍历边把增量累进各自的
 * 进度槽，另一个协程每 [SNAPSHOT_INTERVAL_MS] 读出当前累计推一份快照——包括仍在
 * 遍历、尚未收尾的项。全部 worker 完成后立即补推一份收尾全量。
 */
// 略长于 UI 数字翻转（~260ms），避免下一拍到来时上一次翻牌还没播完。
private const val SNAPSHOT_INTERVAL_MS = 500L
private const val SCAN_PARALLELISM = 8
private val SCAN_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)

/** 边跑 [workers] 边按固定节拍执行 [emit]，全部完成后立即再 emit 一次收尾。 */
private suspend fun CoroutineScope.snapshotWhile(
    workers: List<Job>,
    emit: suspend () -> Unit,
) {
    val allDone = CompletableDeferred<Unit>()
    launch {
        workers.forEach { it.join() }
        allDone.complete(Unit)
    }
    while (true) {
        val finished = withTimeoutOrNull(SNAPSHOT_INTERVAL_MS) {
            allDone.await()
            true
        } == true
        emit()
        if (finished) break
    }
}

fun scanStream(profiles: List<ToolProfile>): Flow<ScanEvent> = channelFlow {
    send(ScanEvent.Started(profiles.sumOf { it.targets.size } + profiles.size))

    // —— 阶段一：工具目录总占用。边测边报，供 UI「已找到」实时更新。 ——
    val spaceProgresses = profiles.map { SpaceProgress(it.id) }
    val spaceById = spaceProgresses.associateBy { it.toolId }
    val spaceWorkers = profiles.flatMap { profile ->
        profile.spaceDirs().map { dir ->
            launch(SCAN_DISPATCHER) {
                val progress = spaceById.getValue(profile.id)
                measure(dir) { deltaBytes, deltaCount ->
                    if (deltaBytes != 0L) progress.bytes.addAndGet(deltaBytes)
                    if (deltaCount != 0) progress.files.addAndGet(deltaCount)
                }
            }
        }
    }
    snapshotWhile(spaceWorkers) {
        send(ScanEvent.SpaceScanned(spaceProgresses.map { it.snapshot() }))
    }

    // —— 阶段二：可清理项。每个 target 一份实时累计。 ——
    val progresses = profiles.flatMap { profile ->
        profile.targets.map { TargetProgress(profile.id, it.id) }
    }
    val progressByKey = progresses.associateBy { it.key }
    val workers = buildList {
        for (profile in profiles) {
            for (target in profile.targets) {
                val progress = progressByKey.getValue(TargetKey(profile.id, target.id))
                add(
                    launch(SCAN_DISPATCHER) {
                        scanWithProgress(profile, target, progress)
                    },
                )
            }
        }
    }
    snapshotWhile(workers) {
        send(ScanEvent.TargetsScanned(progresses.map { it.snapshot() }))
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
