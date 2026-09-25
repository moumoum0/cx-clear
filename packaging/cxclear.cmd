@echo off
setlocal
rem 命令行参数交给对应的桌面启动器处理。
rem 与桌面启动器使用同一套 Java 查找逻辑；带 Java 包由 jpackage 启动器使用内置 runtime，
rem 不含 Java 包由自定义启动器回退到 JAVA_HOME / PATH。
"%~dp0CX Clear.exe" %*
exit /b %ERRORLEVEL%
