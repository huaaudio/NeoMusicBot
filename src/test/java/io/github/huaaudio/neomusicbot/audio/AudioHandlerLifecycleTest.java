package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.Bot;
import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.settings.RepeatMode;
import io.github.huaaudio.neomusicbot.settings.SettingsManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AudioHandlerLifecycleTest
{
    @TempDir Path directory;

    @Test
    void aDelayedNaturalEndCannotRestartRepeatAfterStop() throws Exception
    {
        try(Fixture fixture = new Fixture(directory))
        {
            fixture.bot.getSettingsManager().getSettings(42L).setRepeatMode(RepeatMode.SINGLE);
            AudioTrack first = track("first");
            fixture.handler.addTrackAndCompleteLoad(fixture.handler.reserveLoad(),
                    new QueuedTrack(first, null), false);
            CountDownLatch sessionBlocked = new CountDownLatch(1);
            CountDownLatch endDelivered = new CountDownLatch(1);
            fixture.handler.getSession().execute(() -> {
                sessionBlocked.countDown();
                try
                {
                    assertTrue(endDelivered.await(5, TimeUnit.SECONDS));
                    fixture.handler.stopAndClear();
                }
                catch(InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            });
            assertTrue(sessionBlocked.await(5, TimeUnit.SECONDS));
            try(var sender = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory()))
            {
                sender.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while(fixture.player.getPlayingTrack() == first && System.nanoTime() < deadline)
                    {
                        fixture.player.provide();
                        Thread.sleep(5);
                    }
                    return null;
                }).get(4, TimeUnit.SECONDS);
            }
            finally
            {
                endDelivered.countDown();
            }
            fixture.handler.getSession().run(() -> {});
            assertNull(fixture.handler.getPlaybackState().current());
            assertTrue(fixture.handler.getQueueSnapshot().isEmpty());
        }
    }

    @Test
    void playbackFailureDoesNotExposeCredentialsOrSignedPaths() throws Exception
    {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AudioHandler.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.addAppender(captured);
        try(Fixture fixture = new Fixture(directory))
        {
            fixture.handler.onTrackException(fixture.player,
                    track("https://fake-user:fake-password@media.invalid/signed-path-secret/audio#fragment-secret"),
                    new com.sedmelluq.discord.lavaplayer.tools.FriendlyException(
                            "decode failed https://media.invalid/?token=message-secret",
                            com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON,
                            new IllegalStateException("cause-secret")));
            assertEquals(1, captured.list.size());
            var event = captured.list.getFirst();
            String message = event.getFormattedMessage();
            assertAll(
                    () -> assertFalse(message.contains("fake-user")),
                    () -> assertFalse(message.contains("fake-password")),
                    () -> assertFalse(message.contains("signed-path-secret")),
                    () -> assertFalse(message.contains("fragment-secret")),
                    () -> assertFalse(message.contains("message-secret")),
                    () -> assertFalse(message.contains("cause-secret")),
                    () -> assertTrue(message.contains("decode failed")),
                    () -> assertTrue(message.contains("COMMON")),
                    () -> assertNull(event.getThrowableProxy()));
        }
        finally
        {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private static AudioTrack track(String id)
    {
        return new BaseAudioTrack(new AudioTrackInfo(id, "test", 20L, id, false, null))
        {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception
            {
                executor.getAudioBuffer().consume(new ImmutableAudioFrame(0,
                        new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE},
                        100, StandardAudioDataFormats.DISCORD_OPUS));
                executor.waitOnEnd();
            }
            @Override protected AudioTrack makeShallowClone() { return track(id); }
        };
    }

    private static final class Fixture implements AutoCloseable
    {
        final Bot bot;
        final AudioPlayer player;
        final AudioHandler handler;

        Fixture(Path directory) throws Exception
        {
            var constructor = SettingsManager.class.getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            SettingsManager settings = constructor.newInstance(directory.resolve("settings.json"));
            bot = new Bot(new BotConfig(null)
            {
                @Override public int getMaxYTPlaylistPages() { return 10; }
            }, settings);
            player = bot.getPlayerManager().createPlayer();
            Guild guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(),
                    new Class<?>[]{Guild.class}, (proxy, method, arguments) -> {
                        if(method.getName().equals("getIdLong")) return 42L;
                        throw new UnsupportedOperationException(method.getName());
                    });
            handler = new AudioHandler(bot.getPlayerManager(), guild, player);
            player.addListener(handler);
        }

        @Override public void close()
        {
            handler.destroy();
            bot.shutdown();
        }
    }
}
