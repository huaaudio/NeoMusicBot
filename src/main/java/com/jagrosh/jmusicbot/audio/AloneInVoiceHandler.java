/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author Michaili K (mysteriouscursor+git@protonmail.com)
 */
/*
 * Copyright 2021 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disconnects after a guild has no human listeners for the configured period. */
public class AloneInVoiceHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(AloneInVoiceHandler.class);

    /** Playback changes must not restart the human-absence clock. */
    private record AloneState(Instant since) {}

    private final Bot bot;
    private final Map<Long, AloneState> aloneSince = new ConcurrentHashMap<>();
    private long aloneTimeUntilStop;

    public AloneInVoiceHandler(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        aloneTimeUntilStop = bot.getConfig().getAloneTimeUntilStop();
        if(aloneTimeUntilStop > 0)
            bot.getThreadpool().scheduleWithFixedDelay(this::checkSafely, 5, 5, TimeUnit.SECONDS);
    }

    private void checkSafely()
    {
        try
        {
            check();
        }
        catch(RuntimeException ex)
        {
            // A ScheduledExecutor suppresses every later execution when a
            // fixed-delay task throws. Keep the lifecycle monitor alive.
            LOG.warn("Could not evaluate alone-in-voice state", ex);
        }
    }

    private void check()
    {
        JDA jda = bot.getJDA();
        if(jda == null)
            return;

        Instant now = Instant.now();
        for(Map.Entry<Long, AloneState> entry : aloneSince.entrySet())
        {
            long guildId = entry.getKey();
            AloneState expected = entry.getValue();
            Guild guild = jda.getGuildById(guildId);
            AudioHandler handler = handler(guild);
            if(guild == null || handler == null || !isAlone(guild))
            {
                aloneSince.remove(guildId, expected);
                continue;
            }

            if(Duration.between(expected.since(), now).getSeconds() < aloneTimeUntilStop)
                continue;

            // Generation is only a stale-close guard. If it changes before the
            // task runs, the next check retries without resetting `since`.
            long expectedGeneration = handler.getGeneration();
            handler.getSession().execute(() -> disconnectIfStillAlone(
                    guild, handler, expected, expectedGeneration));
        }
    }

    private void disconnectIfStillAlone(Guild guild, AudioHandler handler,
                                        AloneState expected, long expectedGeneration)
    {
        long guildId = guild.getIdLong();
        if(aloneSince.get(guildId) != expected)
            return;
        if(!isAlone(guild))
        {
            aloneSince.remove(guildId, expected);
            return;
        }
        if(handler.getGeneration() != expectedGeneration)
            return;

        aloneSince.remove(guildId, expected);
        handler.disconnectAndClear();
        handler.closeVoiceConnectionAsync();
    }

    public void onVoiceUpdate(GuildVoiceUpdateEvent event)
    {
        if(aloneTimeUntilStop <= 0)
            return;

        Guild guild = event.getGuild();
        AudioHandler handler = handler(guild);
        if(handler == null)
            return;

        if(isAlone(guild))
            aloneSince.computeIfAbsent(guild.getIdLong(), ignored -> new AloneState(Instant.now()));
        else
            aloneSince.remove(guild.getIdLong());
    }

    private AudioHandler handler(Guild guild)
    {
        if(guild == null || !bot.getPlayerManager().hasHandler(guild))
            return null;
        return guild.getAudioManager().getSendingHandler() instanceof AudioHandler handler
                ? handler : null;
    }

    private boolean isAlone(Guild guild)
    {
        if(guild.getAudioManager().getConnectedChannel() == null)
            return false;
        return guild.getAudioManager().getConnectedChannel().getMembers().stream()
                .noneMatch(member -> !member.getUser().isBot());
    }
}
