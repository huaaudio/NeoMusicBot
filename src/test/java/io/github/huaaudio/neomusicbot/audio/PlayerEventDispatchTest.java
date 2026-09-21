package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.settings.QueueType;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEventDispatchTest
{
    @Test
    void naturalEndReleasesThePlayerLockBeforeStartingTheNextTrack() throws Exception
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        ExecutorService sender = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        AudioPlayer player = manager.createPlayer();
        GuildPlaybackSession session = new GuildPlaybackSession(player, QueueType.LINEAR, worker);
        CountDownLatch firstDecoded = new CountDownLatch(1);
        CountDownLatch nextStarted = new CountDownLatch(1);
        AudioTrack first = track("first", firstDecoded);
        AudioTrack next = track("next", new CountDownLatch(0));
        player.addListener(new AudioEventAdapter()
        {
            @Override public void onTrackEnd(AudioPlayer source, AudioTrack track, AudioTrackEndReason reason)
            {
                if(track == first && reason == AudioTrackEndReason.FINISHED)
                    session.dispatchPlayerEvent(() -> source.playTrack(next));
            }
            @Override public void onTrackStart(AudioPlayer source, AudioTrack track)
            {
                if(track == next)
                    nextStarted.countDown();
            }
        });
        boolean advanced = false;
        try
        {
            session.run(() -> player.playTrack(first));
            assertTrue(firstDecoded.await(5, TimeUnit.SECONDS));
            sender.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while(nextStarted.getCount() > 0 && System.nanoTime() < deadline)
                {
                    player.provide();
                    Thread.sleep(5);
                }
                return null;
            }).get(6, TimeUnit.SECONDS);
            advanced = nextStarted.await(2, TimeUnit.SECONDS);
            assertTrue(advanced, "Natural completion must advance without locking the audio sender");
        }
        finally
        {
            // A failing implementation can hold the player's monitor forever.
            // Daemon workers keep this regression from hanging the test JVM.
            if(advanced)
                player.destroy();
            sender.shutdownNow();
            worker.shutdownNow();
            manager.shutdown();
        }
    }

    @Test
    void eventsAlreadyOnTheSessionRunInline() throws Exception
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        try(ExecutorService worker = Executors.newSingleThreadExecutor())
        {
            GuildPlaybackSession session = new GuildPlaybackSession(manager.createPlayer(), QueueType.LINEAR, worker);
            session.run(() -> {
                AtomicBoolean ran = new AtomicBoolean();
                session.dispatchPlayerEvent(() -> ran.set(true));
                assertTrue(ran.get());
            });
        }
        finally
        {
            manager.shutdown();
        }
    }

    private static AudioTrack track(String id, CountDownLatch decoded)
    {
        return new BaseAudioTrack(new AudioTrackInfo(id, "test", 1L, id, false, null))
        {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception
            {
                executor.getAudioBuffer().consume(new ImmutableAudioFrame(
                        0, new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE},
                        100, StandardAudioDataFormats.DISCORD_OPUS));
                decoded.countDown();
                executor.waitOnEnd();
            }
        };
    }
}
