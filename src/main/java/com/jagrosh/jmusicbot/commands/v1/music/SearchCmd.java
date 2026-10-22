/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.v1.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.v1.MusicCommand;
import com.jagrosh.jmusicbot.commands.v1.SearchMenu;
import com.jagrosh.jmusicbot.service.SearchResult;
import com.jagrosh.jmusicbot.service.SearchService;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Searches for a track and lets the user pick one of the results.
 *
 * <p>Every result is shown as its own embed, with the thumbnail and the artist of that result,
 * because a single embed can only carry a single thumbnail. The results from the local music
 * library are listed first.
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SearchCmd extends MusicCommand
{
    protected String searchPrefix = "ytsearch:";
    private final String searchingEmoji;

    public SearchCmd(Bot bot)
    {
        super(bot);
        this.searchingEmoji = bot.getConfig().getSearching();
        this.name = "search";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.arguments = "<query>";
        this.help = "searches Youtube for a provided query";
        this.beListening = true;
        this.bePlaying = false;
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        if(event.getArgs().isEmpty())
        {
            event.replyError("Please include a query.");
            return;
        }
        event.reply(searchingEmoji+" Searching... `["+event.getArgs()+"]`",
                m -> bot.getSearchService().search(event.getGuild(), event.getMember(), event.getArgs(),
                        searchPrefix, event.getTextChannel(), new ResultHandler(m, event)));
    }

    private class ResultHandler implements SearchService.SearchCallback
    {
        private final Message m;
        private final CommandEvent event;

        private ResultHandler(Message m, CommandEvent event)
        {
            this.m = m;
            this.event = event;
        }

        @Override
        public void onTrackLoaded(AudioTrack track, int queuePosition, String formattedMessage)
        {
            if(bot.getConfig().isTooLong(track))
            {
                String trackTitle = FormatUtil.getTrackTitle(track);
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" This track (**"+trackTitle+"**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+bot.getConfig().getMaxTime()+"`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            String trackTitle = FormatUtil.getTrackTitle(track);
            m.editMessage(FormatUtil.filter(event.getClient().getSuccess()+" Added **"+trackTitle
                    +"** (`"+ TimeUtil.formatTime(track.getDuration())+"`) "+(pos==0 ? "to begin playing"
                        : " to the queue at position "+pos))).queue();
        }

        @Override
        public void onSearchResults(List<SearchResult> results, String[] formattedChoices)
        {
            if(results.isEmpty())
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" No results found for `"
                        + event.getArgs()+"`.")).queue();
                return;
            }

            // The "searching" message is replaced by the results, which are their own message
            // so that every result can carry its own thumbnail.
            m.delete().queue(null, ignored -> { });

            SearchMenu.display(bot.getWaiter(), event.getChannel(), event.getAuthor(), results,
                    event.getSelfMember().getColor(), 1, TimeUnit.MINUTES,
                    index -> select(results.get(index)),
                    () -> { });
        }

        @Override
        public void onNoMatches(String query)
        {
            m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" No results found for `"
                    + query+"`.")).queue();
        }

        @Override
        public void onLoadFailed(String errorMessage)
        {
            m.editMessage(FormatUtil.filter(event.getClient().getError()+" "+errorMessage)).queue();
        }

        @Override
        public void onError(String message)
        {
            m.editMessage(FormatUtil.filter(event.getClient().getError()+" "+message)).queue();
        }

        /**
         * Adds the chosen result to the queue.
         *
         * @param result the result that was chosen
         */
        private void select(SearchResult result)
        {
            AudioTrack track = result.track();
            String trackTitle = result.title() == null ? "" : result.title();

            if(bot.getConfig().isTooLong(track))
            {
                event.replyWarning("This track (**"+trackTitle+"**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+bot.getConfig().getMaxTime()+"`");
                return;
            }

            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            event.replySuccess("Added **" + FormatUtil.filter(trackTitle)
                    + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos==0 ? "to begin playing"
                        : " to the queue at position "+pos));
        }
    }
}
