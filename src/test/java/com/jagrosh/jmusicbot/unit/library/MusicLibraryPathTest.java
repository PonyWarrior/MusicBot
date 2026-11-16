package com.jagrosh.jmusicbot.unit.library;

import com.jagrosh.jmusicbot.library.MusicLibrary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the folder of the local music library.
 *
 * <p>The folder has to be absolute. It is handed to yt-dlp, which runs with its own working
 * directory, so a relative path would be resolved against that directory and the downloads
 * would land inside the yt-dlp checkout instead of the library.
 */
@DisplayName("Music library folder")
class MusicLibraryPathTest
{
    @Test
    @DisplayName("A relative folder is stored as an absolute path")
    void relativeFolderBecomesAbsolute()
    {
        MusicLibrary library = new MusicLibrary(Paths.get("Music"));

        assertTrue(library.getFolder().isAbsolute(),
                "The library folder must be absolute, was " + library.getFolder());
        assertEquals(Paths.get("Music").toAbsolutePath().normalize(), library.getFolder());
    }

    @Test
    @DisplayName("An absolute folder stays the same")
    void absoluteFolderIsKept(@TempDir Path folder) throws Exception
    {
        MusicLibrary library = new MusicLibrary(folder);

        assertEquals(folder.toAbsolutePath().normalize(), library.getFolder());
    }

    @Test
    @DisplayName("A folder with redundant segments is normalised")
    void folderIsNormalised()
    {
        MusicLibrary library = new MusicLibrary(Paths.get("Music").resolve("..").resolve("Music"));

        assertEquals(Paths.get("Music").toAbsolutePath().normalize(), library.getFolder());
        assertFalse(library.getFolder().toString().contains(".."));
    }

    @Test
    @DisplayName("A track placed in the folder is found again")
    void trackIsFound(@TempDir Path folder) throws Exception
    {
        Path file = folder.resolve("Some Artist - Some Song.flac");
        Files.write(file, new byte[] { 'f', 'L', 'a', 'C' });

        MusicLibrary library = new MusicLibrary(folder);

        assertEquals(1, library.listTracks().size());
        assertTrue(library.findTrack("Some Song").isPresent(),
                "A track in the library folder must be found");
    }

    @Test
    @DisplayName("Part of a word does not match a track")
    void partialWordDoesNotMatch(@TempDir Path folder) throws Exception
    {
        Files.write(folder.resolve("Party Anthem.flac"), new byte[] { 'f', 'L', 'a', 'C' });
        MusicLibrary library = new MusicLibrary(folder);

        assertFalse(library.findTrack("art").isPresent());
        assertFalse(library.findTrack("a").isPresent());
        assertTrue(library.findTrack("party").isPresent());
        assertTrue(library.findTrack("anthem").isPresent());
        assertTrue(library.findTrack("anthem party").isPresent());
    }

    @Test
    @DisplayName("Whole-word phrase checks")
    void containsPhraseMatchesWholeWords()
    {
        assertTrue(MusicLibrary.containsPhrase("some artist some song", "some song"));
        assertFalse(MusicLibrary.containsPhrase("some artist some songs", "some song"));
        assertFalse(MusicLibrary.containsPhrase("anything", ""));
    }
}
