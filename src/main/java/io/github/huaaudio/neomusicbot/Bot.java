/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh <john.a.grosh@gmail.com>
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
package io.github.huaaudio.neomusicbot;

import io.github.huaaudio.neomusicbot.audio.AloneInVoiceHandler;
import io.github.huaaudio.neomusicbot.audio.AudioHandler;
import io.github.huaaudio.neomusicbot.audio.NowplayingHandler;
import io.github.huaaudio.neomusicbot.audio.PlayerManager;
import io.github.huaaudio.neomusicbot.gui.GUI;
import io.github.huaaudio.neomusicbot.playlist.PlaylistLoader;
import io.github.huaaudio.neomusicbot.settings.SettingsManager;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;

/** Runtime container for the bot services. */
public class Bot
{
    private final ScheduledExecutorService threadpool;
    private final BotConfig config;
    private final SettingsManager settings;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final NowplayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;

    private volatile boolean shuttingDown;
    private volatile JDA jda;
    private GUI gui;

    public Bot(BotConfig config, SettingsManager settings)
    {
        this.config = config;
        this.settings = settings;
        this.playlists = new PlaylistLoader(config);
        int workerCount = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.threadpool = Executors.newScheduledThreadPool(workerCount);
        this.players = new PlayerManager(this);
        this.players.init();
        this.nowplaying = new NowplayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
    }

    public BotConfig getConfig()
    {
        return config;
    }

    public SettingsManager getSettingsManager()
    {
        return settings;
    }

    public ScheduledExecutorService getThreadpool()
    {
        return threadpool;
    }

    public PlayerManager getPlayerManager()
    {
        return players;
    }

    public PlaylistLoader getPlaylistLoader()
    {
        return playlists;
    }

    public NowplayingHandler getNowplayingHandler()
    {
        return nowplaying;
    }

    public AloneInVoiceHandler getAloneInVoiceHandler()
    {
        return aloneInVoiceHandler;
    }

    public JDA getJDA()
    {
        return jda;
    }

    public void closeAudioConnection(long guildId)
    {
        if (jda == null)
            return;
        Guild guild = jda.getGuildById(guildId);
        if (guild != null)
            closeAudioConnection(guild, null);
    }

    public void closeAudioConnection(long guildId, long expectedGeneration)
    {
        if (jda == null)
            return;
        Guild guild = jda.getGuildById(guildId);
        if (guild == null)
            return;
        if (guild.getAudioManager().getSendingHandler() instanceof AudioHandler handler)
        {
            handler.closeIfIdle(expectedGeneration, () ->
            {
                closeAudioConnection(guild, handler);
            });
        }
    }

    private void closeAudioConnection(Guild guild, AudioHandler expectedHandler)
    {
        Object sendingHandler = guild.getAudioManager().getSendingHandler();
        if(expectedHandler != null && sendingHandler != expectedHandler)
            return;
        AudioHandler handler = sendingHandler instanceof AudioHandler audioHandler ? audioHandler : null;
        if(handler != null)
            handler.beginVoiceClose();

        // Explicit closes, especially terminal encryption/authentication
        // failures, must stop JDA's automatic reconnect loop. The next
        // explicit open enables it again.
        guild.getAudioManager().setAutoReconnect(false);
        guild.getAudioManager().closeAudioConnection();
        if(handler != null && guild.getAudioManager().getConnectedChannel() == null)
            handler.observeVoiceChannel(0L);
    }

    public void resetGame()
    {
        Activity game = config.getGame() == null || config.getGame().getName().equalsIgnoreCase("none")
                ? null : config.getGame();
        if (jda != null && !Objects.equals(jda.getPresence().getActivity(), game))
            jda.getPresence().setActivity(game);
    }

    public synchronized void shutdown()
    {
        if (shuttingDown)
            return;
        shuttingDown = true;

        if (jda != null && jda.getStatus() != JDA.Status.SHUTTING_DOWN)
        {
            jda.getGuilds().forEach(guild ->
            {
                closeAudioConnection(guild, null);
                if (guild.getAudioManager().getSendingHandler() instanceof AudioHandler handler)
                {
                    handler.stopAndClear();
                    handler.destroy();
                }
            });
            jda.shutdown();
        }

        players.shutdown();
        settings.close();
        threadpool.shutdownNow();
        if (gui != null)
            gui.dispose();
    }

    public void setJDA(JDA jda)
    {
        this.jda = jda;
    }

    public void setGUI(GUI gui)
    {
        this.gui = gui;
    }
}
