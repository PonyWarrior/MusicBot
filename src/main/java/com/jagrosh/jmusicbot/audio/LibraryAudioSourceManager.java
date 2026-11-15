package com.jagrosh.jmusicbot.audio;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import com.jagrosh.jmusicbot.library.MusicLibrary;
import com.jagrosh.jmusicbot.library.MusicLibrary.LibraryTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An audio source that resolves queries against the local music library.
 *
 * <p>It is registered ahead of the online sources, so every play, play-next or playlist request
 * is first looked up in the library. When a track matches, the local file is played; when it does
 * not, the source returns nothing and the request continues with the next source, which is the
 * usual online search.
 *
 * <p>Two kinds of identifiers are handled:
 * <ul>
 *   <li>an absolute path to a file inside the library, which is played directly;</li>
 *   <li>any other text, which is treated as a search query for the library.</li>
 * </ul>
 */
public class LibraryAudioSourceManager implements AudioSourceManager
{
    private static final Logger LOG = LoggerFactory.getLogger(LibraryAudioSourceManager.class);

    private final MusicLibrary library;
    private final LocalAudioSourceManager localSource = new LocalAudioSourceManager();

    /**
     * @param library the local music library
     */
    public LibraryAudioSourceManager(MusicLibrary library)
    {
        this.library = library;
    }

    @Override
    public String getSourceName()
    {
        return "library";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
    {
        if (reference == null || reference.identifier == null || reference.identifier.isBlank())
        {
            return null;
        }

        Optional<LibraryTrack> track = resolve(reference.identifier);
        if (track.isEmpty())
        {
            return null;
        }

        LOG.debug("Playing {} from the local library", track.get().path().getFileName());
        return localSource.loadItem(manager,
                new AudioReference(track.get().path().toAbsolutePath().toString(), track.get().title()));
    }

    /**
     * Resolves an identifier to a track in the library.
     *
     * @param identifier the identifier, either a path or a search query
     * @return the track, or empty when the library has no match
     */
    private Optional<LibraryTrack> resolve(String identifier)
    {
        // Anything that looks like a url is left to the online sources. This has to be checked
        // before the identifier is turned into a path: on Windows a url is not a valid path,
        // because of the colon in the scheme.
        String lower = identifier.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://"))
        {
            return Optional.empty();
        }

        // An absolute path to a file is played as it is.
        try
        {
            Path direct = Path.of(identifier);
            if (direct.isAbsolute() && Files.isRegularFile(direct))
            {
                Path absolute = direct.toAbsolutePath();
                return library.getTracks().stream()
                        .filter(track -> track.path().toAbsolutePath().equals(absolute))
                        .findFirst();
            }
        }
        catch (InvalidPathException e)
        {
            // Not a usable path on this system, fall through to the search.
        }

        return library.findTrack(identifier);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track)
    {
        return localSource.isTrackEncodable(track);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException
    {
        localSource.encodeTrack(track, output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
    {
        return localSource.decodeTrack(trackInfo, input);
    }

    @Override
    public void shutdown()
    {
        // Nothing to shut down
    }
}
