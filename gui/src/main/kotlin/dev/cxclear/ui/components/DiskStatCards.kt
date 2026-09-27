package dev.cxclear.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.storage.CleanHistory
import dev.cxclear.storage.DailyClean
import dev.cxclear.storage.DiskUsage
import dev.cxclear.storage.DiskUsageReader
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 底部两张统计卡：累计清理（含近 7 天柱状图）与 C 盘占用圆柱。
 * 两张卡都由 refreshKey 驱动重读磁盘，清理完成后父级 bump 它。
 */

@Composable
internal fun CleaningStatsCard(refreshKey: Int, modifier: Modifier = Modifier) {
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
internal fun CleanHistoryBars(daily: List<DailyClean>, modifier: Modifier = Modifier) {
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
internal fun CleanHistoryBarsPlaceholder(modifier: Modifier = Modifier) {
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
internal fun DiskUsageCard(refreshKey: Int, modifier: Modifier = Modifier) {
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
