package dev.cxclear.ui.components

import dev.cxclear.chats.ChatCondition
import dev.cxclear.tools.chatTools
import dev.cxclear.chats.ConditionValueKind
import dev.cxclear.chats.RetentionRule

/**
 * 自动清理策略的中文文案生成：已存规则的整句描述，以及编辑中草稿的实时预览句。
 * 纯字符串拼接，无 Compose 依赖。
 */
internal fun ruleSentence(rule: RetentionRule): String {
    if (rule.conditions.isEmpty()) return "无条件"
    val parts = rule.conditions.map { readableCondition(it) }
    return parts.joinToString(" ${rule.join.label} ")
}

internal fun readableCondition(c: ChatCondition): String = when (c.type.kind) {
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> "${c.type.label} ${c.number} ${c.type.kind.unit}"
    ConditionValueKind.TOOL ->
        "${c.type.label} ${chatTools().firstOrNull { it.id == c.text }?.displayName ?: c.text}"
    ConditionValueKind.TEXT -> "${c.type.label}「${c.text}」"
}

internal fun draftSentence(draft: Draft, valuePreview: String? = null): String {
    val finished = if (draft.showCombine) draft.committed.dropLast(1) else draft.committed
    val tail = when {
        draft.showCombine && valuePreview != null && draft.attr != null ->
            pendingFragment(draft.attr, draft.larger, valuePreview)
        draft.showCombine -> draft.committed.lastOrNull()?.let { readableCondition(it) }
        else -> pendingFragment(draft.attr, draft.larger, valuePreview)
    }
    val parts = finished.map { readableCondition(it) } + listOfNotNull(tail)
    if (parts.isEmpty()) return "删除"
    val body = "删除 " + parts.joinToString(" ${draft.join.label} ")
    val awaitingNext = !draft.showCombine && finished.isNotEmpty() && tail == null
    return if (awaitingNext) "$body ${draft.join.label}" else body
}

internal fun pendingFragment(attr: AttrSpec?, larger: Boolean?, valuePreview: String?): String? {
    if (attr == null) return null
    if (attr.isNumeric && larger == null) return attr.label
    val type = attr.typeFor(larger)
    val preview = valuePreview?.trim().orEmpty()
    return when (type.kind) {
        ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> {
            val n = preview.toIntOrNull()
            if (n != null && n >= 1) "${type.label} $n ${type.kind.unit}"
            else type.label
        }
        ConditionValueKind.TOOL -> {
            val tool = chatTools().firstOrNull { it.id == preview || it.displayName == preview }
            if (tool != null) "${type.label} ${tool.displayName}" else type.label
        }
        ConditionValueKind.TEXT -> {
            if (preview.isNotEmpty()) "${type.label}「$preview」" else type.label
        }
    }
}
