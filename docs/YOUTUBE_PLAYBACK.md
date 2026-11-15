# YouTube playback in this fork

Why YouTube tracks failed with the stock youtube-source, what this fork changes, and what was
measured along the way. The figures come from a desktop Windows machine on a home connection;
treat them as orders of magnitude.

## The symptom

```
[ERROR] [LocalSignatureCipherManager]: Problematic YouTube player script ... detected
        (issue detected with script: must find sig function)
[WARN]  [LocalAudioTrackExecutor]: Suspicious exception for playback of <id>
[ERROR] [AudioHandler]: Track <id> has failed to play
```

`dev.lavalink.youtube.AllClientsFailedException: All clients failed to load the item`. Every
client failed, each for its own reason:

| Client | Error | Cause |
|--------|-------|-------|
| `ANDROID_VR` | `This video requires login.` | Bot detection on the IP address |
| `MWEB` | `Must find sig function from script` | Local cipher extraction broken |
| `WEB` | `No supported audio streams available` | SABR response without direct URLs |
| `TVHTML5_SIMPLY` | `Sign in to confirm you're not a bot` | Bot detection on the IP address |

The failure looks intermittent because it is: when YouTube returns formats with a direct URL, no
cipher is needed and the track plays. When it returns a format list without them, the cipher path
runs and the extractor fails.

## 1. The signature cipher is resolved by a server

YouTube changed the format of its player script, and youtube-source's `SIG_FUNCTION_PATTERN` and
`N_FUNCTION_PATTERN` no longer match it. At the time of writing the patterns on youtube-source's
main branch were identical, so updating the dependency did not help.

`playback.youtube.remoteCipher` points youtube-source at a cipher server, which resolves the
signature server-side. `AudioSource.buildYoutubeOptions()` applies it whether or not OAuth is on
(upstream only enabled it together with OAuth), and logs the result at startup:

```
YouTube source configured: oauth=false, remoteCipher=http://127.0.0.1:8001/,
                           clients=[LegacyFormatClient, AndroidVrWithThumbnail, MWebWithThumbnail,
                                    WebWithThumbnail, TvHtml5SimplyWithThumbnail]
```

`Client.requirePlayerScript()` decides which clients need the cipher at all:

| Client | Needs cipher | Needs OAuth |
|--------|--------------|-------------|
| `ANDROID_VR` | no | no |
| `MWEB` | yes | no |
| `WEB` | yes | no |
| `TVHTML5_SIMPLY` | yes | no |
| `TV` | yes | yes |

`MWEB` is the one that does the work: it needs the cipher but not an account.

## 2. The media request needs a bounded `Range` header

With the cipher resolved, the media request still answered `HTTP 403`.
`dev.lavalink.youtube.track.YoutubePersistentHttpStream` asked for a byte range with the legacy
`range=` query parameter and turned the HTTP `Range` header off. YouTube's CDN answered:

```
Range: bytes=0-2047   (bounded)           -> 206
Range: bytes=0-       (open ended)        -> 403
no Range header, range= query param only  -> 403
a window larger than 512 KiB              -> 403
```

The fork ships its own class with the same package and name at
`src/main/java/dev/lavalink/youtube/track/YoutubePersistentHttpStream.java`. A
`maven-shade-plugin` filter on `dev.lavalink.youtube:common` removes the library's copy from the
jar, so exactly one is present at runtime. The replacement:

* always sends a bounded `Range` header and opens the next window when the current one is used
  up;
* fetches the next window in the background, so the decoder does not run dry at a window
  boundary (the bot pulls frames without blocking, so a stall there means silence);
* learns the real size from `Content-Range` and reports it. The legacy format has no content
  length of its own, and lavaplayer's MP4 demuxer uses the stream length to locate the `moov`
  atom; with a bogus length it seeks past the end of the file;
* implements `skip()` by reading and discarding, because HTTP response streams do not support
  it, and seeks hard to a new offset with a single request;
* retries a failed window (three attempts, with a short pause, not on a 4xx other than 429) and
  then fails the track with the HTTP status, instead of quietly ending it.

## 3. The legacy format is used first

YouTube throttles the adaptive audio formats (itag 140, 249, 250, 251) through the `n`
parameter: a resolved URL serves roughly 768 KiB and then answers `403`. The progressive format
`itag 18` carries the same audio and is not throttled.

`TrackFormats.getBestFormat()` prefers itag 251, so the fork adds
`com.jagrosh.jmusicbot.audio.LegacyFormatClient`, an `MWeb` subclass that offers only itag 18.
It is first in the client list; the stock clients stay behind it as fallbacks.

The same client keeps ordinary videos on the static playback path. The segmented path derives
duration and position from the sequence headers of a live stream, which an ordinary video does
not have, so it showed `00:00 / 00:00`. A live stream, whose duration is unknown, keeps the
segmented path.

youtube-source is pinned to **1.18.2**, which treats `itag=18` as a legacy format and plays it
through the static path. 1.18.1 sent it down the segmented path, where a video never returns the
`204` that ends a live stream, so the track appeared to loop.

## 4. The player API is asked once per track

youtube-source calls the player API when a track is queued (metadata) and again when it starts
(formats). `LegacyFormatClient` keeps the response in `PlayerApiCache` (five minutes, 64
entries, successful responses only), so the second call is served from memory.

## Audible artifacts

A track that started cleanly and then broke up had three causes, the first two fixed by the
stream class above (bogus content length, stalls at window boundaries). The third was the
resampler: lavaplayer converts everything that is not 48 kHz with libsamplerate, and its default
quality is the coarsest filter, which is audible on music as a rough or warbling sound.

```hocon
performance {
  resamplingQuality = "high"   # low, medium, high
}
```

Ruled out by measurement: corruption in the HTTP stream (the windowed download is byte-identical
to a single request), the decoder (identical output from the stream and from a file), the cipher
server serving degraded audio, frame timing drift, and dropped packets in the middle of a track.

What remains is a start-up gap of about one second before the first frame of an online track:

| Step | Time |
|------|------|
| Player API (metadata) | ~400 ms |
| Cipher (local service) | ~2 ms |
| First media request, including the TLS handshake | ~300 ms |
| Second media request | ~130 ms |

It is a delay, not corruption; a track from the local library starts within a few frames.

## Where the time goes

| Stage | Time |
|-------|------|
| YouTube player API | 500–2000 ms per call |
| Signature cipher, local service, warm | 1–2 ms |
| Signature cipher, public service, warm | 125–216 ms |
| A track from the local library | 2–3 ms |

The local library is the largest gain by far, which is why the favorite button downloads.

Left alone on purpose: `performance.frameBufferMs` and `performance.nasBufferMs` are maximum
buffer sizes, not delays, so lowering them only makes stutter more likely.

## If YouTube still refuses

*"This video requires login"* and *"Sign in to confirm you're not a bot"* depend on the IP
address the request comes from, and come and go. `MWEB` with the cipher is not affected, so
these are usually harmless. If they are not:

* route the audio traffic through another address with `proxy.lavaplayer = true`; or
* set `playback.youtube.useOAuth = true`. The bot prints a device code for
  `https://www.google.com/device` at startup; sign in with a **secondary** Google account. The
  refresh token is kept in `youtubetoken.txt` in the working directory. Delete that file to sign
  in again.

Setting `remoteCipher = ""` goes back to youtube-source's own extraction, which fails with the
player scripts current at the time of writing.
