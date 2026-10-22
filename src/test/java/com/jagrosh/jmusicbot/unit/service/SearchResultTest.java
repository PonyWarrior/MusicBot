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

import com.jagrosh.jmusicbot.service.SearchResult;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import net.dv8tion.jda.api.EmbedBuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the presentation of a search result.
 */
@DisplayName("Search result")
class SearchResultTest
{
    private static AudioTrack track(String title, String author, String artwork)
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo(title, author, 162000, "id", false,
                "https://www.youtube.com/watch?v=id", artwork, null));
        return track;
    }

    @Nested
    @DisplayName("Choice formatting")
    class ChoiceFormatting
    {
        @Test
        @DisplayName("The choice shows the title, the duration and the artist")
        void choiceShowsTitleDurationAndArtist()
        {
            SearchResult result = new SearchResult(track("Example Song", "Example Artist", ""), "Example Song",
                    "Example Artist", "", false);

            String choice = result.formatChoice(1);
            assertTrue(choice.contains("Example Song"), choice);
            assertTrue(choice.contains("Example Artist"), choice);
            assertTrue(choice.contains("02:42"), choice);
        }

        @Test
        @DisplayName("A result without an artist does not show a dash")
        void resultWithoutArtistHasNoDash()
        {
            SearchResult result = new SearchResult(track("Song", "", ""), "Song", "", "", false);

            assertFalse(result.formatChoice(1).contains("\u2014"),
                    "An empty artist should not leave a dangling separator");
        }
    }

    @Nested
    @DisplayName("Embed building")
    class EmbedBuilding
    {
        @Test
        @DisplayName("The embed carries the title, the artist and the thumbnail")
        void embedCarriesTitleArtistAndThumbnail()
        {
            SearchResult result = new SearchResult(track("Example Song", "Example Artist", ""), "Example Song",
                    "Example Artist", "https://i.ytimg.com/vi/abc/hqdefault.jpg", false);

            EmbedBuilder builder = result.buildEmbed(null);
            var embed = builder.build();

            assertEquals("Example Song", embed.getTitle());
            assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", embed.getThumbnail().getUrl());
            assertNotNull(embed.getFields());
            assertTrue(embed.getFields().stream().anyMatch(f -> "Artist".equals(f.getName())
                    && "Example Artist".equals(f.getValue())), embed.getFields().toString());
        }

        @Test
        @DisplayName("A local result is marked as coming from the library")
        void localResultIsMarked()
        {
            SearchResult result = new SearchResult(track("Song", "Artist", ""), "Song", "Artist", "", true);

            var embed = result.buildEmbed(null).build();
            assertTrue(embed.getFields().stream().anyMatch(f -> "Source".equals(f.getName())),
                    embed.getFields().toString());
        }
    }
}
