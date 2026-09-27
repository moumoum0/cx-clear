package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatGroup
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.chats.formatUpdatedAt
import dev.cxclear.chats.projectLabel
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.util.formatBytes

/**
 * 手动管理面板的会话列表叶子：扫描中指示、空态骨架、分组头、会话行。
 *
 * 分组半径、是否显示项目、是否展开都由主体算好当参数传进来，这里只负责画。
 */
@Composable
internal fun ScanningIndicator(
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
internal fun EmptySessionList(hasQuery: Boolean, modifier: Modifier = Modifier) {
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
internal fun SkeletonBar(width: Dp, height: Dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.Surface3),
    )
}

/** 会话在选择集合里的唯一键：同一 UUID 可能同时存在于两个工具下。 */
internal fun sessionKey(session: ChatSessionSummary): String = "${session.tool.id}:${session.id}"

@Composable
internal fun ChatGroupHeader(
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
internal fun SessionRow(
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
