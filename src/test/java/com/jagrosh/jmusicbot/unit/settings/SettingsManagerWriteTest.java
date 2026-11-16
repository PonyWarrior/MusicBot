package com.jagrosh.jmusicbot.unit.settings;

import com.jagrosh.jmusicbot.settings.SettingsManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the temp-file-and-rename write that keeps serversettings.json whole on a failed save.
 */
class SettingsManagerWriteTest
{
    @Test
    void createsAndReplacesWithoutLeavingTempFile(@TempDir Path dir) throws Exception
    {
        Path target = dir.resolve("serversettings.json");

        SettingsManager.writeAtomically(target, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"a\":1}", Files.readString(target));

        SettingsManager.writeAtomically(target, "{\"b\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"b\":2}", Files.readString(target));

        assertFalse(Files.exists(dir.resolve("serversettings.json.tmp")));
    }
}
