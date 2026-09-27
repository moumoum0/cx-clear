package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.model.Risk
import dev.cxclear.model.TargetKey
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.Motion
import dev.cxclear.util.formatBytes

/**
 * 扫描结果主体：左侧圆柱 + 右侧分类卡片列表。
 * 分类卡片可展开成逐条勾选行，扫描中只显示进度不做勾选。
 */

@Composable
internal fun ScanView(
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
internal fun ScanResultView(
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
                val accent = categoryAccent(category.id)
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
                                        .background(accent.copy(alpha = if (isExpanded) 0.18f else 0.14f)),
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
                                        .background(accent, RoundedCornerShape(99.dp))
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
                                    accent = accent,
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
internal fun TargetSelectionRow(
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
