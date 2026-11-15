@echo off
REM JMusicBot launcher (web GUI in a JavaFX window; gui.mode = "swing" uses the Swing window).
REM
REM Starts the GUI with javaw (no console window) and returns immediately, so the
REM console closes. Closing the GUI calls bot.shutdown(), which stops the cipher
REM service and any running yt-dlp download before the JVM exits.
setlocal EnableExtensions
cd /d "%~dp0"

REM The bundled cipher service needs the bundled deno on PATH.
set "PATH=%~dp0cipher;%PATH%"

set "JAVA_EXE=%~dp0runtime\bin\javaw.exe"
if not exist "%JAVA_EXE%" (
  if "%JAVA_HOME%"=="" goto :nojava
  set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
)
if not exist "%JAVA_EXE%" goto :nojava

set "JAR="
for %%f in (JMusicBot-*-All.jar) do set "JAR=%%f"
if not defined JAR goto :nojar

start "" "%JAVA_EXE%" ^
  "--module-path" "%~dp0javafx" ^
  "--add-modules" "javafx.controls,javafx.web" ^
  "-Dfile.encoding=UTF-8" ^
  "--enable-native-access=ALL-UNNAMED,javafx.graphics" ^
  "-XX:+UseZGC" ^
  "-XX:+AlwaysPreTouch" ^
  -Xms256m -Xmx1024m ^
  -jar "%JAR%" %*
exit /b 0

:nojava
mshta "javascript:alert('JMusicBot: no Java runtime found. Re-extract the package or set JAVA_HOME.');close()"
exit /b 1

:nojar
mshta "javascript:alert('JMusicBot: JMusicBot-*-All.jar not found next to run-gui.bat.');close()"
exit /b 1
