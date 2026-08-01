package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.CleanEvent
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.CleanTarget
import dev.cxclear.profiles.ALL_PROFILES
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
    val id: String,
    val label: String,
    val description: String,
    val bytes: Long,
    val risk: Risk,
    val defaultSelected: Boolean,
)

@Composable
fun MainContent(
    currentScreen: Screen,
    modifier: Modifier = Modifier,
) {
    var selectedTools by remember { mutableStateOf(setOf("codex")) }
    var scanPhase by remember { mutableStateOf(ScanPhase.IDLE) }
    var scanCategories by remember { mutableStateOf(emptyList<ScanCategory>()) }
    var selectedTargets by remember { mutableStateOf(emptySet<String>()) }
    var isCleaning by remember { mutableStateOf(false) }
    var showCleanConfirm by remember { mutableStateOf(false) }
    // 每完成一次清理 +1，累计卡片 key 在它上面，借此重新从磁盘读取最新统计。
    var cleanTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun startClean() {
        if (isCleaning) return
        val requests = ALL_PROFILES.flatMap { profile ->
            profile.targets
                .filter { it.id in selectedTargets }
                .map { CleanRequest(profile, it) }
        }
        if (requests.isEmpty()) return
        isCleaning = true
        scope.launch {
            clean(requests).collect { event ->
                if (event is CleanEvent.AllDone) {
                    // 记实测删除量，不是扫描预估值。0 字节 append 会被自动忽略。
                    CleanHistory.append(event.totalFreedBytes)
                }
            }
            // 只切回 IDLE：分类数据留给结果区退场动画用，下次 startScan 会清空重扫。
            selectedTargets = emptySet()
            scanPhase = ScanPhase.IDLE
            isCleaning = false
            cleanTick++
        }
    }

    fun startScan() {
        if (scanPhase == ScanPhase.SCANNING || selectedTools.isEmpty()) return
        scanPhase = ScanPhase.SCANNING
        scanCategories = emptyList()
        selectedTargets = emptySet()
        scope.launch {
            val profiles = ALL_PROFILES.filter { it.id in selectedTools }
            // SpaceScanned / TargetsScanned 每次都是全量快照，直接整份替换即可。
            var results = emptyList<ScanResult>()
            var spaces = emptyList<ToolSpaceResult>()

            // 定时快照每到一次就重算一次分类：阶段一「已找到」随总占用生长，
            // 阶段二柱体按可清理项测量进度生长；节奏由 Scanner 定时器拍平。
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

            // 跟 CleanTarget.defaultSelected 对齐：SAFE 但重建成本高的项可显式不默认勾。
            selectedTargets = scanCategories
                .flatMap { it.items }
                .filter { it.defaultSelected && it.bytes > 0L }
                .mapTo(mutableSetOf()) { it.id }
            scanPhase = ScanPhase.DONE
        }
    }

    val selectedBytes = scanCategories
        .flatMap { it.items }
        .filter { it.id in selectedTargets }
        .sumOf { it.bytes }

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
                // 只切换选择，不动已完成的扫描结果——换工具图标不该抹掉上一轮扫出来的内容，
                // 用户想把新工具纳入统计时再点「重新扫描」即可。
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
            CleaningStatsCard(refreshKey = cleanTick, modifier = Modifier.weight(1f))
            DiskUsageCard(refreshKey = cleanTick, modifier = Modifier.weight(1f))
        }
    }

    if (showCleanConfirm) {
        val hasOptional = scanCategories
            .flatMap { it.items }
            .any { it.id in selectedTargets && it.risk == Risk.OPTIONAL }
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
                        backgroundColor = AppColors.Error,
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
            backgroundColor = AppColors.Surface2,
        )
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
            defaultSelected = target.defaultSelected,
        )
    }

    val packageItems = targets
        .filter { target ->
            resultByTarget[target.id]?.exists == true &&
                target.risk == Risk.SAFE &&
                listOf("plugins", "downloads", "sandbox", "vendor", "extension", "cached", "runtime").any {
                    target.id.contains(it)
                }
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
    // 只用 IDLE vs 有结果 做切换；SCANNING/DONE 共用同一 target，
    // 避免扫描结束时整棵子树重建导致柱体 Animatable 归零重播。
    val showingResults = phase != ScanPhase.IDLE
    AnimatedContent(
        targetState = showingResults,
        transitionSpec = {
            if (targetState) {
                (fadeIn(Motion.normal()) + slideInVertically(Motion.normal()) { it / 14 }) togetherWith
                    fadeOut(Motion.fast())
            } else {
                (fadeIn(Motion.normal()) + slideInVertically(Motion.normal()) { -it / 14 }) togetherWith
                    (fadeOut(Motion.normal()) + slideOutVertically(Motion.normal()) { it / 14 })
            }.using(SizeTransform(clip = false))
        },
        label = "scanPhase",
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { results ->
        if (!results) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "未开始扫描",
                    fontSize = 18.sp,
                    color = AppColors.TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击「开始扫描」查看应用占用与可清理内容",
                    fontSize = 14.sp,
                    color = AppColors.TextTertiary,
                )
            }
        } else {
            ScanResultView(
                categories = categories,
                isScanning = phase == ScanPhase.SCANNING,
                totalBytes = categories.sumOf { it.bytes },
                selectedTargets = selectedTargets,
                onTargetToggle = onTargetToggle,
            )
        }
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
    // 单向展开：同时最多打开一个分类，避免多栏同时撑开把列表冲散。
    var expandedCategoryId by remember(categories) { mutableStateOf<String?>(null) }
    val selectedBytes = categories
        .flatMap { it.items }
        .filter { it.id in selectedTargets }
        .sumOf { it.bytes }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 42.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        // 顶对齐：展开后列表变高时若仍垂直居中，顶部会被父级裁掉。
        verticalAlignment = Alignment.Top,
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
            // 占比条两态各表意，且都随数据动态生长：
            // 扫描时 = 可清理 / 总占用（随字节累加实时增长，呼应「可清理共 X」）；
            // 完成后 = 已选 / 可清理（呼应「已选择 X」）。补间动画抹平两态切换。
            Spacer(modifier = Modifier.height(8.dp))
            val selectedFraction = if (isScanning) {
                if (totalBytes > 0L) (cleanableBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            } else {
                if (cleanableBytes > 0L) (selectedBytes.toFloat() / cleanableBytes).coerceIn(0f, 1f) else 0f
            }
            // 补间到目标占比，勾选/取消时宽度滑动而非瞬跳。
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

                // 头行 + 子项共一张卡：展开时往下长，而不是头下另挂一串游离行。
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
                        // 行内占比底纹：宽度 = 该类 / 总占用，用分类色淡染。
                        // retained 不染（它是留白概念），彩色只给可清理项。
                        // matchParentSize：不参与父测量，避免把分类头撑成整页高。
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

                            // 保留数据 = 总占用 − 已扫可清理，扫描中会一路变小；行保留，字节扫完再出。
                            if (!(isScanning && isRetained)) {
                                FlipBytesText(
                                    bytes = category.bytes,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isRetained) AppColors.TextSecondary else AppColors.TextPrimary,
                                )
                            }
                            if (isScanning) {
                                Spacer(modifier = Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = category.color,
                                    strokeWidth = 2.dp,
                                )
                            } else if (canExpand) {
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
                                    checked = target.id in selectedTargets,
                                    accent = category.color,
                                    onCheckedChange = { onTargetToggle(target.id) },
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
        // 分类色竖条：把子项钉在所属类上，避免展开后看起来像另一套列表。
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
            // 只在 OPTIONAL 亮说明：SAFE 的「会重建」对勾选决策没有增量信息。
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
                    animationSpec = Motion.grow(),
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

        AnimatedContent(
            targetState = showClean,
            transitionSpec = {
                if (targetState) {
                    (fadeIn(Motion.normal()) + slideInHorizontally(Motion.normal()) { it / 5 }) togetherWith
                        (fadeOut(Motion.fast()) + slideOutHorizontally(Motion.fast()) { -it / 5 })
                } else {
                    (fadeIn(Motion.normal()) + slideInHorizontally(Motion.normal()) { -it / 5 }) togetherWith
                        (fadeOut(Motion.fast()) + slideOutHorizontally(Motion.fast()) { it / 5 })
                }.using(SizeTransform(clip = false))
            },
            label = "topBarActions",
            contentAlignment = Alignment.CenterEnd,
        ) { cleanMode ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cleanMode) {
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

                    val cleanBg by animateColorAsState(
                        targetValue = if (cleanEnabled) AppColors.Primary else AppColors.PrimaryContainer,
                        animationSpec = Motion.normal(),
                        label = "cleanBtnBg",
                    )
                    val cleanFg by animateColorAsState(
                        targetValue = if (cleanEnabled) AppColors.OnPrimary else AppColors.Primary,
                        animationSpec = Motion.normal(),
                        label = "cleanBtnFg",
                    )
                    Button(
                        onClick = onRequestClean,
                        enabled = cleanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = cleanBg,
                            contentColor = cleanFg,
                            disabledBackgroundColor = cleanBg,
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
                    val scanEnabled = scanPhase != ScanPhase.SCANNING && selectedTools.isNotEmpty()
                    val scanBg by animateColorAsState(
                        targetValue = if (scanEnabled) AppColors.Primary else AppColors.PrimaryContainer,
                        animationSpec = Motion.normal(),
                        label = "scanBtnBg",
                    )
                    val scanFg by animateColorAsState(
                        targetValue = if (scanEnabled) AppColors.OnPrimary else AppColors.Primary,
                        animationSpec = Motion.normal(),
                        label = "scanBtnFg",
                    )
                    Button(
                        onClick = onStartScan,
                        enabled = scanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = scanBg,
                            contentColor = scanFg,
                            disabledBackgroundColor = scanBg,
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
        ToolIcon("Cursor", "icons/cursor.svg", "cursor" in selectedTools) { onToolToggle("cursor") }

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
    val bg by animateColorAsState(
        targetValue = if (isSelected) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "toolIconBg",
    )
    val tint by animateColorAsState(
        targetValue = if (isSelected) AppColors.OnPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "toolIconTint",
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
            painter = painterResource(iconPath),
            contentDescription = name,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CleaningStatsCard(refreshKey: Int, modifier: Modifier = Modifier) {
    // refreshKey 变化（一次清理完成）就重新从磁盘读，把最新记录带进来。
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

// 柱子固定宽度，靠左排列：只有一两根记录时也不会被 weight 拉宽成一整条。
private val BarWidth = 26.dp
private val BarSpacing = 8.dp
private val BarLabelHeight = 16.dp

/**
 * 最近几次清理的柱状图。每根柱子 = 一天的清理总量，只画有记录的天（空天跳过）。
 * 高度按当前窗口内的最大值归一，最新一根用主色高亮。
 */
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
                // 柱子只在这块「剩余高度」里按占比生长，日期标签始终有自己的固定高度。
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

/** 尚无清理记录时的骨架占位：几根等宽的空框，交代「这里将来会画柱状图」。 */
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

/**
 * C 盘占用卡：布局与左侧累计清理卡对齐——顶行左标签右百分比，底下一根横放圆柱。
 * 「C 盘占用」与左侧「累计清理」同顶对齐；正下方展示「已用 / 总量」。磁盘读取走 IO 线程；清理完成（refreshKey 变化）后重读一次。
 */
@Composable
private fun DiskUsageCard(refreshKey: Int, modifier: Modifier = Modifier) {
    var usage by remember { mutableStateOf<DiskUsage?>(null) }
    LaunchedEffect(refreshKey) {
        usage = withContext(Dispatchers.IO) { DiskUsageReader.readSystemDrive() }
    }
    val snapshot = usage
    val fraction = snapshot?.usedFraction ?: 0f
    val hasData = snapshot?.hasData == true
    // 补间到实测占比，读取完成时柱身从左往右生长而非瞬现。
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
                // 与「累计清理」卡一致顶对齐，避免标题被大号百分比往下拽。
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

            // 与左侧柱状图同高，贴底；横放的扫描风圆柱表达占用占比。
            DiskUsageCylinder(
                fraction = animatedFraction,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }
}

/**
 * 横放占用圆柱：柱面暗边、占用前缘端面高光对齐扫描页的 [StorageCylinder]。
 * 左端只做闭口圆角收口，不画竖筒顶那种开口玻璃透视。
 */
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

        // 空筒壳：竖直渐变交代横躺圆度。
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
        // 左右都是闭口端盖（对齐竖筒底），不用白色开口高光。
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
            // 柱面基本平涂，只在下沿收一点暗边；混同色系深色，不混黑。
            val bodyBrush = Brush.verticalGradient(
                0f to fillColor,
                0.80f to fillColor,
                1f to lerp(fillColor, AppColors.CategoryHistory, 0.16f),
                startY = top,
                endY = top + cylinderHeight,
            )
            // 两端各放出半个端盖：左闭口圆角 + 占用前缘端面。
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
                // 左端闭口：与壳同形，刷成填充色，避免露灰边。
                drawOval(
                    brush = bodyBrush,
                    topLeft = Offset(left - capWidth / 2f, top),
                    size = Size(capWidth, cylinderHeight),
                )
                // 占用前缘的端面高光。
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
                // 筒壁投在端面上的阴影，靠后沿最深。
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
