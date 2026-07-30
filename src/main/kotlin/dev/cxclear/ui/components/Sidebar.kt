package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions

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
            icon = Icons.Default.Delete,
            label = "清理",
            isSelected = currentScreen == Screen.CLEAN,
            onClick = { onScreenChange(Screen.CLEAN) }
        )

        SidebarItem(
            icon = Icons.Default.DateRange,
            label = "历史",
            isSelected = currentScreen == Screen.HISTORY,
            onClick = { onScreenChange(Screen.HISTORY) }
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
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(if (isSelected) AppColors.Surface4 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) AppColors.Primary else AppColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary
        )
    }
}
