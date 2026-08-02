package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions

@Composable
fun WindowScope.AppTitleBar(
    title: String,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppDimensions.TitleBarHeight.dp)
            .background(AppColors.Surface2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题区只交给 WindowDraggableArea，不要再叠 pointerInput，
        // 否则会抢走按下事件，窗口就拖不动。
        WindowDraggableArea(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(horizontal = AppDimensions.SpacingMedium.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextSecondary,
                )
            }
        }

        TitleBarButton(
            icon = Icons.Filled.Minimize,
            contentDescription = "最小化",
            onClick = onMinimize,
        )
        TitleBarButton(
            icon = if (isMaximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
            contentDescription = if (isMaximized) "还原" else "最大化",
            onClick = onToggleMaximize,
        )
        TitleBarButton(
            icon = Icons.Filled.Close,
            contentDescription = "关闭",
            onClick = onClose,
            hoverBackground = AppColors.Error,
            hoverContent = AppColors.OnPrimary,
        )
    }
}

@Composable
private fun TitleBarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    hoverBackground: Color = AppColors.Surface4,
    hoverContent: Color = AppColors.TextPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .hoverable(interaction)
            .background(if (hovered) hoverBackground else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (hovered) hoverContent else AppColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}
