package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.tools.chatTools

private const val MB = 1024L * 1024L
private const val DAY_MILLIS = 86_400_000L

enum class ConditionValueKind(val unit: String) {
    DAYS("天"),
    MEGABYTES("MB"),
    TOOL(""),
    TEXT(""),
}

enum class ChatConditionType(
    val id: String,
    val label: String,
    val kind: ConditionValueKind,
) {
    UPDATED_BEFORE_DAYS("older_than", "未更新超过", ConditionValueKind.DAYS),
    UPDATED_WITHIN_DAYS("newer_than", "未更新少于", ConditionValueKind.DAYS),
    SIZE_LARGER_MB("larger_than", "大小超过", ConditionValueKind.MEGABYTES),
    SIZE_SMALLER_MB("smaller_than", "大小少于", ConditionValueKind.MEGABYTES),
    TOOL_IS("tool_is", "所属工具是", ConditionValueKind.TOOL),
    PROJECT_CONTAINS("project_has", "项目名包含", ConditionValueKind.TEXT),
    TITLE_CONTAINS("title_has", "标题包含", ConditionValueKind.TEXT),
    ;

    companion object {
        fun fromId(id: String): ChatConditionType? = entries.firstOrNull { it.id == id }
    }
}

data class ChatCondition(
    val type: ChatConditionType,
    val number: Int = defaultNumberFor(type),
    val text: String = "",
)

fun defaultNumberFor(type: ChatConditionType): Int = when (type.kind) {
    // 各类型的默认数值：新加条件时给一个合理起点；填 0 天会命中全部会话。
    ConditionValueKind.DAYS -> 30
    ConditionValueKind.MEGABYTES -> 10
    else -> 0
}

enum class ConditionJoin(val id: String, val label: String) {
    AND("and", "且"),
    OR("or", "或"),
    ;

    companion object {
        fun fromId(id: String): ConditionJoin = entries.firstOrNull { it.id == id } ?: AND
    }
}

data class RetentionRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = false,
    val join: ConditionJoin = ConditionJoin.AND,
    val conditions: List<ChatCondition> = emptyList(),
)

data class RetentionConfig(val rules: List<RetentionRule> = emptyList())

fun ChatCondition.isComplete(): Boolean = when (type.kind) {
    // 空条件会命中一切，这里挡住未填完的规则。
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> number >= 1
    ConditionValueKind.TOOL -> chatTools().any { it.id == text }
    ConditionValueKind.TEXT -> text.isNotBlank()
}

fun RetentionRule.effectiveConditions(): List<ChatCondition> = conditions.filter { it.isComplete() }

fun RetentionRule.isEffective(): Boolean = enabled && effectiveConditions().isNotEmpty()

fun ChatCondition.matches(session: ChatSessionSummary, nowMillis: Long): Boolean = when (type) {
    // [nowMillis] 由调用方固定，保证一次判定内时间基准一致。
    ChatConditionType.UPDATED_BEFORE_DAYS ->
        session.updatedMillis < nowMillis - number * DAY_MILLIS

    ChatConditionType.UPDATED_WITHIN_DAYS ->
        session.updatedMillis > nowMillis - number * DAY_MILLIS

    ChatConditionType.SIZE_LARGER_MB ->
        session.sizeBytes > number * MB

    ChatConditionType.SIZE_SMALLER_MB ->
        session.sizeBytes < number * MB

    ChatConditionType.TOOL_IS ->
        session.tool.id == text

    ChatConditionType.PROJECT_CONTAINS ->
        projectLabel(session).contains(text, ignoreCase = true)

    ChatConditionType.TITLE_CONTAINS ->
        session.title.contains(text, ignoreCase = true)
}

fun RetentionRule.matches(session: ChatSessionSummary, nowMillis: Long): Boolean {
    if (!enabled) return false
    val effective = effectiveConditions()
    if (effective.isEmpty()) return false
    return when (join) {
        ConditionJoin.AND -> effective.all { it.matches(session, nowMillis) }
        ConditionJoin.OR -> effective.any { it.matches(session, nowMillis) }
    }
}

fun RetentionConfig.isActive(): Boolean = rules.any { it.isEffective() }

fun RetentionConfig.match(
    sessions: List<ChatSessionSummary>,
    nowMillis: Long,
): List<ChatSessionSummary> =
    // 任一规则命中即删。
    sessions.filter { session -> rules.any { it.matches(session, nowMillis) } }

fun newRuleId(existing: Collection<String>): String {
    // 生成一个不与现有规则冲突的 id。落盘按 id 定位规则，顺序变化时引用仍稳定。
    var i = 1
    while ("rule-$i" in existing) i++
    return "rule-$i"
}
