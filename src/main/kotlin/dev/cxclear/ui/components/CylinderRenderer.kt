// 视频录制临时代码，暂时注释掉
/*
package dev.cxclear.ui.components

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max

internal data class CylinderLayer(
    val color: Int,
    val share: Float,
)

internal data class CylinderFrame(
    val layers: List<CylinderLayer>,
    val isScanning: Boolean,
    val showSweep: Boolean,
    val sweepProgress: Float,
    val backgroundColor: Int,
    val palette: CylinderPalette,
)

internal data class CylinderPalette(
    val shellEdge: Int,
    val shellLight: Int,
    val shellMid: Int,
    val categoryHistory: Int,
)

internal object CylinderRenderer {
    fun render(width: Int, height: Int, frame: CylinderFrame): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            image.createGraphics().let { graphics ->
                try {
                    graphics.enableAntialiasing()
                    graphics.color = Color(frame.backgroundColor, true)
                    graphics.fillRect(0, 0, width, height)
                    draw(graphics, width.toFloat(), height.toFloat(), frame)
                } finally {
                    graphics.dispose()
                }
            }
        }

    private fun draw(graphics: Graphics2D, width: Float, height: Float, frame: CylinderFrame) {
        val cylinderWidth = width * 0.66f
        val left = (width - cylinderWidth) / 2f
        val right = left + cylinderWidth
        val capHeight = cylinderWidth * 0.22f
        val top = capHeight / 2f
        val bottom = height - capHeight / 2f
        val bodyHeight = bottom - top

        graphics.paint = LinearGradientPaint(
            left,
            0f,
            right,
            0f,
            floatArrayOf(0f, 0.34f, 0.68f, 1f),
            arrayOf(
                Color(frame.palette.shellEdge, true),
                Color(frame.palette.shellLight, true),
                Color(frame.palette.shellMid, true),
                Color(frame.palette.shellEdge, true),
            ),
        )
        graphics.fill(Rectangle2D.Float(left, top, cylinderWidth, bodyHeight))
        graphics.paint = GradientPaint(
            0f,
            0f,
            Color.WHITE,
            0f,
            capHeight,
            Color(frame.palette.shellMid, true),
        )
        graphics.fill(Ellipse2D.Float(left, 0f, cylinderWidth, capHeight))
        graphics.paint = GradientPaint(
            0f,
            bottom - capHeight / 2f,
            Color(frame.palette.shellMid, true),
            0f,
            bottom + capHeight / 2f,
            Color(frame.palette.shellLight, true),
        )
        graphics.fill(Ellipse2D.Float(left, bottom - capHeight / 2f, cylinderWidth, capHeight))

        val slices = slice(frame.layers, bottom, bodyHeight, capHeight * 0.55f, frame.isScanning)
        val fillTop = slices.lastOrNull()?.top ?: bottom
        val originalClip = graphics.clip
        graphics.clip(Rectangle2D.Float(left, top - capHeight / 2f, cylinderWidth, bodyHeight + capHeight))
        slices.forEach { slice ->
            val color = Color(slice.color, true)
            graphics.paint = LinearGradientPaint(
                left,
                0f,
                right,
                0f,
                floatArrayOf(0f, 0.8f, 1f),
                arrayOf(color, color, Color(mix(slice.color, frame.palette.categoryHistory, 0.16f), true)),
            )
            graphics.fill(Rectangle2D.Float(left, slice.top, cylinderWidth, slice.bottom - slice.top))
            graphics.fill(Ellipse2D.Float(left, slice.bottom - capHeight / 2f, cylinderWidth, capHeight))
        }
        slices.lastOrNull()?.let { slice ->
            graphics.paint = GradientPaint(
                0f,
                fillTop - capHeight / 2f,
                Color(mix(slice.color, Color.WHITE.rgb, 0.32f), true),
                0f,
                fillTop + capHeight / 2f,
                Color(mix(slice.color, Color.WHITE.rgb, 0.08f), true),
            )
            graphics.fill(Ellipse2D.Float(left, fillTop - capHeight / 2f, cylinderWidth, capHeight))
            graphics.paint = GradientPaint(
                0f,
                fillTop - capHeight / 2f,
                Color(0, 0, 0, 26),
                0f,
                fillTop + capHeight * 0.18f,
                Color(0, 0, 0, 0),
            )
            graphics.fill(Ellipse2D.Float(left, fillTop - capHeight / 2f, cylinderWidth, capHeight))
        }
        if (frame.showSweep) {
            val bandHeight = bodyHeight * 0.24f
            val bandTop = top - bandHeight + (bodyHeight + bandHeight) * (1f - frame.sweepProgress)
            graphics.paint = LinearGradientPaint(
                0f,
                bandTop,
                0f,
                bandTop + bandHeight,
                floatArrayOf(0f, 0.5f, 1f),
                arrayOf(Color(255, 255, 255, 0), Color(255, 255, 255, 87), Color(255, 255, 255, 0)),
            )
            graphics.fill(Rectangle2D.Float(left, bandTop, cylinderWidth, bandHeight))
        }
        graphics.clip = originalClip
        graphics.color = Color(255, 255, 255, 71)
        graphics.fill(Ellipse2D.Float(left, 0f, cylinderWidth, capHeight))
    }

    private fun slice(
        layers: List<CylinderLayer>,
        bottom: Float,
        bodyHeight: Float,
        minHeight: Float,
        stubZero: Boolean,
    ): List<CylinderSlice> {
        val heights = layers.map { layer ->
            when {
                layer.share <= 0.0005f -> if (stubZero) minHeight else 0f
                else -> max(layer.share * bodyHeight, minHeight)
            }
        }
        val used = heights.sum()
        val scale = if (used > bodyHeight) bodyHeight / used else 1f
        var cursor = bottom
        return layers.indices.mapNotNull { index ->
            val height = heights[index] * scale
            if (height <= 0f) return@mapNotNull null
            CylinderSlice(layers[index].color, cursor - height, cursor).also { cursor -= height }
        }
    }

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val source = Color(first, true)
        val target = Color(second, true)
        val inverse = 1f - amount
        return Color(
            (source.red * inverse + target.red * amount).toInt(),
            (source.green * inverse + target.green * amount).toInt(),
            (source.blue * inverse + target.blue * amount).toInt(),
            (source.alpha * inverse + target.alpha * amount).toInt(),
        ).rgb
    }

    private fun Graphics2D.enableAntialiasing() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        stroke = BasicStroke(1f)
    }
}

private data class CylinderSlice(
    val color: Int,
    val top: Float,
    val bottom: Float,
)*/
