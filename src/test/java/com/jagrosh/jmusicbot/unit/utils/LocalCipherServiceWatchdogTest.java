package com.jagrosh.jmusicbot.unit.utils;

import com.jagrosh.jmusicbot.utils.LocalCipherService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the restart budget of the cipher watchdog.
 */
class LocalCipherServiceWatchdogTest
{
    private static boolean allow(List<Long> history, long now) throws Exception
    {
        Method method = LocalCipherService.class.getDeclaredMethod("allowRestart", List.class, long.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, history, now);
    }

    @Test
    void allowsFiveRestartsThenStops() throws Exception
    {
        List<Long> history = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            assertTrue(allow(history, 1000L + i));
        assertFalse(allow(history, 2000L));
    }

    @Test
    void oldRestartsExpire() throws Exception
    {
        List<Long> history = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            assertTrue(allow(history, i));
        assertTrue(allow(history, TimeUnit.MINUTES.toMillis(11)));
    }
}
