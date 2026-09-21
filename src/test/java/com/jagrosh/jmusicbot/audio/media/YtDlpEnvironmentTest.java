/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YtDlpEnvironmentTest
{
    @Test
    public void usesPackagedDenoCacheWhenLauncherDidNotSetOne() throws Exception
    {
        Path providerHome = Files.createTempDirectory("jmusicbot-provider-");
        Path packagedDenoDirectory = Files.createDirectories(providerHome.resolve(".deno-dir"));
        YtDlpConfiguration configuration = new YtDlpConfiguration(
                "yt-dlp", "deno", null, null, providerHome, providerHome,
                true, true, Duration.ofSeconds(30), Duration.ofSeconds(60));

        Map<String, String> childEnvironment = new HashMap<>();
        childEnvironment.put("JMUSICBOT_DISCORD_TOKEN", "discord-secret");
        childEnvironment.put("Cookie", "cookie-secret");
        childEnvironment.put("Authorization", "bearer-secret");
        childEnvironment.put("AWS_SECRET_ACCESS_KEY", "aws-secret");
        try (YtDlpMediaResolver resolver = new YtDlpMediaResolver(configuration))
        {
            resolver.configureChildEnvironment(childEnvironment);
        }

        String launcherDenoDirectory = System.getenv("DENO_DIR");
        assertEquals(launcherDenoDirectory == null
                        ? packagedDenoDirectory.toString() : launcherDenoDirectory,
                childEnvironment.get("DENO_DIR"));
        assertEquals("1", childEnvironment.get("DENO_NO_PROMPT"));
        assertEquals("1", childEnvironment.get("DENO_NO_UPDATE_CHECK"));
        assertFalse(childEnvironment.containsKey("JMUSICBOT_DISCORD_TOKEN"));
        assertFalse(childEnvironment.containsKey("Cookie"));
        assertFalse(childEnvironment.containsKey("Authorization"));
        assertFalse(childEnvironment.containsKey("AWS_SECRET_ACCESS_KEY"));

        Set<String> allowed = Set.of(
                "PATH", "Path", "HOME", "USERPROFILE", "SystemRoot", "WINDIR", "PATHEXT",
                "TEMP", "TMP", "TMPDIR", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME",
                "DENO_DIR", "SSL_CERT_FILE", "SSL_CERT_DIR", "TOKEN_TTL", "NO_COLOR",
                "PYTHONUTF8", "DENO_NO_PROMPT", "DENO_NO_UPDATE_CHECK");
        assertTrue(childEnvironment.keySet().stream().allMatch(allowed::contains));
    }
}
