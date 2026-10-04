package dev.cxclear.scan

import java.nio.file.Path
import kotlin.io.path.name

// 调试用：日志里只打印路径名，完整路径会泄露用户目录结构。
internal fun Path.displayName(): String = name
