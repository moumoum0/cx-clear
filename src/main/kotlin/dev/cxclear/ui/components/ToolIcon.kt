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
import dev.cxclear.resources.Res
import dev.cxclear.resources.claude
import dev.cxclear.resources.codex
import dev.cxclear.resources.cursor
import dev.cxclear.resources.deepseek
import dev.cxclear.resources.opencode
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * 工具图标按钮，以及全应用唯一的工具清单 [ToolEntries]。
 * 扫描页与对话页的工具条都从这里取列表，别再各写一份硬编码。
 */

internal data class ToolEntry(
    val id: String,
    val name: String,
    val resource: DrawableResource,
)

/** [id] 必须与 `profiles/` 下各 `<Tool>Profile.kt` 里 `ToolProfile.id` 的字符串完全一致。 */
internal val ToolEntries: List<ToolEntry> = listOf(
    ToolEntry(id = "codex", name = "Codex", resource = Res.drawable.codex),
    ToolEntry(id = "claude", name = "Claude", resource = Res.drawable.claude),
    ToolEntry(id = "cursor", name = "Cursor", resource = Res.drawable.cursor),
    ToolEntry(id = "opencode", name = "Open Code", resource = Res.drawable.opencode),
    ToolEntry(id = "deepseek-hermes", name = "DeepSeek", resource = Res.drawable.deepseek),
)

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
