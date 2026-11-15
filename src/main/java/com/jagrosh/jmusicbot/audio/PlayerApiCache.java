package com.jagrosh.jmusicbot.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

/**
 * A short lived cache for YouTube player API responses.
 *
 * <p>The player API is the slowest part of loading a track, and youtube-source asks for it
 * twice: once to read the metadata of a video when a track is queued, and once more to read
 * the formats when the track starts playing. Both requests ask for the same video, so the
 * second one can be served from this cache.
 *
 * <p>The entries expire, because the format urls in a response carry a signature that
 * eventually stops being valid.
 */
public class PlayerApiCache
{
    /**
     * The default time an entry is kept, in milliseconds.
     */
    public static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    /**
     * The default maximum number of entries.
     */
    public static final int DEFAULT_MAX_ENTRIES = 64;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /**
     * Creates a cache with the default settings.
     */
    public PlayerApiCache()
    {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_ENTRIES);
    }

    /**
     * @param ttlMs how long an entry is kept, in milliseconds
     * @param maxEntries the maximum number of entries
     */
    public PlayerApiCache(long ttlMs, int maxEntries)
    {
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
    }

    /**
     * Returns the cached response for a key, if it is still fresh.
     *
     * @param key the key
     * @return the response, or null when there is no fresh entry
     */
    public JsonBrowser get(String key)
    {
        Entry entry = entries.get(key);
        if (entry == null)
        {
            misses.incrementAndGet();
            return null;
        }
        if (entry.isExpired())
        {
            entries.remove(key, entry);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.json;
    }

    /**
     * Stores a response.
     *
     * @param key the key
     * @param json the response
     */
    public void put(String key, JsonBrowser json)
    {
        if (entries.size() >= maxEntries)
        {
            entries.entrySet().removeIf(e -> e.getValue().isExpired());
            if (entries.size() >= maxEntries)
            {
                entries.clear();
            }
        }
        entries.put(key, new Entry(json, System.currentTimeMillis() + ttlMs));
    }

    /**
     * @return the number of requests that were served from the cache
     */
    public long getHits()
    {
        return hits.get();
    }

    /**
     * @return the number of requests that were not served from the cache
     */
    public long getMisses()
    {
        return misses.get();
    }

    /**
     * @return the number of entries currently held
     */
    public int size()
    {
        return entries.size();
    }

    /**
     * A cached response.
     *
     * @param json the response
     * @param expiresAt the timestamp at which the entry expires
     */
    private static final class Entry
    {
        private final JsonBrowser json;
        private final long expiresAt;

        private Entry(JsonBrowser json, long expiresAt)
        {
            this.json = json;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired()
        {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
