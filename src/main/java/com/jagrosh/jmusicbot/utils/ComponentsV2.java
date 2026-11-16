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
package com.jagrosh.jmusicbot.utils;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Renders the bot's widgets as Discord Components V2 messages.
 *
 * <p>Every widget is one {@link Container}: an accent colour, a header (with the artwork as a
 * thumbnail when there is one), the details, a footer, and the widget's buttons inside the same
 * card. The widgets are still described as {@link MessageEmbed}s by their builders, so the layout
 * logic stays in one place and this class only decides how they look.
 *
 * <p>A message sent with the Components V2 flag can never drop it again, so every later edit of
 * such a message must be a V2 edit too; {@link #editOf(MessageCreateData)} and
 * {@link #text(String)} take care of that.
 */
public final class ComponentsV2
{
    /** Discord allows 4000 characters of text across a whole V2 message; keep a margin. */
    static final int TEXT_BUDGET = 3800;

    private static final String EMPTY = "​";

    private ComponentsV2()
    {
    }

    /**
     * Builds the card for a widget.
     *
     * @param content an optional line shown above the card body (for example "Now Playing in X")
     * @param embed   the widget description, may be null
     * @param rows    action rows placed at the bottom of the card; non-row components are ignored
     * @return the container
     */
    public static Container container(String content, MessageEmbed embed,
                                      Collection<? extends MessageTopLevelComponent> rows)
    {
        return container(content, embed, rows, TEXT_BUDGET);
    }

    private static Container container(String content, MessageEmbed embed,
                                       Collection<? extends MessageTopLevelComponent> rows, int budget)
    {
        List<ContainerChildComponent> children = new ArrayList<>();

        if (content != null && !content.isBlank())
        {
            String text = truncate(content, budget / 4);
            budget -= text.length();
            children.add(TextDisplay.of(text));
        }

        Integer accent = null;
        if (embed != null)
        {
            if (embed.getColorRaw() != Role.DEFAULT_COLOR_RAW)
                accent = embed.getColorRaw();

            String fields = fieldsText(embed);
            String footer = footerText(embed);
            budget -= fields.length() + footer.length();

            String header = headerText(embed, Math.max(budget, 200));
            String thumbnail = embed.getThumbnail() == null ? null : embed.getThumbnail().getUrl();
            if (!header.isEmpty())
            {
                if (isWebUrl(thumbnail))
                    children.add(Section.of(Thumbnail.fromUrl(thumbnail), TextDisplay.of(header)));
                else
                    children.add(TextDisplay.of(header));
            }
            if (!fields.isEmpty())
                children.add(TextDisplay.of(fields));

            String image = embed.getImage() == null ? null : embed.getImage().getUrl();
            if (isWebUrl(image))
                children.add(MediaGallery.of(MediaGalleryItem.fromUrl(image)));

            if (!footer.isEmpty())
                children.add(TextDisplay.of(footer));
        }

        List<ContainerChildComponent> actionRows = new ArrayList<>();
        if (rows != null)
        {
            for (MessageTopLevelComponent row : rows)
            {
                if (row instanceof ContainerChildComponent child)
                    actionRows.add(child);
            }
        }
        if (!actionRows.isEmpty())
        {
            if (!children.isEmpty())
                children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.addAll(actionRows);
        }

        if (children.isEmpty())
            children.add(TextDisplay.of(EMPTY));

        Container container = Container.of(children);
        return accent == null ? container : container.withAccentColor(accent);
    }

    /**
     * @return a new V2 message holding one widget card
     */
    public static MessageCreateData create(String content, MessageEmbed embed,
                                           Collection<? extends MessageTopLevelComponent> rows)
    {
        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .setComponents(container(content, embed, rows))
                .build();
    }

    /**
     * @return a V2 edit that replaces a widget with a new card
     */
    public static MessageEditData edit(String content, MessageEmbed embed,
                                       Collection<? extends MessageTopLevelComponent> rows)
    {
        return new MessageEditBuilder()
                .useComponentsV2(true)
                .setComponents(container(content, embed, rows))
                .build();
    }

    /**
     * A V2 edit that replaces a widget with a single line of text, for example once the list it
     * showed has become empty.
     *
     * @param text the text
     * @return the edit
     */
    public static MessageEditData text(String text)
    {
        return new MessageEditBuilder()
                .useComponentsV2(true)
                .setComponents(TextDisplay.of(truncate(text == null || text.isBlank() ? EMPTY : text,
                        TEXT_BUDGET)))
                .build();
    }

    /**
     * Converts a classic message (content, embeds and action rows) into one V2 card. A message
     * that already uses Components V2 is returned unchanged.
     *
     * @param legacy the message, may be null
     * @return the V2 message, or null when {@code legacy} is null
     */
    public static MessageCreateData toV2(MessageCreateData legacy)
    {
        if (legacy == null || legacy.isUsingComponentsV2())
            return legacy;
        MessageEmbed embed = legacy.getEmbeds().isEmpty() ? null : legacy.getEmbeds().get(0);
        return create(legacy.getContent(), embed, legacy.getComponents());
    }

    /**
     * Turns a message into an edit of an existing message, keeping the Components V2 flag. Use it
     * wherever a widget is re-rendered in place.
     *
     * @param source the message to send as an edit
     * @return the edit
     */
    public static MessageEditData editOf(MessageCreateData source)
    {
        MessageCreateData message = toV2(source);
        return new MessageEditBuilder()
                .useComponentsV2(true)
                .setComponents(message.getComponents())
                .build();
    }

    /**
     * Adds a notice line above a widget, for example "There is no music in the queue!" above the
     * now-playing card.
     *
     * @param notice the notice
     * @param widget the widget message
     * @return a V2 message with the notice first
     */
    public static MessageCreateData withNotice(String notice, MessageCreateData widget)
    {
        MessageCreateData message = toV2(widget);
        List<MessageTopLevelComponent> components = new ArrayList<>();
        components.add(TextDisplay.of(truncate(notice, 200)));
        components.addAll(message.getComponents());
        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .setComponents(components)
                .build();
    }

    /**
     * Builds a message that shows several cards, for example one per search result, with an
     * optional heading line above them and action rows below them. The text budget is shared
     * between the cards so the whole message stays under Discord's limit.
     *
     * @param content an optional heading line
     * @param embeds  one card per embed
     * @param rows    action rows placed after the cards
     * @return the top-level components of the message
     */
    public static List<MessageTopLevelComponent> cards(String content, List<MessageEmbed> embeds,
                                                       Collection<? extends MessageTopLevelComponent> rows)
    {
        List<MessageTopLevelComponent> components = new ArrayList<>();
        int budget = TEXT_BUDGET;
        if (content != null && !content.isBlank())
        {
            String text = truncate(content, 300);
            budget -= text.length();
            components.add(TextDisplay.of(text));
        }
        int count = embeds == null ? 0 : embeds.size();
        for (int i = 0; i < count; i++)
            components.add(container(null, embeds.get(i), List.of(), Math.max(200, budget / count)));
        if (rows != null)
            components.addAll(rows);
        if (components.isEmpty())
            components.add(TextDisplay.of(EMPTY));
        return components;
    }

    /**
     * @return a new V2 message holding several cards; see {@link #cards}
     */
    public static MessageCreateData createCards(String content, List<MessageEmbed> embeds,
                                                Collection<? extends MessageTopLevelComponent> rows)
    {
        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .setComponents(cards(content, embeds, rows))
                .build();
    }

    /**
     * @return a V2 edit holding several cards; see {@link #cards}
     */
    public static MessageEditData editCards(String content, List<MessageEmbed> embeds,
                                            Collection<? extends MessageTopLevelComponent> rows)
    {
        return new MessageEditBuilder()
                .useComponentsV2(true)
                .setComponents(cards(content, embeds, rows))
                .build();
    }

    // ========== Layout ==========

    private static String headerText(MessageEmbed embed, int budget)
    {
        StringBuilder head = new StringBuilder();
        if (embed.getAuthor() != null && embed.getAuthor().getName() != null
                && !embed.getAuthor().getName().isBlank())
        {
            head.append("-# ").append(embed.getAuthor().getName()).append('\n');
        }
        if (embed.getTitle() != null && !embed.getTitle().isBlank())
        {
            String title = MarkdownSanitizer.escape(embed.getTitle().replace("\n", " "));
            head.append("### ");
            if (isWebUrl(embed.getUrl()))
                head.append('[').append(title.replace("[", "\\[").replace("]", "\\]"))
                        .append("](").append(embed.getUrl()).append(')');
            else
                head.append(title);
            head.append('\n');
        }

        String description = embed.getDescription();
        if (description != null && !description.isBlank())
        {
            int room = Math.max(0, budget - head.length());
            head.append(truncate(description.strip(), room));
        }
        return head.toString().strip();
    }

    private static String fieldsText(MessageEmbed embed)
    {
        StringBuilder text = new StringBuilder();
        StringBuilder inline = new StringBuilder();
        for (MessageEmbed.Field field : embed.getFields())
        {
            String name = field.getName() == null ? "" : field.getName().strip();
            String value = field.getValue() == null ? "" : field.getValue().strip();
            if (name.isEmpty() && value.isEmpty())
                continue;

            if (field.isInline())
            {
                if (!inline.isEmpty())
                    inline.append("  ·  ");
                inline.append(name.isEmpty() ? "" : "**" + name + ":** ").append(value);
                continue;
            }

            flush(text, inline);
            if (!name.isEmpty())
                text.append("**").append(name).append("**\n");
            text.append(value).append('\n');
        }
        flush(text, inline);
        return text.toString().strip();
    }

    private static void flush(StringBuilder text, StringBuilder inline)
    {
        if (!inline.isEmpty())
        {
            text.append(inline).append('\n');
            inline.setLength(0);
        }
    }

    private static String footerText(MessageEmbed embed)
    {
        List<String> parts = new ArrayList<>();
        if (embed.getFooter() != null && embed.getFooter().getText() != null
                && !embed.getFooter().getText().isBlank())
        {
            parts.add(embed.getFooter().getText().replace("\n", " "));
        }
        if (embed.getTimestamp() != null)
            parts.add("<t:" + embed.getTimestamp().toEpochSecond() + ":f>");
        return parts.isEmpty() ? "" : "-# " + String.join(" · ", parts);
    }

    static String truncate(String text, int max)
    {
        if (text == null)
            return "";
        if (text.length() <= max)
            return text;
        if (max <= 1)
            return max == 1 ? "…" : "";
        return text.substring(0, max - 1) + "…";
    }

    private static boolean isWebUrl(String url)
    {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /** Mirrors {@code Role.DEFAULT_COLOR_RAW} without depending on the entities package. */
    private static final class Role
    {
        static final int DEFAULT_COLOR_RAW = 0x1FFFFFFF;
    }
}
