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
package com.jagrosh.jmusicbot.commands.v1.owner;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.v1.OwnerCommand;
import com.jagrosh.jmusicbot.update.UpdateComponent;
import com.jagrosh.jmusicbot.update.UpdateManager;

import java.util.List;

/**
 * Owner command for the updater.
 *
 * <ul>
 *   <li>{@code update} - shows installed and latest versions</li>
 *   <li>{@code update check} - asks every source for its latest version</li>
 *   <li>{@code update all} - installs every available update (the bot itself last)</li>
 *   <li>{@code update <component>} - installs one component, e.g. {@code yt-dlp}</li>
 * </ul>
 */
public class UpdateCmd extends OwnerCommand
{
    private final Bot bot;

    public UpdateCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "update";
        this.help = "checks for or installs updates of the bot and its tools";
        this.arguments = "[check|all|yt-dlp|ffmpeg|deno|yt-cipher|bot]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = false;
    }

    @Override
    protected void execute(CommandEvent event)
    {
        UpdateManager updates = bot.getUpdateManager();
        String args = event.getArgs().trim();
        if (args.isEmpty())
        {
            event.reply(formatStatus(updates.getStatus(), updates.getLastMessage()));
            return;
        }

        boolean started;
        if (args.equalsIgnoreCase("check"))
        {
            started = updates.checkAsync();
            if (started)
                event.replySuccess("Checking for updates. Run `" + event.getClient().getPrefix()
                        + "update` in a moment to see the result.");
        }
        else
        {
            UpdateComponent component = args.equalsIgnoreCase("all") ? null : UpdateComponent.fromId(args);
            if (component == null && !args.equalsIgnoreCase("all"))
            {
                event.replyError("Unknown component `" + args + "`. Use one of: " + this.arguments);
                return;
            }
            started = updates.updateAsync(component);
            if (started)
                event.replySuccess("Updating " + (component == null ? "everything" : component.displayName())
                        + ". A bot update restarts the bot. Run `" + event.getClient().getPrefix()
                        + "update` to see the result.");
        }
        if (!started)
            event.replyWarning("An update check or install is already running.");
    }

    /**
     * @param status the per-component status
     * @param lastMessage the last updater message, may be empty
     * @return a Discord-ready summary
     */
    public static String formatStatus(List<UpdateManager.Status> status, String lastMessage)
    {
        StringBuilder text = new StringBuilder("**Updates**\n");
        for (UpdateManager.Status item : status)
        {
            text.append(item.updateAvailable() ? "⬆️ " : "• ")
                    .append("**").append(item.name()).append("**: ");
            if (!item.present())
                text.append("not installed");
            else
                text.append(blankToUnknown(item.installed()));
            if (item.latest() != null && !item.latest().isBlank())
                text.append(" (latest ").append(item.latest()).append(')');
            if (item.note() != null && !item.note().isBlank())
                text.append(" - ").append(item.note());
            text.append('\n');
        }
        if (lastMessage != null && !lastMessage.isBlank())
            text.append("\nLast result: ").append(lastMessage);
        return text.toString();
    }

    private static String blankToUnknown(String text)
    {
        return text == null || text.isBlank() ? "unknown" : text;
    }
}
