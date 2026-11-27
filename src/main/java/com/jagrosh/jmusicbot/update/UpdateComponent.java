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

import java.util.Locale;

/**
 * The parts of an install (the Windows package, or a Linux systemd service) that can be updated on their own. The order is the order
 * "update everything" applies them in: the bot goes last because it restarts the process.
 */
public enum UpdateComponent
{
    YTDLP("yt-dlp"),
    FFMPEG("ffmpeg"),
    DENO("Deno"),
    YTCIPHER("yt-cipher"),
    BOT("JMusicBot");

    private final String displayName;

    UpdateComponent(String displayName)
    {
        this.displayName = displayName;
    }

    public String displayName()
    {
        return displayName;
    }

    public String id()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param text an id such as {@code yt-dlp}, {@code ytdlp} or {@code bot}
     * @return the component, or null when the text names none
     */
    public static UpdateComponent fromId(String text)
    {
        if (text == null)
            return null;
        String key = text.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (UpdateComponent component : values())
        {
            if (component.id().equals(key)
                    || component.displayName.toLowerCase(Locale.ROOT).replace("-", "").equals(key))
                return component;
        }
        return null;
    }
}
