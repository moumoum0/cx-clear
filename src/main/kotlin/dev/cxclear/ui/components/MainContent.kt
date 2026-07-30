package dev.cxclear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.CleanTarget
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.formatBytes
import dev.cxclear.scan.scanAll
import dev.cxclear.scan.scanToolSpaces
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ScanPhase { IDLE, SCANNING, DONE }

private data class ScanCategory(
    val id: String,
    val label: String,
    val bytes: Long,
    val color: Color,
    val items: List<ScanTargetItem> = emptyList(),
)

private data class ScanTargetItem(
    val id: String,
    val label: String,
    val description: String,
    val bytes: Long,
    val risk: Risk,
)

@Composable
fun MainContent(currentScreen: Screen) {
    var selectedTools by remember { mutableStateOf(setOf("codex")) }
    var scanPhase by remember { mutableStateOf(ScanPhase.IDLE) }
    var scanCategories by remember { mutableStateOf(emptyList<ScanCategory>()) }
    var selectedTargets by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    fun startScan() {
        if (scanPhase == ScanPhase.SCANNING || selectedTools.isEmpty()) return
        scanPhase = ScanPhase.SCANNING
        scope.launch {
            val profiles = ALL_PROFILES.filter { it.id in selectedTools }
            val startedAt = System.currentTimeMillis()
            val (results, toolSpaces) = coroutineScope {
                val targetScan = async { scanAll(profiles) }
                val spaceScan = async { scanToolSpaces(profiles) }
                targetScan.await() to spaceScan.await()
            }
            val remainingAnimationTime = 1200L - (System.currentTimeMillis() - startedAt)
            if (remainingAnimationTime > 0) delay(remainingAnimationTime)

            scanCategories = buildCategories(profiles, results, toolSpaces.sumOf { it.bytes })
            selectedTargets = scanCategories
                .flatMap { it.items }
                .filter { it.risk == Risk.SAFE && it.bytes > 0L }
                .mapTo(mutableSetOf()) { it.id }
            scanPhase = ScanPhase.DONE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
            .padding(AppDimensions.SpacingLarge.dp),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp)
    ) {
        TopBar(
            selectedTools = selectedTools,
            onToolToggle = { id ->
                if (scanPhase != ScanPhase.SCANNING) {
                    selectedTools = if (id in selectedTools) selectedTools - id else selectedTools + id
                    scanPhase = ScanPhase.IDLE
                    scanCategories = emptyList()
                    selectedTargets = emptySet()
                }
            },
            scanPhase = scanPhase,
            onStartScan = ::startScan,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (currentScreen) {
                Screen.SCAN -> ScanView(
                    phase = scanPhase,
                    categories = scanCategories,
                    selectedTargets = selectedTargets,
                    onTargetToggle = { targetId ->
                        selectedTargets = if (targetId in selectedTargets) {
                            selectedTargets - targetId
                        } else {
                            selectedTargets + targetId
                        }
                    },
                    onStartScan = ::startScan,
                )
                Screen.CLEAN -> CleanView()
                Screen.HISTORY -> HistoryView()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp)
        ) {
            CleaningStatsCard(modifier = Modifier.weight(1f))
            DiskUsageCard(modifier = Modifier.weight(1f))
        }
    }
}

private fun buildCategories(
    profiles: List<dev.cxclear.model.ToolProfile>,
    results: List<ScanResult>,
    totalToolBytes: Long,
): List<ScanCategory> {
    val resultByTarget = results.associateBy { it.targetId }
    val profileByTarget = profiles.flatMap { profile -> profile.targets.map { it.id to profile } }.toMap()
    val targets = profiles.flatMap { it.targets }

    fun item(target: CleanTarget): ScanTargetItem {
        val profileName = profileByTarget[target.id]?.name.orEmpty()
        return ScanTargetItem(
            id = target.id,
            label = if (profileName.isBlank()) target.label else "$profileName · ${target.label}",
            description = target.description,
            bytes = resultByTarget[target.id]?.bytes ?: 0L,
            risk = target.risk,
        )
    }

    val packageItems = targets
        .filter { target ->
            resultByTarget[target.id]?.exists == true &&
                target.risk == Risk.SAFE &&
                listOf("plugins", "downloads", "sandbox", "vendor").any { target.id.contains(it) }
        }
        .map(::item)
    val workingItems = targets
        .filter { target ->
            resultByTarget[target.id]?.exists == true &&
                target.risk == Risk.SAFE &&
                packageItems.none { it.id == target.id }
        }
        .map(::item)
    val historyItems = targets
        .filter { resultByTarget[it.id]?.exists == true && it.risk == Risk.OPTIONAL }
        .map(::item)

    val knownBytes = (packageItems + workingItems + historyItems).sumOf { it.bytes }
    val retainedBytes = (totalToolBytes - knownBytes).coerceAtLeast(0L)

    return listOf(
        ScanCategory(
            id = "packages",
            label = "插件与安装缓存",
            bytes = packageItems.sumOf { it.bytes },
            color = Color(0xFFFFC928),
            items = packageItems,
        ),
        ScanCategory(
            id = "working",
            label = "日志与临时文件",
            bytes = workingItems.sumOf { it.bytes },
            color = Color(0xFFFF8437),
            items = workingItems,
        ),
        ScanCategory(
            id = "history",
            label = "历史与会话",
            bytes = historyItems.sumOf { it.bytes },
            color = Color(0xFFC43DE4),
            items = historyItems,
        ),
        ScanCategory(
            id = "retained",
            label = "应用保留数据",
            bytes = retainedBytes,
            color = Color(0xFF8D96AC),
        ),
    )
}

@Composable
private fun ScanView(
    phase: ScanPhase,
    categories: List<ScanCategory>,
    selectedTargets: Set<String>,
    onTargetToggle: (String) -> Unit,
    onStartScan: () -> Unit,
) {
    when (phase) {
        ScanPhase.IDLE -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "未开始扫描",
                fontSize = 18.sp,
                color = AppColors.TextSecondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击「开始扫描」查看应用占用与可清理内容",
                fontSize = 14.sp,
                color = AppColors.TextTertiary
            )
        }

        ScanPhase.SCANNING -> ScanResultView(
            categories = placeholderCategories(),
            isScanning = true,
            totalBytes = 0L,
            selectedTargets = emptySet(),
            onTargetToggle = {},
            onRescan = onStartScan,
        )

        ScanPhase.DONE -> ScanResultView(
            categories = categories,
            isScanning = false,
            totalBytes = categories.sumOf { it.bytes },
            selectedTargets = selectedTargets,
            onTargetToggle = onTargetToggle,
            onRescan = onStartScan,
        )
    }
}

@Composable
private fun ScanResultView(
    categories: List<ScanCategory>,
    isScanning: Boolean,
    totalBytes: Long,
    selectedTargets: Set<String>,
    onTargetToggle: (String) -> Unit,
    onRescan: () -> Unit,
) {
    var expandedCategories by remember(categories) { mutableStateOf(emptySet<String>()) }
    val selectedBytes = categories
        .flatMap { it.items }
        .filter { it.id in selectedTargets }
        .sumOf { it.bytes }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 42.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StorageCylinder(
            categories = categories,
            isScanning = isScanning,
            modifier = Modifier.width(170.dp).fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (isScanning) "正在扫描应用空间…" else "应用共占用 ${formatBytes(totalBytes)}",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isScanning) {
                    "正在统计完整占用和可清理位置"
                } else {
                    "已选择 ${formatBytes(selectedBytes)} 可清理内容"
                },
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.height(12.dp))

            categories.forEach { category ->
                val canExpand = !isScanning && category.items.isNotEmpty()
                val isExpanded = category.id in expandedCategories
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Surface3.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                            .clickable(enabled = canExpand) {
                                expandedCategories = if (isExpanded) {
                                    expandedCategories - category.id
                                } else {
                                    expandedCategories + category.id
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(category.color, RoundedCornerShape(99.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.label, fontSize = 13.sp, color = AppColors.TextSecondary)
                            if (!isScanning && category.id == "retained") {
                                Text("应用运行所需，不提供清理", fontSize = 10.sp, color = AppColors.TextTertiary)
                            }
                        }

                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = category.color,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = formatBytes(category.bytes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.TextPrimary,
                            )
                            if (canExpand) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isExpanded) "⌃" else "⌄",
                                    fontSize = 15.sp,
                                    color = AppColors.TextTertiary,
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        category.items.forEach { target ->
                            TargetSelectionRow(
                                target = target,
                                checked = target.id in selectedTargets,
                                onCheckedChange = { onTargetToggle(target.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }

            if (!isScanning) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "重新扫描",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.Primary,
                    modifier = Modifier.clickable(onClick = onRescan),
                )
            }
        }
    }
}

private fun placeholderCategories() = listOf(
    ScanCategory("packages", "插件与安装缓存", 28L, Color(0xFFFFC928)),
    ScanCategory("working", "日志与临时文件", 20L, Color(0xFFFF8437)),
    ScanCategory("history", "历史与会话", 18L, Color(0xFFC43DE4)),
    ScanCategory("retained", "应用保留数据", 34L, Color(0xFF8D96AC)),
)

@Composable
private fun TargetSelectionRow(
    target: ScanTargetItem,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckedChange)
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(checkedColor = AppColors.Primary),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                )
                Text(formatBytes(target.bytes), fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Text(
                text = target.description,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun StorageCylinder(
    categories: List<ScanCategory>,
    isScanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val growth = remember { Animatable(0.08f) }
    LaunchedEffect(isScanning, categories) {
        if (isScanning) {
            growth.snapTo(0.08f)
            growth.animateTo(
                targetValue = 0.88f,
                animationSpec = tween(1150, easing = FastOutSlowInEasing),
            )
        } else {
            growth.animateTo(
                targetValue = 1f,
                animationSpec = tween(360, easing = FastOutSlowInEasing),
            )
        }
    }

    Canvas(modifier = modifier) {
        val cylinderWidth = size.width * 0.7f
        val left = (size.width - cylinderWidth) / 2f
        val capHeight = cylinderWidth * 0.24f
        val top = capHeight / 2f
        val bottom = size.height - capHeight / 2f
        val bodyHeight = bottom - top

        val shellBrush = Brush.horizontalGradient(
            listOf(Color(0xFFE4E7ED), Color(0xFFFAFAFC), Color(0xFFD8DCE5)),
            startX = left,
            endX = left + cylinderWidth,
        )
        drawRect(shellBrush, Offset(left, top), Size(cylinderWidth, bodyHeight))
        drawOval(
            brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFE5E8EF))),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
        )
        drawOval(
            brush = Brush.verticalGradient(listOf(Color(0xFFC8CDD8), Color(0xFFE9EBF0))),
            topLeft = Offset(left, bottom - capHeight / 2f),
            size = Size(cylinderWidth, capHeight),
        )

        val sum = categories.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)
        val segments = categories.map { it to (it.bytes.toFloat() / sum) }.asReversed()
        var segmentBottom = bottom
        val filledHeight = bodyHeight * growth.value
        val fillTop = bottom - filledHeight

        clipRect(left, fillTop, left + cylinderWidth, bottom + capHeight / 2f) {
            segments.forEach { (category, fraction) ->
                val height = filledHeight * fraction
                val segmentTop = segmentBottom - height
                val segmentBrush = Brush.horizontalGradient(
                    listOf(
                        category.color.copy(alpha = 0.74f),
                        category.color.copy(alpha = 0.98f),
                        category.color.copy(alpha = 0.8f),
                    ),
                    startX = left,
                    endX = left + cylinderWidth,
                )
                drawRect(segmentBrush, Offset(left, segmentTop), Size(cylinderWidth, height + 1f))
                drawOval(
                    brush = Brush.verticalGradient(
                        listOf(category.color.copy(alpha = 0.98f), category.color.copy(alpha = 0.72f)),
                        startY = segmentTop - capHeight / 2f,
                        endY = segmentTop + capHeight / 2f,
                    ),
                    topLeft = Offset(left, segmentTop - capHeight / 2f),
                    size = Size(cylinderWidth, capHeight),
                )
                segmentBottom = segmentTop
            }
        }

        val topCategory = categories.firstOrNull()
        if (topCategory != null) {
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(topCategory.color.copy(alpha = 0.98f), topCategory.color.copy(alpha = 0.7f)),
                    startY = fillTop - capHeight / 2f,
                    endY = fillTop + capHeight / 2f,
                ),
                topLeft = Offset(left, fillTop - capHeight / 2f),
                size = Size(cylinderWidth, capHeight),
            )
        }

        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                startX = left + cylinderWidth * 0.12f,
                endX = left + cylinderWidth * 0.42f,
            ),
            topLeft = Offset(left + cylinderWidth * 0.12f, fillTop),
            size = Size(cylinderWidth * 0.3f, filledHeight),
        )

        drawOval(Color.White.copy(alpha = 0.5f), Offset(left, 0f), Size(cylinderWidth, capHeight))
        drawOval(
            Color(0xFFAFB5C3).copy(alpha = 0.22f),
            Offset(left, bottom - capHeight / 2f),
            Size(cylinderWidth, capHeight),
        )
    }
}

@Composable
private fun CleanView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("当前可清理的文件", fontSize = 18.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("未扫描无法操作", fontSize = 14.sp, color = AppColors.TextTertiary)
    }
}

@Composable
private fun HistoryView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("清理历史记录", fontSize = 18.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("暂无历史数据", fontSize = 14.sp, color = AppColors.TextTertiary)
    }
}

@Composable
private fun TopBar(
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
    scanPhase: ScanPhase,
    onStartScan: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolSelector(selectedTools, onToolToggle)

        Button(
            onClick = onStartScan,
            enabled = scanPhase != ScanPhase.SCANNING && selectedTools.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AppColors.Primary,
                contentColor = AppColors.OnPrimary,
                disabledBackgroundColor = AppColors.PrimaryContainer,
                disabledContentColor = AppColors.Primary,
            ),
            shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp)
        ) {
            Text(
                text = when (scanPhase) {
                    ScanPhase.IDLE -> "开始扫描"
                    ScanPhase.SCANNING -> "正在扫描…"
                    ScanPhase.DONE -> "重新扫描"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
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
        ToolIcon("Codex", "icons/codex.svg", "codex" in selectedTools) { onToolToggle("codex") }
        ToolIcon("Claude", "icons/claude.svg", "claude" in selectedTools) { onToolToggle("claude") }
        ToolIcon("Cursor", "icons/cursor.svg", false) { }

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
private fun ToolIcon(
    name: String,
    iconPath: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = if (isSelected) AppColors.Primary else AppColors.Surface3,
                shape = RoundedCornerShape(AppDimensions.Radius.dp)
            )
            .clickable(enabled = name != "Cursor", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconPath),
            contentDescription = name,
            tint = if (isSelected) AppColors.OnPrimary else AppColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CleaningStatsCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(180.dp)
            .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp))
            .padding(AppDimensions.SpacingLarge.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp)) {
            Text("累计清理", fontSize = 14.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
            Text("38.7 GB", fontSize = 36.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingLarge.dp)) {
                Column {
                    Text("本周", fontSize = 12.sp, color = AppColors.TextTertiary)
                    Text("2.4 GB", fontSize = 16.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                }
                Column {
                    Text("今日", fontSize = 12.sp, color = AppColors.TextTertiary)
                    Text("320 MB", fontSize = 16.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun DiskUsageCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(180.dp)
            .background(AppColors.Surface2, RoundedCornerShape(AppDimensions.Radius.dp))
            .padding(AppDimensions.SpacingLarge.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimensions.SpacingMedium.dp)) {
            Text("C 盘占用", fontSize = 14.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
            Text("180 / 512 GB", fontSize = 28.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("剩余 332 GB (65%)", fontSize = 14.sp, color = AppColors.TextSecondary)
        }
    }
}
