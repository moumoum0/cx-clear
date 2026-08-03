package dev.cxclear.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.theme.AppColors

/** 设置分组标题。颜色走 Primary，与 selves 设置页同构。 */
@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppColors.Primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** 可点击设置行：图标 + 标题 + 副标题。 */
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val titleColor = if (enabled) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.38f)
    val subtitleColor = if (enabled) AppColors.TextSecondary else AppColors.TextSecondary.copy(alpha = 0.38f)
    val iconTint = if (enabled) AppColors.TextSecondary else AppColors.TextSecondary.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = titleColor)
            Text(text = subtitle, fontSize = 13.sp, color = subtitleColor)
        }
    }
}

/** 带开关的设置行；整行可点切换。 */
@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val titleColor = if (enabled) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.38f)
    val subtitleColor = if (enabled) AppColors.TextSecondary else AppColors.TextSecondary.copy(alpha = 0.38f)
    val iconTint = if (enabled) AppColors.TextSecondary else AppColors.TextSecondary.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = titleColor)
            Text(text = subtitle, fontSize = 13.sp, color = subtitleColor)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.OnPrimary,
                checkedTrackColor = AppColors.Primary,
            ),
        )
    }
}

/** 操作进行中时在行尾显示小进度圈。 */
@Composable
fun SettingsItemWithProgress(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isLoading) AppColors.TextSecondary.copy(alpha = 0.5f) else AppColors.TextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = if (isLoading) AppColors.TextPrimary.copy(alpha = 0.5f) else AppColors.TextPrimary,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = AppColors.TextSecondary.copy(alpha = if (isLoading) 0.5f else 1f),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = AppColors.Primary,
            )
        }
    }
}
