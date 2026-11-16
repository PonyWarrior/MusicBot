package com.jagrosh.jmusicbot.unit.commands.v1.owner;

import com.jagrosh.jmusicbot.commands.v1.owner.UpdateCmd;
import com.jagrosh.jmusicbot.update.UpdateManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCmdTest
{
    @Test
    void formatsEachComponent()
    {
        String text = UpdateCmd.formatStatus(List.of(
                new UpdateManager.Status("yt-dlp", "yt-dlp", true, "2026.01.01", "2026.02.01", true, null),
                new UpdateManager.Status("deno", "Deno", false, null, null, false, "needs PowerShell"),
                new UpdateManager.Status("ffmpeg", "FFmpeg", true, "", null, false, null)), "");

        assertTrue(text.startsWith("**Updates**\n"));
        assertTrue(text.contains("⬆️ **yt-dlp**: 2026.01.01 (latest 2026.02.01)"));
        assertTrue(text.contains("• **Deno**: not installed - needs PowerShell"));
        assertTrue(text.contains("• **FFmpeg**: unknown\n"));
        assertFalse(text.contains("Last result"));
    }

    @Test
    void appendsLastResult()
    {
        String text = UpdateCmd.formatStatus(List.of(), "Everything is up to date.");
        assertTrue(text.endsWith("\nLast result: Everything is up to date."));
    }
}
