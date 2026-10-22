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
package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import net.dv8tion.jda.api.entities.Guild;
import org.apache.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.ProxyUtil;
import com.jagrosh.jmusicbot.utils.ResamplingQuality;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlayerManager extends DefaultAudioPlayerManager
{
    private final static Logger LOGGER = LoggerFactory.getLogger(PlayerManager.class);
    private final Bot bot;
    
    public PlayerManager(Bot bot)
    {
        this.bot = bot;
    }
    
    public void init()
    {
        BotConfig config = bot.getConfig();
        
        // Configure frame buffer for GC protection (default 2000ms = 2 seconds of audio buffered)
        setFrameBufferDuration(config.getFrameBufferMs());

        // Configure the sample rate conversion quality. The default of lavaplayer is the
        // coarsest filter, which is audible on music that is not already at 48 kHz.
        ResamplingQuality.apply(this, config.getResamplingQuality());
        
        // Configure proxy for Lavaplayer HTTP requests if enabled
        // This should be done BEFORE registering source managers
        if (config.proxyLavaplayer() && config.hasProxy()) {
            HttpHost proxy = ProxyUtil.createApacheProxy(config);
            setHttpBuilderConfigurator(builder -> builder.setProxy(proxy));
            LOGGER.info("Lavaplayer configured to use proxy: {}:{}", 
                    config.getProxyHost(), config.getProxyPort());
        }
        
        // Register the local music library first, so that a play request is looked up in the
        // library before it is searched for online.
        if (config.libraryEnabled() && config.librarySearchFirst() && bot.getMusicLibrary() != null)
        {
            registerSourceManager(new LibraryAudioSourceManager(bot.getMusicLibrary()));
            LOGGER.info("Local music library registered as the first audio source");
        }

        // Register transformative audio sources
        TransformativeAudioSourceManager.createTransforms(config.getTransforms())
                .forEach(this::registerSourceManager);

        // Register enabled audio sources with error handling
        for (AudioSource source : config.getEnabledAudioSources())
        {
            try
            {
                source.register(this, config);
                LOGGER.debug("Successfully registered audio source: {}", source.getConfigName());
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to register audio source '{}': {}", 
                    source.getConfigName(), e.getMessage(), e);
            }
        }
    }
    
    public Bot getBot()
    {
        return bot;
    }
    
    public boolean hasHandler(Guild guild)
    {
        return guild.getAudioManager().getSendingHandler()!=null;
    }
    
    public AudioHandler setUpHandler(Guild guild)
    {
        AudioHandler handler;
        if(guild.getAudioManager().getSendingHandler()==null)
        {
            AudioPlayer player = createPlayer();
            player.setVolume(bot.getSettingsManager().getSettings(guild).getVolume());
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
        }
        else
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler;
    }

}
