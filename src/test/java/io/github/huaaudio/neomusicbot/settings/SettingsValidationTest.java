package io.github.huaaudio.neomusicbot.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SettingsValidationTest
{
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"\"volume\":-1", "\"volume\":151", "\"volume\":4294967296",
            "\"volume\":0.5", "\"volume\":\"mistyped\"", "\"volume\":null",
            "\"skip_ratio\":-0.1", "\"skip_ratio\":1.1", "\"skip_ratio\":\"NaN\"",
            "\"skip_ratio\":1e999"})
    void invalidPersistedNumbersArePreservedAndStopStartup(String field) throws Exception
    {
        Path path = directory.resolve("settings.json");
        String original = "{\"9\":{" + field + "}}";
        Files.writeString(path, original);
        assertThrows(IllegalStateException.class, () -> {
            try(SettingsManager ignored = new SettingsManager(path)) { }
        });
        assertEquals(original, Files.readString(path));
    }

    @Test
    void invalidPrimaryNumbersRecoverFromTheValidBackup() throws Exception
    {
        Path path = directory.resolve("settings.json");
        Files.writeString(path, "{\"9\":{\"skip_ratio\":2}}");
        Files.writeString(path.resolveSibling("settings.json.bak"), "{\"9\":{\"skip_ratio\":0.75,\"volume\":77}}");
        try(SettingsManager manager = new SettingsManager(path))
        {
            assertEquals(0.75, manager.getSettings(9).getSkipRatio());
            assertEquals(77, manager.getSettings(9).getVolume());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1})
    void invalidUpdatesCannotPoisonTheSettingsWriter(double ratio) throws Exception
    {
        try(SettingsManager manager = new SettingsManager(directory.resolve("settings.json")))
        {
            Settings settings = manager.getSettings(9);
            settings.setSkipRatio(0.75);
            assertTrue(manager.flush(Duration.ofSeconds(3)));
            try
            {
                assertThrows(IllegalArgumentException.class, () -> settings.setSkipRatio(ratio));
                assertEquals(0.75, settings.getSkipRatio());
                assertTrue(manager.flush(Duration.ofSeconds(3)));
            }
            finally { settings.setSkipRatio(0.75); }
        }
    }

    @Test
    void invalidVolumeUpdatesDoNotChangeThePersistedSetting() throws Exception
    {
        Path path = directory.resolve("settings.json");
        try(SettingsManager manager = new SettingsManager(path))
        {
            Settings settings = manager.getSettings(9);
            settings.setVolume(77);
            assertThrows(IllegalArgumentException.class, () -> settings.setVolume(-1));
            assertThrows(IllegalArgumentException.class, () -> settings.setVolume(151));
            assertEquals(77, settings.getVolume());
        }
        try(SettingsManager manager = new SettingsManager(path))
        {
            assertEquals(77, manager.getSettings(9).getVolume());
        }
    }

    @Test
    void endpointsAndTheInheritedRatioSurvivePersistence() throws Exception
    {
        Path path = directory.resolve("settings.json");
        // Previously supported integer strings remain readable.
        Files.writeString(path, "{\"9\":{\"volume\":\"77\",\"skip_ratio\":-1}}");
        try(SettingsManager manager = new SettingsManager(path))
        {
            assertEquals(77, manager.getSettings(9).getVolume());
            assertEquals(-1, manager.getSettings(9).getSkipRatio());
            for(int id = 0; id < 3; id++)
            {
                Settings settings = manager.getSettings(10 + id);
                settings.setVolume(id == 0 ? 0 : 150);
                settings.setSkipRatio(id - 1);
            }
        }
        try(SettingsManager manager = new SettingsManager(path))
        {
            for(int id = 0; id < 3; id++)
            {
                assertEquals(id == 0 ? 0 : 150, manager.getSettings(10 + id).getVolume());
                assertEquals(id - 1, manager.getSettings(10 + id).getSkipRatio());
            }
        }
    }
}
