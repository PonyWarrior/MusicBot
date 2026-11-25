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
    # The reviewed yt-cipher commit. Keep it equal to UpdateManager.PINNED_CIPHER_COMMIT: the
    # in-app updater installs exactly this commit, never upstream HEAD.
    [string]$CipherCommit = "d6b70bace398632cbc886934ff8273a7524fc0c4",
    [string]$EjsCommit = "d48c6303a5a6455a42ef9e0b445149cadea51279"
)

$ErrorActionPreference = "Stop"

# Both checkouts must keep LF endings: patch-ejs.ts matches multi-line LF blocks in ejs, and
# Git for Windows defaults to core.autocrlf=true, which turns them into CRLF.
Write-Host "==> Cloning yt-cipher $CipherCommit into $Directory"
if (Test-Path (Join-Path $Directory ".git")) {
    Write-Host "    already present, fetching the pinned commit"
    git -C $Directory config core.autocrlf false
    git -C $Directory fetch origin $CipherCommit
    if ($LASTEXITCODE -ne 0) { throw "git fetch of yt-cipher $CipherCommit failed." }
} else {
    git clone -c core.autocrlf=false https://github.com/kikkia/yt-cipher.git $Directory
    if ($LASTEXITCODE -ne 0) { throw "git clone of yt-cipher failed." }
}
git -C $Directory checkout --detach $CipherCommit
if ($LASTEXITCODE -ne 0) { throw "git checkout of yt-cipher $CipherCommit failed." }

Write-Host "==> Cloning the pinned ejs commit"
$ejsDir = Join-Path $Directory "ejs"
if (Test-Path (Join-Path $ejsDir ".git")) {
    git -C $ejsDir config core.autocrlf false
    git -C $ejsDir fetch --depth 1 origin $EjsCommit
    if ($LASTEXITCODE -ne 0) { throw "git fetch of ejs $EjsCommit failed." }
} else {
    git clone -c core.autocrlf=false https://github.com/yt-dlp/ejs.git $ejsDir
    if ($LASTEXITCODE -ne 0) { throw "git clone of ejs failed." }
}
git -C $ejsDir checkout --detach $EjsCommit
if ($LASTEXITCODE -ne 0) { throw "git checkout of ejs $EjsCommit failed." }

Write-Host "==> Patching ejs for Deno's npm compatibility"
Push-Location $Directory
try {
    & $Deno run --allow-read --allow-write ./scripts/patch-ejs.ts
    if ($LASTEXITCODE -ne 0) { throw "patch-ejs.ts failed (exit code $LASTEXITCODE)." }
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
