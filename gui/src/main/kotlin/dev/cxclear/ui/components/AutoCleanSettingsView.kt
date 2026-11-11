package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.AppPrefs
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AutoCleanSettingsView(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(AppPreferences.read()) }

    // 磁盘新鲜读改写，不整份覆盖：设置列表页可能同时改了别的字段
    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        prefs = transform(prefs)
        scope.launch { withContext(Dispatchers.IO) { AppPreferences.update(transform) } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextPrimary,
                )
            }
            Text(
                text = "自动清理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                color = AppColors.TextPrimary,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp)),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppDimensions.SpacingMedium.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item { SettingsGroupTitle("策略执行") }
                item {
                    SettingsSwitchItem(
                        icon = Icons.Default.AutoMode,
                        title = "自动清理",
                        subtitle = "进入对话页或开机自启动时，按已保存策略自动删除会话",
                        checked = prefs.autoCleanEnabled,
                        onCheckedChange = { checked ->
                            updatePrefs { it.copy(autoCleanEnabled = checked) }
                        },
                    )
                }
                item {
                    SettingsSwitchItem(
                        icon = Icons.Default.Notifications,
                        title = "清理完成通知",
                        subtitle = "自动清理完成后在对话页显示结果",
                        checked = prefs.autoCleanNotify,
                        onCheckedChange = { checked ->
                            updatePrefs { it.copy(autoCleanNotify = checked) }
                        },
                    )
                }
            }
        }
    }
}
