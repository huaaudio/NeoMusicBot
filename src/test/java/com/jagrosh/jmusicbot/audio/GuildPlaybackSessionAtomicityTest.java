package com.jagrosh.jmusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jagrosh.jmusicbot.settings.QueueType;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GuildPlaybackSessionAtomicityTest
{
    @Test
    void removeRequiresTheExpectedIdentityAndRequesterInsideOneSessionCall()
    {
        TestPlayer player = new TestPlayer();
        GuildPlaybackSession session = session(player);
        QueuedTrack first = queued("first");
        QueuedTrack replacement = queued("replacement");
        session.run(() -> session.queue().add(first));
        List<QueuedTrack> snapshot = session.queueSnapshot();

        session.run(() -> {
            session.queue().remove(0);
            session.queue().addAt(0, replacement);
        });

        assertNull(session.removeQueuedTrackIfMatches(0, snapshot.get(0), 0L, false));
        assertSame(replacement, session.queueSnapshot().get(0));
        assertNull(session.removeQueuedTrackIfMatches(0, replacement, 123L, false));
        assertSame(replacement, session.queueSnapshot().get(0));
        assertSame(replacement, session.removeQueuedTrackIfMatches(0, replacement, 0L, false));
        assertTrue(session.queueSnapshot().isEmpty());
    }

    @Test
    void moveRequiresBothSnapshotEndpointsToStillMatch()
    {
        GuildPlaybackSession session = session(new TestPlayer());
        QueuedTrack first = queued("first");
        QueuedTrack middle = queued("middle");
        QueuedTrack oldTarget = queued("old-target");
        QueuedTrack replacement = queued("replacement");
        session.run(() -> {
            session.queue().add(first);
            session.queue().add(middle);
            session.queue().add(oldTarget);
        });

        session.run(() -> {
            session.queue().remove(2);
            session.queue().addAt(2, replacement);
        });

        assertNull(session.moveQueuedTrackIfMatches(0, 2, first, oldTarget));
        assertIterableEquals(List.of(first, middle, replacement), session.queueSnapshot());
        assertSame(first, session.moveQueuedTrackIfMatches(0, 2, first, replacement));
        assertIterableEquals(List.of(middle, replacement, first), session.queueSnapshot());
    }

    @Test
    void currentTrackMutationsRejectAReplacedTrack()
    {
        TestPlayer player = new TestPlayer();
        GuildPlaybackSession session = session(player);
        TestTrack expected = track("expected");
        TestTrack replacement = track("replacement");
        player.playTrack(expected);

        assertFalse(session.seekIfCurrent(replacement, 400L));
        assertEquals(0L, expected.getPosition());
        assertNull(session.voteIfCurrent(replacement, "user"));
        assertFalse(session.skipIfCurrent(replacement));
        assertSame(expected, player.getPlayingTrack());
        assertEquals(0, player.stopCalls);

        assertTrue(session.seekIfCurrent(expected, 400L));
        assertEquals(400L, expected.getPosition());
        GuildPlaybackSession.VoteUpdate vote = session.voteIfCurrent(expected, "user");
        assertTrue(vote.added());
        assertTrue(vote.votes().contains("user"));

        player.playTrack(replacement);
        assertFalse(session.skipIfCurrent(expected));
        assertSame(replacement, player.getPlayingTrack());
        assertEquals(0, player.stopCalls);
        assertTrue(session.skipIfCurrent(replacement));
        assertNull(player.getPlayingTrack());
        assertEquals(1, player.stopCalls);
    }

    @Test
    void skipToRequiresBothCurrentTrackAndQueueTargetIdentity()
    {
        TestPlayer player = new TestPlayer();
        GuildPlaybackSession session = session(player);
        TestTrack current = track("current");
        TestTrack replacementCurrent = track("replacement-current");
        QueuedTrack first = queued("first");
        QueuedTrack target = queued("target");
        session.run(() -> {
            session.queue().add(first);
            session.queue().add(target);
        });
        player.playTrack(current);

        assertFalse(session.skipToIfMatches(current, List.of(first, queued("stale-target"))));
        assertIterableEquals(List.of(first, target), session.queueSnapshot());
        assertEquals(0, player.stopCalls);

        QueuedTrack replacementFirst = queued("replacement-first");
        session.run(() -> {
            session.queue().remove(0);
            session.queue().addAt(0, replacementFirst);
        });
        assertFalse(session.skipToIfMatches(current, List.of(first, target)));
        assertIterableEquals(List.of(replacementFirst, target), session.queueSnapshot());
        assertEquals(0, player.stopCalls);
        session.run(() -> {
            session.queue().remove(0);
            session.queue().addAt(0, first);
        });

        player.playTrack(replacementCurrent);
        assertFalse(session.skipToIfMatches(current, List.of(first, target)));
        assertIterableEquals(List.of(first, target), session.queueSnapshot());
        assertEquals(0, player.stopCalls);

        player.playTrack(current);
        assertTrue(session.skipToIfMatches(current, List.of(first, target)));
        assertIterableEquals(List.of(target), session.queueSnapshot());
        assertNull(player.getPlayingTrack());
        assertEquals(1, player.stopCalls);
    }

    @Test
    void defaultCompletionUsesCurrentPlaybackStateInsteadOfHistoricalResults()
    {
        GuildPlaybackSession empty = session(new TestPlayer());
        empty.run(() -> empty.setLoadingDefault(true));
        assertTrue(empty.call(empty::finishDefaultLoadingAndIsIdle));

        GuildPlaybackSession queued = session(new TestPlayer());
        queued.run(() -> {
            queued.queue().add(queued("queued"));
            queued.setLoadingDefault(true);
        });
        assertFalse(queued.call(queued::finishDefaultLoadingAndIsIdle));

        TestPlayer playingPlayer = new TestPlayer();
        GuildPlaybackSession playing = session(playingPlayer);
        playingPlayer.playTrack(track("playing"));
        playing.run(() -> playing.setLoadingDefault(true));
        assertFalse(playing.call(playing::finishDefaultLoadingAndIsIdle));

        GuildPlaybackSession defaultQueued = session(new TestPlayer());
        defaultQueued.run(() -> {
            defaultQueued.addDefault(track("default"));
            defaultQueued.setLoadingDefault(true);
        });
        assertFalse(defaultQueued.call(defaultQueued::finishDefaultLoadingAndIsIdle));
    }

    @Test
    void idleCloseEnqueuesWithoutWaitingForItsOwnBackingExecutor()
    {
        ConcurrentLinkedQueue<Runnable> backing = new ConcurrentLinkedQueue<>();
        GuildPlaybackSession session = new GuildPlaybackSession(
                new TestPlayer(), QueueType.LINEAR, backing::add);
        AtomicBoolean closed = new AtomicBoolean();

        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> session.closeIfIdle(session.generation(), () -> closed.set(true)));
        assertFalse(closed.get());
        assertEquals(1, backing.size());

        backing.remove().run();
        assertTrue(closed.get());

        GuildPlaybackSession stale = session(new TestPlayer());
        long oldGeneration = stale.generation();
        stale.run(() -> stale.setLoadingDefault(true));
        stale.call(stale::finishDefaultLoadingAndIsIdle);
        AtomicBoolean staleClosed = new AtomicBoolean();
        stale.closeIfIdle(oldGeneration, () -> staleClosed.set(true));
        assertFalse(staleClosed.get());
    }

    @Test
    void pendingLoadPreventsIdleCloseUntilItCompletes()
    {
        GuildPlaybackSession session = session(new TestPlayer());
        PlaybackLoadToken token = session.reserveLoad();
        AtomicBoolean closed = new AtomicBoolean();

        session.closeIfIdle(session.generation(), () -> closed.set(true));
        assertFalse(closed.get());

        GuildPlaybackSession.LoadCompletion completion = session.finishPendingLoad(token);
        assertTrue(completion.accepted());
        assertTrue(completion.idle());
        session.closeIfIdle(session.generation(), () -> closed.set(true));
        assertTrue(closed.get());
    }

    @Test
    void invalidatedLoadCannotReviveStoppedPlayback()
    {
        TestPlayer player = new TestPlayer();
        GuildPlaybackSession session = session(player);
        PlaybackLoadToken token = session.reserveLoad();

        session.run(session::invalidatePendingLoads);

        assertNull(session.completePendingLoadWithTracks(token, List.of(queued("late")), false));
        assertNull(player.getPlayingTrack());
        assertTrue(session.queueSnapshot().isEmpty());
        assertFalse(session.finishPendingLoad(token).accepted());
    }

    @Test
    void independentConcurrentLoadsRemainValidUntilEachCompletes()
    {
        TestPlayer player = new TestPlayer();
        GuildPlaybackSession session = session(player);
        PlaybackLoadToken first = session.reserveLoad();
        PlaybackLoadToken second = session.reserveLoad();

        assertEquals(-1, session.completePendingLoadWithTracks(first, List.of(queued("first")), false));
        assertEquals(0, session.completePendingLoadWithTracks(second, List.of(queued("second")), false));
        assertEquals("first", player.getPlayingTrack().getIdentifier());
        assertEquals("second", session.queueSnapshot().get(0).getTrack().getIdentifier());
    }

    @Test
    void voiceReservationRejectsConcurrentDifferentChannelsAndRecoversAfterClose()
    {
        GuildPlaybackSession session = session(new TestPlayer());

        assertEquals(GuildPlaybackSession.VoiceReservationResult.OPEN,
                session.reserveVoiceChannel(100L).result());
        assertEquals(GuildPlaybackSession.VoiceReservationResult.ALREADY_RESERVED,
                session.reserveVoiceChannel(100L).result());
        GuildPlaybackSession.VoiceReservation conflict = session.reserveVoiceChannel(200L);
        assertEquals(GuildPlaybackSession.VoiceReservationResult.CONFLICT, conflict.result());
        assertEquals(100L, conflict.channelId());

        session.beginVoiceClose();
        assertEquals(GuildPlaybackSession.VoiceReservationResult.CLOSING,
                session.reserveVoiceChannel(200L).result());
        session.observeVoiceChannel(0L);
        assertEquals(GuildPlaybackSession.VoiceReservationResult.OPEN,
                session.reserveVoiceChannel(200L).result());
    }

    @Test
    void kickOrFailedOpenClearsStaleTargetAndAllowsExplicitReconnect()
    {
        GuildPlaybackSession session = session(new TestPlayer());
        session.reserveVoiceChannel(100L);
        session.setConnectionState(GuildPlaybackSession.ConnectionState.DISCONNECTED);

        assertEquals(0L, session.targetVoiceChannelId());
        assertEquals(GuildPlaybackSession.VoiceReservationResult.OPEN,
                session.reserveVoiceChannel(200L).result());
        session.rollbackVoiceReservation(200L);
        assertEquals(GuildPlaybackSession.ConnectionState.DISCONNECTED, session.connectionState());
    }

    @Test
    void connectionStatusNoiseDoesNotConsumeTheOnlyIdleClose()
    {
        GuildPlaybackSession session = session(new TestPlayer());
        session.reserveVoiceChannel(100L);
        long idleGeneration = session.generation();
        AtomicBoolean closed = new AtomicBoolean();

        session.setConnectionState(GuildPlaybackSession.ConnectionState.CONNECTED);
        session.setConnectionState(GuildPlaybackSession.ConnectionState.RECONNECTING);
        session.closeIfIdle(idleGeneration, () -> closed.set(true));

        assertTrue(closed.get());
    }

    @Test
    void oldIdleCloseCannotClosePlaybackStartedByACompletedLoad()
    {
        GuildPlaybackSession session = session(new TestPlayer());
        long oldGeneration = session.generation();
        PlaybackLoadToken token = session.reserveLoad();
        assertEquals(-1, session.completePendingLoadWithTracks(token, List.of(queued("new")), false));
        AtomicBoolean closed = new AtomicBoolean();

        session.closeIfIdle(oldGeneration, () -> closed.set(true));

        assertFalse(closed.get());
    }

    private static GuildPlaybackSession session(TestPlayer player)
    {
        return new GuildPlaybackSession(player, QueueType.LINEAR, Runnable::run);
    }

    private static QueuedTrack queued(String id)
    {
        return new QueuedTrack(track(id), RequestMetadata.EMPTY);
    }

    private static TestTrack track(String id)
    {
        return new TestTrack(id);
    }

    private static final class TestTrack extends BaseAudioTrack
    {
        private long position;

        private TestTrack(String id)
        {
            super(new AudioTrackInfo(id, "test", 10_000L, id, false,
                    "https://example.invalid/" + id));
        }

        @Override
        public void process(LocalAudioTrackExecutor executor)
        {
        }

        @Override
        public long getPosition()
        {
            return position;
        }

        @Override
        public void setPosition(long position)
        {
            this.position = position;
        }
    }

    private static final class TestPlayer implements AudioPlayer
    {
        private AudioTrack current;
        private int volume = 100;
        private boolean paused;
        private int stopCalls;

        @Override
        public AudioTrack getPlayingTrack()
        {
            return current;
        }

        @Override
        public void playTrack(AudioTrack track)
        {
            current = track;
        }

        @Override
        public boolean startTrack(AudioTrack track, boolean noInterrupt)
        {
            if (noInterrupt && current != null)
                return false;
            current = track;
            return true;
        }

        @Override
        public void stopTrack()
        {
            stopCalls++;
            current = null;
        }

        @Override
        public int getVolume()
        {
            return volume;
        }

        @Override
        public void setVolume(int volume)
        {
            this.volume = volume;
        }

        @Override
        public void setFilterFactory(PcmFilterFactory factory)
        {
        }

        @Override
        public void setFrameBufferDuration(Integer duration)
        {
        }

        @Override
        public boolean isPaused()
        {
            return paused;
        }

        @Override
        public void setPaused(boolean paused)
        {
            this.paused = paused;
        }

        @Override
        public void destroy()
        {
            current = null;
        }

        @Override
        public void addListener(AudioEventListener listener)
        {
        }

        @Override
        public void removeListener(AudioEventListener listener)
        {
        }

        @Override
        public void checkCleanup(long threshold)
        {
        }

        @Override
        public AudioFrame provide()
        {
            return null;
        }

        @Override
        public AudioFrame provide(long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException
        {
            return null;
        }

        @Override
        public boolean provide(MutableAudioFrame targetFrame)
        {
            return false;
        }

        @Override
        public boolean provide(MutableAudioFrame targetFrame, long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException
        {
            return false;
        }
    }
}
