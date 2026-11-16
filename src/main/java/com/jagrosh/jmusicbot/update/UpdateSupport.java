/*
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pure helpers for the updater: version comparison, checksum files, version output parsing,
 * the yt-cipher watchdog patch and safe zip extraction.
 */
public final class UpdateSupport
{
    /** The line the watchdog block is inserted before in yt-cipher's {@code server.ts}. */
    public static final String WATCHDOG_ANCHOR = "const API_TOKEN = Deno.env.get(\"API_TOKEN\");";

    static final String WATCHDOG_BLOCK = """
            // The bot starts this service with a pipe on stdin. When the bot dies the pipe closes,
            // so exit instead of lingering as an orphan that holds the port.
            try {
                if (!Deno.stdin.isTerminal()) {
                    (async () => {
                        const buffer = new Uint8Array(1);
                        try {
                            while (await Deno.stdin.read(buffer) !== null) {
                                // Only the end of the pipe matters.
                            }
                        } catch {
                            // A closed stdin means the parent is gone too.
                        }
                        Deno.exit(0);
                    })();
                }
            } catch {
                // No usable stdin; leave the watchdog disabled.
            }

            """;

    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    private UpdateSupport()
    {
    }

    /**
     * Compares two versions such as {@code 0.8.0}, {@code v0.9.0-beta.1} or {@code 2026.11.10}.
     * Numeric parts compare as numbers; a release sorts after its own prereleases.
     *
     * @return negative, zero or positive like {@link Comparator#compare}
     */
    public static int compareVersions(String a, String b)
    {
        String[] left = splitPrerelease(normalizeVersion(a));
        String[] right = splitPrerelease(normalizeVersion(b));

        int core = compareDotted(left[0], right[0]);
        if (core != 0)
            return core;
        if (left[1] == null && right[1] == null)
            return 0;
        if (left[1] == null)
            return 1;
        if (right[1] == null)
            return -1;
        return compareDotted(left[1], right[1]);
    }

    /**
     * @return true when {@code latest} is strictly newer than {@code installed}; false when either
     *         is unknown
     */
    public static boolean isNewer(String latest, String installed)
    {
        if (!isKnown(latest) || !isKnown(installed))
            return false;
        return compareVersions(latest, installed) > 0;
    }

    static boolean isKnown(String version)
    {
        return version != null && !version.isBlank() && !"UNKNOWN".equalsIgnoreCase(version.trim());
    }

    /** Strips whitespace, a leading {@code v} and build metadata after {@code +}. */
    public static String normalizeVersion(String version)
    {
        if (version == null)
            return "";
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V"))
            value = value.substring(1);
        int plus = value.indexOf('+');
        if (plus >= 0)
            value = value.substring(0, plus);
        return value;
    }

    /** @return true when the version has a prerelease part, such as {@code 1.0.0-beta.1} */
    public static boolean isPrerelease(String version)
    {
        return splitPrerelease(normalizeVersion(version))[1] != null;
    }

    private static String[] splitPrerelease(String version)
    {
        int dash = version.indexOf('-');
        if (dash < 0)
            return new String[]{version, null};
        return new String[]{version.substring(0, dash), version.substring(dash + 1)};
    }

    private static int compareDotted(String a, String b)
    {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++)
        {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            boolean lNum = l.matches("\\d+");
            boolean rNum = r.matches("\\d+");
            int result;
            if (lNum && rNum)
                result = new java.math.BigInteger(l).compareTo(new java.math.BigInteger(r));
            else if (lNum)
                result = -1; // numeric identifiers sort before words, as in SemVer
            else if (rNum)
                result = 1;
            else
                result = l.compareToIgnoreCase(r);
            if (result != 0)
                return result;
        }
        return 0;
    }

    /**
     * Parses a {@code sha256sum}-style file ({@code <hex>  <name>} or {@code <hex> *<name>}).
     *
     * @return file name to lower-case hex digest
     */
    public static Map<String, String> parseSha256Sums(String text)
    {
        Map<String, String> sums = new HashMap<>();
        if (text == null)
            return sums;
        for (String line : text.split("\\R"))
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F]{64}"))
                continue;
            String name = parts[1].trim();
            if (name.startsWith("*"))
                name = name.substring(1);
            sums.put(name, parts[0].toLowerCase(Locale.ROOT));
        }
        return sums;
    }

    /** @return the version from {@code deno --version} output ({@code deno 2.9.7 (stable...)}) */
    public static String parseDenoVersion(String output)
    {
        if (output == null)
            return null;
        Matcher matcher = Pattern.compile("deno\\s+(\\d+(?:\\.\\d+)*)").matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** @return the version from {@code ffmpeg -version} output ({@code ffmpeg version 9.0.2-essentials...}) */
    public static String parseFfmpegVersion(String output)
    {
        if (output == null)
            return null;
        Matcher matcher = Pattern.compile("ffmpeg version\\s+(\\S+)").matcher(output);
        if (!matcher.find())
            return null;
        Matcher number = LEADING_VERSION.matcher(normalizeVersion(matcher.group(1)));
        return number.find() ? number.group(1) : null;
    }

    /** @return the first line of {@code yt-dlp --version} output, such as {@code 2026.11.10} */
    public static String parseYtDlpVersion(String output)
    {
        if (output == null)
            return null;
        for (String line : output.split("\\R"))
        {
            String trimmed = line.trim();
            if (trimmed.matches("\\d{4}\\.\\d{1,2}\\.\\d{1,2}(\\.\\d+)?"))
                return trimmed;
        }
        return null;
    }

    /** @return the {@code yt-cipher.commit=} value from a build-info file, or null */
    public static String readBuildInfoCommit(String text)
    {
        if (text == null)
            return null;
        for (String line : text.split("\\R"))
        {
            String trimmed = line.trim();
            if (trimmed.startsWith("yt-cipher.commit="))
            {
                String value = trimmed.substring("yt-cipher.commit=".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Inserts the stdin watchdog into yt-cipher's {@code server.ts}.
     *
     * @return the patched source; unchanged when it already has a watchdog
     * @throws IllegalStateException when the anchor line is missing (yt-cipher changed shape)
     */
    public static String insertWatchdog(String source)
    {
        if (source.contains("Deno.stdin.isTerminal"))
            return source;
        int index = source.indexOf(WATCHDOG_ANCHOR);
        if (index < 0)
            throw new IllegalStateException("server.ts no longer contains the API_TOKEN line");
        return source.substring(0, index) + WATCHDOG_BLOCK + source.substring(index);
    }

    /** @return the lower-case hex SHA-256 of a file */
    public static String sha256(Path file) throws IOException
    {
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file))
        {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0)
                digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Extracts a zip into a directory, refusing entries that would escape it.
     *
     * @return the extracted regular files
     */
    public static List<Path> extractZip(Path zip, Path target) throws IOException
    {
        return extractZip(zip, target, MAX_EXTRACTED_BYTES);
    }

    /** The largest total size an update archive may expand to; ffmpeg, the biggest, is ~200 MB. */
    public static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Extracts a zip into a directory, refusing entries that would escape it and stopping once
     * the extracted data passes {@code maxBytes}, so a malformed archive cannot fill the disk.
     */
    public static List<Path> extractZip(Path zip, Path target, long maxBytes) throws IOException
    {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<Path> files = new ArrayList<>();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip)))
        {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null)
            {
                Path out = resolveEntry(root, entry.getName());
                if (entry.isDirectory())
                {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var stream = Files.newOutputStream(out))
                {
                    int read;
                    while ((read = in.read(buffer)) > 0)
                    {
                        total += read;
                        if (total > maxBytes)
                            throw new IOException("the archive expands to more than " + maxBytes + " bytes");
                        stream.write(buffer, 0, read);
                    }
                }
                files.add(out);
            }
        }
        return files;
    }

    /**
     * Resolves a zip entry name below a root.
     *
     * @throws IOException when the entry is absolute or climbs out of the root
     */
    static Path resolveEntry(Path root, String name) throws IOException
    {
        String clean = name.replace('\\', '/');
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*"))
            throw new IOException("Unsafe zip entry: " + name);
        Path out = root.resolve(clean).normalize();
        if (!out.startsWith(root))
            throw new IOException("Unsafe zip entry: " + name);
        return out;
    }

    /** Deletes a file or directory tree if it exists. */
    public static void deleteRecursively(Path path) throws IOException
    {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return;
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        {
            try (Stream<Path> walk = Files.walk(path))
            {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
        else
        {
            Files.deleteIfExists(path);
        }
    }
}
