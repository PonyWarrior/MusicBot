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
package com.jagrosh.jmusicbot.unit.library;

import com.jagrosh.jmusicbot.library.MusicLibrary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MusicLibrary Deferred Deletion Tests")
class MusicLibraryDeletionTest
{
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the folder listing is reused until the bot adds or removes a file")
    void listingIsReusedUntilTheLibraryChanges() throws IOException
    {
        MusicLibrary library = new MusicLibrary(tempDir);
        Path first = Files.writeString(tempDir.resolve("first.flac"), "audio");
        assertEquals(1, library.listTracks().size());

        // Copied in behind the bot's back: seen once the short-lived listing expires.
        Path second = Files.writeString(tempDir.resolve("second.flac"), "audio");
        assertEquals(1, library.listTracks().size());
        library.refresh();
        assertEquals(2, library.listTracks().size());

        // The bot's own changes are visible at once.
        Path third = Files.writeString(tempDir.resolve("third.flac"), "audio");
        library.recordDownload(third);
        assertEquals(3, library.listTracks().size());

        Files.delete(first);
        library.forgetDownload(first);
        assertEquals(java.util.List.of(second, third), library.listTracks());
    }

    @Test
    @DisplayName("a scheduled file and its sidecar are deleted")
    void scheduledFileIsDeleted() throws IOException
    {
        Path song = tempDir.resolve("song.flac");
        Path sidecar = tempDir.resolve("song.info.json");
        Files.writeString(song, "audio");
        Files.writeString(sidecar, "{}");

        MusicLibrary library = new MusicLibrary(tempDir);
        library.ensureReady();
        library.scheduleDeletion(song);
        assertTrue(Files.exists(song), "the file is still there until processing");

        library.processPendingDeletions();

        assertFalse(Files.exists(song));
        assertFalse(Files.exists(sidecar));
        assertFalse(Files.exists(tempDir.resolve(".pending-deletions.txt")));
    }

    @Test
    @DisplayName("ensureReady processes a pending deletion left by a previous run")
    void ensureReadyProcessesPendingDeletion() throws IOException
    {
        Path song = tempDir.resolve("pending.flac");
        Files.writeString(song, "audio");
        Files.writeString(tempDir.resolve(".pending-deletions.txt"),
                song.toAbsolutePath().normalize() + System.lineSeparator(), StandardCharsets.UTF_8);

        MusicLibrary library = new MusicLibrary(tempDir);
        assertTrue(library.ensureReady());

        assertFalse(Files.exists(song));
        assertFalse(Files.exists(tempDir.resolve(".pending-deletions.txt")));
    }

    @Test
    @DisplayName("a pending deletion outside the library is ignored")
    void pendingDeletionOutsideLibraryIsIgnored(@TempDir Path elsewhere) throws IOException
    {
        Path outside = elsewhere.resolve("keep.flac");
        Files.writeString(outside, "audio");
        Files.writeString(tempDir.resolve(".pending-deletions.txt"),
                outside.toAbsolutePath().normalize() + System.lineSeparator(), StandardCharsets.UTF_8);

        new MusicLibrary(tempDir).processPendingDeletions();

        assertTrue(Files.exists(outside), "a file outside the library must never be deleted");
        assertFalse(Files.exists(tempDir.resolve(".pending-deletions.txt")));
    }

    @Test
    @DisplayName("a cancelled deletion keeps the file and leaves the other entries")
    void cancelledDeletionKeepsTheFile() throws IOException
    {
        Path wanted = tempDir.resolve("wanted.flac");
        Path unwanted = tempDir.resolve("unwanted.flac");
        Files.writeString(wanted, "audio");
        Files.writeString(unwanted, "audio");

        MusicLibrary library = new MusicLibrary(tempDir);
        library.scheduleDeletion(wanted);
        library.scheduleDeletion(unwanted);
        library.cancelDeletion(wanted);
        library.processPendingDeletions();

        assertTrue(Files.exists(wanted));
        assertFalse(Files.exists(unwanted));
    }

    @Test
    @DisplayName("only recorded downloads count as downloaded, across instances")
    void recordedDownloadsAreRemembered() throws IOException
    {
        Path download = tempDir.resolve("download.flac");
        Path own = tempDir.resolve("my-own-song.flac");
        Files.writeString(download, "audio");
        Files.writeString(own, "audio");

        MusicLibrary library = new MusicLibrary(tempDir);
        library.recordDownload(download);

        assertTrue(library.isDownloaded(download));
        assertFalse(library.isDownloaded(own));

        MusicLibrary reopened = new MusicLibrary(tempDir);
        assertTrue(reopened.isDownloaded(download));
        assertFalse(reopened.isDownloaded(own));

        reopened.forgetDownload(download);
        assertFalse(new MusicLibrary(tempDir).isDownloaded(download));
    }

    @Test
    @DisplayName("a library without a manifest treats tracks with a yt-dlp sidecar as downloads")
    void manifestIsSeededFromSidecars() throws IOException
    {
        Path download = tempDir.resolve("old-download.flac");
        Path own = tempDir.resolve("my-own-song.flac");
        Files.writeString(download, "audio");
        Files.writeString(tempDir.resolve("old-download.info.json"), "{}");
        Files.writeString(own, "audio");

        MusicLibrary library = new MusicLibrary(tempDir);

        assertTrue(library.isDownloaded(download));
        assertFalse(library.isDownloaded(own));
        assertTrue(Files.exists(tempDir.resolve(".downloads.txt")));
    }

    @Test
    @DisplayName("a file outside the library is never a download")
    void fileOutsideLibraryIsNotADownload(@TempDir Path elsewhere) throws IOException
    {
        Path outside = elsewhere.resolve("song.flac");
        Files.writeString(outside, "audio");

        MusicLibrary library = new MusicLibrary(tempDir);
        library.recordDownload(outside);

        assertFalse(library.isDownloaded(outside));
        assertFalse(library.contains(outside));
        assertFalse(library.contains(tempDir));
    }

    @Test
    @DisplayName("a deleted pending file is forgotten as a download")
    void deletedPendingFileIsForgotten() throws IOException
    {
        Path song = tempDir.resolve("song.flac");
        Files.writeString(song, "audio");

        MusicLibrary library = new MusicLibrary(tempDir);
        library.recordDownload(song);
        library.scheduleDeletion(song);
        library.processPendingDeletions();

        assertFalse(Files.exists(song));
        assertFalse(library.isDownloaded(song));
    }
}
