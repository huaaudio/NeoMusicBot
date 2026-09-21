package io.github.huaaudio.neomusicbot.settings;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import io.github.huaaudio.neomusicbot.Bot;
import io.github.huaaudio.neomusicbot.BotConfig;
import io.github.huaaudio.neomusicbot.audio.AudioHandler;
import io.github.huaaudio.neomusicbot.audio.GuildPlaybackSession;
import io.github.huaaudio.neomusicbot.commands.slash.SlashCommandListener;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SlashSkipVoteTest
{
    @TempDir Path directory;

    @Test
    void leavingWhileTheCommandWaitsDoesNotRecordAVoteOrThrow()
    {
        try(Fixture fixture = new Fixture())
        {
            fixture.afterReservation.set(() -> fixture.requesterChannel.set(null));
            String reply = fixture.skip(fixture.requester);
            assertTrue(reply.contains("same voice channel"), reply);
            assertSame(fixture.track, fixture.playing.get());
            assertTrue(fixture.handler.addVoteIfCurrent(fixture.track, "1").added());
        }
    }

    @Test
    void movingToAnEmptyChannelCannotReduceTheVoteThreshold()
    {
        try(Fixture fixture = new Fixture())
        {
            AudioChannelUnion other = fixture.channel(200, List.of(fixture.requester));
            fixture.afterReservation.set(() -> fixture.requesterChannel.set(other));
            String reply = fixture.skip(fixture.requester);
            assertTrue(reply.contains("same voice channel"), reply);
            assertSame(fixture.track, fixture.playing.get());
            assertEquals(0, fixture.stops.get());
        }
    }

    @Test
    void oneMembershipSnapshotDeterminesBothListenersAndVotes()
    {
        try(Fixture fixture = new Fixture())
        {
            fixture.afterMembersRead.set(fixture.members::clear);
            String reply = fixture.skip(fixture.requester);
            assertTrue(reply.contains("1/2"), reply);
            assertEquals(1, fixture.memberReads.get());
            assertSame(fixture.track, fixture.playing.get());
        }
    }

    @Test
    void duplicateVotesDoNotCountAndBotsAndDeafenedMembersAreExcluded()
    {
        try(Fixture fixture = new Fixture())
        {
            fixture.members.add(fixture.member(4, true, false, new AtomicReference<>(fixture.voice)));
            fixture.members.add(fixture.member(5, false, true, new AtomicReference<>(fixture.voice)));
            assertTrue(fixture.skip(fixture.requester).contains("1/2"));
            String duplicate = fixture.skip(fixture.requester);
            assertTrue(duplicate.contains("already voted"), duplicate);
            assertTrue(duplicate.contains("1/2"), duplicate);
            assertSame(fixture.track, fixture.playing.get());
            String skipped = fixture.skip(fixture.members.get(1));
            assertTrue(skipped.contains("2/2"), skipped);
            assertTrue(skipped.contains("Skipped"), skipped);
            assertNull(fixture.playing.get());
            assertEquals(1, fixture.stops.get());
        }
    }

    @Test
    void zeroRatioStillRequiresTheRequesterToBeListening()
    {
        try(Fixture fixture = new Fixture())
        {
            fixture.bot.getSettingsManager().getSettings(10L).setSkipRatio(0);
            fixture.afterReservation.set(() -> fixture.requesterChannel.set(null));
            String reply = fixture.skip(fixture.requester);
            assertTrue(reply.contains("same voice channel"), reply);
            assertSame(fixture.track, fixture.playing.get());
        }
    }

    @Test
    void channelDisappearingDuringTheInitialVoiceCheckHasAFriendlyReply()
    {
        try(Fixture fixture = new Fixture())
        {
            fixture.requesterChannel.set(null);
            String reply = fixture.skip(fixture.requester);
            assertTrue(reply.contains("Join a voice channel"), reply);
            assertSame(fixture.track, fixture.playing.get());
        }
    }

    private final class Fixture implements AutoCloseable
    {
        final Bot bot;
        final Guild guild;
        final JDA jda;
        final AudioTrack track = new BaseAudioTrack(new AudioTrackInfo("song", "test", 60_000, "test", false, null)) {
            @Override public void process(LocalAudioTrackExecutor executor) { }
        };
        final AtomicReference<AudioTrack> playing = new AtomicReference<>(track);
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger memberReads = new AtomicInteger();
        final AtomicReference<Runnable> afterReservation = new AtomicReference<>();
        final AtomicReference<Runnable> afterMembersRead = new AtomicReference<>();
        final AtomicReference<AudioChannelUnion> requesterChannel = new AtomicReference<>();
        final List<Member> members = new ArrayList<>();
        final AudioChannelUnion voice;
        final Member requester;
        final AudioHandler handler;
        final AtomicReference<String> reply = new AtomicReference<>();

        Fixture()
        {
            bot = new Bot(new BotConfig(null) {
                @Override public int getMaxYTPlaylistPages() { return 10; }
            }, new SettingsManager(directory.resolve("settings.json")));
            bot.getSettingsManager().getSettings(10L).setSkipRatio(0.55);
            AtomicReference<Object> sending = new AtomicReference<>();
            voice = channel(100, members);
            requesterChannel.set(voice);
            requester = member(1, false, false, requesterChannel);
            members.add(requester);
            members.add(member(2, false, false, new AtomicReference<>(voice)));
            members.add(member(3, false, false, new AtomicReference<>(voice)));
            AudioManager audio = proxy(AudioManager.class, (self, method, args) -> switch(method.getName()) {
                case "getSendingHandler" -> sending.get();
                case "getConnectedChannel" -> voice;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            guild = proxy(Guild.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> 10L;
                case "getId" -> "10";
                case "getAudioManager" -> audio;
                case "getAfkChannel" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            jda = proxy(JDA.class, (self, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
            AudioPlayer player = proxy(AudioPlayer.class, (self, method, args) -> switch(method.getName()) {
                case "getPlayingTrack" -> playing.get();
                case "isPaused" -> false;
                case "getVolume" -> 100;
                case "stopTrack" -> { stops.incrementAndGet(); playing.set(null); yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            handler = new AudioHandler(bot.getPlayerManager(), guild, player) {
                @Override public GuildPlaybackSession.VoiceReservation reserveVoiceChannel(long channelId) {
                    var result = super.reserveVoiceChannel(channelId);
                    Runnable callback = afterReservation.getAndSet(null);
                    if(callback != null) callback.run();
                    return result;
                }
            };
            sending.set(handler);
            handler.reserveVoiceChannel(100);
        }

        AudioChannelUnion channel(long id, List<Member> occupants)
        {
            return proxy(AudioChannelUnion.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> id;
                case "getType" -> ChannelType.VOICE;
                case "getMembers" -> {
                    memberReads.incrementAndGet();
                    List<Member> snapshot = List.copyOf(occupants);
                    Runnable callback = afterMembersRead.getAndSet(null);
                    if(callback != null) callback.run();
                    yield snapshot;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        Member member(long id, boolean isBot, boolean deaf, AtomicReference<AudioChannelUnion> channel)
        {
            User user = proxy(User.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> id;
                case "getId" -> Long.toString(id);
                case "isBot" -> isBot;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            GuildVoiceState state = proxy(GuildVoiceState.class, (self, method, args) -> switch(method.getName()) {
                // Model the gateway removing the channel between inAudioChannel() and getChannel().
                case "inAudioChannel" -> true;
                case "getChannel" -> channel.get();
                case "isDeafened" -> deaf;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return proxy(Member.class, (self, method, args) -> switch(method.getName()) {
                case "getIdLong" -> id;
                case "getId" -> Long.toString(id);
                case "getUser" -> user;
                case "getVoiceState" -> state;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        String skip(Member caller)
        {
            reply.set(null);
            SlashCommandInteraction interaction = proxy(SlashCommandInteraction.class, (self, method, args) -> switch(method.getName()) {
                case "getName", "getFullCommandName" -> "skip";
                case "getSubcommandGroup", "getSubcommandName" -> null;
                case "getUser" -> caller.getUser();
                case "getGuild" -> guild;
                case "getMember" -> caller;
                case "isFromGuild" -> true;
                case "getChannelIdLong" -> 20L;
                case "isAcknowledged" -> false;
                case "reply" -> { reply.set((String) args[0]); yield action(method.getReturnType()); }
                case "deferReply" -> action(method.getReturnType());
                default -> throw new UnsupportedOperationException(method.getName());
            });
            new SlashCommandListener(bot).onSlashCommandInteraction(new SlashCommandInteractionEvent(jda, 0, interaction));
            return reply.get();
        }

        Object action(Class<?> type)
        {
            return proxy(type, (self, method, args) -> switch(method.getName()) {
                case "setEphemeral" -> self;
                case "setContent" -> { reply.set((String) args[0]); yield self; }
                case "queue" -> null;
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
