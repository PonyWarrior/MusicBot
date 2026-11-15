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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
     * The parsed info sidecars, by path.
     */
    private final Map<Path, JsonNode> metadataCache = new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param folder the folder holding the downloaded tracks
     */
    public MusicLibrary(Path folder)
    {
        // The folder is stored as an absolute path. The bot passes this path to yt-dlp, which
        // runs with its own working directory, so a relative path would be resolved against
        // that directory instead of the bot's.
        this.folder = folder.toAbsolutePath().normalize();
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
            processPendingDeletions();
            return true;
        }
        catch (IOException e)
        {
            LOG.error("Could not create the music library folder {}: {}", folder.toAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * The file holding paths that could not be deleted yet, for example because the track was
     * still being played. It has no audio extension, so it is not listed as a track.
     */
    private static final String PENDING_DELETIONS_FILE = ".pending-deletions.txt";

    private final Object deletionLock = new Object();

    /**
     * Remembers a file to delete later, when it is no longer in use.
     *
     * @param file the file to delete
     */
    public void scheduleDeletion(Path file)
    {
        if(file == null)
            return;
        Path absolute = file.toAbsolutePath().normalize();
        synchronized(deletionLock)
        {
            try
            {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve(PENDING_DELETIONS_FILE),
                        absolute + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                LOG.info("Scheduled {} for deletion", absolute);
            }
            catch(IOException e)
            {
                LOG.warn("Could not schedule {} for deletion: {}", absolute, e.getMessage());
            }
        }
    }

    /**
     * Takes a file off the deletion list, because it is wanted again (for example it was
     * favorited again before the deletion could happen).
     *
     * @param file the file to keep
     */
    public void cancelDeletion(Path file)
    {
        Path pending = folder.resolve(PENDING_DELETIONS_FILE);
        if(file == null || !Files.isRegularFile(pending))
            return;
        Path absolute = file.toAbsolutePath().normalize();
        synchronized(deletionLock)
        {
            try
            {
                List<String> remaining = new ArrayList<>();
                boolean changed = false;
                for(String line : Files.readAllLines(pending, StandardCharsets.UTF_8))
                {
                    Path listed = parsePath(line.trim());
                    if(listed != null && listed.toAbsolutePath().normalize().equals(absolute))
                        changed = true;
                    else if(!line.isBlank())
                        remaining.add(line.trim());
                }
                if(!changed)
                    return;
                if(remaining.isEmpty())
                    Files.deleteIfExists(pending);
                else
                    Files.write(pending, remaining, StandardCharsets.UTF_8);
                LOG.info("{} is wanted again, its deletion was cancelled", absolute);
            }
            catch(IOException e)
            {
                LOG.warn("Could not cancel the deletion of {}: {}", absolute, e.getMessage());
            }
        }
    }

    /**
     * @param file a file
     * @return true when the file is inside the library folder (and is not the folder itself)
     */
    public boolean contains(Path file)
    {
        if(file == null)
            return false;
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(folder) && !absolute.equals(folder);
    }

    private static Path parsePath(String value)
    {
        if(value == null || value.isEmpty())
            return null;
        try
        {
            return Path.of(value);
        }
        catch(RuntimeException e)
        {
            return null;
        }
    }

    // ========== Downloads manifest ==========

    /**
     * Lists the files the bot downloaded itself, relative to the library folder. Only these
     * may be deleted when a track is unfavorited: the folder can also hold the user's own music.
     * It has no audio extension, so it is not listed as a track.
     */
    private static final String DOWNLOADS_FILE = ".downloads.txt";

    private final Object downloadsLock = new Object();
    private Set<Path> downloads;

    /**
     * Remembers that the bot downloaded a file.
     *
     * @param file the downloaded file
     */
    public void recordDownload(Path file)
    {
        if(!contains(file))
            return;
        synchronized(downloadsLock)
        {
            if(loadDownloads().add(file.toAbsolutePath().normalize()))
                saveDownloads();
        }
    }

    /**
     * Forgets a downloaded file, because it was deleted.
     *
     * @param file the file
     */
    public void forgetDownload(Path file)
    {
        if(file == null)
            return;
        synchronized(downloadsLock)
        {
            if(loadDownloads().remove(file.toAbsolutePath().normalize()))
                saveDownloads();
        }
    }

    /**
     * @param file a file
     * @return true when the bot downloaded this file, so it may delete it again
     */
    public boolean isDownloaded(Path file)
    {
        if(!contains(file))
            return false;
        synchronized(downloadsLock)
        {
            return loadDownloads().contains(file.toAbsolutePath().normalize());
        }
    }

    private Set<Path> loadDownloads()
    {
        if(downloads != null)
            return downloads;

        Set<Path> loaded = new HashSet<>();
        Path manifest = folder.resolve(DOWNLOADS_FILE);
        if(Files.isRegularFile(manifest))
        {
            try
            {
                for(String line : Files.readAllLines(manifest, StandardCharsets.UTF_8))
                {
                    Path relative = parsePath(line.trim());
                    if(relative == null)
                        continue;
                    Path absolute = folder.resolve(relative).normalize();
                    if(contains(absolute))
                        loaded.add(absolute);
                }
            }
            catch(IOException e)
            {
                LOG.warn("Could not read the downloads manifest: {}", e.getMessage());
            }
            downloads = loaded;
        }
        else
        {
            // A library from before the manifest existed: yt-dlp writes an info sidecar next
            // to every download, so a track that has one was downloaded by the bot.
            for(Path track : listTracks())
                if(sidecarOf(track) != null)
                    loaded.add(track.toAbsolutePath().normalize());
            downloads = loaded;
            if(!loaded.isEmpty())
                saveDownloads();
        }
        return downloads;
    }

    private void saveDownloads()
    {
        try
        {
            Files.createDirectories(folder);
            List<String> lines = downloads.stream()
                    .map(path -> folder.relativize(path).toString())
                    .sorted()
                    .toList();
            Path manifest = folder.resolve(DOWNLOADS_FILE);
            Path temp = folder.resolve(DOWNLOADS_FILE + ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch(IOException e)
        {
            LOG.warn("Could not write the downloads manifest: {}", e.getMessage());
        }
    }

    /**
     * Deletes the files that were scheduled earlier. Files that are still in use stay on the
     * list and are retried later.
     */
    public void processPendingDeletions()
    {
        Path pending = folder.resolve(PENDING_DELETIONS_FILE);
        if(!Files.isRegularFile(pending))
            return;

        synchronized(deletionLock)
        {
            List<String> remaining = new ArrayList<>();
            List<String> lines;
            try
            {
                lines = Files.readAllLines(pending, StandardCharsets.UTF_8);
            }
            catch(IOException e)
            {
                return;
            }

            for(String line : lines)
            {
                String value = line.trim();
                Path file = parsePath(value);
                if(file == null)
                    continue;
                // The list is a plain text file; never follow it outside the library.
                if(!contains(file))
                {
                    LOG.warn("Ignoring pending deletion outside the music library: {}", value);
                    continue;
                }

                try
                {
                    boolean deleted = Files.deleteIfExists(file);
                    Path sidecar = sidecarOf(file);
                    if(sidecar != null)
                        Files.deleteIfExists(sidecar);
                    if(!deleted && Files.exists(file))
                        remaining.add(value);
                    else
                    {
                        forgetDownload(file);
                        LOG.info("Deleted pending file {}", file);
                    }
                }
                catch(IOException e)
                {
                    remaining.add(value);
                }
            }

            try
            {
                if(remaining.isEmpty())
                    Files.deleteIfExists(pending);
                else
                    Files.write(pending, remaining, StandardCharsets.UTF_8);
            }
            catch(IOException e)
            {
                LOG.warn("Could not update the pending deletions file: {}", e.getMessage());
            }
        }
    }

    /**
     * @param file an audio file
     * @return the yt-dlp info sidecar of the file, or null when there is none
     */
    public static Path sidecarOf(Path file)
    {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if(dot < 0)
            return null;
        Path sidecar = file.resolveSibling(name.substring(0, dot) + ".info.json");
        return Files.isRegularFile(sidecar) ? sidecar : null;
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
        catch (IOException | java.io.UncheckedIOException e)
        {
            // Files.walk reports a failure in the middle of the walk as an unchecked exception.
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
    /**
     * Finds the downloaded file whose source url is the given one.
     *
     * <p>yt-dlp records the page it downloaded from as {@code webpage_url}, which is the
     * YouTube url of a track. It lets the bot map a favorited track back to its library file.
     *
     * @param url the source url
     * @return the matching file, if any
     */
    public Optional<Path> findBySourceUrl(String url)
    {
        if (url == null || url.isBlank())
            return Optional.empty();
        for (LibraryTrack track : getTracks())
        {
            if (url.equals(metadata(track.path(), "webpage_url"))
                    || url.equals(metadata(track.path(), "original_url")))
            {
                return Optional.of(track.path());
            }
        }
        return Optional.empty();
    }

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
    /**
     * Reads a field out of the yt-dlp info sidecar of a track.
     *
     * <p>The sidecar is parsed as JSON, and the parsed form is cached, so that a track with
     * several fields only reads and parses its sidecar once.
     *
     * @param track the track
     * @param field the field to read
     * @return the value, or an empty string when there is no sidecar or no such field
     */
    private String metadata(Path track, String field)
    {
        JsonNode root = metadataOf(track);
        if (root == null)
        {
            return "";
        }
        JsonNode node = root.get(field);
        if (node == null || node.isNull())
        {
            return "";
        }
        return node.isValueNode() ? node.asText() : "";
    }

    /**
     * The parsed info sidecar of a track.
     *
     * @param track the track
     * @return the parsed sidecar, or null when there is none
     */
    private JsonNode metadataOf(Path track)
    {
        Path sidecar = infoSidecar(track);
        if (sidecar == null)
        {
            return null;
        }

        return metadataCache.computeIfAbsent(sidecar, path ->
        {
            try
            {
                return MAPPER.readTree(Files.readAllBytes(path));
            }
            catch (IOException e)
            {
                return null;
            }
        });
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
            String artist = uploaderOf(path);
            String artwork = artworkOf(path);
            tracks.add(new LibraryTrack(path, title, artist, metadata(path, "album"), artwork));
        }
        return tracks;
    }

    /**
     * The uploader of a downloaded track.
     *
     * <p>yt-dlp writes the channel that uploaded the video as {@code uploader}. The
     * {@code artist} field is often empty for user generated content, so it is only used as a
     * fallback.
     *
     * @param track the track
     * @return the uploader, or an empty string
     */
    private String uploaderOf(Path track)
    {
        String uploader = metadata(track, "uploader");
        if (!uploader.isEmpty())
        {
            return uploader;
        }
        uploader = metadata(track, "artist");
        if (!uploader.isEmpty())
        {
            return uploader;
        }
        return metadata(track, "channel");
    }

    /**
     * The artwork of a downloaded track, taken from the thumbnail url that yt-dlp recorded.
     *
     * @param track the track
     * @return the artwork url, or an empty string
     */
    private String artworkOf(Path track)
    {
        String thumbnail = metadata(track, "thumbnail");
        if (!thumbnail.isEmpty())
        {
            return thumbnail;
        }
        return metadata(track, "thumbnails");
    }

    /**
     * A track in the local library.
     *
     * @param path the file
     * @param title the title from the metadata
     * @param artist the uploader or artist from the metadata
     * @param album the album from the metadata
     * @param artwork the artwork url from the metadata
     */
    public record LibraryTrack(Path path, String title, String artist, String album, String artwork)
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
