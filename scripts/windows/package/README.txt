JMusicBot for Windows
=====================

This is the Windows package of the PonyWarrior fork of JMusicBot:
https://github.com/PonyWarrior/MusicBot

Requirements
------------
* Windows 10/11 x64 (all bundled binaries are Windows x64).
* No Java install is needed: a Java 25 runtime is bundled in runtime\.
  If you remove it, set JAVA_HOME to a JDK/JRE 25 instead.
* A Discord bot token, and the Message Content Intent enabled in the
  Discord Developer Portal.
* The bot needs Connect, Speak, Embed Links, Add Reactions and
  Read Message History permissions in the voice channels it uses.

First run
---------
1. Edit config.txt: set discord.token and discord.owner.
2. Run run.bat (headless) or run-gui.bat (desktop window).
   Both cd into their own folder and put cipher\ on PATH, so the bundled
   cipher service is found.

What is bundled
---------------
JMusicBot-<version>-All.jar   the bot, with all dependencies and the web UI inside
runtime\                      Java 25 runtime
javafx\                       JavaFX 25 modules for the web GUI window
config.txt                    ready-to-run configuration (local cipher and library enabled)
config.defaults               what the Reset buttons in the GUI go back to
cipher\deno.exe               Deno, used by the cipher service
cipher\yt-cipher\             local yt-cipher service (server.ts, src, ejs\src, docs)
cipher\yt-cipher\deno-cache\  pre-downloaded Deno modules, so the cipher starts offline
ytdlp\yt-dlp.exe              standalone yt-dlp, no Python needed
ytdlp\ffmpeg.exe              ffmpeg, used for the audio conversion
run.bat                       headless launcher
run-gui.bat                   web GUI launcher (no console window)
LICENSE.txt                   licence of the bot (Apache 2.0)
THIRD-PARTY-NOTICES.txt       versions, sources and licences of the bundled software
licenses\                     the licence texts of the bundled software

GUI
---
run-gui.bat opens the web UI in a JavaFX window. Closing the window stops the
bot, the cipher service and any running download.
Set gui.mode = "swing" in config.txt to use the legacy Swing window instead.

YouTube playback
----------------
No Google account is needed. config.txt starts the bundled yt-cipher on
127.0.0.1:8001 and points playback.youtube.remoteCipher at it. The Deno
modules the cipher needs are bundled in cipher\yt-cipher\deno-cache, so it
starts without network access.

If you prefer the public cipher server, set
  playback.youtube.remoteCipher = "https://cipher.kikkia.dev/"
and playback.youtube.localCipher.enabled = false.

Local library and downloads
---------------------------
The library is enabled and points at Music\ next to the jar. Put existing
audio files there (recursively), and favoriting a track downloads it with the
bundled yt-dlp and converts it with the bundled ffmpeg. No Python or system
ffmpeg is required.

Updating
--------
New versions are published at
https://github.com/PonyWarrior/MusicBot/releases
Extract the new package into a new folder and copy over what is yours:
config.txt, serversettings.json, Music\ and Playlists\.

Not supported
-------------
* Spotify: Spotify streams are DRM protected and cannot be played by a
  lavaplayer-based bot.

Logs
----
logs\jmusicbot.log is written by the bot. When logging.level is "all" or
"trace" it may contain the bot token in websocket frames, so treat it as
secret.
