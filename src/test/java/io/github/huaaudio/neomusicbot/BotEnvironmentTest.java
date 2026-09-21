package io.github.huaaudio.neomusicbot;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotEnvironmentTest
{
    @Test
    void oldAndNewInstallationsCanEachConfigureTheSameSetting()
    {
        assertEquals("new-value", BotEnvironment.value("NEOMUSICBOT_YTDLP_PATH",
                Map.of("NEOMUSICBOT_YTDLP_PATH", " new-value ")));
        assertEquals("old-value", BotEnvironment.value("NEOMUSICBOT_YTDLP_PATH",
                Map.of("JMUSICBOT_YTDLP_PATH", "old-value")));
        assertEquals("old-value", BotEnvironment.value("NEOMUSICBOT_YTDLP_PATH",
                Map.of("NEOMUSICBOT_YTDLP_PATH", " ", "JMUSICBOT_YTDLP_PATH", "old-value")));
    }

    @Test
    void duplicateSecretAliasesFailWithoutDisclosingEitherValue()
    {
        var exception = assertThrows(IllegalArgumentException.class, () ->
                BotEnvironment.value("NEOMUSICBOT_DISCORD_TOKEN", Map.of(
                        "NEOMUSICBOT_DISCORD_TOKEN", "new-secret-value",
                        "JMUSICBOT_DISCORD_TOKEN", "old-secret-value")));
        assertFalse(exception.getMessage().contains("new-secret-value"));
        assertFalse(exception.getMessage().contains("old-secret-value"));
        assertNull(BotEnvironment.value("NEOMUSICBOT_DISCORD_TOKEN", Map.of()));
    }
}
