# Third-party components

The CLI includes Kotlin Standard Library and kotlinx.coroutines (Apache-2.0),
JetBrains annotations (Apache-2.0), sqlite-jdbc (Apache-2.0 / BSD-2-Clause),
and zstd-jni (BSD-2-Clause). Notices bundled in the JARs under `native/lib` stay
with those JARs.

- https://github.com/JetBrains/kotlin
- https://github.com/Kotlin/kotlinx.coroutines
- https://github.com/JetBrains/java-annotations
- https://github.com/xerial/sqlite-jdbc
- https://github.com/luben/zstd-jni

Java is not included. If no compatible Java is found, the installer downloads
Eclipse Temurin 21. Its license stays with the downloaded runtime:
https://github.com/adoptium/temurin21-binaries

CX Clear is GPL-3.0-only. Source: https://github.com/moumoum0/cx-clear
