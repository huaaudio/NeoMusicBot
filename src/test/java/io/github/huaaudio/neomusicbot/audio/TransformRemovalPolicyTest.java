package io.github.huaaudio.neomusicbot.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TransformRemovalPolicyTest
{
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void genericHtmlTransformsAndJsoupCannotReturn() throws IOException
    {
        assertFalse(Files.exists(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/audio/TransformativeAudioSourceManager.java")));

        String playerManager = Files.readString(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/audio/PlayerManager.java"));
        String botConfig = Files.readString(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/BotConfig.java"));
        String reference = Files.readString(ROOT.resolve("src/main/resources/reference.conf"));
        String pom = Files.readString(ROOT.resolve("pom.xml"));

        assertFalse(playerManager.contains("TransformativeAudioSourceManager"));
        assertFalse(botConfig.contains("getTransforms"));
        assertFalse(reference.contains("transforms"));
        assertFalse(pom.contains("org.jsoup"));
        assertFalse(pom.contains("<artifactId>jsoup</artifactId>"));
    }
}
