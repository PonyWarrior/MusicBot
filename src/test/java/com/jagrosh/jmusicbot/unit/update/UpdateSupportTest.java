package com.jagrosh.jmusicbot.unit.update;

import com.jagrosh.jmusicbot.update.UpdateComponent;
import com.jagrosh.jmusicbot.update.UpdateSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdateSupportTest
{
    @Test
    void comparesNumericParts()
    {
        assertTrue(UpdateSupport.compareVersions("0.10.0", "0.9.9") > 0);
        assertTrue(UpdateSupport.compareVersions("v0.8.1", "0.8.0") > 0);
        assertEquals(0, UpdateSupport.compareVersions("0.8", "0.8.0"));
        assertTrue(UpdateSupport.compareVersions("2026.11.10", "2026.9.30") > 0);
    }

    @Test
    void releaseSortsAfterItsPrereleases()
    {
        assertTrue(UpdateSupport.compareVersions("0.9.0", "0.9.0-beta.2") > 0);
        assertTrue(UpdateSupport.compareVersions("0.9.0-beta.2", "0.9.0-beta.1") > 0);
        assertTrue(UpdateSupport.compareVersions("0.9.0-beta.1", "0.8.0") > 0);
        assertTrue(UpdateSupport.compareVersions("0.9.0-rc.1", "0.9.0-beta.9") > 0);
        assertTrue(UpdateSupport.isPrerelease("v1.0.0-beta"));
        assertFalse(UpdateSupport.isPrerelease("1.0.0"));
    }

    @Test
    void unknownIsNeverNewerOrOlder()
    {
        assertFalse(UpdateSupport.isNewer("0.9.0", "UNKNOWN"));
        assertFalse(UpdateSupport.isNewer(null, "0.8.0"));
        assertFalse(UpdateSupport.isNewer("0.8.0", "0.8.0"));
        assertTrue(UpdateSupport.isNewer("0.8.1", "0.8.0"));
    }

    @Test
    void parsesChecksumFiles()
    {
        String hex = "a".repeat(64);
        Map<String, String> sums = UpdateSupport.parseSha256Sums(
                hex + "  yt-dlp.exe\r\n" + "B".repeat(64) + " *JMusicBot-0.9.0-windows-x64.zip\n\ngarbage line\n");
        assertEquals(hex, sums.get("yt-dlp.exe"));
        assertEquals("b".repeat(64), sums.get("JMusicBot-0.9.0-windows-x64.zip"));
        assertEquals(2, sums.size());
    }

    @Test
    void parsesToolVersions()
    {
        assertEquals("2.9.7", UpdateSupport.parseDenoVersion("deno 2.9.7 (stable, release, x86_64-pc-windows-msvc)\nv8 14.0"));
        assertEquals("9.0.2", UpdateSupport.parseFfmpegVersion("ffmpeg version 9.0.2-essentials_build-www.gyan.dev Copyright"));
        assertEquals("2026.11.10", UpdateSupport.parseYtDlpVersion("2026.11.10\r\n"));
        assertNull(UpdateSupport.parseDenoVersion("nope"));
        assertNull(UpdateSupport.parseFfmpegVersion(null));
        assertNull(UpdateSupport.parseYtDlpVersion("error"));
    }

    @Test
    void readsBuildInfoCommit()
    {
        assertEquals("abc123", UpdateSupport.readBuildInfoCommit("x=y\nyt-cipher.commit=abc123\n"));
        assertNull(UpdateSupport.readBuildInfoCommit("yt-cipher.commit=\n"));
    }

    @Test
    void insertsWatchdogOnce()
    {
        String source = "import x;\n" + UpdateSupport.WATCHDOG_ANCHOR + "\nrest\n";
        String patched = UpdateSupport.insertWatchdog(source);
        assertTrue(patched.indexOf("Deno.stdin.isTerminal") < patched.indexOf(UpdateSupport.WATCHDOG_ANCHOR));
        assertEquals(patched, UpdateSupport.insertWatchdog(patched));
        assertThrows(IllegalStateException.class, () -> UpdateSupport.insertWatchdog("no anchor"));
    }

    @Test
    void extractsZipAndRejectsTraversal(@TempDir Path dir) throws IOException
    {
        Path good = dir.resolve("good.zip");
        writeZip(good, "folder/bin/ffmpeg.exe", "binary");
        List<Path> files = UpdateSupport.extractZip(good, dir.resolve("out"));
        assertEquals(1, files.size());
        assertEquals("binary", Files.readString(dir.resolve("out/folder/bin/ffmpeg.exe")));

        Path bad = dir.resolve("bad.zip");
        writeZip(bad, "../evil.txt", "x");
        assertThrows(IOException.class, () -> UpdateSupport.extractZip(bad, dir.resolve("out2")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    @Test
    void hashesFiles(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateSupport.sha256(file));
    }

    @Test
    void parsesComponentIds()
    {
        assertEquals(UpdateComponent.YTDLP, UpdateComponent.fromId("yt-dlp"));
        assertEquals(UpdateComponent.YTCIPHER, UpdateComponent.fromId("yt-cipher"));
        assertEquals(UpdateComponent.BOT, UpdateComponent.fromId("BOT"));
        assertEquals(UpdateComponent.BOT, UpdateComponent.fromId("jmusicbot"));
        assertNull(UpdateComponent.fromId("all"));
    }

    private static void writeZip(Path zip, String name, String content) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            zipOut.putNextEntry(new ZipEntry(name));
            zipOut.write(content.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }
}
