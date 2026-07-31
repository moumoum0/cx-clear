package dev.cxclear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
            // TargetsScanned 每次带来的是全量快照，直接整份替换即可，无需再逐项累加。
            var results = emptyList<ScanResult>()
            val spaces = mutableMapOf<String, ToolSpaceResult>()

            // 定时快照每到一次就重算一次分类，柱体按真实测量进度生长，
            // 节奏由 Scanner 的定时器拍平，不随磁盘忽快忽慢抖动。
            scanStream(profiles).collect { event ->
                when (event) {
                    is ScanEvent.Started -> Unit
                    is ScanEvent.TargetsScanned -> results = event.results
                    is ScanEvent.SpaceScanned -> spaces[event.space.toolId] = event.space
                }
                scanCategories = buildCategories(
                    profiles = profiles,
                    results = results,
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

    // retained 排在最前：它在图例最上、在柱体顶端留空。可清理的三类自底向上填，
    // 扫描时彩色只增不减，顶部空区被逐渐顶掉，柱身高度（=totalBytes）始终不变。
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

@Composable
private fun ScanView(
    phase: ScanPhase,
    categories: List<ScanCategory>,
    selectedTargets: Set<String>,
    onTargetToggle: (String) -> Unit,
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

        // 两个阶段必须共用同一个调用点：`when` 的每个分支是独立的组合 group，
        // 分开写会让扫描结束时整棵子树被丢弃重建，柱体里的 Animatable 一起归零，
        // 于是明明数据没变，柱子却要塌成空筒再长一遍。
        ScanPhase.SCANNING, ScanPhase.DONE -> ScanResultView(
            categories = categories,
            isScanning = phase == ScanPhase.SCANNING,
            totalBytes = categories.sumOf { it.bytes },
            selectedTargets = selectedTargets,
            onTargetToggle = onTargetToggle,
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

        val cleanableBytes = categories
            .filter { it.id != "retained" }
            .sumOf { it.bytes }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = if (isScanning) "已扫描 ${formatBytes(totalBytes)}" else "应用共占用 ${formatBytes(totalBytes)}",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isScanning) {
                    "可清理共 ${formatBytes(cleanableBytes)}"
                } else {
                    "已选择 ${formatBytes(selectedBytes)} · 可清理共 ${formatBytes(cleanableBytes)}"
                },
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
            )
            // 占比条两态各表意，且都随数据动态生长：
            // 扫描时 = 可清理 / 总占用（随字节累加实时增长，呼应「可清理共 X」）；
            // 完成后 = 已选 / 可清理（呼应「已选择 X」）。补间动画抹平两态切换。
            Spacer(Modifier.height(8.dp))
            val selectedFraction = if (isScanning) {
                if (totalBytes > 0L) (cleanableBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            } else {
                if (cleanableBytes > 0L) (selectedBytes.toFloat() / cleanableBytes).coerceIn(0f, 1f) else 0f
            }
            // 补间到目标占比，勾选/取消时宽度滑动而非瞬跳。
            val animatedFraction by animateFloatAsState(
                targetValue = selectedFraction,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
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
            Spacer(Modifier.height(14.dp))

            categories.forEach { category ->
                val canExpand = !isScanning && category.items.isNotEmpty()
                val isExpanded = category.id in expandedCategories
                val isRetained = category.id == "retained"
                val fraction = if (!isScanning && totalBytes > 0L) {
                    (category.bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else 0f

                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.Surface3.copy(alpha = if (isRetained) 0.4f else 0.72f))
                            .clickable(enabled = canExpand) {
                                expandedCategories = if (isExpanded) {
                                    expandedCategories - category.id
                                } else {
                                    expandedCategories + category.id
                                }
                            },
                    ) {
                        // 行内占比底纹：宽度 = 该类 / 总占用，用分类色淡染。
                        // retained 不染（它是留白概念），彩色只给可清理项。
                        if (!isRetained && fraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(category.color.copy(alpha = 0.14f)),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // retained 在柱体里是留白，图例也用空心描边圈呼应，不给实心色块。
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
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    category.label,
                                    fontSize = 13.sp,
                                    color = if (isRetained) AppColors.TextTertiary else AppColors.TextSecondary,
                                )
                                if (isRetained) {
                                    Text("应用运行所需，不提供清理", fontSize = 10.sp, color = AppColors.TextTertiary)
                                }
                            }

                            Text(
                                text = formatBytes(category.bytes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isRetained) AppColors.TextSecondary else AppColors.TextPrimary,
                            )
                            if (isScanning) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = category.color,
                                    strokeWidth = 2.dp,
                                )
                            } else if (canExpand) {
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
    // 分母取全量（含首项的 retained），但 retained 本身不进柱体：可清理的几类
    // 自底向上填，占比之和 < 1，剩下的就是顶部空筒——那块正是不可清理的保留数据，
    // 以留白表达而非画出来。扫描时彩色段只增不减，顶部空区被顶掉，柱身高度不变。
    val totalBytes = categories.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)
    // 图例自上而下按占用递减，柱体自下而上堆叠，故这里反转；同时丢掉 retained。
    val stack = remember(categories) { categories.filter { it.id != "retained" }.asReversed() }

    // 每段一个独立占比动画：0 表示尚未出现。
    // 扫描期每来一批测量结果就重新补间，各段互不等待；结果就绪后同一组动画值
    // 继续补间到最终占比。「生长」和「重新分配高度」因此共用一套状态，
    // 不需要额外的全局进度，也不会出现顶面与最上段脱节。
    val shares = remember(stack.size) { List(stack.size) { Animatable(0f) } }
    // key 只跟字节数走。别把 isScanning 加进来：扫描结束那一刻占比通常已经到位，
    // 多这个 key 只会白重启一轮补间，看着像柱子又抖了一下。
    LaunchedEffect(stack.map { it.bytes }) {
        stack.forEachIndexed { index, category ->
            launch {
                shares[index].animateTo(
                    targetValue = category.bytes / totalBytes,
                    animationSpec = tween(440, easing = FastOutSlowInEasing),
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
        )
        val fillTop = slices.lastOrNull()?.top ?: bottom

        clipRect(left, top - capHeight / 2f, right, bottom + capHeight / 2f) {
            slices.forEach { slice ->
                // 侧面与底盘共用一支笔刷：底盘是同一段柱面的延续，
                // 不额外压暗，否则每条接缝都会读成一道投影。
                // 柱面基本平涂，只在右侧收一点暗边交代圆度；不加高光，
                // 否则会在整根柱子上留下一条与数据无关的白斑。
                // 暗边混的是分类色自身的深色而非纯黑：混黑会把蓝色拉向灰，
                // 一眼看过去像蒙了层脏，混同色系只降明度、不掉饱和。
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
                // 筒壁投在顶面上的阴影，靠后沿最深。alpha 压得很低：
                // 这是整根柱子上唯一的纯黑，稍重一点顶面就会读成一个凹坑。
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

        // 玻璃顶盖压在所有内容之上，alpha 高了会把最上段的颜色一起洗白。
        drawOval(
            color = Color.White.copy(alpha = 0.28f),
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
