package com.jagrosh.jmusicbot.unit;

import com.jagrosh.jmusicbot.DiscordService;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot does not request the intents behind these caches, so they must stay disabled or JDA
 * logs a warning on every start.
 */
class DiscordServiceCacheTest
{
    @Test
    void soundboardCacheIsDisabled()
    {
        assertTrue(DiscordService.DISABLED_CACHE.contains(CacheFlag.SOUNDBOARD_SOUNDS));
    }

    @Test
    void cachesTheBotNeedsStayEnabled()
    {
        assertFalse(DiscordService.DISABLED_CACHE.contains(CacheFlag.VOICE_STATE));
        assertFalse(DiscordService.DISABLED_CACHE.contains(CacheFlag.MEMBER_OVERRIDES));
    }
}