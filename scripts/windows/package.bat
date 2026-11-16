@echo off
REM ---------------------------------------------------------------------------
REM Build and package the Windows distribution.
REM
REM   scripts\windows\package.bat            -> build the web UI, test and build the jar, zip
REM   scripts\windows\package.bat skipWeb    -> keep the web build already in webui\dist
REM
REM Output, under dist\ in the repository or under the folder named by JMB_DIST:
REM
REM   JMusicBot-<version>\                  the package folder, assembled from scratch
REM   JMusicBot-<version>-windows-x64.zip   the same folder, zipped
REM
REM The version comes from the jar that Maven built (pom.xml). The third-party
REM parts (runtime\, javafx\, cipher\, ytdlp\, licence notices) are copied from
REM dist\bundle, which scripts\windows\bundle.ps1 creates; they are not rebuilt
REM here. The launchers, README.txt and config.txt come from
REM scripts\windows\package\, so a package never carries an edited config or a
REM bot token.
REM
REM Set JMB_JAR to the path of an already built JMusicBot-<version>-All.jar to
REM package that jar instead of building one (the release workflow does this, so
REM the zip holds the very jar that is published next to it).
REM ---------------------------------------------------------------------------
setlocal EnableExtensions
set "HERE=%~dp0"
for %%i in ("%HERE%..\..") do set "ROOT=%%~fi"
if "%JMB_DIST%"=="" set "JMB_DIST=%ROOT%\dist"

set "BUNDLE=%JMB_DIST%\bundle"
set "FILES=%HERE%package"

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set. Point it at a JDK 25+ install.
  exit /b 1
)
for %%d in (runtime javafx cipher ytdlp licenses) do (
  if not exist "%BUNDLE%\%%d\" (
    echo [ERROR] %BUNDLE%\%%d is missing. Create the bundle first:
    echo         powershell -ExecutionPolicy Bypass -File scripts\windows\bundle.ps1 -Output "%BUNDLE%"
    exit /b 1
  )
)
findstr /r /c:"^ *token *= *\"BOT_TOKEN_HERE\"" "%FILES%\config.txt" >nul
if errorlevel 1 (
  echo [ERROR] scripts\windows\package\config.txt must keep token = "BOT_TOKEN_HERE".
  exit /b 1
)

if not "%JMB_JAR%"=="" goto :givenjar

where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] mvn is not on PATH.
  exit /b 1
)

REM The web UI is built first: Maven puts webui\dist into the jar.
if /i "%~1"=="skipWeb" goto :keepweb
where pnpm >nul 2>nul
if errorlevel 1 goto :nopnpm
echo [1/4] Building the web UI...
pushd "%ROOT%\webui"
call pnpm install --frozen-lockfile
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :webdone
call pnpm build
set "RC=%ERRORLEVEL%"
:webdone
popd
if "%RC%"=="0" goto :build
echo [ERROR] The web UI build failed; nothing was packaged.
exit /b %RC%

:nopnpm
echo [ERROR] pnpm is not on PATH. Install it, or pass skipWeb to keep the existing web build.
exit /b 1

:keepweb
echo [1/4] Keeping the existing web build.
if exist "%ROOT%\webui\dist\index.html" goto :build
echo [ERROR] %ROOT%\webui\dist\index.html is missing; run without skipWeb.
exit /b 1

:build
echo [2/4] Testing and building the bot...
pushd "%ROOT%"
call mvn -B clean package
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%
set "JAR="
for %%f in ("%ROOT%\target\JMusicBot-*-All.jar") do set "JAR=%%~ff"
goto :havejar

:givenjar
echo [1/4] Using %JMB_JAR%
echo [2/4] Not building: JMB_JAR is set.
set "JAR=%JMB_JAR%"

:havejar
if not defined JAR goto :nojar
if not exist "%JAR%" goto :nojar
for %%f in ("%JAR%") do set "NAME=%%~nf"
set "NAME=%NAME:-All=%"
set "DIST=%JMB_DIST%\%NAME%"
set "ZIP=%JMB_DIST%\%NAME%-windows-x64.zip"

REM A jar without the web UI opens the old Swing window instead; do not ship that.
"%JAVA_HOME%\bin\jar.exe" tf "%JAR%" | findstr /x /c:"web/index.html" >nul
if errorlevel 1 (
  echo [ERROR] %JAR% does not contain the web UI. Build webui first, then the jar.
  exit /b 1
)

echo [3/4] Assembling %DIST%...
if exist "%DIST%" rmdir /s /q "%DIST%"
if exist "%DIST%" (
  echo [ERROR] %DIST% could not be removed. Is the bot still running from it?
  exit /b 1
)
robocopy "%BUNDLE%" "%DIST%" /E /XD .bundle-tmp /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo [ERROR] Copying %BUNDLE% failed.
  exit /b 1
)
copy /y "%JAR%" "%DIST%\" >nul
if errorlevel 1 exit /b 1
for %%f in (run.bat run-gui.bat README.txt config.txt) do (
  copy /y "%FILES%\%%f" "%DIST%\%%f" >nul
  if errorlevel 1 exit /b 1
)
REM config.defaults is what the GUI's Reset buttons go back to.
copy /y "%FILES%\config.txt" "%DIST%\config.defaults" >nul
if errorlevel 1 exit /b 1
copy /y "%ROOT%\LICENSE" "%DIST%\LICENSE.txt" >nul
if errorlevel 1 exit /b 1

echo [4/4] Creating the zip...
if exist "%ZIP%" del /q "%ZIP%"
REM The paths go through the environment, so a ' in the folder name cannot break the command.
set "JMB_ZIP_SRC=%DIST%"
set "JMB_ZIP_DST=%ZIP%"
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; try { Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::CreateFromDirectory($env:JMB_ZIP_SRC, $env:JMB_ZIP_DST, [IO.Compression.CompressionLevel]::Optimal, $false) } catch { Write-Error $_; exit 1 }"
if errorlevel 1 exit /b 1

echo [OK] %ZIP%
endlocal
exit /b 0

:nojar
echo [ERROR] No JMusicBot-*-All.jar was found.
exit /b 1
