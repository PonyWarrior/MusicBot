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
package com.jagrosh.jmusicbot.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads tracks with <a href="https://github.com/yt-dlp/yt-dlp">yt-dlp</a> and converts them
 * to a local audio format.
 *
 * <p>Only the audio stream is fetched and it is converted with ffmpeg, which yt-dlp invokes
 * itself. The download runs in a separate process, so the bot stays responsive while it works.
 *
 * @author Arif Banai (arif-banai)
 */
public class YtDlpDownloader
{
    private static final Logger LOG = LoggerFactory.getLogger(YtDlpDownloader.class);

    private final Path workingDirectory;
    private final List<String> command;
    private final String audioFormat;
    private final long timeoutMinutes;

    /**
     * @param workingDirectory the yt-dlp checkout, used as the working directory of the process
     * @param command the command and the arguments used to start yt-dlp
     * @param audioFormat the audio format the download is converted to
     * @param timeoutMinutes how long a single download may take
     */
    public YtDlpDownloader(Path workingDirectory, List<String> command, String audioFormat, long timeoutMinutes)
    {
        this.workingDirectory = workingDirectory;
        this.command = new ArrayList<>(command);
        this.audioFormat = audioFormat;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Checks that yt-dlp can be started.
     *
     * @return true if yt-dlp is usable
     */
    public boolean isAvailable()
    {
        if (!Files.isDirectory(workingDirectory))
        {
            LOG.error("The yt-dlp directory {} does not exist", workingDirectory.toAbsolutePath());
            return false;
        }
        try
        {
            List<String> args = new ArrayList<>(command);
            args.add("--version");
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES))
            {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0)
            {
                LOG.error("yt-dlp could not be started: {}", output.strip());
                return false;
            }
            LOG.info("yt-dlp is available: {}", output.strip());
            return true;
        }
        catch (IOException e)
        {
            LOG.error("yt-dlp could not be started: {}", e.getMessage());
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Downloads the audio of the given url into the library folder.
     *
     * @param url the url of the track
     * @param libraryFolder the folder to download into
     * @return the path of the downloaded file
     * @throws IOException if the download failed
     */
    public Path download(String url, Path libraryFolder) throws IOException, InterruptedException
    {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
        {
            // yt-dlp would report a confusing "Unsupported url scheme" error for a file path.
            throw new IOException("Not a downloadable URL: " + url);
        }

        List<String> args = new ArrayList<>(command);
        args.add("--no-playlist");
        args.add("--no-warnings");
        args.add("--ignore-config");
        args.add("--no-progress");
        args.add("-f");
        args.add("bestaudio/best");
        args.add("-x");
        args.add("--audio-format");
        args.add(audioFormat);
        args.add("--audio-quality");
        args.add("0");
        args.add("--write-info-json");
        args.add("--embed-metadata");
        args.add("--print");
        args.add("after_move:filepath");
        args.add("-o");
        args.add(libraryFolder.resolve("%(title)s.%(ext)s").toString());
        args.add(url);

        LOG.info("Downloading {} with yt-dlp into {}", url, libraryFolder.toAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output;
        try
        {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            process.destroyForcibly();
            throw e;
        }

        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES))
        {
            process.destroyForcibly();
            throw new IOException("The download timed out after " + timeoutMinutes + " minutes");
        }

        if (process.exitValue() != 0)
        {
            throw new IOException("yt-dlp failed: " + lastLines(output));
        }

        Path downloaded = parseDownloadedPath(output);
        if (downloaded == null)
        {
            throw new IOException("yt-dlp did not report a downloaded file: " + lastLines(output));
        }

        LOG.info("Downloaded {}", downloaded.toAbsolutePath());
        return downloaded;
    }

    /**
     * Reads the path of the downloaded file out of the yt-dlp output.
     *
     * @param output the output of yt-dlp
     * @return the path, or null when it could not be found
     */
    private Path parseDownloadedPath(String output)
    {
        for (String line : output.split("\\R"))
        {
            String trimmed = line.strip();
            if (trimmed.isEmpty())
            {
                continue;
            }
            try
            {
                Path candidate = Paths.get(trimmed);
                if (Files.isRegularFile(candidate))
                {
                    return candidate;
                }
            }
            catch (Exception e)
            {
                // Not a path, keep looking.
            }
        }
        return null;
    }

    /**
     * @param output the output of yt-dlp
     * @return the last few lines of the output, for error messages
     */
    private static String lastLines(String output)
    {
        String[] lines = output.split("\\R");
        int from = Math.max(0, lines.length - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++)
        {
            if (!lines[i].isBlank())
            {
                sb.append(lines[i].strip()).append(" | ");
            }
        }
        return sb.toString();
    }
}
