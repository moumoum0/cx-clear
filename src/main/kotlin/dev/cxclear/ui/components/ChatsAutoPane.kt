package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatCondition
import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ChatTool
import dev.cxclear.chats.ConditionJoin
import dev.cxclear.chats.ConditionValueKind
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionRule
import dev.cxclear.chats.newRuleId
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 自动清理：策略列表 + 点击式创建向导。
 *
 * 列表里每条策略是一句只读的话（开关 + 句子 + 删除）；改动即时落盘（[onConfigChange]），
 * 没有「保存」按钮。新建走全屏分步向导——每步只列几个可点选项，当前最右列末尾一个「返回」上一层，
 * 一路点到「保存」才把这条策略落进列表，中途「返回」不改动已有策略。
 */
@Composable
internal fun ChatsAutoPane(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = 列表态；非空 = 正在向导里建一条新策略。
    var draft by remember { mutableStateOf<Draft?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        val current = draft
        if (current == null) {
            RuleListView(
                config = config,
                onConfigChange = onConfigChange,
                onNewRule = { draft = Draft() },
            )
        } else {
            WizardView(
                draft = current,
                onDraftChange = { draft = it },
                onCancel = { draft = null },
                onSave = { conditions, join ->
                    val rule = RetentionRule(
                        id = newRuleId(config.rules.map { it.id }),
                        enabled = false,
                        join = join,
                        conditions = conditions,
                    )
                    onConfigChange(config.copy(rules = config.rules + rule))
                    draft = null
                },
            )
        }
    }
}

// ─────────────────────────────────────────────
// 属性 → 具体条件类型的映射（向导用）
// ─────────────────────────────────────────────

/**
 * 一个可选「属性」。数值属性有「超过 / 少于」两个比较符，各对应一个具体 [ChatConditionType]；
 * 工具 / 文本属性没有比较符，[direct] 直接就是类型。
 */
private data class AttrSpec(
    val label: String,
    val kind: ConditionValueKind,
    val larger: ChatConditionType? = null,
    val smaller: ChatConditionType? = null,
    val direct: ChatConditionType? = null,
) {
    /** 这个属性用到的全部具体类型。 */
    val types: List<ChatConditionType> = listOfNotNull(larger, smaller, direct)
}

private val ATTRS = listOf(
    AttrSpec("大小", ConditionValueKind.MEGABYTES,
        larger = ChatConditionType.SIZE_LARGER_MB, smaller = ChatConditionType.SIZE_SMALLER_MB),
    AttrSpec("未更新", ConditionValueKind.DAYS,
        larger = ChatConditionType.UPDATED_BEFORE_DAYS, smaller = ChatConditionType.UPDATED_WITHIN_DAYS),
    AttrSpec("所属工具", ConditionValueKind.TOOL, direct = ChatConditionType.TOOL_IS),
    AttrSpec("项目名", ConditionValueKind.TEXT, direct = ChatConditionType.PROJECT_CONTAINS),
    AttrSpec("标题", ConditionValueKind.TEXT, direct = ChatConditionType.TITLE_CONTAINS),
)

private fun attrFor(type: ChatConditionType): AttrSpec = ATTRS.first { type in it.types }

/** 数值属性的取值预设，省得每次都手打；末尾仍留「自定义」。 */
private fun presetsFor(kind: ConditionValueKind): List<Int> = when (kind) {
    ConditionValueKind.MEGABYTES -> listOf(1, 5, 10)
    ConditionValueKind.DAYS -> listOf(7, 30, 90)
    else -> emptyList()
}

// ─────────────────────────────────────────────
// 只读句子
// ─────────────────────────────────────────────

/** 一条已建好的策略读成一句话：「删除 未更新超过 30 天 且 大小超过 10 MB」。 */
private fun ruleSentence(rule: RetentionRule): String {
    if (rule.conditions.isEmpty()) return "删除（无条件）"
    val parts = rule.conditions.map { readableCondition(it) }
    return "删除 " + parts.joinToString(" ${rule.join.label} ")
}

private fun readableCondition(c: ChatCondition): String = when (c.type.kind) {
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> "${c.type.label} ${c.number} ${c.type.kind.unit}"
    ConditionValueKind.TOOL ->
        "${c.type.label} ${ChatTool.entries.firstOrNull { it.id == c.text }?.displayName ?: c.text}"
    ConditionValueKind.TEXT -> "${c.type.label}「${c.text}」"
}

// ─────────────────────────────────────────────
// 列表态：策略读成句子 + 新建入口
// ─────────────────────────────────────────────

@Composable
private fun RuleListView(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    onNewRule: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (config.rules.isEmpty()) {
            EmptyRuleList(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            ) {
                items(
                    count = config.rules.size,
                    key = { index -> config.rules[index].id },
                ) { index ->
                    val rule = config.rules[index]
                    RuleRow(
                        rule = rule,
                        onToggle = { on ->
                            onConfigChange(
                                config.copy(
                                    rules = config.rules.map {
                                        if (it.id == rule.id) it.copy(enabled = on) else it
                                    }
                                )
                            )
                        },
                        onDelete = {
                            onConfigChange(
                                config.copy(rules = config.rules.filterNot { it.id == rule.id })
                            )
                        },
                    )
                }
            }
        }
        AddRuleButton(onClick = onNewRule)
    }
}

/** 一条只读策略：左开关，中间一句话，右删除。改条件要删了重建（向导里点更快）。 */
@Composable
private fun RuleRow(
    rule: RetentionRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.OnPrimary,
                checkedTrackColor = AppColors.Primary,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            ruleSentence(rule),
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "删除策略",
            tint = AppColors.TextTertiary,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDelete),
        )
    }
}

// ─────────────────────────────────────────────
// 向导态：一步一屏，点着走
// ─────────────────────────────────────────────

/**
 * 正在建的策略草稿。[committed] 是已定好的条件，[attr] / [larger] 是「正在拼的这一条」选到哪。
 *
 * 向导一路点到「保存」才把它变成一条真正的 [RetentionRule]，中途「返回」不碰已有策略。
 */
private data class Draft(
    val committed: List<ChatCondition> = emptyList(),
    val join: ConditionJoin = ConditionJoin.AND,
    val attr: AttrSpec? = null,
    val larger: Boolean? = null,
    /** 取值已定，右侧展开「且 / 或 / 保存 / 返回」那一列。 */
    val showCombine: Boolean = false,
) {
    /**
     * 已被别的条件占用、不该再出现在列里的类型。
     *
     * 正在改的那一条不算占用——[showCombine] 为真时 [committed] 末尾就是它，
     * 若把它也算进去，它自己的属性与比较符会从列里消失，回头就改不成了。
     */
    val usedTypes: List<ChatConditionType>
        get() = (if (showCombine) committed.dropLast(1) else committed).map { it.type }

    /** 真正已占用的全部类型，用于判断「还有没有下一个条件可加」。 */
    val allTypes: List<ChatConditionType> get() = committed.map { it.type }
}

/** 数值属性才有「超过 / 少于」两个比较符，也才需要中间那一列。 */
private val AttrSpec.isNumeric: Boolean
    get() = kind == ConditionValueKind.MEGABYTES || kind == ConditionValueKind.DAYS

private fun AttrSpec.typeFor(larger: Boolean?): ChatConditionType =
    if (isNumeric) (if (larger == true) this.larger!! else smaller!!) else direct!!

/** 从一个已定条件反推它当初的选择路径，用于「返回」时把左边几列原样点回来。 */
private fun pathOf(condition: ChatCondition): Pair<AttrSpec, Boolean?> {
    val attr = attrFor(condition.type)
    return attr to if (attr.isNumeric) condition.type == attr.larger else null
}

private val ColumnWidth = 150.dp

private fun segmentedItemCenterY(index: Int, segH: Float, divH: Float): Float =
    index * (segH + divH) + segH / 2f

private fun segmentedBlockHeight(count: Int, segH: Float, divH: Float): Float =
    if (count <= 0) 0f else count * segH + (count - 1) * divH

private fun cascadedItemCenterY(
    anchorCenterY: Float,
    blockItemCount: Int,
    itemIndex: Int,
    segH: Float,
    divH: Float,
): Float {
    val blockH = segmentedBlockHeight(blockItemCount, segH, divH)
    val offsetY = (anchorCenterY - blockH / 2f).coerceAtLeast(0f)
    return offsetY + segmentedItemCenterY(itemIndex, segH, divH)
}

/**
 * 创建向导：从左到右一列列展开。
 *
 * 属性列 → （数值属性才有的）比较符列 → 取值列 → 且 / 或 / 保存 列。
 * 选过的项高亮留在原位，整条路径一直看得见；只有当前最右一列末尾留「返回」，
 * 右边一旦展开下一列，左边那列的「返回」就撤掉。
 * 下一列选项区（不含「返回」）相对上一列选中项中线垂直居中；顶边若会越过行顶则贴顶。
 * 锚点用选中下标同步计算，Layout 同帧落位，项数变化不跳。
 */
@Composable
private fun WizardView(
    draft: Draft,
    onDraftChange: (Draft) -> Unit,
    onCancel: () -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
) {
    val density = LocalDensity.current
    var measuredSegH by remember { mutableFloatStateOf(0f) }
    val divH = with(density) { 1.dp.toPx() }
    val segH = if (measuredSegH > 0f) measuredSegH else with(density) { 44.dp.toPx() }
    val onSegHeight: (Float) -> Unit = { h ->
        if (h > 0f && measuredSegH == 0f) measuredSegH = h
    }
    var valueSelectedCenterY by remember { mutableFloatStateOf(Float.NaN) }
    var rowTopInWindow by remember { mutableFloatStateOf(Float.NaN) }

    val attrOptions = ATTRS.filter { spec -> spec.types.any { it !in draft.usedTypes } }
    val attrIndex = attrOptions.indexOfFirst { it == draft.attr }
    val attrCenterY = if (attrIndex >= 0) segmentedItemCenterY(attrIndex, segH, divH) else null

    Column(modifier = Modifier.fillMaxSize()) {
        WizardHeader(draft)
        Spacer(Modifier.height(AppDimensions.SpacingMedium.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .onGloballyPositioned { rowTopInWindow = it.localToWindow(Offset.Zero).y },
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp),
        ) {
            AttrColumn(
                draft = draft,
                options = attrOptions,
                onDraftChange = onDraftChange,
                onCancel = onCancel,
                onSegmentHeight = onSegHeight,
            )
            draft.attr?.let { attr ->
                if (attr.isNumeric) {
                    val compOptions = buildList {
                        if (attr.larger !in draft.usedTypes) add(true)
                        if (attr.smaller !in draft.usedTypes) add(false)
                    }
                    val compIndex = compOptions.indexOf(draft.larger)
                    AlignedColumn(
                        anchorCenterY = attrCenterY,
                        body = {
                            ComparatorColumn(
                                draft = draft,
                                attr = attr,
                                onDraftChange = onDraftChange,
                                onSegmentHeight = onSegHeight,
                            )
                        },
                        footer = if (draft.larger == null) {
                            {
                                BackCell {
                                    onDraftChange(draft.copy(attr = null, larger = null, showCombine = false))
                                }
                            }
                        } else {
                            null
                        },
                    )
                    if (draft.larger != null && compIndex >= 0 && attrCenterY != null) {
                        val compCenterY = cascadedItemCenterY(
                            anchorCenterY = attrCenterY,
                            blockItemCount = compOptions.size,
                            itemIndex = compIndex,
                            segH = segH,
                            divH = divH,
                        )
                        AlignedColumn(
                            anchorCenterY = compCenterY,
                            body = {
                                ValueColumn(
                                    draft = draft,
                                    attr = attr,
                                    onDraftChange = onDraftChange,
                                    onSegmentHeight = onSegHeight,
                                    onSelectedCenterY = { valueSelectedCenterY = it },
                                    rowTopInWindow = rowTopInWindow,
                                )
                            },
                            footer = if (!draft.showCombine) {
                                {
                                    BackCell {
                                        onDraftChange(draft.copy(larger = null, showCombine = false))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    AlignedColumn(
                        anchorCenterY = attrCenterY,
                        body = {
                            ValueColumn(
                                draft = draft,
                                attr = attr,
                                onDraftChange = onDraftChange,
                                onSegmentHeight = onSegHeight,
                                onSelectedCenterY = { valueSelectedCenterY = it },
                                rowTopInWindow = rowTopInWindow,
                            )
                        },
                        footer = if (!draft.showCombine) {
                            {
                                BackCell {
                                    onDraftChange(draft.copy(attr = null, showCombine = false))
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            if (draft.showCombine && !valueSelectedCenterY.isNaN()) {
                AlignedColumn(
                    anchorCenterY = valueSelectedCenterY,
                    body = {
                        CombineColumn(draft, onDraftChange, onSave, onSegHeight)
                    },
                    footer = {
                        BackCell {
                            val remaining = draft.committed.dropLast(1)
                            onDraftChange(draft.copy(committed = remaining, showCombine = false))
                        }
                    },
                )
            }
        }
    }
}

/**
 * 相对锚点（上一列选中项中线）垂直居中 [body]；[footer]（返回）不参与居中。
 * Layout 同帧测量并落位，无位移动画。
 */
@Composable
private fun AlignedColumn(
    anchorCenterY: Float?,
    body: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    // 同帧测量 body 并落位，不做位移动画；项数变化时高度与偏移一次算清。
    val spacing = 6.dp
    Layout(
        modifier = Modifier.width(ColumnWidth),
        content = {
            Box(modifier = Modifier.fillMaxWidth()) { body() }
            if (footer != null) {
                Box(modifier = Modifier.fillMaxWidth()) { footer() }
            }
        },
    ) { measurables, constraints ->
        val bodyPlaceable = measurables[0].measure(constraints)
        val footerPlaceable = measurables.getOrNull(1)?.measure(constraints)
        val gap = if (footerPlaceable != null) spacing.roundToPx() else 0
        val width = max(bodyPlaceable.width, footerPlaceable?.width ?: 0)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val contentH = bodyPlaceable.height + gap + (footerPlaceable?.height ?: 0)
        val yOff = if (anchorCenterY != null) {
            (anchorCenterY - bodyPlaceable.height / 2f).roundToInt().coerceAtLeast(0)
        } else {
            0
        }
        layout(width, contentH + yOff) {
            bodyPlaceable.placeRelative(0, yOff)
            footerPlaceable?.placeRelative(0, yOff + bodyPlaceable.height + gap)
        }
    }
}

/** 顶部一句话交代已定到哪；还没定条件时只写「删除…」，不写「请选择」这类空话。 */
@Composable
private fun WizardHeader(draft: Draft) {
    val sentence = if (draft.committed.isEmpty()) {
        "删除…"
    } else {
        "删除 " + draft.committed.joinToString(" ${draft.join.label} ") { readableCondition(it) }
    }
    Text(
        sentence,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextPrimary,
    )
}

/** 第一列：属性。类型已被用光的属性不再列出。 */
@Composable
private fun AttrColumn(
    draft: Draft,
    options: List<AttrSpec>,
    onDraftChange: (Draft) -> Unit,
    onCancel: () -> Unit,
    onSegmentHeight: (Float) -> Unit,
) {
    WizColumn {
        WizSegmented(
            options = options.map { attr ->
                WizOption(attr.label, selected = draft.attr == attr) {
                    // 换属性要清掉右边选过的比较符，否则会带着上一属性的选择往下走。
                    onDraftChange(draft.copy(attr = attr, larger = null, showCombine = false))
                }
            },
            onSegmentHeight = onSegmentHeight,
        )
        // 属性已点出下一列时，这一列不再留「返回」。
        if (draft.attr == null) {
            BackCell {
                if (draft.committed.isEmpty()) {
                    onCancel()
                } else {
                    // 已有定好的条件：退回上一条的组合列，并把它的路径点回来。
                    val (attr, larger) = pathOf(draft.committed.last())
                    onDraftChange(draft.copy(attr = attr, larger = larger, showCombine = true))
                }
            }
        }
    }
}

/** 第二列：超过 / 少于。已用过的那半边不再列出（同属性同方向不重复加）。 */
@Composable
private fun ComparatorColumn(
    draft: Draft,
    attr: AttrSpec,
    onDraftChange: (Draft) -> Unit,
    onSegmentHeight: (Float) -> Unit,
) {
    // 只渲染选项；「返回」由 AlignedColumn.footer 挂在外面，不进对齐高度。
    WizSegmented(
        options = buildList {
            if (attr.larger !in draft.usedTypes) {
                add(WizOption("超过", selected = draft.larger == true) {
                    onDraftChange(draft.copy(larger = true, showCombine = false))
                })
            }
            if (attr.smaller !in draft.usedTypes) {
                add(WizOption("少于", selected = draft.larger == false) {
                    onDraftChange(draft.copy(larger = false, showCombine = false))
                })
            }
        },
        onSegmentHeight = onSegmentHeight,
    )
}

/**
 * 第三列：取值。数值属性给几个预设 + 自定义输入；工具属性列工具；文本属性一个输入框。
 * 点定一个值就把这条条件并进 [Draft.committed]，右边展开组合列。
 */
@Composable
private fun ValueColumn(
    draft: Draft,
    attr: AttrSpec,
    onDraftChange: (Draft) -> Unit,
    onSegmentHeight: (Float) -> Unit,
    onSelectedCenterY: (Float) -> Unit,
    rowTopInWindow: Float,
) {
    val type = attr.typeFor(draft.larger)
    // 已定好的这一条就是 committed 的末尾：重选取值应替换它，而不是再加一条。
    val replacing = draft.showCombine

    fun commit(condition: ChatCondition) {
        val base = if (replacing) draft.committed.dropLast(1) else draft.committed
        onDraftChange(draft.copy(committed = base + condition, showCombine = true))
    }

    val chosen = if (replacing) draft.committed.lastOrNull() else null
    val presets = presetsFor(attr.kind)
    val customNumberSelected = chosen != null && attr.isNumeric && chosen.number !in presets

    fun reportCenter(coords: LayoutCoordinates) {
        if (rowTopInWindow.isNaN() || !coords.isAttached) return
        val centerInWindow = coords.localToWindow(Offset(0f, coords.size.height / 2f)).y
        onSelectedCenterY(centerInWindow - rowTopInWindow)
    }

    // 只渲染取值区；「返回」由 AlignedColumn.footer 挂在外面，不进对齐高度。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (attr.kind) {
            ConditionValueKind.MEGABYTES, ConditionValueKind.DAYS -> {
                WizSegmented(
                    options = presets.map { n ->
                        WizOption("$n ${attr.kind.unit}", selected = chosen?.number == n) {
                            commit(ChatCondition(type = type, number = n))
                        }
                    },
                    onSegmentHeight = onSegmentHeight,
                    onSelectedCoords = { reportCenter(it) },
                )
                CustomNumberCell(
                    unit = attr.kind.unit,
                    reportCoords = customNumberSelected,
                    onCoords = { reportCenter(it) },
                ) { n ->
                    commit(ChatCondition(type = type, number = n))
                }
            }

            ConditionValueKind.TOOL -> WizSegmented(
                options = ChatTool.entries.map { tool ->
                    WizOption(tool.displayName, selected = chosen?.text == tool.id) {
                        commit(ChatCondition(type = type, text = tool.id))
                    }
                },
                onSegmentHeight = onSegmentHeight,
                onSelectedCoords = { reportCenter(it) },
            )

            ConditionValueKind.TEXT -> CustomTextCell(
                reportCoords = chosen != null,
                onCoords = { reportCenter(it) },
            ) { text ->
                commit(ChatCondition(type = type, text = text))
            }
        }
    }
}

/**
 * 末列：这条条件已定完，选下一步——且 / 或（定下整条的连接词，回左边加下一个条件）、保存、返回。
 *
 * 「返回」把刚定的这条撤掉：这一列是它带出来的，退回去自然该连它一起退。
 */
@Composable
private fun CombineColumn(
    draft: Draft,
    onDraftChange: (Draft) -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
    onSegmentHeight: (Float) -> Unit,
) {
    // 所有类型都用过了就没有「下一个条件」可加，不留点了没反应的入口。
    // 这里按 allTypes 算：刚定好的这一条确实占了一个类型。
    val canAddMore = ATTRS.any { spec -> spec.types.any { it !in draft.allTypes } }

    // 只渲染且/或/保存；「返回」由 AlignedColumn.footer 挂在外面，不进对齐高度。
    WizSegmented(
        options = buildList {
            if (canAddMore) {
                add(WizOption("且", selected = draft.join == ConditionJoin.AND && draft.committed.size > 1) {
                    onDraftChange(
                        draft.copy(join = ConditionJoin.AND, attr = null, larger = null, showCombine = false)
                    )
                })
                add(WizOption("或", selected = draft.join == ConditionJoin.OR && draft.committed.size > 1) {
                    onDraftChange(
                        draft.copy(join = ConditionJoin.OR, attr = null, larger = null, showCombine = false)
                    )
                })
            }
            add(WizOption("保存", emphasize = true) { onSave(draft.committed, draft.join) })
        },
        onSegmentHeight = onSegmentHeight,
    )
}

// ─────────────────────────────────────────────
// 向导的一列 + 竖向连体分段
// ─────────────────────────────────────────────

/** 一列：固定宽度竖着排。宽度定死，列多了整行横向滚动，不让某列被挤窄。 */
@Composable
private fun WizColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.width(ColumnWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** 分段选项：文字 + 选中态。整列拼成一块连体控件，不再各自成卡。 */
private data class WizOption(
    val label: String,
    val selected: Boolean = false,
    val emphasize: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 竖向连体分段，视觉对齐顶栏手动/自动切换：
 * 共享一圈描边、未选段浅底、选中段主色填充、段间分隔线；仍是文字、仍是竖排。
 */
@Composable
private fun WizSegmented(
    options: List<WizOption>,
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, AppColors.OutlineVariant), shape)
            .background(AppColors.Surface3),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AppColors.OutlineVariant),
                )
            }
            WizSegment(
                option = option,
                onSegmentHeight = if (index == 0) onSegmentHeight else null,
                onSelectedCoords = onSelectedCoords,
            )
        }
    }
}

@Composable
private fun WizSegment(
    option: WizOption,
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    val active = option.emphasize || option.selected
    val bg by animateColorAsState(
        targetValue = if (active) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "wizSegBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) AppColors.OnPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "wizSegFg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onSegmentHeight?.invoke(coords.size.height.toFloat())
                if (option.selected) onSelectedCoords?.invoke(coords)
            }
            .background(bg)
            .clickable(onClick = option.onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            option.label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = fg,
        )
    }
}

/** 每列末尾的「返回」：无底色、带左箭头，视觉上比选项弱一档。 */
@Composable
private fun BackCell(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Text("返回", fontSize = 13.sp, color = AppColors.TextSecondary)
    }
}

/** 自选输入（数值）：输入框 + 单位 + 确认。空/零不提交（`isComplete()` 也会兜底）。 */
@Composable
private fun CustomNumberCell(
    unit: String,
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val n = input.toIntOrNull() ?: 0
    val enabled = n >= 1
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            }
            .clip(shape)
            .border(BorderStroke(1.dp, AppColors.OutlineVariant), shape)
            .background(AppColors.Surface3)
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (input.isEmpty()) {
                Text("自选输入", fontSize = 13.sp, color = AppColors.TextTertiary)
            }
            BasicTextField(
                value = input,
                onValueChange = { raw ->
                    if (raw.all { it.isDigit() } && raw.length <= 5) input = raw
                },
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = AppColors.Primary,
                    fontWeight = FontWeight.Medium,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                cursorBrush = SolidColor(AppColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (input.isNotEmpty()) {
            Text(unit, fontSize = 13.sp, color = AppColors.TextSecondary)
        }
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "确认",
            tint = if (enabled) AppColors.Primary else AppColors.TextTertiary,
            modifier = Modifier
                .size(17.dp)
                .clickable(enabled = enabled) { onConfirm(n.coerceIn(1, 99999)) },
        )
    }
}

/** 自选输入（文本）：输入框 + 确认；空白不提交。 */
@Composable
private fun CustomTextCell(
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val enabled = input.isNotBlank()
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            }
            .clip(shape)
            .border(BorderStroke(1.dp, AppColors.OutlineVariant), shape)
            .background(AppColors.Surface3)
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (input.isEmpty()) {
                Text("关键词", fontSize = 13.sp, color = AppColors.TextTertiary)
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it.take(60) },
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = AppColors.Primary,
                    fontWeight = FontWeight.Medium,
                ),
                singleLine = true,
                cursorBrush = SolidColor(AppColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "确认",
            tint = if (enabled) AppColors.Primary else AppColors.TextTertiary,
            modifier = Modifier
                .size(17.dp)
                .clickable(enabled = enabled) { onConfirm(input.trim()) },
        )
    }
}

// ─────────────────────────────────────────────
// 空态 + 新建入口
// ─────────────────────────────────────────────

@Composable
private fun EmptyRuleList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        // 骨架与真实策略行同形：开关 + 一行句子，数据到位时原地填充。
        repeat(2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                    .background(AppColors.Surface2)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(width = 34.dp, height = 18.dp)
                Spacer(Modifier.width(12.dp))
                SkeletonBox(width = 180.dp, height = 18.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBox(width: Dp, height: Dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(AppColors.Surface3),
    )
}

@Composable
private fun AddRuleButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppDimensions.SpacingSmall.dp)
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = AppColors.Primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("新建策略", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.Primary)
    }
}


