/*
 * Copyright 2026 JMusicBot contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Mutable playback state for one guild. All state except the audio frame pull is
 * confined to {@link #executor}; Lavaplayer's audio thread reads the player
 * directly so the 20 ms send path never waits on command or resolver work.
 */
public final class GuildPlaybackSession
{
    public enum ConnectionState
    {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        DISCONNECTING
    }

    public enum VoiceReservationResult
    {
        OPEN,
        ALREADY_RESERVED,
        CONFLICT,
        CLOSING
    }

    public enum Transition
    {
        SKIP,
        TERMINAL_STOP,
        DISCONNECT,
        REPLACE,
        NATURAL_END
    }

    public record VoiceReservation(VoiceReservationResult result, long channelId)
    {
    }

    record VoteUpdate(boolean added, Set<String> votes)
    {
    }

    record PlayerSnapshot(AudioTrack current, boolean paused, int volume,
                          long position, long duration, boolean seekable)
    {
    }

    record LoadCompletion(boolean accepted, boolean idle)
    {
    }

    private final AudioPlayer player;
    private final SerialExecutor executor;
    private final ArrayDeque<AudioTrack> defaultQueue = new ArrayDeque<>();
    private final Set<String> votes = new LinkedHashSet<>();
    private final Set<PlaybackLoadToken> pendingLoads = new LinkedHashSet<>();
    private final Map<AudioTrack, Transition> transitions = new IdentityHashMap<>();

    private volatile long generation;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private AbstractQueue<QueuedTrack> queue;
    private boolean loadingDefault;
    private long loadEpoch;
    private long nextLoadSequence;
    private long targetVoiceChannelId;

    public GuildPlaybackSession(AudioPlayer player, QueueType queueType, Executor backingExecutor)
    {
        this.player = Objects.requireNonNull(player, "player");
        this.executor = new SerialExecutor(Objects.requireNonNull(backingExecutor, "backingExecutor"));
        this.queue = Objects.requireNonNull(queueType, "queueType").createInstance(null);
    }

    public AudioPlayer player()
    {
        return player;
    }

    public void execute(Runnable task)
    {
        executor.execute(task);
    }

    void dispatchPlayerEvent(Runnable task)
    {
        // Lavaplayer dispatches callbacks while holding its track-switch lock.
        // Waiting for another thread here deadlocks as soon as that thread
        // starts/stops a track. Calls originating on this session may run inline.
        if(executor.isCurrentThread())
            task.run();
        else
            execute(task);
    }

    public <T> T call(Callable<T> task)
    {
        Objects.requireNonNull(task, "task");
        if(executor.isCurrentThread())
            return invoke(task);

        CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> {
            try
            {
                result.complete(task.call());
            }
            catch(Throwable ex)
            {
                result.completeExceptionally(ex);
            }
        });
        try
        {
            return result.join();
        }
        catch(CompletionException ex)
        {
            if(ex.getCause() instanceof RuntimeException runtime)
                throw runtime;
            throw ex;
        }
    }

    public void run(Runnable task)
    {
        call(() -> {
            task.run();
            return null;
        });
    }

    void closeIfIdle(long expectedGeneration, Runnable closeAction)
    {
        Objects.requireNonNull(closeAction, "closeAction");
        execute(() -> {
            if(generation == expectedGeneration && isIdle())
                closeAction.run();
        });
    }

    long bumpGeneration()
    {
        return ++generation;
    }

    public long generation()
    {
        return generation;
    }

    void setQueueType(QueueType type)
    {
        queue = Objects.requireNonNull(type, "type").createInstance(queue);
        bumpGeneration();
    }

    AbstractQueue<QueuedTrack> queue()
    {
        return queue;
    }

    List<QueuedTrack> queueSnapshot()
    {
        return List.copyOf(queue.getList());
    }

    QueuedTrack removeQueuedTrackIfMatches(int index, QueuedTrack expected,
                                           long requesterId, boolean allowAnyRequester)
    {
        return call(() -> {
            if(expected == null || index < 0 || index >= queue.size())
                return null;
            QueuedTrack actual = queue.get(index);
            if(actual != expected || (!allowAnyRequester && actual.getIdentifier() != requesterId))
                return null;
            QueuedTrack removed = queue.remove(index);
            bumpGeneration();
            return removed;
        });
    }

    QueuedTrack moveQueuedTrackIfMatches(int from, int to,
                                         QueuedTrack expectedFrom, QueuedTrack expectedTo)
    {
        return call(() -> {
            int size = queue.size();
            if(expectedFrom == null || expectedTo == null || from < 0 || to < 0
                    || from >= size || to >= size || from == to)
                return null;
            if(queue.get(from) != expectedFrom || queue.get(to) != expectedTo)
                return null;
            QueuedTrack moved = queue.moveItem(from, to);
            bumpGeneration();
            return moved;
        });
    }

    PlayerSnapshot playerSnapshot()
    {
        return call(() -> {
            AudioTrack current = player.getPlayingTrack();
            return new PlayerSnapshot(current, player.isPaused(), player.getVolume(),
                    current == null ? 0L : current.getPosition(),
                    current == null ? 0L : current.getDuration(),
                    current != null && current.isSeekable());
        });
    }

    boolean setPausedIfCurrent(AudioTrack expected, boolean paused)
    {
        return call(() -> {
            if(expected == null || player.getPlayingTrack() != expected)
                return false;
            player.setPaused(paused);
            bumpGeneration();
            return true;
        });
    }

    void updateSettings(Runnable update)
    {
        run(Objects.requireNonNull(update, "update"));
    }

    boolean seekIfCurrent(AudioTrack expected, long positionMs)
    {
        return call(() -> {
            AudioTrack current = player.getPlayingTrack();
            if(expected == null || current != expected || !current.isSeekable() || positionMs < 0)
                return false;
            long duration = current.getDuration();
            if(duration > 0 && positionMs > duration)
                return false;
            current.setPosition(positionMs);
            bumpGeneration();
            return true;
        });
    }

    boolean skipIfCurrent(AudioTrack expected)
    {
        return call(() -> {
            AudioTrack current = player.getPlayingTrack();
            if(expected == null || current != expected)
                return false;
            markTransition(current, Transition.SKIP);
            player.stopTrack();
            return true;
        });
    }

    boolean skipToIfMatches(AudioTrack expectedCurrent, List<QueuedTrack> expectedPrefix)
    {
        return call(() -> {
            AudioTrack current = player.getPlayingTrack();
            if(expectedCurrent == null || current != expectedCurrent || expectedPrefix == null
                    || expectedPrefix.isEmpty() || expectedPrefix.size() > queue.size())
                return false;
            for(int index = 0; index < expectedPrefix.size(); index++)
            {
                if(queue.get(index) != expectedPrefix.get(index))
                    return false;
            }
            int targetIndex = expectedPrefix.size() - 1;
            if(targetIndex > 0)
            {
                queue.skip(targetIndex);
                bumpGeneration();
            }
            markTransition(current, Transition.SKIP);
            player.stopTrack();
            return true;
        });
    }

    VoteUpdate voteIfCurrent(AudioTrack expected, String userId)
    {
        return call(() -> {
            if(expected == null || player.getPlayingTrack() != expected)
                return null;
            return new VoteUpdate(addVote(userId), voteSnapshot());
        });
    }

    PlaybackLoadToken reserveLoad()
    {
        return call(() -> {
            PlaybackLoadToken token = new PlaybackLoadToken(loadEpoch, ++nextLoadSequence);
            pendingLoads.add(token);
            bumpGeneration();
            return token;
        });
    }

    boolean isLoadValid(PlaybackLoadToken token)
    {
        return token != null && call(() -> pendingLoads.contains(token) && token.epoch() == loadEpoch);
    }

    Integer completePendingLoadWithTracks(PlaybackLoadToken token, List<QueuedTrack> tracks, boolean front)
    {
        Objects.requireNonNull(tracks, "tracks");
        return call(() -> {
            if(!consumePendingLoad(token))
                return null;
            int firstPosition = -2;
            int frontIndex = 0;
            for(QueuedTrack track : tracks)
            {
                int position = addTrackInternal(Objects.requireNonNull(track, "track"), front, frontIndex);
                if(firstPosition == -2)
                    firstPosition = position;
                if(front && position >= 0)
                    frontIndex++;
            }
            return firstPosition;
        });
    }

    boolean addTrackForPendingLoad(PlaybackLoadToken token, QueuedTrack track)
    {
        Objects.requireNonNull(track, "track");
        return call(() -> {
            if(!pendingLoads.contains(token) || token.epoch() != loadEpoch)
                return false;
            addTrackInternal(track, false, 0);
            return true;
        });
    }

    LoadCompletion finishPendingLoad(PlaybackLoadToken token)
    {
        return call(() -> {
            if(!consumePendingLoad(token))
                return new LoadCompletion(false, false);
            return new LoadCompletion(true, isIdle());
        });
    }

    void invalidatePendingLoads()
    {
        loadEpoch++;
        pendingLoads.clear();
        bumpGeneration();
    }

    private boolean consumePendingLoad(PlaybackLoadToken token)
    {
        if(token == null || token.epoch() != loadEpoch || !pendingLoads.remove(token))
            return false;
        bumpGeneration();
        return true;
    }

    private int addTrackInternal(QueuedTrack track, boolean front, int frontIndex)
    {
        bumpGeneration();
        if(player.getPlayingTrack() == null)
        {
            player.playTrack(track.getTrack());
            return -1;
        }
        if(front)
        {
            queue.addAt(frontIndex, track);
            return frontIndex;
        }
        return queue.add(track);
    }

    void addDefault(AudioTrack track)
    {
        defaultQueue.add(Objects.requireNonNull(track, "track"));
        bumpGeneration();
    }

    AudioTrack pollDefault()
    {
        AudioTrack track = defaultQueue.poll();
        if(track != null)
            bumpGeneration();
        return track;
    }

    void clearDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            defaultQueue.clear();
            bumpGeneration();
        }
    }

    boolean hasDefaultTracks()
    {
        return !defaultQueue.isEmpty();
    }

    boolean isLoadingDefault()
    {
        return loadingDefault;
    }

    void setLoadingDefault(boolean loadingDefault)
    {
        this.loadingDefault = loadingDefault;
        bumpGeneration();
    }

    boolean finishDefaultLoadingAndIsIdle()
    {
        setLoadingDefault(false);
        return isIdle();
    }

    boolean addVote(String userId)
    {
        return votes.add(userId);
    }

    Set<String> voteSnapshot()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(votes));
    }

    void clearVotes()
    {
        votes.clear();
    }

    void markTransition(AudioTrack track, Transition transition)
    {
        if(track != null)
            transitions.put(track, transition);
        bumpGeneration();
    }

    Transition consumeTransition(AudioTrack track, Transition fallback)
    {
        return transitions.getOrDefault(track, fallback);
    }

    void finishTransition(AudioTrack track)
    {
        transitions.remove(track);
    }

    VoiceReservation reserveVoiceChannel(long requestedChannelId)
    {
        if(requestedChannelId <= 0)
            throw new IllegalArgumentException("requestedChannelId must be a Discord snowflake");
        return call(() -> {
            if(connectionState == ConnectionState.DISCONNECTING)
                return new VoiceReservation(VoiceReservationResult.CLOSING, 0L);
            if(connectionState != ConnectionState.DISCONNECTED)
            {
                if(targetVoiceChannelId == requestedChannelId)
                    return new VoiceReservation(VoiceReservationResult.ALREADY_RESERVED, requestedChannelId);
                return new VoiceReservation(VoiceReservationResult.CONFLICT, targetVoiceChannelId);
            }

            targetVoiceChannelId = requestedChannelId;
            connectionState = ConnectionState.CONNECTING;
            bumpGeneration();
            return new VoiceReservation(VoiceReservationResult.OPEN, requestedChannelId);
        });
    }

    void rollbackVoiceReservation(long requestedChannelId)
    {
        run(() -> {
            if(connectionState == ConnectionState.CONNECTING && targetVoiceChannelId == requestedChannelId)
            {
                targetVoiceChannelId = 0L;
                connectionState = ConnectionState.DISCONNECTED;
                bumpGeneration();
            }
        });
    }

    void beginVoiceClose()
    {
        run(() -> {
            if(connectionState != ConnectionState.DISCONNECTING)
            {
                connectionState = ConnectionState.DISCONNECTING;
                targetVoiceChannelId = 0L;
                bumpGeneration();
            }
        });
    }

    void observeVoiceChannel(long channelId)
    {
        run(() -> {
            if(connectionState == ConnectionState.DISCONNECTING && channelId > 0)
                return;
            if(connectionState == ConnectionState.DISCONNECTING)
            {
                connectionState = ConnectionState.DISCONNECTED;
                return;
            }
            if(channelId > 0)
            {
                targetVoiceChannelId = channelId;
                connectionState = ConnectionState.CONNECTED;
            }
            else
            {
                targetVoiceChannelId = 0L;
                connectionState = ConnectionState.DISCONNECTED;
            }
        });
    }

    public ConnectionState connectionState()
    {
        return connectionState;
    }

    long targetVoiceChannelId()
    {
        return call(() -> targetVoiceChannelId);
    }

    void setConnectionState(ConnectionState state)
    {
        connectionState = Objects.requireNonNull(state, "state");
        if(state == ConnectionState.DISCONNECTED)
            targetVoiceChannelId = 0L;
        // Connection-status noise is deliberately not part of the playback
        // generation used by idle-close guards. A reconnect callback must not
        // swallow the only close scheduled for a genuinely idle session.
    }

    boolean isIdle()
    {
        return player.getPlayingTrack() == null
                && queue.isEmpty()
                && defaultQueue.isEmpty()
                && pendingLoads.isEmpty()
                && !loadingDefault;
    }

    private static <T> T invoke(Callable<T> task)
    {
        try
        {
            return task.call();
        }
        catch(RuntimeException ex)
        {
            throw ex;
        }
        catch(Exception ex)
        {
            throw new CompletionException(ex);
        }
    }
}
