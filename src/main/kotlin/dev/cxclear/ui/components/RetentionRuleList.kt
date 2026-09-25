package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionRule
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions

/**
 * 自动清理策略的列表区：策略卡片（长按出删除菜单）、空态占位与底部新建/AI 提示词按钮。
 */
@Composable
internal fun RuleListView(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    onNewRule: () -> Unit,
    onAiPrompt: () -> Unit,
    onEditRule: (RetentionRule) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (config.rules.isEmpty()) {
            EmptyRuleList(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = AppDimensions.SpacingSmall.dp),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
            ) {
                items(
                    count = config.rules.size,
                    key = { index -> config.rules[index].id },
                ) { index ->
                    val rule = config.rules[index]
                    RuleCard(
                        rule = rule,
                        onToggle = { on ->
                            onConfigChange(
                                config.copy(
                                    rules = config.rules.map {
                                        if (it.id == rule.id) it.copy(enabled = on) else it
                                    }
                                )
                            )
                        },
                        onEdit = { onEditRule(rule) },
                        onDelete = {
                            onConfigChange(
                                config.copy(rules = config.rules.filterNot { it.id == rule.id })
                            )
                        },
                    )
                }
            }
        }
        AddRuleButton(onClick = onNewRule, onAiPrompt = onAiPrompt)
    }
}

@Composable
internal fun RuleCard(
    rule: RetentionRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val title = rule.name.ifBlank { "未命名策略" }
    val detail = ruleSentence(rule)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .combinedClickable(
                onClick = {},
                onLongClick = { menuExpanded = true },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onEdit) {
                Text("编辑", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                ),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = AppColors.Surface1,
        ) {
            DropdownMenuItem(
                text = {
                    Text("删除", fontSize = 13.sp, color = AppColors.Error)
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = AppColors.Error,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
internal fun EmptyRuleList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有自动清理策略",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "策略按条件自动删除对话记录，例如「未更新超过 30 天」",
            fontSize = 12.sp,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
internal fun AddRuleButton(onClick: () -> Unit, onAiPrompt: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppDimensions.SpacingSmall.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAiPrompt, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "AI 生成提示词",
                tint = AppColors.Primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(6.dp))
            Text("新建策略", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.Primary)
        }
    }
}
