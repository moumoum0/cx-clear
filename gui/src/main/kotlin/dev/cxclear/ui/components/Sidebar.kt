package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion

@Composable
fun Sidebar(
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .width(AppDimensions.SidebarWidth.dp)
            .fillMaxHeight()
            .background(AppColors.Surface2)
            .padding(vertical = AppDimensions.SpacingMedium.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp)
    ) {
        SidebarItem(
            icon = Icons.Default.Search,
            label = "扫描",
            isSelected = currentScreen == Screen.SCAN,
            onClick = { onScreenChange(Screen.SCAN) }
        )

        SidebarItem(
            icon = Icons.AutoMirrored.Filled.Chat,
            label = "对话管理",
            isSelected = currentScreen == Screen.CHATS,
            onClick = { onScreenChange(Screen.CHATS) }
        )

        SidebarItem(
            icon = Icons.Default.Settings,
            label = "设置",
            isSelected = currentScreen == Screen.SETTINGS,
            onClick = { onScreenChange(Screen.SETTINGS) }
        )
    }
}

@Composable
fun SidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) AppColors.Surface4 else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = Motion.normal(),
        label = "sidebarBg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) AppColors.Primary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "sidebarIcon",
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "sidebarText",
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = Motion.scale(),
        label = "sidebarScale",
    )

    Column(
        modifier = Modifier
            .width(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = textColor
        )
    }
}
