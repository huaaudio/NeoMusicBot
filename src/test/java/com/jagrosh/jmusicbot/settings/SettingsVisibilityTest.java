package com.jagrosh.jmusicbot.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsVisibilityTest
{
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyMutableSettingFieldIsVolatile() throws Exception
    {
        for(String name : List.of("textId", "voiceId", "roleId", "volume", "defaultPlaylist",
                "repeatMode", "queueType", "skipRatio"))
        {
            Field field = Settings.class.getDeclaredField(name);
            assertTrue(Modifier.isVolatile(field.getModifiers()), name + " must be volatile");
        }
    }

    @Test
    void aConcurrentReaderObservesASettingsUpdate() throws Exception
    {
        Path path = temporaryDirectory.resolve("visibility.json");
        try(SettingsManager manager = new SettingsManager(path);
            var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Settings settings = manager.getSettings(1L);
            CountDownLatch start = new CountDownLatch(1);
            var observed = executor.submit(() ->
            {
                start.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while(settings.getVolume() != 137 && System.nanoTime() < deadline)
                    Thread.onSpinWait();
                return settings.getVolume() == 137;
            });
            start.countDown();
            settings.setVolume(137);
            assertTrue(observed.get(3, TimeUnit.SECONDS));
            assertTrue(manager.flush(Duration.ofSeconds(3)));
        }
    }

    @Test
    void legacyPrefixJsonIsIgnoredAndNotWrittenBack() throws Exception
    {
        Path path = temporaryDirectory.resolve("legacy.json");
        Files.writeString(path, "{\"9\":{\"prefix\":\"!\",\"volume\":77}}",
                StandardCharsets.UTF_8);
        try(SettingsManager manager = new SettingsManager(path))
        {
            manager.getSettings(9L).setVolume(78);
            assertTrue(manager.flush(Duration.ofSeconds(3)));
        }
        JSONObject stored = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
        assertFalse(stored.getJSONObject("9").has("prefix"));
    }
}
