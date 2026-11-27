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

import com.jagrosh.jmusicbot.update.UpdateComponent;
import com.jagrosh.jmusicbot.update.UpdateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release asset selection per OS/CPU and the Linux launcher that ships inside the jar.
 */
class LinuxUpdateTest
{
    @TempDir
    Path dir;

    @Test
    void picksYtDlpBuildPerPlatform()
    {
        assertEquals("yt-dlp.exe", UpdateManager.assetName(UpdateComponent.YTDLP, "Windows 11", "amd64", ""));
        assertEquals("yt-dlp_linux", UpdateManager.assetName(UpdateComponent.YTDLP, "Linux", "amd64", ""));
        assertEquals("yt-dlp_linux", UpdateManager.assetName(UpdateComponent.YTDLP, "Linux", "x86_64", ""));
        assertEquals("yt-dlp_linux_aarch64", UpdateManager.assetName(UpdateComponent.YTDLP, "Linux", "aarch64", ""));
        assertNull(UpdateManager.assetName(UpdateComponent.YTDLP, "Mac OS X", "aarch64", ""));
    }

    @Test
    void picksDenoBuildPerPlatform()
    {
        assertEquals("deno-x86_64-pc-windows-msvc.zip",
                UpdateManager.assetName(UpdateComponent.DENO, "Windows 10", "amd64", ""));
        assertEquals("deno-x86_64-unknown-linux-gnu.zip",
                UpdateManager.assetName(UpdateComponent.DENO, "Linux", "amd64", ""));
        assertEquals("deno-aarch64-unknown-linux-gnu.zip",
                UpdateManager.assetName(UpdateComponent.DENO, "Linux", "aarch64", ""));
        assertNull(UpdateManager.assetName(UpdateComponent.DENO, "Linux", "arm", ""));
    }

    @Test
    void ffmpegOnlyOnWindows()
    {
        assertEquals("ffmpeg-7.1-essentials_build.zip",
                UpdateManager.assetName(UpdateComponent.FFMPEG, "Windows 11", "amd64", "7.1"));
        assertNull(UpdateManager.assetName(UpdateComponent.FFMPEG, "Linux", "amd64", "7.1"));
    }

    @Test
    void noBinaryAssetForSourceComponents()
    {
        assertNull(UpdateManager.assetName(UpdateComponent.YTCIPHER, "Linux", "amd64", ""));
        assertNull(UpdateManager.assetName(UpdateComponent.BOT, "Linux", "amd64", ""));
    }

    @Test
    void launcherIsBundledWithUnixLineEndings() throws Exception
    {
        try (InputStream in = UpdateManager.class.getResourceAsStream("/update/start.sh"))
        {
            assertNotNull(in, "update/start.sh must be packaged in the jar");
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.startsWith("#!/bin/sh\n"));
            assertFalse(script.contains("\r"), "start.sh must use LF line endings");
            assertTrue(script.contains("JMusicBot.jar.old") || script.contains("$JAR.old"));
        }
    }

    @Test
    void refreshesLauncherFromNewJar() throws Exception
    {
        Path jar = dir.resolve("new.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file))
        {
            zip.putNextEntry(new ZipEntry("update/start.sh"));
            zip.write("#!/bin/sh\necho new\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path launcher = dir.resolve("start.sh");
        Files.writeString(launcher, "old");

        assertTrue(UpdateManager.refreshLauncher(jar, launcher));
        assertEquals("#!/bin/sh\necho new\n", Files.readString(launcher));
    }

    @Test
    void keepsLauncherWhenNewJarHasNone() throws Exception
    {
        Path jar = dir.resolve("new.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file))
        {
            zip.putNextEntry(new ZipEntry("other.txt"));
            zip.closeEntry();
        }
        Path launcher = dir.resolve("start.sh");
        Files.writeString(launcher, "old");

        assertFalse(UpdateManager.refreshLauncher(jar, launcher));
        assertEquals("old", Files.readString(launcher));
    }
}
