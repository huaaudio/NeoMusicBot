package io.github.huaaudio.neomusicbot.audio;

import java.util.EnumSet;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.UserSnowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes JDA voice lifecycle callbacks through the guild playback executor. */
public final class GuildAudioConnectionListener implements ConnectionListener
{
    private static final Logger LOG = LoggerFactory.getLogger(GuildAudioConnectionListener.class);

    private final AudioHandler handler;
    private final long guildId;

    public GuildAudioConnectionListener(AudioHandler handler, long guildId)
    {
        this.handler = handler;
        this.guildId = guildId;
    }

    @Override
    public void onStatusChange(ConnectionStatus status)
    {
        handler.getSession().execute(() -> apply(status));
    }

    private void apply(ConnectionStatus status)
    {
        GuildPlaybackSession session = handler.getSession();
        if(isTerminal(status))
        {
            // Some terminal statuses report shouldReconnect=true. Mark the
            // explicit close before touching AudioManager and disable its
            // reconnect loop in the queued close action.
            session.beginVoiceClose();
            LOG.warn("Voice connection for guild {} reached terminal status {}", guildId, status);
            handler.disconnectAndClear();
            handler.closeVoiceConnectionAsync();
            return;
        }

        if(session.connectionState() == GuildPlaybackSession.ConnectionState.DISCONNECTING)
        {
            if(status == ConnectionStatus.NOT_CONNECTED || status == ConnectionStatus.SHUTTING_DOWN)
                session.setConnectionState(GuildPlaybackSession.ConnectionState.DISCONNECTED);
            else
                LOG.debug("Ignoring late voice status {} while guild {} is closing", status, guildId);
            return;
        }

        GuildPlaybackSession.ConnectionState state;
        if(status == ConnectionStatus.CONNECTED)
            state = GuildPlaybackSession.ConnectionState.CONNECTED;
        else if(status.shouldReconnect() || status == ConnectionStatus.AUDIO_REGION_CHANGE)
            state = GuildPlaybackSession.ConnectionState.RECONNECTING;
        else if(status.name().startsWith("CONNECTING_"))
            state = session.connectionState() == GuildPlaybackSession.ConnectionState.DISCONNECTED
                    ? GuildPlaybackSession.ConnectionState.CONNECTING
                    : GuildPlaybackSession.ConnectionState.RECONNECTING;
        else
            state = GuildPlaybackSession.ConnectionState.DISCONNECTED;

        session.setConnectionState(state);
        LOG.debug("Voice connection for guild {} changed to {} ({})", guildId, state, status);
    }

    static boolean isTerminal(ConnectionStatus status)
    {
        return switch(status)
        {
            case DISCONNECTED_LOST_PERMISSION,
                 DISCONNECTED_CHANNEL_DELETED,
                 DISCONNECTED_REMOVED_FROM_GUILD,
                 DISCONNECTED_KICKED_FROM_CHANNEL,
                 DISCONNECTED_REMOVED_DURING_RECONNECT,
                 DISCONNECTED_AUTHENTICATION_FAILURE,
                 ERROR_UNSUPPORTED_ENCRYPTION_MODES -> true;
            default -> false;
        };
    }

    @Override
    public void onUserSpeakingModeUpdate(UserSnowflake user, EnumSet<SpeakingMode> modes)
    {
        // Playback state is independent from other users' speaking flags.
    }
}
