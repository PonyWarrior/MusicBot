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
package com.jagrosh.jmusicbot.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local music library of downloaded tracks.
 *
 * <p>The library is a folder of audio files. When a track is downloaded with yt-dlp, a
 * {@code .info.json} sidecar is written next to it; the sidecar carries the real title and
 * artist, which makes the library searchable by more than just the file name.
 *
 * @author Arif Banai (arif-banai)
 */
public class MusicLibrary
{
    private static final Logger LOG = LoggerFactory.getLogger(MusicLibrary.class);

    private static final List<String> AUDIO_EXTENSIONS = List.of(".flac", ".m4a", ".mp3", ".ogg", ".opus", ".wav", ".aac", ".wma");

    private final Path folder;

    /**
     * @param folder the folder holding the downloaded tracks
     */
    public MusicLibrary(Path folder)
    {
        this.folder = folder;
    }

    /**
     * @return the folder holding the downloaded tracks
     */
    public Path getFolder()
    {
        return folder;
    }

    /**
     * Creates the library folder if it does not exist yet.
     *
     * @return true if the folder is ready to be used
     */
    public boolean ensureReady()
    {
        try
        {
            Files.createDirectories(folder);
            return true;
        }
        catch (IOException e)
        {
            LOG.error("Could not create the music library folder {}: {}", folder.toAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * @return true if the library holds at least one track
     */
    public boolean isEmpty()
    {
        return listTracks().isEmpty();
    }

    /**
     * @return all audio files in the library
     */
    public List<Path> listTracks()
    {
        if (!Files.isDirectory(folder))
        {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder))
        {
            return files.filter(Files::isRegularFile)
                    .filter(this::isAudioFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        catch (IOException e)
        {
            LOG.error("Could not list the music library: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Searches the library for a track matching the given query.
     *
     * <p>The query is compared against the title and artist of every track. A query that matches
     * nothing returns an empty result, in which case the caller should fall back to an online
     * search.
     *
     * @param query the search query
     * @return the best matching track, if any
     */
    public Optional<Path> find(String query)
    {
        return findTrack(query).map(LibraryTrack::path);
    }

    /**
     * Searches the library for a track matching the given query.
     *
     * @param query the search query
     * @return the best matching track, if any
     */
    public Optional<LibraryTrack> findTrack(String query)
    {
        String needle = normalize(query);
        if (needle.isEmpty())
        {
            return Optional.empty();
        }

        List<LibraryTrack> tracks = getTracks();
        if (tracks.isEmpty())
        {
            return Optional.empty();
        }

        LibraryTrack best = null;
        int bestScore = 0;

        for (LibraryTrack track : tracks)
        {
            int score = score(track, needle);
            if (score > bestScore)
            {
                bestScore = score;
                best = track;
            }
        }

        return Optional.ofNullable(best);
    }

    /**
     * Scores how well a track matches the search query. A higher score is a better match.
     *
     * @param track the track to score
     * @param needle the normalized query
     * @return the score, {@code 0} when the track does not match
     */
    private int score(LibraryTrack track, String needle)
    {
        String name = normalize(stripExtension(track.path().getFileName().toString()));

        int score = 0;
        if (name.equals(needle))
        {
            score = 1000;
        }
        else if (name.startsWith(needle))
        {
            score = 800;
        }
        else if (name.contains(needle))
        {
            score = 600;
        }

        // Every word of the query has to appear somewhere in the track's name or metadata.
        String[] words = needle.split(" ");
        if (words.length > 1)
        {
            String haystack = name + " " + normalize(track.artist()) + " " + normalize(track.album());
            boolean all = true;
            for (String word : words)
            {
                if (!haystack.contains(word))
                {
                    all = false;
                    break;
                }
            }
            if (all)
            {
                score = Math.max(score, 500);
            }
        }

        // A title that also matches the artist is a better match than a title alone.
        String artist = normalize(track.artist());
        if (!artist.isEmpty() && artist.contains(needle))
        {
            score += 100;
        }

        return score;
    }

    /**
     * Reads a field out of the yt-dlp info sidecar of a track.
     *
     * @param track the track
     * @param field the field to read
     * @return the value, or an empty string when there is no sidecar or no such field
     */
    private String metadata(Path track, String field)
    {
        Path sidecar = infoSidecar(track);
        if (sidecar == null)
        {
            return "";
        }
        try
        {
            String json = Files.readString(sidecar, StandardCharsets.UTF_8);
            String key = "\"" + field + "\"";
            int index = json.indexOf(key);
            if (index < 0)
            {
                return "";
            }
            int colon = json.indexOf(':', index + key.length());
            if (colon < 0)
            {
                return "";
            }
            int start = json.indexOf('"', colon);
            if (start < 0)
            {
                return "";
            }
            int end = json.indexOf('"', start + 1);
            return end < 0 ? "" : json.substring(start + 1, end);
        }
        catch (IOException e)
        {
            return "";
        }
    }

    /**
     * @param track the track
     * @return the yt-dlp info sidecar of the track, or null when there is none
     */
    private Path infoSidecar(Path track)
    {
        String name = track.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
        {
            return null;
        }
        Path sidecar = track.resolveSibling(name.substring(0, dot) + ".info.json");
        return Files.isRegularFile(sidecar) ? sidecar : null;
    }

    /**
     * @param file the file
     * @return true if the file looks like an audio file
     */
    private boolean isAudioFile(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".info.json"))
        {
            return false;
        }
        return AUDIO_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * @param name a file name
     * @return the name without its extension
     */
    private static String stripExtension(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Normalises a string for comparison: lower case, without accents, without punctuation and
     * with single spaces.
     *
     * @param value the value to normalise
     * @return the normalised value
     */
    public static String normalize(String value)
    {
        if (value == null)
        {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String withoutAccents = decomposed.replaceAll("\\p{M}", "");
        String withoutPunctuation = withoutAccents.replaceAll("[^\\p{Alnum}\\s]", " ");
        return withoutPunctuation.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * @return the tracks in the library, with their metadata
     */
    public List<LibraryTrack> getTracks()
    {
        List<LibraryTrack> tracks = new ArrayList<>();
        for (Path path : listTracks())
        {
            String title = metadata(path, "title");
            if (title.isEmpty())
            {
                // Without metadata the file name is the best title available.
                title = stripExtension(path.getFileName().toString());
            }
            tracks.add(new LibraryTrack(path, title, metadata(path, "artist"), metadata(path, "album")));
        }
        return tracks;
    }

    /**
     * A track in the local library.
     *
     * @param path the file
     * @param title the title from the metadata, or the file name
     * @param artist the artist from the metadata
     * @param album the album from the metadata
     */
    public record LibraryTrack(Path path, String title, String artist, String album)
    {
        /**
         * @return a human readable description of the track
         */
        public String describe()
        {
            if (!artist.isEmpty() && !title.isEmpty())
            {
                return artist + " - " + title;
            }
            return !title.isEmpty() ? title : path.getFileName().toString();
        }
    }
}
