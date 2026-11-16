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
package com.jagrosh.jmusicbot.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.library.YtDlpDownloader;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.ProxyUtil;
import com.jagrosh.jmusicbot.web.WebServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Checks for and installs updates of the bundled tools and of the bot itself.
 *
 * <p>Every download is verified against a SHA-256 published by its source; a download without a
 * checksum is refused. The one exception is yt-cipher, which is fetched by commit hash over TLS
 * from GitHub, so the commit pins the content. A swapped file keeps a {@code .old} backup and is
 * rolled back when the new one does not run.
 *
 * @author Arif Banai (arif-banai)
 */
public class UpdateManager
{
    private static final Logger LOG = LoggerFactory.getLogger(UpdateManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://api.github.com/repos/";
    private static final String BOT_REPO = "PonyWarrior/MusicBot";
    private static final String CIPHER_REPO = "kikkia/yt-cipher";

    /** What the GUI and the owner command show for one component. */
    public record Status(String id, String name, boolean present, String installed, String latest,
                         boolean updateAvailable, String note)
    {
    }

    private final Bot bot;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<UpdateComponent, String> latest = new EnumMap<>(UpdateComponent.class);
    private final Map<UpdateComponent, String> notes = new EnumMap<>(UpdateComponent.class);
    private volatile String lastMessage = "";
    private HttpClient client;

    public UpdateManager(Bot bot)
    {
        this.bot = bot;
    }

    // ========== Public API ==========

    /** @return true while a check or an install is running */
    public boolean isBusy()
    {
        return busy.get();
    }

    /** @return the outcome of the last check or install, for the GUI */
    public String getLastMessage()
    {
        return lastMessage;
    }

    /** @return the installed and latest known version of every component, without network access */
    public synchronized List<Status> getStatus()
    {
        List<Status> list = new ArrayList<>();
        for (UpdateComponent component : UpdateComponent.values())
        {
            String installed = installedVersion(component);
            boolean present = component == UpdateComponent.BOT || installed != null || exists(component);
            String known = latest.get(component);
            boolean available = present && isUpdate(component, known, installed);
            list.add(new Status(component.id(), component.displayName(), present, installed, known,
                    available, notes.getOrDefault(component, "")));
        }
        return list;
    }

    /**
     * Asks every source for its latest version, in the background.
     *
     * @return false when another check or install is already running
     */
    public boolean checkAsync()
    {
        return runAsync(() ->
        {
            checkNow();
            lastMessage = "Checked for updates.";
        });
    }

    /**
     * Installs one component, or every component with an update when {@code component} is null.
     * The bot itself goes last because it restarts the process.
     *
     * @return false when another check or install is already running
     */
    public boolean updateAsync(UpdateComponent component)
    {
        return runAsync(() ->
        {
            checkNow();
            List<UpdateComponent> targets = new ArrayList<>();
            if (component != null)
                targets.add(component);
            else
                for (Status status : getStatus())
                    if (status.updateAvailable())
                        targets.add(UpdateComponent.fromId(status.id()));
            targets.sort((a, b) -> Boolean.compare(a == UpdateComponent.BOT, b == UpdateComponent.BOT));

            if (targets.isEmpty())
            {
                lastMessage = "Everything is up to date.";
                return;
            }
            List<String> results = new ArrayList<>();
            for (UpdateComponent target : targets)
                results.add(target.displayName() + ": " + install(target));
            lastMessage = String.join("; ", results);
            LOG.info("Update result: {}", lastMessage);
        });
    }

    /**
     * Schedules the automatic updates: ten minutes after start, then every six hours. Each run
     * only installs while nothing is playing and no download is running.
     */
    public void scheduleAutoUpdates()
    {
        bot.getThreadpool().scheduleWithFixedDelay(() ->
        {
            if (!bot.getConfig().useAutoUpdates() || !isIdle())
                return;
            updateAsync(null);
        }, 10, 6 * 60, TimeUnit.MINUTES);
    }

    /** @return true when no guild is playing and yt-dlp is not downloading */
    public boolean isIdle()
    {
        if (YtDlpDownloader.isBusy())
            return false;
        JDA jda = bot.getJDA();
        if (jda == null)
            return true;
        for (Guild guild : jda.getGuilds())
        {
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if (handler != null && handler.getPlayer().getPlayingTrack() != null)
                return false;
        }
        return true;
    }

    // ========== Startup markers for the bot update ==========

    /** Called early in startup: tells apply-update.ps1 which process is the new bot. */
    public static void markStarted()
    {
        Path dir = updateDirectory();
        if (!Files.isRegularFile(dir.resolve("pending")))
            return;
        try
        {
            Files.writeString(dir.resolve("started"), String.valueOf(ProcessHandle.current().pid()));
        }
        catch (IOException e)
        {
            LOG.warn("Could not write the update start marker: {}", e.getMessage());
        }
    }

    /** Called once the bot is connected: the update script stops waiting and keeps the new jar. */
    public static void markReady()
    {
        Path dir = updateDirectory();
        try
        {
            if (Files.isRegularFile(dir.resolve("pending")))
                Files.writeString(dir.resolve("ok"), "ok");
            Path result = dir.resolve("result.txt");
            if (Files.isRegularFile(result))
            {
                LOG.info("Last bot update: {}", Files.readString(result, StandardCharsets.UTF_8).trim());
                Files.delete(result);
            }
        }
        catch (IOException e)
        {
            LOG.warn("Could not write the update ready marker: {}", e.getMessage());
        }
    }

    // ========== Checking ==========

    private boolean runAsync(Runnable task)
    {
        if (!busy.compareAndSet(false, true))
            return false;
        Thread thread = new Thread(() ->
        {
            try
            {
                task.run();
            }
            catch (Exception e)
            {
                lastMessage = "Update failed: " + e.getMessage();
                LOG.error("Update failed", e);
            }
            finally
            {
                busy.set(false);
            }
        }, "updater");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void checkNow()
    {
        for (UpdateComponent component : UpdateComponent.values())
        {
            try
            {
                String version = fetchLatestVersion(component);
                synchronized (this)
                {
                    latest.put(component, version);
                    notes.remove(component);
                }
            }
            catch (Exception e)
            {
                LOG.warn("Could not check {} for updates: {}", component.displayName(), e.getMessage());
                synchronized (this)
                {
                    notes.put(component, "check failed: " + e.getMessage());
                }
            }
        }
    }

    private String fetchLatestVersion(UpdateComponent component) throws IOException, InterruptedException
    {
        return switch (component)
        {
            case YTDLP -> UpdateSupport.normalizeVersion(latestRelease("yt-dlp/yt-dlp").path("tag_name").asText());
            case FFMPEG -> UpdateSupport.normalizeVersion(latestRelease("GyanD/codexffmpeg").path("tag_name").asText());
            case DENO -> UpdateSupport.normalizeVersion(latestRelease("denoland/deno").path("tag_name").asText());
            case YTCIPHER -> getJson(API + CIPHER_REPO + "/commits/HEAD").path("sha").asText();
            case BOT -> UpdateSupport.normalizeVersion(botRelease().path("tag_name").asText());
        };
    }

    private JsonNode latestRelease(String repo) throws IOException, InterruptedException
    {
        return getJson(API + repo + "/releases/latest");
    }

    /** The newest bot release; with beta updates on, pre-releases count too. */
    private JsonNode botRelease() throws IOException, InterruptedException
    {
        if (!bot.getConfig().useBetaUpdates())
            return latestRelease(BOT_REPO);
        JsonNode best = null;
        for (JsonNode release : getJson(API + BOT_REPO + "/releases?per_page=20"))
        {
            if (release.path("draft").asBoolean())
                continue;
            if (best == null || UpdateSupport.compareVersions(
                    release.path("tag_name").asText(), best.path("tag_name").asText()) > 0)
                best = release;
        }
        if (best == null)
            throw new IOException("no releases");
        return best;
    }

    private boolean isUpdate(UpdateComponent component, String known, String installed)
    {
        if (known == null || known.isBlank())
            return false;
        if (component == UpdateComponent.YTCIPHER)
            return installed != null && !known.equalsIgnoreCase(installed);
        if (component == UpdateComponent.BOT && UpdateSupport.isPrerelease(known)
                && !bot.getConfig().useBetaUpdates())
            return false;
        return UpdateSupport.isNewer(known, installed);
    }

    // ========== Installed versions and paths ==========

    private String installedVersion(UpdateComponent component)
    {
        try
        {
            return switch (component)
            {
                case YTDLP -> exists(component) ? UpdateSupport.parseYtDlpVersion(run(ytDlpExe(), "--version")) : null;
                case FFMPEG -> exists(component) ? UpdateSupport.parseFfmpegVersion(run(ffmpegExe(), "-version")) : null;
                case DENO -> exists(component) ? UpdateSupport.parseDenoVersion(run(denoExe(), "--version")) : null;
                case YTCIPHER -> Files.isRegularFile(buildInfo())
                        ? UpdateSupport.readBuildInfoCommit(Files.readString(buildInfo(), StandardCharsets.UTF_8))
                        : null;
                case BOT -> UpdateSupport.normalizeVersion(OtherUtil.getCurrentVersion());
            };
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private boolean exists(UpdateComponent component)
    {
        return switch (component)
        {
            case YTDLP -> Files.isRegularFile(ytDlpExe());
            case FFMPEG -> Files.isRegularFile(ffmpegExe());
            case DENO -> Files.isRegularFile(denoExe());
            case YTCIPHER -> Files.isRegularFile(cipherDir().resolve("server.ts"));
            case BOT -> true;
        };
    }

    private Path ytDlpDir()
    {
        return OtherUtil.getPath(bot.getConfig().getLibraryYtdlpDirectory()).toAbsolutePath().normalize();
    }

    private Path ytDlpExe()
    {
        return ytDlpDir().resolve("yt-dlp.exe");
    }

    private Path ffmpegExe()
    {
        return ytDlpDir().resolve("ffmpeg.exe");
    }

    private Path cipherDir()
    {
        return OtherUtil.getPath(bot.getConfig().getLocalCipherDirectory()).toAbsolutePath().normalize();
    }

    private Path denoExe()
    {
        return cipherDir().getParent().resolve("deno.exe");
    }

    private static Path appDir()
    {
        Path dir = WebServer.codeDirectory();
        return dir == null ? Path.of(".").toAbsolutePath().normalize() : dir;
    }

    private static Path buildInfo()
    {
        return appDir().resolve("licenses").resolve("yt-cipher-build-info.txt");
    }

    static Path updateDirectory()
    {
        return appDir().resolve("update");
    }

    // ========== Installing ==========

    private String install(UpdateComponent component)
    {
        if (!isWindows())
            return "automatic updates are only available in the Windows package";
        if (!exists(component))
            return "not installed, skipped";
        // The bot update restarts the process, which would cut off whatever is playing.
        if (component == UpdateComponent.BOT && !isIdle())
            return "music is playing or a download is running, try again when idle";
        try
        {
            return switch (component)
            {
                case YTDLP -> installYtDlp();
                case FFMPEG -> installFfmpeg();
                case DENO -> installDeno();
                case YTCIPHER -> installYtCipher();
                case BOT -> installBot();
            };
        }
        catch (Exception e)
        {
            LOG.error("Could not update {}", component.displayName(), e);
            return "failed (" + e.getMessage() + ")";
        }
    }

    private String installYtDlp() throws Exception
    {
        if (YtDlpDownloader.isBusy())
            return "a download is running, try again later";
        JsonNode release = latestRelease("yt-dlp/yt-dlp");
        JsonNode asset = asset(release, "yt-dlp.exe");
        Path staged = downloadVerified(release, asset, "yt-dlp.exe");
        swapFile(staged, ytDlpExe(), () -> UpdateSupport.parseYtDlpVersion(run(ytDlpExe(), "--version")) != null);
        return "updated to " + UpdateSupport.normalizeVersion(release.path("tag_name").asText());
    }

    private String installFfmpeg() throws Exception
    {
        if (YtDlpDownloader.isBusy())
            return "a download is running, try again later";
        JsonNode release = latestRelease("GyanD/codexffmpeg");
        String tag = release.path("tag_name").asText();
        JsonNode asset = asset(release, "ffmpeg-" + tag + "-essentials_build.zip");
        Path zip = downloadVerified(release, asset, "ffmpeg.zip");
        Path exe = extractOne(zip, "bin/ffmpeg.exe");
        swapFile(exe, ffmpegExe(), () -> UpdateSupport.parseFfmpegVersion(run(ffmpegExe(), "-version")) != null);
        return "updated to " + UpdateSupport.normalizeVersion(tag);
    }

    private String installDeno() throws Exception
    {
        JsonNode release = latestRelease("denoland/deno");
        JsonNode asset = asset(release, "deno-x86_64-pc-windows-msvc.zip");
        Path zip = downloadVerified(release, asset, "deno.zip");
        Path exe = extractOne(zip, "deno.exe");

        BotConfig config = bot.getConfig();
        boolean cipher = config.useLocalCipher();
        if (cipher)
            JMusicBot.stopLocalCipherService();
        try
        {
            swapFile(exe, denoExe(), () -> UpdateSupport.parseDenoVersion(run(denoExe(), "--version")) != null);
        }
        finally
        {
            if (cipher && !JMusicBot.restartLocalCipherService(config))
                LOG.error("The cipher service did not start after the Deno update");
        }
        return "updated to " + UpdateSupport.normalizeVersion(release.path("tag_name").asText());
    }

    private String installYtCipher() throws Exception
    {
        String sha = getJson(API + CIPHER_REPO + "/commits/HEAD").path("sha").asText();
        if (!sha.matches("[0-9a-f]{40}"))
            throw new IOException("unexpected commit id from GitHub");

        Path work = cleanWorkDir("yt-cipher");
        Path zip = download(API + CIPHER_REPO + "/zipball/" + sha, work.resolve("yt-cipher.zip"));
        Path extracted = work.resolve("src");
        UpdateSupport.extractZip(zip, extracted);
        Path top;
        try (Stream<Path> children = Files.list(extracted))
        {
            top = children.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new IOException("empty yt-cipher archive"));
        }

        // Start from the current checkout so the pinned ejs/ and the offline deno-cache stay.
        Path current = cipherDir();
        Path staged = current.resolveSibling(current.getFileName() + ".new");
        UpdateSupport.deleteRecursively(staged);
        copyTree(current, staged);
        copyTree(top, staged);
        Path server = staged.resolve("server.ts");
        Files.writeString(server, UpdateSupport.insertWatchdog(Files.readString(server, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        ProcessBuilder cache = new ProcessBuilder(denoExe().toString(), "cache", "--node-modules-dir=none",
                "server.ts", "worker.ts");
        cache.directory(staged.toFile());
        cache.environment().put("DENO_DIR", staged.resolve("deno-cache").toString());
        cache.redirectErrorStream(true);
        Process process = cache.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0)
        {
            UpdateSupport.deleteRecursively(staged);
            throw new IOException("deno cache failed: " + tail(output));
        }

        BotConfig config = bot.getConfig();
        boolean cipher = config.useLocalCipher();
        Path old = current.resolveSibling(current.getFileName() + ".old");
        if (cipher)
            JMusicBot.stopLocalCipherService();
        try
        {
            UpdateSupport.deleteRecursively(old);
            Files.move(current, old);
            try
            {
                Files.move(staged, current);
            }
            catch (IOException e)
            {
                Files.move(old, current);
                throw e;
            }
        }
        catch (IOException e)
        {
            // Nothing was swapped: bring the old service back before reporting.
            if (cipher)
                JMusicBot.restartLocalCipherService(config);
            UpdateSupport.deleteRecursively(staged);
            throw e;
        }

        if (cipher && !JMusicBot.restartLocalCipherService(config))
        {
            LOG.error("The new yt-cipher did not start, rolling back");
            JMusicBot.stopLocalCipherService();
            UpdateSupport.deleteRecursively(current);
            Files.move(old, current);
            JMusicBot.restartLocalCipherService(config);
            return "failed (the new version did not start, rolled back)";
        }
        writeBuildInfoCommit(sha);
        UpdateSupport.deleteRecursively(work);
        try
        {
            UpdateSupport.deleteRecursively(old);
        }
        catch (Exception e)
        {
            LOG.debug("Could not delete {} yet: {}", old, e.getMessage());
        }
        return "updated to " + sha.substring(0, 7);
    }

    private String installBot() throws Exception
    {
        Path launcher = WebServer.findLauncher();
        if (launcher == null)
            return "run-gui.bat or run.bat not found, update manually";
        JsonNode release = botRelease();
        String version = UpdateSupport.normalizeVersion(release.path("tag_name").asText());
        String name = "JMusicBot-" + version + "-All.jar";
        JsonNode asset = asset(release, name);

        Path dir = updateDirectory();
        Files.createDirectories(dir);
        for (String marker : List.of("pending", "started", "ok", "result.txt"))
            Files.deleteIfExists(dir.resolve(marker));
        Path jar = downloadVerified(release, asset, name);
        Path target = dir.resolve(name);
        Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);

        Path script = dir.resolve("apply-update.ps1");
        try (InputStream in = UpdateManager.class.getResourceAsStream("/update/apply-update.ps1"))
        {
            if (in == null)
                throw new IOException("apply-update.ps1 is missing from the jar");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(dir.resolve("pending"), version);

        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString());
        builder.environment().put("JMB_PID", String.valueOf(ProcessHandle.current().pid()));
        builder.environment().put("JMB_APP_DIR", appDir().toString());
        builder.environment().put("JMB_NEW_JAR", target.toString());
        builder.environment().put("JMB_LAUNCHER", launcher.toString());
        builder.environment().put("JMB_UPDATE_DIR", dir.toString());
        builder.directory(appDir().toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();

        LOG.info("Restarting into JMusicBot {}", version);
        Thread shutdown = new Thread(bot::shutdown, "update-shutdown");
        shutdown.start();
        return "installing " + version + ", the bot restarts now";
    }

    // ========== Download and swap helpers ==========

    private interface Check
    {
        boolean ok() throws Exception;
    }

    /** Replaces {@code target} with {@code staged}, keeping a backup that is restored if the check fails. */
    private static void swapFile(Path staged, Path target, Check check) throws Exception
    {
        Path backup = target.resolveSibling(target.getFileName() + ".old");
        // A backup left by an earlier run may still be locked by a process using it.
        deleteQuietly(backup);
        if (Files.exists(backup))
            backup = target.resolveSibling(target.getFileName() + "." + System.currentTimeMillis() + ".old");
        Files.move(target, backup);
        try
        {
            Files.move(staged, target);
        }
        catch (IOException e)
        {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
        boolean ok;
        try
        {
            ok = check.ok();
        }
        catch (Exception e)
        {
            ok = false;
        }
        if (!ok)
        {
            Files.deleteIfExists(target);
            Files.move(backup, target);
            throw new IOException("the new " + target.getFileName() + " did not run, rolled back");
        }
        // Windows lets a running exe be renamed but not deleted; a locked backup goes on the next run.
        deleteQuietly(backup);
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            LOG.debug("Could not delete {} yet: {}", path, e.getMessage());
        }
    }

    private static JsonNode asset(JsonNode release, String name) throws IOException
    {
        for (JsonNode asset : release.path("assets"))
            if (name.equals(asset.path("name").asText()))
                return asset;
        throw new IOException("release " + release.path("tag_name").asText() + " has no " + name);
    }

    /** Downloads an asset and checks it against its published SHA-256; refuses when there is none. */
    private Path downloadVerified(JsonNode release, JsonNode asset, String fileName) throws Exception
    {
        String expected = expectedSha256(release, asset);
        if (expected == null)
            throw new IOException("no published SHA-256 for " + asset.path("name").asText());
        Path work = cleanWorkDir(fileName);
        Path file = download(asset.path("browser_download_url").asText(), work.resolve(fileName));
        String actual = UpdateSupport.sha256(file);
        if (!actual.equalsIgnoreCase(expected))
        {
            Files.deleteIfExists(file);
            throw new IOException("checksum mismatch for " + asset.path("name").asText());
        }
        return file;
    }

    private String expectedSha256(JsonNode release, JsonNode asset) throws Exception
    {
        String digest = asset.path("digest").asText("");
        if (digest.toLowerCase(Locale.ROOT).startsWith("sha256:"))
            return digest.substring(7).toLowerCase(Locale.ROOT);

        String name = asset.path("name").asText();
        for (JsonNode candidate : release.path("assets"))
        {
            String candidateName = candidate.path("name").asText();
            String lower = candidateName.toLowerCase(Locale.ROOT);
            if (!(lower.contains("sha256") || lower.contains("sha2-256")) || lower.contains("sig"))
                continue;
            String text = getText(candidate.path("browser_download_url").asText());
            String hash = UpdateSupport.parseSha256Sums(text).get(name);
            if (hash != null)
                return hash.toLowerCase(Locale.ROOT);
            // A per-file checksum such as deno.zip.sha256sum holds one bare hash.
            if (candidateName.startsWith(name))
            {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b([0-9a-fA-F]{64})\\b").matcher(text);
                if (matcher.find())
                    return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static Path extractOne(Path zip, String suffix) throws IOException
    {
        Path target = zip.resolveSibling("extracted");
        UpdateSupport.deleteRecursively(target);
        for (Path file : UpdateSupport.extractZip(zip, target))
            if (file.toString().replace('\\', '/').endsWith(suffix))
            {
                Path out = zip.resolveSibling(file.getFileName());
                Files.move(file, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
        throw new IOException("the archive has no " + suffix);
    }

    private static Path cleanWorkDir(String name) throws IOException
    {
        Path dir = updateDirectory().resolve("work").resolve(name.replaceAll("[^A-Za-z0-9.-]", "_"));
        UpdateSupport.deleteRecursively(dir);
        Files.createDirectories(dir);
        return dir;
    }

    private static void copyTree(Path source, Path target) throws IOException
    {
        try (Stream<Path> files = Files.walk(source))
        {
            for (Path file : (Iterable<Path>) files::iterator)
            {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file))
                    Files.createDirectories(destination);
                else
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void writeBuildInfoCommit(String sha) throws IOException
    {
        Path file = buildInfo();
        List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).startsWith("yt-cipher.commit="))
            {
                lines.set(i, "yt-cipher.commit=" + sha);
                replaced = true;
            }
        if (!replaced)
            lines.add("yt-cipher.commit=" + sha);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    // ========== HTTP and processes ==========

    private synchronized HttpClient client()
    {
        if (client == null)
        {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20));
            Proxy proxy = ProxyUtil.createProxy(bot.getConfig());
            if (proxy != null && proxy.address() instanceof InetSocketAddress address)
                builder.proxy(ProxySelector.of(address));
            client = builder.build();
        }
        return client;
    }

    private HttpRequest request(String url)
    {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "JMusicBot-updater")
                .header("Accept", url.startsWith(API) ? "application/vnd.github+json" : "*/*")
                .build();
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException
    {
        return MAPPER.readTree(getText(url));
    }

    private String getText(String url) throws IOException, InterruptedException
    {
        HttpResponse<String> response = client().send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        return response.body();
    }

    private Path download(String url, Path target) throws IOException, InterruptedException
    {
        HttpResponse<Path> response = client().send(request(url), HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200)
        {
            Files.deleteIfExists(target);
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return target;
    }

    private static String run(Path exe, String... args) throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(exe.getParent().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS))
        {
            process.destroyForcibly();
            throw new IOException(exe.getFileName() + " did not answer");
        }
        return output;
    }

    private static String tail(String text)
    {
        String trimmed = text.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(trimmed.length() - 300);
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
