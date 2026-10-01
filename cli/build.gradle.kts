import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm")
    application
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.cxclear.cli.MainKt")
    applicationName = "cxclear"
}

tasks.named<CreateStartScripts>("startScripts") {
    // 发布包只用原生 cxclear.exe，不带 application plugin 生成的 bat/sh
    enabled = false
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

val launcher = layout.buildDirectory.file("launcher/cxclear.exe")
val compileCliLauncher = tasks.register("compileCliLauncher") {
    group = "distribution"
    inputs.file(rootProject.file("packaging/cli_launcher.cpp"))
    outputs.file(launcher)
    doLast {
        val compiler = listOf(
            System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "g++.exe") }.firstOrNull { it.isFile },
            File("C:/msys64/ucrt64/bin/g++.exe"),
        ).firstOrNull { it?.isFile == true } ?: error("找不到 MinGW-w64 g++.exe")
        val executable = launcher.get().asFile
        executable.parentFile.mkdirs()
        val process = ProcessBuilder(
            compiler.absolutePath, "-O2", "-s", "-municode", "-static",
            rootProject.file("packaging/cli_launcher.cpp").absolutePath,
            "-o", executable.absolutePath,
        ).inheritIO().start()
        check(process.waitFor() == 0) { "CLI 启动器编译失败" }
    }
}

tasks.named<Sync>("installDist") {
    dependsOn(tasks.jar, compileCliLauncher)
    inputs.file(rootProject.file("packaging/windows-native-jars.gradle.kts"))
    from(launcher)
    doFirst {
        val expected = layout.buildDirectory.dir("install/cxclear").get().asFile.canonicalFile
        check(destinationDir.canonicalFile == expected) { "拒绝清理非默认安装目录：$destinationDir" }
        check(expected.deleteRecursively()) { "无法更新 CLI 构建目录：$expected" }
    }
    doLast {
        @Suppress("UNCHECKED_CAST")
        val trimNativeJars = rootProject.extra["trimWindowsNativeJars"] as (File) -> Unit
        trimNativeJars(File(destinationDir, "lib"))
    }
}

tasks.register<JavaExec>("verifyWindowsNativeJars") {
    group = "verification"
    description = "Verify SQLite and concatenated Zstd frames using trimmed distribution JARs"
    dependsOn("installDist")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    mainClass.set(rootProject.file("packaging/NativeLibrariesSmokeTest.java").absolutePath)
    classpath = fileTree(layout.buildDirectory.dir("install/cxclear/lib")) {
        include("*.jar")
    }
}

tasks.register("packageCli") {
    group = "distribution"
    description = "Build a Windows CLI zip with the native cxclear.exe launcher"
    dependsOn("installDist")
    doLast {
        val dist = layout.buildDirectory.dir("install/cxclear").get().asFile
        val output = layout.buildDirectory.dir("distributions").get().asFile.apply { mkdirs() }
        val zip = File(output, "CXClear-${version}-cli.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { stream ->
            dist.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(dist).path.replace(File.separatorChar, '/')
                stream.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(stream) }
                stream.closeEntry()
            }
        }
        logger.lifecycle("CLI 包已生成：$zip")
    }
}
