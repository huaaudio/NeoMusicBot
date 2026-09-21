package com.jagrosh.jmusicbot.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsManagerTest
{
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAtomicallyAndRecoversTheLastKnownGoodBackup() throws Exception
    {
        Path path = temporaryDirectory.resolve("serversettings.json");
        SettingsManager first = new SettingsManager(path);
        first.getSettings(42L).setVolume(77);
        assertTrue(first.flush(Duration.ofSeconds(5)));
        first.getSettings(42L).setVolume(88);
        assertTrue(first.flush(Duration.ofSeconds(5)));
        first.close();

        JSONObject current = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
        assertEquals(88, current.getJSONObject("42").getInt("volume"));
        assertTrue(Files.isRegularFile(path.resolveSibling("serversettings.json.bak")));
        assertFalse(Files.exists(path.resolveSibling("serversettings.json.tmp")));

        Files.writeString(path, "{truncated", StandardCharsets.UTF_8);
        try(SettingsManager recovered = new SettingsManager(path))
        {
            assertEquals(77, recovered.getSettings(42L).getVolume());
            JSONObject repaired = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
            assertEquals(77, repaired.getJSONObject("42").getInt("volume"));
        }
    }

    @Test
    void closeFlushesAnUpdateArrivingAsTheLastDrainReleases() throws Exception
    {
        Path path = temporaryDirectory.resolve("close-race.json");
        CountDownLatch drainReachedRelease = new CountDownLatch(1);
        CountDownLatch allowDrainRelease = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        SettingsManager manager = new SettingsManager(path)
        {
            @Override
            void beforeDrainRelease()
            {
                if(pauseOnce.compareAndSet(true, false))
                {
                    drainReachedRelease.countDown();
                    awaitUninterruptibly(allowDrainRelease);
                }
            }
        };

        Thread closer = null;
        try
        {
            manager.getSettings(7L).setVolume(77);
            assertTrue(drainReachedRelease.await(5, TimeUnit.SECONDS));

            // The first drain has performed its final dirty check but still
            // owns writeQueued. This update therefore cannot enqueue a drain.
            manager.getSettings(7L).setVolume(88);
            closer = Thread.ofVirtual().name("settings-close-test").start(manager::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while(!manager.isClosed() && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertTrue(manager.isClosed());

            allowDrainRelease.countDown();
            closer.join(5_000);
            assertFalse(closer.isAlive(), "Settings close did not finish");

            JSONObject stored = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
            assertEquals(88, stored.getJSONObject("7").getInt("volume"));
            assertFalse(Files.exists(path.resolveSibling("close-race.json.tmp")));
        }
        finally
        {
            allowDrainRelease.countDown();
            manager.close();
            if(closer != null)
                closer.join(5_000);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;
        while(true)
        {
            try
            {
                latch.await();
                break;
            }
            catch(InterruptedException ex)
            {
                interrupted = true;
            }
        }
        if(interrupted)
            Thread.currentThread().interrupt();
    }
}
