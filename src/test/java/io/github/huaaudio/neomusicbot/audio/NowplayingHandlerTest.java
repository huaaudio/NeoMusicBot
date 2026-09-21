package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.Bot;
import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.settings.SettingsManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.managers.Presence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NowplayingHandlerTest
{
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"a", "\uD83C\uDFB5"})
    void longMediaTitlesUseAValidActivityWithoutChangingTrackMetadata(String character) throws Exception
    {
        try(Fixture fixture = new Fixture(directory, true))
        {
            String title = character.repeat(129);
            AudioTrack track = track(title);
            assertDoesNotThrow(() -> fixture.bot.getNowplayingHandler().onTrackUpdate(track));
            Activity activity = fixture.activity.get();
            assertEquals(Activity.ActivityType.LISTENING, activity.getType());
            assertEquals(character.repeat(127) + "\u2026", activity.getName());
            assertEquals(title, track.getInfo().title);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  \t\n", "\u2003"})
    void missingOrBlankTitlesRestoreTheConfiguredActivity(String title) throws Exception
    {
        try(Fixture fixture = new Fixture(directory, true))
        {
            fixture.activity.set(Activity.listening("Previous song"));
            assertDoesNotThrow(() -> fixture.bot.getNowplayingHandler().onTrackUpdate(track(title)));
            assertEquals(fixture.configured, fixture.activity.get());
        }
    }

    @Test
    void aValidUnicodeTitleAtTheLimitIsNotShortened() throws Exception
    {
        try(Fixture fixture = new Fixture(directory, true))
        {
            String title = "\uD83C\uDFB5".repeat(128);
            fixture.bot.getNowplayingHandler().onTrackUpdate(track(title));
            assertEquals(title, fixture.activity.get().getName());
        }
    }

    @Test
    void multipleActiveGuildsAndStoppedPlaybackRestoreTheConfiguredActivity() throws Exception
    {
        try(Fixture fixture = new Fixture(directory, true))
        {
            Guild connected = connectedGuild();
            fixture.guilds.set(List.of(connected, connected));
            fixture.bot.getNowplayingHandler().onTrackUpdate(track("Song"));
            assertEquals(fixture.configured, fixture.activity.get());
            fixture.guilds.set(List.of(connected));
            fixture.bot.getNowplayingHandler().onTrackUpdate(track("Song"));
            assertEquals("Song", fixture.activity.get().getName());
            fixture.bot.getNowplayingHandler().onTrackUpdate(null);
            assertEquals(fixture.configured, fixture.activity.get());
        }
    }

    @Test
    void disabledSongStatusKeepsTheCurrentActivity() throws Exception
    {
        try(Fixture fixture = new Fixture(directory, false))
        {
            fixture.bot.getNowplayingHandler().onTrackUpdate(track("Song"));
            assertSame(fixture.configured, fixture.activity.get());
        }
    }

    private static AudioTrack track(String title)
    {
        AudioTrackInfo info = new AudioTrackInfo(title, "author", 1000, "test", false, null);
        return proxy(AudioTrack.class, (self, method, args) -> {
            if(method.getName().equals("getInfo")) return info;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Guild connectedGuild()
    {
        GuildVoiceState state = proxy(GuildVoiceState.class, (self, method, args) -> {
            if(method.getName().equals("inAudioChannel")) return true;
            throw new UnsupportedOperationException(method.getName());
        });
        SelfMember member = proxy(SelfMember.class, (self, method, args) -> {
            if(method.getName().equals("getVoiceState")) return state;
            throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Guild.class, (self, method, args) -> {
            if(method.getName().equals("getSelfMember")) return member;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class Fixture implements AutoCloseable
    {
        final Activity configured = Activity.playing("Configured activity");
        final AtomicReference<Activity> activity = new AtomicReference<>(configured);
        final AtomicReference<List<Guild>> guilds = new AtomicReference<>(List.of(connectedGuild()));
        final Bot bot;

        Fixture(Path directory, boolean enabled) throws Exception
        {
            var constructor = SettingsManager.class.getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            bot = new Bot(new BotConfig(null) {
                @Override public boolean getSongInStatus() { return enabled; }
                @Override public Activity getGame() { return configured; }
                @Override public int getMaxYTPlaylistPages() { return 10; }
            }, constructor.newInstance(directory.resolve("settings.json")));
            Presence presence = proxy(Presence.class, (self, method, args) -> {
                if(method.getName().equals("getActivity")) return activity.get();
                if(method.getName().equals("setActivity")) { activity.set((Activity) args[0]); return null; }
                throw new UnsupportedOperationException(method.getName());
            });
            bot.setJDA(proxy(JDA.class, (self, method, args) -> {
                if(method.getName().equals("getGuilds")) return guilds.get();
                if(method.getName().equals("getPresence")) return presence;
                if(method.getName().equals("shutdown")) return null;
                throw new UnsupportedOperationException(method.getName());
            }));
        }

        @Override public void close()
        {
            guilds.set(List.of());
            bot.shutdown();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
