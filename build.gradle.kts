import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
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
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("net.java.dev.jna:jna:5.19.1")
}

compose.desktop {
    application {
        mainClass = "dev.cxclear.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.uiScale.enabled=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Cx Clear"
            packageVersion = "1.0.0"
            description = "AI Agent disk cleanup tool"
            vendor = "Cx Clear"

            windows {
                menuGroup = "Cx Clear"
                upgradeUuid = "B5F8A2C1-3D4E-5F6A-7B8C-9D0E1F2A3B4C"
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}
