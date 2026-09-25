package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.model.ChatDeleteResult
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.util.formatBytes

/**
 * 对话管理页筛选顶栏：工具筛选按钮 + 手动/自动分段切换。
 * 以及自动清理、手动删除共用的结果条 [ChatsNotice]。
 */

internal enum class ChatsMode { MANUAL, AUTO }

@Composable
internal fun ChatsTopBar(
    selectedTool: String,
    onToolSelect: (String) -> Unit,
    mode: ChatsMode,
    onModeChange: (ChatsMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AllFilterButton(isSelected = selectedTool == TOOL_FILTER_ALL) {
                onToolSelect(TOOL_FILTER_ALL)
            }
            ToolEntries.forEach { entry ->
                ToolIcon(entry.name, entry.resource, selectedTool == entry.id) {
                    onToolSelect(entry.id)
                }
            }
        }

        ModeSegmentedControl(
            mode = mode,
            onModeChange = onModeChange,
        )
    }
}

@Composable
internal fun AllFilterButton(
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "allFilterBg",
    )
    val fg by animateColorAsState(
        targetValue = if (isSelected) AppColors.OnPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "allFilterFg",
    )
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(color = bg, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Apps,
            contentDescription = "所有",
            tint = fg,
            modifier = Modifier.size(24.dp),
        )
    }
}

private val ModeSegmentWidth = 52.dp
private val ModeControlHeight = 40.dp

/**
 * 手动 / 自动切换：M3 原生连体分段按钮。两段共享一圈描边、选中段填主色，
 * 段间由 M3 自己画分隔线，切换自带补间；只放图标，文案降到 contentDescription。
 */
@Composable
internal fun ModeSegmentedControl(
    mode: ChatsMode,
    onModeChange: (ChatsMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.height(ModeControlHeight),
    ) {
        val colors = SegmentedButtonDefaults.colors(
            activeContainerColor = AppColors.Primary,
            activeContentColor = AppColors.OnPrimary,
            activeBorderColor = AppColors.OutlineVariant,
            inactiveContainerColor = AppColors.Surface3,
            inactiveContentColor = AppColors.TextSecondary,
            inactiveBorderColor = AppColors.OutlineVariant,
        )
        SegmentedButton(
            selected = mode == ChatsMode.MANUAL,
            onClick = { onModeChange(ChatsMode.MANUAL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = colors,
            icon = {},
            modifier = Modifier.width(ModeSegmentWidth),
        ) {
            Icon(
                imageVector = Icons.Filled.Checklist,
                contentDescription = "手动",
                modifier = Modifier.size(20.dp),
            )
        }
        SegmentedButton(
            selected = mode == ChatsMode.AUTO,
            onClick = { onModeChange(ChatsMode.AUTO) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = colors,
            icon = {},
            modifier = Modifier.width(ModeSegmentWidth),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoDelete,
                contentDescription = "自动",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun deleteNotice(result: ChatDeleteResult, auto: Boolean): String? = when {
    result.blockedTools.isNotEmpty() -> {
        val skip = if (auto) "自动清理已跳过其会话" else "已跳过其会话"
        "${result.blockedTools.joinToString("、")} 正在运行，$skip"
    }
    result.errors.isNotEmpty() -> result.errors.first()
    auto && result.deletedSessions > 0 ->
        "自动清理已删除 ${result.deletedSessions} 个会话 · ${formatBytes(result.freedBytes)}"
    else -> null
}

/**
 * 对话页结果条：自动清理和手动删除共用。
 * 挂在列表外面，避免重扫把提示冲掉。
 */
@Composable
internal fun ChatsNotice(message: String, warning: Boolean, onDismiss: () -> Unit) {
    val bg = if (warning) AppColors.Optional.copy(alpha = 0.12f) else AppColors.PrimaryContainer
    val iconTint = if (warning) AppColors.Optional else AppColors.Primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(bg)
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        Icon(
            imageVector = if (warning) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            message,
            fontSize = 12.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭",
            tint = AppColors.TextSecondary,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDismiss),
        )
    }
}
