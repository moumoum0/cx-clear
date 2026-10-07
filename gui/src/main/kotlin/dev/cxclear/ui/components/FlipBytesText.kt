package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cxclear.ui.theme.Motion
import dev.cxclear.util.formatBytes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val UnitRank = mapOf("KB" to 0, "MB" to 1, "GB" to 2, "TB" to 3)

private const val DigitCycle = 10

private val DigitStrip = List(DigitCycle * 3) { it % DigitCycle }

private const val StripHome = DigitCycle

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

    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = fontSize,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
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

    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = fontSize,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
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
        number.forEachIndexed { index, char ->
            val fromRight = number.lastIndex - index
            key("n$fromRight") {
                if (char.isDigit()) {
                    DigitReel(
                        digit = char.digitToInt(),
                        style = style,
                        color = color,
                        modifier = Modifier.width(digitWidth),
                    )
                } else {
                    Text(text = char.toString(), style = style, color = color)
                }
            }
        }
    }
}

@Composable
private fun DigitReel(
    digit: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val index = remember { Animatable((StripHome + digit).toFloat()) }
    val smear = remember { Animatable(0f) }

    LaunchedEffect(digit) {
        val from = index.value
        val fromDigit = ((from.roundToInt() % DigitCycle) + DigitCycle) % DigitCycle
        val steps = ((fromDigit - digit) % DigitCycle + DigitCycle) % DigitCycle
        if (steps == 0) {
            if (index.value != (StripHome + digit).toFloat()) {
                index.snapTo((StripHome + digit).toFloat())
            }
            smear.snapTo(0f)
            return@LaunchedEffect
        }
        val depth = (abs(steps) / 4f).coerceAtMost(1f)
        coroutineScope {
            launch {
                index.animateTo(
                    from.roundToInt() - steps.toFloat(),
                    animationSpec = tween(durationMillis = Motion.FlipMs, easing = Motion.Roll),
                )
            }
            launch {
                smear.snapTo(0f)
                smear.animateTo(
                    0f,
                    animationSpec = keyframes {
                        durationMillis = Motion.FlipMs
                        0f at 0 using LinearEasing
                        depth at (Motion.FlipMs * 0.15f).toInt() using LinearEasing
                        0f at (Motion.FlipMs * 0.62f).toInt() using LinearEasing
                        0f at Motion.FlipMs
                    },
                )
            }
        }
        index.snapTo((StripHome + digit).toFloat())
        smear.snapTo(0f)
    }

    val density = LocalDensity.current
    // 最糊 0.09em，步数 / 4 封顶。停住时不挂 blur，0 半径会把字抹掉。
    val blurRadius = with(density) { (smear.value * style.fontSize.toPx() * 0.09f).toDp() }
    SubcomposeLayout(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val bleed = size.height * 0.27f
                if (bleed <= 0f) return@drawWithContent
                val veil = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (bleed * 0.35f / size.height).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.06f),
                    (bleed * 0.68f / size.height).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.4f),
                    (bleed / size.height).coerceIn(0f, 1f) to Color.Black,
                    (1f - bleed / size.height).coerceIn(0f, 1f) to Color.Black,
                    (1f - bleed * 0.68f / size.height).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.4f),
                    (1f - bleed * 0.35f / size.height).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                )
                drawRect(veil, blendMode = BlendMode.DstIn)
            },
    ) { constraints ->
        val probe = subcompose("probe") {
            Text(text = "0", style = style, color = color, maxLines = 1)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val cell = probe.height.coerceAtLeast(1)
        val cells = subcompose("strip") {
            Column(
                modifier = Modifier
                    .graphicsLayer { translationY = -index.value * cell }
                    .then(if (blurRadius > 0.05.dp) Modifier.blur(blurRadius) else Modifier),
            ) {
                DigitStrip.forEach { n ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cell.toDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = n.toString(),
                            style = style,
                            color = color,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }.first().measure(
            constraints.copy(minHeight = 0, maxHeight = cell * DigitStrip.size),
        )
        layout(cells.width, cell) {
            cells.place(0, 0)
        }
    }
}

@Composable
private fun FlipToken(
    text: String,
    rankOf: (String) -> Int,
    wrapRising: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(text) }
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
