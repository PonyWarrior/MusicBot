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
package com.jagrosh.jmusicbot.config.model;

import java.util.List;
import java.util.Set;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

/**
 * Enumeration of all configuration options for the bot.
 * This provides a centralized, type-safe way to manage config options.
 * 
 * @author Arif Banai (arif-banai)
 */
public enum ConfigOption {
    // Required options
    TOKEN("discord.token", ConfigType.STRING, true, "Bot token for authentication"),
    OWNER("discord.owner", ConfigType.LONG, true, "Owner's Discord user ID"),
    
    // String options
    PREFIX("commands.prefix", ConfigType.STRING, false, "Command prefix (use @mention for mention prefix)"),
    ALTPREFIX("commands.altPrefix", ConfigType.STRING, false, "Alternative command prefix (use NONE to disable)"),
    HELP_WORD("commands.help", ConfigType.STRING, false, "Word used to view help"),
    SUCCESS_EMOJI("ui.emojis.success", ConfigType.STRING, false, "Success emoji"),
    WARNING_EMOJI("ui.emojis.warning", ConfigType.STRING, false, "Warning emoji"),
    ERROR_EMOJI("ui.emojis.error", ConfigType.STRING, false, "Error emoji"),
    LOADING_EMOJI("ui.emojis.loading", ConfigType.STRING, false, "Loading emoji"),
    SEARCHING_EMOJI("ui.emojis.searching", ConfigType.STRING, false, "Searching emoji"),
    GAME("presence.game", ConfigType.STRING, false, "Bot's game status (use DEFAULT for default, NONE for no game)"),
    STATUS("presence.status", ConfigType.STRING, false, "Bot's online status (ONLINE, IDLE, DND, INVISIBLE)"),
    LOG_LEVEL("logging.level", ConfigType.STRING, false, "Logging verbosity (off, error, warn, info, debug, trace, all)"),
    EVAL_ENGINE("dangerous.evalEngine", ConfigType.STRING, false, "Eval engine name"),
    PLAYLISTS_FOLDER("paths.playlistsFolder", ConfigType.STRING, false, "Alternative folder for playlists"),
    REMOTE_CIPHER("playback.youtube.remoteCipher", ConfigType.STRING, false, "Base URL of a remote signature-cipher server (empty = local extraction)"),

    // Local cipher service options
    LOCAL_CIPHER_ENABLED("playback.youtube.localCipher.enabled", ConfigType.BOOLEAN, false, "Start a local yt-cipher service as a child process of the bot"),
    LOCAL_CIPHER_DIRECTORY("playback.youtube.localCipher.directory", ConfigType.STRING, false, "Directory of the local yt-cipher checkout"),
    LOCAL_CIPHER_PORT("playback.youtube.localCipher.port", ConfigType.INT, false, "Port the local yt-cipher service listens on"),
    LOCAL_CIPHER_COMMAND("playback.youtube.localCipher.command", ConfigType.STRING, false, "Command used to launch the local yt-cipher service"),
    LOCAL_CIPHER_ARGS("playback.youtube.localCipher.args", ConfigType.STRING_LIST, false, "Arguments passed to the local yt-cipher command"),
    LOCAL_CIPHER_ENV("playback.youtube.localCipher.env", ConfigType.CONFIG, false, "Environment variables for the local yt-cipher service"),
    LOCAL_CIPHER_STARTUP_TIMEOUT("playback.youtube.localCipher.startupTimeoutSeconds", ConfigType.INT, false, "Seconds to wait for the local yt-cipher service to start"),

    // Local music library options
    LIBRARY_ENABLED("library.enabled", ConfigType.BOOLEAN, false, "Enable the local music library"),
    LIBRARY_FOLDER("library.folder", ConfigType.STRING, false, "Folder holding the downloaded music"),
    LIBRARY_YTDLP_DIRECTORY("library.ytdlpDirectory", ConfigType.STRING, false, "Directory of the yt-dlp checkout"),
    LIBRARY_YTDLP_COMMAND("library.ytdlpCommand", ConfigType.STRING, false, "Command used to run yt-dlp"),
    LIBRARY_YTDLP_ARGS("library.ytdlpArgs", ConfigType.STRING_LIST, false, "Arguments passed to the yt-dlp command"),
    LIBRARY_AUDIO_FORMAT("library.audioFormat", ConfigType.STRING, false, "Audio format downloads are converted to"),
    LIBRARY_SEARCH_FIRST("library.searchFirst", ConfigType.BOOLEAN, false, "Search the local library before searching online"),
    LIBRARY_DOWNLOAD_ON_FAVORITE("library.downloadOnFavorite", ConfigType.BOOLEAN, false, "Download the track when it is favorited"),
    
    // Boolean options
    STAY_IN_CHANNEL("voice.stayInChannel", ConfigType.BOOLEAN, false, "Whether to stay in voice channel after queue ends"),
    SONG_IN_GAME("presence.songInStatus", ConfigType.BOOLEAN, false, "Whether to show current song in bot status"),
    NP_IMAGES("nowPlaying.images", ConfigType.BOOLEAN, false, "Whether to show YouTube thumbnails in nowplaying"),
    NP_MINIMAL_MESSAGE("nowPlaying.minimalMessage", ConfigType.BOOLEAN, false, "Whether to render now-playing messages in a compact/minimal layout"),
    NP_SHOW_BUTTONS("nowPlaying.showButtons", ConfigType.BOOLEAN, false, "Whether to include interactive playback buttons in now-playing messages"),
    NP_SHOW_PROGRESS_BAR("nowPlaying.showProgressBar", ConfigType.BOOLEAN, false, "Whether to show the now-playing progress bar"),
    UPDATE_ALERTS("updates.alerts", ConfigType.BOOLEAN, false, "Whether to alert owner about updates"),
    USE_EVAL("dangerous.eval", ConfigType.BOOLEAN, false, "Whether to enable eval command (DANGEROUS)"),
    USE_YOUTUBE_OAUTH("playback.youtube.useOAuth", ConfigType.BOOLEAN, false, "Whether to use YouTube OAuth2 for playback"),
    
    // Numeric options
    MAX_SECONDS("playback.maxTrackSeconds", ConfigType.LONG, false, "Maximum track length in seconds (0 = no limit)"),
    MAX_YT_PLAYLIST_PAGES("playback.maxYouTubePlaylistPages", ConfigType.INT, false, "Maximum YouTube playlist pages to load"),
    MAX_HISTORY_SIZE("playback.maxHistorySize", ConfigType.INT, false, "Maximum number of tracks to keep in history (0 = disabled)"),
    ALONE_TIME_UNTIL_STOP("voice.aloneTimeUntilStopSeconds", ConfigType.LONG, false, "Seconds to wait alone before leaving (0 = never)"),
    SKIP_RATIO("playback.skipRatio", ConfigType.DOUBLE, false, "Ratio of users needed to vote skip"),
    CLEAR_CHANNEL_DELETE_LIMIT("commands.clearChannel.deleteLimit", ConfigType.INT, false, "Max messages deleted by clearchannel (0 = unlimited)"),
    CLEAR_CHANNEL_AGE_DAYS("commands.clearChannel.ageDays", ConfigType.LONG, false, "Max message age in days for clearchannel (0 = unlimited)"),
    
    // Complex options - Nested configurations
    ALIASES("commands.aliases", ConfigType.CONFIG, false, "Command aliases configuration"),
    TRANSFORMS("playback.transforms", ConfigType.CONFIG, false, "Audio source transforms configuration"),
    AUDIO_SOURCES("playback.audioSources", ConfigType.CONFIG, false, "Audio sources configuration (nested booleans)"),
    
    // GUI options
    GUI_ENABLED("gui.enabled", ConfigType.BOOLEAN, false, "Enable or disable the GUI (default true)"),
    GUI_THEME("gui.theme", ConfigType.STRING, false, "GUI theme: light, dark, darcula, intellij"),
    GUI_FONT_SIZE("gui.fontSize", ConfigType.INT, false, "Base font size for GUI components (8-24)"),
    
    // Performance options
    NAS_BUFFER_MS("performance.nasBufferMs", ConfigType.INT, false, "NAS buffer duration in ms (protects against GC pauses)"),
    FRAME_BUFFER_MS("performance.frameBufferMs", ConfigType.INT, false, "Lavaplayer frame buffer duration in ms"),
    
    // Proxy options
    PROXY_HOST("proxy.host", ConfigType.STRING, false, "Proxy server hostname"),
    PROXY_PORT("proxy.port", ConfigType.INT, false, "Proxy server port"),
    PROXY_LAVAPLAYER("proxy.lavaplayer", ConfigType.BOOLEAN, false, "Route Lavaplayer audio requests through proxy"),
    PROXY_JDA("proxy.jda", ConfigType.BOOLEAN, false, "Route JDA Discord API traffic through proxy"),
    PROXY_GITHUB("proxy.github", ConfigType.BOOLEAN, false, "Route GitHub version checks through proxy");
    
    private final String key;
    private final ConfigType type;
    private final boolean required;
    private final String description;
    
    ConfigOption(String key, ConfigType type, boolean required, String description) {
        this.key = key;
        this.type = type;
        this.required = required;
        this.description = description;
    }
    
    public String getKey() {
        return key;
    }
    
    public ConfigType getType() {
        return type;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the value of this config option from the provided Config object.
     * 
     * @param config The Config object to read from
     * @return The value as an Object (cast appropriately based on type)
     * @deprecated Use type-specific getter methods instead for type safety
     */
    @Deprecated
    public Object getValue(Config config) {
        if (!config.hasPath(key)) {
            return null;
        }
        
        try {
            return switch (type) {
                case STRING -> config.getString(key);
                case LONG -> config.getLong(key);
                case INT -> config.getInt(key);
                case DOUBLE -> config.getDouble(key);
                case BOOLEAN -> config.getBoolean(key);
                case CONFIG -> config.getConfig(key);
                case STRING_LIST -> config.getStringList(key);
            };
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Type-safe getter for String values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public String getString(Config config) {
        return config.getString(key);
    }
    
    /**
     * Type-safe getter for Long values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public Long getLong(Config config) {
        return config.getLong(key);
    }
    
    /**
     * Type-safe getter for Integer values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public Integer getInt(Config config) {
        return config.getInt(key);
    }
    
    /**
     * Type-safe getter for Double values.
    * @throws ConfigException.Missing if the path doesn't exist
     */
    public Double getDouble(Config config) {
        return config.getDouble(key);
    }
    
    /**
     * Type-safe getter for Boolean values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public Boolean getBoolean(Config config) {
        return config.getBoolean(key);
    }
    
    /**
     * Type-safe getter for Config (nested config) values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public Config getConfig(Config config) {
        return config.getConfig(key);
    }
    
    /**
     * Type-safe getter for String list values.
     * @throws ConfigException.Missing if the path doesn't exist
     */
    public List<String> getStringList(Config config) {
        return config.getStringList(key);
    }
    
    /**
     * Checks if the config has a value for this option.
     */
    public boolean hasValue(Config config) {
        return config.hasPath(key);
    }
    
    /**
     * Gets all config option keys as a set.
     */
    public static Set<String> getAllKeys() {
        Set<String> keys = new java.util.HashSet<>();
        for (ConfigOption option : values()) {
            keys.add(option.key);
        }
        return keys;
    }
    
    /**
     * Gets all optional (non-required) config option keys as a set.
     */
    public static Set<String> getOptionalKeys() {
        Set<String> keys = new java.util.HashSet<>();
        for (ConfigOption option : values()) {
            if (!option.required) {
                keys.add(option.key);
            }
        }
        return keys;
    }
    
    /**
     * Finds a ConfigOption by its key name.
     */
    public static java.util.Optional<ConfigOption> findByKey(String key) {
        for (ConfigOption option : values()) {
            if (option.key.equals(key)) {
                return java.util.Optional.of(option);
            }
        }
        return java.util.Optional.empty();
    }
    
    /**
     * Config value types supported by the configuration system.
     */
    public enum ConfigType {
        STRING,
        LONG,
        INT,
        DOUBLE,
        BOOLEAN,
        CONFIG,
        STRING_LIST
    }
}
