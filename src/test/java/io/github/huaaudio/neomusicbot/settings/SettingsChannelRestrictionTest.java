package io.github.huaaudio.neomusicbot.settings;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsChannelRestrictionTest
{
    @TempDir Path directory;

    @Test
    void aMissingCachedChannelDoesNotRemovePersistedRestrictions() throws Exception
    {
        Path path = directory.resolve("settings.json");
        Files.writeString(path, "{\"9\":{\"text_channel_id\":\"123\",\"voice_channel_id\":\"456\"}}");
        Guild unavailable = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(),
                new Class<?>[]{Guild.class}, (proxy, method, args) -> null);
        try(SettingsManager manager = new SettingsManager(path))
        {
            Settings settings = manager.getSettings(9L);
            assertNull(settings.getTextChannel(unavailable));
            assertNull(settings.getVoiceChannel(unavailable));
            assertTrue(settings.hasTextChannelRestriction());
            assertTrue(settings.hasVoiceChannelRestriction());
            assertFalse(settings.allowsTextChannel(789L));
            assertFalse(settings.allowsVoiceChannel(789L));
            assertTrue(settings.allowsTextChannel(123L));
            assertTrue(settings.allowsVoiceChannel(456L));
        }
    }

    @Test
    void explicitlyClearingRestrictionsAllowsOtherChannels() throws Exception
    {
        Path path = directory.resolve("settings.json");
        Files.writeString(path, "{\"9\":{\"text_channel_id\":\"123\",\"voice_channel_id\":\"456\"}}");
        try(SettingsManager manager = new SettingsManager(path))
        {
            Settings settings = manager.getSettings(9L);
            settings.setTextChannel(null);
            settings.setVoiceChannel(null);
            assertFalse(settings.hasTextChannelRestriction());
            assertFalse(settings.hasVoiceChannelRestriction());
            assertTrue(settings.allowsTextChannel(789L));
            assertTrue(settings.allowsVoiceChannel(789L));
        }
    }
}
