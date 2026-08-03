package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatScanCache
import dev.cxclear.chats.ChatSessionSummary
import dev.cxclear.chats.ChatTool
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionRunner
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.scan.formatBytes
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
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

/** 顶栏筛选值 → 参与展示的工具集合。 */
private fun resolveTools(filter: String): Set<ChatTool> = when (filter) {
    TOOL_FILTER_ALL -> ChatTool.entries.toSet()
    else -> ChatTool.entries.filter { it.id == filter }.toSet()
}

/** 从全量缓存裁出当前筛选要展示的会话。 */
private fun filterCachedSessions(
    sessions: List<ChatSessionSummary>,
    filter: String,
): List<ChatSessionSummary> {
    val tools = resolveTools(filter)
    if (tools.size == ChatTool.entries.size) return sessions
    return sessions.filter { it.tool in tools }
}

@Composable
fun ChatsView(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val initialPrefs = remember { AppPreferences.read() }

    var mode by remember {
        mutableStateOf(
            if (initialPrefs.defaultChatsMode == "auto") ChatsMode.AUTO else ChatsMode.MANUAL
        )
    }
    var selectedTool by remember { mutableStateOf(TOOL_FILTER_ALL) }

    var config by remember { mutableStateOf(RetentionConfig()) }

    // 有进程缓存时直接进结果态，避免切页回来再闪一遍扫描。
    val cachedOnEnter = remember { ChatScanCache.snapshot() }
    var viewState by remember {
        mutableStateOf(if (cachedOnEnter != null) ViewState.SCAN_DONE else ViewState.IDLE)
    }
    var allSessions by remember {
        mutableStateOf(cachedOnEnter ?: emptyList())
    }
    var foundCount by remember { mutableIntStateOf(cachedOnEnter?.size ?: 0) }
    // 删除完成后 invalidate 缓存并自增，触发下面的扫描 LaunchedEffect 重跑
    //（清单必须重扫，不能就地改）。
    var rescanToken by remember { mutableIntStateOf(0) }

    // 自动清理提示；闸门在 ChatScanCache.autoRunDone，不因导航重置。
    var autoNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        config = withContext(Dispatchers.IO) { RetentionStore.read() }
    }

    /** 策略改动即时落盘，不设「保存」按钮——开关拨了就该生效。 */
    fun updateConfig(updated: RetentionConfig) {
        config = updated
        // rememberCoroutineScope 已绑 Compose 调度器；桌面没有 Dispatchers.Main。
        scope.launch { withContext(Dispatchers.IO) { RetentionStore.write(updated) } }
    }

    // 进入手动模式时：有缓存直接用；无缓存或删除失效后才扫全量。
    // 切工具筛选不重扫——展示层从全量缓存裁剪（与首页扫描页「换工具不抹结果」同哲学）。
    // 默认落在自动页时也要跑完进程级扫描+自动清理，否则总开关永远摸不到会话。
    LaunchedEffect(mode, rescanToken) {
        if (mode != ChatsMode.MANUAL && ChatScanCache.autoRunDone) return@LaunchedEffect

        val cached = ChatScanCache.snapshot()
        if (cached != null) {
            allSessions = cached
            foundCount = cached.size
            viewState = ViewState.SCAN_DONE
        } else {
            viewState = ViewState.SCANNING
            foundCount = 0
            // worker 边扫边累加；UI 按固定节拍读快照，扫完立刻再推一次收尾。
            // 加载态是否离开由手动面板等翻牌 settle 后再切，这里不再按首次推数估算等待。
            // 始终扫全集，保证缓存对任意筛选都完整；自动保留也因此能看到全量。
            val latestCount = AtomicInteger(0)
            val sessions = coroutineScope {
                val job = async(Dispatchers.IO) {
                    scanAllChatSessions(ChatTool.entries.toSet()) { count, _ ->
                        latestCount.set(count)
                    }
                }
                while (true) {
                    val finished = withTimeoutOrNull(SCAN_SNAPSHOT_INTERVAL_MS) {
                        job.join()
                        true
                    } == true
                    foundCount = latestCount.get()
                    if (finished) break
                }
                job.await()
            }
            ChatScanCache.update(sessions)
            allSessions = sessions
            viewState = ViewState.SCAN_DONE
        }

        val sessions = allSessions
        // 首次扫完按已保存的策略执行一次。每进程只跑一次：删完要重扫，
        // 若不加闸门，重扫又会触发执行，成为循环。
        // 缓存始终是全量，执行器因此看到全部会话，与顶栏筛选无关。
        if (!ChatScanCache.autoRunDone) {
            ChatScanCache.markAutoRunDone()
            val prefs = withContext(Dispatchers.IO) { AppPreferences.read() }
            val result = RetentionRunner.runIfNeeded(sessions)
            if (result.freedBytes > 0L) {
                withContext(Dispatchers.IO) { CleanHistory.append(result.freedBytes) }
            }
            autoNotice = when {
                !prefs.autoCleanNotify -> null
                result.blockedTools.isNotEmpty() ->
                    "${result.blockedTools.joinToString("、")} 正在运行，自动清理已跳过其会话"
                result.deletedSessions > 0 ->
                    "自动清理已删除 ${result.deletedSessions} 个会话 · ${formatBytes(result.freedBytes)}"
                else -> null
            }
            // 删过东西，当前清单已失效，必须重扫而不是就地改。
            if (result.deletedSessions > 0) {
                ChatScanCache.invalidate()
                rescanToken++
            }
        }
    }

    val now = remember { Instant.now() }
    val displayedSessions = remember(allSessions, selectedTool) {
        filterCachedSessions(allSessions, selectedTool)
    }

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

        autoNotice?.let { message ->
            AutoCleanNotice(message = message, onDismiss = { autoNotice = null })
        }

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
                        allSessions = displayedSessions,
                        nowMillis = now.toEpochMilli(),
                        onRescan = {
                            ChatScanCache.invalidate()
                            rescanToken++
                        },
                    )
                    ChatsMode.AUTO -> ChatsAutoPane(
                        modifier = Modifier.fillMaxSize(),
                        config = config,
                        onConfigChange = ::updateConfig,
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

/**
 * 自动清理的结果提示。
 *
 * 自动删除不该静默发生：本次进程删掉了什么、或因工具在运行而跳过了什么，
 * 都在这里交代一次，用户手动关掉才消失。
 */
@Composable
private fun AutoCleanNotice(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.PrimaryContainer)
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = AppColors.Primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            message,
            fontSize = 12.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭",
            tint = AppColors.TextSecondary,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDismiss),
        )
    }
}
