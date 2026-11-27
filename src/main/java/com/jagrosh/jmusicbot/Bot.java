/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.audio.AloneInVoiceHandler;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.AudioLoadWrapper;
import com.jagrosh.jmusicbot.audio.LibraryAudioSourceManager;
import com.jagrosh.jmusicbot.audio.NowPlayingHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.audio.TrackLoadingMonitor;
import com.jagrosh.jmusicbot.entities.UserInteraction;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.service.SearchService;
import com.jagrosh.jmusicbot.library.MusicLibrary;
import com.jagrosh.jmusicbot.library.YtDlpDownloader;
import com.jagrosh.jmusicbot.utils.InstanceLock;
import com.jagrosh.jmusicbot.web.WebServer;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.YoutubeOauth2TokenHandler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class Bot
{
    private final EventWaiter waiter;
    private final ScheduledExecutorService threadpool;
    private final BotConfig config;
    private final SettingsManager settings;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final NowPlayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;
    private final MusicService musicService;
    private final SearchService searchService;
    private final YoutubeOauth2TokenHandler youTubeOauth2TokenHandler;
    private final UserInteraction userInteraction;
    private final Instant startTime;
    private final AudioLoadWrapper audioLoadWrapper;
    private final MusicLibrary musicLibrary;
    private final LibraryAudioSourceManager librarySource;
    private final YtDlpDownloader ytdlp;
    private final com.jagrosh.jmusicbot.update.UpdateManager updates;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Bot.class);
    
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private JDA jda;
    private JFrame gui;
    private WebServer webServer;
    private CommandClient commandClient;
    
    public Bot(EventWaiter waiter, BotConfig config, SettingsManager settings, UserInteraction userInteraction)
    {
        this.waiter = waiter;
        this.config = config;
        this.settings = settings;
        this.userInteraction = userInteraction;
        this.playlists = new PlaylistLoader(config);
        this.musicLibrary = createMusicLibrary(config);
        this.librarySource = this.musicLibrary == null ? null : new LibraryAudioSourceManager(this.musicLibrary);
        this.ytdlp = createYtDlpDownloader(config);
        this.threadpool = Executors.newSingleThreadScheduledExecutor();
        this.startTime = Instant.now();
        this.youTubeOauth2TokenHandler = new YoutubeOauth2TokenHandler();
        this.youTubeOauth2TokenHandler.init();
        this.players = new PlayerManager(this);
        // Delay init of the PlayerManager until the GUI has started
        // this.players.init();
        this.nowplaying = new NowPlayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
        this.musicService = new MusicService(this);
        this.searchService = new SearchService(this);
        this.updates = new com.jagrosh.jmusicbot.update.UpdateManager(this);
        
        // Initialize audio load wrapper - use NO_OP when GUI is disabled to avoid monitoring overhead
        this.audioLoadWrapper = isNoGUI() 
            ? AudioLoadWrapper.NO_OP 
            : TrackLoadingMonitor.getInstance();
    }
    
    public BotConfig getConfig()
    {
        return config;
    }

    /**
     * @return the local music library
     */
    public MusicLibrary getMusicLibrary()
    {
        return musicLibrary;
    }

    /**
     * @return the yt-dlp downloader used to fetch tracks for the library
     */
    public YtDlpDownloader getYtDlp()
    {
        return ytdlp;
    }

    /**
     * @return the audio source that reads from the local music library
     */
    public LibraryAudioSourceManager getLibrarySource()
    {
        return librarySource;
    }

    /**
     * Creates the local music library, if it is enabled.
     *
     * @param config the bot configuration
     * @return the library, or null when it is disabled
     */
    private static MusicLibrary createMusicLibrary(BotConfig config)
    {
        if (!config.libraryEnabled())
        {
            return null;
        }
        MusicLibrary library = new MusicLibrary(OtherUtil.getPath(config.getLibraryFolder()));
        library.ensureReady();
        LOGGER.info("Local music library: {} ({} tracks)", library.getFolder().toAbsolutePath(), library.listTracks().size());
        return library;
    }

    /**
     * Creates the yt-dlp downloader, if the library is enabled.
     *
     * @param config the bot configuration
     * @return the downloader, or null when it is not usable
     */
    private static YtDlpDownloader createYtDlpDownloader(BotConfig config)
    {
        if (!config.libraryEnabled())
        {
            return null;
        }
        List<String> command = new ArrayList<>();
        command.add(config.getLibraryYtdlpCommand());
        command.addAll(config.getLibraryYtdlpArgs());

        YtDlpDownloader downloader = new YtDlpDownloader(OtherUtil.getPath(config.getLibraryYtdlpDirectory()),
                command, config.getLibraryAudioFormat(), 15);
        if (!downloader.isAvailable())
        {
            LOGGER.error("yt-dlp is not usable, downloads are disabled");
            return null;
        }
        return downloader;
    }
    
    public SettingsManager getSettingsManager()
    {
        return settings;
    }
    
    public EventWaiter getWaiter()
    {
        return waiter;
    }
    
    public ScheduledExecutorService getThreadpool()
    {
        return threadpool;
    }
    
    public PlayerManager getPlayerManager()
    {
        return players;
    }
    
    public PlaylistLoader getPlaylistLoader()
    {
        return playlists;
    }
    
    public NowPlayingHandler getNowplayingHandler()
    {
        return nowplaying;
    }

    public AloneInVoiceHandler getAloneInVoiceHandler()
    {
        return aloneInVoiceHandler;
    }

    public MusicService getMusicService()
    {
        return musicService;
    }

    /**
     * @return the updater for the bot and its bundled tools
     */
    public com.jagrosh.jmusicbot.update.UpdateManager getUpdateManager()
    {
        return updates;
    }

    public SearchService getSearchService()
    {
        return searchService;
    }

    /**
     * Gets the audio load wrapper for wrapping track load handlers.
     * Returns {@link AudioLoadWrapper#NO_OP} when GUI is disabled,
     * or {@link TrackLoadingMonitor} when GUI is enabled.
     *
     * @return the audio load wrapper
     */
    public AudioLoadWrapper getAudioLoadWrapper()
    {
        return audioLoadWrapper;
    }

    /**
     * Gets the TrackLoadingMonitor instance if monitoring is enabled.
     * Returns null when GUI is disabled (audioLoadWrapper is NO_OP).
     *
     * @return the TrackLoadingMonitor, or null if monitoring is disabled
     */
    public TrackLoadingMonitor getTrackLoadingMonitor()
    {
        return audioLoadWrapper instanceof TrackLoadingMonitor 
            ? (TrackLoadingMonitor) audioLoadWrapper 
            : null;
    }

    public UserInteraction getUserInteraction()
    {
        return userInteraction;
    }

    /**
     * Checks if running in no-GUI mode.
     * GUI is disabled when either the -Dnogui=true property is set,
     * or gui.enabled is set to false in the config.
     *
     * @return true if GUI is disabled, false if GUI is enabled
     */
    public boolean isNoGUI()
    {
        return userInteraction.isNoGUI() || !config.getGuiEnabled();
    }

    public JDA getJDA()
    {
        return jda;
    }
    
    public void closeAudioConnection(long guildId)
    {
        Guild guild = jda.getGuildById(guildId);
        if(guild!=null)
            threadpool.submit(() -> guild.getAudioManager().closeAudioConnection());
    }
    
    public void resetGame()
    {
        Activity game = config.getGame()==null || config.getGame().getName().equalsIgnoreCase("none") ? null : config.getGame();
        if(!Objects.equals(jda.getPresence().getActivity(), game))
            jda.getPresence().setActivity(game);
    }

    /**
     * Performs a full graceful shutdown with complete cleanup.
     * Use this for normal shutdowns (GUI close, /shutdown command).
     */
    public void shutdown()
    {
        shutdown(0);
    }

    /**
     * Shuts down like {@link #shutdown()}, ending the JVM with the given status. A non-zero status
     * makes a service manager such as systemd start the bot again (used by the Linux self-update).
     *
     * @param exitCode the JVM exit status
     */
    public void shutdown(int exitCode)
    {
        // The window, the restart button and the shutdown command can all get here at once.
        if (!shuttingDown.compareAndSet(false, true))
            return;

        // Whatever goes wrong while cleaning up, the lock must be released and the JVM must
        // exit; otherwise a half-closed bot keeps the folder locked until it is killed by hand.
        try
        {
            // Clean up audio connections first (before shutting down thread pool, as these may trigger events that use it)
            if (jda != null && jda.getStatus() != JDA.Status.SHUTTING_DOWN)
            {
                for (Guild g : jda.getGuilds())
                {
                    try
                    {
                        AudioHandler ah = (AudioHandler) g.getAudioManager().getSendingHandler();
                        if (ah != null)
                        {
                            ah.stopAndClear();
                            ah.getPlayer().destroy();
                        }
                        g.getAudioManager().closeAudioConnection();
                    }
                    catch (RuntimeException e)
                    {
                        LOGGER.warn("Could not close the audio connection of guild {}: {}", g.getId(), e.toString());
                    }
                }
                jda.shutdown();
            }

            // Shut down thread pool after audio cleanup to avoid RejectedExecutionException
            threadpool.shutdownNow();

            if (gui != null)
            {
                JFrame frame = gui;
                if (SwingUtilities.isEventDispatchThread())
                    frame.dispose();
                else
                    SwingUtilities.invokeLater(frame::dispose);
            }
            if (webServer != null)
                webServer.stop();
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Shutdown did not finish cleanly", e);
        }
        finally
        {
            InstanceLock.release();
            System.exit(exitCode);
        }
    }

    public void setJDA(JDA jda)
    {
        this.jda = jda;
    }
    
    public void setGUI(JFrame gui)
    {
        this.gui = gui;
    }

    /**
     * @param webServer the web GUI server, stopped on shutdown
     */
    public void setWebServer(WebServer webServer)
    {
        this.webServer = webServer;
    }

    public void setCommandClient(CommandClient commandClient)
    {
        this.commandClient = commandClient;
    }

    public CommandClient getCommandClient()
    {
        return commandClient;
    }

    public YoutubeOauth2TokenHandler getYouTubeOauth2Handler() {
        return youTubeOauth2TokenHandler;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
}
