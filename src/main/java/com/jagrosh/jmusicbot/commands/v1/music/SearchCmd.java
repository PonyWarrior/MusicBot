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
import com.jagrosh.jdautilities.menu.OrderedMenu;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.v1.MusicCommand;
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
 * <p>Every result is shown with its artist and its thumbnail, and the results from the local
 * music library are listed first.
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SearchCmd extends MusicCommand
{
    protected String searchPrefix = "ytsearch:";
    private final OrderedMenu.Builder builder;
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
        builder = new OrderedMenu.Builder()
                .allowTextInput(true)
                .useNumbers()
                .useCancelButton(true)
                .setEventWaiter(bot.getWaiter())
                .setTimeout(1, TimeUnit.MINUTES);
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

            int limit = Math.min(4, results.size());

            builder.setColor(event.getSelfMember().getColor())
                    .setText(FormatUtil.filter(event.getClient().getSuccess()+" Search results for `"+event.getArgs()+"`:"))
                    .setChoices(new String[0])
                    .setSelection((msg,i) ->
                    {
                        SearchResult result = results.get(i-1);
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
                    })
                    .setCancel((msg) -> {})
                    .setUsers(event.getAuthor())
                    ;

            for(int i=0; i<limit; i++)
            {
                builder.addChoices(results.get(i).formatChoice(i + 1));
            }
            builder.build().display(m);

            // The menu itself can only carry one embed, so the results with their thumbnails
            // are sent as a separate message.
            List<net.dv8tion.jda.api.EmbedBuilder> embeds =
                    bot.getSearchService().buildResultEmbeds(results, limit, event.getSelfMember().getColor());
            if(!embeds.isEmpty())
            {
                m.getChannel().sendMessageEmbeds(embeds.stream()
                        .map(net.dv8tion.jda.api.EmbedBuilder::build).toList()).queue();
            }
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
    }
}
