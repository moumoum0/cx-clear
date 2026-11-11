package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.cxclear.AppMeta
import dev.cxclear.chats.RetentionAiPrompt
import dev.cxclear.platform.AutoStart
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import dev.cxclear.storage.AppDir
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.AppPrefs
import dev.cxclear.storage.CleanHistory
import dev.cxclear.ui.LocalThemeController
import dev.cxclear.storage.AppColorScheme
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.storage.ThemeMode
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files

@Composable
fun SettingsView(
    modifier: Modifier = Modifier,
) {
    var showAbout by remember { mutableStateOf(false) }
    var showAutoClean by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    Box(modifier = modifier.fillMaxSize()) {
        SettingsListPage(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .then(
                    if (showAbout || showAutoClean) {
                        Modifier.hazeBlur(
                            input = HazeInput.Content,
                            style = HazeBlurStyle { blurRadius(12.dp) },
                            performanceMode = HazePerformanceMode.Performance,
                        )
                    } else Modifier
                ),
            onOpenAbout = { showAbout = true },
            onOpenAutoClean = { showAutoClean = true },
        )

        AnimatedVisibility(
            visible = showAbout,
            enter = slideInHorizontally(Motion.normal()) { it / 4 } + fadeIn(Motion.normal()),
            exit = slideOutHorizontally(Motion.fast()) { it / 6 } + fadeOut(Motion.fast()),
        ) {
            AboutView(
                modifier = Modifier.fillMaxSize(),
                onBack = { showAbout = false },
            )
        }

        AnimatedVisibility(
            visible = showAutoClean,
            enter = slideInHorizontally(Motion.normal()) { it / 4 } + fadeIn(Motion.normal()),
            exit = slideOutHorizontally(Motion.fast()) { it / 6 } + fadeOut(Motion.fast()),
        ) {
            AutoCleanSettingsView(
                modifier = Modifier.fillMaxSize(),
                onBack = { showAutoClean = false },
            )
        }
    }
}

@Composable
private fun SettingsListPage(
    onOpenAbout: () -> Unit,
    onOpenAutoClean: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val themeController = LocalThemeController.current
    var prefs by remember { mutableStateOf(AppPreferences.read()) }
    var historyTotal by remember { mutableStateOf(CleanHistory.totalBytes()) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showColorSchemeDialog by remember { mutableStateOf(false) }
    var promptCopied by remember { mutableStateOf(false) }

    val isWindows = remember { currentOs() == HostOs.WINDOWS }
    var autostartOn by remember { mutableStateOf(false) }
    var autostartAvailable by remember { mutableStateOf(false) }
    var autostartError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isWindows) {
        if (!isWindows) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            autostartAvailable = AutoStart.installedExePath() != null
            autostartOn = AutoStart.isEnabled()
        }
    }

    fun toggleAutostart(checked: Boolean) {
        autostartOn = checked
        autostartError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val exe = AutoStart.installedExePath()
                when {
                    exe == null -> null
                    checked -> AutoStart.enable(exe)
                    else -> AutoStart.disable()
                }
            }
            when {
                // 开关已禁用时点不到这里；以防万一保持现状
                result == null -> Unit
                result.ok -> autostartOn = withContext(Dispatchers.IO) { AutoStart.isEnabled() }
                else -> {
                    autostartOn = !checked
                    autostartError = result.output.trim()
                        .ifEmpty { "注册表操作失败（代码 ${result.exitCode}）" }
                }
            }
        }
    }

    // 磁盘新鲜读改写，不整份覆盖：自动清理子页可能同时改了别的字段
    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        prefs = transform(prefs)
        scope.launch { withContext(Dispatchers.IO) { AppPreferences.update(transform) } }
    }

    LaunchedEffect(promptCopied) {
        if (!promptCopied) return@LaunchedEffect
        delay(2_000)
        promptCopied = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Normal,
            color = AppColors.TextPrimary,
        )

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
                item { SettingsGroupTitle("外观") }
                item {
                    SettingsItem(
                        icon = Icons.Default.DarkMode,
                        title = "主题模式",
                        subtitle = themeController.themeMode.displayName,
                        onClick = { showThemeModeDialog = true },
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = "配色方案",
                        subtitle = themeController.colorScheme.displayName,
                        onClick = { showColorSchemeDialog = true },
                    )
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
                item { SettingsGroupTitle("通用") }
                item {
                    SettingsSwitchItem(
                        icon = Icons.Default.Restore,
                        title = "记住上次打开的页面",
                        subtitle = "下次启动时回到离开前的页面",
                        checked = prefs.rememberLastScreen,
                        onCheckedChange = { checked ->
                            updatePrefs { it.copy(rememberLastScreen = checked) }
                        },
                    )
                }
                if (isWindows) {
                    item {
                        SettingsSwitchItem(
                            icon = Icons.Default.PowerSettingsNew,
                            title = "开机自启动",
                            subtitle = when {
                                autostartError != null -> autostartError!!
                                !autostartAvailable -> "仅安装版可用"
                                else -> "登录 Windows 后最小化启动，静默执行自动清理"
                            },
                            checked = autostartOn,
                            onCheckedChange = ::toggleAutostart,
                            enabled = autostartAvailable,
                        )
                    }
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.FolderOpen,
                        title = "打开配置目录",
                        subtitle = AppDir.dir()?.toString() ?: "主目录不可用",
                        onClick = { openConfigDir() },
                        enabled = AppDir.dir() != null,
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Default.DeleteForever,
                        title = "清空清理历史",
                        subtitle = if (historyTotal > 0L) {
                            "累计已记 ${formatBytes(historyTotal)}"
                        } else {
                            "暂无记录"
                        },
                        onClick = { showClearHistoryConfirm = true },
                        enabled = historyTotal > 0L,
                    )
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
                item { SettingsGroupTitle("自动清理") }
                item {
                    SettingsItem(
                        icon = Icons.Default.CleaningServices,
                        title = "自动清理",
                        subtitle = "总开关与清理完成通知",
                        onClick = onOpenAutoClean,
                    )
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
                item { SettingsGroupTitle("AI") }
                item {
                    SettingsItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "用 AI 写自动清理策略",
                        subtitle = if (promptCopied) {
                            "已复制到剪贴板"
                        } else {
                            "复制提示词，粘贴给任意 AI 助手"
                        },
                        onClick = {
                            copyTextToClipboard(RetentionAiPrompt.text)
                            promptCopied = true
                        },
                    )
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
                item { SettingsGroupTitle("其他") }
                item {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "关于",
                        subtitle = "版本 ${AppMeta.VERSION}",
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }

    if (showThemeModeDialog) {
        ThemeChoiceDialog(
            title = "主题模式",
            options = ThemeMode.entries.map { it to it.displayName },
            selected = themeController.themeMode,
            onSelected = {
                themeController.setThemeMode(it)
                showThemeModeDialog = false
            },
            onDismiss = { showThemeModeDialog = false },
        )
    }

    if (showColorSchemeDialog) {
        ThemeChoiceDialog(
            title = "配色方案",
            options = AppColorScheme.entries.map { it to "${it.displayName} · ${it.description}" },
            selected = themeController.colorScheme,
            onSelected = {
                themeController.setColorScheme(it)
                showColorSchemeDialog = false
            },
            onDismiss = { showColorSchemeDialog = false },
            subtitleOf = { it.description },
            titleOf = { it.displayName },
        )
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = {
                Text("清空清理历史", color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "将删除本地累计清理记录（不影响已删文件）。首页统计会归零。",
                    fontSize = 14.sp,
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { CleanHistory.clear() }
                            historyTotal = 0L
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Error,
                        contentColor = AppColors.OnPrimary,
                    ),
                    shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                ) {
                    Text("清空", fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text("取消", color = AppColors.TextSecondary, fontSize = 14.sp)
                }
            },
            containerColor = AppColors.Surface2,
        )
    }
}

@Composable
private fun <T> ThemeChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    titleOf: (T) -> String = { options.first { pair -> pair.first == it }.second },
    subtitleOf: ((T) -> String)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { (value, _) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = value == selected,
                                onClick = { onSelected(value) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AppColors.Primary,
                                unselectedColor = AppColors.Outline,
                            ),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = titleOf(value),
                                fontSize = 15.sp,
                                color = AppColors.TextPrimary,
                            )
                            subtitleOf?.let { subtitle ->
                                Text(
                                    text = subtitle(value),
                                    fontSize = 13.sp,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = AppColors.Primary, fontSize = 14.sp)
            }
        },
        containerColor = AppColors.Surface2,
    )
}

private fun openConfigDir() {
    val dir = AppDir.dir() ?: return
    runCatching {
        Files.createDirectories(dir)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(dir.toFile())
        }
    }
}

private fun copyTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
    }
}
