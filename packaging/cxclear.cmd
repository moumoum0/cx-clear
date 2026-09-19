@echo off
setlocal
rem 走 java.exe 才能把 stdout 交给 AI；CX Clear.exe 是 GUI 子系统，管道经常是空的。
set "APP_HOME=%~dp0"
set "APP_DIR=%APP_HOME%app"
if exist "%APP_HOME%runtime\bin\java.exe" (
  set "JAVA_EXE=%APP_HOME%runtime\bin\java.exe"
) else if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)
set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
if "%~1"=="" (
  "%JAVA_EXE%" %JAVA_OPTS% "-Dskiko.library.path=%APP_DIR%" "-Djava.library.path=%APP_DIR%" -cp "%APP_DIR%\*" dev.cxclear.MainKt help
) else (
  "%JAVA_EXE%" %JAVA_OPTS% "-Dskiko.library.path=%APP_DIR%" "-Djava.library.path=%APP_DIR%" -cp "%APP_DIR%\*" dev.cxclear.MainKt %*
)
exit /b %ERRORLEVEL%
