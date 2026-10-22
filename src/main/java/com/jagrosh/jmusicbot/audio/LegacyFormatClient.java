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
