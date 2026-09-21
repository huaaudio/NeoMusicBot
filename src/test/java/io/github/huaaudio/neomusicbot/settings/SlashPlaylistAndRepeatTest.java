package io.github.huaaudio.neomusicbot.settings;

import io.github.huaaudio.neomusicbot.Bot;
import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.commands.slash.SlashCommandListener;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SlashPlaylistAndRepeatTest
{
    @TempDir Path directory;

    @Test
    void appendPreservesShuffleAndCommentsInOriginalOrder() throws Exception
    {
        String original = "# 夜间歌单\r\n#shuffle\r\nhttps://youtu.be/YE7VzlLtp-4\r\n\r\n// keep this comment";
        Path playlist = Files.writeString(directory.resolve("night.txt"), original);
        try(Fixture fixture = new Fixture())
        {
            String reply = fixture.run("owner", "playlist", 20, Map.of(
                    "action", "append", "name", "night", "items", "https://www.bilibili.com/video/BV13x41117TL"));
            assertTrue(reply.contains("Appended items"), reply);
            String updated = Files.readString(playlist);
            assertTrue(updated.startsWith(original), "Appending must preserve the exact original contents");
            assertEquals(1, updated.lines().filter(line -> line.equals("#shuffle")).count());
            assertTrue(updated.contains("https://www.bilibili.com/video/BV13x41117TL"));
        }
    }

    @Test
    void repeatHonorsTextRestrictionAndStillWorksInAllowedChannel() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            Settings settings = fixture.bot.getSettingsManager().getSettings(10L);
            settings.setTextChannel(proxy(TextChannel.class, (self, method, args) -> {
                if(method.getName().equals("getIdLong")) return 20L;
                throw new UnsupportedOperationException(method.getName());
            }));
            RepeatMode original = settings.getRepeatMode();
            String denied = fixture.run("dj", "repeat", 21, Map.of("mode", "all"));
            assertTrue(denied.contains("restricted to the configured text channel"), denied);
            assertEquals(original, settings.getRepeatMode());
            String accepted = fixture.run("dj", "repeat", 20, Map.of("mode", "all"));
            assertTrue(accepted.contains("Repeat mode is now"), accepted);
            assertEquals(RepeatMode.ALL, settings.getRepeatMode());
        }
    }

    private final class Fixture implements AutoCloseable
    {
        final Bot bot;
        final Guild guild;
        final User owner;
        final Member member;
        final JDA jda;
        final AtomicReference<String> reply = new AtomicReference<>();

        Fixture()
        {
            bot = new Bot(new BotConfig(null) {
                @Override public int getMaxYTPlaylistPages() { return 10; }
                @Override public long getOwnerId() { return 1; }
                @Override public String getPlaylistsFolder() { return directory.toString(); }
            }, new SettingsManager(directory.resolve("settings.json")));
            AtomicReference<Object> sending = new AtomicReference<>();
            AudioManager audio = proxy(AudioManager.class, (self, method, args) -> switch(method.getName()) {
                case "getSendingHandler" -> sending.get();
                case "setSendingHandler" -> { sending.set(args[0]); yield null; }
                case "setConnectionListener", "setAutoReconnect", "closeAudioConnection", "getConnectedChannel" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            guild = proxy(Guild.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> 10L;
                case "getId" -> "10";
                case "getAudioManager" -> audio;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            owner = proxy(User.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> 1L;
                case "getId" -> "1";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            member = proxy(Member.class, (self, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
            jda = proxy(JDA.class, (self, method, args) -> switch(method.getName()) {
                case "getStatus" -> JDA.Status.CONNECTED;
                case "getGuilds" -> List.of(guild);
                case "shutdown" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            bot.setJDA(jda);
        }

        String run(String command, String subcommand, long channelId, Map<String, String> options)
        {
            reply.set(null);
            InteractionHook hook = proxy(InteractionHook.class, (self, method, args) -> {
                if(method.getName().equals("editOriginal"))
                {
                    reply.set((String) args[0]);
                    return action(method.getReturnType());
                }
                throw new UnsupportedOperationException(method.getName());
            });
            SlashCommandInteraction interaction = proxy(SlashCommandInteraction.class, (self, method, args) -> switch(method.getName()) {
                case "getName" -> command;
                case "getSubcommandName" -> subcommand;
                case "getSubcommandGroup" -> null;
                case "getUser" -> owner;
                case "getGuild" -> guild;
                case "getMember" -> member;
                case "isFromGuild" -> true;
                case "getChannelIdLong" -> channelId;
                case "isAcknowledged" -> false;
                case "getHook" -> hook;
                case "getOptions" -> options.entrySet().stream().map(entry -> new OptionMapping(
                        DataObject.empty().put("name", entry.getKey()).put("type", 3)
                                .put("value", entry.getValue()), null, null, guild)).toList();
                case "getOption" -> options.containsKey((String) args[0])
                        ? new OptionMapping(DataObject.empty().put("name", args[0]).put("type", 3)
                            .put("value", options.get(args[0])), null, null, guild) : null;
                case "reply" -> { reply.set((String) args[0]); yield action(method.getReturnType()); }
                case "deferReply" -> action(method.getReturnType());
                default -> throw new UnsupportedOperationException(method.getName());
            });
            new SlashCommandListener(bot).onSlashCommandInteraction(new SlashCommandInteractionEvent(jda, 0, interaction));
            return reply.get();
        }

        @SuppressWarnings("unchecked")
        Object action(Class<?> type)
        {
            return proxy(type, (self, method, args) -> switch(method.getName()) {
                case "setEphemeral" -> self;
                case "setContent" -> { reply.set((String) args[0]); yield self; }
                case "queue" -> {
                    if(args != null && args.length > 0 && args[0] != null)
                        ((Consumer<Object>) args[0]).accept(null);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        @Override public void close() { bot.shutdown(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
