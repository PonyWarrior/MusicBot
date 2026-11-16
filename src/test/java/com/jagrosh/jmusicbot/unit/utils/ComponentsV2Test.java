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
package com.jagrosh.jmusicbot.unit.utils;

import com.jagrosh.jmusicbot.testutil.V2Messages;
import com.jagrosh.jmusicbot.utils.ComponentsV2;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the conversion of classic embed widgets into Components V2 cards.
 */
class ComponentsV2Test
{
    private static MessageEmbed sampleEmbed()
    {
        return new EmbedBuilder()
                .setAuthor("My Guild")
                .setTitle("Song [Title]", "https://example.com/track")
                .setDescription("Playing now")
                .addField("Duration", "3:00", true)
                .addField("Volume", "100%", true)
                .addField("Requester", "someone", false)
                .setThumbnail("https://example.com/art.jpg")
                .setFooter("Footer text")
                .setColor(new Color(0x112233))
                .build();
    }

    private static List<ActionRow> sampleRows()
    {
        return List.of(ActionRow.of(Button.primary("np_pause", "Pause"), Button.danger("np_stop", "Stop")));
    }

    @Test
    void createBuildsOneCardWithEverything()
    {
        MessageCreateData message = ComponentsV2.create("Heads up", sampleEmbed(), sampleRows());

        assertTrue(message.isUsingComponentsV2());
        assertTrue(message.getContent().isEmpty());
        assertTrue(message.getEmbeds().isEmpty());
        assertEquals(1, message.getComponents().size());

        String text = V2Messages.text(message.getComponents());
        assertTrue(text.contains("Heads up"));
        assertTrue(text.contains("My Guild"));
        assertTrue(text.contains("https://example.com/track"));
        assertTrue(text.contains("Playing now"));
        assertTrue(text.contains("**Duration:** 3:00"));
        assertTrue(text.contains("**Requester**"));
        assertTrue(text.contains("Footer text"));

        assertEquals(2, V2Messages.buttons(message.getComponents()).size());
        assertEquals(1, MessageComponentTree.of(message.getComponents()).findAll(Thumbnail.class).size());

        Container container = (Container) message.getComponents().get(0);
        assertEquals(0x112233, container.getAccentColorRaw() & 0xFFFFFF);
    }

    @Test
    void titleMarkdownIsEscaped()
    {
        String text = V2Messages.text(ComponentsV2.create(null, sampleEmbed(), List.of()).getComponents());
        assertTrue(text.contains("Song \\[Title\\]"));
    }

    @Test
    void longDescriptionStaysUnderTheTextLimit()
    {
        MessageEmbed embed = new EmbedBuilder().setTitle("Long").setDescription("x".repeat(4096)).build();
        MessageCreateData message = ComponentsV2.create(null, embed, List.of());
        assertTrue(V2Messages.text(message.getComponents()).length() <= 4000);
    }

    @Test
    void toV2ConvertsLegacyAndKeepsV2()
    {
        MessageCreateData legacy = new MessageCreateBuilder()
                .setContent("Now Playing")
                .setEmbeds(sampleEmbed())
                .setComponents(sampleRows())
                .build();
        MessageCreateData converted = ComponentsV2.toV2(legacy);
        assertTrue(converted.isUsingComponentsV2());
        assertTrue(V2Messages.text(converted.getComponents()).contains("Now Playing"));
        assertSame(converted, ComponentsV2.toV2(converted));
        assertNull(ComponentsV2.toV2(null));
    }

    @Test
    void editOfKeepsTheFlagAndComponents()
    {
        MessageEditData edit = ComponentsV2.editOf(ComponentsV2.create(null, sampleEmbed(), sampleRows()));
        assertTrue(edit.isUsingComponentsV2());
        assertEquals(2, V2Messages.buttons(edit.getComponents()).size());
    }

    @Test
    void textEditIsV2()
    {
        MessageEditData edit = ComponentsV2.text("The queue is now empty!");
        assertTrue(edit.isUsingComponentsV2());
        assertEquals("The queue is now empty!", V2Messages.text(edit.getComponents()));
    }

    @Test
    void withNoticePutsTheNoticeFirst()
    {
        MessageCreateData message = ComponentsV2.withNotice("No music in queue",
                ComponentsV2.create(null, sampleEmbed(), sampleRows()));
        assertTrue(message.isUsingComponentsV2());
        assertTrue(V2Messages.text(message.getComponents()).startsWith("No music in queue"));
    }

    @Test
    void cardsMakesOneContainerPerEmbedAndRowsAfter()
    {
        MessageEmbed a = new EmbedBuilder().setTitle("Song 1").build();
        MessageEmbed b = new EmbedBuilder().setTitle("Song 2").build();
        MessageEditData edit = ComponentsV2.editCards("Results", List.of(a, b), sampleRows());

        assertTrue(edit.isUsingComponentsV2());
        long containers = edit.getComponents().stream().filter(c -> c instanceof Container).count();
        assertEquals(2, containers);
        String text = V2Messages.text(edit.getComponents());
        assertTrue(text.contains("Results") && text.contains("Song 1") && text.contains("Song 2"));
        assertEquals(1, V2Messages.rows(edit.getComponents()).size());
    }

    @Test
    void emptyEmbedStillProducesAValidCard()
    {
        MessageCreateData message = ComponentsV2.create(null, null, List.of());
        assertTrue(message.isUsingComponentsV2());
        assertFalse(message.getComponents().isEmpty());
    }
}
