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
package com.jagrosh.jmusicbot.unit.update;

import com.jagrosh.jmusicbot.update.UpdateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An update cut off after the current file was moved aside must not leave the component missing.
 */
class RestoreIfMissingTest
{
    @TempDir
    Path dir;

    @Test
    void restoresPlainBackup() throws Exception
    {
        Path target = dir.resolve("yt-dlp.exe");
        Files.writeString(dir.resolve("yt-dlp.exe.old"), "old");

        assertTrue(UpdateManager.restoreIfMissing(target));
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("yt-dlp.exe.old")));
    }

    @Test
    void restoresNewestTimestampedBackup() throws Exception
    {
        Path target = dir.resolve("deno.exe");
        Files.writeString(dir.resolve("deno.exe.1000.old"), "older");
        Files.writeString(dir.resolve("deno.exe.2000.old"), "newer");

        assertTrue(UpdateManager.restoreIfMissing(target));
        assertEquals("newer", Files.readString(target));
    }

    @Test
    void restoresFolder() throws Exception
    {
        Path target = dir.resolve("yt-cipher");
        Files.createDirectories(dir.resolve("yt-cipher.old"));
        Files.writeString(dir.resolve("yt-cipher.old").resolve("server.ts"), "x");

        assertTrue(UpdateManager.restoreIfMissing(target));
        assertTrue(Files.isRegularFile(target.resolve("server.ts")));
    }

    @Test
    void leavesPresentTargetAlone() throws Exception
    {
        Path target = dir.resolve("ffmpeg.exe");
        Files.writeString(target, "current");
        Files.writeString(dir.resolve("ffmpeg.exe.old"), "old");

        assertFalse(UpdateManager.restoreIfMissing(target));
        assertEquals("current", Files.readString(target));
    }

    @Test
    void doesNothingWithoutBackup()
    {
        assertFalse(UpdateManager.restoreIfMissing(dir.resolve("ffmpeg.exe")));
    }
}
