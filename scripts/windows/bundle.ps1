# ---------------------------------------------------------------------------
# Rebuilds the third-party binaries that the Windows package bundles.
#
#   scripts\windows\bundle.ps1 [-Output <dir>] [-DenoVersion <v>] [-YtDlpVersion <v|latest>]
#                              [-FfmpegVersion <v>] [-Force] [-Verify]
#
# Output (default: dist\bundle in the repository):
#   runtime\                  jlink image of the JDK at JAVA_HOME
#   javafx\                   the five javafx-*-win.jar files (version from the pom)
#   cipher\deno.exe           Deno
#   cipher\yt-cipher\         yt-cipher + yt-dlp/ejs, with its Deno modules in deno-cache\
#   ytdlp\yt-dlp.exe          yt-dlp
#   ytdlp\ffmpeg.exe          FFmpeg (gyan.dev "essentials" build)
#   licenses\                 licence texts and build information
#   THIRD-PARTY-NOTICES.txt
#
# A part that is already there is skipped; -Force rebuilds all four. Each part
# is built under <Output>\.bundle-tmp and renamed into place when complete.
# -Verify starts the bundled yt-cipher offline on 127.0.0.1:8765 and requests it.
#
# Requires JAVA_HOME pointing at a JDK 25+, and mvn and git on the PATH.
# Runs under Windows PowerShell 5.1 and PowerShell 7. Keep this file ASCII.
# ---------------------------------------------------------------------------
param(
    [string]$Output = "",
    [string]$DenoVersion = "2.9.7",
    [string]$YtDlpVersion = "latest",
    [string]$FfmpegVersion = "9.0.2",
    [switch]$Force,
    [switch]$Verify
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$JlinkModules = "java.base,java.compiler,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging," +
    "java.management,java.security.sasl,java.naming,java.net.http,java.scripting,java.security.jgss," +
    "java.transaction.xa,java.sql,jdk.crypto.ec,jdk.httpserver,jdk.jsobject,jdk.localedata,jdk.management," +
    "jdk.unsupported,jdk.xml.dom,jdk.zipfs"
$JavaFxModules = @("base", "controls", "graphics", "media", "web")
$VerifyPort = 8765

$started = Get-Date
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $Output) { $Output = Join-Path $repoRoot "dist\bundle" }
$Output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$stage = Join-Path $Output ".bundle-tmp"
$licenses = Join-Path $Output "licenses"
$status = @{}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step([string]$Text) { Write-Host "==> $Text" }

# Runs a native command and throws when it fails. stderr is left alone on purpose: under
# Windows PowerShell 5.1 a redirected stderr line becomes an error record, which
# $ErrorActionPreference = "Stop" would turn into a failure (Maven and git write there).
function Invoke-Native {
    param([string]$What, [string]$Exe, [string[]]$Arguments, [switch]$Capture)
    $ErrorActionPreference = "Continue"
    if ($Capture) { $out = & $Exe @Arguments } else { & $Exe @Arguments | Out-Host }
    $code = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($code -ne 0) { throw "$What failed (exit code $code): $Exe $($Arguments -join ' ')" }
    if ($Capture) { return $out }
}

function Get-Download([string]$Url, [string]$Destination) {
    for ($attempt = 1; ; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
            return
        } catch {
            if ($attempt -ge 3) { throw "Download failed: $Url ($($_.Exception.Message))" }
            Write-Host "    download failed, retrying: $($_.Exception.Message)"
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

# Downloads one GitHub release asset and checks it against the SHA-256 digest GitHub records for
# it. $Tag "latest" means the latest release. Returns the release tag.
function Get-ReleaseAsset([string]$Repo, [string]$Tag, [string]$Name, [string]$Destination) {
    $api = if ($Tag -eq "latest") { "https://api.github.com/repos/$Repo/releases/latest" }
           else { "https://api.github.com/repos/$Repo/releases/tags/$Tag" }
    $headers = @{ "Accept" = "application/vnd.github+json"; "User-Agent" = "jmusicbot-bundle" }
    if ($env:GITHUB_TOKEN) { $headers["Authorization"] = "Bearer $env:GITHUB_TOKEN" }
    $release = Invoke-RestMethod -UseBasicParsing -Uri $api -Headers $headers
    $asset = @($release.assets | Where-Object { $_.name -eq $Name })
    if ($asset.Count -ne 1) { throw "$Repo $($release.tag_name) has no asset named $Name." }
    $digest = [string]$asset[0].digest
    if (-not $digest.StartsWith("sha256:")) { throw "$Repo $($release.tag_name): GitHub has no SHA-256 digest for $Name." }
    Get-Download $asset[0].browser_download_url $Destination
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $digest.Substring(7).ToLowerInvariant()) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Checksum mismatch for $Name from $Repo $($release.tag_name): expected $($digest.Substring(7)), got $actual."
    }
    Write-Host "    sha256 ok: $Name"
    return [string]$release.tag_name
}

# Extracts the one zip entry whose path matches the wildcard pattern.
function Expand-ZipEntry([string]$Zip, [string]$Pattern, [string]$Destination) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -like $Pattern })
        if ($entries.Count -ne 1) { throw "Expected one '$Pattern' in $Zip, found $($entries.Count)." }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $Destination, $true)
    } finally {
        $archive.Dispose()
    }
}

function New-CleanDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Recurse -Force }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

function Get-Size([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $sum = (Get-ChildItem -LiteralPath $Path -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum
    if ($sum) { return $sum } else { return 0 }
}

function Format-Size($Bytes) { "{0:N1} MB" -f ($Bytes / 1MB) }

# True when the part has to be built; an existing part is kept unless -Force.
function Test-Build([string]$Part) {
    if ((Test-Path -LiteralPath (Join-Path $Output $Part)) -and -not $Force) {
        Write-Step "$Part\ is already there, skipping (use -Force to rebuild)"
        $status[$Part] = "kept"
        return $false
    }
    $status[$Part] = "built"
    return $true
}

# Renames a finished part from the staging folder into the output folder.
function Install-Part([string]$Part) {
    $target = Join-Path $Output $Part
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    Move-Item -LiteralPath (Join-Path $stage $Part) -Destination $target
}

# Reads "key=value" (optionally quoted) from a text file; "unknown" when it is not there.
function Get-Info([string]$File, [string]$Key) {
    if (Test-Path -LiteralPath $File) {
        foreach ($line in Get-Content -LiteralPath $File) {
            if ($line -match ('^' + [regex]::Escape($Key) + '=(.*)$')) { return $Matches[1].Trim('"') }
        }
    }
    return "unknown"
}

function Get-FirstLine([string]$What, [string]$Exe, [string[]]$Arguments) {
    return [string](@(Invoke-Native $What $Exe $Arguments -Capture)[0])
}

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set. Point it at a JDK 25+ install." }
$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
$jdkRelease = Join-Path $env:JAVA_HOME "release"
if (-not (Test-Path -LiteralPath $jlink)) { throw "No bin\jlink.exe under JAVA_HOME ($env:JAVA_HOME). It has to be a JDK, not a JRE." }
$jdkVersion = Get-Info $jdkRelease "JAVA_VERSION"
if ($jdkVersion -notmatch '^(\d+)' -or [int]$Matches[1] -lt 25) {
    throw "JAVA_HOME ($env:JAVA_HOME) is Java '$jdkVersion'. A JDK 25+ is required."
}
foreach ($tool in "mvn", "git") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is not on the PATH." }
}

Write-Step "Bundling into $Output"
New-CleanDirectory $stage
New-Item -ItemType Directory -Path $licenses -Force | Out-Null

# ---------------------------------------------------------------------------
# runtime: jlink image
# ---------------------------------------------------------------------------
if (Test-Build "runtime") {
    Write-Step "runtime: jlink from JDK $jdkVersion"
    $dir = Join-Path $stage "runtime"
    # zip-6 is what the hand-made package used; without it lib\modules is 87 MB instead of 38 MB.
    Invoke-Native "jlink" $jlink @("--no-header-files", "--no-man-pages", "--strip-debug", "--compress", "zip-6",
        "--add-modules", $JlinkModules, "--output", $dir)
    Invoke-Native "The new runtime" (Join-Path $dir "bin\java.exe") @("--version")
    if (-not (Test-Path -LiteralPath (Join-Path $dir "legal\java.base"))) { throw "jlink produced no legal\ folder." }
    # The image's own release file drops the vendor and source lines; keep the JDK's for the notices.
    Copy-Item -LiteralPath $jdkRelease -Destination (Join-Path $licenses "jdk-release.txt") -Force
    Install-Part "runtime"
}

# ---------------------------------------------------------------------------
# javafx: Windows jars from Maven, licence texts from the matching source tag
# ---------------------------------------------------------------------------
if (Test-Build "javafx") {
    Write-Step "javafx: copying the Windows jars with Maven"
    $dir = Join-Path $stage "javafx"
    Push-Location $repoRoot
    try {
        Invoke-Native "mvn dependency:copy-dependencies" "mvn" @("-q", "-B", "dependency:copy-dependencies",
            "-DincludeGroupIds=org.openjfx", "-DincludeClassifiers=win", "-DexcludeArtifactIds=jdk-jsobject",
            "-DoutputDirectory=$dir")
    } finally {
        Pop-Location
    }
    $jars = @(Get-ChildItem -LiteralPath $dir -File | ForEach-Object { $_.Name } | Sort-Object)
    if ($jars.Count -ne 5 -or $jars[0] -notmatch '^javafx-base-(.+)-win\.jar$') {
        throw "Expected the five javafx-*-win.jar files, got: $($jars -join ', ')"
    }
    $fxVersion = $Matches[1]
    $expected = @($JavaFxModules | ForEach-Object { "javafx-$_-$fxVersion-win.jar" })
    if (Compare-Object $jars $expected) { throw "Expected $($expected -join ', '), got: $($jars -join ', ')" }

    # The jars carry no licence files. They are in the source repository, openjdk/jfx<major>u,
    # at the tag <version>+<build>; the highest build is the released one.
    Write-Step "javafx: fetching the OpenJFX $fxVersion licence texts"
    $fxRepo = "https://github.com/openjdk/jfx$(($fxVersion -split '\.')[0])u"
    $tags = @(Invoke-Native "git ls-remote" "git" @("ls-remote", "--tags", "--refs", "$fxRepo.git", "refs/tags/$fxVersion+*") -Capture)
    $builds = @($tags | Where-Object { $_ -match '\+(\d+)$' } | ForEach-Object { [int]$Matches[1] } | Sort-Object)
    if ($builds.Count -eq 0) { throw "No $fxVersion+<build> tag in $fxRepo." }
    $fxTag = "$fxVersion+$($builds[-1])"
    $src = Join-Path $stage "openjfx-src"
    Invoke-Native "git clone (OpenJFX)" "git" @("clone", "-q", "--depth", "1", "--branch", $fxTag, "--filter=blob:none",
        "--no-checkout", "$fxRepo.git", $src)
    Invoke-Native "git sparse-checkout (OpenJFX)" "git" @("-C", $src, "sparse-checkout", "set", "--no-cone",
        "/LICENSE", "/ADDITIONAL_LICENSE_INFO", "/modules/*/src/main/legal/")
    Invoke-Native "git checkout (OpenJFX)" "git" @("-C", $src, "checkout", "-q")
    $fxLicenses = Join-Path $licenses "openjfx"
    New-CleanDirectory $fxLicenses
    Copy-Item -LiteralPath (Join-Path $src "LICENSE") -Destination (Join-Path $fxLicenses "LICENSE")
    Copy-Item -LiteralPath (Join-Path $src "ADDITIONAL_LICENSE_INFO") -Destination (Join-Path $fxLicenses "ADDITIONAL_LICENSE_INFO")
    foreach ($module in $JavaFxModules) {
        $legal = Join-Path $src "modules\javafx.$module\src\main\legal"
        if (Test-Path -LiteralPath $legal) {
            Get-ChildItem -LiteralPath $legal -File | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $fxLicenses "javafx.$module-$($_.Name)")
            }
        }
    }
    [IO.File]::WriteAllLines((Join-Path $fxLicenses "source.txt"), @("repository=$fxRepo", "tag=$fxTag"))
    Remove-Item -LiteralPath $src -Recurse -Force
    Install-Part "javafx"
}

# ---------------------------------------------------------------------------
# cipher: Deno, yt-cipher with the pinned ejs, and the Deno module cache
# ---------------------------------------------------------------------------
if (Test-Build "cipher") {
    $dir = Join-Path $stage "cipher"
    New-CleanDirectory $dir
    $deno = Join-Path $dir "deno.exe"

    Write-Step "cipher: downloading Deno $DenoVersion"
    $zip = Join-Path $stage "deno.zip"
    $null = Get-ReleaseAsset "denoland/deno" "v$DenoVersion" "deno-x86_64-pc-windows-msvc.zip" $zip
    Expand-ZipEntry $zip "deno.exe" $deno
    Remove-Item -LiteralPath $zip
    Write-Host "    $(Get-FirstLine 'deno --version' $deno @('--version'))"
    Get-Download "https://raw.githubusercontent.com/denoland/deno/v$DenoVersion/LICENSE.md" (Join-Path $licenses "deno-LICENSE.md")

    # setup-cipher.ps1 changes $ErrorActionPreference and does not check git's exit codes, so it
    # runs in its own PowerShell and its result is checked here. DENO_DIR keeps the modules its
    # patch script downloads out of the user's Deno cache and out of the bundle.
    Write-Step "cipher: running setup-cipher.ps1"
    $checkout = Join-Path $stage "yt-cipher-src"
    $shell = Join-Path $PSHOME $(if ($PSVersionTable.PSEdition -eq "Core") { "pwsh.exe" } else { "powershell.exe" })
    $oldDenoDir = $env:DENO_DIR
    $env:DENO_DIR = Join-Path $stage "deno-setup-cache"
    try {
        Invoke-Native "setup-cipher.ps1" $shell @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            (Join-Path $PSScriptRoot "setup-cipher.ps1"), "-Directory", $checkout, "-Deno", $deno)
    } finally {
        $env:DENO_DIR = $oldDenoDir
    }
    $ejs = Join-Path $checkout "ejs"
    foreach ($file in "server.ts", "worker.ts", "src", "docs", "ejs\src", "ejs\LICENSE", "ejs\README.md") {
        if (-not (Test-Path -LiteralPath (Join-Path $checkout $file))) { throw "setup-cipher.ps1 did not produce yt-cipher\$file." }
    }
    if (-not (Select-String -LiteralPath (Join-Path $checkout "server.ts") -Pattern "Deno.stdin.isTerminal" -SimpleMatch -Quiet)) {
        throw "The watchdog patch is not in server.ts (yt-cipher changed it). Update scripts\windows\yt-cipher-watchdog.patch."
    }
    if (-not (Get-ChildItem -LiteralPath (Join-Path $ejs "src") -Recurse -File | Select-String -Pattern "npm:meriyah" -SimpleMatch -List)) {
        throw "ejs was not patched for Deno (no npm:meriyah import under ejs\src)."
    }
    $cipherCommit = Get-FirstLine "git rev-parse" "git" @("-C", $checkout, "rev-parse", "HEAD")
    $cipherDate = Get-FirstLine "git log" "git" @("-C", $checkout, "log", "-1", "--format=%cI")
    $ejsCommit = Get-FirstLine "git rev-parse" "git" @("-C", $ejs, "rev-parse", "HEAD")

    # Only what the service needs at run time: no .git, tests, CI or Docker files.
    Write-Step "cipher: assembling yt-cipher ($($cipherCommit.Substring(0, 7))) with ejs ($($ejsCommit.Substring(0, 7)))"
    $app = Join-Path $dir "yt-cipher"
    New-Item -ItemType Directory -Path (Join-Path $app "ejs") -Force | Out-Null
    foreach ($item in "server.ts", "worker.ts", "src", "docs", "ejs\src", "ejs\LICENSE", "ejs\README.md") {
        Copy-Item -LiteralPath (Join-Path $checkout $item) -Destination (Join-Path $app $item) -Recurse
    }
    # Not upstream files: they make Deno resolve npm: imports from its cache instead of node_modules.
    foreach ($file in "deno.json", "ejs\deno.json") {
        [IO.File]::WriteAllText((Join-Path $app $file), "{ `"nodeModulesDir`": `"none`" }`r`n")
    }

    # worker.ts is named too: it is loaded through new Worker(), which the module graph of
    # server.ts does not include.
    Write-Step "cipher: downloading the Deno modules into yt-cipher\deno-cache"
    $env:DENO_DIR = "deno-cache"
    Push-Location $app
    try {
        Invoke-Native "deno cache" $deno @("cache", "--quiet", "--node-modules-dir=none", "server.ts", "worker.ts")
    } finally {
        Pop-Location
        $env:DENO_DIR = $oldDenoDir
    }
    # Keep the downloaded modules only. The compiled-code caches are keyed by the absolute build
    # path, so Deno rebuilds them on the first start anyway.
    $cache = Join-Path $app "deno-cache"
    Get-ChildItem -LiteralPath $cache -Force | Where-Object { "remote", "npm" -notcontains $_.Name } |
        Remove-Item -Recurse -Force
    foreach ($name in "remote", "npm") {
        if (-not (Test-Path -LiteralPath (Join-Path $cache $name))) { throw "deno cache left no deno-cache\$name." }
    }

    # deno.lock records what was resolved; note it for the notices, then drop it.
    $lockFile = Join-Path $app "deno.lock"
    $lock = Get-Content -LiteralPath $lockFile -Raw | ConvertFrom-Json
    Remove-Item -LiteralPath $lockFile
    $cipherLicense = @(Get-ChildItem -LiteralPath $checkout -File | Where-Object { $_.Name -match '^(LICEN[SC]E|COPYING)' })
    $info = @(
        "yt-cipher.repository=https://github.com/kikkia/yt-cipher",
        "yt-cipher.commit=$cipherCommit",
        "yt-cipher.commitDate=$cipherDate",
        "yt-cipher.licenseFile=$(if ($cipherLicense) { $cipherLicense[0].Name } else { 'none' })",
        "ejs.repository=https://github.com/yt-dlp/ejs",
        "ejs.commit=$ejsCommit"
    )
    if ($lock.npm) {
        foreach ($package in $lock.npm.PSObject.Properties.Name) {
            $at = $package.LastIndexOf("@")
            $name = $package.Substring(0, $at)
            $folder = Join-Path $cache ("npm\registry.npmjs.org\" + $name.Replace("/", "\") + "\" + $package.Substring($at + 1))
            $license = (Get-Content -LiteralPath (Join-Path $folder "package.json") -Raw | ConvertFrom-Json).license
            $info += "module=npm:$package (licence in its package.json: $license)"
            Get-ChildItem -LiteralPath $folder -File | Where-Object { $_.Name -match '^LICEN[SC]E' } | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $licenses "npm-$($name.Replace('/', '-'))-$($_.Name)") -Force
            }
        }
    }
    if ($lock.jsr) { foreach ($package in $lock.jsr.PSObject.Properties.Name) { $info += "module=jsr:$package" } }
    if ($lock.remote) {
        $lock.remote.PSObject.Properties.Name | ForEach-Object { if ($_ -match '^https://[^/]+/(x/)?[^/]+') { $Matches[0] } } |
            Sort-Object -Unique | ForEach-Object { $info += "module=$_" }
    }
    [IO.File]::WriteAllLines((Join-Path $licenses "yt-cipher-build-info.txt"), $info)
    Copy-Item -LiteralPath (Join-Path $ejs "LICENSE") -Destination (Join-Path $licenses "ejs-LICENSE.txt") -Force
    if ($cipherLicense) {
        Copy-Item -LiteralPath $cipherLicense[0].FullName -Destination (Join-Path $licenses "yt-cipher-LICENSE.txt") -Force
    }
    Remove-Item -LiteralPath $checkout -Recurse -Force
    Install-Part "cipher"
}

# ---------------------------------------------------------------------------
# ytdlp: yt-dlp and FFmpeg
# ---------------------------------------------------------------------------
if (Test-Build "ytdlp") {
    $dir = Join-Path $stage "ytdlp"
    New-CleanDirectory $dir

    Write-Step "ytdlp: downloading yt-dlp ($YtDlpVersion)"
    $ytdlp = Join-Path $dir "yt-dlp.exe"
    $null = Get-ReleaseAsset "yt-dlp/yt-dlp" $YtDlpVersion "yt-dlp.exe" $ytdlp
    # The version it reports is also its release tag.
    $ytdlpTag = Get-FirstLine "yt-dlp --version" $ytdlp @("--version")
    Write-Host "    yt-dlp $ytdlpTag"
    Get-Download "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$ytdlpTag/LICENSE" (Join-Path $licenses "yt-dlp-LICENSE.txt")
    # The notices of what the exe bundles; releases before 2025 do not have the file.
    $ytdlpNotices = Join-Path $licenses "yt-dlp-THIRD_PARTY_LICENSES.txt"
    if (Test-Path -LiteralPath $ytdlpNotices) { Remove-Item -LiteralPath $ytdlpNotices }
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$ytdlpTag/THIRD_PARTY_LICENSES.txt" -OutFile $ytdlpNotices
    } catch {
        Write-Warning "yt-dlp $ytdlpTag has no THIRD_PARTY_LICENSES.txt: $($_.Exception.Message)"
    }

    Write-Step "ytdlp: downloading FFmpeg $FfmpegVersion (essentials build)"
    $ffmpeg = Join-Path $dir "ffmpeg.exe"
    $zip = Join-Path $stage "ffmpeg.zip"
    $null = Get-ReleaseAsset "GyanD/codexffmpeg" $FfmpegVersion "ffmpeg-$FfmpegVersion-essentials_build.zip" $zip
    Expand-ZipEntry $zip "*/bin/ffmpeg.exe" $ffmpeg
    Expand-ZipEntry $zip "*_build/LICENSE" (Join-Path $licenses "ffmpeg-LICENSE.txt")
    Expand-ZipEntry $zip "*_build/README.txt" (Join-Path $licenses "ffmpeg-README.txt")
    Remove-Item -LiteralPath $zip
    Write-Host "    $(Get-FirstLine 'ffmpeg -version' $ffmpeg @('-version'))"
    # The bot converts downloads to FLAC.
    $flac = Join-Path $stage "test.flac"
    Invoke-Native "The FLAC encoding test" $ffmpeg @("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
        "-i", "sine=frequency=440:duration=1", "-c:a", "flac", $flac)
    if ((Get-Item -LiteralPath $flac).Length -eq 0) { throw "ffmpeg wrote an empty FLAC file." }
    Remove-Item -LiteralPath $flac
    Install-Part "ytdlp"
}

Remove-Item -LiteralPath $stage -Recurse -Force

# ---------------------------------------------------------------------------
# -Verify: the bundled yt-cipher has to start with no network access
# ---------------------------------------------------------------------------
# Runs a copy of the bundled service the way the bot does, plus --cached-only, on 127.0.0.1:8765,
# and requests it once. The copy keeps Deno's run-time files (deno.lock, compiled-code cache)
# out of the bundle.
function Test-CipherService {
    Write-Step "verify: starting the bundled yt-cipher offline on 127.0.0.1:$VerifyPort"
    $work = Join-Path ([IO.Path]::GetTempPath()) "jmb-bundle-verify-$PID"
    New-CleanDirectory $work
    $app = Join-Path $work "yt-cipher"
    Copy-Item -LiteralPath (Join-Path $Output "cipher\yt-cipher") -Destination $app -Recurse

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Join-Path $Output "cipher\deno.exe"
    $psi.Arguments = "run --cached-only --node-modules-dir=none --allow-net --allow-read --allow-write --allow-env server.ts"
    $psi.WorkingDirectory = $app
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    # server.ts exits when its stdin closes (the watchdog patch), so the pipe is held open.
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables["DENO_DIR"] = "deno-cache"
    $psi.EnvironmentVariables["HOST"] = "127.0.0.1"
    $psi.EnvironmentVariables["PORT"] = "$VerifyPort"
    $psi.EnvironmentVariables["OVERRIDE_PLAYER_VARIANT"] = "IAS"
    # The service keeps its player cache under this; do not use the real one of this machine.
    $psi.EnvironmentVariables["XDG_CACHE_HOME"] = Join-Path $work "cache"

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $response = $null
    $owners = @()
    try {
        $deadline = (Get-Date).AddSeconds(60)
        while (-not $response -and -not $process.HasExited -and (Get-Date) -lt $deadline) {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$VerifyPort/" -TimeoutSec 5
            } catch {
                Start-Sleep -Milliseconds 300
            }
        }
        # The answer has to come from the process started here, not from something else on the port.
        $owners = @(Get-NetTCPConnection -LocalPort $VerifyPort -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { $_.OwningProcess })
    } finally {
        # Only the process started above is stopped: first the way the bot does it, then by PID.
        if (-not $process.HasExited) {
            $process.StandardInput.Close()
            if (-not $process.WaitForExit(10000)) { Invoke-Native "taskkill" "taskkill" @("/PID", "$($process.Id)", "/T", "/F") }
        }
    }
    $id = $process.Id
    $log = "stdout:`n$($stdout.Result)`nstderr:`n$($stderr.Result)"
    $process.Dispose()
    Remove-Item -LiteralPath $work -Recurse -Force
    if (-not $response -or $response.StatusCode -ne 200 -or $owners -notcontains $id) {
        throw "The bundled yt-cipher did not answer on port $VerifyPort.`n$log"
    }
    Write-Host "    GET http://127.0.0.1:$VerifyPort/ -> HTTP $($response.StatusCode), $($response.Content.Length) characters, from PID $id"
    Write-Host "    stopped; the service works with no network access"
}

if ($Verify) { Test-CipherService }

# ---------------------------------------------------------------------------
# THIRD-PARTY-NOTICES.txt and the summary, from what is in the output folder
# ---------------------------------------------------------------------------
Write-Step "Writing THIRD-PARTY-NOTICES.txt"
$jdkInfo = Join-Path $licenses "jdk-release.txt"
$jdkVendor = Get-Info $jdkInfo "IMPLEMENTOR"
$jdkBuild = Get-Info $jdkInfo "IMPLEMENTOR_VERSION"
$runtimeVersion = Get-Info (Join-Path $Output "runtime\release") "JAVA_VERSION"
$fxJar = @(Get-ChildItem -LiteralPath (Join-Path $Output "javafx") -Filter "javafx-base-*-win.jar")[0].Name
$fxVersion = $fxJar -replace '^javafx-base-(.+)-win\.jar$', '$1'
$fxInfo = Join-Path $licenses "openjfx\source.txt"
$denoBundled = (Get-FirstLine "deno --version" (Join-Path $Output "cipher\deno.exe") @("--version")) -replace '^deno (\S+).*$', '$1'
$cipherInfo = Join-Path $licenses "yt-cipher-build-info.txt"
$cipherCommit = Get-Info $cipherInfo "yt-cipher.commit"
$ejsCommit = Get-Info $cipherInfo "ejs.commit"
$ytdlpBundled = Get-FirstLine "yt-dlp --version" (Join-Path $Output "ytdlp\yt-dlp.exe") @("--version")
$ffmpegBundled = (Get-FirstLine "ffmpeg -version" (Join-Path $Output "ytdlp\ffmpeg.exe") @("-version")) -replace '^ffmpeg version (\S+).*$', '$1'
$ffmpegReadme = Join-Path $licenses "ffmpeg-README.txt"
$ffmpegLicense = "see licenses\ffmpeg-LICENSE.txt"
$ffmpegSource = "https://github.com/FFmpeg/FFmpeg"
if (Test-Path -LiteralPath $ffmpegReadme) {
    foreach ($line in Get-Content -LiteralPath $ffmpegReadme) {
        if ($line -match '^License:\s*(.+)$') { $ffmpegLicense = $Matches[1].Trim() }
        if ($line -match '^Source Code:\s*(\S+)') { $ffmpegSource = $Matches[1] }
    }
}
$ffmpegBinary = "https://github.com/GyanD/codexffmpeg/releases"
if ($ffmpegBundled -match '^(.+)-essentials_build') {
    $ffmpegBinary = "$ffmpegBinary/download/$($Matches[1])/ffmpeg-$($Matches[1])-essentials_build.zip"
}

$notices = @(
    "THIRD-PARTY NOTICES",
    "===================",
    "",
    "This package bundles the third-party software listed below. Each component stays under its",
    "own licence. The licence texts collected at build time are in the licenses\ folder and in",
    "runtime\legal\. Generated by scripts\windows\bundle.ps1.",
    "",
    "Java runtime (runtime\)",
    "  Version: $jdkVendor $jdkBuild (Java $runtimeVersion), reduced with jlink",
    "  Licence: GNU General Public License version 2 with the Classpath Exception",
    "           (texts and third-party notices in runtime\legal\)",
    "  Binary:  the JDK installed on the build machine$(if ($jdkVendor -eq 'Eclipse Adoptium') { ' (Eclipse Temurin, https://adoptium.net/temurin/releases/)' })",
    "  Source:  $(Get-Info $jdkInfo 'SOURCE_REPO') ($(Get-Info $jdkInfo 'SOURCE'))",
    "",
    "OpenJFX (javafx\)",
    "  Version: $fxVersion (modules $($JavaFxModules -join ', '); Windows x64)",
    "  Licence: GNU General Public License version 2 with the Classpath Exception (licenses\openjfx\LICENSE).",
    "           The notices of the third-party code in the modules (WebKit, GStreamer and others)",
    "           are the javafx.* files in licenses\openjfx\.",
    "  Binary:  Maven Central, https://repo.maven.apache.org/maven2/org/openjfx/",
    "  Source:  $(Get-Info $fxInfo 'repository') (tag $(Get-Info $fxInfo 'tag'))",
    "",
    "Deno (cipher\deno.exe)",
    "  Version: $denoBundled",
    "  Licence: MIT (licenses\deno-LICENSE.md)",
    "  Binary:  https://github.com/denoland/deno/releases/download/v$denoBundled/deno-x86_64-pc-windows-msvc.zip",
    "  Source:  https://github.com/denoland/deno/tree/v$denoBundled",
    "",
    "yt-cipher (cipher\yt-cipher\)",
    "  Version: commit $cipherCommit ($(Get-Info $cipherInfo 'yt-cipher.commitDate')), with",
    "           scripts\windows\yt-cipher-watchdog.patch from this project applied to server.ts"
)
if ((Get-Info $cipherInfo "yt-cipher.licenseFile") -eq "none") {
    $notices += "  Licence: none stated. The repository had no licence file at this commit."
} else {
    $notices += "  Licence: see licenses\yt-cipher-LICENSE.txt"
}
$notices += @(
    "  Source:  https://github.com/kikkia/yt-cipher/tree/$cipherCommit",
    "",
    "yt-dlp/ejs (cipher\yt-cipher\ejs\)",
    "  Version: commit $ejsCommit, imports rewritten by yt-cipher's scripts/patch-ejs.ts",
    "  Licence: The Unlicense (licenses\ejs-LICENSE.txt)",
    "  Source:  https://github.com/yt-dlp/ejs/tree/$ejsCommit",
    "",
    "Deno modules used by yt-cipher (cipher\yt-cipher\deno-cache\)"
)
if (Test-Path -LiteralPath $cipherInfo) {
    foreach ($line in Get-Content -LiteralPath $cipherInfo) {
        if ($line -notmatch '^module=(.*)$') { continue }
        $module = $Matches[1]
        if ($module -match '^npm:') { $note = "; text in licenses\npm-*" }
        elseif ($module -match '^(jsr:@std/|https://deno\.land/std@)') { $note = " (Deno standard library, MIT, https://github.com/denoland/std)" }
        elseif ($module -match '/x/ts_prometheus') { $note = " (https://github.com/marcopacini/ts-prometheus, which states no licence)" }
        else { $note = " (see the module's own page for its licence)" }
        $notices += "  $module$note"
    }
} else {
    $notices += "  unknown (licenses\yt-cipher-build-info.txt is missing)"
}
$notices += @(
    "",
    "yt-dlp (ytdlp\yt-dlp.exe)",
    "  Version: $ytdlpBundled",
    "  Licence: The Unlicense (licenses\yt-dlp-LICENSE.txt). The standalone executable is a PyInstaller",
    "           bundle that also contains Python and other libraries under their own licences:",
    "           $(if (Test-Path -LiteralPath (Join-Path $licenses 'yt-dlp-THIRD_PARTY_LICENSES.txt')) { 'licenses\yt-dlp-THIRD_PARTY_LICENSES.txt' } else { 'see the yt-dlp repository' })",
    "  Binary:  https://github.com/yt-dlp/yt-dlp/releases/download/$ytdlpBundled/yt-dlp.exe",
    "  Source:  https://github.com/yt-dlp/yt-dlp/tree/$ytdlpBundled",
    "",
    "FFmpeg (ytdlp\ffmpeg.exe)",
    "  Version: $ffmpegBundled",
    "  Licence: $ffmpegLicense (licenses\ffmpeg-LICENSE.txt). This build enables GPL components; the",
    "           libraries it includes are listed in licenses\ffmpeg-README.txt.",
    "  Binary:  $ffmpegBinary",
    "  Source:  $ffmpegSource"
)
[IO.File]::WriteAllLines((Join-Path $Output "THIRD-PARTY-NOTICES.txt"), $notices)

$rows = @(
    @("runtime\", "$jdkVendor $jdkBuild", "runtime", "runtime"),
    @("javafx\", $fxVersion, "javafx", "javafx"),
    @("cipher\deno.exe", $denoBundled, "cipher\deno.exe", "cipher"),
    @("cipher\yt-cipher\", "$($cipherCommit.Substring(0, 7)) + ejs $($ejsCommit.Substring(0, 7))", "cipher\yt-cipher", "cipher"),
    @("ytdlp\yt-dlp.exe", $ytdlpBundled, "ytdlp\yt-dlp.exe", "ytdlp"),
    @("ytdlp\ffmpeg.exe", $ffmpegBundled, "ytdlp\ffmpeg.exe", "ytdlp"),
    @("licenses\ + notices", "", "licenses", "")
)
$total = 0
$table = foreach ($row in $rows) {
    $size = Get-Size (Join-Path $Output $row[2])
    $total += $size
    [pscustomobject]@{ Component = $row[0]; Version = $row[1]; Size = Format-Size $size; Status = $status[$row[3]] }
}
$table = @($table) + [pscustomobject]@{ Component = "Total"; Version = ""; Size = Format-Size $total; Status = "" }
Write-Host ""
Write-Host (($table | Format-Table -AutoSize | Out-String).Trim())
Write-Host ""
Write-Host ("Done in {0:N0} s: {1}" -f ((Get-Date) - $started).TotalSeconds, $Output)
