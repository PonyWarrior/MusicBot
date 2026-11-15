package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The favorites entry of a track must only count while that track is playing. It used to stay
 * behind when the next track started, so unfavoriting the next track could match (and delete)
 * the previous track's library file.
 */
@DisplayName("AudioHandler favorite mark")
class AudioHandlerFavoriteMarkTest
{
    private static final String TRACK_A = "https://example.com/a";
    private static final String TRACK_B = "https://example.com/b";
    private static final String FILE_A = "library/a.flac";

    private AudioPlayer player;
    private AudioHandler handler;

    @BeforeEach
    void setUp()
    {
        Bot bot = mock(Bot.class);
        BotConfig config = mock(BotConfig.class);
        SettingsManager settingsManager = mock(SettingsManager.class);
        Settings settings = mock(Settings.class);
        PlayerManager manager = mock(PlayerManager.class);
        Guild guild = mock(Guild.class);
        player = mock(AudioPlayer.class);

        when(manager.getBot()).thenReturn(bot);
        when(bot.isNoGUI()).thenReturn(true);
        when(bot.getConfig()).thenReturn(config);
        when(config.getMaxHistorySize()).thenReturn(10);
        when(bot.getSettingsManager()).thenReturn(settingsManager);
        when(guild.getIdLong()).thenReturn(1L);
        when(settingsManager.getSettings(1L)).thenReturn(settings);
        when(settings.getQueueType()).thenReturn(QueueType.LINEAR);

        handler = new AudioHandler(manager, guild, player) { };
    }

    private void play(String uri)
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("Title", "Author", 1000L, "id", false, uri));
        when(player.getPlayingTrack()).thenReturn(track);
    }

    @Test
    @DisplayName("a downloaded track is favorited although its entry is a file, not its url")
    void downloadedTrackCountsAsFavorited()
    {
        play(TRACK_A);
        handler.markCurrentTrackFavorited(FILE_A);

        assertTrue(handler.isCurrentTrackFavorited());
        assertEquals(FILE_A, handler.getFavoritedTrackUri());
    }

    @Test
    @DisplayName("the entry does not carry over to the next track")
    void entryDoesNotCarryOverToTheNextTrack()
    {
        play(TRACK_A);
        handler.markCurrentTrackFavorited(FILE_A);

        play(TRACK_B);

        assertFalse(handler.isCurrentTrackFavorited());
        assertNull(handler.getFavoritedTrackUri());
    }

    @Test
    @DisplayName("a download that finishes after the track changed does not mark the new track")
    void lateDownloadDoesNotMarkTheNewTrack()
    {
        play(TRACK_B);

        handler.markTrackFavorited(TRACK_A, FILE_A);

        assertFalse(handler.isCurrentTrackFavorited());
        assertNull(handler.getFavoritedTrackUri());
    }

    @Test
    @DisplayName("clearing the mark unfavorites the current track")
    void clearingTheMark()
    {
        play(TRACK_A);
        handler.markCurrentTrackFavorited(TRACK_A);
        handler.markCurrentTrackFavorited(null);

        assertFalse(handler.isCurrentTrackFavorited());
    }

    @Test
    @DisplayName("nothing is favorited when nothing plays")
    void nothingPlaying()
    {
        when(player.getPlayingTrack()).thenReturn(null);
        handler.markCurrentTrackFavorited(FILE_A);

        assertFalse(handler.isCurrentTrackFavorited());
        assertNull(handler.getFavoritedTrackUri());
    }
}
