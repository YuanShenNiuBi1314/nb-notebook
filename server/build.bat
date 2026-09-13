@echo off
rem 牛逼笔记本 · 编译脚本（纯 JDK，无需 Maven/Gradle）
setlocal
cd /d "%~dp0"
set JAVA_HOME=..\tools\jdk-17.0.20.1+1
if not exist "%JAVA_HOME%\bin\javac.exe" (
  rem 尝试自动定位解压后的 JDK
  for /d %%d in (..\tools\jdk*) do set JAVA_HOME=%%d
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo [错误] 未找到 JDK，请把 JDK17 解压到 tools\ 目录
  exit /b 1
)
echo 使用 JDK: %JAVA_HOME%
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d out src\com\nbnotebook\*.java
if %errorlevel% neq 0 ( echo 编译失败 & exit /b 1 )
echo 编译成功
