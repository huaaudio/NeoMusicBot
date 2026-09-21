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
import com.jagrosh.jmusicbot.audio.media.MediaResolverEnvironment;
import com.jagrosh.jmusicbot.audio.media.MediaResolverReadiness;
import com.jagrosh.jmusicbot.audio.media.MediaSource;
import com.jagrosh.jmusicbot.audio.media.YtDlpConfiguration;
import com.jagrosh.jmusicbot.audio.media.YtDlpMediaResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.dv8tion.jda.api.entities.Guild;

/** Central Lavaplayer source and guild-player manager. */
public class PlayerManager extends DefaultAudioPlayerManager
{
    private final Bot bot;
    private YtDlpMediaResolver mediaResolver;
    private MediaResolverReadiness mediaResolverReadiness;

    public PlayerManager(Bot bot)
    {
        this.bot = bot;
    }

    public synchronized void init()
    {
        if (mediaResolver != null)
            throw new IllegalStateException("PlayerManager is already initialized");

        YtDlpConfiguration resolverConfiguration = MediaResolverEnvironment.load();
        mediaResolverReadiness = MediaResolverReadiness.inspect(resolverConfiguration);
        mediaResolver = new YtDlpMediaResolver(resolverConfiguration);

        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(true);
        youtube.setPlaylistPageCount(bot.getConfig().getMaxYTPlaylistPages());
        if (resolverConfiguration.youtubeFallbackEnabled())
        {
            YtDlpAudioSourceManager fallbackTracks = new YtDlpAudioSourceManager(
                    "youtube-yt-dlp", MediaSource.YOUTUBE, mediaResolver, this);
            int maximumEntries = Math.min(50,
                    Math.max(10, bot.getConfig().getMaxYTPlaylistPages() * 10));
            registerSourceManager(new YoutubeFallbackAudioSourceManager(
                    youtube, fallbackTracks, mediaResolver, maximumEntries));
            registerSourceManager(fallbackTracks);
        }
        else
        {
            registerSourceManager(youtube);
        }

        registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        registerSourceManager(new BilibiliAudioSourceManager(mediaResolver, this));
        registerSourceManager(new DiscordAttachmentAudioSourceManager());

        // No generic local or HTTP source is registered. Playlist entries and
        // user requests must match an explicitly allow-listed source manager.
    }

    public Bot getBot()
    {
        return bot;
    }

    public YtDlpMediaResolver getMediaResolver()
    {
        return mediaResolver;
    }

    public MediaResolverReadiness getMediaResolverReadiness()
    {
        return mediaResolverReadiness;
    }

    public boolean hasHandler(Guild guild)
    {
        return guild.getAudioManager().getSendingHandler() != null;
    }

    public synchronized AudioHandler setUpHandler(Guild guild)
    {
        AudioHandler handler;
        if (guild.getAudioManager().getSendingHandler() == null)
        {
            AudioPlayer player = createPlayer();
            player.setVolume(bot.getSettingsManager().getSettings(guild).getVolume());
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
            guild.getAudioManager().setConnectionListener(
                    new GuildAudioConnectionListener(handler, guild.getIdLong()));
        }
        else
        {
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        }
        return handler;
    }

    @Override
    public void shutdown()
    {
        try
        {
            super.shutdown();
        }
        finally
        {
            if (mediaResolver != null)
                mediaResolver.close();
        }
    }
}
