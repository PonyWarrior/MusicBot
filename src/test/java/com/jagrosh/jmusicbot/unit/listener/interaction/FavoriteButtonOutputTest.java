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
package com.jagrosh.jmusicbot.unit.listener.interaction;

import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.listener.interaction.OutputAdapters;
import com.jagrosh.jmusicbot.service.MusicService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The favorite button answers "Downloading..." before the download finishes, so the later
 * now-playing refresh must not try to answer the interaction a second time.
 */
class FavoriteButtonOutputTest
{
    private ButtonInteractionEvent event;
    private Message message;
    private MessageEditAction messageEdit;
    private MessageEditCallbackAction callbackEdit;
    private AudioHandler handler;

    @BeforeEach
    void setUp()
    {
        event = mock(ButtonInteractionEvent.class);
        message = mock(Message.class);
        messageEdit = mock(MessageEditAction.class);
        callbackEdit = mock(MessageEditCallbackAction.class);
        handler = mock(AudioHandler.class);
        JDA jda = mock(JDA.class);

        when(event.getJDA()).thenReturn(jda);
        when(event.getMessage()).thenReturn(message);
        when(message.editMessage(any(MessageEditData.class))).thenReturn(messageEdit);
        when(event.editMessage(any(MessageEditData.class))).thenReturn(callbackEdit);
        when(handler.getNowPlaying(jda)).thenReturn(new MessageCreateBuilder().setContent("playing").build());
        when(handler.getNoMusicPlaying(jda)).thenReturn(new MessageCreateBuilder().setContent("idle").build());
    }

    @Test
    void editsTheCardDirectlyOnceAcknowledged()
    {
        when(event.isAcknowledged()).thenReturn(true);
        MusicService.OutputAdapter output = OutputAdapters.forFavoritePlaybackButton(event);

        output.editNowPlaying(handler);
        output.editNoMusic(handler);

        verify(message, times(2)).editMessage(any(MessageEditData.class));
        verify(messageEdit, times(2)).queue();
        verify(event, never()).editMessage(any(MessageEditData.class));
    }

    @Test
    void answersTheInteractionWhenNotYetAcknowledged()
    {
        when(event.isAcknowledged()).thenReturn(false);
        MusicService.OutputAdapter output = OutputAdapters.forFavoritePlaybackButton(event);

        output.editNowPlaying(handler);

        verify(event).editMessage(any(MessageEditData.class));
        verify(callbackEdit).queue();
        verify(message, never()).editMessage(any(MessageEditData.class));
    }

    @Test
    void playbackButtonUsesTheSameRule()
    {
        when(event.isAcknowledged()).thenReturn(true);
        OutputAdapters.forPlaybackButton(event).editNowPlaying(handler);

        verify(message).editMessage(any(MessageEditData.class));
        verify(event, never()).editMessage(any(MessageEditData.class));
    }
}
