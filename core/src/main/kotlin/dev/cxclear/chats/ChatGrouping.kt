package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 手动管理页的排列与分组：纯计算，UI 可随状态反复调用。
enum class ChatSortKey(val label: String) {
    UPDATED("更新时间"),
    SIZE("大小"),
    PROJECT("项目"),
    TITLE("标题"),
}

enum class ChatGroupDimension(val label: String) {
    TIME("时间"),
    SIZE("大小"),
    PROJECT("项目"),
    NONE("不分组"),
}

// 排列轴：选中即按它排列，[groupDimension] 打开就按同一个轴切档；null 表示无可用分档。
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

data class ChatGroup(
    val key: String,
    val label: String,
    val sessions: List<ChatSessionSummary>,
) {
    val totalBytes: Long get() = sessions.sumOf { it.sizeBytes }
}

private const val MB = 1024L * 1024L

// [min, max) ；顺序即展示顺序。
private data class SizeBucket(val key: String, val label: String, val min: Long, val max: Long)

private val SIZE_BUCKETS = listOf(
    SizeBucket("size-lt-1m", "小于 1 MB", 0L, MB),
    SizeBucket("size-1-5m", "1 ~ 5 MB", MB, 5 * MB),
    SizeBucket("size-5-20m", "5 ~ 20 MB", 5 * MB, 20 * MB),
    SizeBucket("size-gt-20m", "大于 20 MB", 20 * MB, Long.MAX_VALUE),
)

// 时间档：距今不超过 [withinDays] 天；`null` 落在「更早」档。顺序即展示顺序。
private data class TimeBucket(val key: String, val label: String, val withinDays: Long?)

private val TIME_BUCKETS = listOf(
    TimeBucket("time-1d", "今天", 1L),
    TimeBucket("time-7d", "7 天内", 7L),
    TimeBucket("time-30d", "30 天内", 30L),
    TimeBucket("time-90d", "90 天内", 90L),
    TimeBucket("time-older", "更早", null),
)

// 项目为空时的归档名。Codex 未记录 cwd、Claude 目录名缺失时落到这里。
private const val NO_PROJECT_LABEL = "未归属项目"

// 展示用项目名。编码规则在各工具自己的 [ChatTool.projectLabel] 里。
fun projectLabel(session: ChatSessionSummary): String = session.tool.projectLabel(session.project)

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
        ChatSortKey.PROJECT -> sessions.sortedWith(
            compareBy<ChatSessionSummary> { projectLabel(it).lowercase() }
                .thenBy { it.updatedMillis }
        )
        ChatSortKey.TITLE -> sessions.sortedBy { it.title.lowercase() }
    }
    return if (ascending) sorted else sorted.reversed()
}

/** 按 [dimension] 切成可折叠区块，空档不产出；时间档以 [nowMillis] 为基准。 */
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
            // 单次遍历分桶：逐档 filter + Set 差集在会话多时会拖慢 UI 线程。
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
            .sortedWith(
                compareBy<ChatGroup> { it.label == NO_PROJECT_LABEL }
                    .thenByDescending { it.sessions.size }
                    .thenBy { it.label }
            )
    }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

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
