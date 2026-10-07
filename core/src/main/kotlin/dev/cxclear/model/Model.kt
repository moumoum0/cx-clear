package dev.cxclear.model

import java.nio.file.Path

enum class Risk { SAFE, OPTIONAL }

enum class MatchKind {
    DIRECTORY,

    // 只删目录里的内容，保留目录本身（有些工具启动时要求目录存在）。
    DIRECTORY_CONTENTS,

    FILE,
    GLOB,

    STALE_VERSIONS,
}

enum class TargetEntryType { FILE, DIRECTORY }
// target 最终命中的条目类型。删除前会核对；“文件规则”命中同名目录时会递归删错。

// 不能只用 targetId，否则不同工具的同名项会串选、串删。
data class TargetKey(val toolId: String, val targetId: String)

enum class PathSnapshotKind { FILE, DIRECTORY, LINK }

data class PathSnapshot(
    val path: Path,
    val kind: PathSnapshotKind,
    val fileKey: String?,
    val size: Long,
    val creationMillis: Long,
    val lastModifiedMillis: Long,
)

data class DeletionPlan(
    val toolId: String,
    val targetId: String,
    val baseDir: Path,
    val entries: List<PathSnapshot>,
)
// 一次扫描冻结下来的精确删除清单。清理阶段按清单删；重新展开 glob 或目录会把扫描后新增的文件带走。

data class CleanTarget(
    val id: String,
    val label: String,
    val relPath: String,
    val kind: MatchKind,
    val risk: Risk,
    val description: String,
    val entryType: TargetEntryType = when (kind) {
        MatchKind.DIRECTORY, MatchKind.DIRECTORY_CONTENTS -> TargetEntryType.DIRECTORY
        MatchKind.FILE, MatchKind.GLOB, MatchKind.STALE_VERSIONS -> TargetEntryType.FILE
    },
    // 重建贵的 SAFE 项要显式 false；默认勾选一律读这个字段，禁止写死 risk == SAFE。
    val defaultSelected: Boolean = risk == Risk.SAFE,
    val baseDir: (() -> Path?)? = null,
)

data class ToolProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseDir: () -> Path?,
    val targets: List<CleanTarget>,
    // 运行中进程的可执行文件名（小写、不含 .exe）；命中时 Cleaner 会阻断该工具的全部清理。
    val processNamePrefixes: Set<String> = emptySet(),
    // 无论 profile 数据怎样配置都不能删除的路径，是名单之外的最后一道保险。
    val protectedPaths: () -> List<Path> = { emptyList() },
    val spaceDirs: () -> List<Path> = { listOfNotNull(baseDir()) },
)

data class ScanResult(
    val toolId: String,
    val targetId: String,
    val bytes: Long,
    val fileCount: Int,
    val exists: Boolean,
    val deletionPlan: DeletionPlan? = null,
)

sealed interface CleanEvent {
    data class Started(val totalTargets: Int) : CleanEvent

    data class TargetDone(
        val targetId: String,
        val label: String,
        val freedBytes: Long,
        val error: String? = null,
    ) : CleanEvent

    data class Blocked(val tools: List<String>) : CleanEvent
    // 检测到目标工具仍在运行，整批清理在删除任何文件前被阻断。

    data class AllDone(val totalFreedBytes: Long, val failures: Int) : CleanEvent
}
