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
}
