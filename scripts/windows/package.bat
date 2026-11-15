@echo off
REM ---------------------------------------------------------------------------
REM Build and package the Windows distribution.
REM
REM   scripts\windows\package.bat            -> test and build the jar, build the web UI, zip
REM   scripts\windows\package.bat skipWeb    -> keep the web build already in the package
REM
REM The package folder is dist\JMusicBot-0.7.0 in the repository, or under the
REM folder named by the JMB_DIST environment variable. It has to contain the
REM bundled binaries already (runtime\, javafx\, cipher\, ytdlp\); they are not
REM rebuilt here and the script stops when one of them is missing. See the
REM "Windows package" section of the README for what goes in each folder.
REM
REM The launchers, README.txt and config.txt come from scripts\windows\package\
REM every time, so the package never carries an edited config (or a bot token).
REM Runtime files (logs, crash dumps, locks, theme choice, playlists, Music) are
REM removed from the package before zipping.
REM ---------------------------------------------------------------------------
setlocal EnableExtensions
set "HERE=%~dp0"
for %%i in ("%HERE%..\..") do set "ROOT=%%~fi"
if "%JMB_DIST%"=="" set "JMB_DIST=%ROOT%\dist"

set "DIST=%JMB_DIST%\JMusicBot-0.7.0"
set "ZIP=%JMB_DIST%\JMusicBot-0.7.0.zip"
set "FILES=%HERE%package"

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set. Point it at a JDK 25+ install.
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] mvn is not on PATH.
  exit /b 1
)
for %%d in (runtime javafx cipher ytdlp) do (
  if not exist "%DIST%\%%d\" (
    echo [ERROR] %DIST%\%%d is missing. The bundled binaries are not rebuilt by this script.
    exit /b 1
  )
)
findstr /r /c:"^ *token *= *\"BOT_TOKEN_HERE\"" "%FILES%\config.txt" >nul
if errorlevel 1 (
  echo [ERROR] scripts\windows\package\config.txt must keep token = "BOT_TOKEN_HERE".
  exit /b 1
)

echo [1/4] Testing and building the bot...
pushd "%ROOT%"
call mvn -B clean package
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%

if /i "%~1"=="skipWeb" goto :keepweb
where pnpm >nul 2>nul
if errorlevel 1 goto :nopnpm
echo [2/4] Building the web UI...
pushd "%ROOT%\webui"
call pnpm install --frozen-lockfile
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :webdone
call pnpm build
set "RC=%ERRORLEVEL%"
:webdone
popd
if not "%RC%"=="0" (
  echo [ERROR] The web UI build failed; nothing was packaged.
  exit /b %RC%
)
if exist "%DIST%\web" rmdir /s /q "%DIST%\web"
xcopy /e /i /y "%ROOT%\webui\dist" "%DIST%\web" >nul
if errorlevel 1 exit /b 1
goto :copy

:nopnpm
echo [ERROR] pnpm is not on PATH. Install it, or pass skipWeb to keep the existing web build.
exit /b 1

:keepweb
echo [2/4] Keeping the existing web build.
if not exist "%DIST%\web\index.html" (
  echo [ERROR] %DIST%\web\index.html is missing; run without skipWeb.
  exit /b 1
)

:copy
echo [3/4] Copying the jar, launchers and config; cleaning runtime files...
copy /y "%ROOT%\target\JMusicBot-0.7.0-All.jar" "%DIST%\JMusicBot-0.7.0-All.jar" >nul
if errorlevel 1 exit /b 1
for %%f in (run.bat run-gui.bat README.txt config.txt) do (
  copy /y "%FILES%\%%f" "%DIST%\%%f" >nul
  if errorlevel 1 exit /b 1
)
REM config.defaults is what the GUI's Reset buttons go back to.
copy /y "%FILES%\config.txt" "%DIST%\config.defaults" >nul
if errorlevel 1 exit /b 1
if exist "%DIST%\logs" rmdir /s /q "%DIST%\logs"
if exist "%DIST%\Playlists" rmdir /s /q "%DIST%\Playlists"
if exist "%DIST%\Music" rmdir /s /q "%DIST%\Music"
del /q "%DIST%\.jmusicbot.lock" 2>nul
del /q "%DIST%\.slashcommands.hash" 2>nul
del /q "%DIST%\gui-theme.txt" 2>nul
del /q "%DIST%\serversettings.json" 2>nul
del /q "%DIST%\config.txt.bak*" 2>nul
del /q "%DIST%\hs_err_pid*.log" 2>nul
del /q "%DIST%\replay_pid*.log" 2>nul

echo [4/4] Creating the zip...
if exist "%ZIP%" del /q "%ZIP%"
powershell -NoProfile -Command "Compress-Archive -Path '%DIST%\*' -DestinationPath '%ZIP%' -CompressionLevel Optimal"
if errorlevel 1 exit /b 1

echo [OK] %ZIP%
endlocal
exit /b 0
