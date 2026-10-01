plugins {
    base
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

group = "dev.cxclear"
version = "1.2.1"

apply(from = "packaging/windows-native-jars.gradle.kts")

// 入口已经拆到 :gui / :cli。kt run 按任务名会匹配所有子模块的 run，
// 根项目不再自己起进程，只把这次运行转给 GUI。
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
