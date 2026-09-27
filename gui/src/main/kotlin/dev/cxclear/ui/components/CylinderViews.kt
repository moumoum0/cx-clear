package dev.cxclear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 两套容量圆柱绘制：[StorageCylinder] 是竖向的堆叠柱（按分类从下往上码），
 * [DiskUsageCylinder] 是横向的单色填充柱（按比例从左往右长）。
 * 渐变与端盖数学各写一份、方向不同，别图省事合并。
 */

internal data class CylinderSlice(
    val color: Color,
    val top: Float,
    val bottom: Float,
)

// 极小段抬高再压回，不然被底盘盖没；stubZero 扫初期画扁片。
internal fun sliceCylinder(
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
internal fun StorageCylinder(
    categories: List<ScanCategory>,
    isScanning: Boolean,
    showSweep: Boolean = isScanning,
    modifier: Modifier = Modifier,
) {
    val totalBytes = categories.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)
    // retained 只留白，不进柱体；图例自上而下 → 柱体反转堆。
    val stack = remember(categories) { categories.filter { it.id != "retained" }.asReversed() }
    val colors = AppColors
    val stackColors = stack.map { categoryAccent(it.id) }

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
                    colors.CylinderShellEdge,
                    colors.CylinderShellLight,
                    colors.CylinderShellMid,
                    colors.CylinderShellEdge,
                ),
                startX = left,
                endX = right,
            ),
            topLeft = Offset(left, top),
            size = Size(cylinderWidth, bodyHeight),
        )
        drawOval(
            brush = Brush.verticalGradient(listOf(colors.Highlight, colors.CylinderShellMid)),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
        )
        drawOval(
            brush = Brush.verticalGradient(
                listOf(colors.CylinderShellMid, colors.CylinderShellLight),
            ),
            topLeft = Offset(left, bottom - capHeight / 2f),
            size = Size(cylinderWidth, capHeight),
        )

        val slices = sliceCylinder(
            colors = stackColors,
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
                    1f to lerp(slice.color, colors.CategoryHistory, 0.16f),
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
                            lerp(topSlice.color, colors.Highlight, 0.32f),
                            lerp(topSlice.color, colors.Highlight, 0.08f),
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
                        listOf(
                            Color.Transparent,
                            colors.Highlight.copy(alpha = 0.34f),
                            Color.Transparent,
                        ),
                        startY = bandTop,
                        endY = bandTop + bandHeight,
                    ),
                    topLeft = Offset(left, bandTop),
                    size = Size(cylinderWidth, bandHeight),
                )
            }
        }

        drawOval(
            color = colors.Highlight.copy(alpha = 0.28f),
            topLeft = Offset(left, 0f),
            size = Size(cylinderWidth, capHeight),
        )
    }
}

@Composable
internal fun DiskUsageCylinder(fraction: Float, modifier: Modifier = Modifier) {
    val fill = fraction.coerceIn(0f, 1f)
    val colors = AppColors
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
                    colors.CylinderShellEdge,
                    colors.CylinderShellLight,
                    colors.CylinderShellMid,
                    colors.CylinderShellEdge,
                ),
                startY = top,
                endY = top + cylinderHeight,
            ),
            topLeft = Offset(left, top),
            size = Size(bodyWidth, cylinderHeight),
        )
        drawOval(
            brush = Brush.horizontalGradient(
                listOf(colors.CylinderShellLight, colors.CylinderShellMid),
            ),
            topLeft = Offset(0f, top),
            size = Size(capWidth, cylinderHeight),
        )
        drawOval(
            brush = Brush.horizontalGradient(
                listOf(colors.CylinderShellMid, colors.CylinderShellLight),
            ),
            topLeft = Offset(right - capWidth / 2f, top),
            size = Size(capWidth, cylinderHeight),
        )

        if (fill > 0f) {
            val fillRight = left + bodyWidth * fill
            val fillColor = colors.Primary
            // 同色系暗边，别混黑。
            val bodyBrush = Brush.verticalGradient(
                0f to fillColor,
                0.80f to fillColor,
                1f to lerp(fillColor, colors.CategoryHistory, 0.16f),
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
                            lerp(fillColor, colors.Highlight, 0.32f),
                            lerp(fillColor, colors.Highlight, 0.08f),
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
