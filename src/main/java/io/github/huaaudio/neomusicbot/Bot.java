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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime container for the bot services. */
public class Bot
{
    private static final Logger LOG = LoggerFactory.getLogger(Bot.class);
    private final ScheduledThreadPoolExecutor threadpool;
    private final Thread shutdownHook;
    private final CountDownLatch shutdownFinished = new CountDownLatch(1);
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
        this.threadpool = new ScheduledThreadPoolExecutor(workerCount);
        this.threadpool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.threadpool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.shutdownHook = new Thread(() -> {
            shutdown();
            // If another thread is already shutting down, keep the JVM alive
            // long enough for its final settings flush and source cleanup.
            try
            {
                if(!shutdownFinished.await(30, TimeUnit.SECONDS))
                    LOG.warn("Timed out waiting for application shutdown");
            }
            catch(InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }, "NeoMusicBot-shutdown");
        this.players = new PlayerManager(this);
        this.nowplaying = new NowplayingHandler(this);
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        try
        {
            this.players.init();
            this.nowplaying.init();
            this.aloneInVoiceHandler.init();
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        catch(RuntimeException | LinkageError failure)
        {
            shutdown();
            throw failure;
        }
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

    public void shutdown()
    {
        JDA discord;
        GUI window;
        synchronized(this)
        {
            if(shuttingDown)
                return;
            shuttingDown = true;
            discord = jda;
            window = gui;
        }

        try
        {
            // Never hold the bot monitor across library calls: shutdown may trigger
            // callbacks on another thread. One broken component must not skip others.
            if(discord != null)
            {
                shutdownStep("guild audio cleanup", () -> discord.getGuilds().forEach(guild ->
                        shutdownStep("guild audio", () -> closeGuildResources(guild))));
                shutdownStep("Discord client", discord::shutdown);
            }
            shutdownStep("audio sources", players::shutdown);
            shutdownStep("server settings", settings::close);
            // Drain accepted immediate tasks so callers waiting on their results do
            // not hang. Delayed and periodic tasks are cancelled by the policies above.
            shutdownStep("workers", threadpool::shutdown);
            if(window != null)
                shutdownStep("GUI", window::dispose);
        }
        finally
        {
            shutdownFinished.countDown();
            if(Thread.currentThread() != shutdownHook)
            {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch(IllegalStateException ignored)
                {
                    // JVM shutdown has already started; the hook is idempotent.
                }
            }
        }
    }

    private void closeGuildResources(Guild guild)
    {
        Object sender = guild.getAudioManager().getSendingHandler();
        shutdownStep("voice connection", () -> closeAudioConnection(guild, null));
        if(sender instanceof AudioHandler handler)
        {
            shutdownStep("playback stop", handler::stopAndClear);
            shutdownStep("player", handler::destroy);
        }
    }

    private static void shutdownStep(String component, Runnable action)
    {
        try { action.run(); }
        catch(RuntimeException | LinkageError failure)
        {
            LOG.warn("Could not clean up {} ({})", component, failure.getClass().getSimpleName());
        }
    }

    public void setJDA(JDA jda)
    {
        Objects.requireNonNull(jda, "jda");
        synchronized(this)
        {
            if(!shuttingDown)
            {
                this.jda = jda;
                return;
            }
        }
        shutdownStep("late Discord client", jda::shutdown);
    }

    public void setGUI(GUI gui)
    {
        Objects.requireNonNull(gui, "gui");
        synchronized(this)
        {
            if(!shuttingDown)
            {
                this.gui = gui;
                return;
            }
        }
        shutdownStep("late GUI", gui::dispose);
    }
}
