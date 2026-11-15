@echo off
REM JMusicBot launcher (headless). Uses the bundled runtime, or JAVA_HOME if it is absent.
setlocal EnableExtensions
cd /d "%~dp0"

REM The bundled cipher service needs the bundled deno on PATH.
set "PATH=%~dp0cipher;%PATH%"

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if exist "%JAVA_EXE%" goto :havejava
if "%JAVA_HOME%"=="" (
  echo [ERROR] The bundled runtime is missing and JAVA_HOME is not set.
  echo         Re-extract the package, or install JDK 25 and set JAVA_HOME.
  goto :fail
)
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

:havejava
set "JAR="
for %%f in (JMusicBot-*-All.jar) do set "JAR=%%f"
if not defined JAR (
  echo [ERROR] JMusicBot-*-All.jar not found next to this script.
  goto :fail
)
echo [INFO] Using %JAVA_EXE%
echo [INFO] Jar   %JAR%
"%JAVA_EXE%" ^
  "-Dfile.encoding=UTF-8" ^
  "-Dnogui=true" ^
  "--enable-native-access=ALL-UNNAMED" ^
  "-XX:+UseZGC" ^
  "-XX:+AlwaysPreTouch" ^
  -Xms256m -Xmx1024m ^
  -jar "%JAR%" %*
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" exit /b 0
echo.
echo [ERROR] JMusicBot exited with code %RC%.
echo         See logs\jmusicbot.log for details.

:fail
echo.
REM Wait for a key press, but only when the input is a console. With redirected
REM input the read returns straight away, so automated runs do not hang.
set "_WAIT="
set /p "_WAIT=Press Enter to close this window..."
exit /b 1
