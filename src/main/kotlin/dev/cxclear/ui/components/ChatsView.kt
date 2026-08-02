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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private enum class ViewState { IDLE, SCANNING, SCAN_DONE }

private enum class ChatsMode { MANUAL, AUTO }

/** 对话管理顶栏筛选：all = Codex + Claude；Cursor 不可选。 */
private const val TOOL_FILTER_ALL = "all"

/** 与扫描页 `SNAPSHOT_INTERVAL_MS` 对齐：进度数字每 0.5s 推一次。 */
private const val SCAN_SNAPSHOT_INTERVAL_MS = 500L

@Composable
fun ChatsView(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ChatsMode.MANUAL) }
    var selectedTool by remember { mutableStateOf(TOOL_FILTER_ALL) }

    var policy by remember { mutableStateOf(RetentionPolicy()) }
    var policyDirty by remember { mutableStateOf(false) }

    var viewState by remember { mutableStateOf(ViewState.IDLE) }
    var allSessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var foundCount by remember { mutableIntStateOf(0) }
    // 删除完成后自增，触发下面的扫描 LaunchedEffect 重跑（清单必须重扫，不能就地改）。
    var rescanToken by remember { mutableIntStateOf(0) }

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
    LaunchedEffect(selectedTool, mode, rescanToken) {
        if (mode != ChatsMode.MANUAL) return@LaunchedEffect
        val tools = resolveTools(selectedTool)
        if (tools.isEmpty()) return@LaunchedEffect
        viewState = ViewState.SCANNING
        foundCount = 0
        // worker 边扫边累加；UI 按固定节拍读快照，扫完立刻再推一次收尾。
        val latestCount = AtomicInteger(0)
        var firstPublishAtNanos = -1L
        val sessions = coroutineScope {
            val job = async(Dispatchers.IO) {
                scanAllChatSessions(tools) { count, _ -> latestCount.set(count) }
            }
            while (true) {
                val finished = withTimeoutOrNull(SCAN_SNAPSHOT_INTERVAL_MS) {
                    job.join()
                    true
                } == true
                foundCount = latestCount.get()
                if (firstPublishAtNanos < 0L) firstPublishAtNanos = System.nanoTime()
                if (finished) break
            }
            job.await()
        }
        // 最短撑到首次推数后的翻牌播完，避免扫太快时加载态被结果页掐断。
        val flipMs = Motion.FlipMs.toLong()
        val remainingFlipMs = if (firstPublishAtNanos < 0L) {
            flipMs
        } else {
            val elapsedMs = (System.nanoTime() - firstPublishAtNanos) / 1_000_000L
            (flipMs - elapsedMs).coerceAtLeast(0L)
        }
        if (remainingFlipMs > 0L) delay(remainingFlipMs)
        allSessions = sessions
        viewState = ViewState.SCAN_DONE
    }

    val now = remember { Instant.now() }

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
                .weight(1f),
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
                        foundCount = foundCount,
                        allSessions = allSessions,
                        nowMillis = now.toEpochMilli(),
                        onRescan = { rescanToken++ },
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
            activeBorderColor = AppColors.OutlineVariant,
            inactiveContainerColor = AppColors.Surface3,
            inactiveContentColor = AppColors.TextSecondary,
            inactiveBorderColor = AppColors.OutlineVariant,
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
    foundCount: Int,
    allSessions: List<ChatSessionSummary>,
    nowMillis: Long,
    onRescan: () -> Unit,
) {
    ChatsManualPane(
        modifier = Modifier.fillMaxSize(),
        isScanning = viewState != ViewState.SCAN_DONE,
        foundCount = foundCount,
        allSessions = allSessions,
        nowMillis = nowMillis,
        onDeleted = { _, _ -> onRescan() },
    )
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

