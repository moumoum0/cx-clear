package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatSessionSummary
import dev.cxclear.chats.ChatTool
import dev.cxclear.chats.RetentionPolicy
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.resources.Res
import dev.cxclear.resources.claude
import dev.cxclear.resources.codex
import dev.cxclear.resources.cursor
import dev.cxclear.scan.formatBytes
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

private enum class ViewState { IDLE, SCANNING, SCAN_DONE }

private enum class ChatsMode { MANUAL, AUTO }

/** 对话管理顶栏筛选：all = Codex + Claude；Cursor 不可选。 */
private const val TOOL_FILTER_ALL = "all"

@Composable
fun ChatsView(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ChatsMode.MANUAL) }
    var selectedTool by remember { mutableStateOf(TOOL_FILTER_ALL) }

    var policy by remember { mutableStateOf(RetentionPolicy()) }
    var policyDirty by remember { mutableStateOf(false) }

    var viewState by remember { mutableStateOf(ViewState.IDLE) }
    var allSessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        policy = withContext(Dispatchers.IO) { RetentionStore.read() }
    }

    fun savePolicy() {
        if (!policyDirty) return
        // rememberCoroutineScope 已绑 Compose 调度器；桌面没有 Dispatchers.Main。
        scope.launch {
            withContext(Dispatchers.IO) { RetentionStore.write(policy) }
            policyDirty = false
        }
    }

    fun resolveTools(filter: String): Set<ChatTool> = when (filter) {
        TOOL_FILTER_ALL -> ChatTool.entries.toSet()
        else -> ChatTool.entries.filter { it.id == filter }.toSet()
    }

    // 进入手动模式 / 切换工具筛选时自动扫，不再依赖按钮。
    LaunchedEffect(selectedTool, mode) {
        if (mode != ChatsMode.MANUAL) return@LaunchedEffect
        val tools = resolveTools(selectedTool)
        if (tools.isEmpty()) return@LaunchedEffect
        viewState = ViewState.SCANNING
        val sessions = withContext(Dispatchers.IO) { scanAllChatSessions(tools) }
        allSessions = sessions
        viewState = ViewState.SCAN_DONE
    }

    val now = remember { Instant.now() }
    val cutoffMillis = now.minus(policy.days.toLong(), ChronoUnit.DAYS).toEpochMilli()
    val staleCount = allSessions.count { it.updatedMillis < cutoffMillis }
    val staleBytes = allSessions.filter { it.updatedMillis < cutoffMillis }.sumOf { it.sizeBytes }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
    ) {
        ChatsTopBar(
            selectedTool = selectedTool,
            onToolSelect = { selectedTool = it },
            mode = mode,
            onModeChange = { mode = it },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp)),
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(Motion.normal()) togetherWith fadeOut(Motion.fast()))
                        .using(SizeTransform(clip = false))
                },
                label = "chatsMode",
            ) { currentMode ->
                when (currentMode) {
                    ChatsMode.MANUAL -> ManualPane(
                        viewState = viewState,
                        allSessions = allSessions,
                        policy = policy,
                        staleCount = staleCount,
                        staleBytes = staleBytes,
                    )
                    ChatsMode.AUTO -> AutoPane(
                        policy = policy,
                        onPolicyChange = { newPolicy ->
                            policy = newPolicy
                            policyDirty = true
                        },
                        onSave = ::savePolicy,
                        isDirty = policyDirty,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatsTopBar(
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
            // 单选：所有 / Codex / Claude；Cursor 会话不在对话管理范围内，仅作视觉占位不可选。
            AllFilterButton(isSelected = selectedTool == TOOL_FILTER_ALL) {
                onToolSelect(TOOL_FILTER_ALL)
            }
            ToolIcon("Codex", Res.drawable.codex, selectedTool == "codex") { onToolSelect("codex") }
            ToolIcon("Claude", Res.drawable.claude, selectedTool == "claude") { onToolSelect("claude") }
            ToolIcon("Cursor", Res.drawable.cursor, isSelected = false, enabled = false) {}
        }

        ModeSegmentedControl(
            mode = mode,
            onModeChange = onModeChange,
        )
    }
}

@Composable
private fun AllFilterButton(
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
private fun ModeSegmentedControl(
    mode: ChatsMode,
    onModeChange: (ChatsMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.height(ModeControlHeight),
    ) {
        val colors = SegmentedButtonDefaults.colors(
            activeContainerColor = AppColors.Primary,
            activeContentColor = AppColors.OnPrimary,
            inactiveContainerColor = AppColors.Surface3,
            inactiveContentColor = AppColors.TextSecondary,
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

@Composable
private fun ManualPane(
    viewState: ViewState,
    allSessions: List<ChatSessionSummary>,
    policy: RetentionPolicy,
    staleCount: Int,
    staleBytes: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimensions.SpacingLarge.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
    ) {
        when (viewState) {
            ViewState.IDLE, ViewState.SCANNING -> ManualPaneSkeleton()
            ViewState.SCAN_DONE -> StatsCard(
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
private fun ManualPaneSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .width(72.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppColors.Surface3),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
        ) {
            repeat(2) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Surface3)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Surface2),
                    )
                    Box(
                        Modifier
                            .width(80.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Surface2),
                    )
                    Box(
                        Modifier
                            .width(48.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Surface2),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPane(
    policy: RetentionPolicy,
    onPolicyChange: (RetentionPolicy) -> Unit,
    onSave: () -> Unit,
    isDirty: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimensions.SpacingLarge.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        RetentionPolicyCard(
            policy = policy,
            onPolicyChange = onPolicyChange,
            onSave = onSave,
            isDirty = isDirty,
        )
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
            .background(AppColors.Surface1)
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
                Spacer(modifier = Modifier.height(3.dp))
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
            enter = fadeIn(Motion.normal()),
            exit = fadeOut(Motion.fast()),
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
                            containerColor = AppColors.Primary,
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
            .background(AppColors.Surface1)
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
    color: Color,
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
        Spacer(modifier = Modifier.height(6.dp))
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
