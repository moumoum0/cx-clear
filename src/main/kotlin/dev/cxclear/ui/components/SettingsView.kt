package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.AppMeta
import dev.cxclear.chats.RetentionAiPrompt
import dev.cxclear.scan.formatBytes
import dev.cxclear.storage.AppDir
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.AppPrefs
import dev.cxclear.storage.CleanHistory
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files

@Composable
fun SettingsView(modifier: Modifier = Modifier) {
    var showAbout by remember { mutableStateOf(false) }
    val blurRadius by animateDpAsState(
        targetValue = if (showAbout) 12.dp else 0.dp,
        animationSpec = Motion.normal(),
        label = "aboutBlur",
    )

    Box(modifier = modifier.fillMaxSize()) {
        SettingsListPage(
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius),
            onOpenAbout = { showAbout = true },
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
    }
}

@Composable
private fun SettingsListPage(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(AppPreferences.read()) }
    var historyTotal by remember { mutableStateOf(CleanHistory.totalBytes()) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var promptCopied by remember { mutableStateOf(false) }

    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        val next = transform(prefs)
        prefs = next
        scope.launch { withContext(Dispatchers.IO) { AppPreferences.write(next) } }
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
