# ---------------------------------------------------------------------------
# Sets up a local yt-dlp checkout for the bot to use.
#
#   scripts\windows\setup-ytdlp.ps1
#
# Run it from the folder the bot is started in: the checkout goes to
# ytdlp\yt-dlp there. Pass -Directory to put it elsewhere.
#
# Requires Python 3.9+ and git. ffmpeg has to be on the PATH for the audio
# conversion; see https://ffmpeg.org/download.html
# ---------------------------------------------------------------------------
param(
    [string]$Directory = (Join-Path (Get-Location) "ytdlp\yt-dlp"),
    [string]$Url = "https://github.com/yt-dlp/yt-dlp.git"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Cloning yt-dlp into $Directory"
if (Test-Path (Join-Path $Directory ".git")) {
    Write-Host "    already present, updating"
    git -C $Directory pull --ff-only
} else {
    git clone $Url $Directory
}

Write-Host ""
Write-Host "==> Checking the installation"
Push-Location $Directory
try {
    python -m yt_dlp --version
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Checking ffmpeg"
$ffmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($ffmpeg) {
    Write-Host "    $((& ffmpeg -version | Select-Object -First 1))"
} else {
    Write-Warning "ffmpeg was not found on the PATH. It is required to convert downloads to FLAC."
    Write-Warning "Install it with: winget install Gyan.FFmpeg"
}

Write-Host ""
Write-Host "Done. Make sure config.txt has:"
Write-Host '  library.enabled = true'
Write-Host '  library.ytdlpDirectory = "ytdlp/yt-dlp"'
