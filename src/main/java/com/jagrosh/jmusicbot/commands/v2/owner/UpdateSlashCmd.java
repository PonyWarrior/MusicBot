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
package com.jagrosh.jmusicbot.commands.v2.owner;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.v1.owner.UpdateCmd;
import com.jagrosh.jmusicbot.commands.v2.OwnerSlashCommand;
import com.jagrosh.jmusicbot.update.UpdateComponent;
import com.jagrosh.jmusicbot.update.UpdateManager;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.List;

/**
 * Owner-only {@code /update}: the slash version of the {@code update} text command.
 *
 * <p>Discord cannot limit a command to one user, so it is hidden from members without
 * administrator rights and every reply is ephemeral; the owner check still applies.
 */
public class UpdateSlashCmd extends OwnerSlashCommand
{
    static final String OPTION = "action";

    public UpdateSlashCmd(Bot bot)
    {
        super(bot);
        this.name = "update";
        this.help = "checks for or installs updates of the bot and its tools (owner only)";
        OptionData action = new OptionData(OptionType.STRING, OPTION,
                "what to do; leave empty to show the status", false)
                .addChoice("status", "status")
                .addChoice("check", "check")
                .addChoice("all", "all");
        for (UpdateComponent component : UpdateComponent.values())
            action.addChoice(component.displayName(), component.id());
        this.options = List.of(action);
    }

    @Override
    public CommandData buildCommandData()
    {
        SlashCommandData data = (SlashCommandData) super.buildCommandData();
        return data.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
    }

    @Override
    public void doOwnerCommand(SlashCommandEvent event)
    {
        UpdateManager updates = bot.getUpdateManager();
        OptionMapping option = event.getOption(OPTION);
        String action = option == null ? "status" : option.getAsString().trim();

        String reply;
        if (action.equalsIgnoreCase("status"))
        {
            reply = UpdateCmd.formatStatus(updates.getStatus(), updates.getLastMessage());
        }
        else if (action.equalsIgnoreCase("check"))
        {
            reply = updates.checkAsync()
                    ? event.getClient().getSuccess() + " Checking for updates. Run `/update` in a moment to see the result."
                    : busy(event);
        }
        else
        {
            UpdateComponent component = action.equalsIgnoreCase("all") ? null : UpdateComponent.fromId(action);
            if (component == null && !action.equalsIgnoreCase("all"))
                reply = event.getClient().getError() + " Unknown component `" + action + "`.";
            else
                reply = updates.updateAsync(component)
                        ? event.getClient().getSuccess() + " Updating "
                                + (component == null ? "everything" : component.displayName())
                                + ". A bot update restarts the bot. Run `/update` to see the result."
                        : busy(event);
        }
        event.reply(reply).setEphemeral(true).queue();
    }

    private static String busy(SlashCommandEvent event)
    {
        return event.getClient().getWarning() + " An update check or install is already running.";
    }
}
