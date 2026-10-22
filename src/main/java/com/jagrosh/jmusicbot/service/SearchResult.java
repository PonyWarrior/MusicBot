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
package com.jagrosh.jmusicbot.service;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.EmbedBuilder;

import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;

/**
 * One entry of a search result, with the information that is shown for it.
 *
 * <p>Both the online results and the local library carry a title, an artist, a duration, a
 * thumbnail and a playable track, so they can be shown the same way.
 *
 * @param track the playable track
 * @param title the title to show
 * @param artist the uploader or artist to show
 * @param artwork the thumbnail url, may be empty
 * @param local true when the track comes from the local music library
 * @author Arif Banai (arif-banai)
 */
public record SearchResult(AudioTrack track, String title, String artist, String artwork, boolean local)
{
    /**
     * The length of the track in milliseconds.
     *
     * @return the duration
     */
    public long duration()
    {
        return track == null ? 0 : track.getInfo().length;
    }

    /**
     * The url of the track.
     *
     * @return the url
     */
    public String url()
    {
        return track == null ? "" : track.getInfo().uri;
    }

    /**
     * The choice line used by the text based menu.
     *
     * @param number the position of the result, starting at 1
     * @return the formatted choice
     */
    public String formatChoice(int number)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("`[").append(TimeUtil.formatTime(duration())).append("]` ");
        sb.append("[**").append(FormatUtil.filter(title == null ? "" : title)).append("**](").append(url()).append(")");
        if (artist != null && !artist.isBlank())
        {
            sb.append(" \u2014 ").append(FormatUtil.filter(artist));
        }
        if (local)
        {
            sb.append(" \uD83C\uDFB7");
        }
        return sb.toString();
    }

    /**
     * Builds the embed that shows this result, with its thumbnail.
     *
     * @param color the accent colour of the embed
     * @return the embed
     */
    public EmbedBuilder buildEmbed(java.awt.Color color)
    {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(FormatUtil.filter(title == null ? "Unknown title" : title), url())
                .setColor(color)
                .addField("Artist", FormatUtil.filter(artist == null || artist.isBlank() ? "Unknown" : artist), true)
                .addField("Duration", TimeUtil.formatTime(duration()), true);

        if (local)
        {
            embed.addField("Source", "Local library \uD83C\uDFB7", true);
        }

        if (artwork != null && !artwork.isBlank())
        {
            embed.setThumbnail(artwork);
        }

        return embed;
    }
}
