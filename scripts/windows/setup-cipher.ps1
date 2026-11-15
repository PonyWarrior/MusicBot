# ---------------------------------------------------------------------------
# Sets up a local yt-cipher checkout for the bot to use.
#
#   scripts\windows\setup-cipher.ps1
#
# Run it from the folder the bot is started in: the checkout goes to
# cipher\yt-cipher there, which is the default of
# playback.youtube.localCipher.directory. Pass -Directory to put it elsewhere.
#
# Requires Deno (https://deno.com) and git. The bot starts the service itself,
# so nothing needs to be running afterwards.
# ---------------------------------------------------------------------------
param(
    [string]$Directory = (Join-Path (Get-Location) "cipher\yt-cipher"),
    [string]$Deno = "deno",
    [string]$EjsCommit = "cd4e87f52e87ab6d8b318fd3a817adda6fafa8dc"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Cloning yt-cipher into $Directory"
if (Test-Path $Directory) {
    Write-Host "    already present, updating"
    git -C $Directory pull --ff-only
} else {
    git clone https://github.com/kikkia/yt-cipher.git $Directory
}

Write-Host "==> Cloning the pinned ejs commit"
$ejsDir = Join-Path $Directory "ejs"
if (Test-Path (Join-Path $ejsDir ".git")) {
    git -C $ejsDir fetch --depth 1 origin $EjsCommit
} else {
    git clone https://github.com/yt-dlp/ejs.git $ejsDir
}
git -C $ejsDir checkout --detach $EjsCommit

Write-Host "==> Patching ejs for Deno's npm compatibility"
Push-Location $Directory
try {
    & $Deno run --allow-read --allow-write ./scripts/patch-ejs.ts
} finally {
    Pop-Location
}

# Without this the service outlives a bot that was force-killed: the patch makes it exit when
# the bot's end of its stdin pipe closes.
Write-Host "==> Adding the parent watchdog to server.ts"
$patch = Join-Path $PSScriptRoot "yt-cipher-watchdog.patch"
$ErrorActionPreference = "Continue"
git -C $Directory apply --ignore-whitespace --reverse --check $patch 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "    already applied"
} else {
    git -C $Directory apply --ignore-whitespace $patch
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "The watchdog patch did not apply (yt-cipher changed server.ts)."
        Write-Warning "The service still works; it is only not stopped when the bot is force-killed."
    }
}

Write-Host ""
Write-Host "Done. The bot will start the service from $Directory"
Write-Host "Make sure config.txt has:"
Write-Host '  playback.youtube.remoteCipher = "http://127.0.0.1:8001/"'
Write-Host "  playback.youtube.localCipher.enabled = true"
