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
package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.audio.LegacyFormatClient;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * youtube-source asks the player API for a video twice with different arguments: once without
 * validation for the metadata and once with validation for the formats. The second request must
 * be answered from the cache.
 */
@DisplayName("Legacy format client cache")
class LegacyFormatClientCacheTest
{
    /** A client whose player API answers are canned and counted. */
    private static final class CountingClient extends LegacyFormatClient
    {
        private final AtomicInteger requests = new AtomicInteger();
        private final String answer;

        private CountingClient(String answer)
        {
            this.answer = answer;
        }

        @Override
        protected JsonBrowser fetchTrackInfo(YoutubeAudioSourceManager source, HttpInterface httpInterface,
                                             String videoId, Client.PlayabilityStatus status,
                                             boolean validatePlayabilityStatus) throws IOException
        {
            requests.incrementAndGet();
            return JsonBrowser.parse(answer);
        }
    }

    @Test
    @DisplayName("the formats request reuses the metadata request")
    void formatsRequestReusesMetadataRequest() throws Throwable
    {
        CountingClient client = new CountingClient("{\"playabilityStatus\":{\"status\":\"OK\"}}");

        JsonBrowser metadata = client.loadTrackInfoFromInnertube(null, null, "abc", null, false);
        JsonBrowser formats = client.loadTrackInfoFromInnertube(null, null, "abc", null, true);

        assertSame(metadata, formats);
        assertEquals(1, client.requests.get());
        assertEquals(1, client.getCacheHits());
    }

    @Test
    @DisplayName("another video is asked for separately")
    void anotherVideoIsNotServed() throws Throwable
    {
        CountingClient client = new CountingClient("{\"playabilityStatus\":{\"status\":\"OK\"}}");

        client.loadTrackInfoFromInnertube(null, null, "abc", null, false);
        client.loadTrackInfoFromInnertube(null, null, "def", null, false);

        assertEquals(2, client.requests.get());
    }

    @Test
    @DisplayName("an unplayable answer is not kept")
    void unplayableAnswerIsNotKept() throws Throwable
    {
        CountingClient client = new CountingClient("{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}}");

        client.loadTrackInfoFromInnertube(null, null, "abc", null, false);
        client.loadTrackInfoFromInnertube(null, null, "abc", null, true);

        assertEquals(2, client.requests.get());
    }
}
