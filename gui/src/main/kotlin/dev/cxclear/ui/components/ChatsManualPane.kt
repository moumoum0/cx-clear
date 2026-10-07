package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.cxclear.chats.ChatAxis
import dev.cxclear.model.ChatDeleteResult
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.chats.ChatGroupDimension
import dev.cxclear.chats.deleteSessions
import dev.cxclear.chats.filterSessions
import dev.cxclear.chats.groupSessions
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.launch

@Composable
internal fun ChatsManualPane(
    isScanning: Boolean,
    foundCount: Int,
    allSessions: List<ChatSessionSummary>,
    nowMillis: Long,
    onDeleted: (ChatDeleteResult) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    // 扫完后仍短暂留在加载态，直到翻牌显示值追上最终计数；提前切走会让翻牌播到一半被结果页掐断。

    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var axis by remember { mutableStateOf(ChatAxis.TIME) }
    var ascending by remember { mutableStateOf(false) }
    var grouped by remember { mutableStateOf(true) }

    val sortKey = axis.sortKey
    // 排列与分组共用一个轴：选轴即排序，双击同一个轴切换是否按它分档。
    val dimension = if (grouped) axis.groupDimension ?: ChatGroupDimension.NONE else ChatGroupDimension.NONE

    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    remember(allSessions) {
        // 扫描结果换了一批（重扫 / 切工具）就丢掉旧选择，否则会选中已不存在的会话。
        selectedKeys = emptySet()
        true
    }

    val visible = remember(allSessions, query) { filterSessions(allSessions, query) }
    // 勾选 / 折叠只改选择态，进 remember 的 key 会白白触发筛选分组重算。
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
                    onDeleted(result)
                }
            },
        )
    }
}
