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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.config.io.ConfigIO;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.parser.ConfigDocument;
import com.typesafe.config.parser.ConfigDocumentFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A small local HTTP server for the web GUI.
 *
 * <p>It serves the built React application from {@code webui/dist} (a source checkout) or
 * {@code web/} (a package) and a JSON API under {@code /api}. It binds to 127.0.0.1 only, so
 * it is not reachable from the network.
 *
 * @author Arif Banai (arif-banai)
 */
public class WebServer
{
    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Bot bot;
    private final Path webRoot;
    private HttpServer server;
    private int port;

    public WebServer(Bot bot)
    {
        this.bot = bot;
        this.webRoot = resolveWebRoot(OtherUtil.getPath("."), codeDirectory());
    }

    /**
     * Picks the folder the web UI is served from.
     *
     * <p>A source checkout serves the build output directly, so a forgotten copy in
     * {@code web/} can never shadow a newer build. A packaged bot has no {@code webui/} and
     * serves {@code web/}. A jar started from {@code target/} with another working directory
     * still finds the build of the checkout it belongs to.
     *
     * @param workingDirectory the directory the bot was started in
     * @param codeDirectory the directory holding the jar or classes, may be null
     * @return the first candidate that exists, or {@code web/} in the working directory
     */
    public static Path resolveWebRoot(Path workingDirectory, Path codeDirectory)
    {
        List<Path> candidates = new ArrayList<>();
        candidates.add(workingDirectory.resolve("webui/dist"));
        candidates.add(workingDirectory.resolve("web"));
        if (codeDirectory != null)
        {
            candidates.add(codeDirectory.resolve("web"));
            if (codeDirectory.getParent() != null)
                candidates.add(codeDirectory.getParent().resolve("webui/dist"));
        }

        for (Path candidate : candidates)
            if (Files.isDirectory(candidate))
                return candidate.toAbsolutePath().normalize();
        return candidates.get(1).toAbsolutePath().normalize();
    }

    /**
     * @return the directory that holds the running jar (or classes), or null when unknown
     */
    private static Path codeDirectory()
    {
        try
        {
            Path location = Path.of(JMusicBot.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Starts the server on an ephemeral localhost port.
     *
     * @return the port, or -1 when the server could not start
     */
    public int start()
    {
        try
        {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/state", guarded(this::handleState));
            server.createContext("/api/logs", guarded(this::handleLogs));
            server.createContext("/api/settings", guarded(this::handleSettings));
            server.createContext("/api/action", guarded(this::handleAction));
            server.createContext("/api/restart", guarded(this::handleRestart));
            server.createContext("/api/theme", guarded(this::handleTheme));
            server.createContext("/", guarded(this::handleStatic));
            server.setExecutor(Executors.newFixedThreadPool(4, runnable ->
            {
                Thread thread = new Thread(runnable, "web-gui");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            port = server.getAddress().getPort();
            LOG.info("Web GUI listening on http://127.0.0.1:{} (serving {})", port, webRoot);
            return port;
        }
        catch (IOException e)
        {
            LOG.error("Could not start the web GUI server: {}", e.getMessage());
            return -1;
        }
    }

    public void stop()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
        }
    }

    public int getPort()
    {
        return port;
    }

    /**
     * Rejects requests that were not addressed to this loopback endpoint. Binding to
     * {@code 127.0.0.1} is not enough on its own: DNS rebinding makes an attacker's page appear
     * same-origin, so the {@code Host} and {@code Origin} headers are checked instead of assumed.
     *
     * @param handler the handler to guard
     * @return a handler that answers 403 for foreign requests
     */
    private HttpHandler guarded(HttpHandler handler)
    {
        return exchange ->
        {
            if (!isLocalRequest(exchange))
            {
                sendJson(exchange, 403, Map.of("error", "forbidden"));
                return;
            }
            try
            {
                handler.handle(exchange);
            }
            catch (IOException | RuntimeException e)
            {
                // Without an answer the page waits until its request times out.
                boolean badRequest = e instanceof JacksonException || e instanceof ClassCastException;
                if (!badRequest)
                    LOG.warn("Web GUI request {} failed: {}", exchange.getRequestURI().getPath(), e.toString());
                try
                {
                    sendJson(exchange, badRequest ? 400 : 500,
                            Map.of("ok", false, "error", badRequest ? "invalid request" : "internal error"));
                }
                catch (IOException | RuntimeException alreadyAnswered)
                {
                    exchange.close();
                }
            }
        };
    }

    private boolean isLocalRequest(HttpExchange exchange)
    {
        return LocalRequestGuard.allows(
                exchange.getRequestHeaders().getFirst("Host"),
                exchange.getRequestHeaders().getFirst("Origin"),
                port);
    }

    // ========== API ==========

    private void handleState(HttpExchange exchange) throws IOException
    {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }

        Map<String, Object> state = new LinkedHashMap<>();
        JDA jda = bot.getJDA();
        Guild guild = selectGuild(exchange, jda);

        state.put("bot", botInfo(jda));
        state.put("guilds", guilds(jda));
        state.put("guild", guild == null ? null : Map.of(
                "id", guild.getId(),
                "name", guild.getName()));
        state.put("nowPlaying", nowPlaying(guild));
        state.put("queue", queue(guild));
        state.put("history", history(guild));
        state.put("performance", performance());
        sendJson(exchange, 200, state);
    }

    private void handleLogs(HttpExchange exchange) throws IOException
    {
        int lines = 200;
        String query = exchange.getRequestURI().getQuery();
        if (query != null)
        {
            for (String part : query.split("&"))
            {
                if (part.startsWith("lines="))
                {
                    try
                    {
                        lines = Math.max(10, Math.min(2000, Integer.parseInt(part.substring(6))));
                    }
                    catch (NumberFormatException ignored)
                    {
                    }
                }
            }
        }
        sendJson(exchange, 200, Map.of("lines", readLogTail(lines)));
    }

    private void handleSettings(HttpExchange exchange) throws IOException
    {
        Path path = ConfigIO.getConfigPath();
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            if (!Files.isRegularFile(path))
            {
                sendJson(exchange, 200, Map.of("path", path.toAbsolutePath().toString(), "entries", List.of()));
                return;
            }

            Config config = ConfigFactory.parseFile(path.toFile()).resolve();
            Config defaults = loadDefaults();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<String, ConfigValue> entry : config.entrySet())
            {
                String key = entry.getKey();
                if (key.equals("meta.configVersion"))
                    continue;

                boolean secret = isSecretKey(key);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", key);
                item.put("secret", secret);
                switch (entry.getValue().valueType())
                {
                    case BOOLEAN ->
                    {
                        item.put("type", "boolean");
                        item.put("value", config.getBoolean(key));
                    }
                    case NUMBER ->
                    {
                        item.put("type", "number");
                        item.put("value", entry.getValue().unwrapped());
                    }
                    case LIST ->
                    {
                        item.put("type", "list");
                        item.put("value", config.getStringList(key));
                    }
                    default ->
                    {
                        item.put("type", "string");
                        // Never send the bot token to the page; it is write-only.
                        item.put("value", secret ? "" : config.getString(key));
                    }
                }

                if (defaults.hasPath(key))
                {
                    ConfigValue defaultValue = defaults.getValue(key);
                    switch (defaultValue.valueType())
                    {
                        case BOOLEAN -> item.put("defaultValue", defaults.getBoolean(key));
                        case NUMBER -> item.put("defaultValue", defaultValue.unwrapped());
                        case LIST -> item.put("defaultValue", defaults.getStringList(key));
                        default -> item.put("defaultValue", defaults.getString(key));
                    }
                }
                item.put("resettable", !key.equals("discord.token") && !key.equals("discord.owner"));
                entries.add(item);
            }
            entries.sort(Comparator.comparing(item -> String.valueOf(item.get("key"))));
            sendJson(exchange, 200, Map.of("path", path.toAbsolutePath().toString(), "entries", entries));
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            try
            {
                Map<?, ?> body = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                if (!(body.get("values") instanceof Map<?, ?> values))
                    throw new IllegalArgumentException("missing values");

                String updated = applySettings(
                        Files.readString(path, StandardCharsets.UTF_8), loadDefaults(), values);
                // Write next to the file and swap it in, so a crash cannot leave half a config.
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temp, updated, StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                sendJson(exchange, 200, Map.of("ok", true, "restartRequired", true));
            }
            catch (Exception e)
            {
                sendJson(exchange, 400, Map.of("ok", false, "error", e.getMessage()));
            }
            return;
        }
        sendJson(exchange, 405, Map.of("error", "method not allowed"));
    }

    private void handleTheme(HttpExchange exchange) throws IOException
    {
        Path file = themeFile();
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            String saved = readThemeMode();
            // "system" lets the page follow the OS until the user picks a side.
            sendJson(exchange, 200, Map.of("mode", saved == null ? "system" : saved));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            Map<?, ?> body = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            String mode = String.valueOf(body.get("mode"));
            if (!mode.equals("dark") && !mode.equals("light"))
            {
                sendJson(exchange, 400, Map.of("ok", false, "error", "mode must be light or dark"));
                return;
            }
            Files.writeString(file, mode, StandardCharsets.UTF_8);
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }
        sendJson(exchange, 405, Map.of("error", "method not allowed"));
    }

    private void handleRestart(HttpExchange exchange) throws IOException
    {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        // Only shut down once something is in place to start the bot again; a restart button
        // that just closes the bot is worse than one that reports it cannot restart.
        Path launcher = findLauncher();
        if (launcher == null)
        {
            LOG.error("Could not find run-gui.bat or run.bat next to the jar; restart manually.");
            sendJson(exchange, 500, Map.of("ok", false,
                    "error", "run-gui.bat or run.bat was not found next to the jar. Restart the bot manually."));
            return;
        }
        try
        {
            startRelauncher(launcher, ProcessHandle.current().pid());
        }
        catch (IOException e)
        {
            LOG.error("Could not start the launcher for a restart: {}", e.getMessage());
            sendJson(exchange, 500, Map.of("ok", false,
                    "error", "The launcher could not be started. Restart the bot manually."));
            return;
        }
        LOG.info("Restarting with {}", launcher);
        sendJson(exchange, 200, Map.of("ok", true));
        bot.shutdown();
    }

    /**
     * Starts a helper that waits for the given process to exit and then runs the launcher.
     *
     * <p>Waiting for the process, instead of a fixed delay, means the new bot only starts after
     * this one has released the instance lock and stopped the cipher server. The launcher path
     * and the process id travel in environment variables, so no quoting of the path is needed.
     *
     * @param launcher the batch file to run
     * @param pid the process to wait for
     * @return the helper process
     * @throws IOException when the helper cannot be started
     */
    public static Process startRelauncher(Path launcher, long pid) throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden",
                "-Command",
                "Wait-Process -Id $env:JMB_RESTART_PID -Timeout 60 -ErrorAction SilentlyContinue; "
                        + "Start-Process -FilePath $env:JMB_RESTART_LAUNCHER -WindowStyle Hidden");
        builder.environment().put("JMB_RESTART_PID", Long.toString(pid));
        builder.environment().put("JMB_RESTART_LAUNCHER", launcher.toString());
        builder.directory(launcher.getParent().toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /**
     * Finds the launcher next to the jar, falling back to the working directory.
     *
     * @return the launcher, or null when neither run-gui.bat nor run.bat exists
     */
    private static Path findLauncher()
    {
        List<Path> directories = new ArrayList<>();
        directories.add(codeDirectory());
        directories.add(Path.of(".").toAbsolutePath().normalize());

        for (Path directory : directories)
        {
            if (directory == null)
                continue;
            Path gui = directory.resolve("run-gui.bat");
            if (Files.isRegularFile(gui))
                return gui.toAbsolutePath().normalize();
            Path headless = directory.resolve("run.bat");
            if (Files.isRegularFile(headless))
                return headless.toAbsolutePath().normalize();
        }
        return null;
    }

    /**
     * The defaults used by the reset buttons. A packaged {@code config.defaults} wins, so a
     * distribution can reset to its own intended values (for example the bundled yt-dlp).
     *
     * @return the default configuration
     */
    private static Config loadDefaults()
    {
        Path packaged = OtherUtil.getPath("config.defaults");
        if (Files.isRegularFile(packaged))
        {
            try
            {
                return ConfigFactory.parseFile(packaged.toFile()).resolve();
            }
            catch (Exception e)
            {
                LOG.warn("Could not read config.defaults, using the built-in defaults: {}", e.getMessage());
            }
        }
        return ConfigIO.loadDefaults();
    }

    private static Path themeFile()
    {
        return OtherUtil.getPath("gui-theme.txt");
    }

    /**
     * Reads the theme the user picked last time.
     *
     * @return {@code "light"} or {@code "dark"}, or null when the user has never picked one
     */
    static String readThemeMode()
    {
        Path file = themeFile();
        if (!Files.isRegularFile(file))
            return null;
        try
        {
            String mode = Files.readString(file, StandardCharsets.UTF_8).trim();
            return mode.equals("dark") || mode.equals("light") ? mode : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * Writes the saved theme into the served {@code index.html} so the first paint already
     * matches it. Without this the page renders light until the theme request resolves, which
     * is a visible flash on every dark-mode start.
     *
     * @param html the file contents
     * @param mode {@code "light"}, {@code "dark"} or null to leave the file untouched
     * @return the contents with {@code data-theme} set on the root element
     */
    public static String injectTheme(String html, String mode)
    {
        if (mode == null || (!mode.equals("light") && !mode.equals("dark")))
            return html;

        int start = html.indexOf("<html");
        if (start < 0)
            return html;
        int end = html.indexOf('>', start);
        if (end < 0)
            return html;

        String tag = html.substring(start, end)
                .replaceAll("\\s+data-theme\\s*=\\s*\"[^\"]*\"", "");
        return html.substring(0, start) + tag + " data-theme=\"" + mode + "\""
                + html.substring(end);
    }

    /**
     * @param key the configuration key
     * @return true when the value must never be sent back to the page
     */
    public static boolean isSecretKey(String key)
    {
        return "discord.token".equals(key);
    }

    /**
     * Applies edited settings to the text of {@code config.txt}, keeping its comments.
     *
     * <p>Only settings that already exist in the file can be changed, and each value is written
     * in the type the setting has (taken from the defaults, else from the file) rather than the
     * type the request claims. The result is parsed again before it is returned, so a file the
     * bot could not read back is never written.
     *
     * @param hocon the current file contents
     * @param defaults the default configuration, used for the type of each setting; may be null
     * @param values setting key to a map holding the new {@code value}
     * @return the new file contents
     * @throws IllegalArgumentException when a key is unknown or a value does not fit its setting
     */
    public static String applySettings(String hocon, Config defaults, Map<?, ?> values)
    {
        Config current = ConfigFactory.parseString(hocon).resolve();
        ConfigDocument document = ConfigDocumentFactory.parseString(hocon);
        for (Map.Entry<?, ?> entry : values.entrySet())
        {
            String key = String.valueOf(entry.getKey());
            if (key.equals("meta.configVersion") || !hasSetting(current, key))
                throw new IllegalArgumentException("unknown setting: " + key);

            Object value = entry.getValue() instanceof Map<?, ?> item ? item.get("value") : null;
            if (value == null)
                throw new IllegalArgumentException(key + " has no value");
            // An empty secret means "leave it alone"; the page never receives the value.
            if (isSecretKey(key) && String.valueOf(value).isBlank())
                continue;

            ConfigValue shape = defaults != null && hasSetting(defaults, key)
                    ? defaults.getValue(key)
                    : current.getValue(key);
            document = document.withValueText(key, renderHocon(key, shape, value));
        }

        String rendered = document.render();
        try
        {
            ConfigFactory.parseString(rendered).resolve();
        }
        catch (ConfigException e)
        {
            throw new IllegalArgumentException("the settings would not be readable: " + e.getMessage());
        }
        return rendered;
    }

    private static boolean hasSetting(Config config, String key)
    {
        try
        {
            return config.hasPath(key);
        }
        catch (ConfigException e)
        {
            return false;
        }
    }

    private static String renderHocon(String key, ConfigValue shape, Object value)
    {
        return switch (shape.valueType())
        {
            case BOOLEAN ->
            {
                String text = String.valueOf(value).trim();
                if (!text.equals("true") && !text.equals("false"))
                    throw new IllegalArgumentException(key + " must be true or false");
                yield text;
            }
            case NUMBER ->
            {
                BigDecimal number;
                try
                {
                    // Unlike Double.parseDouble this refuses NaN, Infinity and suffixes like 1d.
                    number = new BigDecimal(String.valueOf(value).trim());
                }
                catch (NumberFormatException e)
                {
                    throw new IllegalArgumentException(key + " must be a number");
                }
                boolean whole = shape.unwrapped() instanceof Integer || shape.unwrapped() instanceof Long;
                if (!whole)
                    yield number.toPlainString();
                if (number.stripTrailingZeros().scale() > 0)
                    throw new IllegalArgumentException(key + " must be a whole number");
                yield number.toBigInteger().toString();
            }
            case LIST ->
            {
                if (!(value instanceof List<?> list))
                    throw new IllegalArgumentException(key + " must be a list");
                StringBuilder builder = new StringBuilder("[ ");
                for (int i = 0; i < list.size(); i++)
                {
                    if (i > 0)
                        builder.append(", ");
                    builder.append(quote(String.valueOf(list.get(i))));
                }
                yield builder.append(" ]").toString();
            }
            case STRING -> quote(String.valueOf(value));
            default -> throw new IllegalArgumentException(key + " cannot be edited here");
        };
    }

    private static String quote(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void handleAction(HttpExchange exchange) throws IOException
    {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }

        Map<?, ?> body = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        String action = String.valueOf(body.get("action"));
        Object value = body.get("value");
        Guild guild = selectGuild(exchange, bot.getJDA());
        if (guild == null)
        {
            sendJson(exchange, 409, Map.of("ok", false, "error", "no guild"));
            return;
        }

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
        {
            sendJson(exchange, 409, Map.of("ok", false, "error", "no player"));
            return;
        }

        Reply reply = new Reply();
        try
        {
            switch (action)
            {
                case "pause" -> handler.getPlayer().setPaused(!handler.getPlayer().isPaused());
                case "skip" -> handler.getPlayer().stopTrack();
                case "stop" ->
                {
                    handler.stopAndClearQueuePreserveHistory();
                    guild.getAudioManager().closeAudioConnection();
                }
                case "volume" ->
                {
                    int volume = Math.max(0, Math.min(150, ((Number) value).intValue()));
                    handler.getPlayer().setVolume(volume);
                    bot.getSettingsManager().getSettings(guild).setVolume(volume);
                }
                case "seek" ->
                {
                    AudioTrack track = handler.getPlayer().getPlayingTrack();
                    if (track != null)
                        track.setPosition(Math.max(0, ((Number) value).longValue()));
                }
                case "shuffle" -> handler.getQueue().shuffle(0);
                case "repeat" -> cycleRepeat(guild);
                case "favorite" -> bot.getMusicService().toggleCurrentTrackFavorite(guild, guiMember(guild), reply);
                case "previous" -> bot.getMusicService().previous(guild, guiMember(guild), reply);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            if (reply.error != null)
            {
                sendJson(exchange, 409, Map.of("ok", false, "error", reply.error));
                return;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            if (reply.message != null)
                result.put("message", reply.message);
            sendJson(exchange, 200, result);
        }
        catch (Exception e)
        {
            sendJson(exchange, 400, Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // ========== State helpers ==========

    private Map<String, Object> botInfo(JDA jda)
    {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("online", jda != null && jda.getStatus() == JDA.Status.CONNECTED);
        info.put("name", jda == null ? "JMusicBot" : jda.getSelfUser().getName());
        info.put("avatar", jda == null ? "" : jda.getSelfUser().getEffectiveAvatarUrl());
        info.put("guilds", jda == null ? 0 : jda.getGuilds().size());
        info.put("uptimeSeconds", (System.currentTimeMillis() - bot.getStartTime().toEpochMilli()) / 1000);
        info.put("version", version());
        info.put("libraryTracks", bot.getMusicLibrary() == null ? 0 : bot.getMusicLibrary().listTracks().size());
        info.put("ytdlp", bot.getYtDlp() != null);
        var cipher = JMusicBot.getLocalCipherService();
        info.put("cipher", cipher == null ? "disabled" : (cipher.isRunning() ? "running" : "stopped"));
        return info;
    }

    private List<Map<String, Object>> guilds(JDA jda)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (jda != null)
        {
            for (Guild guild : jda.getGuilds())
                list.add(Map.of("id", guild.getId(), "name", guild.getName()));
        }
        return list;
    }

    private Guild selectGuild(HttpExchange exchange, JDA jda)
    {
        if (jda == null || jda.getGuilds().isEmpty())
            return null;

        String requested = null;
        String query = exchange.getRequestURI().getQuery();
        if (query != null)
        {
            for (String part : query.split("&"))
                if (part.startsWith("guild="))
                    requested = part.substring(6);
        }
        if (requested != null)
        {
            Guild guild = jda.getGuildById(requested);
            if (guild != null)
                return guild;
        }
        for (Guild guild : jda.getGuilds())
        {
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if (handler != null && handler.getPlayer().getPlayingTrack() != null)
                return guild;
        }
        return jda.getGuilds().get(0);
    }

    private Map<String, Object> nowPlaying(Guild guild)
    {
        if (guild == null)
            return null;
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
            return null;
        AudioTrack track = handler.getPlayer().getPlayingTrack();
        if (track == null)
            return null;

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", track.getInfo().title);
        info.put("author", track.getInfo().author);
        info.put("uri", track.getInfo().uri);
        info.put("artwork", track.getInfo().artworkUrl);
        info.put("durationMs", track.getDuration());
        info.put("positionMs", track.getPosition());
        info.put("paused", handler.getPlayer().isPaused());
        info.put("volume", handler.getPlayer().getVolume());
        info.put("repeat", repeatName(guild));
        info.put("favorited", handler.isCurrentTrackFavorited());
        info.put("source", sourceName(track));
        RequestMetadata metadata = handler.getRequestMetadata();
        info.put("requester", metadata == null || metadata.user == null ? "" : metadata.user.username);
        return info;
    }

    private List<Map<String, Object>> queue(Guild guild)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (guild == null)
            return list;
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
            return list;
        int position = 1;
        for (QueuedTrack queued : handler.getQueue().getList())
        {
            AudioTrack track = queued.getTrack();
            Map<String, Object> item = new LinkedHashMap<>();
            // Stable for the life of the track, so the page can keep a row instead of
            // remounting every row when the queue shifts.
            item.put("id", Integer.toHexString(System.identityHashCode(queued)));
            item.put("position", position++);
            item.put("title", track.getInfo().title);
            item.put("author", track.getInfo().author);
            item.put("durationMs", track.getDuration());
            RequestMetadata metadata = queued.getRequestMetadata();
            item.put("requester", metadata == null || metadata.user == null ? "" : metadata.user.username);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> history(Guild guild)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (guild == null)
            return list;
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
            return list;
        for (int i = 0; i < handler.getQueue().getHistory().size(); i++)
        {
            QueuedTrack queued = handler.getQueue().getHistory().get(i);
            AudioTrack track = queued.getTrack();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Integer.toHexString(System.identityHashCode(queued)));
            item.put("title", track.getInfo().title);
            item.put("author", track.getInfo().author);
            item.put("durationMs", track.getDuration());
            list.add(item);
        }
        return list;
    }

    private Map<String, Object> performance()
    {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("heapUsedMb", used / (1024 * 1024));
        info.put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        info.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        info.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return info;
    }

    private String repeatName(Guild guild)
    {
        Settings settings = bot.getSettingsManager().getSettings(guild);
        RepeatMode mode = settings.getRepeatMode();
        return mode == null ? "OFF" : mode.name();
    }

    private void cycleRepeat(Guild guild)
    {
        Settings settings = bot.getSettingsManager().getSettings(guild);
        RepeatMode mode = settings.getRepeatMode();
        RepeatMode next = switch (mode == null ? RepeatMode.OFF : mode)
        {
            case OFF -> RepeatMode.ALL;
            case ALL -> RepeatMode.SINGLE;
            case SINGLE -> RepeatMode.OFF;
        };
        settings.setRepeatMode(next);
    }

    private Member guiMember(Guild guild)
    {
        Member owner = guild.getOwner();
        return owner != null ? owner : guild.getSelfMember();
    }

    private static String sourceName(AudioTrack track)
    {
        try
        {
            return track.getSourceManager() == null ? "" : track.getSourceManager().getSourceName();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private static String version()
    {
        String version = JMusicBot.class.getPackage().getImplementationVersion();
        return version == null ? "0.7.0" : version;
    }

    private List<String> readLogTail(int lines)
    {
        Path log = OtherUtil.getPath("logs/jmusicbot.log");
        if (!Files.isRegularFile(log))
            return List.of();
        try
        {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return new ArrayList<>(all.subList(from, all.size()));
        }
        catch (IOException e)
        {
            return List.of("Could not read the log: " + e.getMessage());
        }
    }

    // ========== HTTP helpers ==========

    private void handleStatic(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/"))
            path = "/index.html";

        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file))
        {
            byte[] body = ("Web UI not found. Expected " + webRoot).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
            return;
        }

        byte[] body = Files.readAllBytes(file);
        if ("index.html".equalsIgnoreCase(file.getFileName().toString()))
        {
            // Seed the saved theme before the bundle runs so dark mode does not flash light.
            body = injectTheme(new String(body, StandardCharsets.UTF_8), readThemeMode())
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    private static String contentType(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException
    {
        byte[] body = MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * Collects what a service call would have answered in Discord, so the page can show it
     * instead of reporting every button press as a success.
     *
     * <p>Answers that arrive after the request was answered (a download that fails a minute
     * later) cannot reach the page any more, so errors are also written to the log, which the
     * page displays.
     */
    private static final class Reply implements MusicService.OutputAdapter
    {
        private volatile String error;
        private volatile String message;

        @Override
        public void replySuccess(String content)
        {
            message = plain(content);
        }

        @Override
        public void replyError(String content)
        {
            error = plain(content);
            LOG.warn("Web GUI action failed: {}", error);
        }

        @Override
        public void replyWarning(String content)
        {
            message = plain(content);
        }

        @Override public void editMessage(String content) { }
        @Override public void editMessage(String content, Consumer<Message> onSuccess) { }
        @Override public void editNowPlaying(AudioHandler handler) { }
        @Override public void editNoMusic(AudioHandler handler) { }
        @Override public void onShowHelp() { }

        /** Drops the Discord markdown the service messages are written with. */
        private static String plain(String content)
        {
            return content == null ? "" : content.replace("**", "").replace("`", "");
        }
    }
}
