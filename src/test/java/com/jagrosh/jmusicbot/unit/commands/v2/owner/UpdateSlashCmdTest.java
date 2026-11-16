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
package com.jagrosh.jmusicbot.unit.commands.v2.owner;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.v1.OwnerOnlyNotice;
import com.jagrosh.jmusicbot.commands.v2.owner.UpdateSlashCmd;
import com.jagrosh.jmusicbot.update.UpdateComponent;
import com.jagrosh.jmusicbot.update.UpdateManager;
import net.dv8tion.jda.api.interactions.commands.Command.Choice;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link UpdateSlashCmd} and {@link OwnerOnlyNotice}.
 */
class UpdateSlashCmdTest
{
    private Bot bot;
    private UpdateManager updates;
    private SlashCommandEvent event;
    private ReplyCallbackAction reply;
    private UpdateSlashCmd command;

    @BeforeEach
    void setUp()
    {
        bot = mock(Bot.class);
        updates = mock(UpdateManager.class);
        when(bot.getUpdateManager()).thenReturn(updates);
        when(updates.getStatus()).thenReturn(List.of());
        when(updates.getLastMessage()).thenReturn("");

        CommandClient client = mock(CommandClient.class);
        when(client.getSuccess()).thenReturn("OK");
        when(client.getWarning()).thenReturn("WARN");
        when(client.getError()).thenReturn("ERR");

        event = mock(SlashCommandEvent.class);
        reply = mock(ReplyCallbackAction.class);
        when(event.getClient()).thenReturn(client);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(anyBoolean())).thenReturn(reply);

        command = new UpdateSlashCmd(bot);
    }

    private void option(String value)
    {
        OptionMapping mapping = mock(OptionMapping.class);
        when(mapping.getAsString()).thenReturn(value);
        when(event.getOption("action")).thenReturn(mapping);
    }

    private String replyText()
    {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        verify(reply).setEphemeral(true);
        return captor.getValue();
    }

    @Test
    void isHiddenFromNonAdminsAndOffersEveryComponent()
    {
        SlashCommandData data = (SlashCommandData) command.buildCommandData();
        assertEquals("update", data.getName());
        assertEquals(DefaultMemberPermissions.DISABLED, data.getDefaultPermissions());

        List<String> choices = data.getOptions().get(0).getChoices().stream()
                .map(Choice::getAsString).toList();
        assertTrue(choices.containsAll(List.of("status", "check", "all")));
        for (UpdateComponent component : UpdateComponent.values())
            assertTrue(choices.contains(component.id()), component.id());
        for (String choice : choices)
            if (!List.of("status", "check", "all").contains(choice))
                assertNotNull(UpdateComponent.fromId(choice), choice);
    }

    @Test
    void noOptionShowsStatus()
    {
        command.doOwnerCommand(event);
        assertTrue(replyText().startsWith("**Updates**"));
        verify(updates, never()).checkAsync();
    }

    @Test
    void checkStartsACheck()
    {
        option("check");
        when(updates.checkAsync()).thenReturn(true);
        command.doOwnerCommand(event);
        assertTrue(replyText().contains("Checking for updates"));
    }

    @Test
    void allUpdatesEverything()
    {
        option("all");
        when(updates.updateAsync(null)).thenReturn(true);
        command.doOwnerCommand(event);
        assertTrue(replyText().contains("Updating everything"));
        verify(updates).updateAsync(null);
    }

    @Test
    void singleComponent()
    {
        option(UpdateComponent.YTDLP.id());
        when(updates.updateAsync(UpdateComponent.YTDLP)).thenReturn(true);
        command.doOwnerCommand(event);
        assertTrue(replyText().contains("Updating " + UpdateComponent.YTDLP.displayName()));
    }

    @Test
    void busyUpdaterWarns()
    {
        option("all");
        when(updates.updateAsync(null)).thenReturn(false);
        command.doOwnerCommand(event);
        assertTrue(replyText().contains("already running"));
    }

    @Test
    void ownerNoticeRepliesToNonOwners()
    {
        Command ownerCommand = mock(Command.class);
        when(ownerCommand.isOwnerCommand()).thenReturn(true);
        CommandEvent textEvent = mock(CommandEvent.class);
        when(textEvent.isOwner()).thenReturn(false);

        new OwnerOnlyNotice().onTerminatedCommand(textEvent, ownerCommand);
        verify(textEvent).replyError(OwnerOnlyNotice.MESSAGE);
    }

    @Test
    void ownerNoticeIgnoresOtherTerminations()
    {
        Command ownerCommand = mock(Command.class);
        when(ownerCommand.isOwnerCommand()).thenReturn(true);
        CommandEvent ownerEvent = mock(CommandEvent.class);
        when(ownerEvent.isOwner()).thenReturn(true);
        new OwnerOnlyNotice().onTerminatedCommand(ownerEvent, ownerCommand);
        verify(ownerEvent, never()).replyError(anyString());

        Command normal = mock(Command.class);
        when(normal.isOwnerCommand()).thenReturn(false);
        CommandEvent other = mock(CommandEvent.class);
        new OwnerOnlyNotice().onTerminatedCommand(other, normal);
        verify(other, never()).replyError(anyString());
    }
}
