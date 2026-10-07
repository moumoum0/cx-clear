plugins {
    base
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

group = "dev.cxclear"
version = "1.3.1"

apply(from = "packaging/windows-native-jars.gradle.kts")

// 根项目的 run 按任务名会匹配到所有子模块。
tasks.register("run") {
    group = "application"
    description = "启动 GUI"
    dependsOn(":gui:run")
}

tasks.register("packageInnoSetup") {
    group = "distribution"
    description = "打 Windows 安装器与免安装包（带 Java / 不带 Java）"
    dependsOn(":gui:packageInnoSetup")
}
