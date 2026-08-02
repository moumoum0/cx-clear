package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatAxis
import dev.cxclear.chats.ChatGroup
import dev.cxclear.chats.ChatGroupDimension
import dev.cxclear.chats.ChatSessionSummary
import dev.cxclear.chats.deleteSessions
import dev.cxclear.chats.filterSessions
import dev.cxclear.chats.formatUpdatedAt
import dev.cxclear.chats.groupSessions
import dev.cxclear.chats.projectLabel
import dev.cxclear.scan.formatBytes
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.launch

/** 会话在选择集合里的唯一键：同一 UUID 可能同时存在于两个工具下。 */
private fun sessionKey(session: ChatSessionSummary): String = "${session.tool.id}:${session.id}"

/**
 * 手动管理：筛选 + 排序 + 分组勾选 + 删除。
 *
 * 会话列表由父级扫描后传入（[allSessions]），这里只做纯展示与选择；
 * 删除走 [deleteSessions]（只删扫描时冻结的条目），完成后回调父级重扫。
 */
@Composable
internal fun ChatsManualPane(
    isScanning: Boolean,
    foundCount: Int,
    allSessions: List<ChatSessionSummary>,
    nowMillis: Long,
    onDeleted: (deleted: Int, freedBytes: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isScanning) {
        ScanningIndicator(foundCount, modifier)
        return
    }

    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    // 排列与分组共用一个轴：选轴即排序，双击同一个轴切换是否按它分档。
    var axis by remember { mutableStateOf(ChatAxis.TIME) }
    var ascending by remember { mutableStateOf(false) }
    var grouped by remember { mutableStateOf(true) }

    val sortKey = axis.sortKey
    val dimension = if (grouped) axis.groupDimension ?: ChatGroupDimension.NONE else ChatGroupDimension.NONE

    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // 扫描结果换了一批（重扫 / 切工具）就丢掉旧选择，避免选中已不存在的会话。
    remember(allSessions) {
        selectedKeys = emptySet()
        true
    }

    val visible = filterSessions(allSessions, query)
    val groups = groupSessions(visible, dimension, sortKey, ascending, nowMillis)

    val byKey = remember(visible) { visible.associateBy(::sessionKey) }
    val selectedSessions = selectedKeys.mapNotNull { byKey[it] }
    val selectedBytes = selectedSessions.sumOf { it.sizeBytes }

    Column(modifier = modifier.fillMaxSize()) {
        ChatsFilterBar(
            query = query,
            onQueryChange = { query = it },
            axis = axis,
            grouped = grouped,
            ascending = ascending,
            onAxisClick = { clicked ->
                if (clicked == axis) {
                    // 双击同一个轴 = 切换分组；标题没有可用分档，忽略。
                    if (clicked.groupDimension != null) grouped = !grouped
                } else {
                    axis = clicked
                }
            },
            onToggleOrder = { ascending = !ascending },
        )

        if (groups.isEmpty()) {
            EmptySessionList(hasQuery = query.isNotBlank(), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = AppDimensions.SpacingSmall.dp),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            ) {
                items(
                    count = groups.size,
                    key = { index -> groups[index].key },
                ) { index ->
                    val group = groups[index]
                    ChatGroupCard(
                        group = group,
                        expanded = group.key !in collapsedGroups,
                        selectedKeys = selectedKeys,
                        nowMillis = nowMillis,
                        showProject = dimension != ChatGroupDimension.PROJECT,
                        onToggleExpand = {
                            collapsedGroups = if (group.key in collapsedGroups) {
                                collapsedGroups - group.key
                            } else {
                                collapsedGroups + group.key
                            }
                        },
                        onToggleGroup = { checkAll ->
                            val keys = group.sessions.map(::sessionKey).toSet()
                            selectedKeys = if (checkAll) selectedKeys + keys else selectedKeys - keys
                        },
                        onToggleSession = { session ->
                            val key = sessionKey(session)
                            selectedKeys = if (key in selectedKeys) {
                                selectedKeys - key
                            } else {
                                selectedKeys + key
                            }
                        },
                    )
                }
            }
        }

        lastError?.let { message ->
            SelectionErrorBar(message = message, onDismiss = { lastError = null })
        }

        AnimatedVisibility(
            visible = selectedSessions.isNotEmpty(),
            enter = slideInVertically(Motion.normal()) { it } + fadeIn(Motion.normal()),
            exit = slideOutVertically(Motion.fast()) { it } + fadeOut(Motion.fast()),
        ) {
            SelectionActionBar(
                count = selectedSessions.size,
                bytes = selectedBytes,
                deleting = deleting,
                onClear = { selectedKeys = emptySet() },
                onDelete = { confirming = true },
            )
        }
    }

    if (confirming) {
        DeleteConfirmDialog(
            count = selectedSessions.size,
            bytes = selectedBytes,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                deleting = true
                val targets = selectedSessions
                scope.launch {
                    val result = deleteSessions(targets)
                    deleting = false
                    selectedKeys = emptySet()
                    lastError = when {
                        result.blockedTools.isNotEmpty() ->
                            "${result.blockedTools.joinToString("、")} 正在运行，已跳过其会话"
                        result.errors.isNotEmpty() -> result.errors.first()
                        else -> null
                    }
                    onDeleted(result.deletedSessions, result.freedBytes)
                }
            },
        )
    }
}

// ─────────────────────────────────────────────
// 扫描中
// ─────────────────────────────────────────────

@Composable
private fun ScanningIndicator(foundCount: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = AppColors.Primary,
                strokeWidth = 4.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已找到 ", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                FlipCountText(
                    count = foundCount,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                )
                Text(" 个", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            }
        }
    }
}
// ─────────────────────────────────────────────
// 筛选栏
// ─────────────────────────────────────────────

@Composable
private fun ChatsFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    axis: ChatAxis,
    grouped: Boolean,
    ascending: Boolean,
    onAxisClick: (ChatAxis) -> Unit,
    onToggleOrder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDimensions.SpacingSmall.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        ChatAxis.entries.forEach { entry ->
            val selected = entry == axis
            AxisPill(
                label = entry.label,
                selected = selected,
                grouped = selected && grouped && entry.groupDimension != null,
                onClick = { onAxisClick(entry) },
            )
        }

        Spacer(Modifier.weight(1f))

        // 升降序独立成一个按钮，避免和「双击切分组」抢同一次点击。
        OrderToggle(ascending = ascending, onClick = onToggleOrder)

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.width(SearchFieldWidth),
        )
    }
}

private val SearchFieldWidth = 160.dp

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(AppColors.Surface3)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(14.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索", fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TextStyle(fontSize = 12.sp, color = AppColors.TextPrimary),
                singleLine = true,
                cursorBrush = SolidColor(AppColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清空",
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

/**
 * 排列轴胶囊。选中即按该轴排列；再点一下（同一个轴的第二次点击）切换是否按它分档，
 * 分档开启时胶囊左侧长出分组图标。
 */
@Composable
private fun AxisPill(
    label: String,
    selected: Boolean,
    grouped: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "axisPillBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) AppColors.OnPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "axisPillFg",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedVisibility(
            visible = grouped,
            enter = expandHorizontally(Motion.fast()) + fadeIn(Motion.fast()),
            exit = shrinkHorizontally(Motion.fast()) + fadeOut(Motion.fast()),
        ) {
            Icon(
                imageVector = Icons.Filled.Segment,
                contentDescription = "已按 $label 分组",
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 升降序切换：箭头翻转，跟分组的双击手势分开。 */
@Composable
private fun OrderToggle(ascending: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (ascending) 0f else 180f,
        animationSpec = Motion.normal(),
        label = "orderArrow",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(AppColors.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowUpward,
            contentDescription = if (ascending) "升序" else "降序",
            tint = AppColors.TextSecondary,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
        Text(
            if (ascending) "升序" else "降序",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
        )
    }
}
// ─────────────────────────────────────────────
// 空态骨架
// ─────────────────────────────────────────────

@Composable
private fun EmptySessionList(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppDimensions.SpacingMedium.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        Text(
            if (hasQuery) "没有匹配的会话" else "没有可管理的会话",
            fontSize = 13.sp,
            color = AppColors.TextTertiary,
        )
        // 骨架：形状与真实分组卡一致，数据到位时原地填充。
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                    .background(AppColors.Surface1)
                    .padding(AppDimensions.SpacingMedium.dp),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            ) {
                SkeletonBar(width = 120.dp, height = 14.dp)
                repeat(2) { SkeletonBar(width = 260.dp, height = 12.dp) }
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.Surface3),
    )
}

// ─────────────────────────────────────────────
// 分组卡
// ─────────────────────────────────────────────

@Composable
private fun ChatGroupCard(
    group: ChatGroup,
    expanded: Boolean,
    selectedKeys: Set<String>,
    nowMillis: Long,
    showProject: Boolean,
    onToggleExpand: () -> Unit,
    onToggleGroup: (Boolean) -> Unit,
    onToggleSession: (ChatSessionSummary) -> Unit,
) {
    val selectedInGroup = group.sessions.count { sessionKey(it) in selectedKeys }
    val state = when (selectedInGroup) {
        0 -> ToggleableState.Off
        group.sessions.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = Motion.normal(),
        label = "groupChevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = state,
                onClick = { onToggleGroup(state != ToggleableState.On) },
                colors = CheckboxDefaults.colors(
                    checkedColor = AppColors.Primary,
                    checkmarkColor = AppColors.OnPrimary,
                    uncheckedColor = AppColors.Outline,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                group.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${group.sessions.size} 个 · ${formatBytes(group.totalBytes)}",
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevron),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.normal()) + fadeIn(Motion.normal()),
            exit = shrinkVertically(Motion.fast()) + fadeOut(Motion.fast()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        selected = sessionKey(session) in selectedKeys,
                        nowMillis = nowMillis,
                        showProject = showProject,
                        onToggle = { onToggleSession(session) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    selected: Boolean,
    nowMillis: Long,
    showProject: Boolean,
    onToggle: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppColors.PrimaryContainer else Color.Transparent,
        animationSpec = Motion.fast(),
        label = "rowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AppColors.Primary,
                checkmarkColor = AppColors.OnPrimary,
                uncheckedColor = AppColors.Outline,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.title,
                fontSize = 13.sp,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append(session.tool.displayName)
                if (showProject) {
                    projectLabel(session).takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                append(" · ").append(formatUpdatedAt(session.updatedMillis, nowMillis))
            }
            Text(
                meta,
                fontSize = 11.sp,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatBytes(session.sizeBytes),
            fontSize = 12.sp,
            color = AppColors.TextSecondary,
        )
    }
}
// ─────────────────────────────────────────────
// 底部条 + 确认弹窗
// ─────────────────────────────────────────────

@Composable
private fun SelectionErrorBar(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Optional.copy(alpha = 0.10f))
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = AppColors.Optional,
            modifier = Modifier.size(16.dp),
        )
        Text(message, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭",
            tint = AppColors.TextTertiary,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    bytes: Long,
    deleting: Boolean,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface1)
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        Text("已选 ", fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(
            "$count",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
        )
        Text(" 个 · ", fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(
            formatBytes(bytes),
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onClear, enabled = !deleting) {
            Text("取消选择", fontSize = 13.sp, color = AppColors.TextSecondary)
        }
        Button(
            onClick = onDelete,
            enabled = !deleting,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Error,
                contentColor = AppColors.OnPrimary,
            ),
            shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
        ) {
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColors.OnPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text("删除中", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    count: Int,
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface1,
        title = {
            Text("删除 $count 个会话？", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
        },
        text = {
            Text(
                "将释放约 ${formatBytes(bytes)}。会话记录删除后无法恢复；如果对应的工具正在运行，该工具的会话会被整批跳过。",
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                lineHeight = 18.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Error,
                    contentColor = AppColors.OnPrimary,
                ),
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
            ) {
                Text("确认删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        },
    )
}
