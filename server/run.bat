@echo off
rem 牛逼笔记本 · 启动脚本
setlocal
cd /d "%~dp0"
set JAVA_HOME=..\tools\jdk-17.0.20.1+1
if not exist "%JAVA_HOME%\bin\java.exe" (
  for /d %%d in (..\tools\jdk*) do set JAVA_HOME=%%d
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [错误] 未找到 JDK，请把 JDK17 解压到 tools\ 目录
  pause & exit /b 1
)
if not exist out\com\nbnotebook\Main.class (
  call build.bat
  if errorlevel 1 pause & exit /b 1
)
set PORT=%1
if "%PORT%"=="" set PORT=8080
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dnb.base="%~dp0.." -Dnb.port=%PORT% -cp out com.nbnotebook.Main %*
pause
