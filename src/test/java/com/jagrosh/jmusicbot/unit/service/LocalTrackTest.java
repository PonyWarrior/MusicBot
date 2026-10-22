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
package com.jagrosh.jmusicbot.unit.service;

import com.jagrosh.jmusicbot.service.MusicService;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the detection of tracks that are played from the local file system.
 *
 * <p>Such a track must never be handed to yt-dlp: it has a file path as its uri, and yt-dlp
 * would read the Windows drive letter as a url scheme ("Unsupported url scheme: e").
 */
@DisplayName("Local track detection")
class LocalTrackTest
{
    /**
     * Calls the package private helper of MusicService.
     */
    private static boolean isLocalTrack(AudioTrack track, String uri) throws Exception
    {
        Method method = MusicService.class.getDeclaredMethod("isLocalTrack", AudioTrack.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, track, uri);
    }

    @Nested
    @DisplayName("isLocalTrack Tests")
    class IsLocalTrackTests
    {
        @Test
        @DisplayName("A track with an absolute Windows path is local")
        void absoluteWindowsPathIsLocal() throws Exception
        {
            assertTrue(isLocalTrack(mock(AudioTrack.class),
                    "C:\\Music\\Example Song (Remix).flac"));
        }

        @Test
        @DisplayName("A track with an absolute POSIX path is local on POSIX systems only")
        void absolutePosixPathIsLocal() throws Exception
        {
            // A path is only native on the system it belongs to, so /home/... is a local file on
            // Linux but not on Windows.
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            assertEquals(!windows,
                    isLocalTrack(mock(AudioTrack.class), "/home/user/Music/song.flac"));
        }

        @Test
        @DisplayName("A track with a https url is not local")
        void httpsUrlIsNotLocal() throws Exception
        {
            assertFalse(isLocalTrack(mock(AudioTrack.class),
                    "https://www.youtube.com/watch?v=abcdefghijk"));
        }

        @Test
        @DisplayName("A track played by the local source is local")
        void localSourceIsLocal() throws Exception
        {
            AudioTrack track = mock(AudioTrack.class);
            AudioSourceManager source = mock(AudioSourceManager.class);
            when(source.getSourceName()).thenReturn("local");
            when(track.getSourceManager()).thenReturn(source);

            assertTrue(isLocalTrack(track, "https://www.youtube.com/watch?v=abcdefghijk"));
        }

        @Test
        @DisplayName("A track played by an online source is not local")
        void onlineSourceIsNotLocal() throws Exception
        {
            AudioTrack track = mock(AudioTrack.class);
            AudioSourceManager source = mock(AudioSourceManager.class);
            when(source.getSourceName()).thenReturn("youtube");
            when(track.getSourceManager()).thenReturn(source);

            assertFalse(isLocalTrack(track, "https://www.youtube.com/watch?v=abcdefghijk"));
        }

        @Test
        @DisplayName("A track without a source manager is not local")
        void noSourceIsNotLocal() throws Exception
        {
            AudioTrack track = mock(AudioTrack.class);
            when(track.getSourceManager()).thenReturn(null);

            assertFalse(isLocalTrack(track, "https://www.youtube.com/watch?v=abcdefghijk"));
        }
    }

    @Nested
    @DisplayName("Track info Tests")
    class TrackInfoTests
    {
        @Test
        @DisplayName("A local track keeps its uri as the file path")
        void localTrackKeepsFilePath()
        {
            AudioTrackInfo info = new AudioTrackInfo("Example Song (Remix)", "Example Artist",
                    161000, "abcdefghijk", false,
                    "C:\\Music\\Example Song (Remix).flac");

            assertEquals("C:\\Music\\Example Song (Remix).flac",
                    info.uri);
        }
    }
}
