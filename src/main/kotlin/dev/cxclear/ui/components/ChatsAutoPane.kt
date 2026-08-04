package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import dev.cxclear.ui.LocalOverlayHost
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.ui.theme.appOutlinedTextFieldColors
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun ChatsAutoPane(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlayHost = LocalOverlayHost.current
    var namingForNew by remember { mutableStateOf(false) }

    fun openWizard(
        ruleName: String,
        initial: RetentionRule? = null,
        onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
    ) {
        overlayHost.show {
            WizardOverlay(
                ruleName = ruleName,
                initialConditions = initial?.conditions.orEmpty(),
                initialJoin = initial?.join ?: ConditionJoin.AND,
                onDismiss = { overlayHost.hide() },
                onSave = onSave,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        RuleListView(
            config = config,
            onConfigChange = onConfigChange,
            onNewRule = { namingForNew = true },
            onEditRule = { rule ->
                openWizard(ruleName = rule.name.ifBlank { "未命名策略" }, initial = rule) { conditions, join ->
                    onConfigChange(
                        config.copy(
                            rules = config.rules.map {
                                if (it.id == rule.id) it.copy(conditions = conditions, join = join) else it
                            },
                        ),
                    )
                    overlayHost.hide()
                }
            },
        )
    }

    if (namingForNew) {
        NameRuleDialog(
            onDismiss = { namingForNew = false },
            onConfirm = { name ->
                namingForNew = false
                openWizard(ruleName = name) { conditions, join ->
                    val rule = RetentionRule(
                        id = newRuleId(config.rules.map { it.id }),
                        name = name,
                        enabled = false,
                        join = join,
                        conditions = conditions,
                    )
                    onConfigChange(config.copy(rules = config.rules + rule))
                    overlayHost.hide()
                }
            },
        )
    }
}

@Composable
private fun NameRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val trimmed = input.trim()
    val enabled = trimmed.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface1,
        title = {
            Text(
                "给这条策略起个名字",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(30) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("策略名称") },
                shape = RoundedCornerShape(AppDimensions.Radius.dp),
                colors = appOutlinedTextFieldColors(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (enabled) onConfirm(trimmed) },
                enabled = enabled,
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary,
                ),
            ) {
                Text("下一步")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun WizardOverlay(
    ruleName: String,
    initialConditions: List<ChatCondition> = emptyList(),
    initialJoin: ConditionJoin = ConditionJoin.AND,
    onDismiss: () -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
) {
    var draft by remember {
        mutableStateOf(draftFromExisting(initialConditions, initialJoin))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = AppDimensions.SidebarWidth.dp, top = AppDimensions.TitleBarHeight.dp)
                .padding(AppDimensions.SpacingLarge.dp)
                // 吞点击，别落到 scrim 上取消。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            WizardView(
                draft = draft,
                onDraftChange = { draft = it },
                onCancel = onDismiss,
                onSave = onSave,
            )
        }
        Text(
            ruleName,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextOnScrim,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(AppDimensions.SpacingLarge.dp),
        )
    }
}

private data class AttrSpec(
    val label: String,
    val kind: ConditionValueKind,
    val larger: ChatConditionType? = null,
    val smaller: ChatConditionType? = null,
    val direct: ChatConditionType? = null,
) {
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

private fun presetsFor(kind: ConditionValueKind): List<Int> = when (kind) {
    ConditionValueKind.MEGABYTES -> listOf(1, 5, 10)
    ConditionValueKind.DAYS -> listOf(7, 30, 90)
    else -> emptyList()
}

private fun ruleSentence(rule: RetentionRule): String {
    if (rule.conditions.isEmpty()) return "无条件"
    val parts = rule.conditions.map { readableCondition(it) }
    return parts.joinToString(" ${rule.join.label} ")
}

private fun readableCondition(c: ChatCondition): String = when (c.type.kind) {
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> "${c.type.label} ${c.number} ${c.type.kind.unit}"
    ConditionValueKind.TOOL ->
        "${c.type.label} ${ChatTool.entries.firstOrNull { it.id == c.text }?.displayName ?: c.text}"
    ConditionValueKind.TEXT -> "${c.type.label}「${c.text}」"
}

private fun draftSentence(draft: Draft, valuePreview: String? = null): String {
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

private fun pendingFragment(attr: AttrSpec?, larger: Boolean?, valuePreview: String?): String? {
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
            val tool = ChatTool.entries.firstOrNull { it.id == preview || it.displayName == preview }
            if (tool != null) "${type.label} ${tool.displayName}" else type.label
        }
        ConditionValueKind.TEXT -> {
            if (preview.isNotEmpty()) "${type.label}「$preview」" else type.label
        }
    }
}

@Composable
private fun RuleListView(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (RetentionRule) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (config.rules.isEmpty()) {
            EmptyRuleList(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = AppDimensions.SpacingSmall.dp),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            ) {
                items(
                    count = config.rules.size,
                    key = { index -> config.rules[index].id },
                ) { index ->
                    val rule = config.rules[index]
                    RuleCard(
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
                        onEdit = { onEditRule(rule) },
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

@Composable
private fun RuleCard(
    rule: RetentionRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val title = rule.name.ifBlank { "未命名策略" }
    val detail = ruleSentence(rule)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .combinedClickable(
                onClick = {},
                onLongClick = { menuExpanded = true },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onEdit) {
                Text("编辑", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                ),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = AppColors.Surface1,
        ) {
            DropdownMenuItem(
                text = {
                    Text("删除", fontSize = 13.sp, color = AppColors.Error)
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = AppColors.Error,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

private data class Draft(
    val committed: List<ChatCondition> = emptyList(),
    val join: ConditionJoin = ConditionJoin.AND,
    val attr: AttrSpec? = null,
    val larger: Boolean? = null,
    val showCombine: Boolean = false,
) {
    // showCombine 时末尾是正在改的那条，算占用会把自己选项弄没。
    val usedTypes: List<ChatConditionType>
        get() = (if (showCombine) committed.dropLast(1) else committed).map { it.type }

    val allTypes: List<ChatConditionType> get() = committed.map { it.type }
}

private val AttrSpec.isNumeric: Boolean
    get() = kind == ConditionValueKind.MEGABYTES || kind == ConditionValueKind.DAYS

private fun AttrSpec.typeFor(larger: Boolean?): ChatConditionType =
    if (isNumeric) (if (larger == true) this.larger!! else smaller!!) else direct!!

private fun pathOf(condition: ChatCondition): Pair<AttrSpec, Boolean?> {
    val attr = attrFor(condition.type)
    return attr to if (attr.isNumeric) condition.type == attr.larger else null
}

private fun draftFromExisting(conditions: List<ChatCondition>, join: ConditionJoin): Draft {
    if (conditions.isEmpty()) return Draft(join = join)
    val last = conditions.last()
    val (attr, larger) = pathOf(last)
    return Draft(
        committed = conditions,
        join = join,
        attr = attr,
        larger = larger,
        showCombine = true,
    )
}

private fun Draft.rightmostDepth(): Int {
    val a = attr ?: return 0
    return when {
        showCombine -> if (a.isNumeric) 3 else 2
        a.isNumeric && larger == null -> 1
        a.isNumeric -> 2
        else -> 1
    }
}

private fun Draft.isColumnEditable(columnIndex: Int): Boolean =
    columnIndex >= rightmostDepth() - 1

private val ColumnWidth = 150.dp

private data class RoundEntry(
    val id: Int,
    val locked: Boolean,
    val attr: AttrSpec?,
    val larger: Boolean?,
    val condition: ChatCondition?,
    val priorTypes: List<ChatConditionType>,
)

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
private class ColumnAnim {
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
private fun AlignedColumn(
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
private fun WizardHeader(draft: Draft, valuePreview: String? = null) {
    Text(
        draftSentence(draft, valuePreview),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextOnScrim,
    )
}

@Composable
private fun CombineColumn(
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
private fun JoinLabel(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        color = AppColors.TextOnScrim,
        modifier = Modifier.padding(top = 13.dp),
    )
}

@Composable
private fun RoundSlot(
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
private fun RoundValueColumn(
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

@Composable
private fun WizColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.width(ColumnWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

private data class WizOption(
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private enum class WizWeight { ACTIVE, DIMMED }

@Composable
private fun WizSegmented(
    options: List<WizOption>,
    weight: WizWeight = WizWeight.ACTIVE,
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
            // 别按下标 key，选项换位会串颜色动画。
            key(option.label) {
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
                    weight = weight,
                    onSegmentHeight = if (index == 0) onSegmentHeight else null,
                    onSelectedCoords = onSelectedCoords,
                )
            }
        }
    }
}

@Composable
private fun WizSegment(
    option: WizOption,
    weight: WizWeight = WizWeight.ACTIVE,
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    val active = option.selected
    val dimmed = weight == WizWeight.DIMMED
    val bg by animateColorAsState(
        targetValue = when {
            active && dimmed -> AppColors.PrimaryContainer
            active -> AppColors.Primary
            else -> AppColors.Surface3
        },
        animationSpec = Motion.normal(),
        label = "wizSegBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            active && dimmed -> AppColors.TextSecondary
            active -> AppColors.OnPrimary
            !option.enabled -> AppColors.TextTertiary
            else -> AppColors.TextSecondary
        },
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
            .clickable(enabled = option.enabled, onClick = option.onClick)
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
            tint = AppColors.TextOnScrim,
            modifier = Modifier.size(15.dp),
        )
        Text("返回", fontSize = 13.sp, color = AppColors.TextOnScrim)
    }
}

@Composable
private fun CustomNumberCell(
    unit: String,
    editable: Boolean = true,
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onPreview: ((String?) -> Unit)? = null,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val n = input.toIntOrNull() ?: 0
    val enabled = editable && n >= 1
    val confirmTint = if (enabled) AppColors.Primary else AppColors.TextTertiary
    OutlinedTextField(
        value = input,
        onValueChange = { raw ->
            if (!editable) return@OutlinedTextField
            if (raw.all { it.isDigit() } && raw.length <= 5) {
                input = raw
                onPreview?.invoke(raw.ifEmpty { null })
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            },
        enabled = editable,
        placeholder = { Text("自选输入") },
        suffix = if (input.isNotEmpty()) {
            { Text(unit) }
        } else {
            null
        },
        trailingIcon = {
            IconButton(
                onClick = { onConfirm(n.coerceIn(1, 99999)) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "确认",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(AppDimensions.Radius.dp),
        colors = appOutlinedTextFieldColors(
            focusedTrailingIconColor = confirmTint,
            unfocusedTrailingIconColor = confirmTint,
        ),
    )
}

@Composable
private fun CustomTextCell(
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onPreview: ((String?) -> Unit)? = null,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val enabled = input.isNotBlank()
    val confirmTint = if (enabled) AppColors.Primary else AppColors.TextTertiary
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.take(60)
            onPreview?.invoke(input.ifBlank { null })
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            },
        placeholder = { Text("关键词") },
        trailingIcon = {
            IconButton(
                onClick = { onConfirm(input.trim()) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "确认",
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(AppDimensions.Radius.dp),
        colors = appOutlinedTextFieldColors(
            focusedTrailingIconColor = confirmTint,
            unfocusedTrailingIconColor = confirmTint,
        ),
    )
}

@Composable
private fun EmptyRuleList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有自动清理策略",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "策略按条件自动删除对话记录，例如「未更新超过 30 天」",
            fontSize = 12.sp,
            color = AppColors.TextTertiary,
        )
    }
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


