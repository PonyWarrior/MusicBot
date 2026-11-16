/*
 * Copyright 2016 John Grosh (jagrosh).
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
import com.jagrosh.jmusicbot.audio.VoiceConnectionMonitor;
import com.jagrosh.jmusicbot.commands.v1.CommandFactory;
import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.entities.UserInteraction;
import com.jagrosh.jmusicbot.gui.MainFrame;
import com.jagrosh.jmusicbot.gui.web.WebGui;
import com.jagrosh.jmusicbot.web.WebServer;
import com.jagrosh.jmusicbot.gui.theme.ThemeManager;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.ConsoleUtil;
import com.jagrosh.jmusicbot.utils.InstanceLock;
import com.jagrosh.jmusicbot.utils.LocalCipherService;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.TeeOutputStream;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;


/**
 *
 * @author John Grosh (jagrosh)
 */
public class JMusicBot 
{
    public final static Logger LOG = LoggerFactory.getLogger(JMusicBot.class);

    /**
     * The local signature cipher service, if one was started.
     */
    private static LocalCipherService localCipherService = null;

    /**
     * @return the local cipher service, or null when it is not running
     */
    public static LocalCipherService getLocalCipherService()
    {
        return localCipherService;
    }
    public final static Permission[] RECOMMENDED_PERMS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_MANAGE,
            Permission.MESSAGE_EXT_EMOJI,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.NICKNAME_CHANGE
    };
    public final static GatewayIntent[] INTENTS = {
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.MESSAGE_CONTENT
    };
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        if(args.length > 0) {
            if (args[0].equalsIgnoreCase("generate-config")) {
                // Use headless prompt for config generation (nogui=true, noprompt=true)
                UserInteraction userInteraction = new Prompt(null, null, true, true);
                BotConfig.writeDefaultConfig(userInteraction);
                return;
            }
        }
        startBot();
    }
    
    /**
     * Starts the local signature cipher service (yt-cipher) as a child process of the bot.
     *
     * <p>The service resolves YouTube's signature cipher server-side, which keeps playback
     * requests away from a third-party service. It is stopped again through a shutdown hook when
     * the bot exits.
     *
     * @param config the bot configuration
     */
    /**
     * Stops the local cipher service, if it is running.
     */
    public static synchronized void stopLocalCipherService()
    {
        if (localCipherService != null)
        {
            localCipherService.stop();
            localCipherService = null;
        }
    }

    /**
     * Stops the local cipher service and starts it again, for example after its files changed.
     *
     * @param config the bot configuration
     * @return true when the service is running afterwards
     */
    public static synchronized boolean restartLocalCipherService(BotConfig config)
    {
        stopLocalCipherService();
        startLocalCipherService(config);
        return localCipherService != null;
    }

    private static synchronized void startLocalCipherService(BotConfig config)
    {
        if (!config.useLocalCipher())
        {
            return;
        }

        try
        {
            Path directory = OtherUtil.getPath(config.getLocalCipherDirectory());
            List<String> command = new ArrayList<>();
            command.add(config.getLocalCipherCommand());
            command.addAll(config.getLocalCipherArgs());

            Path logFile = OtherUtil.getPath("logs/yt-cipher.log");
            LocalCipherService service = new LocalCipherService(directory, command, config.getLocalCipherEnv(),
                    config.getLocalCipherPort(), config.getLocalCipherStartupTimeout(), logFile);

            if (service.start())
            {
                localCipherService = service;
            }
            else
            {
                LOG.error("The local cipher service could not be started. Playback will use the "
                        + "cipher server at {}", config.getYoutubeRemoteCipher());
            }
        }
        catch (Exception e)
        {
            LOG.error("Could not start the local cipher service: {}", e.getMessage());
        }
    }

    private static void startSwingGui(Bot bot)
    {
        // Swing components must be built and shown on the event dispatch thread.
        Runnable create = () ->
        {
            MainFrame mainFrame = new MainFrame(bot);
            bot.setGUI(mainFrame);
            mainFrame.init();
        };
        try
        {
            if (SwingUtilities.isEventDispatchThread())
                create.run();
            else
                SwingUtilities.invokeAndWait(create);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while starting the GUI");
        }
        catch (Exception e)
        {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            LOG.error("Could not start GUI. Use -Dnogui=true for server environments.", cause);
        }
    }

    private static void startBot()
    {
        // Create user interaction handler for startup
        UserInteraction userInteraction = new Prompt("JMusicBot");
        
        // Buffer early output when GUI might be used.
        // We don't know if config has gui.enabled=true yet, so buffer when -Dnogui is not set.
        // If GUI ends up disabled by config, we restore original streams; otherwise we replay buffer into GUI.
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        TeeOutputStream teeOut = null;
        TeeOutputStream teeErr = null;
        
        if (!userInteraction.isNoGUI())
        {
            teeOut = new TeeOutputStream(originalOut);
            teeErr = new TeeOutputStream(originalErr);
            System.setOut(new PrintStream(teeOut, true));
            System.setErr(new PrintStream(teeErr, true));
        }
        
        // Check for another running instance
        if (!InstanceLock.tryAcquire()) {
            userInteraction.alert(UserInteraction.Level.ERROR, "JMusicBot",
                    "Another instance of JMusicBot is already running.\n" +
                    "Running multiple instances with the same configuration causes duplicate responses to commands.\n" +
                    "Please close the other instance first.");
            System.exit(1);
        }

        // Tell a pending bot update (apply-update.ps1) that the new jar got this far.
        com.jagrosh.jmusicbot.update.UpdateManager.markStarted();
        
        // Startup checks
        OtherUtil.checkVersion(userInteraction);
        OtherUtil.checkJavaVersion(userInteraction);
        
        // Load config
        BotConfig config = new BotConfig(userInteraction);
        config.load();
        if (!config.isValid())
        {
            // Restore original streams before exiting
            if (teeOut != null)
            {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            return;
        }
        LOG.info("Loaded config from {}", config.getConfigLocation());

        // Start the local signature cipher service, if one is configured. It has to be running
        // before the first track is loaded, and it is stopped again when the JVM exits.
        startLocalCipherService(config);

        // Set log level from config
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(
                ch.qos.logback.classic.Level.toLevel(config.getLogLevel(), ch.qos.logback.classic.Level.INFO));
        
        // Single source of truth for GUI: combines -Dnogui and config gui.enabled.
        // This matches the logic in Bot.isNoGUI().
        boolean guiEnabled = !userInteraction.isNoGUI() && config.getGuiEnabled();
        boolean webGui = guiEnabled && !"swing".equalsIgnoreCase(config.getGuiMode());
        
        // Get buffered early output before we change streams again
        String earlyOutput = null;
        if (teeOut != null)
        {
            earlyOutput = teeOut.getBufferedContent();
            teeOut.clearBuffer();
            if (teeErr != null)
            {
                teeErr.clearBuffer();
            }
        }
        
        if (guiEnabled && !webGui)
        {
            // Initialize theme before creating any Swing components
            try
            {
                ThemeManager.initialize(config.getGuiTheme(), config.getGuiFontSize());
            }
            catch (Exception e)
            {
                LOG.warn("Could not apply theme from config: {}", e.getMessage());
            }
            
            // Redirect streams to GUI console, replaying buffered early output
            try
            {
                ConsoleUtil.redirectSystemStreamsWithReplay(earlyOutput);
            }
            catch (Exception e)
            {
                LOG.warn("Could not redirect console streams to GUI. Logs may not appear in GUI console.");
                // Restore original streams on failure
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
        else
        {
            // GUI disabled or web GUI: restore original streams (discard buffer)
            if (teeOut != null)
            {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
        
        // Set up the listener
        EventWaiter waiter = new EventWaiter();
        SettingsManager settings = new SettingsManager();
        Bot bot = new Bot(waiter, config, settings, userInteraction);

        // TrackLoadingMonitor is configured via Bot.getAudioLoadWrapper() (DI pattern).
        VoiceConnectionMonitor.setEnabled(guiEnabled);

        if (guiEnabled)
        {
            if (webGui)
            {
                WebServer webServer = new WebServer(bot);
                boolean opened = false;
                // A jar built without the web UI has no page to show; a window that only says
                // "not found" would hide that a working GUI is available.
                int port = webServer.hasWebUi() ? webServer.start() : -1;
                if (port <= 0 && !webServer.hasWebUi())
                    LOG.error("This build does not contain the web UI (webui/dist was not built before packaging).");
                if (port > 0)
                {
                    try
                    {
                        WebGui.start(bot, "http://127.0.0.1:" + port + "/");
                        bot.setWebServer(webServer);
                        opened = true;
                    }
                    catch (Throwable t)
                    {
                        // JavaFX is not part of the jar. Started without --module-path this is a
                        // NoClassDefFoundError, which would otherwise leave a windowless process
                        // holding the instance lock.
                        LOG.error("Could not open the web GUI window (is JavaFX on the module path?): {}",
                                t.toString());
                        webServer.stop();
                    }
                }
                if (!opened)
                {
                    LOG.error("Could not start the web GUI, falling back to the Swing GUI");
                    startSwingGui(bot);
                }
            }
            else
            {
                startSwingGui(bot);
            }
        }
        
        CommandClient client = CommandFactory.createCommandClient(config, settings, bot);
        bot.setCommandClient(client);

        // Now that GUI/Logging is ready, initialize the player manager
        bot.getPlayerManager().init();

        // attempt to log in and start
        try
        {
            JDA jda = DiscordService.createJDA(config, bot, waiter, client, userInteraction);
            bot.setJDA(jda);
        }
        catch(IllegalArgumentException ex)
        {
            userInteraction.alert(UserInteraction.Level.ERROR, "JMusicBot",
                    "Invalid configuration. Check your token.\nConfig Location: " + config.getConfigLocation());
            System.exit(1);
        }
        catch(ErrorResponseException ex)
        {
            userInteraction.alert(UserInteraction.Level.ERROR, "JMusicBot", "Invalid response from Discord. Check your internet connection.");
            System.exit(1);
        }
        catch(Exception ex)
        {
            LOG.error("An unexpected error occurred during startup", ex);
            System.exit(1);
        }
    }
}
