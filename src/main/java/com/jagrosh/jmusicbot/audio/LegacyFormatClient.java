/*
 * Copyright 2026 Arif Banai (arif-banai)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.StreamFormat;

import java.util.List;

/**
 * A YouTube client restricted to the legacy {@code itag 18} format.
 *
 * <p><b>Why this exists:</b> YouTube throttles the modern adaptive audio formats (itag 140, 249,
 * 250, 251, ...) using the {@code n} parameter. Measured against YouTube's own responses, a
 * resolved URL for those formats stops serving bytes partway through the file and then answers
 * {@code HTTP 403}:
 *
 * <pre>
 *   itag 251 (webm/opus, 2.7 MB) : 206 up to ~768 KiB, then 403
 *   itag 18  (mp4,       7.7 MB) : 206 for the whole file
 * </pre>
 *
 * <p>{@code itag 18} is the legacy progressive format. It carries the same audio, it is not
 * throttled, and it is present in the {@code streamingData.formats} section of the player
 * response. Restricting the client to it means {@code TrackFormats.getBestFormat()} has a single
 * candidate to choose from.
 *
 * <p>The client is otherwise identical to {@link MWebWithThumbnail}: same player API payload, same
 * client name and version, and it keeps the thumbnail support, so it is subject to the same bot
 * detection as any other client.
 *
 * @author Arif Banai (arif-banai)
 */
public class LegacyFormatClient extends MWebWithThumbnail
{
    /**
     * The itag of the legacy progressive format.
     */
    public static final int LEGACY_ITAG = 18;

    /**
     * How long a player API response is reused, in milliseconds.
     *
     * <p>The player API is the slowest part of loading a track (about 1 to 2 seconds), and
     * youtube-source asks for it twice: once to read the metadata of a video and once more to
     * read the formats when the track starts playing. Both requests ask for the same video, so
     * the second one can be served from a cache.
     *
     * <p>Five minutes is short enough for the metadata to stay accurate and long enough to
     * cover the gap between a track being queued and it starting to play.
     */
    private static final long CACHE_TTL_MS = PlayerApiCache.DEFAULT_TTL_MS;

    /**
     * The maximum number of player API responses that are kept.
     */
    private static final int CACHE_MAX_ENTRIES = PlayerApiCache.DEFAULT_MAX_ENTRIES;

    /**
     * The cached player API responses, by video id.
     */
    private final PlayerApiCache responseCache = new PlayerApiCache();

    public LegacyFormatClient()
    {
        super();
    }

    public LegacyFormatClient(ClientOptions options)
    {
        super(options);
    }

    /**
     * Keeps only the legacy format; every other format is skipped without being reported as an
     * error, so the client simply offers a single candidate.
     */
    @Override
    protected boolean extractFormat(JsonBrowser formatJson, List<StreamFormat> formats, boolean isLive)
    {
        if (formatJson.get("itag").asLong(-1) != LEGACY_ITAG)
        {
            return true;
        }
        return super.extractFormat(formatJson, formats, isLive);
    }

    /**
     * Returns the player API response for a video, from the cache when it is still fresh.
     *
     * <p>Without this, every track costs two player API requests: one for the metadata when
     * the track is queued, and another one for the formats when it starts playing. With the
     * cache, the second one is free.
     *
     * @param source the source manager
     * @param httpInterface the http interface to use
     * @param videoId the id of the video
     * @param status the last playability status
     * @param validatePlayabilityStatus whether the playability status is validated
     * @return the player API response
     */
    @Override
    public JsonBrowser loadTrackInfoFromInnertube(YoutubeAudioSourceManager source,
                                                  com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface httpInterface,
                                                  String videoId,
                                                  Client.PlayabilityStatus status,
                                                  boolean validatePlayabilityStatus)
            throws dev.lavalink.youtube.CannotBeLoaded, java.io.IOException
    {
        String key = videoId + "|" + status + "|" + validatePlayabilityStatus;

        JsonBrowser cached = responseCache.get(key);
        if (cached != null)
        {
            return cached;
        }

        JsonBrowser json = super.loadTrackInfoFromInnertube(source, httpInterface, videoId, status,
                validatePlayabilityStatus);

        responseCache.put(key, json);

        return json;
    }

    /**
     * @return the number of player API requests that were served from the cache
     */
    public long getCacheHits()
    {
        return responseCache.getHits();
    }

    /**
     * @return the number of player API requests that had to be made
     */
    public long getCacheMisses()
    {
        return responseCache.getMisses();
    }

    /**
     * Marks regular videos as non-streams so that they are played through the static playback
     * path.
     *
     * <p><b>Why this exists:</b> the segmented playback path
     * ({@code YoutubeMpegStreamAudioTrack}) derives the duration and the position of the track
     * from the sequence headers of a live/segmented stream. A regular video has no such
     * headers, so it reported a duration of {@code 0} and a position of {@code 0}, which left the
     * "now playing" widget blank ({@code 00:00 / 00:00}).
     *
     * <p>Only videos with a known duration are switched over; a live stream (duration {@code 0})
     * keeps the segmented path it needs.
     */
    @Override
    public AudioTrack buildAudioTrack(YoutubeAudioSourceManager source, JsonBrowser json, String title,
            String author, long duration, String videoId, boolean isStream)
    {
        if (isStream && duration > 0)
        {
            isStream = false;
        }
        return super.buildAudioTrack(source, json, title, author, duration, videoId, isStream);
    }
}
