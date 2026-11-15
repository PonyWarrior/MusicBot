package com.jagrosh.jmusicbot.unit.web;

import com.jagrosh.jmusicbot.web.WebServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String CONFIG = """
            # The bot token
            discord {
              token = "secret-token" # keep me
              owner = 0
            }
            playback {
              skipRatio = 0.55
              maxHistorySize = 40
            }
            library.enabled = true
            commands.aliases.skip = [ "voteskip" ]
            """;

    private static String apply(String key, Object value)
    {
        return WebServer.applySettings(CONFIG, null, Map.of(key, Map.of("type", "ignored", "value", value)));
    }

    @Test
    void settingsKeepCommentsAndUntouchedValues()
    {
        String result = apply("playback.maxHistorySize", 25);
        assertTrue(result.contains("# The bot token"));
        assertTrue(result.contains("token = \"secret-token\" # keep me"));
        assertTrue(result.contains("maxHistorySize = 25"));
        assertTrue(result.contains("skipRatio = 0.55"));
    }

    @Test
    void settingsAreWrittenInTheTypeOfTheSetting()
    {
        // The request cannot turn a boolean into a string by claiming another type.
        assertTrue(apply("library.enabled", false).contains("library.enabled = false"));
        assertTrue(apply("playback.skipRatio", "0.7").contains("skipRatio = 0.7"));
        assertTrue(apply("commands.aliases.skip", List.of("a", "b \"c\""))
                .contains("commands.aliases.skip = [ \"a\", \"b \\\"c\\\"\" ]"));
        assertTrue(apply("discord.token", "new\"token").contains("token = \"new\\\"token\""));
    }

    @Test
    void settingsRejectUnknownKeys()
    {
        assertThrows(IllegalArgumentException.class, () -> apply("dangerous.eval", true));
        assertThrows(IllegalArgumentException.class, () -> apply("discord", "x"));
        assertThrows(IllegalArgumentException.class, () -> apply("not a } path", "x"));
        assertThrows(IllegalArgumentException.class, () -> apply("meta.configVersion", 3));
    }

    @Test
    void settingsRejectValuesOfTheWrongType()
    {
        assertThrows(IllegalArgumentException.class, () -> apply("library.enabled", "yes"));
        assertThrows(IllegalArgumentException.class, () -> apply("playback.skipRatio", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> apply("playback.skipRatio", "Infinity"));
        assertThrows(IllegalArgumentException.class, () -> apply("playback.skipRatio", "1d"));
        assertThrows(IllegalArgumentException.class, () -> apply("playback.maxHistorySize", 2.5));
        assertThrows(IllegalArgumentException.class, () -> apply("commands.aliases.skip", "voteskip"));
        assertThrows(IllegalArgumentException.class,
                () -> WebServer.applySettings(CONFIG, null, Map.of("discord.owner", "no map")));
    }

    @Test
    void wholeNumbersStayWhole()
    {
        assertTrue(apply("playback.maxHistorySize", 30.0).contains("maxHistorySize = 30"));
        assertTrue(apply("discord.owner", "123456789012345678").contains("owner = 123456789012345678"));
    }

    @Test
    void theTypeComesFromTheDefaultsWhenTheyKnowTheSetting()
    {
        // The file holds a whole number, but the setting is a ratio.
        String file = "playback.skipRatio = 1\n";
        Config defaults = ConfigFactory.parseString("playback.skipRatio = 0.55");
        String result = WebServer.applySettings(file, defaults,
                Map.of("playback.skipRatio", Map.of("value", 0.6)));
        assertTrue(result.contains("playback.skipRatio = 0.6"));
    }

    @Test
    void anEmptySecretLeavesTheTokenAlone()
    {
        assertEquals(CONFIG, apply("discord.token", ""));
        assertEquals(CONFIG, apply("discord.token", "   "));
    }

    @Test
    void aCheckoutServesItsBuildOutputBeforeAnOldCopy(@TempDir Path work) throws IOException
    {
        Files.createDirectories(work.resolve("web"));
        Files.createDirectories(work.resolve("webui/dist"));
        assertEquals(work.resolve("webui/dist"), WebServer.resolveWebRoot(work, null));
    }

    @Test
    void aPackageServesItsWebFolder(@TempDir Path work) throws IOException
    {
        Files.createDirectories(work.resolve("web"));
        assertEquals(work.resolve("web"), WebServer.resolveWebRoot(work, work));
    }

    @Test
    void aJarInTargetFindsTheBuildOfItsCheckout(@TempDir Path root) throws IOException
    {
        Path work = Files.createDirectories(root.resolve("elsewhere"));
        Path target = Files.createDirectories(root.resolve("clone/target"));
        Files.createDirectories(root.resolve("clone/webui/dist"));
        assertEquals(root.resolve("clone/webui/dist"), WebServer.resolveWebRoot(work, target));
    }

    @Test
    void aMissingWebUiFallsBackToTheWebFolderOfTheWorkingDirectory(@TempDir Path work)
    {
        assertEquals(work.resolve("web"), WebServer.resolveWebRoot(work, null));
        assertEquals(work.resolve("web"), WebServer.resolveWebRoot(work, work.resolve("target")));
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
