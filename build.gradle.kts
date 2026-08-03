import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "dev.cxclear"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    // JB 官方定格版本，不会再更新，必须显式写死。
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("net.java.dev.jna:jna:5.19.1")
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
        mainClass = "dev.cxclear.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.uiScale.enabled=true",
        )

        // 只删未引用的代码/图标（material-icons-extended 是体积大头），减小安装包。
        // optimize 关掉：它会改写方法签名/内联，是之前 metadata 警告和潜在崩溃的来源；
        // 纯 shrink 只删没用到的东西、不改写任何一行，最安全。
        buildTypes.release.proguard {
            // Compose 1.11.1 自带的 ProGuard 太旧，读不了 Kotlin 2.4 的 metadata，必须覆盖成支持 2.4 的版本。
            version.set("7.9.1")
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            // Windows 走 app-image + Inno Setup（packageInnoSetup 任务），不再出 MSI。
            targetFormats(TargetFormat.Dmg)
            packageName = "CX Clear"
            packageVersion = "1.0.0"
            description = "AI Agent disk cleanup tool"
            vendor = "CX Clear"

            // 只打进实际用到的 JDK 模块，砍掉捆绑 JRE 体积。
            modules("java.base", "java.desktop", "java.logging", "jdk.unsupported")

            windows {
                menuGroup = "CX Clear"
                upgradeUuid = "B5F8A2C1-3D4E-5F6A-7B8C-9D0E1F2A3B4C"
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("packaging/app_icon.ico"))
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// app-image + Inno Setup：出带现代向导、简体中文的 per-user EXE 安装器。
// 前置：ISCC.exe（Inno Setup 6，winget JRSoftware.InnoSetup）。
tasks.register("packageInnoSetup") {
    group = "compose desktop"
    description = "用 Inno Setup 把 app-image 打成现代 EXE 安装器"
    // release 变体才会跑 ProGuard 收缩，安装包更小；对应产物在 main-release 目录。
    dependsOn("createReleaseDistributable")
    doLast {
        val isccCandidates = listOf(
            File(System.getenv("LOCALAPPDATA") ?: "", "Programs/Inno Setup 6/ISCC.exe"),
            File("C:/Program Files (x86)/Inno Setup 6/ISCC.exe"),
        )
        val iscc = isccCandidates.firstOrNull { it.isFile }
            ?: error("找不到 ISCC.exe，请先安装 Inno Setup 6：winget install JRSoftware.InnoSetup")

        // ISCC 上次编译被中断时会卡在重试循环里、一直锁着输出文件（不会自己退出）。
        // 打包前先无条件清掉残留 ISCC 进程，避免下一次写不进输出而报 corrupted。
        runCatching {
            ProcessBuilder("taskkill", "/F", "/IM", "ISCC.exe")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        }

        val appDir = layout.buildDirectory
            .dir("compose/binaries/main-release/app/CX Clear").get().asFile
        require(appDir.isDirectory) { "未找到 app-image：$appDir（createDistributable 应已生成）" }

        val outDir = layout.buildDirectory
            .dir("compose/binaries/main/innosetup").get().asFile
        val outExe = File(outDir, "CXClear-$version-setup.exe")
        if (outExe.exists() && !outExe.delete()) {
            error("旧安装器仍被占用，无法覆盖：$outExe\n已尝试结束 ISCC.exe 但文件仍被锁——可能是你双击运行过它、或杀软正在扫描，先关掉再重试。")
        }

        val script = project.file("packaging/setup.iss")
        // 不能用 inheritIO()：Gradle daemon 后台运行时无人读取子进程管道，
        // ISCC 打印进度会写满 stdout 缓冲区并永久阻塞（表现为编译卡死、锁住输出文件）。
        // 必须主动把 ISCC 的输出流读走。
        val proc = ProcessBuilder(
            iscc.absolutePath,
            "/DAPP_VERSION=$version",
            "/DAPP_DIR=${appDir.absolutePath}",
            script.absolutePath,
        ).directory(script.parentFile)
            .redirectErrorStream(true)
            .start()
        val drain = Thread {
            proc.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        }.apply { isDaemon = true; start() }
        val exit = proc.waitFor()
        drain.join()
        if (exit != 0) error("Inno Setup 编译失败，退出码 $exit")
        logger.lifecycle("Inno Setup 安装器已生成：$outDir")
    }
}
