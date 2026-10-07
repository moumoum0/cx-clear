package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.MatchKind
import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.ScanResult
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

// 一个 target 实际会被删掉的东西。Scanner 与 Cleaner 共用同一套解析结果。
data class ResolvedTarget(
    val target: CleanTarget,
    // 本项允许删除的根目录。Cleaner 会在实际删除前再次用它确认待删路径仍在根目录内。
    val baseDir: Path,
    // 待删除的路径。DIRECTORY_CONTENTS 展开成一级子项，GLOB 展开成匹配到的文件。
    val paths: List<Path>,
)

// 一个工具目录的完整占用，不区分是否允许清理。
data class ToolSpaceResult(
    val toolId: String,
    val bytes: Long,
    val fileCount: Int,
)

/**
 * 解析 target 实际使用的根目录。
 *
 * target 一旦声明了自己的 [CleanTarget.baseDir]，解析失败需要返回 null；
 * 回退到 profile 根目录会让空 relPath 等规则把整个工具数据目录当成独立缓存清掉。
 */
fun resolveBase(profile: ToolProfile, target: CleanTarget): Path? {
    val targetBase = target.baseDir
    val resolved = if (targetBase != null) targetBase() else profile.baseDir()
    return resolved?.toAbsolutePath()?.normalize()
}

// 把 [CleanTarget] 解析成具体路径列表；`*` 路径段展开为该层子目录，末段可文件名 glob。
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
            // listDirectoryEntries 能拿到点开头的条目（Codex 的大头就在这里），shell glob 会漏掉。
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
                        // 每个父目录内各自保留全部并列最新项；mtime 读不出来的也保留。
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
 * 递归统计大小与文件数。软链接按自身计（1 个条目、0 字节）：跟随会重复计算甚至走出目标范围。
 * [onProgress] 每条目回调一次增量，供调用方实时累计进度。
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

// 单个 target 内缓存已验证为普通目录的目录；否则每个文件都要从 base 重新检查整条父路径。
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

// 扫描阶段把每个实际条目及身份冻结下来，Cleaner 直接消费这份计划。
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

// 扫描单个 target。已解析过路径的话直接复用，省一次目录遍历。
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

// 单个 target 遍历中的实时累计，定时器随时读出「当前扫到多少」。
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

// 与 [scanResolved] 等价，边扫边把增量写进 [progress]；复用 scanResolved 会给 Cleaner 引入回调开销。
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

// 工具目录总占用遍历中的实时累计，与 [TargetProgress] 同理。
private class SpaceProgress(val toolId: String) {
    val bytes = AtomicLong(0L)
    val files = AtomicInteger(0)

    fun snapshot(): ToolSpaceResult = ToolSpaceResult(
        toolId = toolId,
        bytes = bytes.get(),
        fileCount = files.get(),
    )
}

// 扫描过程中的增量事件；事件流上报让 UI 能实时反映进度。
sealed interface ScanEvent {
    // 本次扫描的工作项总数（可清理项 + 工具目录），用于算进度分母。
    data class Started(val total: Int) : ScanEvent

    // 各工具目录总占用的当前快照，按固定节拍推整份。
    data class SpaceScanned(val spaces: List<ToolSpaceResult>) : ScanEvent

    // 已测完可清理项的当前快照，按固定节拍推整份。
    data class TargetsScanned(val results: List<ScanResult>) : ScanEvent
}

/**
 * 扫描所有 profile，逐项上报。总占用与可清理项两类 worker 并行开扫：
 * GUI 只拿总占用做实时进度，细分扫完一次性出，不需要先凑齐分母。
 * worker 边遍历边累增量，定时协程每 [SNAPSHOT_INTERVAL_MS] 推一份整份快照，收尾补推全量。
 * 工具未安装（spaceDirs 为空）时上报全 0，由 UI 决定是否显示。
 */
// 略长于 UI 数字滚动（520ms），留出播完再追上的空档。
private const val SNAPSHOT_INTERVAL_MS = 800L
private const val SCAN_PARALLELISM = 8
private val SCAN_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)

// 边跑 [workers] 边按固定节拍执行 [emit]，全部完成后立即再 emit 一次收尾。
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

    // 工具目录总占用。
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

    // 可清理项，每个 target 一份实时累计。
    val progresses = profiles.flatMap { profile ->
        profile.targets.map { TargetProgress(profile.id, it.id) }
    }
    val progressByKey = progresses.associateBy { it.key }
    val targetWorkers = profiles.flatMap { profile ->
        profile.targets.map { target ->
            launch(SCAN_DISPATCHER) {
                scanWithProgress(profile, target, progressByKey.getValue(TargetKey(profile.id, target.id)))
            }
        }
    }

    snapshotWhile(spaceWorkers + targetWorkers) {
        send(ScanEvent.SpaceScanned(spaceProgresses.map { it.snapshot() }))
        send(ScanEvent.TargetsScanned(progresses.map { it.snapshot() }))
    }
}
