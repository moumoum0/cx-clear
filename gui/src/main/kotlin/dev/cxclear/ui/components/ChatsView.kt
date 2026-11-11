package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.cxclear.model.ChatDeleteResult
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.tools.chatTools
import dev.cxclear.chats.ChatScanCache
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionRunner
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
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

internal const val TOOL_FILTER_ALL = "all"

private const val SCAN_SNAPSHOT_INTERVAL_MS = 800L

private fun resolveTools(filter: String): Set<ChatTool> = when (filter) {
    TOOL_FILTER_ALL -> chatTools().toSet()
    else -> chatTools().filter { it.id == filter }.toSet()
}

private fun filterCachedSessions(
    sessions: List<ChatSessionSummary>,
    filter: String,
): List<ChatSessionSummary> {
    val tools = resolveTools(filter)
    if (tools.size == chatTools().size) return sessions
    return sessions.filter { it.tool in tools }
}

@Composable
fun ChatsView(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val initialPrefs = remember { AppPreferences.read() }

    var mode by remember { mutableStateOf(ChatsMode.AUTO) }
    var selectedTool by remember { mutableStateOf(TOOL_FILTER_ALL) }

    var config by remember { mutableStateOf(RetentionConfig()) }
    val enabledRuleCount = remember(config) { config.rules.count { it.enabled } }

    val cachedOnEnter = remember { ChatScanCache.snapshot() }
    var viewState by remember {
        mutableStateOf(if (cachedOnEnter != null) ViewState.SCAN_DONE else ViewState.IDLE)
    }
    var allSessions by remember {
        mutableStateOf(cachedOnEnter ?: emptyList())
    }
    var foundCount by remember { mutableIntStateOf(cachedOnEnter?.size ?: 0) }
    var rescanToken by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsWarning by remember { mutableStateOf(false) }

    var autoCleanEnabled by remember { mutableStateOf(initialPrefs.autoCleanEnabled) }
    // 「暂未开启自动清理」胶囊的就地子页：不切屏，导航栏停在对话管理
    var showAutoCleanSettings by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    LaunchedEffect(Unit) {
        config = withContext(Dispatchers.IO) { RetentionStore.read() }
    }

    fun updateConfig(updated: RetentionConfig) {
        config = updated
        scope.launch { withContext(Dispatchers.IO) { RetentionStore.write(updated) } }
    }

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
            val latestCount = AtomicInteger(0)
            val sessions = coroutineScope {
                val job = async(Dispatchers.IO) {
                    scanAllChatSessions(chatTools().toSet()) { count, _ ->
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
        // 每进程只跑一次，再跑会删完又扫、扫完又删，成环。
        if (!ChatScanCache.autoRunDone) {
            ChatScanCache.markAutoRunDone()
            val prefs = withContext(Dispatchers.IO) { AppPreferences.read() }
            val result = RetentionRunner.runIfNeeded(sessions)
            if (result.freedBytes > 0L) {
                withContext(Dispatchers.IO) { CleanHistory.append(result.freedBytes) }
            }
            if (prefs.autoCleanNotify) {
                notice = deleteNotice(result, auto = true)
                noticeIsWarning = result.blockedTools.isNotEmpty() || result.errors.isNotEmpty()
            }
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .then(
                    if (showAutoCleanSettings) {
                        Modifier.hazeBlur(
                            input = HazeInput.Content,
                            style = HazeBlurStyle { blurRadius(12.dp) },
                            performanceMode = HazePerformanceMode.Performance,
                        )
                    } else Modifier
                )
                .background(AppColors.Surface1)
                .padding(AppDimensions.SpacingLarge.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
        ) {
            ChatsTopBar(
                selectedTool = selectedTool,
                onToolSelect = { selectedTool = it },
                mode = mode,
                onModeChange = { mode = it },
                autoCleanEnabled = autoCleanEnabled,
                enabledRuleCount = enabledRuleCount,
                totalRuleCount = config.rules.size,
                onOpenAutoCleanSettings = { showAutoCleanSettings = true },
            )

            notice?.let { message ->
                ChatsNotice(
                    message = message,
                    warning = noticeIsWarning,
                    onDismiss = { notice = null },
                )
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
                        ChatsMode.MANUAL -> ChatsManualPane(
                            modifier = Modifier.fillMaxSize(),
                            isScanning = viewState != ViewState.SCAN_DONE,
                            foundCount = foundCount,
                            allSessions = displayedSessions,
                            nowMillis = now.toEpochMilli(),
                            onDeleted = { result ->
                                notice = deleteNotice(result, auto = false)
                                noticeIsWarning = result.blockedTools.isNotEmpty() || result.errors.isNotEmpty()
                                if (result.deletedSessions > 0) {
                                    ChatScanCache.invalidate()
                                    rescanToken++
                                }
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

        // 自动清理子页就地滑入，动画与设置子页一致；返回时刷新总开关，胶囊即时收起
        AnimatedVisibility(
            visible = showAutoCleanSettings,
            enter = slideInHorizontally(Motion.normal()) { it / 4 } + fadeIn(Motion.normal()),
            exit = slideOutHorizontally(Motion.fast()) { it / 6 } + fadeOut(Motion.fast()),
        ) {
            AutoCleanSettingsView(
                modifier = Modifier.fillMaxSize(),
                onBack = {
                    showAutoCleanSettings = false
                    scope.launch {
                        autoCleanEnabled = withContext(Dispatchers.IO) { AppPreferences.read().autoCleanEnabled }
                    }
                },
            )
        }
    }
}

