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
package com.jagrosh.jmusicbot.service;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.LibraryAudioSourceManager;
import com.jagrosh.jmusicbot.library.MusicLibrary;
import com.jagrosh.jmusicbot.library.MusicLibrary.LibraryTrack;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;

/**
 * Service for search-related operations.
 * Uses PlayerService for shared track operations (validation, queue addition).
 */
public class SearchService
{
    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);

    /**
     * How long a search result is kept, in milliseconds.
     *
     * <p>Searching costs a round trip to YouTube, and the same query is often repeated. The
     * cache turns a repeated search into an instant answer.
     */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /**
     * The maximum number of results that are kept in the cache.
     */
    private static final int CACHE_MAX_ENTRIES = 32;

    private final Bot bot;

    /**
     * The cached search results, by query.
     */
    private final Map<String, CachedSearch> cache = new ConcurrentHashMap<>();

    public SearchService(Bot bot)
    {
        this.bot = bot;
        LOG.info("SearchService initialized");
    }

    /**
     * Performs a search and handles the results.
     *
     * @param guild        The guild
     * @param member       The member requesting search
     * @param query        The search query
     * @param searchPrefix The search prefix (e.g., "ytsearch:" or "scsearch:")
     * @param channel      The text channel for request metadata
     * @param callback     The callback for handling results
     */
    public void search(Guild guild, Member member, String query, String searchPrefix,
                       TextChannel channel, SearchCallback callback)
    {
        LOG.debug("Search requested: guild={}, user={}, query=\"{}\", prefix={}",
                guild.getId(), member.getUser().getName(), query, searchPrefix);

        if (query == null || query.isEmpty())
        {
            LOG.debug("Search rejected: empty query");
            callback.onError("Please include a query.");
            return;
        }

        LOG.info("Executing search: guild={}, user={}, query=\"{}\"",
                guild.getId(), member.getUser().getName(), query);

        // The local library is searched first. A track that is already downloaded can be played
        // straight from disk, which is faster and does not need a stream at all.
        List<SearchResult> localResults = findLocalResults(query);

        CachedSearch cached = cache.get(searchPrefix + query);
        if (cached != null && !cached.isExpired())
        {
            LOG.debug("Search served from the cache: query=\"{}\"", query);
            callback.onSearchResults(toResults(cached.tracks(), localResults), formatSearchChoices(toResults(cached.tracks(), localResults), 5));
            return;
        }

        bot.getPlayerManager().loadItemOrdered(guild, searchPrefix + query,
                bot.getAudioLoadWrapper().wrap(searchPrefix + query, new SearchResultHandler(guild, member, query, channel, callback, localResults)));
    }

    /**
     * Looks a query up in the local music library.
     *
     * @param query the query
     * @return the matching library tracks
     */
    private List<SearchResult> findLocalResults(String query)
    {
        MusicLibrary library = bot.getMusicLibrary();
        if (library == null || library.isEmpty())
        {
            return List.of();
        }

        try
        {
            Optional<LibraryTrack> match = library.findTrack(query);
            if (match.isEmpty())
            {
                return List.of();
            }

            LibraryTrack libraryTrack = match.get();
            AudioTrack track = loadLocalTrack(query);
            if (track == null)
            {
                return List.of();
            }

            LOG.debug("Local library match for \"{}\": {}", query, libraryTrack.describe());
            return List.of(SearchResult.of(track,
                    libraryTrack.title().isEmpty() ? track.getInfo().title : libraryTrack.title(),
                    libraryTrack.artist(),
                    libraryTrack.artwork()));
        }
        catch (RuntimeException e)
        {
            LOG.debug("Local library lookup failed for \"{}\": {}", query, e.toString());
            return List.of();
        }
    }

    /**
     * Loads a track from the local library.
     *
     * <p>The library source is used directly, so that no other source is asked and the lookup
     * stays local.
     *
     * @param query the query
     * @return the track, or null when it could not be loaded
     */
    private AudioTrack loadLocalTrack(String query)
    {
        LibraryAudioSourceManager source = bot.getLibrarySource();
        if (source == null)
        {
            return null;
        }
        try
        {
            Object item = source.loadItem(bot.getPlayerManager(),
                    new com.sedmelluq.discord.lavaplayer.track.AudioReference(query, null));
            return item instanceof AudioTrack track ? track : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Merges the local results with the online ones, the local ones first.
     */
    private List<SearchResult> toResults(List<AudioTrack> tracks, List<SearchResult> localResults)
    {
        List<SearchResult> results = new ArrayList<>(localResults);
        for (AudioTrack track : tracks)
        {
            results.add(SearchResult.of(track, track.getInfo().title, track.getInfo().author,
                    artworkOf(track)));
        }
        return results;
    }

    /**
     * The thumbnail of a track, with a higher resolution when YouTube offers one.
     *
     * <p>youtube-source hands out the 320x180 variant. The 480x360 one is always available and
     * looks much better in an embed.
     *
     * @param track the track
     * @return the thumbnail url, may be empty
     */
    public static String artworkOf(AudioTrack track)
    {
        String artwork = track == null ? null : track.getInfo().artworkUrl;
        if (artwork == null || artwork.isBlank())
        {
            return "";
        }
        if (artwork.contains("/mqdefault."))
        {
            return artwork.replace("/mqdefault.", "/hqdefault.");
        }
        return artwork;
    }

    /**
     * Formats search result choices for display.
     *
     * @param results The results
     * @param limit   Maximum number of choices to return
     * @return Array of formatted choice strings
     */
    public String[] formatSearchChoices(List<SearchResult> results, int limit)
    {
        int count = Math.min(limit, results.size());
        String[] choices = new String[count];
        for (int i = 0; i < count; i++)
        {
            choices[i] = results.get(i).formatChoice(i + 1);
        }
        return choices;
    }

    /**
     * Builds one embed per result, each with the thumbnail of that result.
     *
     * @param results The results
     * @param limit   Maximum number of embeds to return
     * @param color   The accent colour
     * @return The embeds
     */
    public List<EmbedBuilder> buildResultEmbeds(List<SearchResult> results, int limit, java.awt.Color color)
    {
        int count = Math.min(limit, results.size());
        List<EmbedBuilder> embeds = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            embeds.add(results.get(i).buildEmbed(color));
        }
        return embeds;
    }

    /**
     * Callback interface for search results.
     */
    public interface SearchCallback
    {
        /**
         * Called when a single track is loaded.
         */
        void onTrackLoaded(AudioTrack track, int queuePosition, String formattedMessage);

        /**
         * Called when search results (playlist) are loaded.
         */
        void onSearchResults(List<SearchResult> results, String[] formattedChoices);

        /**
         * Called when no matches are found.
         */
        void onNoMatches(String query);

        /**
         * Called when loading fails.
         */
        void onLoadFailed(String errorMessage);

        /**
         * Called for general errors.
         */
        void onError(String message);
    }

    private class SearchResultHandler implements AudioLoadResultHandler
    {
        private final Guild guild;
        private final Member member;
        private final String query;
        private final TextChannel channel;
        private final SearchCallback callback;
        private final List<SearchResult> localResults;

        private SearchResultHandler(Guild guild, Member member, String query,
                                    TextChannel channel, SearchCallback callback,
                                    List<SearchResult> localResults)
        {
            this.guild = guild;
            this.member = member;
            this.query = query;
            this.channel = channel;
            this.callback = callback;
            this.localResults = localResults;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            LOG.debug("Search result - single track: guild={}, track=\"{}\"",
                    guild.getId(), track.getInfo().title);

            MusicService musicService = bot.getMusicService();

            // Use shared track operations from MusicService
            MusicService.TrackAddResult result = musicService.addTrackToQueue(guild, member, track, query, channel);
            if (result == null)
            {
                LOG.warn("Search track rejected (too long): guild={}, track=\"{}\"",
                        guild.getId(), track.getInfo().title);
                callback.onLoadFailed(musicService.formatTooLongError(track));
                return;
            }

            LOG.info("Search result added to queue: guild={}, track=\"{}\", position={}",
                    guild.getId(), track.getInfo().title, result.position);
            callback.onTrackLoaded(track, result.position, result.formattedMessage);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            LOG.debug("Search results loaded: guild={}, query=\"{}\", results={}",
                    guild.getId(), query, playlist.getTracks().size());

            List<AudioTrack> tracks = playlist.getTracks();
            storeInCache(tracks);

            List<SearchResult> results = toResults(tracks, localResults);
            String[] choices = formatSearchChoices(results, 5);
            callback.onSearchResults(results, choices);
        }

        @Override
        public void noMatches()
        {
            LOG.debug("Search - no matches: guild={}, query=\"{}\"", guild.getId(), query);
            callback.onNoMatches(query);
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if (throwable.severity == Severity.COMMON)
            {
                LOG.warn("Search load failed (common): guild={}, query=\"{}\", error={}",
                        guild.getId(), query, throwable.getMessage());
                callback.onLoadFailed("Error loading: " + throwable.getMessage());
            }
            else
            {
                LOG.error("Search load failed (severe): guild={}, query=\"{}\"",
                        guild.getId(), query, throwable);
                callback.onLoadFailed("Error loading track.");
            }
        }

        /**
         * Keeps the results so that the same query does not have to be searched twice.
         */
        private void storeInCache(List<AudioTrack> tracks)
        {
            if (cache.size() >= CACHE_MAX_ENTRIES)
            {
                cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                if (cache.size() >= CACHE_MAX_ENTRIES)
                {
                    cache.clear();
                }
            }
            cache.put(query, new CachedSearch(tracks, System.currentTimeMillis() + CACHE_TTL_MS));
        }
    }

    /**
     * A cached search result.
     *
     * @param tracks the tracks
     * @param expiresAt the timestamp at which the entry expires
     */
    private record CachedSearch(List<AudioTrack> tracks, long expiresAt)
    {
        private boolean isExpired()
        {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
