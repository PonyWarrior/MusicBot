package com.jagrosh.jmusicbot.service;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.EmbedBuilder;

import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;

/**
 * One entry of a search result, with the information that is shown for it.
 *
 * <p>Both the online results and the local library carry a title, an artist, a duration, a
 * thumbnail, a source and a playable track, so they can be shown the same way.
 *
 * @param track the playable track
 * @param title the title to show
 * @param artist the uploader or artist to show
 * @param artwork the thumbnail url, may be empty
 * @param source the raw source name of the track, for example {@code youtube} or {@code local}
 */
public record SearchResult(AudioTrack track, String title, String artist, String artwork, String source)
{
    /**
     * The name of the local library source.
     */
    public static final String LOCAL_SOURCE = "local";

    /**
     * Creates a result, taking the source from the track itself.
     *
     * @param track the playable track
     * @param title the title to show
     * @param artist the uploader or artist to show
     * @param artwork the thumbnail url, may be empty
     * @return the result
     */
    public static SearchResult of(AudioTrack track, String title, String artist, String artwork)
    {
        return new SearchResult(track, title, artist, artwork, sourceNameOf(track));
    }

    /**
     * The source name of a track.
     *
     * @param track the track
     * @return the source name, or an empty string when it is not known
     */
    public static String sourceNameOf(AudioTrack track)
    {
        if (track == null)
        {
            return "";
        }
        try
        {
            return track.getSourceManager() == null ? "" : track.getSourceManager().getSourceName();
        }
        catch (RuntimeException e)
        {
            return "";
        }
    }

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
     * @return true when the track comes from the local music library
     */
    public boolean local()
    {
        return LOCAL_SOURCE.equalsIgnoreCase(source);
    }

    /**
     * A readable name for the source of the track.
     *
     * @return the label to show
     */
    public String sourceLabel()
    {
        if (source == null || source.isBlank())
        {
            return "Unknown";
        }
        return switch (source.toLowerCase(java.util.Locale.ROOT))
        {
            case LOCAL_SOURCE, "library" -> "Local library";
            case "youtube" -> "YouTube";
            case "soundcloud" -> "SoundCloud";
            case "bandcamp" -> "Bandcamp";
            case "vimeo" -> "Vimeo";
            case "twitch" -> "Twitch";
            case "beam" -> "Beam.pro";
            case "getyarn" -> "Getyarn";
            case "nico" -> "NicoNico";
            case "http" -> "HTTP";
            default -> source.substring(0, 1).toUpperCase() + source.substring(1);
        };
    }

    /**
     * The choice line used by a text based menu.
     *
     * @param number the position of the result, starting at 1
     * @return the formatted choice
     */
    public String formatChoice(int number)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("`[").append(TimeUtil.formatTime(duration())).append("]` ");
        if (isHttpUrl(url()))
        {
            sb.append("[**").append(FormatUtil.filter(title == null ? "" : title)).append("**](").append(url()).append(")");
        }
        else
        {
            // A track from the local library has a file path instead of a url, and Discord
            // would render that as an enormous unreadable link.
            sb.append("**").append(FormatUtil.filter(title == null ? "" : title)).append("**");
        }
        if (artist != null && !artist.isBlank())
        {
            sb.append(" \u2014 ").append(FormatUtil.filter(artist));
        }
        sb.append(" `[").append(sourceLabel()).append("]`");
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
        String title = FormatUtil.filter(this.title == null ? "Unknown title" : this.title);
        EmbedBuilder embed = new EmbedBuilder().setColor(color);

        // Discord only accepts http(s) urls for the title link and for the thumbnail. A track
        // from the local library has a file path, which would make the embed fail to build.
        if (isHttpUrl(url()))
        {
            embed.setTitle(title, url());
        }
        else
        {
            embed.setTitle(title);
        }

        embed.addField("Artist", FormatUtil.filter(artist == null || artist.isBlank() ? "Unknown" : artist), true)
                .addField("Duration", TimeUtil.formatTime(duration()), true)
                .addField("Source", sourceLabel() + (local() ? " \uD83C\uDFB7" : ""), true);

        if (isHttpUrl(artwork))
        {
            embed.setThumbnail(artwork);
        }

        return embed;
    }

    /**
     * @param value the value to test
     * @return true if the value is an http(s) url that Discord accepts
     */
    public static boolean isHttpUrl(String value)
    {
        if (value == null || value.isBlank())
        {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
