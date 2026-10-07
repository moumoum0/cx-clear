import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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

tasks.register<Sync>("prepareNpmPackage") {
    group = "distribution"
    description = "Prepare the Windows x64 npm package using the Gradle application version"
    dependsOn("installDist")
    val target = rootProject.layout.buildDirectory.dir("npm/cxclear")
    into(target)
    from(rootProject.file("npm")) {
        exclude("package.json.in", "test/**")
    }
    from(layout.buildDirectory.dir("install/cxclear")) {
        into("native")
        include("lib/*.jar")
    }
    from(rootProject.file("LICENSE"))
    inputs.property("appVersion", project.version.toString())
    inputs.file(rootProject.file("npm/package.json.in"))
    outputs.upToDateWhen { false }
    doLast {
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(rootProject.file("npm/package.json.in")) as MutableMap<String, Any?>
        manifest["version"] = project.version.toString()
        val directory = target.get().asFile
        File(directory, "package.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(rootProject.projectDir).start()
        val commit = git.inputStream.bufferedReader().readText().trim()
        check(git.waitFor() == 0 && commit.matches(Regex("[a-f0-9]{40}"))) { "Cannot determine source commit for npm package" }
        val source = mapOf(
            "version" to project.version.toString(),
            "commit" to commit,
            "url" to "https://github.com/moumoum0/cx-clear/tree/$commit",
        )
        File(directory, "source.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(source)) + "\n")
        logger.lifecycle("npm package prepared: $directory (${project.version})")
    }
}
