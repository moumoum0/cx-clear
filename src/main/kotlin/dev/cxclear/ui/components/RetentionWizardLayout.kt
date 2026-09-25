package dev.cxclear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatCondition
import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ChatTool
import dev.cxclear.chats.ConditionJoin
import dev.cxclear.chats.ConditionValueKind
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 规则编辑向导的骨架：分栏滚轮式条件构造器的槽位调度、级联锚点对齐与整体位移动画。
 * 自定义 Layout + onPlaced 位移那套度量逻辑全在这里，单元格外观见 RetentionWizardCells。
 */
internal val ColumnWidth = 150.dp

internal data class RoundEntry(
    val id: Int,
    val locked: Boolean,
    val attr: AttrSpec?,
    val larger: Boolean?,
    val condition: ChatCondition?,
    val priorTypes: List<ChatConditionType>,
)

internal fun segmentedItemCenterY(index: Int, segH: Float, divH: Float): Float =
    index * (segH + divH) + segH / 2f

internal fun segmentedBlockHeight(count: Int, segH: Float, divH: Float): Float =
    if (count <= 0) 0f else count * segH + (count - 1) * divH

internal fun cascadedItemCenterY(
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

@Composable
internal fun WizardView(
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
    var valuePreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft.attr, draft.larger, draft.showCombine) {
        valuePreview = null
    }

    // RoundEntry.id 要稳：点「且」只翻 locked，别换 key。
    val initialFrozenCount = if (draft.showCombine) {
        (draft.committed.size - 1).coerceAtLeast(0)
    } else {
        draft.committed.size
    }
    val committedRoundIds = remember {
        mutableStateListOf<Int>().also { list ->
            repeat(initialFrozenCount) { list.add(it) }
        }
    }
    var buildingRoundId by remember { mutableIntStateOf(initialFrozenCount) }
    var nextRoundId by remember { mutableIntStateOf(initialFrozenCount + 1) }

    fun applyDraft(newDraft: Draft) {
        val oldSize = draft.committed.size
        val newSize = newDraft.committed.size
        if (newSize > oldSize) {
            while (committedRoundIds.size < newSize) {
                committedRoundIds.add(buildingRoundId)
            }
        } else if (newSize < oldSize) {
            while (committedRoundIds.size > newSize) {
                committedRoundIds.removeAt(committedRoundIds.lastIndex)
            }
        }
        onDraftChange(newDraft)
    }

    fun startNextRound() {
        applyDraft(
            draft.copy(join = ConditionJoin.AND, attr = null, larger = null, showCombine = false),
        )
        buildingRoundId = nextRoundId
        nextRoundId += 1
    }

    fun reopenLastRound() {
        if (committedRoundIds.isEmpty() || draft.committed.isEmpty()) return
        buildingRoundId = committedRoundIds.last()
        val (attr, larger) = pathOf(draft.committed.last())
        applyDraft(draft.copy(attr = attr, larger = larger, showCombine = true))
    }

    val frozenCount = if (draft.showCombine) {
        (draft.committed.size - 1).coerceAtLeast(0)
    } else {
        draft.committed.size
    }
    val roundEntries = buildList {
        for (index in 0 until frozenCount) {
            val condition = draft.committed[index]
            val (attr, larger) = pathOf(condition)
            add(
                RoundEntry(
                    id = committedRoundIds.getOrElse(index) { index },
                    locked = true,
                    attr = attr,
                    larger = larger,
                    condition = condition,
                    priorTypes = draft.committed.take(index).map { it.type },
                ),
            )
        }
        add(
            RoundEntry(
                id = buildingRoundId,
                locked = false,
                attr = draft.attr,
                larger = draft.larger,
                condition = if (draft.showCombine) draft.committed.lastOrNull() else null,
                priorTypes = draft.usedTypes,
            ),
        )
    }

    // 当前轮钉左边；右对齐单轮也会贴右。位移只信 onPlaced（-x），别在 layout 里重算。
    val pathShift = remember { Animatable(0f) }
    var shiftInit by remember { mutableStateOf(false) }
    var shiftTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shiftTarget) {
        if (!shiftInit) {
            pathShift.snapTo(shiftTarget)
            shiftInit = true
        } else {
            pathShift.animateTo(shiftTarget, animationSpec = Motion.medium())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WizardHeader(draft, valuePreview)
        Spacer(modifier = Modifier.height(AppDimensions.SpacingMedium.dp))
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowTopInWindow = it.localToWindow(Offset.Zero).y },
            content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp),
            ) {
                roundEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        JoinLabel(draft.join.label)
                    }
                    val isCurrent = index == roundEntries.lastIndex
                    val anchorModifier = if (isCurrent) {
                        Modifier.onPlaced { shiftTarget = -it.positionInParent().x }
                    } else {
                        Modifier
                    }
                    key(entry.id) {
                        Box(modifier = anchorModifier) {
                            RoundSlot(
                                locked = entry.locked,
                                attr = entry.attr,
                                larger = entry.larger,
                                condition = entry.condition,
                                priorTypes = entry.priorTypes,
                                draft = draft,
                                onDraftChange = ::applyDraft,
                                onCancel = onCancel,
                                onReopenLast = ::reopenLastRound,
                                onValuePreview = { valuePreview = it },
                                onSegmentHeight = onSegHeight,
                                onSelectedCenterY = { valueSelectedCenterY = it },
                                rowTopInWindow = rowTopInWindow,
                                segH = segH,
                                divH = divH,
                            )
                        }
                    }
                }
                if (draft.showCombine && !valueSelectedCenterY.isNaN()) {
                    AlignedColumn(
                        anchorCenterY = valueSelectedCenterY,
                        body = {
                            CombineColumn(
                                draft = draft,
                                onAnd = ::startNextRound,
                                onSave = onSave,
                                onSegmentHeight = onSegHeight,
                            )
                        },
                        footer = {
                            BackCell {
                                val remaining = draft.committed.dropLast(1)
                                applyDraft(draft.copy(committed = remaining, showCombine = false))
                            }
                        },
                    )
                }
            }
            },
        ) { measurables, constraints ->
            val path = measurables[0].measure(
                constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
            )
            val viewport = constraints.maxWidth
            layout(viewport, path.height) {
                path.placeRelative(pathShift.value.roundToInt(), 0)
            }
        }
    }
}

// 同槽位会换 call-site（比较符↔取值），状态得 hoist，否则每次重播入场。
internal class ColumnAnim {
    val enter = Animatable(0f)
    val anchor = Animatable(0f)
    var appeared = false
    var anchorInit = false

    fun reset() {
        appeared = false
        anchorInit = false
    }
}

@Composable
internal fun AlignedColumn(
    anchorCenterY: Float?,
    sharedAnim: ColumnAnim? = null,
    playEnter: Boolean = true,
    body: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slideFrom = with(density) { 20.dp.toPx() }
    val anim = sharedAnim ?: remember { ColumnAnim() }
    LaunchedEffect(anim, playEnter) {
        if (!playEnter || anim.appeared) {
            anim.enter.snapTo(1f)
            anim.appeared = true
        } else {
            anim.enter.snapTo(0f)
            anim.enter.animateTo(1f, animationSpec = Motion.grow())
            anim.appeared = true
        }
    }
    LaunchedEffect(anim, anchorCenterY) {
        val target = anchorCenterY ?: return@LaunchedEffect
        if (anim.anchorInit) {
            anim.anchor.animateTo(target, animationSpec = Motion.medium())
        } else {
            anim.anchor.snapTo(target)
            anim.anchorInit = true
        }
    }
    val spacing = 6.dp
    Layout(
        modifier = Modifier
            .width(ColumnWidth)
            .graphicsLayer {
                alpha = anim.enter.value
                translationX = (anim.enter.value - 1f) * slideFrom
            },
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
            (anim.anchor.value - bodyPlaceable.height / 2f).roundToInt().coerceAtLeast(0)
        } else {
            0
        }
        layout(width, contentH + yOff) {
            bodyPlaceable.placeRelative(0, yOff)
            footerPlaceable?.placeRelative(0, yOff + bodyPlaceable.height + gap)
        }
    }
}

@Composable
internal fun WizardHeader(draft: Draft, valuePreview: String? = null) {
    Text(
        draftSentence(draft, valuePreview),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextOnScrim,
    )
}

@Composable
internal fun CombineColumn(
    draft: Draft,
    onAnd: () -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
    onSegmentHeight: (Float) -> Unit,
) {
    val canAddMore = ATTRS.any { spec -> spec.types.any { it !in draft.allTypes } }

    WizSegmented(
        options = buildList {
            if (canAddMore) {
                add(WizOption("且", onClick = onAnd))
            }
            add(WizOption("保存") { onSave(draft.committed, draft.join) })
        },
        onSegmentHeight = onSegmentHeight,
    )
}

@Composable
internal fun JoinLabel(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        color = AppColors.TextOnScrim,
        modifier = Modifier.padding(top = 13.dp),
    )
}

@Composable
internal fun RoundSlot(
    locked: Boolean,
    attr: AttrSpec?,
    larger: Boolean?,
    condition: ChatCondition?,
    priorTypes: List<ChatConditionType>,
    draft: Draft,
    onDraftChange: (Draft) -> Unit,
    onCancel: () -> Unit,
    onReopenLast: () -> Unit,
    onValuePreview: (String?) -> Unit,
    onSegmentHeight: (Float) -> Unit,
    onSelectedCenterY: (Float) -> Unit,
    rowTopInWindow: Float,
    segH: Float,
    divH: Float,
) {
    val col1Anim = remember { ColumnAnim() }
    LaunchedEffect(attr == null) {
        if (attr == null) col1Anim.reset()
    }

    val attrOptions = ATTRS.filter { spec -> spec.types.any { it !in priorTypes } }
    val attrIndex = attrOptions.indexOfFirst { it == attr }
    val attrCenterY = if (attrIndex >= 0) segmentedItemCenterY(attrIndex, segH, divH) else null
    val depth = when {
        attr == null -> 0
        condition != null -> if (attr.isNumeric) 3 else 2
        attr.isNumeric && larger == null -> 1
        attr.isNumeric -> 2
        else -> 1
    }
    fun colEditable(columnIndex: Int): Boolean =
        !locked && columnIndex >= depth - 1

    val roundWeight = if (locked) WizWeight.DIMMED else WizWeight.ACTIVE

    Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp)) {
        WizColumn {
            WizSegmented(
                options = attrOptions.map { spec ->
                    WizOption(
                        spec.label,
                        selected = attr == spec,
                        enabled = colEditable(0),
                    ) {
                        onDraftChange(draft.copy(attr = spec, larger = null, showCombine = false))
                    }
                },
                weight = roundWeight,
                onSegmentHeight = onSegmentHeight,
            )
            if (!locked && attr == null) {
                BackCell {
                    if (draft.committed.isEmpty()) {
                        onCancel()
                    } else {
                        onReopenLast()
                    }
                }
            }
        }

        val currentAttr = attr ?: return@Row

        if (currentAttr.isNumeric) {
            val compOptions = buildList {
                if (currentAttr.larger !in priorTypes) add(true)
                if (currentAttr.smaller !in priorTypes) add(false)
            }
            val compIndex = compOptions.indexOf(larger)
            AlignedColumn(
                anchorCenterY = attrCenterY,
                sharedAnim = col1Anim,
                body = {
                    WizSegmented(
                        options = compOptions.map { isLarger ->
                            WizOption(
                                if (isLarger) "超过" else "少于",
                                selected = larger == isLarger,
                                enabled = colEditable(1),
                            ) {
                                onDraftChange(draft.copy(larger = isLarger, showCombine = false))
                            }
                        },
                        weight = roundWeight,
                        onSegmentHeight = onSegmentHeight,
                    )
                },
                footer = if (!locked && larger == null) {
                    {
                        BackCell {
                            onDraftChange(draft.copy(attr = null, larger = null, showCombine = false))
                        }
                    }
                } else {
                    null
                },
            )
            if (larger != null && compIndex >= 0 && attrCenterY != null) {
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
                        RoundValueColumn(
                            attr = currentAttr,
                            larger = larger,
                            condition = condition,
                            locked = locked,
                            priorCommitted = if (condition != null) {
                                draft.committed.dropLast(1)
                            } else {
                                draft.committed
                            },
                            draft = draft,
                            onDraftChange = onDraftChange,
                            onValuePreview = onValuePreview,
                            onSegmentHeight = onSegmentHeight,
                            onSelectedCenterY = onSelectedCenterY,
                            rowTopInWindow = rowTopInWindow,
                        )
                    },
                    footer = if (!locked && condition == null) {
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
                sharedAnim = col1Anim,
                body = {
                    RoundValueColumn(
                        attr = currentAttr,
                        larger = larger,
                        condition = condition,
                        locked = locked,
                        priorCommitted = if (condition != null) {
                            draft.committed.dropLast(1)
                        } else {
                            draft.committed
                        },
                        draft = draft,
                        onDraftChange = onDraftChange,
                        onValuePreview = onValuePreview,
                        onSegmentHeight = onSegmentHeight,
                        onSelectedCenterY = onSelectedCenterY,
                        rowTopInWindow = rowTopInWindow,
                    )
                },
                footer = if (!locked && condition == null) {
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
}

@Composable
internal fun RoundValueColumn(
    attr: AttrSpec,
    larger: Boolean?,
    condition: ChatCondition?,
    locked: Boolean,
    priorCommitted: List<ChatCondition>,
    draft: Draft,
    onDraftChange: (Draft) -> Unit,
    onValuePreview: (String?) -> Unit,
    onSegmentHeight: (Float) -> Unit,
    onSelectedCenterY: (Float) -> Unit,
    rowTopInWindow: Float,
) {
    val type = attr.typeFor(larger)
    val weight = if (locked) WizWeight.DIMMED else WizWeight.ACTIVE

    fun commit(next: ChatCondition) {
        if (locked) return
        onValuePreview(null)
        onDraftChange(draft.copy(committed = priorCommitted + next, showCombine = true))
    }

    val chosen = condition
    val presets = presetsFor(attr.kind)
    val customNumberSelected = chosen != null && attr.isNumeric && chosen.number !in presets

    fun reportCenter(coords: LayoutCoordinates) {
        if (locked || rowTopInWindow.isNaN() || !coords.isAttached) return
        val centerInWindow = coords.localToWindow(Offset(0f, coords.size.height / 2f)).y
        onSelectedCenterY(centerInWindow - rowTopInWindow)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (attr.kind) {
            ConditionValueKind.MEGABYTES, ConditionValueKind.DAYS -> {
                // 锁定也留自选行，不然点「且」高度会跳。
                val numberOptions = buildList {
                    addAll(presets)
                    if (chosen != null && chosen.number !in presets) add(chosen.number)
                }
                WizSegmented(
                    options = numberOptions.map { n ->
                        WizOption(
                            "$n ${attr.kind.unit}",
                            selected = chosen?.number == n,
                            enabled = !locked,
                        ) {
                            commit(ChatCondition(type = type, number = n))
                        }
                    },
                    weight = weight,
                    onSegmentHeight = onSegmentHeight,
                    onSelectedCoords = { reportCenter(it) },
                )
                key(type) {
                    CustomNumberCell(
                        unit = attr.kind.unit,
                        editable = !locked,
                        reportCoords = customNumberSelected && !locked,
                        onCoords = { reportCenter(it) },
                        onPreview = onValuePreview,
                    ) { n ->
                        commit(ChatCondition(type = type, number = n))
                    }
                }
            }

            ConditionValueKind.TOOL -> WizSegmented(
                options = ChatTool.entries.map { tool ->
                    WizOption(
                        tool.displayName,
                        selected = chosen?.text == tool.id,
                        enabled = !locked,
                    ) {
                        commit(ChatCondition(type = type, text = tool.id))
                    }
                },
                weight = weight,
                onSegmentHeight = onSegmentHeight,
                onSelectedCoords = { reportCenter(it) },
            )

            ConditionValueKind.TEXT -> {
                if (locked && chosen != null) {
                    WizSegmented(
                        options = listOf(
                            WizOption("「${chosen.text}」", selected = true, enabled = false) {},
                        ),
                        weight = weight,
                    )
                } else {
                    key(type) {
                        CustomTextCell(
                            reportCoords = chosen != null,
                            onCoords = { reportCenter(it) },
                            onPreview = onValuePreview,
                        ) { text ->
                            commit(ChatCondition(type = type, text = text))
                        }
                    }
                }
            }
        }
    }
}
