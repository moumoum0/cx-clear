package dev.cxclear.model

import java.nio.file.Path

/**
 * 清理风险等级。
 * SAFE     — 纯缓存/临时文件，删除后工具会自动重建，不丢任何用户数据。
 * OPTIONAL — 会丢历史记录（会话、日志），功能不受影响但内容不可恢复。
 */
enum class Risk { SAFE, OPTIONAL }

/** 目标匹配方式。 */
enum class MatchKind {
    /** 整个目录连同内容一起删除。 */
    DIRECTORY,

    /** 只删目录里的内容，保留目录本身（有些工具启动时要求目录存在）。 */
    DIRECTORY_CONTENTS,

    /** 单个文件。 */
    FILE,

    /** 目录下用 glob 匹配的一批文件，如 `logs_*.sqlite*`。 */
    GLOB,

    /**
     * 同 [GLOB]，但按修改时间保留最新的一个。
     * 用于「同一程序留了很多历史版本」的目录：旧版本是垃圾，最新那份可能正在被使用。
     */
    STALE_VERSIONS,
}

/** target 最终命中的条目类型。严格校验，避免“文件规则”意外递归删除同名目录。 */
enum class TargetEntryType { FILE, DIRECTORY }

/** target 的全局唯一键。不能只用 targetId，否则不同工具的同名项会串选、串删。 */
data class TargetKey(val toolId: String, val targetId: String)

/** 扫描时记录的实际文件系统条目类型。 */
enum class PathSnapshotKind { FILE, DIRECTORY, LINK }

/**
 * 扫描时冻结的单个待删除条目。
 *
 * Cleaner 会在删除前重新读取 NOFOLLOW_LINKS 属性并核对身份；路径相同但已被替换的文件不会删除。
 */
data class PathSnapshot(
    val path: Path,
    val kind: PathSnapshotKind,
    val fileKey: String?,
    val size: Long,
    val creationMillis: Long,
    val lastModifiedMillis: Long,
)

/** 一次扫描冻结下来的精确删除清单。清理阶段不得重新扩展 glob 或目录内容。 */
data class DeletionPlan(
    val toolId: String,
    val targetId: String,
    val baseDir: Path,
    val entries: List<PathSnapshot>,
)

/**
 * 一个可清理项。[relPath] 相对 [baseDir]（若指定）或所属 [ToolProfile] 的 baseDir。
 *
 * [relPath] 中单独的路径段 `*` 表示「展开为该层每个子目录」
 *（例：projects 下每个子目录里的 agent-tools）。最后一段的文件名 glob
 *（配合 GLOB / STALE_VERSIONS）与路径段展开不冲突。
 */
data class CleanTarget(
    val id: String,
    val label: String,
    val relPath: String,
    val kind: MatchKind,
    val risk: Risk,
    val description: String,
    /**
     * 最终命中项的预期类型。
     * DIRECTORY / DIRECTORY_CONTENTS 默认目录，FILE / GLOB / STALE_VERSIONS 默认文件；
     * 像 Cursor CachedData 这种“历史版本目录”需显式指定 DIRECTORY。
     */
    val entryType: TargetEntryType = when (kind) {
        MatchKind.DIRECTORY, MatchKind.DIRECTORY_CONTENTS -> TargetEntryType.DIRECTORY
        MatchKind.FILE, MatchKind.GLOB, MatchKind.STALE_VERSIONS -> TargetEntryType.FILE
    },
    /**
     * 默认是否勾选。
     * 一般 SAFE 默认勾上、OPTIONAL 交给用户；重建成本高（大体积重下、会暂时影响功能）
     * 的 SAFE 项应显式设为 false。
     */
    val defaultSelected: Boolean = risk == Risk.SAFE,
    /**
     * 覆盖所属 profile 的根目录。
     * 用于数据分散在多处的工具（如 Cursor：`~/.cursor` 与 `%APPDATA%\\Cursor`）。
     */
    val baseDir: (() -> Path?)? = null,
)

/**
 * 一个被支持的工具（Codex / Claude Code / …）。
 * [baseDir] 延迟求值，因为路径解析依赖运行时环境变量。
 */
data class ToolProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseDir: () -> Path?,
    val targets: List<CleanTarget>,
    /** 运行中进程的可执行文件名前缀（小写）；命中时 Cleaner 会阻断该工具的全部清理。 */
    val processNamePrefixes: Set<String> = emptySet(),
    /** 无论 profile 数据怎样配置都绝不能删除的路径，作为名单之外的最后一道保险。 */
    val protectedPaths: () -> List<Path> = { emptyList() },
    /**
     * 计入「工具总占用」的目录列表（不含安装目录）。
     * 默认只有 [baseDir]；多根工具（Cursor）在此列出全部数据根。
     */
    val spaceDirs: () -> List<Path> = { listOfNotNull(baseDir()) },
)

/** 单个 target 的扫描结果。 */
data class ScanResult(
    val toolId: String,
    val targetId: String,
    val bytes: Long,
    val fileCount: Int,
    val exists: Boolean,
    val deletionPlan: DeletionPlan? = null,
)

/** 清理过程中的事件流。 */
sealed interface CleanEvent {
    data class Started(val totalTargets: Int) : CleanEvent

    data class TargetDone(
        val targetId: String,
        val label: String,
        val freedBytes: Long,
        val error: String? = null,
    ) : CleanEvent

    /** 检测到目标工具仍在运行，整批清理在删除任何文件前被阻断。 */
    data class Blocked(val tools: List<String>) : CleanEvent

    data class AllDone(val totalFreedBytes: Long, val failures: Int) : CleanEvent
}
