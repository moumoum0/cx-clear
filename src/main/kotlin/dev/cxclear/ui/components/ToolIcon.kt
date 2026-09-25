package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.cxclear.tools.tools
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * 工具图标按钮。清单来自 [tools]，不要在这里再写一份 id。
 */

internal data class ToolEntry(
    val id: String,
    val name: String,
    val resource: DrawableResource,
)

internal val ToolEntries: List<ToolEntry> get() = tools().map { plugin ->
    ToolEntry(id = plugin.profile.id, name = plugin.shortName, resource = plugin.icon)
}

@Composable
internal fun ToolIcon(
    name: String,
    resource: DrawableResource,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> AppColors.Surface3.copy(alpha = 0.55f)
            isSelected -> AppColors.Primary
            else -> AppColors.Surface3
        },
        animationSpec = Motion.normal(),
        label = "toolIconBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> AppColors.TextTertiary.copy(alpha = 0.55f)
            isSelected -> AppColors.OnPrimary
            else -> AppColors.TextSecondary
        },
        animationSpec = Motion.normal(),
        label = "toolIconTint",
    )
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(color = bg, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(resource),
            contentDescription = name,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
