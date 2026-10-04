package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.tools.chatTools

/**
 * 自动清理策略模型与匹配引擎。规则之间恒为「或」，任一命中即待删。
 * 这里做纯计算，落盘在 [RetentionStore]，执行在 [RetentionRunner]。
 */

private const val MB = 1024L * 1024L
private const val DAY_MILLIS = 86_400_000L

// 条件取值形态：决定输入控件与单位。
enum class ConditionValueKind(val unit: String) {
    DAYS("天"),
    MEGABYTES("MB"),
    // 单选一个 [ChatTool]，值存工具 id
    TOOL(""),
    // 自由文本，大小写不敏感的「包含」匹配
    TEXT(""),
}

// 条件类型。[label] 与取值拼成可读中文（如「未更新超过 30 天」）。
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

/**
 * 一个条件。数值型用 [number]，文本型与工具型用 [text]，
 * 同时保留两个字段是为了在 UI 上切换类型时不丢已填的输入。
 */
data class ChatCondition(
    val type: ChatConditionType,
    val number: Int = defaultNumberFor(type),
    val text: String = "",
)

// 各类型的默认数值：新加条件时给一个合理起点；填 0 天会命中全部会话。
fun defaultNumberFor(type: ChatConditionType): Int = when (type.kind) {
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

/**
 * 一条策略。[enabled] 由用户单独开关；新建时默认关闭，等用户打开后才生效。
 *
 * [name] 是列表里的唯一可读标识（列表行只显示名称），因此建策略时必填；
 * 旧配置或迁移出来的规则可能为空，读回后由 UI 用条件句子拼一行显示。
 */
data class RetentionRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = false,
    val join: ConditionJoin = ConditionJoin.AND,
    val conditions: List<ChatCondition> = emptyList(),
)

data class RetentionConfig(val rules: List<RetentionRule> = emptyList())

/**
 * 条件是否填写完整。不完整的条件不参与匹配。
 *
 * 数值下界卡在 1：「未更新超过 0 天」会命中全部会话，这类空/零输入
 * 按「还没填完」处理；算「匹配一切」会删光。
 */
fun ChatCondition.isComplete(): Boolean = when (type.kind) {
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> number >= 1
    ConditionValueKind.TOOL -> chatTools().any { it.id == text }
    ConditionValueKind.TEXT -> text.isNotBlank()
}

fun RetentionRule.effectiveConditions(): List<ChatCondition> = conditions.filter { it.isComplete() }

// 空条件会命中一切，这里挡住未填完的规则。
fun RetentionRule.isEffective(): Boolean = enabled && effectiveConditions().isNotEmpty()

// [nowMillis] 由调用方固定，保证一次判定内时间基准一致。
fun ChatCondition.matches(session: ChatSessionSummary, nowMillis: Long): Boolean = when (type) {
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

/**
 * 规则是否命中某会话。
 *
 * 没有任何完整条件时返回未命中：「且」对空条件集在逻辑上为真，
 * 照搬会把所有会话判成待删。这个检查放在这里，因为 UI 层的校验挡不住直接调用。
 */
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

// 任一规则命中即删。
fun RetentionConfig.match(
    sessions: List<ChatSessionSummary>,
    nowMillis: Long,
): List<ChatSessionSummary> =
    sessions.filter { session -> rules.any { it.matches(session, nowMillis) } }

// 生成一个不与现有规则冲突的 id。落盘按 id 定位规则，顺序变化时引用仍稳定。
fun newRuleId(existing: Collection<String>): String {
    var i = 1
    while ("rule-$i" in existing) i++
    return "rule-$i"
}
