/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YtDlpEphemeralCacheTest
{
    @Test
    public void replacesHostXdgCacheAndErasesProviderDataOnClose() throws Exception
    {
        Path providerHome = Files.createTempDirectory("jmusicbot-provider-cache-test-");
        YtDlpConfiguration configuration = new YtDlpConfiguration(
                "yt-dlp", "deno", null, null, providerHome, providerHome,
                true, true, Duration.ofSeconds(30), Duration.ofSeconds(60));
        Map<String, String> parent = new HashMap<>();
        parent.put("HOME", providerHome.resolve("host-home").toString());
        parent.put("XDG_CACHE_HOME", providerHome.resolve("host-cache").toString());
        parent.put("DENO_DIR", providerHome.resolve("host-deno-cache").toString());
        parent.put("JMUSICBOT_DISCORD_TOKEN", "must-not-propagate");

        YtDlpMediaResolver resolver = new YtDlpMediaResolver(configuration);
        Path childCache = null;
        try
        {
            Map<String, String> child = new HashMap<>();
            resolver.configureChildEnvironment(child, parent);
            childCache = Path.of(child.get("XDG_CACHE_HOME"));

            assertNotEquals(parent.get("XDG_CACHE_HOME"), childCache.toString());
            assertEquals(parent.get("DENO_DIR"), child.get("DENO_DIR"));
            assertFalse(child.containsKey("JMUSICBOT_DISCORD_TOKEN"));
            assertTrue(Files.isDirectory(childCache));
            CookieFilePermissionPolicy.validateOwnerOnly(childCache, "unsafe cache permissions");

            Path providerCache = Files.createDirectories(
                    childCache.resolve("bgutil-ytdlp-pot-provider"));
            Files.writeString(providerCache.resolve("cache.json"),
                    "{\"po_token\":\"must-be-erased\"}", StandardCharsets.UTF_8);
        }
        finally
        {
            resolver.close();
        }

        assertNotNull(childCache);
        assertFalse(Files.exists(childCache));
    }
}
