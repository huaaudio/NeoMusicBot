package com.jagrosh.jmusicbot.audio.media;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** Maps the documented JMUSICBOT_* environment variables to resolver config. */
public final class MediaResolverEnvironment
{
    private MediaResolverEnvironment()
    {
    }

    public static YtDlpConfiguration load()
    {
        Path pluginDirectory = path("JMUSICBOT_YTDLP_PLUGIN_DIR");
        Path providerHome = path("JMUSICBOT_YOUTUBE_POT_PROVIDER_PATH");
        boolean providerDetected = MediaResolverReadiness.providerAssetsAvailable(
                pluginDirectory, providerHome);
        return new YtDlpConfiguration(
                value("JMUSICBOT_YTDLP_PATH", "yt-dlp"),
                value("JMUSICBOT_DENO_PATH", "deno"),
                path("JMUSICBOT_YOUTUBE_COOKIES_FILE"),
                path("JMUSICBOT_BILIBILI_COOKIES_FILE"),
                pluginDirectory,
                providerHome,
                autoOrOff("JMUSICBOT_YOUTUBE_FALLBACK", true),
                autoOrOff("JMUSICBOT_YOUTUBE_POT_PROVIDER", providerDetected),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60));
    }

    private static boolean autoOrOff(String name, boolean autoValue)
    {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("auto"))
            return autoValue;
        if (raw.trim().toLowerCase(Locale.ROOT).equals("off"))
            return false;
        throw new IllegalArgumentException(name + " must be auto or off");
    }

    private static String value(String name, String fallback)
    {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static Path path(String name)
    {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? null : Path.of(raw.trim()).toAbsolutePath().normalize();
    }
}
