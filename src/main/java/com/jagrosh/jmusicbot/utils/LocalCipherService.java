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
package com.jagrosh.jmusicbot.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a local <a href="https://github.com/kikkia/yt-cipher">yt-cipher</a> service as a child
 * process of the bot.
 *
 * <p>yt-cipher resolves YouTube's signature cipher server-side. Running it locally means the
 * bot does not have to send playback requests to a third-party service, and the service is
 * stopped together with the bot.
 *
 * <p>The service is started with {@link ProcessBuilder} and torn down again when the JVM
 * exits, through a shutdown hook. A service that is already listening on the configured port is
 * reused instead of starting a second one.
 */
public class LocalCipherService
{
    private static final Logger LOG = LoggerFactory.getLogger(LocalCipherService.class);

    private final Path directory;
    private final List<String> command;
    private final Map<String, String> environment;
    private final int port;
    private final int startupTimeoutSeconds;
    private final Path logFile;

    private Process process;

    /**
     * @param directory the working directory of the service (the yt-cipher checkout)
     * @param command the command and its arguments
     * @param environment additional environment variables for the service
     * @param port the port the service listens on
     * @param startupTimeoutSeconds how long to wait for the service to accept connections
     * @param logFile the file the service output is written to
     */
    public LocalCipherService(Path directory, List<String> command, Map<String, String> environment,
            int port, int startupTimeoutSeconds, Path logFile)
    {
        this.directory = directory;
        this.command = new ArrayList<>(command);
        this.environment = environment;
        this.port = port;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.logFile = logFile;
    }

    /**
     * Starts the service and waits for it to accept connections.
     *
     * @return true if the service is up and running
     */
    public boolean start()
    {
        if (isPortOpen())
        {
            if (killOrphanedService())
            {
                // The stale service is gone, fall through and start our own.
                waitForPortClosed(10);
            }
            else
            {
                LOG.info("Local cipher service is already listening on port {}, reusing it", port);
                return true;
            }
        }

        if (!Files.isDirectory(directory))
        {
            LOG.error("Local cipher directory {} does not exist, cannot start the service", directory.toAbsolutePath());
            return false;
        }

        if (!isCommandResolvable())
        {
            LOG.error("The local cipher command '{}' could not be found. Use an absolute path or make sure it is on the PATH.",
                    command.isEmpty() ? "" : command.get(0));
            return false;
        }

        try
        {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(directory.toFile());
            builder.environment().putAll(environment);
            // Use the module cache that ships next to the checkout, so the service can start
            // without network access. An explicit DENO_DIR from the config still wins.
            Path bundledCache = directory.resolve("deno-cache");
            if (Files.isDirectory(bundledCache))
            {
                builder.environment().putIfAbsent("DENO_DIR", bundledCache.toAbsolutePath().toString());
            }
            builder.redirectErrorStream(true);

            if (logFile != null)
            {
                Files.createDirectories(logFile.getParent());
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            }
            else
            {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }

            process = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "local-cipher-shutdown"));

            LOG.info("Started local cipher service: {} (in {})", String.join(" ", command), directory.toAbsolutePath());
        }
        catch (IOException e)
        {
            LOG.error("Could not start the local cipher service: {}", e.getMessage());
            return false;
        }

        if (waitForPort(startupTimeoutSeconds))
        {
            LOG.info("Local cipher service is listening on port {}", port);
            return true;
        }

        LOG.error("Local cipher service did not start listening on port {} within {} seconds", port,
                startupTimeoutSeconds);
        logCipherOutputTail();
        stop();
        return false;
    }

    /**
     * Logs the last lines of the service output, so a failed start shows the reason instead of
     * only a timeout. The service writes its output to {@link #logFile}.
     */
    private void logCipherOutputTail()
    {
        if (logFile == null || !Files.isRegularFile(logFile))
        {
            return;
        }
        try
        {
            List<String> lines = Files.readAllLines(logFile);
            int from = Math.max(0, lines.size() - 10);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++)
            {
                sb.append(lines.get(i)).append('\n');
            }
            if (!sb.isEmpty())
            {
                LOG.error("Cipher service output:\n{}", sb.toString().strip());
            }
        }
        catch (IOException e)
        {
            LOG.debug("Could not read the cipher service log {}: {}", logFile, e.getMessage());
        }
    }

    /**
     * Stops the service if this instance started it.
     */
    public void stop()
    {
        Process p = process;
        if (p == null)
        {
            return;
        }
        process = null;

        p.destroy();
        p.descendants().forEach(ProcessHandle::destroy);
        try
        {
            if (!p.waitFor(5, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        LOG.info("Stopped local cipher service");
    }

    /**
     * @return true if this instance is managing a running service
     */
    public boolean isRunning()
    {
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * @return true if something is listening on the configured port
     */
    public boolean isPortOpen()
    {
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Looks for a service on the configured port whose parent process is gone, and stops it.
     *
     * <p>When the bot is killed rather than shut down, its shutdown hook does not run and the
     * service would be left behind, holding on to the port. Such an orphan is detected here and
     * removed, so the next start gets a service of its own.
     *
     * @return true if an orphaned service was found and stopped
     */
    private boolean killOrphanedService()
    {
        for (int pid : findPortOwners())
        {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty())
            {
                continue;
            }

            Optional<ProcessHandle> parent = handle.get().parent();
            boolean orphaned = parent.isEmpty() || ProcessHandle.of(parent.get().pid()).isEmpty();
            if (orphaned)
            {
                LOG.warn("Found an orphaned local cipher service (pid {}), stopping it", pid);
                handle.get().destroy();
                try
                {
                    handle.get().onExit().get(5, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    handle.get().destroyForcibly();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the process ids listening on the configured port.
     *
     * <p>Implemented with {@code netstat} on Windows; other platforms simply report nothing,
     * in which case an orphaned service is left alone.
     *
     * @return the process ids listening on the port
     */
    private List<Integer> findPortOwners()
    {
        List<Integer> owners = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
        {
            return owners;
        }

        try
        {
            ProcessBuilder builder = new ProcessBuilder("netstat", "-ano", "-p", "TCP");
            builder.redirectErrorStream(true);
            Process netstat = builder.start();

            List<String> output = new ArrayList<>();
            try (var in = netstat.getInputStream())
            {
                output.addAll(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                        .filter(line -> line.contains("LISTENING"))
                        .toList());
            }
            netstat.waitFor(10, TimeUnit.SECONDS);

            for (String line : output)
            {
                String[] parts = line.trim().split("\\s+");
                // parts[1] is the local address, e.g. 127.0.0.1:8001. Checking the whole column
                // avoids matching a different port such as 80010.
                if (parts.length < 2 || !parts[1].endsWith(":" + port))
                {
                    continue;
                }
                try
                {
                    owners.add(Integer.parseInt(parts[parts.length - 1]));
                }
                catch (NumberFormatException e)
                {
                    // Not a process id.
                }
            }
        }
        catch (Exception e)
        {
            LOG.debug("Could not determine which process listens on port {}: {}", port, e.getMessage());
        }
        return owners;
    }

    /**
     * Waits for the port to become free again.
     *
     * @param timeoutSeconds how long to wait
     */
    private void waitForPortClosed(int timeoutSeconds)
    {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline && isPortOpen())
        {
            try
            {
                Thread.sleep(200);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Checks whether the configured command can be found, either as a path or on the PATH.
     *
     * @return true if the command is resolvable
     */
    private boolean isCommandResolvable()
    {
        if (command.isEmpty())
        {
            return false;
        }

        String exe = command.get(0);
        if (exe.contains("/") || exe.contains("\\"))
        {
            return Files.isRegularFile(Paths.get(exe));
        }

        String path = System.getenv("PATH");
        if (path == null)
        {
            return false;
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator))
        {
            if (dir.isEmpty())
            {
                continue;
            }
            try
            {
                if (Files.isRegularFile(Paths.get(dir, windows ? exe + ".exe" : exe)))
                {
                    return true;
                }
            }
            catch (InvalidPathException e)
            {
                // Ignore malformed PATH entries.
            }
        }
        return false;
    }

    /**
     * Waits for the service to accept connections.
     *
     * @param timeoutSeconds how long to wait
     * @return true if the service is up
     */
    private boolean waitForPort(int timeoutSeconds)
    {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline)
        {
            if (!isRunning())
            {
                // The process died, there is no point in waiting any longer.
                return false;
            }
            if (isPortOpen())
            {
                return true;
            }
            try
            {
                Thread.sleep(250);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
