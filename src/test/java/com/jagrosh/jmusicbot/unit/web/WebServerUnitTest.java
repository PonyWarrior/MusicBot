package com.jagrosh.jmusicbot.unit.web;

import com.jagrosh.jmusicbot.web.WebServer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the pure helpers on {@link WebServer}: the pre-paint theme injection, what the
 * settings form is told and what it may change, where the page is served from and how a
 * stream is reported.
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

    private static Path page(Path folder) throws IOException
    {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("index.html"), PAGE);
        return folder;
    }

    @Test
    void aCheckoutServesItsBuildOutputBeforeAnOldCopy(@TempDir Path work) throws IOException
    {
        page(work.resolve("web"));
        Path build = page(work.resolve("webui/dist"));
        assertEquals(build, WebServer.resolveWebRoot(work, null, false));
        assertEquals(build, WebServer.resolveWebRoot(work, null, true));
    }

    @Test
    void aJarInTargetFindsTheBuildOfItsCheckout(@TempDir Path root) throws IOException
    {
        Path work = Files.createDirectories(root.resolve("elsewhere"));
        Path target = Files.createDirectories(root.resolve("clone/target"));
        Path build = page(root.resolve("clone/webui/dist"));
        assertEquals(build, WebServer.resolveWebRoot(work, target, true));
    }

    @Test
    void aJarWithoutTheWebUiServesAWebFolder(@TempDir Path root) throws IOException
    {
        Path work = Files.createDirectories(root.resolve("work"));
        Path code = Files.createDirectories(root.resolve("code"));
        Path besideTheJar = page(code.resolve("web"));
        assertEquals(besideTheJar, WebServer.resolveWebRoot(work, code, false));

        Path inTheWorkingDirectory = page(work.resolve("web"));
        assertEquals(inTheWorkingDirectory, WebServer.resolveWebRoot(work, code, false));
    }

    @Test
    void aWebFolderOfAnOlderPackageDoesNotReplaceThePageInTheJar(@TempDir Path work) throws IOException
    {
        page(work.resolve("web"));
        assertNull(WebServer.resolveWebRoot(work, work, true));
    }

    @Test
    void aFolderWithoutAPageIsNotAWebUi(@TempDir Path work) throws IOException
    {
        Files.createDirectories(work.resolve("webui/dist"));
        Files.createDirectories(work.resolve("web"));
        assertNull(WebServer.resolveWebRoot(work, null, false));
        assertNull(WebServer.resolveWebRoot(work, work.resolve("target"), false));
    }

    private static Map<String, Object> setting(String hocon, String defaults, String key)
    {
        return WebServer.describeSettings(ConfigFactory.parseString(hocon), ConfigFactory.parseString(defaults))
                .stream().filter(item -> key.equals(item.get("key"))).findFirst().orElseThrow();
    }

    @Test
    void numbersTravelAsTextSoALongIdKeepsEveryDigit()
    {
        Map<String, Object> owner = setting("discord.owner = 123456789012345678", "discord.owner = 0",
                "discord.owner");
        assertEquals("number", owner.get("type"));
        assertEquals("123456789012345678", owner.get("value"));
        assertEquals("0", owner.get("defaultValue"));

        assertEquals("0.55", setting("playback.skipRatio = 0.55", "", "playback.skipRatio").get("value"));
        assertEquals("0.0000001", setting("x.tiny = 1e-7", "", "x.tiny").get("value"));
        assertEquals("40", setting("playback.maxHistorySize = 40", "", "playback.maxHistorySize").get("value"));
    }

    @Test
    void theTokenIsNeverDescribedWithItsValue()
    {
        Map<String, Object> token = setting("discord.token = \"secret-token\"",
                "discord.token = BOT_TOKEN_HERE", "discord.token");
        assertEquals("", token.get("value"));
        assertEquals(true, token.get("secret"));
        assertEquals(false, token.get("resettable"));
    }

    @Test
    void settingsCarryTheirDescriptionAndChoices()
    {
        Map<String, Object> level = setting("logging.level = info", "", "logging.level");
        assertEquals(List.of("off", "error", "warn", "info", "debug", "trace", "all"), level.get("options"));
        assertTrue(String.valueOf(level.get("description")).startsWith("Logging verbosity"));
        assertFalse(level.containsKey("defaultValue"));

        Map<String, Object> unknown = setting("some.custom = true", "", "some.custom");
        assertFalse(unknown.containsKey("description"));
        assertFalse(unknown.containsKey("options"));
        assertEquals(true, unknown.get("value"));
    }

    @Test
    void settingsAreSortedAndLeaveTheConfigVersionOut()
    {
        List<Map<String, Object>> entries = WebServer.describeSettings(
                ConfigFactory.parseString("b.two = 2\na.one = [ x, y ]\nmeta.configVersion = 2"),
                ConfigFactory.empty());
        assertEquals(List.of("a.one", "b.two"), entries.stream().map(item -> item.get("key")).toList());
        assertEquals(List.of("x", "y"), entries.get(0).get("value"));
        assertEquals("list", entries.get(0).get("type"));
    }

    private static AudioTrack track(boolean stream, long duration)
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("title", "author", duration, "id", stream, "uri"));
        when(track.getDuration()).thenReturn(duration);
        return track;
    }

    @Test
    void aStreamHasNoLength()
    {
        assertFalse(WebServer.isStream(track(false, 200_000)));
        assertEquals(200_000, WebServer.durationOf(track(false, 200_000)));

        assertTrue(WebServer.isStream(track(true, 200_000)));
        assertEquals(0, WebServer.durationOf(track(true, 200_000)));

        // A live stream reports the largest long as its duration.
        assertTrue(WebServer.isStream(track(false, Long.MAX_VALUE)));
        assertEquals(0, WebServer.durationOf(track(false, Long.MAX_VALUE)));
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
