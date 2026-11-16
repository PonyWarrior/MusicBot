# Applies a downloaded JMusicBot jar. Started hidden by UpdateManager, which then shuts the bot down.
#
# Environment (set by UpdateManager):
#   JMB_PID         process id of the bot that is shutting down
#   JMB_APP_DIR     folder that holds JMusicBot-*-All.jar and the launchers
#   JMB_NEW_JAR     the verified new jar, inside JMB_UPDATE_DIR
#   JMB_LAUNCHER    run-gui.bat or run.bat
#   JMB_UPDATE_DIR  the update folder (markers: pending, started, ok, result.txt)
#
# The launchers start the only JMusicBot-*-All.jar beside them, so the old jar is moved into
# update\backup instead of being left next to the new one. If the new bot does not write the
# "ok" marker within the timeout, it is stopped and the old jar is restored and started again.
# Never name a variable $pid here: it is PowerShell's read-only automatic variable.

$ErrorActionPreference = 'Stop'
$readyTimeoutSeconds = 180

$botPid = [int]$env:JMB_PID
$appDir = $env:JMB_APP_DIR
$newJar = $env:JMB_NEW_JAR
$launcher = $env:JMB_LAUNCHER
$updateDir = $env:JMB_UPDATE_DIR
$backupDir = Join-Path $updateDir 'backup'
$logFile = Join-Path $updateDir 'apply-update.log'
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Write-Log([string]$message)
{
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $message
    # UTF-8 without a byte-order mark: -Encoding UTF8 in Windows PowerShell writes one, and the
    # bot then logs it as text.
    [System.IO.File]::AppendAllText($logFile, $line + "`r`n", $utf8NoBom)
}

function Write-Result([string]$message)
{
    [System.IO.File]::WriteAllText((Join-Path $updateDir 'result.txt'), $message, $utf8NoBom)
    Write-Log $message
}

function Remove-Markers
{
    foreach ($name in 'pending', 'started', 'ok')
    {
        Remove-Item -LiteralPath (Join-Path $updateDir $name) -Force -ErrorAction SilentlyContinue
    }
}

function Wait-ProcessExit([int]$processId, [int]$seconds)
{
    # Polls instead of Wait-Process: Wait-Process can fail at once on a process it did not start
    # (seen live: it returned after one second with the bot still alive).
    $deadline = (Get-Date).AddSeconds($seconds)
    while (Get-Process -Id $processId -ErrorAction SilentlyContinue)
    {
        if ((Get-Date) -ge $deadline) { return $false }
        Start-Sleep -Milliseconds 500
    }
    return $true
}

function Wait-BotExit([int]$seconds)
{
    return (Wait-ProcessExit $botPid $seconds)
}

function Start-Bot
{
    # Never start a second instance while the old one still holds the single-instance lock: it
    # would stop at the "already running" dialog and never exit.
    if (-not (Wait-BotExit 120))
    {
        Write-Log "not restarting: the old bot (process $botPid) is still running"
        return
    }
    Start-Process -FilePath $launcher -WorkingDirectory $appDir -WindowStyle Hidden | Out-Null
}

function Get-AppJars
{
    @(Get-ChildItem -LiteralPath $appDir -Filter 'JMusicBot-*-All.jar' -File -ErrorAction SilentlyContinue)
}

try
{
    Write-Log "Applying $newJar"
    if (-not (Test-Path -LiteralPath $newJar -PathType Leaf)) { throw "the new jar is missing: $newJar" }

    # 1. Wait for the old bot to exit, so its jar is no longer locked.
    if (-not (Wait-BotExit 120))
    {
        throw "the bot (process $botPid) did not exit within 120 seconds"
    }

    # 2. Move every current jar out of the launcher's glob.
    if (Test-Path -LiteralPath $backupDir) { Remove-Item -LiteralPath $backupDir -Recurse -Force }
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    foreach ($jar in Get-AppJars)
    {
        Move-Item -LiteralPath $jar.FullName -Destination $backupDir -Force
    }

    # 3. Put the new jar in place.
    try
    {
        Copy-Item -LiteralPath $newJar -Destination $appDir -Force
    }
    catch
    {
        Get-ChildItem -LiteralPath $backupDir -Filter '*.jar' | Move-Item -Destination $appDir -Force
        throw
    }

    # 4. Start it and wait for the ready marker.
    Start-Bot
    $okFile = Join-Path $updateDir 'ok'
    $deadline = (Get-Date).AddSeconds($readyTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $okFile))
    {
        Start-Sleep -Seconds 2
    }

    $version = (Get-Content -LiteralPath (Join-Path $updateDir 'pending') -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (Test-Path -LiteralPath $okFile)
    {
        Remove-Item -LiteralPath $newJar -Force -ErrorAction SilentlyContinue
        Remove-Markers
        Write-Result "updated to $version"
        exit 0
    }

    # 5. Roll back: stop the new bot (only the process it reported), restore the old jar.
    Write-Log "The new bot did not report ready within $readyTimeoutSeconds seconds, rolling back"
    $startedFile = Join-Path $updateDir 'started'
    if (Test-Path -LiteralPath $startedFile)
    {
        $newPid = [int](Get-Content -LiteralPath $startedFile | Select-Object -First 1)
        if (Get-Process -Id $newPid -ErrorAction SilentlyContinue)
        {
            Stop-Process -Id $newPid -Force -ErrorAction SilentlyContinue
            [void](Wait-ProcessExit $newPid 30)
        }
    }
    # A jar that is still locked must not abort the rollback: restoring the old jar matters more.
    foreach ($jar in Get-AppJars)
    {
        Remove-Item -LiteralPath $jar.FullName -Force -ErrorAction SilentlyContinue
    }
    Get-ChildItem -LiteralPath $backupDir -Filter '*.jar' | Move-Item -Destination $appDir -Force
    Remove-Markers
    Write-Result "update to $version failed (no ready signal), rolled back"
    Start-Bot
    exit 1
}
catch
{
    Remove-Markers
    Write-Result "update failed: $($_.Exception.Message)"
    try
    {
        # Never leave the folder without a jar: bring the backup back if the swap got that far.
        if ((Get-AppJars).Count -eq 0 -and (Test-Path -LiteralPath $backupDir))
        {
            Get-ChildItem -LiteralPath $backupDir -Filter '*.jar' | Move-Item -Destination $appDir -Force
        }
        if ((Get-AppJars).Count -gt 0) { Start-Bot }
    }
    catch
    {
        Write-Log "recovery failed: $($_.Exception.Message)"
    }
    exit 1
}
