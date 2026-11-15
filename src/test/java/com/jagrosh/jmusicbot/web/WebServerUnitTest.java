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
package com.jagrosh.jmusicbot.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the small pure helpers on {@link WebServer}: the pre-paint theme injection and the
 * rule that keeps the bot token out of the settings response.
 */
class WebServerUnitTest
{
    private static final String PAGE = """
            <!doctype html>
            <html lang="en" data-astryx-theme="neutral">
              <head><title>JMusicBot</title></head>
            </html>
            """;

    @Test
    void injectsThemeWhenMissing()
    {
        String result = WebServer.injectTheme(PAGE, "dark");
        assertTrue(result.contains("<html lang=\"en\" data-astryx-theme=\"neutral\" data-theme=\"dark\">"));
        assertEquals(1, count(result, "data-theme="));
    }

    @Test
    void replacesExistingTheme()
    {
        String result = WebServer.injectTheme(PAGE, "dark");
        String again = WebServer.injectTheme(result, "light");
        assertTrue(again.contains("data-theme=\"light\""));
        assertFalse(again.contains("data-theme=\"dark\""));
        assertEquals(1, count(again, "data-theme="));
    }

    @Test
    void leavesPageAloneWithoutATheme()
    {
        assertEquals(PAGE, WebServer.injectTheme(PAGE, null));
        assertEquals(PAGE, WebServer.injectTheme(PAGE, "system"));
        assertEquals(PAGE, WebServer.injectTheme(PAGE, ""));
    }

    @Test
    void leavesMalformedPageAlone()
    {
        assertEquals("no root here", WebServer.injectTheme("no root here", "dark"));
        assertEquals("<html", WebServer.injectTheme("<html", "dark"));
    }

    @Test
    void onlyTheTokenIsSecret()
    {
        assertTrue(WebServer.isSecretKey("discord.token"));
        assertFalse(WebServer.isSecretKey("discord.owner"));
        assertFalse(WebServer.isSecretKey("library.ytdlpCommand"));
        assertFalse(WebServer.isSecretKey(""));
    }

    private static int count(String text, String needle)
    {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0)
        {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
