// 视频录制临时代码，暂时注释掉
/*
package dev.cxclear.ui.components

import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal class CylinderVideoRecorder private constructor(
    private val outputDirectory: Path,
) {
    private var frameIndex = 0

    suspend fun record(isActive: () -> Boolean, frame: () -> CylinderFrame?) {
        while (isActive()) {
            frame()?.let { currentFrame ->
                val image = CylinderRenderer.render(VideoWidth, VideoHeight, currentFrame)
                val outputFile = outputDirectory.resolve(String.format("frame_%05d.png", frameIndex++)).toFile()
                ImageIO.write(image, "png", outputFile)
            }
            delay(FrameIntervalMs)
        }
    }

    fun finish(): Path = outputDirectory

    companion object {
        private const val FrameIntervalMs = 1_000L / 60
        private const val VideoWidth = 480
        private const val VideoHeight = 720

        fun create(): CylinderVideoRecorder {
            val desktop = File(System.getProperty("user.home"), "Desktop")
            val baseDirectory = if (desktop.isDirectory) desktop.toPath() else Path.of(System.getProperty("user.home"))
            val outputDirectory = Files.createTempDirectory(baseDirectory, "CX-Clear-cylinder-")
            return CylinderVideoRecorder(outputDirectory)
        }
    }
}*/
