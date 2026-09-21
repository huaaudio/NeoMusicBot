package io.github.huaaudio.neomusicbot.commands.slash;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class SlashFailureLoggingTest
{
    @Test
    void actualResponseFailureCallbackDoesNotLogWebhookCredentialsOrNestedSecrets() throws Exception
    {
        IOException failure = new IOException("Request failed https://discord.com/api/webhooks/123/HOOK_SENTINEL",
                new IllegalStateException("Cookie: SESSDATA=COOKIE_SENTINEL"));
        failure.addSuppressed(new IllegalStateException("discord_token=TOKEN_SENTINEL"));
        InteractionHook hook = proxy(InteractionHook.class, (self, method, args) -> {
            if(method.getName().equals("editOriginal"))
                return proxy(method.getReturnType(), (action, actionMethod, actionArgs) -> {
                    if(actionMethod.getName().equals("queue") && actionArgs.length == 2)
                    {
                        @SuppressWarnings("unchecked") Consumer<Throwable> callback = (Consumer<Throwable>)actionArgs[1];
                        callback.accept(failure);
                        return null;
                    }
                    throw new UnsupportedOperationException(actionMethod.getName());
                });
            throw new UnsupportedOperationException(method.getName());
        });
        SlashCommandInteraction interaction = proxy(SlashCommandInteraction.class, (self, method, args) -> {
            if(method.getName().equals("getHook")) return hook;
            throw new UnsupportedOperationException(method.getName());
        });
        JDA jda = proxy(JDA.class, (self, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });
        SlashCommandInteractionEvent event = new SlashCommandInteractionEvent(jda, 0, interaction);
        Logger logger = (Logger)LoggerFactory.getLogger(SlashCommandListener.class);
        Level previous = logger.getLevel();
        boolean additive = logger.isAdditive();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.addAppender(captured);
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        try
        {
            var edit = SlashCommandListener.class.getDeclaredMethod("edit", SlashCommandInteractionEvent.class, String.class);
            edit.setAccessible(true);
            edit.invoke(new SlashCommandListener(null), event, "Safe response");
            assertEquals(1, captured.list.size());
            ILoggingEvent logged = captured.list.getFirst();
            String rendered = logged.getFormattedMessage() + (logged.getThrowableProxy() == null
                    ? "" : ThrowableProxyUtil.asString(logged.getThrowableProxy()));
            assertAll(
                    () -> assertTrue(rendered.contains("Could not edit Slash command response")),
                    () -> assertTrue(rendered.contains("IOException")),
                    () -> assertFalse(rendered.contains("HOOK_SENTINEL")),
                    () -> assertFalse(rendered.contains("COOKIE_SENTINEL")),
                    () -> assertFalse(rendered.contains("TOKEN_SENTINEL")));
        }
        finally
        {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(previous);
            logger.setAdditive(additive);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler)
    {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
