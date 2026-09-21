/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh (john.a.grosh@gmail.com)
 */
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
package io.github.huaaudio.neomusicbot;

import io.github.huaaudio.neomusicbot.audio.AudioHandler;
import io.github.huaaudio.neomusicbot.audio.GuildPlaybackSession;
import io.github.huaaudio.neomusicbot.utils.OtherUtil;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Non-command gateway event handling. */
public final class Listener extends ListenerAdapter
{
    private static final Logger LOG = LoggerFactory.getLogger(Listener.class);
    private final Bot bot;

    public Listener(Bot bot)
    {
        this.bot = bot;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event)
    {
        if (event.getJDA().getGuildCache().isEmpty())
        {
            LOG.warn("This bot is not in any guild. Invite URL: {}",
                    event.getJDA().getInviteUrl(NeoMusicBot.RECOMMENDED_PERMS));
        }

        event.getJDA().getGuilds().forEach(guild ->
        {
            guild.getAudioManager().setSelfDeafened(true);
            guild.getAudioManager().setAutoReconnect(true);
            AudioHandler handler = null;
            GuildPlaybackSession.VoiceReservation reservation = null;
            VoiceChannel channel = null;
            try
            {
                String defaultPlaylist = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                channel = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if (defaultPlaylist != null && channel != null)
                {
                    handler = bot.getPlayerManager().setUpHandler(guild);
                    reservation = handler.reserveVoiceChannel(channel.getIdLong());
                    boolean usable = reservation.result()
                            == GuildPlaybackSession.VoiceReservationResult.OPEN
                            || reservation.result()
                            == GuildPlaybackSession.VoiceReservationResult.ALREADY_RESERVED;
                    if(usable && handler.playFromDefault())
                    {
                        if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.OPEN)
                            guild.getAudioManager().openAudioConnection(channel);
                    }
                    else if(reservation.result() == GuildPlaybackSession.VoiceReservationResult.OPEN)
                    {
                        handler.rollbackVoiceReservation(channel.getIdLong());
                    }
                }
            }
            catch (RuntimeException ex)
            {
                if(handler != null && reservation != null
                        && reservation.result() == GuildPlaybackSession.VoiceReservationResult.OPEN)
                {
                    handler.disconnectAndClear();
                    if(channel != null)
                        handler.rollbackVoiceReservation(channel.getIdLong());
                    bot.closeAudioConnection(guild.getIdLong());
                }
                LOG.warn("Could not start the default playlist for guild {}", guild.getId(), ex);
            }
        });

        if (bot.getConfig().useUpdateAlerts())
        {
            bot.getThreadpool().scheduleWithFixedDelay(this::sendUpdateAlert, 0, 24, TimeUnit.HOURS);
        }
    }

    private void sendUpdateAlert()
    {
        try
        {
            User owner = bot.getJDA().retrieveUserById(bot.getConfig().getOwnerId()).complete();
            String currentVersion = OtherUtil.getCurrentVersion();
            String latestVersion = OtherUtil.getLatestVersion();
            if (OtherUtil.isNewerVersion(currentVersion, latestVersion))
            {
                String message = String.format(OtherUtil.NEW_VERSION_AVAILABLE, currentVersion, latestVersion);
                owner.openPrivateChannel().queue(channel -> channel.sendMessage(message).queue());
            }
        }
        catch (RuntimeException ex)
        {
            LOG.debug("Could not perform the update check", ex);
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event)
    {
        if(event.getMember().getIdLong() == event.getGuild().getSelfMember().getIdLong()
                && event.getGuild().getAudioManager().getSendingHandler() instanceof AudioHandler handler)
        {
            long joinedChannelId = event.getChannelJoined() == null
                    ? 0L : event.getChannelJoined().getIdLong();
            handler.observeVoiceChannel(joinedChannelId);
        }
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
    }

    @Override
    public void onShutdown(@NotNull ShutdownEvent event)
    {
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event)
    {
        event.getGuild().getAudioManager().setSelfDeafened(true);
        event.getGuild().getAudioManager().setAutoReconnect(true);
    }

}
