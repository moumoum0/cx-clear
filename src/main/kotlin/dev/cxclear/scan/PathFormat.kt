package dev.cxclear.scan

import java.nio.file.Path
import kotlin.io.path.name

/** 调试用：打印某个路径的名字，避免在日志里泄露完整路径。 */
internal fun Path.displayName(): String = name
