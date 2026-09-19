@echo off
setlocal
rem 走 java.exe 才能把 stdout 交给 AI；CX Clear.exe 是 GUI 子系统，管道经常是空的。
set "APP_HOME=%~dp0"
set "JAVA_EXE=%APP_HOME%runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
  echo 找不到捆绑 JRE：%JAVA_EXE% 1>&2
  exit /b 1
)
set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.library.path=%APP_HOME%app"
if "%~1"=="" (
  "%JAVA_EXE%" %JAVA_OPTS% -cp "%APP_HOME%app\*" dev.cxclear.MainKt help
) else (
  "%JAVA_EXE%" %JAVA_OPTS% -cp "%APP_HOME%app\*" dev.cxclear.MainKt %*
)
exit /b %ERRORLEVEL%
