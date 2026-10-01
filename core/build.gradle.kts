plugins {
    kotlin("jvm")
}

group = "dev.cxclear"
version = rootProject.version

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.github.luben:zstd-jni:1.5.7-4")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

val generateAppVersion = tasks.register("generateAppVersion") {
    val outputDir = layout.buildDirectory.dir("generated/sources/appmeta")
    val ver = version.toString()
    inputs.property("version", ver)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("dev/cxclear/AppVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.cxclear\n\ninternal object AppVersion {\n    const val VALUE = \"$ver\"\n}\n",
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.getByName("main").kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/appmeta"),
    )
}

tasks.named("compileKotlin") {
    dependsOn(generateAppVersion)
}
