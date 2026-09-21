/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author John Grosh <john.a.grosh@gmail.com>
 */
/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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

import io.github.huaaudio.neomusicbot.audio.media.SensitiveLogSanitizer;
import io.github.huaaudio.neomusicbot.playlist.PlaylistLoader.Playlist;
import io.github.huaaudio.neomusicbot.settings.QueueType;
import io.github.huaaudio.neomusicbot.settings.RepeatMode;
import io.github.huaaudio.neomusicbot.settings.Settings;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild audio sender and lifecycle adapter. State-changing work is routed
 * through {@link GuildPlaybackSession}; only the 20 ms Opus frame pull stays on
 * JDA's audio thread.
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(AudioHandler.class);

    public record VoteUpdate(boolean added, Set<String> votes)
    {
    }

    public record PlaybackState(AudioTrack current, boolean paused, int volume,
                                long position, long duration, boolean seekable)
    {
    }

    private final PlayerManager manager;
    private final long guildId;
    private final GuildPlaybackSession session;
    private final AtomicLong defaultLoadToken = new AtomicLong();

    private AudioFrame lastFrame;
    // Accessed only on the session executor. Also identifies a natural-end
    // callback queued just before a stop cleared the player's active track.
    private AudioTrack sessionTrack;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.guildId = guild.getIdLong();
        QueueType queueType = manager.getBot().getSettingsManager().getSettings(guildId).getQueueType();
        this.session = new GuildPlaybackSession(player, queueType, manager.getBot().getThreadpool());
    }

    public GuildPlaybackSession getSession()
    {
        return session;
    }

    public void setQueueTypeAndSettings(QueueType type, Runnable settingsUpdate)
    {
        session.run(() -> {
            session.setQueueType(type);
            settingsUpdate.run();
        });
    }

    public PlaybackLoadToken reserveLoad()
    {
        return session.reserveLoad();
    }

    public boolean isLoadValid(PlaybackLoadToken token)
    {
        return session.isLoadValid(token);
    }

    public Integer addTrackAndCompleteLoad(PlaybackLoadToken token, QueuedTrack track, boolean front)
    {
        return session.completePendingLoadWithTracks(token, List.of(track), front);
    }

    public Integer addTracksAndCompleteLoad(PlaybackLoadToken token, List<QueuedTrack> tracks, boolean front)
    {
        return session.completePendingLoadWithTracks(token, List.copyOf(tracks), front);
    }

    public boolean addTrackForPendingLoad(PlaybackLoadToken token, QueuedTrack track)
    {
        return session.addTrackForPendingLoad(token, track);
    }

    public boolean finishPendingLoad(PlaybackLoadToken token)
    {
        GuildPlaybackSession.LoadCompletion completion = session.finishPendingLoad(token);
        if(completion.accepted() && completion.idle())
            requestIdleDisconnect();
        return completion.accepted();
    }

    public List<QueuedTrack> getQueueSnapshot()
    {
        return session.call(session::queueSnapshot);
    }

    public QueuedTrack removeQueuedTrackIfMatches(int index, QueuedTrack expected,
                                                  long requesterId, boolean allowAnyRequester)
    {
        return session.removeQueuedTrackIfMatches(index, expected, requesterId, allowAnyRequester);
    }

    public int removeAllQueued(long identifier)
    {
        return session.call(() -> {
            int removed = session.queue().removeAll(identifier);
            if(removed > 0)
                session.bumpGeneration();
            return removed;
        });
    }

    public int shuffleQueued(long identifier)
    {
        return session.call(() -> {
            int shuffled = session.queue().shuffle(identifier);
            if(shuffled > 1)
                session.bumpGeneration();
            return shuffled;
        });
    }

    public QueuedTrack moveQueuedTrackIfMatches(int from, int to,
                                                QueuedTrack expectedFrom, QueuedTrack expectedTo)
    {
        return session.moveQueuedTrackIfMatches(from, to, expectedFrom, expectedTo);
    }

    public boolean skipToIfMatches(AudioTrack expectedCurrent, List<QueuedTrack> expectedPrefix)
    {
        return session.skipToIfMatches(expectedCurrent, expectedPrefix);
    }

    public VoteUpdate addVoteIfCurrent(AudioTrack expectedCurrent, String userId)
    {
        GuildPlaybackSession.VoteUpdate update = session.voteIfCurrent(expectedCurrent, userId);
        return update == null ? null : new VoteUpdate(update.added(), update.votes());
    }

    public boolean skipCurrentIfMatches(AudioTrack expectedCurrent)
    {
        return session.skipIfCurrent(expectedCurrent);
    }

    public void stopAndClear()
    {
        stopAndClear(GuildPlaybackSession.Transition.TERMINAL_STOP);
    }

    public void disconnectAndClear()
    {
        stopAndClear(GuildPlaybackSession.Transition.DISCONNECT);
    }

    private void stopAndClear(GuildPlaybackSession.Transition transition)
    {
        session.run(() -> {
            defaultLoadToken.incrementAndGet();
            session.invalidatePendingLoads();
            session.setLoadingDefault(false);
            session.queue().clear();
            session.clearDefault();
            AudioTrack current = session.player().getPlayingTrack();
            session.markTransition(current, transition);
            if(current != null)
                session.player().stopTrack();
            else
                manager.getBot().getNowplayingHandler().onTrackUpdate(null);
            sessionTrack = null;
            session.player().setPaused(false);
        });
    }

    public PlaybackState getPlaybackState()
    {
        GuildPlaybackSession.PlayerSnapshot snapshot = session.playerSnapshot();
        return new PlaybackState(snapshot.current(), snapshot.paused(), snapshot.volume(),
                snapshot.position(), snapshot.duration(), snapshot.seekable());
    }

    public boolean setPausedIfCurrent(AudioTrack expectedCurrent, boolean paused)
    {
        return session.setPausedIfCurrent(expectedCurrent, paused);
    }

    public void updateSettings(Runnable settingsUpdate)
    {
        session.updateSettings(settingsUpdate);
    }

    public void updateDefaultPlaylistSetting(Runnable settingsUpdate)
    {
        session.run(() -> {
            defaultLoadToken.incrementAndGet();
            session.setLoadingDefault(false);
            session.clearDefault();
            settingsUpdate.run();
        });
    }

    public int setVolumeAndSettings(int volume, Runnable settingsUpdate)
    {
        return session.call(() -> {
            int old = session.player().getVolume();
            session.player().setVolume(Math.max(0, Math.min(150, volume)));
            settingsUpdate.run();
            session.bumpGeneration();
            return old;
        });
    }

    public boolean seekIfCurrent(AudioTrack expectedCurrent, long positionMs)
    {
        return session.seekIfCurrent(expectedCurrent, positionMs);
    }

    public void closeIfIdle(long expectedGeneration, Runnable closeAction)
    {
        session.closeIfIdle(expectedGeneration, closeAction);
    }

    public long getGeneration()
    {
        return session.generation();
    }

    public GuildPlaybackSession.VoiceReservation reserveVoiceChannel(long channelId)
    {
        return session.reserveVoiceChannel(channelId);
    }

    public void rollbackVoiceReservation(long channelId)
    {
        session.rollbackVoiceReservation(channelId);
    }

    public void observeVoiceChannel(long channelId)
    {
        session.observeVoiceChannel(channelId);
    }

    public void beginVoiceClose()
    {
        session.beginVoiceClose();
    }

    void closeVoiceConnectionAsync()
    {
        // Queue behind the current connection callback so AudioManager.close()
        // cannot re-enter the listener while its state transition is running.
        session.execute(() -> manager.getBot().closeAudioConnection(guildId));
    }

    public void destroy()
    {
        session.run(session.player()::destroy);
    }

    public boolean playFromDefault()
    {
        return session.call(this::playFromDefaultInternal);
    }

    private boolean playFromDefaultInternal()
    {
        AudioTrack cached = session.pollDefault();
        if(cached != null)
        {
            session.player().playTrack(cached);
            return true;
        }
        if(session.isLoadingDefault())
            return true;

        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings == null || settings.getDefaultPlaylist() == null)
            return false;

        Playlist playlist = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(playlist == null || playlist.getItems().isEmpty())
            return false;

        long token = defaultLoadToken.incrementAndGet();
        session.setLoadingDefault(true);
        playlist.loadTracks(manager, track -> session.execute(() -> {
            if(defaultLoadToken.get() != token || !session.isLoadingDefault())
                return;
            if(session.player().getPlayingTrack() == null)
                session.player().playTrack(track);
            else
                session.addDefault(track);
        }), () -> session.execute(() -> {
            if(defaultLoadToken.get() != token)
                return;
            // The final consumer is queued before this completion callback on
            // the same SerialExecutor, so current state is authoritative.
            if(session.finishDefaultLoadingAndIsIdle())
            {
                manager.getBot().getNowplayingHandler().onTrackUpdate(null);
                session.player().setPaused(false);
                requestIdleDisconnect();
            }
        }));
        return true;
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        session.dispatchPlayerEvent(() -> handleTrackEnd(player, track, endReason));
    }

    private void handleTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        GuildPlaybackSession.Transition fallback = switch(endReason)
        {
            case FINISHED -> GuildPlaybackSession.Transition.NATURAL_END;
            case REPLACED -> GuildPlaybackSession.Transition.REPLACE;
            case CLEANUP -> GuildPlaybackSession.Transition.DISCONNECT;
            default -> GuildPlaybackSession.Transition.SKIP;
        };
        GuildPlaybackSession.Transition transition = session.consumeTransition(track, fallback);
        session.finishTransition(track);

        if(sessionTrack != track)
            return;
        sessionTrack = null;

        // A new request may have started playback before an external end event
        // reached this session. Never let an old event replace that new track.
        if(player.getPlayingTrack() != null && player.getPlayingTrack() != track)
            return;

        if(transition == GuildPlaybackSession.Transition.TERMINAL_STOP
                || transition == GuildPlaybackSession.Transition.DISCONNECT)
        {
            manager.getBot().getNowplayingHandler().onTrackUpdate(null);
            player.setPaused(false);
            return;
        }
        if(transition == GuildPlaybackSession.Transition.REPLACE)
            return;

        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        if(transition == GuildPlaybackSession.Transition.NATURAL_END && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
                session.queue().add(clone);
            else
                session.queue().addAt(0, clone);
        }

        if(!session.queue().isEmpty())
        {
            QueuedTrack next = session.queue().pull();
            session.bumpGeneration();
            player.playTrack(next.getTrack());
            return;
        }

        if(!playFromDefaultInternal())
        {
            manager.getBot().getNowplayingHandler().onTrackUpdate(null);
            player.setPaused(false);
            requestIdleDisconnect();
        }
    }

    private void requestIdleDisconnect()
    {
        if(!manager.getBot().getConfig().getStay())
            manager.getBot().closeAudioConnection(guildId, session.generation());
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception)
    {
        LOG.error("Track {} failed to play (severity={}): {}",
                safeIdentifier(track.getIdentifier()), exception.severity,
                SensitiveLogSanitizer.sanitize(exception.getMessage()));
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track)
    {
        session.dispatchPlayerEvent(() -> {
            if(player.getPlayingTrack() != track)
                return;
            sessionTrack = track;
            session.clearVotes();
            session.bumpGeneration();
            manager.getBot().getNowplayingHandler().onTrackUpdate(track);
        });
    }

    @Override
    public boolean canProvide()
    {
        lastFrame = session.player().provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio()
    {
        AudioFrame frame = lastFrame;
        lastFrame = null;
        return frame == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(frame.getData());
    }

    @Override
    public boolean isOpus()
    {
        return true;
    }

    private static String safeIdentifier(String identifier)
    {
        if(identifier == null)
            return "<unknown>";
        int query = identifier.indexOf('?');
        String redacted = query < 0 ? identifier : identifier.substring(0, query) + "?<redacted>";
        redacted = redacted.replace('\r', ' ').replace('\n', ' ');
        return redacted.length() > 256 ? redacted.substring(0, 256) + "..." : redacted;
    }
}
