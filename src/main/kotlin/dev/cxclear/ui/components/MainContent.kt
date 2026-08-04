package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.CleanTarget
import dev.cxclear.resources.Res
import dev.cxclear.resources.claude
import dev.cxclear.resources.codex
import dev.cxclear.resources.cursor
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import dev.cxclear.model.TargetKey
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
import dev.cxclear.storage.DailyClean
import dev.cxclear.storage.DiskUsage
import dev.cxclear.storage.DiskUsageReader
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.formatBytes
import dev.cxclear.scan.scanStream
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private enum class ScanPhase { IDLE, SCANNING, DONE }

private data class ScanCategory(
    val id: String,
    val label: String,
    val bytes: Long,
    val color: Color,
    val items: List<ScanTargetItem> = emptyList(),
)

private data class ScanTargetItem(
    val key: TargetKey,
    val label: String,
    val description: String,
    val bytes: Long,
    val risk: Risk,
    val defaultSelected: Boolean,
    val deletionPlan: DeletionPlan?,
)

@Composable
fun MainContent(
    currentScreen: Screen,
    modifier: Modifier = Modifier,
) {
    val initialPrefs = remember { AppPreferences.read() }
    var selectedTools by remember {
        mutableStateOf(initialPrefs.defaultTools.ifEmpty { setOf("codex") })
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

private fun buildCategories(
    profiles: List<dev.cxclear.model.ToolProfile>,
    results: List<ScanResult>,
    totalToolBytes: Long,
): List<ScanCategory> {
    val resultByTarget = results.associateBy { TargetKey(it.toolId, it.targetId) }
    val targets = profiles.flatMap { profile -> profile.targets.map { profile to it } }

    fun item(profile: dev.cxclear.model.ToolProfile, target: CleanTarget): ScanTargetItem {
        val key = TargetKey(profile.id, target.id)
        val result = resultByTarget.getValue(key)
        return ScanTargetItem(
            key = key,
            label = "${profile.name} · ${target.label}",
            description = target.description,
            bytes = result.bytes,
            risk = target.risk,
            defaultSelected = target.defaultSelected,
            deletionPlan = result.deletionPlan,
        )
    }

    val packageItems = targets
        .filter { (profile, target) ->
            resultByTarget[TargetKey(profile.id, target.id)]?.exists == true &&
                target.risk == Risk.SAFE &&
                listOf("plugins", "downloads", "sandbox", "vendor", "extension", "cached", "runtime").any {
                    target.id.contains(it)
                }
        }
        .map { (profile, target) -> item(profile, target) }
    val workingItems = targets
        .filter { (profile, target) ->
            val key = TargetKey(profile.id, target.id)
            resultByTarget[key]?.exists == true &&
                target.risk == Risk.SAFE &&
                packageItems.none { it.key == key }
        }
        .map { (profile, target) -> item(profile, target) }
    val historyItems = targets
        .filter { (profile, target) ->
            resultByTarget[TargetKey(profile.id, target.id)]?.exists == true &&
                target.risk == Risk.OPTIONAL
        }
        .map { (profile, target) -> item(profile, target) }

    val knownBytes = (packageItems + workingItems + historyItems).sumOf { it.bytes }
    val retainedBytes = (totalToolBytes - knownBytes).coerceAtLeast(0L)

    return listOf(
        ScanCategory(
            id = "retained",
            label = "应用保留数据",
            bytes = retainedBytes,
            color = AppColors.CategoryRetained,
        ),
        ScanCategory(
            id = "packages",
            label = "插件与安装缓存",
            bytes = packageItems.sumOf { it.bytes },
            color = AppColors.CategoryPackages,
            items = packageItems,
        ),
        ScanCategory(
            id = "working",
            label = "日志与临时文件",
            bytes = workingItems.sumOf { it.bytes },
            color = AppColors.CategoryWorking,
            items = workingItems,
        ),
        ScanCategory(
            id = "history",
            label = "历史与会话",
            bytes = historyItems.sumOf { it.bytes },
            color = AppColors.CategoryHistory,
            items = historyItems,
        ),
    )
}

private fun emptyScanCategories(): List<ScanCategory> = listOf(
    ScanCategory(
        id = "retained",
        label = "应用保留数据",
        bytes = 0L,
        color = AppColors.CategoryRetained,
    ),
    ScanCategory(
        id = "packages",
        label = "插件与安装缓存",
        bytes = 0L,
        color = AppColors.CategoryPackages,
    ),
    ScanCategory(
        id = "working",
        label = "日志与临时文件",
        bytes = 0L,
        color = AppColors.CategoryWorking,
    ),
    ScanCategory(
        id = "history",
        label = "历史与会话",
        bytes = 0L,
        color = AppColors.CategoryHistory,
    ),
)

@Composable
private fun ScanView(
    phase: ScanPhase,
    categories: List<ScanCategory>,
    selectedTargets: Set<TargetKey>,
    onTargetToggle: (TargetKey) -> Unit,
) {
    val displayCategories = categories.ifEmpty { emptyScanCategories() }
    ScanResultView(
        categories = displayCategories,
        isScanning = phase != ScanPhase.DONE,
        showCylinderSweep = phase == ScanPhase.SCANNING,
        totalBytes = displayCategories.sumOf { it.bytes },
        selectedTargets = selectedTargets,
        onTargetToggle = onTargetToggle,
    )
}

@Composable
private fun ScanResultView(
    categories: List<ScanCategory>,
    isScanning: Boolean,
    totalBytes: Long,
    selectedTargets: Set<TargetKey>,
    onTargetToggle: (TargetKey) -> Unit,
    showCylinderSweep: Boolean = isScanning,
) {
    var expandedCategoryId by remember(categories) { mutableStateOf<String?>(null) }
    val selectedBytes = categories
        .flatMap { it.items }
        .filter { it.key in selectedTargets }
        .sumOf { it.bytes }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 42.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        // 居中展开会被裁顶。
        verticalAlignment = Alignment.Top,
    ) {
        StorageCylinder(
            categories = categories,
            isScanning = isScanning,
            showSweep = showCylinderSweep,
            modifier = Modifier.width(170.dp).fillMaxHeight(),
        )

        val cleanableBytes = categories
            .filter { it.id != "retained" }
            .sumOf { it.bytes }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = isScanning,
                    transitionSpec = {
                        fadeIn(Motion.normal()) togetherWith fadeOut(Motion.fast())
                    },
                    label = "scanTitle",
                ) { scanning ->
                    Text(
                        text = if (scanning) "已找到 " else "应用共占用 ",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                    )
                }
                FlipBytesText(
                    bytes = totalBytes,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            AnimatedContent(
                targetState = isScanning,
                transitionSpec = {
                    fadeIn(Motion.normal()) togetherWith fadeOut(Motion.fast())
                },
                label = "scanSubtitle",
            ) { scanning ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) {
                        Text("可清理共 ", fontSize = 12.sp, color = AppColors.TextTertiary)
                        FlipBytesText(
                            bytes = cleanableBytes,
                            fontSize = 12.sp,
                            color = AppColors.TextTertiary,
                        )
                    } else {
                        Text("已选择 ", fontSize = 12.sp, color = AppColors.TextTertiary)
                        FlipBytesText(
                            bytes = selectedBytes,
                            fontSize = 12.sp,
                            color = AppColors.TextTertiary,
                        )
                        Text(" · 可清理共 ", fontSize = 12.sp, color = AppColors.TextTertiary)
                        FlipBytesText(
                            bytes = cleanableBytes,
                            fontSize = 12.sp,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val selectedFraction = if (isScanning) {
                if (totalBytes > 0L) (cleanableBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            } else {
                if (cleanableBytes > 0L) (selectedBytes.toFloat() / cleanableBytes).coerceIn(0f, 1f) else 0f
            }
            val animatedFraction by animateFloatAsState(
                targetValue = selectedFraction,
                animationSpec = Motion.medium(),
                label = "selectedFraction",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(AppColors.Surface3, RoundedCornerShape(99.dp)),
            ) {
                if (animatedFraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedFraction)
                            .fillMaxHeight()
                            .background(AppColors.Primary, RoundedCornerShape(99.dp)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            categories.forEach { category ->
                val canExpand = !isScanning && category.items.isNotEmpty()
                val isExpanded = expandedCategoryId == category.id
                val isRetained = category.id == "retained"
                val targetFraction = if (!isScanning && totalBytes > 0L) {
                    (category.bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else 0f
                val fraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = Motion.medium(),
                    label = "categoryFraction",
                )
                val chevronRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = Motion.fast(),
                    label = "chevron",
                )
                val cardAlpha = when {
                    isRetained -> 0.4f
                    isExpanded -> 0.92f
                    else -> 0.72f
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Surface3.copy(alpha = cardAlpha)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canExpand) {
                                expandedCategoryId = if (isExpanded) null else category.id
                            },
                    ) {
                        // matchParentSize，别把分类头撑高。
                        if (!isRetained && fraction > 0f) {
                            Box(Modifier.matchParentSize()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(category.color.copy(alpha = if (isExpanded) 0.18f else 0.14f)),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isRetained) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .border(1.dp, AppColors.OutlineVariant, RoundedCornerShape(99.dp))
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(category.color, RoundedCornerShape(99.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    category.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isExpanded) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isRetained) AppColors.TextTertiary else AppColors.TextSecondary,
                                )
                                if (isRetained) {
                                    Text("应用运行所需，不提供清理", fontSize = 10.sp, color = AppColors.TextTertiary)
                                }
                            }

                            // 扫描中 retained 在变，扫完再出数。
                            if (!(isScanning && isRetained)) {
                                FlipBytesText(
                                    bytes = category.bytes,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isRetained) AppColors.TextSecondary else AppColors.TextPrimary,
                                )
                            }
                            if (canExpand) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "收起" else "展开",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(chevronRotation),
                                    tint = AppColors.TextTertiary,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(Motion.normal()) + fadeIn(Motion.normal()),
                        exit = shrinkVertically(Motion.normal()) + fadeOut(Motion.fast()),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(AppColors.OutlineVariant.copy(alpha = 0.45f)),
                            )
                            category.items.forEachIndexed { index, target ->
                                if (index > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 42.dp)
                                            .height(1.dp)
                                            .background(AppColors.OutlineVariant.copy(alpha = 0.28f)),
                                    )
                                }
                                TargetSelectionRow(
                                    target = target,
                                    checked = target.key in selectedTargets,
                                    accent = category.color,
                                    onCheckedChange = { onTargetToggle(target.key) },
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

        }
    }
}

@Composable
private fun TargetSelectionRow(
    target: ScanTargetItem,
    checked: Boolean,
    accent: Color,
    onCheckedChange: () -> Unit,
) {
    val isOptional = target.risk == Risk.OPTIONAL
    val rowBg by animateColorAsState(
        targetValue = if (checked) accent.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = Motion.fast(),
        label = "targetRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onCheckedChange)
            .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(accent.copy(alpha = if (checked) 0.85f else 0.35f)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = if (isOptional) AppColors.Optional else AppColors.Primary,
            ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                )
                if (isOptional) {
                    Text(
                        text = "不可恢复",
                        fontSize = 10.sp,
                        color = AppColors.Optional,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Optional.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = formatBytes(target.bytes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextSecondary,
                )
            }
            // SAFE 说明对勾选没增量，只亮 OPTIONAL。
            if (isOptional && target.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = target.description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

private data class CylinderSlice(
    val color: Color,
    val top: Float,
    val bottom: Float,
)

// 极小段抬高再压回，不然被底盘盖没；stubZero 扫初期画扁片。
private fun sliceCylinder(
    colors: List<Color>,
    shares: List<Float>,
    bottom: Float,
    bodyHeight: Float,
    minHeight: Float,
    stubZero: Boolean = false,
): List<CylinderSlice> {
    val heights = shares.map { share ->
        when {
            share <= 0.0005f -> if (stubZero) minHeight else 0f
            else -> max(share * bodyHeight, minHeight)
        }
    }
    val used = heights.sum()
    val scale = if (used > bodyHeight) bodyHeight / used else 1f

    var cursor = bottom
    return colors.indices.mapNotNull { index ->
        val height = heights[index] * scale
        if (height <= 0f) return@mapNotNull null
        val sliceTop = cursor - height
        CylinderSlice(colors[index], sliceTop, cursor).also { cursor = sliceTop }
    }
}

@Composable
private fun StorageCylinder(
    categories: List<ScanCategory>,
    isScanning: Boolean,
    showSweep: Boolean = isScanning,
    modifier: Modifier = Modifier,
) {
    val totalBytes = categories.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)
    // retained 只留白，不进柱体；图例自上而下 → 柱体反转堆。
    val stack = remember(categories) { categories.filter { it.id != "retained" }.asReversed() }

    val shares = remember(stack.size) { List(stack.size) { Animatable(0f) } }
    // 别把 isScanning 塞进 key，扫完会白抖一轮。
    LaunchedEffect(stack.map { it.bytes }) {
        stack.forEachIndexed { index, category ->
            launch {
                shares[index].animateTo(
                    targetValue = category.bytes / totalBytes,
                    animationSpec = Motion.grow(),
                )
            }
        }
    }

    val sweep = remember { Animatable(0f) }
    LaunchedEffect(showSweep) {
        if (showSweep) {
            sweep.snapTo(0f)
            sweep.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(Motion.SweepMs, easing = LinearEasing),
                ),
            )
        } else {
            sweep.snapTo(0f)
        }
    }

    Canvas(modifier = modifier) {
        val cylinderWidth = size.width * 0.66f
        val left = (size.width - cylinderWidth) / 2f
        val right = left + cylinderWidth
        val capHeight = cylinderWidth * 0.22f
        val top = capHeight / 2f
        val bottom = size.height - capHeight / 2f
        val bodyHeight = bottom - top

        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    AppColors.CylinderShellEdge,
                    AppColors.CylinderShellLight,
                    AppColors.CylinderShellMid,
                    AppColors.CylinderShellEdge,
                ),
                startX = left,
                endX = right,
            ),
            topLeft = Offset(left, top),
            size = Size(cylinderWidth, bodyHeight),
        )
        drawOval(
            brush = Brush.verticalGradient(listOf(Color.White, AppColors.CylinderShellMid)),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
        )
        drawOval(
            brush = Brush.verticalGradient(
                listOf(AppColors.CylinderShellMid, AppColors.CylinderShellLight),
            ),
            topLeft = Offset(left, bottom - capHeight / 2f),
            size = Size(cylinderWidth, capHeight),
        )

        val slices = sliceCylinder(
            colors = stack.map { it.color },
            shares = shares.map { it.value },
            bottom = bottom,
            bodyHeight = bodyHeight,
            minHeight = capHeight * 0.55f,
            stubZero = isScanning,
        )
        val fillTop = slices.lastOrNull()?.top ?: bottom

        clipRect(left, top - capHeight / 2f, right, bottom + capHeight / 2f) {
            slices.forEach { slice ->
                // 暗边用同色系，别混黑（蓝会脏）；别加高光（会留白斑）。
                val bodyBrush = Brush.horizontalGradient(
                    0f to slice.color,
                    0.80f to slice.color,
                    1f to lerp(slice.color, AppColors.CategoryHistory, 0.16f),
                    startX = left,
                    endX = right,
                )
                drawRect(
                    brush = bodyBrush,
                    topLeft = Offset(left, slice.top),
                    size = Size(cylinderWidth, slice.bottom - slice.top),
                )
                drawOval(
                    brush = bodyBrush,
                    topLeft = Offset(left, slice.bottom - capHeight / 2f),
                    size = Size(cylinderWidth, capHeight),
                )
            }

            slices.lastOrNull()?.let { topSlice ->
                drawOval(
                    brush = Brush.verticalGradient(
                        listOf(
                            lerp(topSlice.color, Color.White, 0.32f),
                            lerp(topSlice.color, Color.White, 0.08f),
                        ),
                        startY = fillTop - capHeight / 2f,
                        endY = fillTop + capHeight / 2f,
                    ),
                    topLeft = Offset(left, fillTop - capHeight / 2f),
                    size = Size(cylinderWidth, capHeight),
                )
                // 柱上唯一纯黑，alpha 重了像凹坑。
                drawOval(
                    brush = Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent),
                        startY = fillTop - capHeight / 2f,
                        endY = fillTop + capHeight * 0.18f,
                    ),
                    topLeft = Offset(left, fillTop - capHeight / 2f),
                    size = Size(cylinderWidth, capHeight),
                )
            }

            if (showSweep) {
                val bandHeight = bodyHeight * 0.24f
                val bandTop = top - bandHeight + (bodyHeight + bandHeight) * (1f - sweep.value)
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.34f), Color.Transparent),
                        startY = bandTop,
                        endY = bandTop + bandHeight,
                    ),
                    topLeft = Offset(left, bandTop),
                    size = Size(cylinderWidth, bandHeight),
                )
            }
        }

        drawOval(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
        )
    }
}

@Composable
private fun TopBar(
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
    scanPhase: ScanPhase,
    onStartScan: () -> Unit,
    showClean: Boolean,
    isCleaning: Boolean,
    selectedBytes: Long,
    cleanEnabled: Boolean,
    onRequestClean: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolSelector(selectedTools, onToolToggle)

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showClean) {
                    OutlinedButton(
                        onClick = onStartScan,
                        enabled = !isCleaning && selectedTools.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AppColors.Primary,
                            disabledContentColor = AppColors.Primary.copy(alpha = 0.5f),
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        Text("重新扫描", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    val cleanBg = if (cleanEnabled) AppColors.Primary else AppColors.PrimaryContainer
                    val cleanFg = if (cleanEnabled) AppColors.OnPrimary else AppColors.Primary
                    Button(
                        onClick = onRequestClean,
                        enabled = cleanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cleanBg,
                            contentColor = cleanFg,
                            disabledContainerColor = cleanBg,
                            disabledContentColor = cleanFg,
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        if (isCleaning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AppColors.OnPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在清理…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        } else {
                            Text(
                                text = if (selectedBytes > 0L) {
                                    "清理选中 ${formatBytes(selectedBytes)}"
                                } else {
                                    "清理选中项"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                } else {
                    val scanEnabled = !isCleaning && scanPhase != ScanPhase.SCANNING && selectedTools.isNotEmpty()
                    val scanBg = if (scanEnabled) AppColors.Primary else AppColors.PrimaryContainer
                    val scanFg = if (scanEnabled) AppColors.OnPrimary else AppColors.Primary
                    Button(
                        onClick = onStartScan,
                        enabled = scanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scanBg,
                            contentColor = scanFg,
                            disabledContainerColor = scanBg,
                            disabledContentColor = scanFg,
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = when (scanPhase) {
                                ScanPhase.IDLE -> "开始扫描"
                                ScanPhase.SCANNING -> "正在扫描"
                                ScanPhase.DONE -> "重新扫描"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
        }
    }
}

@Composable
private fun ToolSelector(
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon("Codex", Res.drawable.codex, "codex" in selectedTools) { onToolToggle("codex") }
        ToolIcon("Claude", Res.drawable.claude, "claude" in selectedTools) { onToolToggle("claude") }
        ToolIcon("Cursor", Res.drawable.cursor, "cursor" in selectedTools) { onToolToggle("cursor") }

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(AppColors.Surface3, RoundedCornerShape(AppDimensions.Radius.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(">", color = AppColors.TextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun ToolIcon(
    name: String,
    resource: DrawableResource,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> AppColors.Surface3.copy(alpha = 0.55f)
            isSelected -> AppColors.Primary
            else -> AppColors.Surface3
        },
        animationSpec = Motion.normal(),
        label = "toolIconBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> AppColors.TextTertiary.copy(alpha = 0.55f)
            isSelected -> AppColors.OnPrimary
            else -> AppColors.TextSecondary
        },
        animationSpec = Motion.normal(),
        label = "toolIconTint",
    )
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(color = bg, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(resource),
            contentDescription = name,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CleaningStatsCard(refreshKey: Int, modifier: Modifier = Modifier) {
    val total by remember(refreshKey) { mutableStateOf(CleanHistory.totalBytes()) }
    val daily by remember(refreshKey) { mutableStateOf(CleanHistory.recentDaily(limit = 7)) }

    Box(
        modifier = modifier
            .height(180.dp)
            .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp))
            .padding(AppDimensions.SpacingLarge.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("累计清理", fontSize = 14.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
                Text(
                    text = formatBytes(total),
                    fontSize = 28.sp,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.weight(1f))

            if (daily.isEmpty()) {
                CleanHistoryBarsPlaceholder(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            } else {
                CleanHistoryBars(
                    daily = daily,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }
    }
}

// 别用 weight，一两根会被拉成整条。
private val BarWidth = 26.dp
private val BarSpacing = 8.dp
private val BarLabelHeight = 16.dp

@Composable
private fun CleanHistoryBars(daily: List<DailyClean>, modifier: Modifier = Modifier) {
    val maxBytes = daily.maxOf { it.bytes }.coerceAtLeast(1L)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(BarSpacing),
        verticalAlignment = Alignment.Bottom,
    ) {
        daily.forEachIndexed { index, day ->
            val fraction = (day.bytes.toFloat() / maxBytes).coerceIn(0.08f, 1f)
            val isLatest = index == daily.lastIndex
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = Motion.grow(),
                label = "historyBar",
            )
            Column(
                modifier = Modifier.width(BarWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(animated)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (isLatest) AppColors.Primary else AppColors.CategoryPackages),
                    )
                }
                Text(
                    text = "${day.date.monthValue}/${day.date.dayOfMonth}",
                    fontSize = 9.sp,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.height(BarLabelHeight),
                )
            }
        }
    }
}

@Composable
private fun CleanHistoryBarsPlaceholder(modifier: Modifier = Modifier) {
    val heights = listOf(0.4f, 0.68f, 0.32f, 0.84f, 0.52f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(BarSpacing),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { fraction ->
            Column(
                modifier = Modifier.width(BarWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(AppColors.Surface3),
                    )
                }
                Spacer(Modifier.height(BarLabelHeight))
            }
        }
    }
}

@Composable
private fun DiskUsageCard(refreshKey: Int, modifier: Modifier = Modifier) {
    var usage by remember { mutableStateOf<DiskUsage?>(null) }
    LaunchedEffect(refreshKey) {
        usage = withContext(Dispatchers.IO) { DiskUsageReader.readSystemDrive() }
    }
    val snapshot = usage
    val fraction = snapshot?.usedFraction ?: 0f
    val hasData = snapshot?.hasData == true
    val animatedFraction by animateFloatAsState(
        targetValue = if (hasData) fraction else 0f,
        animationSpec = Motion.slow(),
        label = "diskUsage",
    )
    val spaceLabel = snapshot?.takeIf { it.hasData }?.let {
        "${formatBytes(it.usedBytes)} / ${formatBytes(it.totalBytes)}"
    } ?: "—"

    Box(
        modifier = modifier
            .height(180.dp)
            .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp))
            .padding(AppDimensions.SpacingLarge.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text("C 盘占用", fontSize = 14.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
                    Text(
                        text = spaceLabel,
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                }
                Text(
                    text = if (hasData) "${(fraction * 100).toInt()}%" else "—",
                    fontSize = 28.sp,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.weight(1f))

            DiskUsageCylinder(
                fraction = animatedFraction,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }
}

@Composable
private fun DiskUsageCylinder(fraction: Float, modifier: Modifier = Modifier) {
    val fill = fraction.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val cylinderHeight = size.height * 0.66f
        val top = (size.height - cylinderHeight) / 2f
        val capWidth = cylinderHeight * 0.22f
        val left = capWidth / 2f
        val right = size.width - capWidth / 2f
        val bodyWidth = right - left

        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    AppColors.CylinderShellEdge,
                    AppColors.CylinderShellLight,
                    AppColors.CylinderShellMid,
                    AppColors.CylinderShellEdge,
                ),
                startY = top,
                endY = top + cylinderHeight,
            ),
            topLeft = Offset(left, top),
            size = Size(bodyWidth, cylinderHeight),
        )
        drawOval(
            brush = Brush.horizontalGradient(
                listOf(AppColors.CylinderShellLight, AppColors.CylinderShellMid),
            ),
            topLeft = Offset(0f, top),
            size = Size(capWidth, cylinderHeight),
        )
        drawOval(
            brush = Brush.horizontalGradient(
                listOf(AppColors.CylinderShellMid, AppColors.CylinderShellLight),
            ),
            topLeft = Offset(right - capWidth / 2f, top),
            size = Size(capWidth, cylinderHeight),
        )

        if (fill > 0f) {
            val fillRight = left + bodyWidth * fill
            val fillColor = AppColors.Primary
            // 同色系暗边，别混黑。
            val bodyBrush = Brush.verticalGradient(
                0f to fillColor,
                0.80f to fillColor,
                1f to lerp(fillColor, AppColors.CategoryHistory, 0.16f),
                startY = top,
                endY = top + cylinderHeight,
            )
            clipRect(
                left - capWidth / 2f,
                top,
                right + capWidth / 2f,
                top + cylinderHeight,
            ) {
                drawRect(
                    brush = bodyBrush,
                    topLeft = Offset(left, top),
                    size = Size((fillRight - left).coerceAtLeast(0f), cylinderHeight),
                )
                drawOval(
                    brush = bodyBrush,
                    topLeft = Offset(left - capWidth / 2f, top),
                    size = Size(capWidth, cylinderHeight),
                )
                drawOval(
                    brush = Brush.horizontalGradient(
                        listOf(
                            lerp(fillColor, Color.White, 0.32f),
                            lerp(fillColor, Color.White, 0.08f),
                        ),
                        startX = fillRight - capWidth / 2f,
                        endX = fillRight + capWidth / 2f,
                    ),
                    topLeft = Offset(fillRight - capWidth / 2f, top),
                    size = Size(capWidth, cylinderHeight),
                )
                drawOval(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent),
                        startX = fillRight - capWidth / 2f,
                        endX = fillRight + capWidth * 0.18f,
                    ),
                    topLeft = Offset(fillRight - capWidth / 2f, top),
                    size = Size(capWidth, cylinderHeight),
                )
            }
        }
    }
}
