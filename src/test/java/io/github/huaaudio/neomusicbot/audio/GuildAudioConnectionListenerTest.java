package io.github.huaaudio.neomusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import org.junit.jupiter.api.Test;

class GuildAudioConnectionListenerTest
{
    @Test
    void authenticationAndEncryptionFailuresAreTerminal()
    {
        assertTrue(GuildAudioConnectionListener.isTerminal(
                ConnectionStatus.DISCONNECTED_AUTHENTICATION_FAILURE));
        assertTrue(GuildAudioConnectionListener.isTerminal(
                ConnectionStatus.ERROR_UNSUPPORTED_ENCRYPTION_MODES));
        assertFalse(GuildAudioConnectionListener.isTerminal(ConnectionStatus.CONNECTED));
        assertFalse(GuildAudioConnectionListener.isTerminal(ConnectionStatus.AUDIO_REGION_CHANGE));
    }
}
