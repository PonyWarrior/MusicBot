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
package com.jagrosh.jmusicbot.utils;

import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;

/**
 * Configures the sample rate conversion quality of a player manager.
 *
 * <p>lavaplayer resamples the audio to Discord's 48 kHz with libsamplerate, and the default
 * quality is {@link AudioConfiguration.ResamplingQuality#LOW}, which is libsamplerate's
 * {@code SINC_FASTEST} filter: the coarsest one, with a wide transition band and noticeable
 * passband ripple. On music that is clearly audible as a rough or warbling sound.
 *
 * <p>Only sources that are not already at 48 kHz are affected. A YouTube track in 44.1 kHz is
 * always resampled, while a track from the local music library is stored at 48 kHz and passes
 * through untouched.
 *
 * <p>lavaplayer 2.x has no public setter for this, the field is private, so it is set
 * reflectively. If that ever stops working the bot keeps the default and says so.
 *
 * @author Arif Banai (arif-banai)
 */
public final class ResamplingQuality
{
    private static final Logger LOG = LoggerFactory.getLogger(ResamplingQuality.class);

    private ResamplingQuality()
    {
    }

    /**
     * Sets the resampling quality on a player manager.
     *
     * @param manager the player manager
     * @param quality the quality to use, {@code low}, {@code medium} or {@code high}
     * @return true if the quality was applied
     */
    public static boolean apply(DefaultAudioPlayerManager manager, String quality)
    {
        if (manager == null || quality == null || quality.isBlank())
        {
            return false;
        }

        AudioConfiguration.ResamplingQuality resolved;
        try
        {
            resolved = AudioConfiguration.ResamplingQuality.valueOf(quality.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Unknown resampling quality '{}', keeping the default", quality);
            return false;
        }

        try
        {
            Field field = DefaultAudioPlayerManager.class.getDeclaredField("configuration");
            field.setAccessible(true);
            AudioConfiguration configuration = (AudioConfiguration) field.get(manager);
            if (configuration == null)
            {
                return false;
            }
            configuration.setResamplingQuality(resolved);
            LOG.info("Sample rate conversion quality set to {}", resolved);
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            LOG.warn("Could not set the sample rate conversion quality: {}", e.toString());
            return false;
        }
    }
}
