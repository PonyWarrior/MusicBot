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
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>It serves the built React application from {@code web/} (or {@code webui/dist} during
 * development) and a JSON API under {@code /api}. It binds to 127.0.0.1 only, so it is not
 * reachable from the network.
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
        Path web = OtherUtil.getPath("web");
        if (!Files.isDirectory(web))
        {
            Path dev = OtherUtil.getPath("webui/dist");
            if (Files.isDirectory(dev))
                web = dev;
        }
        this.webRoot = web.toAbsolutePath().normalize();
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
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/logs", this::handleLogs);
            server.createContext("/api/settings", this::handleSettings);
            server.createContext("/api/action", this::handleAction);
            server.createContext("/api/restart", this::handleRestart);
            server.createContext("/", this::handleStatic);
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
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<String, ConfigValue> entry : config.entrySet())
            {
                String key = entry.getKey();
                if (key.equals("meta.configVersion"))
                    continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", key);
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
                        item.put("value", config.getString(key));
                    }
                }
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
                Map<?, ?> values = (Map<?, ?>) body.get("values");
                if (values == null)
                    throw new IllegalArgumentException("missing values");

                ConfigDocument document = ConfigDocumentFactory.parseFile(path.toFile());
                for (Map.Entry<?, ?> entry : values.entrySet())
                {
                    String key = String.valueOf(entry.getKey());
                    Map<?, ?> item = (Map<?, ?>) entry.getValue();
                    String type = String.valueOf(item.get("type"));
                    Object value = item.get("value");
                    document = document.withValueText(key, renderHocon(type, value));
                }
                Files.writeString(path, document.render(), StandardCharsets.UTF_8);
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

    private void handleRestart(HttpExchange exchange) throws IOException
    {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        sendJson(exchange, 200, Map.of("ok", true));
        restartBot();
    }

    /**
     * Starts the launcher again after a short delay, then shuts this process down. The delay
     * lets the instance lock be released before the new process starts.
     */
    private void restartBot()
    {
        try
        {
            Path launcher = findLauncher();
            if (launcher == null)
            {
                LOG.error("Could not find run-gui.bat or run.bat next to the jar; restart manually.");
                bot.shutdown();
                return;
            }

            String command = "ping -n 3 127.0.0.1 >nul & start \"\" /min \"" + launcher + "\"";
            ProcessBuilder builder = new ProcessBuilder("cmd", "/c", command);
            builder.directory(launcher.getParent().toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            LOG.info("Restarting with {}", launcher);
        }
        catch (IOException e)
        {
            LOG.error("Could not start the launcher for a restart: {}", e.getMessage());
        }
        bot.shutdown();
    }

    /**
     * Finds the launcher next to the jar, falling back to the working directory.
     *
     * @return the launcher, or null when neither run-gui.bat nor run.bat exists
     */
    private static Path findLauncher()
    {
        List<Path> directories = new ArrayList<>();
        try
        {
            Path location = Path.of(JMusicBot.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            directories.add(Files.isDirectory(location) ? location : location.getParent());
        }
        catch (Exception ignored)
        {
        }
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

    private static String renderHocon(String type, Object value)
    {
        return switch (type)
        {
            case "boolean" -> Boolean.parseBoolean(String.valueOf(value)) ? "true" : "false";
            case "number" ->
            {
                String text = String.valueOf(value).trim();
                Double.parseDouble(text);
                yield text;
            }
            case "list" ->
            {
                List<?> list = value instanceof List<?> l ? l : List.of();
                StringBuilder builder = new StringBuilder("[ ");
                for (int i = 0; i < list.size(); i++)
                {
                    if (i > 0)
                        builder.append(", ");
                    builder.append(quote(String.valueOf(list.get(i))));
                }
                yield builder.append(" ]").toString();
            }
            default -> quote(String.valueOf(value));
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
                case "favorite" -> bot.getMusicService().toggleCurrentTrackFavorite(guild, guiMember(guild), NOOP);
                case "previous" -> bot.getMusicService().previous(guild, guiMember(guild), NOOP);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            sendJson(exchange, 200, Map.of("ok", true));
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

    private static final MusicService.OutputAdapter NOOP = new MusicService.OutputAdapter()
    {
        @Override public void replySuccess(String content) { }
        @Override public void replyError(String content) { }
        @Override public void replyWarning(String content) { }
        @Override public void editMessage(String content) { }
        @Override public void editMessage(String content, Consumer<Message> onSuccess) { }
        @Override public void editNowPlaying(AudioHandler handler) { }
        @Override public void editNoMusic(AudioHandler handler) { }
        @Override public void onShowHelp() { }
    };
}
