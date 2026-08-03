package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cxclear.scan.formatBytes
import dev.cxclear.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val UnitRank = mapOf("B" to 0, "KB" to 1, "MB" to 2, "GB" to 3, "TB" to 4)


/**
 * 容量数字的翻转显示：每位数字与单位都只在新旧两值之间切换，
 * 旧字上滑淡出、新字下滑淡入，不经过中间值。小数点保持静止。
 *
 * 显示值会等本轮翻转播完再追上最新 [bytes]，避免扫描节拍快于动画时翻牌被打断。
 */
@Composable
fun FlipBytesText(
    bytes: Long,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    var displayed by remember { mutableLongStateOf(bytes) }
    val latestBytes by rememberUpdatedState(bytes)

    LaunchedEffect(Unit) {
        while (true) {
            if (displayed != latestBytes) {
                displayed = latestBytes
                delay(Motion.FlipMs.toLong())
            } else {
                snapshotFlow { latestBytes }.first { it != displayed }
            }
        }
    }

    val label = formatBytes(displayed)
    val unitStart = label.indexOfFirst { it.isLetter() }
    val number = if (unitStart >= 0) label.substring(0, unitStart).trimEnd() else label
    val unit = if (unitStart >= 0) label.substring(unitStart) else ""
    val leadingSpace = unitStart > 0 && label[unitStart - 1] == ' '

    val style = TextStyle(fontSize = fontSize, fontWeight = fontWeight)
    val measurer = rememberTextMeasurer()
    val digitLayout = remember(style, measurer) { measurer.measure("0", style) }
    val density = LocalDensity.current
    val digitWidth = with(density) { digitLayout.size.width.toDp() }
    val digitHeight = with(density) { digitLayout.size.height.toDp() }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FlipNumberDigits(
            number = number,
            style = style,
            color = color,
            digitWidth = digitWidth,
            digitHeight = digitHeight,
        )
        if (unit.isNotEmpty()) {
            if (leadingSpace) {
                Text(text = " ", style = style, color = color)
            }
            FlipToken(
                text = unit,
                rankOf = { UnitRank[it] ?: 0 },
                wrapRising = false,
                style = style,
                color = color,
                modifier = Modifier.height(digitHeight),
            )
        }
    }
}

/**
 * 整数个数的翻转显示，节奏与 [FlipBytesText] 相同。
 *
 * [onSettled] 在显示值追上目标并完成本轮翻牌后回调，供加载态等「播完再切」使用。
 */
@Composable
fun FlipCountText(
    count: Int,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    onSettled: ((settled: Int) -> Unit)? = null,
) {
    var displayed by remember { mutableIntStateOf(count) }
    val latestCount by rememberUpdatedState(count)
    val onSettledState by rememberUpdatedState(onSettled)

    LaunchedEffect(Unit) {
        while (true) {
            if (displayed != latestCount) {
                displayed = latestCount
                delay(Motion.FlipMs.toLong())
            } else {
                onSettledState?.invoke(displayed)
                snapshotFlow { latestCount }.first { it != displayed }
            }
        }
    }

    val style = TextStyle(fontSize = fontSize, fontWeight = fontWeight)
    val measurer = rememberTextMeasurer()
    val digitLayout = remember(style, measurer) { measurer.measure("0", style) }
    val density = LocalDensity.current
    val digitWidth = with(density) { digitLayout.size.width.toDp() }
    val digitHeight = with(density) { digitLayout.size.height.toDp() }

    FlipNumberDigits(
        number = displayed.coerceAtLeast(0).toString(),
        style = style,
        color = color,
        digitWidth = digitWidth,
        digitHeight = digitHeight,
        modifier = modifier,
    )
}

@Composable
private fun FlipNumberDigits(
    number: String,
    style: TextStyle,
    color: Color,
    digitWidth: Dp,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // 数字段自右向左编号：个位恒为 0，进位时左侧长出新列，右侧列身份稳定。
        number.forEachIndexed { index, char ->
            val fromRight = number.lastIndex - index
            key("n$fromRight") {
                if (char.isDigit()) {
                    FlipToken(
                        text = char.toString(),
                        rankOf = { it.singleOrNull()?.digitToIntOrNull() ?: 0 },
                        wrapRising = true,
                        // 新进位列首次入场时从 0 翻到目标位，避免只有个位在转、高位直接跳出。
                        appearFromZero = true,
                        style = style,
                        color = color,
                        modifier = Modifier.width(digitWidth).height(digitHeight),
                    )
                } else {
                    Text(text = char.toString(), style = style, color = color)
                }
            }
        }
    }
}

/**
 * @param wrapRising 数字进位 9→0 时仍视为「往上翻」；单位不适用。
 * @param appearFromZero 首次入场先显示 0 再翻到 [text]，让新数位列也有翻牌过程。
 */
@Composable
private fun FlipToken(
    text: String,
    rankOf: (String) -> Int,
    wrapRising: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    appearFromZero: Boolean = false,
) {
    var shown by remember {
        mutableStateOf(if (appearFromZero) "0" else text)
    }
    LaunchedEffect(text) {
        shown = text
    }

    Box(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                val from = rankOf(initialState)
                val to = rankOf(targetState)
                val rising = to > from || (wrapRising && from == 9 && to == 0)
                val enter = (if (rising) {
                    slideInVertically(Motion.flip()) { it }
                } else {
                    slideInVertically(Motion.flip()) { -it }
                }) + fadeIn(Motion.flip())
                val exit = (if (rising) {
                    slideOutVertically(Motion.flip()) { -it }
                } else {
                    slideOutVertically(Motion.flip()) { it }
                }) + fadeOut(Motion.flip())
                enter togetherWith exit
            },
            label = "flipToken",
        ) { value ->
            Text(
                text = value,
                style = style,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
    }
}
