package io.github.huaaudio.neomusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;

class PlayerManagerSecurityTest
{
    @Test
    void guildHandlerInitializationIsSerialized() throws Exception
    {
        Method method = PlayerManager.class.getDeclaredMethod("setUpHandler", Guild.class);
        assertTrue(Modifier.isSynchronized(method.getModifiers()),
                "setUpHandler must keep check/create/install inside one critical section");
    }

    @Test
    void compiledManagerHasNoGenericLocalSourceRegistration() throws Exception
    {
        try (InputStream input = PlayerManager.class.getResourceAsStream("PlayerManager.class"))
        {
            String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("AudioSourceManagers"));
            assertFalse(constants.contains("LocalAudioSourceManager"));
            assertFalse(constants.contains("registerLocalSource"));
        }
    }
}
