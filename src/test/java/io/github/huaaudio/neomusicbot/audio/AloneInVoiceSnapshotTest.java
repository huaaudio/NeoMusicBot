package io.github.huaaudio.neomusicbot.audio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AloneInVoiceSnapshotTest
{
    @Test
    void concurrentDisconnectDoesNotInvalidateTheCapturedChannel() throws Exception
    {
        AudioChannelUnion channel = channel(List.of(member(true)));
        AtomicReference<AudioChannelUnion> connected = new AtomicReference<>(channel);
        AudioManager manager = proxy(AudioManager.class, name -> connected.getAndSet(null));
        Guild guild = proxy(Guild.class, name -> manager);
        assertTrue(isAlone(guild));
        assertNull(connected.get());
    }

    @Test
    void disconnectedBotsDoNotStartAnAbsenceTimer() throws Exception
    {
        AudioManager manager = proxy(AudioManager.class, name -> null);
        assertFalse(isAlone(proxy(Guild.class, name -> manager)));
    }

    @Test
    void aHumanListenerPreventsIdleDisconnect() throws Exception
    {
        AudioChannelUnion channel = channel(List.of(member(true), member(false)));
        AudioManager manager = proxy(AudioManager.class, name -> channel);
        assertFalse(isAlone(proxy(Guild.class, name -> manager)));
    }

    private static boolean isAlone(Guild guild) throws Exception
    {
        var check = AloneInVoiceHandler.class.getDeclaredMethod("isAlone", Guild.class);
        check.setAccessible(true);
        try { return (boolean) check.invoke(new AloneInVoiceHandler(null), guild); }
        catch(InvocationTargetException failure)
        {
            if(failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static AudioChannelUnion channel(List<Member> members)
    {
        return proxy(AudioChannelUnion.class, name -> members);
    }

    private static Member member(boolean bot)
    {
        User user = proxy(User.class, name -> bot);
        return proxy(Member.class, name -> user);
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> value)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> value.apply(method.getName())));
    }
}
