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
package com.jagrosh.jmusicbot.commands.v1;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jdautilities.menu.OrderedMenu;
import com.jagrosh.jmusicbot.service.SearchResult;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.GenericMessageEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * A menu that shows search results as one embed per result, so that every result can carry its
 * own thumbnail.
 *
 * <p>{@link OrderedMenu} puts all the choices in the description of a single embed, which leaves
 * room for only one thumbnail. A message can hold up to ten embeds, so this menu sends one embed
 * per result and lets the user pick with a number reaction or by typing the number.
 *
 * @author Arif Banai (arif-banai)
 */
public final class SearchMenu
{
    private static final Logger LOG = LoggerFactory.getLogger(SearchMenu.class);

    /**
     * The largest number of results that is shown.
     */
    public static final int MAX_RESULTS = 5;

    /**
     * The value that means "not one of the shown numbers".
     */
    private static final int NOT_A_NUMBER = Integer.MIN_VALUE;

    private SearchMenu()
    {
    }

    /**
     * Shows the results and waits for the user to pick one.
     *
     * @param waiter the event waiter
     * @param channel the channel to post in
     * @param user the user that may interact with the menu
     * @param results the results to show
     * @param color the accent colour of the embeds
     * @param timeout how long to wait for a selection
     * @param unit the unit of the timeout
     * @param onSelect called with the index of the chosen result, starting at 0
     * @param onCancel called when the user cancels, may be null
     */
    public static void display(EventWaiter waiter, MessageChannel channel, User user,
                               List<SearchResult> results, Color color,
                               long timeout, TimeUnit unit,
                               IntConsumer onSelect, Runnable onCancel)
    {
        // Only keep the pairs of result and embed that were built, so the reaction numbers and
        // the results stay in step even when one embed cannot be built.
        List<SearchResult> shown = new ArrayList<>();
        List<MessageEmbed> embeds = new ArrayList<>();
        for (SearchResult result : results)
        {
            if (shown.size() >= MAX_RESULTS)
            {
                break;
            }
            try
            {
                embeds.add(result.buildEmbed(color).build());
                shown.add(result);
            }
            catch (RuntimeException e)
            {
                // One malformed result must not hide the rest.
                LOG.warn("Could not build the embed for a search result: {}", e.toString());
            }
        }

        int count = shown.size();
        if (count == 0)
        {
            return;
        }

        channel.sendMessageEmbeds(embeds).queue(message ->
        {
            // The reactions are a convenience; a missing permission must not break the menu,
            // because the number can also be typed.
            try
            {
                for (int i = 0; i < count; i++)
                {
                    message.addReaction(Emoji.fromFormatted(OrderedMenu.NUMBERS[i])).queue();
                }
                if (onCancel != null)
                {
                    message.addReaction(Emoji.fromFormatted(OrderedMenu.CANCEL)).queue();
                }
            }
            catch (PermissionException e)
            {
                LOG.debug("Could not add the menu reactions: {}", e.toString());
            }

            waiter.waitForEvent(GenericMessageEvent.class,
                    event -> isValid(message, user, count, event),
                    event -> handle(message, shown, event, onSelect, onCancel),
                    timeout, unit,
                    () -> LOG.debug("Search menu timed out"));
        });
    }

    /**
     * @return true if the event is a valid interaction with the menu
     */
    private static boolean isValid(Message message, User user, int count, GenericMessageEvent event)
    {
        if (!message.getId().equals(event.getMessageId())
                || !event.getChannel().getId().equals(message.getChannel().getId()))
        {
            return false;
        }

        if (event instanceof MessageReactionAddEvent reaction)
        {
            return !reaction.getUser().isBot()
                    && reaction.getUser().getIdLong() == user.getIdLong()
                    && (OrderedMenu.CANCEL.equals(reaction.getEmoji().getName())
                        || number(reaction.getEmoji().getName(), count) != NOT_A_NUMBER);
        }

        if (event instanceof MessageReceivedEvent received)
        {
            return received.getAuthor().getIdLong() == user.getIdLong()
                    && number(received.getMessage().getContentRaw(), count) != NOT_A_NUMBER;
        }

        return false;
    }

    /**
     * Handles a valid interaction with the menu.
     */
    private static void handle(Message message, List<SearchResult> results,
                               GenericMessageEvent event, IntConsumer onSelect, Runnable onCancel)
    {
        int count = results.size();
        int index = NOT_A_NUMBER;
        boolean cancel = false;

        if (event instanceof MessageReactionAddEvent reaction)
        {
            cancel = OrderedMenu.CANCEL.equals(reaction.getEmoji().getName());
            if (!cancel)
            {
                index = number(reaction.getEmoji().getName(), count);
            }
        }
        else if (event instanceof MessageReceivedEvent received)
        {
            index = number(received.getMessage().getContentRaw(), count);
        }
        else
        {
            return;
        }

        message.delete().queue(null, error -> { });

        if (cancel)
        {
            if (onCancel != null)
            {
                onCancel.run();
            }
        }
        else if (index != NOT_A_NUMBER)
        {
            onSelect.accept(index);
        }
    }

    /**
     * Reads a result number out of a reaction name or a typed message.
     *
     * <p>The name of a keycap reaction is the digit followed by the combining enclosing keycap
     * ({@code U+20E3}). Both that form and a plain digit are understood. Anything else, including
     * a multi digit number like {@code 12}, is not a valid choice.
     *
     * @param value the reaction name or the message content
     * @param count the number of results that are shown
     * @return the index of the result, starting at 0, or {@link #NOT_A_NUMBER}
     */
    static int number(String value, int count)
    {
        if (value == null)
        {
            return NOT_A_NUMBER;
        }

        // Drop the marks that make a digit a keycap, and the optional variation selectors.
        String stripped = value.trim()
                .replace("\u20E3", "")
                .replace("\uFE0F", "")
                .replace("\uFE0E", "");

        if (stripped.length() != 1)
        {
            return NOT_A_NUMBER;
        }

        char first = stripped.charAt(0);
        if (first < '1' || first > '9')
        {
            return NOT_A_NUMBER;
        }

        int digit = first - '0';
        return digit <= count ? digit - 1 : NOT_A_NUMBER;
    }
}
