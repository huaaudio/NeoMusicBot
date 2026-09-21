package io.github.huaaudio.neomusicbot.settings;

import io.github.huaaudio.neomusicbot.Bot;
import io.github.huaaudio.neomusicbot.BotConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BotShutdownTest
{
    @TempDir Path directory;

    @Test
    void processExitRunsTheApplicationCleanupHook() throws Exception
    {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=" + directory,
                "-cp", System.getProperty("java.class.path"), ExitHarness.class.getName(), directory.toString());
        builder.environment().keySet().removeIf(key -> key.startsWith("NEOMUSICBOT_") || key.startsWith("JMUSICBOT_")
                || List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS").contains(key));
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile());
        Process child = builder.start();
        try
        {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Cleanup hook must not hang process exit");
            assertEquals(0, child.exitValue());
            assertEquals("closed", Files.readString(directory.resolve("closed.txt")));
        }
        finally
        {
            if(child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static final class ExitHarness
    {
        public static void main(String[] args)
        {
            Path directory = Path.of(args[0]);
            bot(new SettingsManager(directory.resolve("settings.json")) {
                @Override public void close()
                {
                    super.close();
                    try { Files.writeString(directory.resolve("closed.txt"), "closed"); }
                    catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
            });
            System.exit(0);
        }
    }

    @Test
    void discordFailureDoesNotSkipSettingsOrWorkers()
    {
        SettingsManager settings = new SettingsManager(directory.resolve("settings.json"));
        Bot bot = bot(settings);
        AtomicInteger disconnected = new AtomicInteger();
        bot.setJDA(proxy(JDA.class, (self, method, args) -> switch(method.getName()) {
            case "getStatus" -> JDA.Status.CONNECTED;
            case "getGuilds" -> throw new IllegalStateException("Simulated Discord failure");
            case "shutdown" -> { disconnected.incrementAndGet(); yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        }));
        try
        {
            assertDoesNotThrow(bot::shutdown);
            assertEquals(1, disconnected.get());
            assertTrue(settings.isClosed());
            assertTrue(bot.getThreadpool().isShutdown());
        }
        finally { cleanup(bot, settings); }
    }

    @Test
    void oneBrokenVoiceConnectionDoesNotSkipOtherGuilds()
    {
        SettingsManager settings = new SettingsManager(directory.resolve("settings.json"));
        Bot bot = bot(settings);
        AtomicInteger closed = new AtomicInteger();
        Guild broken = guild(() -> { throw new IllegalStateException("Simulated close failure"); });
        Guild healthy = guild(closed::incrementAndGet);
        bot.setJDA(proxy(JDA.class, (self, method, args) -> switch(method.getName()) {
            case "getStatus" -> JDA.Status.CONNECTED;
            case "getGuilds" -> List.of(broken, healthy);
            case "shutdown" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        }));
        try
        {
            assertDoesNotThrow(bot::shutdown);
            assertEquals(1, closed.get());
            assertTrue(settings.isClosed());
        }
        finally { cleanup(bot, settings); }
    }

    @Test
    void initializationFailureClosesTheOwnedSettingsStore()
    {
        SettingsManager settings = new SettingsManager(directory.resolve("settings.json"));
        try
        {
            assertThrows(IllegalStateException.class, () -> new Bot(new BotConfig(null) {
                @Override public int getMaxYTPlaylistPages() { throw new IllegalStateException("Invalid source config"); }
            }, settings));
            assertTrue(settings.isClosed());
        }
        finally { settings.close(); }
    }

    @Test
    void discordClientArrivingAfterShutdownIsClosed()
    {
        SettingsManager settings = new SettingsManager(directory.resolve("settings.json"));
        Bot bot = bot(settings);
        AtomicInteger closed = new AtomicInteger();
        try
        {
            bot.shutdown();
            bot.setJDA(proxy(JDA.class, (self, method, args) -> {
                if(method.getName().equals("shutdown")) { closed.incrementAndGet(); return null; }
                throw new UnsupportedOperationException(method.getName());
            }));
            assertEquals(1, closed.get());
            assertNull(bot.getJDA());
        }
        finally { cleanup(bot, settings); }
    }

    @Test
    void discordShutdownDoesNotHoldTheBotMonitor()
    {
        SettingsManager settings = new SettingsManager(directory.resolve("settings.json"));
        Bot bot = bot(settings);
        AtomicBoolean monitorHeld = new AtomicBoolean();
        bot.setJDA(proxy(JDA.class, (self, method, args) -> switch(method.getName()) {
            case "getStatus" -> JDA.Status.CONNECTED;
            case "getGuilds" -> List.of();
            case "shutdown" -> { monitorHeld.set(Thread.holdsLock(bot)); yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        }));
        try
        {
            bot.shutdown();
            assertFalse(monitorHeld.get(), "Library callbacks must not run under the bot monitor");
        }
        finally { cleanup(bot, settings); }
    }

    private static Bot bot(SettingsManager settings)
    {
        return new Bot(new BotConfig(null) {
            @Override public int getMaxYTPlaylistPages() { return 10; }
        }, settings);
    }

    private static Guild guild(Runnable close)
    {
        AudioManager audio = proxy(AudioManager.class, (self, method, args) -> switch(method.getName()) {
            case "getSendingHandler", "setAutoReconnect" -> null;
            case "closeAudioConnection" -> { close.run(); yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Guild.class, (self, method, args) -> {
            if(method.getName().equals("getAudioManager")) return audio;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void cleanup(Bot bot, SettingsManager settings)
    {
        // Keep a failing regression from leaving non-daemon workers in the test JVM.
        bot.getPlayerManager().shutdown();
        settings.close();
        bot.getThreadpool().shutdownNow();
    }
}
