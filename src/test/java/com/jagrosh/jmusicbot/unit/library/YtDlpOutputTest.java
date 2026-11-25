package com.jagrosh.jmusicbot.unit.library;

import com.jagrosh.jmusicbot.library.YtDlpDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yt-dlp output that is not valid UTF-8 (Windows ANSI code page) must not fail the download
 * with "Input length = N".
 */
class YtDlpOutputTest
{
    @TempDir
    Path dir;

    @Test
    void readsUtf8Output() throws Exception
    {
        Path file = dir.resolve("out.log");
        Files.writeString(file, "C:\\Music\\Vos Mères [abc].flac\n", StandardCharsets.UTF_8);
        assertEquals("C:\\Music\\Vos Mères [abc].flac\n", YtDlpDownloader.readOutput(file));
    }

    @Test
    void doesNotFailOnAnsiOutput() throws Exception
    {
        Path file = dir.resolve("out.log");
        Files.write(file, "Vos Mères Les Tricératops".getBytes(Charset.forName("windows-1252")));
        String output = YtDlpDownloader.readOutput(file);
        assertTrue(output.startsWith("Vos M"));
        assertTrue(output.endsWith("ratops"));
    }

    @Test
    void detectsHttp403()
    {
        assertTrue(YtDlpDownloader.isHttp403(
                "ERROR: unable to download video data: HTTP Error 403: Forbidden"));
        assertFalse(YtDlpDownloader.isHttp403("ERROR: Video unavailable"));
        assertFalse(YtDlpDownloader.isHttp403(null));
    }

    @Test
    void summaryPrefersErrorLines()
    {
        String output = "[youtube] abc: Downloading webpage\nWARNING: something minor\n"
                + "ERROR: Video unavailable\n[info] trailing line\n";
        assertEquals("ERROR: Video unavailable | ", YtDlpDownloader.errorSummary(output));
    }

    @Test
    void summaryFallsBackToLastLines()
    {
        assertEquals("one | two | three | ", YtDlpDownloader.errorSummary("zero\none\ntwo\nthree\n"));
    }
}