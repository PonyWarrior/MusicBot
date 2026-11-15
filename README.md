# JMusicBot (PonyWarrior fork)

A cross-platform Discord music bot that is easy to set up and run yourself.

> [!NOTE]
> This is a fork of [arif-banai/MusicBot](https://github.com/arif-banai/MusicBot), which is itself
> a maintained fork of [jagrosh/MusicBot](https://github.com/jagrosh/MusicBot). Everything the
> upstream bot does still works the same way; this page describes what the fork adds and how to
> build and run it. `master` here is upstream's `master` plus the fork's commits, so the upstream
> documentation ([setup guide](https://jmusicbot.com/setup), [architecture](docs/ARCHITECTURE.md))
> still applies.

## What this fork adds

| Area | Change |
|------|--------|
| YouTube playback | Works **without a Google account**. The signature cipher is resolved by a cipher server instead of the broken local extractor, and two youtube-source defects that made tracks fail or stop early are patched. See [docs/YOUTUBE_PLAYBACK.md](docs/YOUTUBE_PLAYBACK.md). |
| Local cipher | The bot can start its own [yt-cipher](https://github.com/kikkia/yt-cipher) service as a child process, so playback requests are not sent to a third party. |
| Local music library | Favoriting a track downloads it with yt-dlp (FLAC by default). Every play request and every search looks in the library first and plays from disk when it finds the track. |
| Search | Results show the artist, the thumbnail and the source of every result, in both the slash command and the prefix command. |
| Audio quality | Sample rate conversion runs at `high` quality by default (`performance.resamplingQuality`); lavaplayer's default is its coarsest filter. |
| Desktop GUI | A web GUI (React 19 + Astryx) in a JavaFX window: now playing, queue, controls, logs, and every setting of `config.txt` as a form. The Swing window is still available. |
| Logging | The bot writes `logs/jmusicbot.log` itself through an async rolling appender (20 MB per file, 7 days, 200 MB in total). |
| Windows package | A script that assembles a self-contained zip: bundled Java runtime, JavaFX, Deno, yt-cipher, yt-dlp and ffmpeg. |

## Requirements

* **Java 25 or newer.**
* A Discord bot token, with the **Message Content Intent** enabled in the
  [Discord Developer Portal](https://discord.com/developers/applications)
  (Your Application > Bot > Privileged Gateway Intents). Without it the bot does not see commands.
* The bot needs the `Connect`, `Speak`, `Embed Links`, `Add Reactions` and `Read Message History`
  permissions in the channels it uses.
* Linux: **glibc 2.38 or newer** (Ubuntu 24.04, Debian 13), plus `libopus0` and `libsodium23`.

Optional, each for one feature:

| Feature | Needs |
|---------|-------|
| Local cipher | [Deno](https://deno.com) 2.x and git |
| Local library downloads | Python 3.9+ with a yt-dlp checkout (or a standalone `yt-dlp` binary), and ffmpeg on the `PATH` |
| Web GUI | The JavaFX 25 modules on the module path. The build pulls the **Windows** JavaFX jars; on other systems the bot falls back to the Swing window, or runs headless. |
| Building the web GUI | Node.js and [pnpm](https://pnpm.io) |

## Building

```bash
mvn -B package          # unit tests + target/JMusicBot-0.7.0-All.jar
mvn -B verify           # unit and integration tests
mvn -B verify -Pcoverage  # what CI runs
```

The web GUI is a separate build:

```bash
cd webui
pnpm install --frozen-lockfile
pnpm build              # output in webui/dist
```

## Running

`config.txt` is read from the **working directory**. On the first start without one, the bot
writes a default file; put the bot token and your Discord user id (`discord.owner`) in it.

### Headless

```bash
java -Dfile.encoding=UTF-8 -Dnogui=true --enable-native-access=ALL-UNNAMED \
     -XX:+UseZGC -XX:+AlwaysPreTouch -jar target/JMusicBot-0.7.0-All.jar
```

In PowerShell, quote each option that starts with `-D`
(`java "-Dfile.encoding=UTF-8" "-Dnogui=true" ...`), because PowerShell splits them otherwise.

| Flag | Why |
|------|-----|
| `-Dfile.encoding=UTF-8` | Correct non-ASCII text in embeds and autocomplete, mainly on Windows |
| `-Dnogui=true` | No window; required on servers |
| `--enable-native-access=ALL-UNNAMED` | Lets Java 22+ load the native JDAVE and udpqueue libraries |
| `-XX:+UseZGC` | Sub-millisecond GC pauses |
| `-XX:+AlwaysPreTouch` | Pre-faults the heap, so playback does not stall on page faults |

### With the web GUI (Windows)

The JavaFX jars are not inside the bot's jar. Copy them next to the checkout once, build the web
UI, then start the bot with them on the module path:

```bat
mvn -q dependency:copy-dependencies -DincludeGroupIds=org.openjfx -DincludeClassifiers=win -DoutputDirectory=javafx

java --module-path javafx --add-modules javafx.controls,javafx.web ^
     -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED,javafx.graphics ^
     -XX:+UseZGC -XX:+AlwaysPreTouch -jar target\JMusicBot-0.7.0-All.jar
```

Closing the window shuts the bot down, including the cipher service and a running download.
Set `gui.mode = "swing"` in `config.txt` for the Swing window, or `gui.enabled = false` for none.
If JavaFX is missing, the bot logs that and opens the Swing window instead.

Only one instance can run per folder (`.jmusicbot.lock`). *"Another instance of JMusicBot is
already running"* means exactly that: a second one would answer every command twice.

## YouTube without a Google account

YouTube changes its player script often, and youtube-source can no longer extract the signature
function from the current ones (`must find sig function`). This fork resolves the signature
through a cipher server instead, which needs no OAuth and no `youtubetoken.txt`:

```hocon
playback.youtube {
  useOAuth = false
  remoteCipher = "https://cipher.kikkia.dev/"   # the default
}
```

The public server is a third party: every stream resolution tells it which video is played.
To keep that on your machine, run the service locally.

### Local cipher

```powershell
# from the folder the bot is started in; creates cipher\yt-cipher
powershell -ExecutionPolicy Bypass -File scripts\windows\setup-cipher.ps1
```

The script clones yt-cipher, checks out the pinned `yt-dlp/ejs` commit, runs the repository's
own `patch-ejs.ts`, and applies [a small patch](scripts/windows/yt-cipher-watchdog.patch) that
makes the service exit when the bot's end of its stdin pipe closes. Then set:

```hocon
playback.youtube {
  remoteCipher = "http://127.0.0.1:8001/"   # must point at the local service
  localCipher {
    enabled = true
    directory = "cipher/yt-cipher"
    port = 8001
    command = "deno"
  }
}
```

Enabling `localCipher` only starts the service; `remoteCipher` decides which server is used, so
set both. The bot starts the service right after reading the config, waits for the port (up to
`startupTimeoutSeconds`), writes its output to `logs/yt-cipher.log` and stops it on exit. If the
bot is killed, the service notices the closed pipe and exits by itself; a leftover service from
an earlier run is stopped on the next start, but only when it is the configured program. If
another program holds the port, the bot reports that instead of using it.

The environment variable that selects the player script variant is `OVERRIDE_PLAYER_VARIANT`
(`IAS` is the default here and the only reliable one at the moment).

### If playback still fails

*"This video requires login"* and *"Sign in to confirm you're not a bot"* are YouTube judging the
IP address of the machine (typically a datacenter range), not the account. Either route the audio
traffic through another address with the [proxy settings](#proxy), or set
`playback.youtube.useOAuth = true` and sign in with a **secondary** Google account when the bot
prints the device code.

## Local music library

```powershell
# from the folder the bot is started in; creates ytdlp\yt-dlp
powershell -ExecutionPolicy Bypass -File scripts\windows\setup-ytdlp.ps1
```

```hocon
library {
  enabled = true
  folder = "Music"                  # relative to the working directory
  ytdlpDirectory = "ytdlp/yt-dlp"   # working directory of the yt-dlp process
  ytdlpCommand = "python"
  ytdlpArgs = [ "-m", "yt_dlp" ]
  audioFormat = "flac"              # needs ffmpeg on the PATH
  searchFirst = true
  downloadOnFavorite = true
}
```

With a standalone binary, point `ytdlpDirectory` at its folder, set `ytdlpCommand` to its file
name and `ytdlpArgs` to `[]`, or to `[ "--ffmpeg-location", "." ]` when ffmpeg sits in the same
folder instead of on the `PATH`.

* **Favorite** downloads the playing track (audio only, converted, tags embedded, with a
  `.info.json` sidecar) and adds it to the `favorites` playlist. The download runs in the
  background; pressing again while it runs only reports that it is already downloading.
* **Unfavorite** removes the entry. The file is deleted only when the bot downloaded it itself
  (it keeps a list in `Music/.downloads.txt`) and no other playlist refers to it. Your own files
  in the folder are never deleted. A file that is still playing is deleted when it is released.
* **Playing and searching** look in the library first. A match is played from disk, which takes
  a few milliseconds instead of the seconds a YouTube lookup takes, and is not re-encoded. A URL
  always goes to the online sources.
* Audio files you put in the folder yourself (`.flac`, `.m4a`, `.mp3`, `.ogg`, `.opus`, `.wav`,
  `.aac`, `.wma`, also in subfolders) are part of the library.

## Web GUI

The bot serves the built web UI and a small JSON API on `127.0.0.1`, on a free port, and opens it
in a JavaFX WebView. It looks for the UI in `webui/dist` of the working directory, then `web/`
there, then next to the jar.

* **Dashboard:** now playing with progress, play/pause, skip, previous, favorite, shuffle,
  repeat, stop, volume, the queue, status and the log.
* **Advanced:** every setting of `config.txt` as a field with a per-section reset, performance
  figures, history and the full log. Saving rewrites only the changed values in `config.txt`
  (comments are kept) and offers a restart, which the new values need.
* Light and dark mode; the choice is kept in `gui-theme.txt` and the window's title bar follows.

The server only answers requests whose `Host` (and `Origin`, for writes) name that loopback
address, so a web page cannot reach it by pointing a domain at 127.0.0.1. Only keys that exist in
the bot's configuration can be written. The bot token is write-only: the page never receives it,
and an empty token field means "keep the current one".

The API is not authenticated beyond that: any program running as the same user can call it,
including the settings that name commands to run. Do not expose the port.

## Windows package

`scripts\windows\package.bat` tests and builds the jar, builds the web UI and zips a folder that
runs on a machine with nothing installed. It does not download the bundled programs; put them in
place once:

```
dist\JMusicBot-0.7.0\
├── runtime\             a Java 25 runtime (see below)
├── javafx\              the five javafx-*-win.jar files (the mvn command above)
├── cipher\deno.exe
├── cipher\yt-cipher\    a checkout made by setup-cipher.ps1 (server.ts, src\, ejs\, ...)
├── ytdlp\yt-dlp.exe     the standalone yt-dlp
└── ytdlp\ffmpeg.exe
```

A runtime with the modules the bot uses:

```bat
jlink --no-header-files --no-man-pages --strip-debug --output dist\JMusicBot-0.7.0\runtime ^
  --add-modules java.base,java.compiler,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging,java.management,java.security.sasl,java.naming,java.net.http,java.scripting,java.security.jgss,java.transaction.xa,java.sql,jdk.crypto.ec,jdk.httpserver,jdk.jsobject,jdk.localedata,jdk.management,jdk.unsupported,jdk.xml.dom,jdk.zipfs
```

The package's `config.txt` sets `DENO_DIR = "deno-cache"` for the cipher service, so Deno keeps
its modules in `cipher\yt-cipher\deno-cache`. Start the service once from that folder before
packaging if the package should work without downloading them on its first start.

Then, with `JAVA_HOME` pointing at a JDK 25 and `mvn` and `pnpm` on the `PATH`:

```bat
scripts\windows\package.bat
```

The launchers, `README.txt` and `config.txt` of the package come from
[`scripts/windows/package/`](scripts/windows/package) on every run, and the script refuses to
continue if that `config.txt` holds anything but the placeholder token. Logs, playlists, the
library and other runtime files are removed from the folder before it is zipped. Set `JMB_DIST`
to build the package somewhere other than `dist\`.

## Docker

The [Dockerfile](Dockerfile) builds a headless image from this source:

```bash
docker build -t jmusicbot .
docker run --rm -it -v "/path/to/musicbot:/musicbot" jmusicbot
```

`/musicbot` is the working directory: `config.txt`, `Playlists/` and `serversettings.json` live
there, and a default `config.txt` is written on the first run. The image has no Deno, Python or
ffmpeg, so the local cipher and the library downloads are not available in it; the default remote
cipher works. This fork does not publish images; the upstream ones at
`ghcr.io/arif-banai/musicbot` do not contain the changes described here.

## Performance tuning

```hocon
performance {
  nasBufferMs = 800          # Discord send buffer; protects against JVM pauses
  frameBufferMs = 2000       # decoded audio kept ahead of playback
  resamplingQuality = "high" # low, medium, high
}
```

The buffers do not delay the start of a track; they only decide how long a pause can be before
it is heard. `logging.level = "all"` writes about forty times more than `info`; it costs little
CPU, but the raw websocket frames it logs **contain the bot token**, so treat `logs/` as secret.

## Proxy

```hocon
proxy {
  host = "127.0.0.1"
  port = 8080
  lavaplayer = true   # audio source requests (YouTube, SoundCloud, ...)
  jda = false         # Discord API traffic
  github = false      # version check
}
```

Leave `host` empty or `port` at 0 to disable it. HTTP proxies only.

## Repository layout

```
src/                 the bot (Java)
webui/               the web GUI (React, TypeScript, Vite)
scripts/windows/     setup-cipher.ps1, setup-ytdlp.ps1, package.bat and the package files
docker/, Dockerfile  the headless image
docs/                architecture, development workflow, YouTube playback notes
```

`src/main/java/dev/lavalink/youtube/track/YoutubePersistentHttpStream.java` deliberately has the
package and name of a youtube-source class: it replaces it, and a `maven-shade-plugin` filter
keeps the library's copy out of the jar.

## Development

Branch names and the workflow follow upstream; see
[docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md). Unit tests live under
`src/test/java/.../unit/` and run with `mvn test`; a test outside that folder is not picked up.

Problems with what this fork adds belong in this repository. For the bot itself, please use
[upstream's issues and discussions](https://github.com/arif-banai/MusicBot/issues).

## Not supported

* **Spotify.** Its streams are DRM protected and cannot be played by a lavaplayer-based bot.

## Credits and license

[jagrosh](https://github.com/jagrosh/MusicBot) wrote JMusicBot and
[arif-banai](https://github.com/arif-banai/MusicBot) maintains the fork this one is based on. The
bot is built on [JDA](https://github.com/DV8FromTheWorld/JDA),
[lavaplayer](https://github.com/lavalink-devs/lavaplayer) and
[youtube-source](https://github.com/lavalink-devs/youtube-source); the fork also uses
[yt-cipher](https://github.com/kikkia/yt-cipher) and [yt-dlp](https://github.com/yt-dlp/yt-dlp).

Licensed under the [Apache License 2.0](LICENSE).
