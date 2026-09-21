package io.github.huaaudio.neomusicbot.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsFailureRecoveryTest
{
    @TempDir Path directory;

    @Test
    void corruptSettingsWithoutABackupArePreservedAndStopStartup() throws Exception
    {
        Path path = directory.resolve("settings.json");
        String original = "{truncated-important-settings";
        Files.writeString(path, original);
        assertThrows(IllegalStateException.class, () -> {
            try(SettingsManager ignored = new SettingsManager(path)) { }
        });
        assertEquals(original, Files.readString(path));
    }

    @Test
    void invalidChannelIdsDoNotSilentlyDisableRestrictions() throws Exception
    {
        Path path = directory.resolve("settings.json");
        String original = "{\"9\":{\"text_channel_id\":\"mistyped-id\"}}";
        Files.writeString(path, original);
        assertThrows(IllegalStateException.class, () -> {
            try(SettingsManager ignored = new SettingsManager(path)) { }
        });
        assertEquals(original, Files.readString(path));
    }

    @Test
    void failedWritesAreReportedAndCanBeFlushedAfterTheFailureIsRemoved() throws Exception
    {
        Path path = directory.resolve("settings.json");
        Path temporary = directory.resolve("settings.json.tmp");
        try(SettingsManager manager = new SettingsManager(path))
        {
            Files.createDirectory(temporary); // Deterministic I/O failure on both Windows and Linux.
            try
            {
                manager.getSettings(9L).setVolume(75);
                assertFalse(manager.flush(Duration.ofSeconds(5)), "An unwritten change is not durable");
            }
            finally
            {
                Files.delete(temporary);
            }
            assertTrue(manager.flush(Duration.ofSeconds(5)));
            assertEquals(75, new JSONObject(Files.readString(path)).getJSONObject("9").getInt("volume"));
        }
    }
}
