package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.audio.LegacyFormatClient;
import com.jagrosh.jmusicbot.audio.PlayerApiCache;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the player API response cache.
 *
 * <p>Without the cache, every track costs two player API requests: one for the metadata when
 * the track is queued and another one for the formats when it starts playing.
 */
@DisplayName("Player API response cache")
class PlayerApiCacheTest
{
    @Nested
    @DisplayName("Cache behaviour")
    class CacheBehaviour
    {
        @Test
        @DisplayName("A stored response is returned again")
        void storedResponseIsReturned() throws Exception
        {
            PlayerApiCache cache = new PlayerApiCache();
            JsonBrowser json = JsonBrowser.parse("{\"videoDetails\":{\"videoId\":\"abc\"}}");

            assertNull(cache.get("abc"));
            cache.put("abc", json);
            assertSame(json, cache.get("abc"));
        }

        @Test
        @DisplayName("A different key is not served from the cache")
        void differentKeyIsNotServed() throws Exception
        {
            PlayerApiCache cache = new PlayerApiCache();
            JsonBrowser json = JsonBrowser.parse("{}");
            cache.put("abc", json);

            assertNull(cache.get("def"));
        }

        @Test
        @DisplayName("An expired entry is not served")
        void expiredEntryIsNotServed() throws Exception
        {
            PlayerApiCache cache = new PlayerApiCache(50, 64);
            JsonBrowser json = JsonBrowser.parse("{}");
            cache.put("abc", json);

            assertSame(json, cache.get("abc"));
            Thread.sleep(120);
            assertNull(cache.get("abc"), "An expired entry must not be served");
        }

        @Test
        @DisplayName("The hit and miss counters are accurate")
        void countersAreAccurate() throws Exception
        {
            PlayerApiCache cache = new PlayerApiCache();
            JsonBrowser json = JsonBrowser.parse("{}");

            cache.get("abc");        // miss
            cache.put("abc", json);
            cache.get("abc");        // hit
            cache.get("abc");        // hit
            cache.get("other");      // miss

            assertEquals(2, cache.getHits());
            assertEquals(2, cache.getMisses());
        }

        @Test
        @DisplayName("The cache is bounded")
        void cacheIsBounded() throws Exception
        {
            PlayerApiCache cache = new PlayerApiCache(60_000, 8);
            for (int i = 0; i < 40; i++)
            {
                cache.put("key" + i, JsonBrowser.parse("{}"));
            }
            assertTrue(cache.size() <= 8, "The cache must not grow past its limit, was " + cache.size());
        }
    }

    @Nested
    @DisplayName("Client integration")
    class ClientIntegration
    {
        @Test
        @DisplayName("The client starts with an empty cache")
        void clientStartsEmpty()
        {
            LegacyFormatClient client = new LegacyFormatClient();
            assertEquals(0, client.getCacheHits());
            assertEquals(0, client.getCacheMisses());
        }
    }
}
