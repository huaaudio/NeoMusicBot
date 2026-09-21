/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh (john.a.grosh@gmail.com)
 */
/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Activity;

/** Keeps the optional song activity in sync with playback. */
public final class NowplayingHandler
{
    private final Bot bot;

    public NowplayingHandler(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        // Slash command responses are immutable snapshots, so there is no
        // persistent message to poll and edit every five seconds.
    }

    public void onTrackUpdate(AudioTrack track)
    {
        if (!bot.getConfig().getSongInStatus() || bot.getJDA() == null)
            return;

        long activeGuilds = bot.getJDA().getGuilds().stream()
                .filter(guild -> guild.getSelfMember().getVoiceState() != null)
                .filter(guild -> guild.getSelfMember().getVoiceState().inAudioChannel())
                .count();
        if (track != null && activeGuilds <= 1)
            bot.getJDA().getPresence().setActivity(Activity.listening(track.getInfo().title));
        else
            bot.resetGame();
    }
}
