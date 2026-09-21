package io.github.huaaudio.neomusicbot.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransformRemovalPolicyTest
{
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void genericHtmlTransformsAndDirectScrapingDependenciesCannotReturn() throws Exception
    {
        assertFalse(Files.exists(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/audio/TransformativeAudioSourceManager.java")));

        String playerManager = Files.readString(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/audio/PlayerManager.java"));
        String botConfig = Files.readString(ROOT.resolve(
                "src/main/java/io/github/huaaudio/neomusicbot/BotConfig.java"));
        String reference = Files.readString(ROOT.resolve("src/main/resources/reference.conf"));

        assertFalse(playerManager.contains("TransformativeAudioSourceManager"));
        assertFalse(botConfig.contains("getTransforms"));
        assertFalse(reference.contains("transforms"));
        // Lavaplayer already needs jsoup. Managing its transitive version must not be
        // confused with adding a direct HTML-scraping dependency to the application.
        var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ROOT.resolve("pom.xml").toFile());
        assertNull(XPathFactory.newInstance().newXPath().evaluate(
                "/project/dependencies/dependency[groupId='org.jsoup' or artifactId='jsoup']",
                pom, XPathConstants.NODE));
        try(var sources = Files.walk(ROOT.resolve("src/main/java")))
        {
            for(Path source : sources.filter(path -> path.toString().endsWith(".java")).toList())
                assertFalse(Files.readString(source).contains("org.jsoup"),
                        "Application code must not restore generic HTML scraping: " + source);
        }
    }
}
