package com.jagrosh.jmusicbot.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads tracks with <a href="https://github.com/yt-dlp/yt-dlp">yt-dlp</a> and converts them
 * to a local audio format.
 *
 * <p>Only the audio stream is fetched and it is converted with ffmpeg, which yt-dlp invokes
 * itself. The download runs in a separate process, so the bot stays responsive while it works.
 */
public class YtDlpDownloader
{
    private static final Logger LOG = LoggerFactory.getLogger(YtDlpDownloader.class);

    /**
     * The yt-dlp processes that are running, so they can be stopped when the bot exits.
     */
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();

    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                RUNNING.forEach(Process::destroyForcibly), "yt-dlp-shutdown"));
    }

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
     * The command with its executable resolved against the working directory when it is a
     * relative path. That lets the packaged yt-dlp.exe be found without an absolute path.
     *
     * @return the command with an absolute executable when it was found
     */
    private List<String> resolvedCommand()
    {
        List<String> args = new ArrayList<>(command);
        if (args.isEmpty())
            return args;
        Path executable;
        try
        {
            executable = Path.of(args.get(0));
        }
        catch (RuntimeException e)
        {
            return args;
        }
        if (executable.isAbsolute())
            return args;
        Path candidate = workingDirectory.resolve(executable);
        if (Files.isRegularFile(candidate))
            args.set(0, candidate.toAbsolutePath().normalize().toString());
        return args;
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
            List<String> args = resolvedCommand();
            args.add("--version");
            Path outputFile = Files.createTempFile("jmusicbot-ytdlp", ".log");
            Process process = null;
            try
            {
                ProcessBuilder builder = new ProcessBuilder(args);
                builder.directory(workingDirectory.toFile());
                builder.redirectErrorStream(true);
                // The output goes to a file, not a pipe: reading a pipe blocks until EOF, which
                // would make the waitFor timeout below unreachable and let yt-dlp hang forever.
                builder.redirectOutput(outputFile.toFile());
                process = builder.start();
                RUNNING.add(process);
                if (!process.waitFor(2, TimeUnit.MINUTES))
                {
                    process.destroyForcibly();
                    return false;
                }
                String output = Files.readString(outputFile, StandardCharsets.UTF_8);
                if (process.exitValue() != 0)
                {
                    LOG.error("yt-dlp could not be started: {}", output.strip());
                    return false;
                }
                LOG.info("yt-dlp is available: {}", output.strip());
                return true;
            }
            finally
            {
                if (process != null)
                    RUNNING.remove(process);
                Files.deleteIfExists(outputFile);
            }
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

        List<String> args = resolvedCommand();
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

        // The output template must be absolute. yt-dlp runs with its own checkout as the
        // working directory, so a relative template would be resolved against that directory
        // and the tracks would land inside the yt-dlp checkout instead of the library.
        // The video id keeps two different videos with the same title in two files; without
        // it the second download would overwrite the first, and unfavoriting one of them
        // would delete the file of both.
        Path outputTemplate = libraryFolder.toAbsolutePath().normalize().resolve("%(title).180B [%(id)s].%(ext)s");
        args.add(outputTemplate.toString());
        args.add(url);

        LOG.info("Downloading {} with yt-dlp into {}", url, outputTemplate.getParent());

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);

        Path outputFile = Files.createTempFile("jmusicbot-ytdlp", ".log");
        Process process = null;
        try
        {
            // The output goes to a file, not a pipe: reading a pipe blocks until EOF, which
            // would make the waitFor timeout below unreachable and let yt-dlp hang forever.
            builder.redirectOutput(outputFile.toFile());
            process = builder.start();
            RUNNING.add(process);

            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES))
            {
                process.destroyForcibly();
                throw new IOException("The download timed out after " + timeoutMinutes + " minutes");
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0)
            {
                throw new IOException("yt-dlp failed: " + lastLines(output));
            }

            Path downloaded = parseDownloadedPath(output);
            if (downloaded == null)
            {
                throw new IOException("yt-dlp did not report a downloaded file: " + lastLines(output));
            }

            downloaded = ensureInsideLibrary(downloaded, libraryFolder);

            LOG.info("Downloaded {}", downloaded.toAbsolutePath());
            return downloaded;
        }
        finally
        {
            if (process != null)
                RUNNING.remove(process);
            Files.deleteIfExists(outputFile);
        }
    }

    /**
     * Makes sure a downloaded file really ended up in the library folder.
     *
     * <p>The output template is absolute, so this should always hold. It is checked anyway,
     * because a file outside the library is invisible to the library search: it would never be
     * found again and the favourites entry would point at something the library does not know.
     * If it does happen, the file and its info sidecar are moved into the library.
     *
     * @param downloaded the file that was downloaded
     * @param libraryFolder the library folder
     * @return the file inside the library folder
     * @throws IOException if the file could not be moved into the library
     */
    private Path ensureInsideLibrary(Path downloaded, Path libraryFolder) throws IOException
    {
        Path library = libraryFolder.toAbsolutePath().normalize();
        Path absolute = downloaded.toAbsolutePath().normalize();

        if (absolute.startsWith(library))
        {
            return absolute;
        }

        LOG.warn("yt-dlp wrote {} outside the music library, moving it to {}", absolute, library);

        Files.createDirectories(library);
        Path target = library.resolve(absolute.getFileName());

        Files.move(absolute, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // The sidecar carries the title, the artist and the artwork, so it has to follow the
        // audio file or the library loses the metadata.
        Path sourceSidecar = sidecarOf(absolute);
        if (sourceSidecar != null)
        {
            Files.move(sourceSidecar, library.resolve(sourceSidecar.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        return target;
    }

    /**
     * @param file an audio file
     * @return the info sidecar of the file, or null when there is none
     */
    private static Path sidecarOf(Path file)
    {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
        {
            return null;
        }
        Path sidecar = file.resolveSibling(name.substring(0, dot) + ".info.json");
        return Files.isRegularFile(sidecar) ? sidecar : null;
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
