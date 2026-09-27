package dev.cxclear.cli

fun main(args: Array<String>) {
    // 修复 Windows 控制台 UTF-8 输出乱码
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")
    try {
        System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
        System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))
    } catch (e: Exception) {
        // 忽略编码设置失败
    }
    
    Cli.runAndExit(args)
}
