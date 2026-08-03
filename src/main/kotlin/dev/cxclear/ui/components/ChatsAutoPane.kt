package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Constraints
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
import dev.cxclear.ui.LocalOverlayHost
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.ui.theme.appOutlinedTextFieldColors
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
    val overlayHost = LocalOverlayHost.current
    // 新建第一步：先给策略起名。填完名再把向导推进全窗浮层。
    var namingForNew by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        RuleListView(
            config = config,
            onConfigChange = onConfigChange,
            onNewRule = { namingForNew = true },
        )
    }

    if (namingForNew) {
        NameRuleDialog(
            onDismiss = { namingForNew = false },
            onConfirm = { name ->
                namingForNew = false
                // 浮层内容挂到根部渲染，scrim 已挡住列表，这里对 config 的快照在浮层存续期间不会变。
                overlayHost.show {
                    WizardOverlay(
                        ruleName = name,
                        onDismiss = { overlayHost.hide() },
                        onSave = { conditions, join ->
                            val rule = RetentionRule(
                                id = newRuleId(config.rules.map { it.id }),
                                name = name,
                                enabled = false,
                                join = join,
                                conditions = conditions,
                            )
                            onConfigChange(config.copy(rules = config.rules + rule))
                            overlayHost.hide()
                        },
                    )
                }
            },
        )
    }
}

/**
 * 新建策略第一步：起名。名称是列表里唯一的可读标识，必填——空白时确认不可点。
 */
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

/**
 * 全窗浮层里的向导：scrim 与模糊已由 [dev.cxclear.ui.App] 铺在整窗底下，向导直接落在这块变暗的
 * 灰色画布上，不再套一层白底面板。草稿状态自持在浮层里，跟着点选即时重组。
 *
 * 编辑区限回原来那块内容区（避开左侧栏与顶部标题栏），跟没进浮层时向导所在的位置一致；
 * 编辑区吞掉点击，避免工作区里的误点落到 scrim 触发取消，其余裸 scrim 区点了 = 取消（[onDismiss]）。
 * 策略名放到整窗右下角、放大、用反色（近白），压在暗画布上仍清晰。
 */
@Composable
private fun WizardOverlay(
    ruleName: String,
    onDismiss: () -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
) {
    var draft by remember { mutableStateOf(Draft()) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 编辑区收回原内容区：左让开侧栏、上让开标题栏，内侧再留一圈与原来一致的边距。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = AppDimensions.SidebarWidth.dp, top = AppDimensions.TitleBarHeight.dp)
                .padding(AppDimensions.SpacingLarge.dp)
                // 编辑区吞点击，避免落到底下的 scrim 触发取消。
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
        // 策略名钉在整窗右下角，放大 + 反色，作为「正在建哪条」的落款。
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

/**
 * 向导顶部简介：已定条件 + 正在点的那一条，随点选/输入即时刷新。
 *
 * [valuePreview] 是取值列里还没点确认的草稿（数字原文或文本）。
 * 点了「且」、下一条还没选时，句子末尾立刻挂上「且」。
 */
private fun draftSentence(draft: Draft, valuePreview: String? = null): String {
    // showCombine：末尾已定好；若正在重打自定义值，用预览替换末尾那句。
    // 否则：committed 全是定稿，正在拼的在 attr/larger（+ 可选预览）上。
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
    // 点「且」后 attr 被清空、下一条还没起头：把连接词立刻挂上，别等选了属性才出现。
    val awaitingNext = !draft.showCombine && finished.isNotEmpty() && tail == null
    return if (awaitingNext) "$body ${draft.join.label}" else body
}

/** 正在拼、还没进 committed 的那半句；缺取值时只写到已选层级，不加省略号。 */
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
        // 名称是唯一可读标识；旧配置/迁移出来的空名规则用条件句子兜底，避免出现空行。
        Text(
            rule.name.ifBlank { ruleSentence(rule) },
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

/**
 * 当前最右列深度（0 = 属性列）。
 *
 * 数值路径：属性 → 比较符 → 取值 → 组合；非数值：属性 → 取值 → 组合。
 * 前一列仍可改选；再往前锁定，只能靠「返回」一步步退。
 */
private fun Draft.rightmostDepth(): Int {
    val a = attr ?: return 0
    return when {
        showCombine -> if (a.isNumeric) 3 else 2
        a.isNumeric && larger == null -> 1
        a.isNumeric -> 2
        else -> 1
    }
}

/** 列下标是否可点选：当前列与前一列可以，前前列及更早锁定。 */
private fun Draft.isColumnEditable(columnIndex: Int): Boolean =
    columnIndex >= rightmostDepth() - 1

private val ColumnWidth = 150.dp

/** 向导里一轮条件的渲染条目；[id] 跨「且」稳定，用来保住 composition。 */
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

/**
 * 创建向导：从左到右一列列展开。
 *
 * 属性列 → （数值属性才有的）比较符列 → 取值列 → 且 / 或 / 保存 列。
 * 选过的项高亮留在原位，整条路径一直看得见；只有当前最右一列末尾留「返回」，
 * 右边一旦展开下一列，左边那列的「返回」就撤掉。
 * 可改选范围：当前列 + 前一列；前前列及更早锁定（仍显示路径，点不动）。
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
    // 取值列里尚未确认的输入，只用来刷新顶部简介；点预设/确认进 committed 后清掉。
    var valuePreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft.attr, draft.larger, draft.showCombine) {
        valuePreview = null
    }

    // 每一轮有稳定 id：点「且」后旧轮只把 locked 打开，不换 composable / key，才不会闪一下。
    val committedRoundIds = remember { mutableStateListOf<Int>() }
    var buildingRoundId by remember { mutableIntStateOf(0) }
    var nextRoundId by remember { mutableIntStateOf(1) }

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

    // 所有轮次排成一条列表、共用同一套 key 槽位：点「且」时旧轮只改 locked，不挪到另一个 for 里去挂。
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

    // 锚点是「当前正在编辑的那一轮固定在左边起点」，不是整条路径右对齐：
    // 右对齐会让只有一轮时也贴到最右边。这里让前缀（已冻结的轮）往左溢出，
    // 当前轮恒定落在 x=0，于是第一轮在原位，点「且」后旧轮才被推走。
    // x 只有一个真值（-前缀宽度），动画从上一帧落点滑到新落点，不做 snapTo 反跳，所以不闪。
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
            // 只有一个子节点：整条路径一个 Row。所有轮共用同一套 key 槽位，
            // 当前轮变成已冻结轮时只翻 locked，不换 call-site，所以不会重挂、不闪。
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp),
            ) {
                roundEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        JoinLabel(draft.join.label)
                    }
                    val isCurrent = index == roundEntries.lastIndex
                    // 当前轮报出它在 Row 内的 x：这是「该滑多少」的唯一真值。
                    // positionInParent relative to Row，在 pathShift 之上测得，不受位移影响，不成环。
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
            // 不限宽测量：整条路径想多宽就多宽，允许超出屏幕。
            val path = measurables[0].measure(
                constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
            )
            val viewport = constraints.maxWidth
            // 位移量由当前轮的 onPlaced 决定（见上），这里不再算落点，
            // 否则会覆盖锚点值、又退回「整条路径右对齐」那套错的行为。
            layout(viewport, path.height) {
                path.placeRelative(pathShift.value.roundToInt(), 0)
            }
        }
    }
}


/**
 * 一列的动画状态：入场进度 [enter] 与竖向锚点 [anchor]。
 *
 * 拆出来单独 hold，是为了让它能被「同一视觉位置、但内容会换的列」共享——
 * 比如属性列右边那一列，选数值属性时是比较符列、选工具/文本属性时是取值列，
 * 二者是不同的 composable（不同 call-site），各自 `remember` 会让状态在切换时丢失、
 * 于是每次换属性都重播入场。把状态 hoist 到 [WizardView] 按位置共享，切内容也不丢，
 * 就能「已经在场 → 换上一列选项 → 平滑滑过去」而不是重新淡入。
 */
private class ColumnAnim {
    val enter = Animatable(0f)
    val anchor = Animatable(0f)
    /** 已播过入场：之后只做位移，不再淡入。 */
    var appeared = false
    /** 锚点已首次落位：之后锚点变化走动画。 */
    var anchorInit = false

    /** 列整个撤走时复位，下次再出现重新走入场（而非从旧位置突兀滑入）。 */
    fun reset() {
        appeared = false
        anchorInit = false
    }
}

/**
 * 相对锚点（上一列选中项中线）垂直居中 [body]；[footer]（返回）不参与居中。
 *
 * 两段动画各管一件事：
 * - **入场**：这一列首次出现时播一次淡入 + 自左滑入（「选了上一列某项、这一列弹出来」的那一下）。
 * - **移动**：列已经在场时，若上一列换了选项、锚点随之改变，整列平滑滑到新位置，而不是重新淡入。
 *   不同选项弹出的选项数可能不同（高度不同），高度按新内容即时取，竖向中线走动画。
 *
 * [sharedAnim] 非空时用它（跨内容切换保留状态，见 [ColumnAnim]）；为空则本列自持一份，
 * 每次首次出现都走入场——适合「选了上一列才冒出来」的真·新列。
 */
@Composable
private fun AlignedColumn(
    anchorCenterY: Float?,
    sharedAnim: ColumnAnim? = null,
    /** 为假时跳过入场（已定稿路径从可点列「就地」冻住，不要再淡入一次）。 */
    playEnter: Boolean = true,
    body: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slideFrom = with(density) { 20.dp.toPx() }
    val anim = sharedAnim ?: remember { ColumnAnim() }
    // 入场：首次出现从 0 淡入 + 自左滑入；已 appeared 则直接坐实到 1，不再重播。
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
    // 竖向锚点：首次直接落位；在场时上一列换选项导致锚点变化，就平滑滑过去。
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
        // 读 anim.anchor.value（在 layout 阶段）→ 动画每帧驱动重新落位。
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

/** 顶部一句话交代已定到哪；随点选与输入即时变。 */
@Composable
private fun WizardHeader(draft: Draft, valuePreview: String? = null) {
    Text(
        draftSentence(draft, valuePreview),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        // 直接浮在暗画布上（不在卡片内），用反色（近白）保证可读。
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
    // 所有类型都用过了就没有「下一个条件」可加，不留点了没反应的入口。
    // 这里按 allTypes 算：刚定好的这一条确实占了一个类型。
    val canAddMore = ATTRS.any { spec -> spec.types.any { it !in draft.allTypes } }

    // 规则内条件恒为「且」：不给连接词选项，「且」就是「再加一个条件」的动作，不是持久选中态。
    // 只渲染且/保存；「返回」由 AlignedColumn.footer 挂在外面，不进对齐高度。
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

// ─────────────────────────────────────────────
// 一轮条件列：可点 / 锁定共用同一棵树（点「且」只翻 locked，不卸挂）
// ─────────────────────────────────────────────

/** 轮次之间的「且」：与首段文字大致齐平，分隔已冻路径与下一轮。 */
@Composable
private fun JoinLabel(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        color = AppColors.TextOnScrim,
        modifier = Modifier.padding(top = 13.dp),
    )
}

/**
 * 一轮的属性 →（比较符）→ 取值。
 *
 * [locked] 为真时选项全禁用、不留返回——点「且」后旧轮就地锁住；
 * 为假时与原来的可点路径同一套列，供当前轮继续点选。
 * 冻结 / 当前必须走这一棵树，才能靠外层 [key] 在「且」时复用节点、避免闪一下。
 */
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

    // 一轮收成一个 Row：外层只挂一个带 key 的节点，锁定时不拆成多列兄弟重排。
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

/** 一轮的取值列：锁定时只展示选中态；可点时与原 ValueColumn 相同。 */
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
                // 锁定后仍保留同一套预设 + 自选输入结构，避免点「且」时取值列高度突变闪一下。
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
                onSegmentHeight = onSegmentHeight,
                onSelectedCoords = { reportCenter(it) },
            )

            ConditionValueKind.TEXT -> {
                if (locked && chosen != null) {
                    WizSegmented(
                        options = listOf(
                            WizOption("「${chosen.text}」", selected = true, enabled = false) {},
                        ),
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

/** 分段选项：文字 + 选中态。整列拼成一块连体控件，不再各自成卡。[enabled] 为假时锁定不可点。 */
private data class WizOption(
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
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
            // 按选项本身 key，别按下标：轮次之间选项增删会换位，
            // 无 key 时新选项会落进上一个下标残留的颜色动画里，看起来「没选却高亮」。
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
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    val active = option.selected
    val bg by animateColorAsState(
        targetValue = if (active) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "wizSegBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
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
        // 「返回」无底色，直接压在暗画布上，用反色近白保证可读。
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = AppColors.TextOnScrim,
            modifier = Modifier.size(15.dp),
        )
        Text("返回", fontSize = 13.sp, color = AppColors.TextOnScrim)
    }
}

/** 自选输入（数值）：输入框 + 单位 + 确认。空/零不提交（`isComplete()` 也会兜底）。 */
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

/** 自选输入（文本）：输入框 + 确认；空白不提交。 */
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


