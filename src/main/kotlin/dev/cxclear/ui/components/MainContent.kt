package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.TargetKey
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.scanStream
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.AppPrefs
import dev.cxclear.storage.CleanHistory
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫描页外壳：只放状态机（ScanPhase / 分类 / 勾选 / 清理中）、页面路由与布局，
 * 具体渲染在 ScanTopBar / ScanResultView / DiskStatCards 里。
 */
@Composable
fun MainContent(
    currentScreen: Screen,
    modifier: Modifier = Modifier,
) {
    var prefs by remember { mutableStateOf<AppPrefs?>(null) }
    LaunchedEffect(Unit) {
        prefs = withContext(Dispatchers.IO) { AppPreferences.read() }
    }
    val initialPrefs = prefs ?: AppPrefs()
    var selectedTools by remember(initialPrefs) {
        mutableStateOf(initialPrefs.defaultTools.ifEmpty { setOf("codex", "claude", "cursor", "opencode") })
    }
    var scanPhase by remember { mutableStateOf(ScanPhase.IDLE) }
    var scanCategories by remember { mutableStateOf(emptyList<ScanCategory>()) }
    var selectedTargets by remember { mutableStateOf(emptySet<TargetKey>()) }
    var isCleaning by remember { mutableStateOf(false) }
    var showCleanConfirm by remember { mutableStateOf(false) }
    var cleanError by remember { mutableStateOf<String?>(null) }
    // 清理完成后 bump，驱动累计卡 / 磁盘卡重读磁盘。
    var cleanTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun startClean() {
        if (isCleaning) return
        val plansByKey = scanCategories
            .flatMap { it.items }
            .mapNotNull { item -> item.deletionPlan?.let { item.key to it } }
            .toMap()
        val requests = ALL_PROFILES.flatMap { profile ->
            profile.targets.mapNotNull { target ->
                val key = TargetKey(profile.id, target.id)
                val plan = plansByKey[key]
                if (key in selectedTargets && plan != null) CleanRequest(profile, target, plan) else null
            }
        }
        if (requests.isEmpty()) return
        isCleaning = true
        scope.launch {
            val errors = mutableListOf<String>()
            try {
                clean(requests).collect { event ->
                    when (event) {
                        is CleanEvent.AllDone -> {
                            // 用实测释放量，不用扫描预估。
                            CleanHistory.append(event.totalFreedBytes)
                        }
                        is CleanEvent.Blocked -> errors +=
                            "检测到 ${event.tools.joinToString("、")} 仍在运行。请完全退出后重新扫描。"
                        is CleanEvent.TargetDone -> event.error?.let { errors += "${event.label}：$it" }
                        is CleanEvent.Started -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += (e.message ?: "清理过程中发生未知错误")
            } finally {
                selectedTargets = emptySet()
                scanCategories = emptyList()
                scanPhase = ScanPhase.IDLE
                isCleaning = false
                cleanTick++
                cleanError = errors.distinct().joinToString("\n").ifBlank { null }
            }
        }
    }

    fun startScan() {
        if (isCleaning || scanPhase == ScanPhase.SCANNING || selectedTools.isEmpty()) return
        scanPhase = ScanPhase.SCANNING
        scanCategories = emptyList()
        selectedTargets = emptySet()
        scope.launch {
            val profiles = ALL_PROFILES.filter { it.id in selectedTools }
            var results = emptyList<ScanResult>()
            var spaces = emptyList<ToolSpaceResult>()

            scanStream(profiles).collect { event ->
                when (event) {
                    is ScanEvent.Started -> Unit
                    is ScanEvent.SpaceScanned -> spaces = event.spaces
                    is ScanEvent.TargetsScanned -> results = event.results
                }
                scanCategories = buildCategories(
                    profiles = profiles,
                    results = results,
                    totalToolBytes = spaces.sumOf { it.bytes },
                )
            }

            // 跟 defaultSelected 对齐，不能写死 risk == SAFE。
            selectedTargets = scanCategories
                .flatMap { it.items }
                .filter { it.defaultSelected && it.bytes > 0L }
                .mapTo(mutableSetOf()) { it.key }
            scanPhase = ScanPhase.DONE
        }
    }

    val selectedBytes = scanCategories
        .flatMap { it.items }
        .filter { it.key in selectedTargets }
        .sumOf { it.bytes }

    if (currentScreen == Screen.CHATS) {
        ChatsView(modifier = modifier)
        return
    }
    if (currentScreen == Screen.SETTINGS) {
        SettingsView(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp)
    ) {
        TopBar(
            selectedTools = selectedTools,
            onToolToggle = { id ->
                // 换工具只改勾选，不抹已有扫描结果。
                if (scanPhase != ScanPhase.SCANNING) {
                    selectedTools = if (id in selectedTools) selectedTools - id else selectedTools + id
                }
            },
            scanPhase = scanPhase,
            onStartScan = ::startScan,
            showClean = scanPhase == ScanPhase.DONE,
            isCleaning = isCleaning,
            selectedBytes = selectedBytes,
            cleanEnabled = !isCleaning && selectedTargets.isNotEmpty() && selectedBytes > 0L,
            onRequestClean = { showCleanConfirm = true },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(AppDimensions.Radius.dp))
                .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp)),
        ) {
            ScanView(
                phase = scanPhase,
                categories = scanCategories,
                selectedTargets = selectedTargets,
                onTargetToggle = { targetKey ->
                    selectedTargets = if (targetKey in selectedTargets) {
                        selectedTargets - targetKey
                    } else {
                        selectedTargets + targetKey
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp)
        ) {
            CleaningStatsCard(refreshKey = cleanTick, modifier = Modifier.weight(1f))
            DiskUsageCard(refreshKey = cleanTick, modifier = Modifier.weight(1f))
        }
    }

    if (showCleanConfirm) {
        val hasOptional = scanCategories
            .flatMap { it.items }
            .any { it.key in selectedTargets && it.risk == Risk.OPTIONAL }
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text("确认清理", color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将删除已选择的 ${formatBytes(selectedBytes)} 内容，此操作不可恢复。",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                    )
                    if (hasOptional) {
                        Text(
                            "其中包含会话历史等不可再生数据，删除后无法找回。",
                            fontSize = 13.sp,
                            color = AppColors.Error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanConfirm = false
                        startClean()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Error,
                        contentColor = AppColors.OnPrimary,
                    ),
                    shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                ) {
                    Text("确认清理", fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanConfirm = false }) {
                    Text("取消", color = AppColors.TextSecondary, fontSize = 14.sp)
                }
            },
            containerColor = AppColors.Surface2,
        )
    }

    cleanError?.let { message ->
        AlertDialog(
            onDismissRequest = { cleanError = null },
            title = { Text("清理未完全执行", color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = { Text(message, fontSize = 14.sp, color = AppColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { cleanError = null }) {
                    Text("知道了", color = AppColors.Primary, fontSize = 14.sp)
                }
            },
            containerColor = AppColors.Surface2,
        )
    }
}
