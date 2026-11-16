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
package com.jagrosh.jmusicbot.commands.v2.music;

import com.jagrosh.jmusicbot.utils.ComponentsV2;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.v2.MusicSlashCommand;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.service.SearchResult;
import com.jagrosh.jmusicbot.service.SearchService;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Slash command to search for tracks and select from results.
 *
 * <p>Every result is shown as its own embed, with the thumbnail of that result and the artist
 * or uploader. The results from the local music library are listed first, because a track that
 * is already downloaded can be played straight from disk.
 */
public class SearchSlashCmd extends MusicSlashCommand
{
    protected String searchPrefix = "ytsearch:";
    protected String searchPlatform = "YouTube";
    private final MusicService musicService;
    private final String searchingEmoji;

    public SearchSlashCmd(Bot bot)
    {
        super(bot);
        this.musicService = bot.getMusicService();
        this.searchingEmoji = bot.getConfig().getSearching();
        this.name = "search";
        this.help = "searches YouTube for a provided query";
        this.options = Collections.singletonList(
                new OptionData(OptionType.STRING, "query", "the search query", true)
        );
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(SlashCommandEvent event)
    {
        String query = event.getOption("query").getAsString();

        event.reply(searchingEmoji + " Searching " + searchPlatform + " for `" + query + "`...").queue(hook ->
        {
            bot.getSearchService().search(event.getGuild(), event.getMember(), query, searchPrefix,
                    event.getTextChannel(), new SearchService.SearchCallback()
                    {
                        @Override
                        public void onTrackLoaded(AudioTrack track, int queuePosition, String formattedMessage)
                        {
                            hook.editOriginal(event.getClient().getSuccess() + " " + formattedMessage).queue();
                        }

                        @Override
                        public void onSearchResults(List<SearchResult> results, String[] formattedChoices)
                        {
                            if (results.isEmpty())
                            {
                                hook.editOriginal(event.getClient().getWarning() + " No results found for `" + query + "`.").queue();
                                return;
                            }

                            // Build select menu for search results
                            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("search_select_" + event.getUser().getId())
                                    .setPlaceholder("Select a track to play")
                                    .setMinValues(1)
                                    .setMaxValues(1);

                            int limit = Math.min(5, results.size());
                            for (SearchResult result : results.subList(0, limit))
                            {
                                String title = result.title() == null ? "" : result.title();
                                if (title.length() > 80)
                                {
                                    title = title.substring(0, 77) + "...";
                                }
                                String artist = result.artist() == null || result.artist().isBlank()
                                        ? "Unknown artist" : result.artist();
                                menuBuilder.addOption(
                                        title,
                                        result.url(),
                                        artist + " \u2022 " + TimeUtil.formatTime(result.duration())
                                                + " \u2022 " + result.sourceLabel()
                                );
                            }

                            // One embed per result, each with the thumbnail of that result.
                            List<EmbedBuilder> embeds = new ArrayList<>();
                            for (SearchResult result : results.subList(0, limit))
                            {
                                embeds.add(result.buildEmbed(event.getMember().getColor()));
                            }

                            hook.editOriginal(ComponentsV2.editCards(event.getClient().getSuccess() + " Search results for `" + query + "`", embeds.stream().map(EmbedBuilder::build).toList(), List.of(ActionRow.of(menuBuilder.build()))))
                                    .queue(msg ->
                                    {
                                        // Wait for selection
                                        bot.getWaiter().waitForEvent(
                                                StringSelectInteractionEvent.class,
                                                e -> e.getMessageId().equals(msg.getId()) &&
                                                        e.getUser().getIdLong() == event.getUser().getIdLong(),
                                                e ->
                                                {
                                                    String selectedUri = e.getValues().get(0);
                                                    e.deferEdit().queue();

                                                    // Find the selected track
                                                    AudioTrack selectedTrack = null;
                                                    for (SearchResult result : results)
                                                    {
                                                        if (result.url().equals(selectedUri))
                                                        {
                                                            selectedTrack = result.track();
                                                            break;
                                                        }
                                                    }

                                                    if (selectedTrack != null)
                                                    {
                                                        MusicService.TrackAddResult result = musicService.addTrackToQueue(
                                                                event.getGuild(),
                                                                event.getMember(),
                                                                selectedTrack,
                                                                query,
                                                                event.getTextChannel()
                                                        );

                                                        if (result != null)
                                                        {
                                                            hook.editOriginal(ComponentsV2.text(event.getClient().getSuccess() + " " + result.formattedMessage))
                                                                    .queue();
                                                        }
                                                        else
                                                        {
                                                            hook.editOriginal(ComponentsV2.text(event.getClient().getWarning() + " " + musicService.formatTooLongError(selectedTrack)))
                                                                    .queue();
                                                        }
                                                    }
                                                },
                                                1, TimeUnit.MINUTES,
                                                () -> hook.editOriginal(ComponentsV2.text("Search timed out."))
                                                .queue()
                                        );
                                    });
                        }

                        @Override
                        public void onNoMatches(String searchQuery)
                        {
                            hook.editOriginal(event.getClient().getWarning() + " No results found for `" + searchQuery + "`.").queue();
                        }

                        @Override
                        public void onLoadFailed(String errorMessage)
                        {
                            hook.editOriginal(event.getClient().getError() + " " + errorMessage).queue();
                        }

                        @Override
                        public void onError(String message)
                        {
                            hook.editOriginal(event.getClient().getError() + " " + message).queue();
                        }
                    });
        });
    }
}
