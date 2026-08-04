package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
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

// 同一 UUID 可能跨工具出现。
private fun sessionKey(session: ChatSessionSummary): String = "${session.tool.id}:${session.id}"

@Composable
internal fun ChatsManualPane(
    isScanning: Boolean,
    foundCount: Int,
    allSessions: List<ChatSessionSummary>,
    nowMillis: Long,
    onDeleted: (deleted: Int, freedBytes: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 等翻牌追上再切结果页。
    var settledFor by remember { mutableIntStateOf(foundCount) }
    val showScanning = isScanning || settledFor != foundCount
    if (showScanning) {
        ScanningIndicator(
            foundCount = foundCount,
            onCountSettled = { settledFor = it },
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
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

    remember(allSessions) {
        selectedKeys = emptySet()
        true
    }

    val visible = remember(allSessions, query) { filterSessions(allSessions, query) }
    val groups = remember(visible, dimension, sortKey, ascending, nowMillis) {
        groupSessions(visible, dimension, sortKey, ascending, nowMillis)
    }

    val byKey = remember(visible) { visible.associateBy(::sessionKey) }
    val selectedSessions = remember(selectedKeys, byKey) { selectedKeys.mapNotNull { byKey[it] } }
    val selectedBytes = remember(selectedSessions) { selectedSessions.sumOf { it.sizeBytes } }
    val showProject = dimension != ChatGroupDimension.PROJECT
    val groupRadius = AppDimensions.Radius.dp

    Column(modifier = modifier.fillMaxSize()) {
        ChatsFilterBar(
            query = query,
            onQueryChange = { query = it },
            axis = axis,
            grouped = grouped,
            ascending = ascending,
            onAxisClick = { clicked ->
                if (clicked == axis) {
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
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        item(key = "gap-${group.key}") {
                            Spacer(Modifier.height(AppDimensions.SpacingSmall.dp))
                        }
                    }
                    val expanded = group.key !in collapsedGroups
                    item(key = "header-${group.key}") {
                        ChatGroupHeader(
                            group = group,
                            expanded = expanded,
                            selectedKeys = selectedKeys,
                            shape = if (expanded) {
                                RoundedCornerShape(topStart = groupRadius, topEnd = groupRadius)
                            } else {
                                RoundedCornerShape(groupRadius)
                            },
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
                        )
                    }
                    if (expanded) {
                        itemsIndexed(
                            items = group.sessions,
                            key = { _, session -> "${group.key}:${sessionKey(session)}" },
                        ) { index, session ->
                            val isLast = index == group.sessions.lastIndex
                            SessionRow(
                                session = session,
                                selected = sessionKey(session) in selectedKeys,
                                nowMillis = nowMillis,
                                showProject = showProject,
                                onToggle = {
                                    val key = sessionKey(session)
                                    selectedKeys = if (key in selectedKeys) {
                                        selectedKeys - key
                                    } else {
                                        selectedKeys + key
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(
                                        if (isLast) {
                                            RoundedCornerShape(
                                                bottomStart = groupRadius,
                                                bottomEnd = groupRadius,
                                            )
                                        } else {
                                            RoundedCornerShape(0.dp)
                                        },
                                    )
                                    .background(AppColors.Surface1)
                                    .then(
                                        if (isLast) Modifier.padding(bottom = 4.dp) else Modifier,
                                    ),
                            )
                        }
                    }
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

@Composable
private fun ScanningIndicator(
    foundCount: Int,
    onCountSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    onSettled = onCountSettled,
                )
                Text(" 个", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            }
        }
    }
}

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

@Composable
private fun ChatGroupHeader(
    group: ChatGroup,
    expanded: Boolean,
    selectedKeys: Set<String>,
    shape: RoundedCornerShape,
    onToggleExpand: () -> Unit,
    onToggleGroup: (Boolean) -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Surface1)
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
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    selected: Boolean,
    nowMillis: Long,
    showProject: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppColors.PrimaryContainer else Color.Transparent,
        animationSpec = Motion.fast(),
        label = "rowBg",
    )
    Row(
        modifier = modifier
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
