package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.audio.LibraryAudioSourceManager;
import com.jagrosh.jmusicbot.library.MusicLibrary;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the audio source that resolves queries against the local music library.
 *
 * <p>The most important case is a url: it has to be left to the online sources without
 * blowing up. On Windows a url is not a valid path, because of the colon in the scheme, so
 * turning the identifier into a path before checking the scheme fails with an
 * {@code InvalidPathException}.
 */
@DisplayName("Library audio source")
class LibraryAudioSourceManagerTest
{
    @Nested
    @DisplayName("Url identifiers")
    class UrlIdentifiers
    {
        @Test
        @DisplayName("A youtube url is left to the online sources")
        void youtubeUrlIsLeftAlone()
        {
            MusicLibrary library = new MusicLibrary(Path.of("."));
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            assertNull(source.loadItem(manager,
                    new AudioReference("https://youtu.be/bcdefghijkl?list=PLabcdefghijklmnopqrstuvwxyz012345", null)),
                    "A url must not be resolved by the library");
        }

        @Test
        @DisplayName("A watch url is left to the online sources")
        void watchUrlIsLeftAlone()
        {
            MusicLibrary library = new MusicLibrary(Path.of("."));
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            assertNull(source.loadItem(manager,
                    new AudioReference("https://www.youtube.com/watch?v=abcdefghijk", null)),
                    "A url must not be resolved by the library");
        }

        @Test
        @DisplayName("An http url is left to the online sources")
        void httpUrlIsLeftAlone()
        {
            MusicLibrary library = new MusicLibrary(Path.of("."));
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            assertNull(source.loadItem(manager,
                    new AudioReference("http://example.com/song.mp3", null)),
                    "A url must not be resolved by the library");
        }
    }

    @Nested
    @DisplayName("Other identifiers")
    class OtherIdentifiers
    {
        @Test
        @DisplayName("An empty identifier is ignored")
        void emptyIdentifierIsIgnored()
        {
            MusicLibrary library = new MusicLibrary(Path.of("."));
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            assertNull(source.loadItem(manager, new AudioReference("", null)));
            assertNull(source.loadItem(manager, new AudioReference("   ", null)));
            assertNull(source.loadItem(manager, new AudioReference(null, null)));
        }

        @Test
        @DisplayName("A query that is not in the library is ignored")
        void unknownQueryIsIgnored()
        {
            MusicLibrary library = new MusicLibrary(Path.of("."));
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            assertNull(source.loadItem(manager,
                    new AudioReference("a song that is definitely not in the library", null)));
        }
    }

    @Nested
    @DisplayName("Local files")
    class LocalFiles
    {
        @Test
        @DisplayName("A file in the library is played from disk")
        void fileInLibraryIsPlayed(@TempDir Path folder) throws Exception
        {
            // A real, very short flac file, so that the load succeeds.
            Path file = folder.resolve("Some Artist - Some Song.flac");
            try (var in = getClass().getResourceAsStream("/tiny-silence.flac"))
            {
                assertNotNull(in, "The test resource tiny-silence.flac should be on the classpath");
                java.nio.file.Files.copy(in, file);
            }

            MusicLibrary library = new MusicLibrary(folder);
            LibraryAudioSourceManager source = new LibraryAudioSourceManager(library);
            AudioPlayerManager manager = new DefaultAudioPlayerManager();

            var item = source.loadItem(manager, new AudioReference("Some Song", null));
            assertNotNull(item, "The library source should have resolved the file");
            assertEquals("Some Artist - Some Song", ((com.sedmelluq.discord.lavaplayer.track.AudioTrack) item)
                    .getInfo().title, "The title should come from the library metadata");
        }
    }
}
