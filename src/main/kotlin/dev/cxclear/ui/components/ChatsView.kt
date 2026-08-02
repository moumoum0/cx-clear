package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.*
import dev.cxclear.scan.formatBytes
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private enum class ViewState { IDLE, SCANNING, SCAN_DONE }

@Composable
fun ChatsView() {
    val scope = rememberCoroutineScope()

    // 自动清理策略
    var policy by remember { mutableStateOf(RetentionPolicy()) }
    var policyDirty by remember { mutableStateOf(false) }

    // 扫描状态
    var viewState by remember { mutableStateOf(ViewState.IDLE) }
    var allSessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var selectedTools by remember { mutableStateOf(ChatTool.entries.toSet()) }

    // 初次加载策略
    LaunchedEffect(Unit) {
        policy = withContext(Dispatchers.IO) { RetentionStore.read() }
    }

    // 保存策略
    fun savePolicy() {
        if (!policyDirty) return
        scope.launch(Dispatchers.IO) {
            RetentionStore.write(policy)
            policyDirty = false
        }
    }

    // 扫描会话
    fun startScan() {
        if (viewState == ViewState.SCANNING) return
        viewState = ViewState.SCANNING
        scope.launch(Dispatchers.IO) {
            val sessions = scanAllChatSessions(selectedTools)
            withContext(Dispatchers.Main) {
                allSessions = sessions
                viewState = ViewState.SCAN_DONE
            }
        }
    }

    // 统计
    val now = remember { Instant.now() }
    val cutoffMillis = now.minus(policy.days.toLong(), ChronoUnit.DAYS).toEpochMilli()
    val staleCount = allSessions.count { it.updatedMillis < cutoffMillis }
    val staleBytes = allSessions.filter { it.updatedMillis < cutoffMillis }.sumOf { it.sizeBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
    ) {
        Text(
            "对话自动清理",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
        )

        // 自动清理策略卡片
        RetentionPolicyCard(
            policy = policy,
            onPolicyChange = { newPolicy ->
                policy = newPolicy
                policyDirty = true
            },
            onSave = ::savePolicy,
            isDirty = policyDirty,
        )

        // 扫描控制
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("扫描工具：", fontSize = 14.sp, color = AppColors.TextSecondary)
                ChatTool.entries.forEach { tool ->
                    val isSelected = tool in selectedTools
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) AppColors.Primary else AppColors.Surface3,
                        animationSpec = Motion.fast(),
                        label = "toolBg",
                    )
                    val fg by animateColorAsState(
                        targetValue = if (isSelected) AppColors.OnPrimary else AppColors.TextSecondary,
                        animationSpec = Motion.fast(),
                        label = "toolFg",
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
                            .background(bg)
                            .clickable(enabled = viewState != ViewState.SCANNING) {
                                selectedTools = if (isSelected) {
                                    selectedTools - tool
                                } else {
                                    selectedTools + tool
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            tool.displayName,
                            fontSize = 13.sp,
                            color = fg,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }

            Button(
                onClick = ::startScan,
                enabled = viewState != ViewState.SCANNING && selectedTools.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary,
                ),
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
            ) {
                Text(
                    when (viewState) {
                        ViewState.IDLE -> "扫描会话"
                        ViewState.SCANNING -> "扫描中…"
                        ViewState.SCAN_DONE -> "重新扫描"
                    },
                    fontSize = 14.sp,
                )
            }
        }

        // 统计卡片
        if (viewState == ViewState.SCAN_DONE) {
            StatsCard(
                policy = policy,
                totalCount = allSessions.size,
                totalBytes = allSessions.sumOf { it.sizeBytes },
                staleCount = staleCount,
                staleBytes = staleBytes,
            )
        }
    }
}

@Composable
private fun RetentionPolicyCard(
    policy: RetentionPolicy,
    onPolicyChange: (RetentionPolicy) -> Unit,
    onSave: () -> Unit,
    isDirty: Boolean,
) {
    var daysInput by remember(policy.days) { mutableStateOf(policy.days.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "自动清理策略",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (policy.enabled) "已启用 · 将自动删除超期会话" else "已停用",
                    fontSize = 12.sp,
                    color = if (policy.enabled) AppColors.Primary else AppColors.TextTertiary,
                )
            }

            Switch(
                checked = policy.enabled,
                onCheckedChange = { onPolicyChange(policy.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                ),
            )
        }

        AnimatedVisibility(
            visible = policy.enabled,
            enter = expandVertically(Motion.normal()) + fadeIn(Motion.normal()),
            exit = shrinkVertically(Motion.normal()) + fadeOut(Motion.fast()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AppColors.OutlineVariant.copy(alpha = 0.4f)),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("保留最近", fontSize = 14.sp, color = AppColors.TextSecondary)
                    BasicTextField(
                        value = daysInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 4) {
                                daysInput = input
                                input.toIntOrNull()?.coerceIn(1, 3650)?.let { days ->
                                    onPolicyChange(policy.copy(days = days))
                                }
                            }
                        },
                        modifier = Modifier
                            .width(60.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppColors.Surface3)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        cursorBrush = SolidColor(AppColors.Primary),
                    )
                    Text("天内的会话", fontSize = 14.sp, color = AppColors.TextSecondary)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.Optional.copy(alpha = 0.08f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AppColors.Optional,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "超过 ${policy.days} 天未更新的会话将在下次启动时自动删除，无法恢复",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        lineHeight = 16.sp,
                    )
                }

                if (isDirty) {
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = AppColors.Primary,
                            contentColor = AppColors.OnPrimary,
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("保存策略", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    policy: RetentionPolicy,
    totalCount: Int,
    totalBytes: Long,
    staleCount: Int,
    staleBytes: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "扫描结果",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
        ) {
            StatTile(
                label = "全部会话",
                value = "$totalCount 个",
                subtitle = formatBytes(totalBytes),
                color = AppColors.Primary,
                modifier = Modifier.weight(1f),
            )

            if (policy.enabled) {
                StatTile(
                    label = "超期会话",
                    value = "$staleCount 个",
                    subtitle = formatBytes(staleBytes),
                    color = if (staleCount > 0) AppColors.Optional else AppColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (policy.enabled && staleCount > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.OutlineVariant.copy(alpha = 0.4f)),
            )

            Text(
                "下次启动应用时将自动清理 $staleCount 个超期会话，释放 ${formatBytes(staleBytes)}",
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                lineHeight = 18.sp,
            )
        } else if (policy.enabled && staleCount == 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.OutlineVariant.copy(alpha = 0.4f)),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "所有会话均在保留期内，无需清理",
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface3)
            .padding(14.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            subtitle,
            fontSize = 11.sp,
            color = AppColors.TextTertiary,
        )
    }
}
