import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Only distribution copies are rewritten; dependency caches stay platform-neutral.
extra["trimWindowsNativeJars"] = { directory: File ->
    val libraries = mapOf(
        "sqlite-jdbc" to "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll",
        "zstd-jni" to "win/amd64/libzstd-jni-",
        "jna" to "com/sun/jna/win32-x86-64/jnidispatch.dll",
    )
    for ((library, nativePath) in libraries) {
        val jar = directory.listFiles().orEmpty().single {
            it.name.startsWith("$library-") && it.extension == "jar"
        }
        val temporary = File(directory, "${jar.name}.tmp")
        val originalSize = jar.length()
        try {
            ZipFile(jar).use { source ->
                val entries = source.entries().asSequence().toList()
                fun isNative(name: String) =
                    listOf(".dll", ".so", ".dylib", ".jnilib").any { name.endsWith(it) }
                fun keepNative(name: String) =
                    if (library == "zstd-jni") name.startsWith(nativePath) && name.endsWith(".dll")
                    else name == nativePath
                check(entries.count { isNative(it.name) && keepNative(it.name) } == 1) {
                    "Missing or ambiguous Windows x64 native library in ${jar.name}"
                }
                check(entries.none {
                    it.name.startsWith("META-INF/") &&
                        listOf(".SF", ".RSA", ".DSA", ".EC").any { suffix -> it.name.endsWith(suffix) }
                }) { "Cannot trim signed JAR: ${jar.name}" }
                ZipOutputStream(temporary.outputStream().buffered()).use { target ->
                    for (entry in entries) {
                        if (isNative(entry.name) && !keepNative(entry.name)) continue
                        target.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                }
            }
            Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("Windows x64 ${jar.name}: $originalSize -> ${jar.length()} bytes")
        } finally {
            temporary.delete()
        }
    }
}
