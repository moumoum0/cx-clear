package dev.cxclear.chats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 手动管理页的排列与分组规则。
 *
 * 这里只做纯计算（筛选 / 排序 / 分档），不碰文件系统，也不依赖 Compose，
 * 便于 UI 层随状态变化反复调用。
 */

/** 列表排列方式。 */
enum class ChatSortKey(val label: String) {
    UPDATED("更新时间"),
    SIZE("大小"),
    PROJECT("项目"),
    TITLE("标题"),
}

/** 分组维度：决定下方列表按哪个维度切成可折叠区块。 */
enum class ChatGroupDimension(val label: String) {
    TIME("时间"),
    SIZE("大小"),
    PROJECT("项目"),
    NONE("不分组"),
}

/**
 * 排列轴：把「按什么排」与「按什么分组」合成一个选择。
 *
 * 选中一个轴即按它排列；再把 [groupDimension] 打开就按同一个轴切档。
 * [groupDimension] 为 null 表示这个轴没有可用的分档（标题无从分档）。
 */
enum class ChatAxis(
    val label: String,
    val sortKey: ChatSortKey,
    val groupDimension: ChatGroupDimension?,
) {
    TIME("时间", ChatSortKey.UPDATED, ChatGroupDimension.TIME),
    SIZE("大小", ChatSortKey.SIZE, ChatGroupDimension.SIZE),
    PROJECT("项目", ChatSortKey.PROJECT, ChatGroupDimension.PROJECT),
    TITLE("标题", ChatSortKey.TITLE, null),
}

/** 一个可折叠区块：同一档位/项目下的会话集合。 */
data class ChatGroup(
    val key: String,
    val label: String,
    val sessions: List<ChatSessionSummary>,
) {
    val totalBytes: Long get() = sessions.sumOf { it.sizeBytes }
}

// ─────────────────────────────────────────────
// 档位定义
// ─────────────────────────────────────────────

private const val MB = 1024L * 1024L

/** 大小档：[minInclusive, maxExclusive)。顺序即展示顺序。 */
private data class SizeBucket(val key: String, val label: String, val min: Long, val max: Long)

private val SIZE_BUCKETS = listOf(
    SizeBucket("size-lt-1m", "小于 1 MB", 0L, MB),
    SizeBucket("size-1-5m", "1 ~ 5 MB", MB, 5 * MB),
    SizeBucket("size-5-20m", "5 ~ 20 MB", 5 * MB, 20 * MB),
    SizeBucket("size-gt-20m", "大于 20 MB", 20 * MB, Long.MAX_VALUE),
)

/** 时间档：距今不超过 [withinDays] 天；`null` 表示兜底的「更早」。顺序即展示顺序。 */
private data class TimeBucket(val key: String, val label: String, val withinDays: Long?)

private val TIME_BUCKETS = listOf(
    TimeBucket("time-1d", "今天", 1L),
    TimeBucket("time-7d", "7 天内", 7L),
    TimeBucket("time-30d", "30 天内", 30L),
    TimeBucket("time-90d", "90 天内", 90L),
    TimeBucket("time-older", "更早", null),
)

/** 项目为空时的归档名。Codex 未记录 cwd、Claude 目录名缺失时落到这里。 */
private const val NO_PROJECT_LABEL = "未归属项目"

// ─────────────────────────────────────────────
// 项目名展示
// ─────────────────────────────────────────────

/**
 * Claude 的项目目录名是把绝对路径整条编码进来的（`d--project-cxclear`），
 * 直接显示太长且看不出重点，取最后一段作为标签。Codex 记的已经是目录名，原样返回。
 */
fun projectLabel(session: ChatSessionSummary): String {
    val raw = session.project?.takeIf { it.isNotBlank() } ?: return NO_PROJECT_LABEL
    return when (session.tool) {
        ChatTool.CLAUDE -> raw.trimEnd('-').substringAfterLast('-').ifBlank { raw }
        ChatTool.CODEX -> raw
    }
}

// ─────────────────────────────────────────────
// 筛选 / 排序 / 分组
// ─────────────────────────────────────────────

/** 按搜索词过滤：匹配标题或项目名，大小写不敏感。空词返回原列表。 */
fun filterSessions(
    sessions: List<ChatSessionSummary>,
    query: String,
): List<ChatSessionSummary> {
    val q = query.trim()
    if (q.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.contains(q, ignoreCase = true) ||
            projectLabel(session).contains(q, ignoreCase = true)
    }
}

private fun sortSessions(
    sessions: List<ChatSessionSummary>,
    sortKey: ChatSortKey,
    ascending: Boolean,
): List<ChatSessionSummary> {
    val sorted = when (sortKey) {
        ChatSortKey.UPDATED -> sessions.sortedBy { it.updatedMillis }
        ChatSortKey.SIZE -> sessions.sortedBy { it.sizeBytes }
        // 项目名同名时退回更新时间，保证同项目内也有稳定次序。
        ChatSortKey.PROJECT -> sessions.sortedWith(
            compareBy<ChatSessionSummary> { projectLabel(it).lowercase() }
                .thenBy { it.updatedMillis }
        )
        // 标题用不区分大小写的字典序，中文按 UTF-16 码点序，够稳定。
        ChatSortKey.TITLE -> sessions.sortedBy { it.title.lowercase() }
    }
    return if (ascending) sorted else sorted.reversed()
}

/**
 * 把 [sessions] 按 [dimension] 切成可折叠区块，每块内部按 [sortKey] / [ascending] 排列。
 *
 * 空档位不产出区块。时间档以 [nowMillis] 为基准，同一次渲染内保持一致。
 */
fun groupSessions(
    sessions: List<ChatSessionSummary>,
    dimension: ChatGroupDimension,
    sortKey: ChatSortKey,
    ascending: Boolean,
    nowMillis: Long,
): List<ChatGroup> {
    fun sorted(list: List<ChatSessionSummary>) = sortSessions(list, sortKey, ascending)

    return when (dimension) {
        ChatGroupDimension.NONE -> if (sessions.isEmpty()) {
            emptyList()
        } else {
            listOf(ChatGroup("all", "全部会话", sorted(sessions)))
        }

        ChatGroupDimension.SIZE -> SIZE_BUCKETS.mapNotNull { bucket ->
            val hits = sessions.filter { it.sizeBytes >= bucket.min && it.sizeBytes < bucket.max }
            if (hits.isEmpty()) null else ChatGroup(bucket.key, bucket.label, sorted(hits))
        }

        ChatGroupDimension.TIME -> {
            // 「今天」按自然日切，其余按滚动天数切；一条会话只落最前面命中的那一档。
            // 单次遍历分桶，避免逐档 filter + Set 差集在会话多时拖慢 UI 线程。
            val zone = ZoneId.systemDefault()
            val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayMs = 24L * 3600_000L
            val floors = LongArray(TIME_BUCKETS.size) { index ->
                when (val days = TIME_BUCKETS[index].withinDays) {
                    null -> Long.MIN_VALUE
                    1L -> startOfToday
                    else -> nowMillis - days * dayMs
                }
            }
            val buckets = Array(TIME_BUCKETS.size) { mutableListOf<ChatSessionSummary>() }
            for (session in sessions) {
                val t = session.updatedMillis
                var assigned = floors.lastIndex
                for (i in floors.indices) {
                    if (t >= floors[i]) {
                        assigned = i
                        break
                    }
                }
                buckets[assigned].add(session)
            }
            TIME_BUCKETS.mapIndexedNotNull { index, bucket ->
                val hits = buckets[index]
                if (hits.isEmpty()) null else ChatGroup(bucket.key, bucket.label, sorted(hits))
            }
        }

        ChatGroupDimension.PROJECT -> sessions
            .groupBy { projectLabel(it) }
            .map { (label, hits) -> ChatGroup("project-$label", label, sorted(hits)) }
            // 项目多时把「会话多的项目」顶上去，「未归属」永远垫底。
            .sortedWith(
                compareBy<ChatGroup> { it.label == NO_PROJECT_LABEL }
                    .thenByDescending { it.sessions.size }
                    .thenBy { it.label }
            )
    }
}

// ─────────────────────────────────────────────
// 时间展示
// ─────────────────────────────────────────────

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** 更新时间的简短展示：今天给时刻，近一周给「N 天前」，更早给日期。 */
fun formatUpdatedAt(millis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = today.toEpochDay() - date.toLocalDate().toEpochDay()
    return when {
        days <= 0L -> "今天 ${TIME_FORMAT.format(date)}"
        days == 1L -> "昨天 ${TIME_FORMAT.format(date)}"
        days < 7L -> "$days 天前"
        else -> DATE_FORMAT.format(date)
    }
}
