package dev.cxclear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.CleanTarget
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.ScanEvent
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.scan.formatBytes
import dev.cxclear.scan.scanStream
import dev.cxclear.ui.Screen
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import kotlinx.coroutines.launch
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
        scanCategories = emptyList()
        selectedTargets = emptySet()
        scope.launch {
            val profiles = ALL_PROFILES.filter { it.id in selectedTools }
            val results = mutableMapOf<String, ScanResult>()
            val spaces = mutableMapOf<String, ToolSpaceResult>()

            // 每来一个事件就重算一次分类，柱体因此按真实测量进度生长，
            // 不再需要占位数据和固定时长的假动画。
            scanStream(profiles).collect { event ->
                when (event) {
                    is ScanEvent.Started -> Unit
                    is ScanEvent.TargetScanned -> results[event.result.targetId] = event.result
                    is ScanEvent.SpaceScanned -> spaces[event.space.toolId] = event.space
                }
                scanCategories = buildCategories(
                    profiles = profiles,
                    results = results.values.toList(),
                    totalToolBytes = spaces.values.sumOf { it.bytes },
                )
            }

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
        ScanCategory(
            id = "retained",
            label = "应用保留数据",
            bytes = retainedBytes,
            color = AppColors.CategoryRetained,
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
            categories = categories,
            isScanning = true,
            totalBytes = categories.sumOf { it.bytes },
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

/** 一段短圆柱的绘制几何，已折算为像素区间；Canvas 只负责按序画出。 */
private data class CylinderSlice(
    val color: Color,
    val top: Float,
    val bottom: Float,
)

/**
 * 把各段占比自底向上摊成像素区间。
 *
 * 占比极小的段会被抬升到 [minHeight]，否则会退化成一条被邻段底盘完全盖住的线；
 * 抬升后整体等比压回筒内，因此累计高度永远不会溢出筒身。
 */
private fun sliceCylinder(
    colors: List<Color>,
    shares: List<Float>,
    bottom: Float,
    bodyHeight: Float,
    minHeight: Float,
): List<CylinderSlice> {
    val heights = shares.map { share ->
        if (share <= 0.0005f) 0f else max(share * bodyHeight, minHeight)
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
    modifier: Modifier = Modifier,
) {
    // 图例自上而下按占用递减，柱体自下而上堆叠，故这里反转。
    val stack = remember(categories) { categories.asReversed() }
    val totalBytes = stack.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)

    // 每段一个独立占比动画：0 表示尚未出现。
    // 扫描期每来一批测量结果就重新补间，各段互不等待；结果就绪后同一组动画值
    // 继续补间到最终占比。「生长」和「重新分配高度」因此共用一套状态，
    // 不需要额外的全局进度，也不会出现顶面与最上段脱节。
    val shares = remember(stack.size) { List(stack.size) { Animatable(0f) } }
    LaunchedEffect(stack.map { it.bytes }, isScanning) {
        stack.forEachIndexed { index, category ->
            launch {
                shares[index].animateTo(
                    targetValue = category.bytes / totalBytes,
                    animationSpec = tween(if (isScanning) 520 else 400, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    // 扫描期沿筒身上行的光带，作为「仍在统计」的活体信号。
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(isScanning) {
        if (isScanning) {
            sweep.snapTo(0f)
            sweep.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
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
                listOf(AppColors.CylinderShellEdge, AppColors.CylinderShellMid),
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
        )
        val fillTop = slices.lastOrNull()?.top ?: bottom

        clipRect(left, top - capHeight / 2f, right, bottom + capHeight / 2f) {
            slices.forEach { slice ->
                // 侧面与底盘共用一支笔刷：底盘是同一段柱面的延续，
                // 不额外压暗，否则每条接缝都会读成一道投影。
                // 柱面基本平涂，只在右侧收一点暗边交代圆度；不加高光，
                // 否则会在整根柱子上留下一条与数据无关的白斑。
                val bodyBrush = Brush.horizontalGradient(
                    0f to slice.color,
                    0.72f to slice.color,
                    1f to lerp(slice.color, Color.Black, 0.10f),
                    startX = left,
                    endX = right,
                )
                drawRect(
                    brush = bodyBrush,
                    topLeft = Offset(left, slice.top),
                    size = Size(cylinderWidth, slice.bottom - slice.top),
                )
                // 每段只画自己朝下的底盘。它向下凸出、盖住下一段的顶端，
                // 接缝的弧线由此产生；顶面留给最上面那段单独处理，避免互相穿插。
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
                // 筒壁投在顶面上的阴影，靠后沿最深。
                drawOval(
                    brush = Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                        startY = fillTop - capHeight / 2f,
                        endY = fillTop + capHeight * 0.32f,
                    ),
                    topLeft = Offset(left, fillTop - capHeight / 2f),
                    size = Size(cylinderWidth, capHeight),
                )
            }

            if (isScanning) {
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
            color = Color.White.copy(alpha = 0.45f),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
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
