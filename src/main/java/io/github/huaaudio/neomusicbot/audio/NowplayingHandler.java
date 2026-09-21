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
package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.Bot;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;
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
        JDA jda = bot.getJDA();
        if (!bot.getConfig().getSongInStatus() || jda == null)
            return;

        long activeGuilds = jda.getGuilds().stream()
                .map(guild -> guild.getSelfMember().getVoiceState())
                .filter(state -> state != null && state.inAudioChannel())
                .count();
        String title = track == null ? null : track.getInfo().title;
        if (title != null && !title.isBlank() && activeGuilds <= 1)
        {
            title = title.strip();
            // JDA measures activity names in Unicode code points. Shorten only
            // the displayed activity, preserving the original media metadata.
            if (title.codePointCount(0, title.length()) > Activity.MAX_ACTIVITY_NAME_LENGTH)
                title = title.substring(0, title.offsetByCodePoints(0, Activity.MAX_ACTIVITY_NAME_LENGTH - 1)) + "\u2026";
            jda.getPresence().setActivity(Activity.listening(title));
        }
        else
            bot.resetGame();
    }
}
