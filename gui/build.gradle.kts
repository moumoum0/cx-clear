import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "dev.cxclear"
version = rootProject.version

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    // 必须跟 foundation 走同一发布列车：compose.desktop 1.11.1 把 foundation 拉到 1.12.0，
    // 而 material3 1.11.0-alpha07 是针对 foundation 1.11.0-beta03 编译的，
    // 运行期会因 CustomStyle.applyStyle 签名变化抛 AbstractMethodError（输入框一渲染就崩）。
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("dev.chrisbanes.haze:haze:2.0.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.0")
    // 视频录制临时用
    implementation("org.jcodec:jcodec-javase:0.2.5")
    testImplementation(kotlin("test"))
}

compose.resources {
    packageOfResClass = "dev.cxclear.resources"
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.cxclear.ui.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.uiScale.enabled=true",
        )

        // CLI jar 在 app-image 生成后加入；收缩 core 会删除 CLI 独有的调用路径。
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // Windows 走 app-image + Inno Setup（packageInnoSetup 任务），不再出 MSI。
            targetFormats(TargetFormat.Dmg)
            packageName = "CX Clear"
            packageVersion = version.toString()
            description = "AI Agent disk cleanup tool"
            vendor = "CX Clear"

            // 只打进实际用到的 JDK 模块，砍掉捆绑 JRE 体积。
            modules("java.base", "java.desktop", "java.logging", "java.net.http", "java.sql", "jdk.unsupported")

            windows {
                menuGroup = "CX Clear"
                upgradeUuid = "B5F8A2C1-3D4E-5F6A-7B8C-9D0E1F2A3B4C"
                dirChooser = true
                perUserInstall = true
                iconFile.set(rootProject.file("packaging/app_icon.ico"))
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

fun findIscc(): File {
    val candidates = listOf(
        File(System.getenv("LOCALAPPDATA") ?: "", "Programs/Inno Setup 6/ISCC.exe"),
        File("C:/Program Files (x86)/Inno Setup 6/ISCC.exe"),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("找不到 ISCC.exe，请先安装 Inno Setup 6：winget install JRSoftware.InnoSetup")
}

fun findOnPath(name: String): File? {
    val path = System.getenv("PATH") ?: return null
    val ext = if (name.contains('.')) "" else ".exe"
    return path.split(File.pathSeparator)
        .map { File(it, name + ext) }
        .firstOrNull { it.isFile }
}

fun findMingwTool(exe: String): File {
    val candidates = listOfNotNull(
        findOnPath(exe),
        File("C:/msys64/ucrt64/bin/$exe.exe"),
        File("C:/msys64/mingw64/bin/$exe.exe"),
        File("C:/mingw64/bin/$exe.exe"),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("找不到 $exe，请安装 MinGW-w64（MSYS2 ucrt64）")
}

fun runLogged(args: List<String>, workDir: File) {
    val proc = ProcessBuilder(args)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val drain = Thread {
        proc.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
    }.apply { isDaemon = true; start() }
    val exit = proc.waitFor()
    drain.join()
    if (exit != 0) error("${args.first()} 失败，退出码 $exit")
}

fun compileCliLauncher(): File {
    val gxx = findMingwTool("g++")
    val outDir = layout.buildDirectory.dir("compose/binaries/main-release/packaging/launcher").get().asFile
    outDir.mkdirs()
    val exe = File(outDir, "cxclear.exe")
    val packDir = rootProject.file("packaging")
    runLogged(
        listOf(
            gxx.absolutePath,
            "-O2",
            "-municode",
            "-static",
            "cli_launcher.cpp",
            "-o",
            exe.absolutePath,
        ),
        packDir,
    )
    require(exe.isFile) { "CLI 启动器未生成：$exe" }
    return exe
}

fun compileGuiLauncher(): File {
    val gxx = findMingwTool("g++")
    val windres = findMingwTool("windres")
    val outDir = layout.buildDirectory.dir("compose/binaries/main-release/packaging/launcher").get().asFile
    outDir.mkdirs()
    val resObj = File(outDir, "launcher.res")
    val exe = File(outDir, "CX Clear.exe")
    val packDir = rootProject.file("packaging")
    runLogged(
        listOf(windres.absolutePath, "launcher.rc", "-O", "coff", "-o", resObj.absolutePath),
        packDir,
    )
    runLogged(
        listOf(
            gxx.absolutePath,
            "-O2",
            "-municode",
            "-mwindows",
            "-static",
            "launcher.cpp",
            resObj.absolutePath,
            "-lshell32",
            "-o",
            exe.absolutePath,
        ),
        packDir,
    )
    require(exe.isFile) { "启动器未生成：$exe" }
    return exe
}

fun killStuckIscc() {
    runCatching {
        ProcessBuilder("taskkill", "/F", "/IM", "ISCC.exe")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    }
}

fun prepareWindowsImage(includeJre: Boolean, launcher: File, cliLauncher: File): File {
    val src = layout.buildDirectory
        .dir("compose/binaries/main-release/app/CX Clear").get().asFile
    require(src.isDirectory) { "未找到 app-image：$src（createReleaseDistributable 应已生成）" }
    val dest = layout.buildDirectory
        .dir("compose/binaries/main-release/packaging/${if (includeJre) "jre" else "no-jre"}/CX Clear")
        .get().asFile
    dest.deleteRecursively()
    dest.mkdirs()
    copy {
        from(src)
        into(dest)
        includeEmptyDirs = false
        if (!includeJre) {
            exclude("CX Clear.exe")
            exclude("runtime/**")
        }
    }
    copy {
        if (!includeJre) {
            from(launcher)
        }
        from(cliLauncher)
        from(rootProject.file("packaging/app_icon.ico"))
        into(dest)
    }
    if (includeJre) {
        val javaExe = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().executablePath.asFile
        require(javaExe.isFile) { "未找到 Java 21 控制台启动程序：$javaExe" }
        copy {
            from(javaExe)
            into(File(dest, "runtime/bin"))
        }
    }
    val cliJar = rootProject.file("cli/build/libs/cli-${version}.jar")
    require(cliJar.isFile) { "未找到 CLI jar：$cliJar" }
    copy {
        from(cliJar)
        into(File(dest, "app"))
    }
    return dest
}

fun zipDirectory(sourceDir: File, zipFile: File) {
    zipFile.parentFile.mkdirs()
    if (zipFile.exists() && !zipFile.delete()) {
        error("旧压缩包仍被占用，无法覆盖：$zipFile")
    }
    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
        val rootName = sourceDir.name
        sourceDir.walkTopDown().forEach { file ->
            val rel = sourceDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val entryName = if (rel.isEmpty()) {
                "$rootName/"
            } else {
                "$rootName/$rel${if (file.isDirectory) "/" else ""}"
            }
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.closeEntry()
            } else {
                zos.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}

fun runIscc(
    iscc: File,
    appVersion: String,
    appDir: File,
    outDir: File,
    outputBase: String,
) {
    val outExe = File(outDir, "$outputBase.exe")
    if (outExe.exists() && !outExe.delete()) {
        error("旧安装器仍被占用，无法覆盖：$outExe\n已尝试结束 ISCC.exe 但文件仍被锁——可能是你双击运行过它、或杀软正在扫描，先关掉再重试。")
    }
    val script = rootProject.file("packaging/setup.iss")
    val args = listOf(
        iscc.absolutePath,
        "/DAPP_VERSION=$appVersion",
        "/DAPP_DIR=${appDir.absolutePath}",
        "/DOUTPUT_DIR=${outDir.absolutePath}",
        "/DOUTPUT_BASE=$outputBase",
        script.absolutePath,
    )
    // 不能用 inheritIO()：Gradle daemon 后台运行时无人读取子进程管道，
    // ISCC 打印进度会写满 stdout 缓冲区并永久阻塞（表现为编译卡死、锁住输出文件）。
    val proc = ProcessBuilder(args)
        .directory(script.parentFile)
        .redirectErrorStream(true)
        .start()
    val drain = Thread {
        proc.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
    }.apply { isDaemon = true; start() }
    val exit = proc.waitFor()
    drain.join()
    if (exit != 0) error("Inno Setup 编译失败，退出码 $exit（$outputBase）")
}

// app-image + Inno Setup / zip：安装器与免安装各出带 Java、不带 Java。
// 前置：ISCC.exe（Inno Setup 6，winget JRSoftware.InnoSetup）。
tasks.register("packageInnoSetup") {
    group = "compose desktop"
    description = "打 Windows 安装器与免安装包（带 Java / 不带 Java）"
    dependsOn("createReleaseDistributable")
    dependsOn(":cli:jar")
    doLast {
        val iscc = findIscc()
        killStuckIscc()
        val appVersion = version.toString()
        val outDir = layout.buildDirectory.dir("compose/binaries/main/dist").get().asFile
        outDir.mkdirs()

        val launcher = compileGuiLauncher()
        val cliLauncher = compileCliLauncher()
        val withJre = prepareWindowsImage(includeJre = true, launcher, cliLauncher)
        val noJre = prepareWindowsImage(includeJre = false, launcher, cliLauncher)

        runIscc(iscc, appVersion, withJre, outDir, "CXClear-$appVersion-setup")
        runIscc(iscc, appVersion, noJre, outDir, "CXClear-$appVersion-setup-no-jre")

        zipDirectory(withJre, File(outDir, "CXClear-$appVersion-portable.zip"))
        zipDirectory(noJre, File(outDir, "CXClear-$appVersion-portable-no-jre.zip"))

        logger.lifecycle("Windows 包已生成：$outDir")
    }
}
