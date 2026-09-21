package io.github.huaaudio.neomusicbot.commands.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;

class SlashCommandSchemaTest
{
    @Test
    void exposesTheFixedPublicCommandTreeAndGuildScopes()
    {
        List<CommandData> commands = SlashCommandSchema.create();
        assertEquals(List.of(
                "about", "ping", "help",
                "play", "search", "now-playing", "queue", "remove", "seek", "shuffle", "skip",
                "playlist", "dj", "config", "owner"),
                commands.stream().map(CommandData::getName).toList());

        Set<String> guildOnly = Set.of(
                "play", "search", "now-playing", "queue", "remove", "seek", "shuffle", "skip",
                "playlist", "dj", "config", "owner");
        commands.stream()
                .filter(command -> guildOnly.contains(command.getName()))
                .forEach(command -> assertEquals(Set.of(InteractionContextType.GUILD), command.getContexts()));

        assertEquals(Set.of("list", "play"), subcommands(commands, "playlist"));
        assertEquals(Set.of("force-skip", "force-remove", "move", "pause", "resume", "play-next",
                "repeat", "skip-to", "stop", "volume"), subcommands(commands, "dj"));
        assertEquals(Set.of("show", "queue-type", "dj-role", "skip-ratio", "text-channel", "voice-channel"),
                subcommands(commands, "config"));
        assertEquals(Set.of("playlist", "presence", "default-playlist", "avatar", "username", "debug", "shutdown"),
                subcommands(commands, "owner"));
    }

    @Test
    void componentIdGrammarCannotCarryQueriesUrlsOrCommandPayloads() throws Exception
    {
        Field field = SlashCommandListener.class.getDeclaredField("COMPONENT_ID");
        field.setAccessible(true);
        Pattern pattern = (Pattern) field.get(null);
        String nonce = "a".repeat(32);

        assertTrue(pattern.matcher("mb:" + nonce + ":pick").matches());
        assertTrue(pattern.matcher("mb:" + nonce + ":prev").matches());
        assertTrue(pattern.matcher("mb:" + nonce + ":next").matches());

        assertFalse(pattern.matcher("mb:" + nonce + ":--exec").matches());
        assertFalse(pattern.matcher("mb:" + nonce + ":pick\n--exec").matches());
        assertFalse(pattern.matcher("mb:https://youtube.com/watch?v=x:pick").matches());
        assertFalse(pattern.matcher("mb:" + nonce + ":next?url=https://example.invalid").matches());
        assertFalse(pattern.matcher("mb:" + nonce + ":pick\"quoted").matches());
    }

    @Test
    void userAndMediaFailureLogsRemoveSecretsAndSignedQueries()
    {
        String discordToken = "ABCDEFGHIJKLMNOPQRSTUVWX.abcdef.abcdefghijklmnopqrstuvwxyz123456";
        RuntimeException failure = new RuntimeException(
                "GET https://r.example/media?expire=1&sig=secret "
                        + "Cookie: SID=one; SESSDATA=two; account=owner@example.invalid\n"
                        + "Authorization: Bearer super-secret\n"
                        + "PO_TOKEN=pot-secret\n"
                        + "token " + discordToken);

        String safe = SlashCommandListener.sanitizedFailure(failure);
        assertTrue(safe.startsWith("RuntimeException:"));
        assertTrue(safe.contains("[url-redacted]"));
        assertFalse(safe.contains("r.example"));
        assertFalse(safe.contains("/media"));
        assertFalse(safe.contains("sig=secret"));
        assertFalse(safe.contains("SID=one"));
        assertFalse(safe.contains("SESSDATA=two"));
        assertFalse(safe.contains("owner@example.invalid"));
        assertFalse(safe.contains("super-secret"));
        assertFalse(safe.contains("pot-secret"));
        assertFalse(safe.contains(discordToken));
    }

    private static Set<String> subcommands(List<CommandData> commands, String name)
    {
        SlashCommandData command = (SlashCommandData) commands.stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
        return command.getSubcommands().stream().map(subcommand -> subcommand.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
