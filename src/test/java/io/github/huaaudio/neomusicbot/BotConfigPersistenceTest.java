package io.github.huaaudio.neomusicbot;

import io.github.huaaudio.neomusicbot.entities.Prompt;
import com.typesafe.config.ConfigFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BotConfigPersistenceTest
{
    @TempDir Path directory;

    @Test
    void generatingDefaultsNeverOverwritesAnExistingConfiguration() throws Exception
    {
        Path path = directory.resolve("config.txt");
        String original = "token = private-test-value\nowner = 123\nstayinchannel = true\n";
        Files.writeString(path, original);
        withConfig(path, BotConfig::writeDefaultConfig);
        assertEquals(original, Files.readString(path));
    }

    @Test
    void fillingMissingOwnerPreservesCustomSettingsAndComments() throws Exception
    {
        Path path = directory.resolve("config.txt");
        Files.writeString(path, "# Keep this comment\ntoken = test-token\nowner = 0\n"
                + "stayinchannel = true\nplaylistsfolder = \"My Playlists\"\nskipratio = 0.8\n");
        RecordingPrompt prompt = new RecordingPrompt("123");
        BotConfig config = new BotConfig(prompt);
        withConfig(path, config::load);
        assertTrue(config.isValid());
        String saved = Files.readString(path);
        var parsed = ConfigFactory.parseString(saved);
        assertEquals(123, parsed.getLong("owner"));
        assertTrue(parsed.getBoolean("stayinchannel"));
        assertEquals("My Playlists", parsed.getString("playlistsfolder"));
        assertEquals(0.8, parsed.getDouble("skipratio"));
        assertTrue(saved.contains("Keep this comment"));
    }

    @Test
    void configurationErrorsDoNotEchoSensitiveValues() throws Exception
    {
        Path path = directory.resolve("config.txt");
        Files.writeString(path, "token = test-token\nowner = private-sentinel-value\n");
        RecordingPrompt prompt = new RecordingPrompt(null);
        BotConfig config = new BotConfig(prompt);
        withConfig(path, config::load);
        assertFalse(config.isValid());
        assertFalse(prompt.messages.isEmpty());
        assertTrue(prompt.messages.stream().noneMatch(message -> message.contains("private-sentinel-value")));
    }

    private static void withConfig(Path path, Runnable operation)
    {
        String previous = System.getProperty("config.file");
        try
        {
            System.setProperty("config.file", path.toString());
            operation.run();
        }
        finally
        {
            if(previous == null) System.clearProperty("config.file");
            else System.setProperty("config.file", previous);
        }
    }

    private static final class RecordingPrompt extends Prompt
    {
        private final String answer;
        final List<String> messages = new ArrayList<>();

        RecordingPrompt(String answer)
        {
            super("test", null, true, true);
            this.answer = answer;
        }

        @Override public String prompt(String message) { return answer; }
        @Override public void alert(Level level, String context, String message) { messages.add(message); }
    }
}
