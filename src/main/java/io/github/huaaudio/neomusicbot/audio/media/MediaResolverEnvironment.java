package io.github.huaaudio.neomusicbot.audio.media;

import io.github.huaaudio.neomusicbot.BotEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** Maps the documented NEOMUSICBOT_* environment variables to resolver config. */
public final class MediaResolverEnvironment
{
    private MediaResolverEnvironment()
    {
    }

    public static YtDlpConfiguration load()
    {
        Path pluginDirectory = path("NEOMUSICBOT_YTDLP_PLUGIN_DIR");
        Path providerHome = path("NEOMUSICBOT_YOUTUBE_POT_PROVIDER_PATH");
        boolean providerDetected = MediaResolverReadiness.providerAssetsAvailable(
                pluginDirectory, providerHome);
        return new YtDlpConfiguration(
                value("NEOMUSICBOT_YTDLP_PATH", "yt-dlp"),
                value("NEOMUSICBOT_DENO_PATH", "deno"),
                path("NEOMUSICBOT_YOUTUBE_COOKIES_FILE"),
                path("NEOMUSICBOT_BILIBILI_COOKIES_FILE"),
                pluginDirectory,
                providerHome,
                autoOrOff("NEOMUSICBOT_YOUTUBE_FALLBACK", true),
                autoOrOff("NEOMUSICBOT_YOUTUBE_POT_PROVIDER", providerDetected),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60));
    }

    private static boolean autoOrOff(String name, boolean autoValue)
    {
        String raw = BotEnvironment.value(name);
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("auto"))
            return autoValue;
        if (raw.trim().toLowerCase(Locale.ROOT).equals("off"))
            return false;
        throw new IllegalArgumentException(name + " must be auto or off");
    }

    private static String value(String name, String fallback)
    {
        String raw = BotEnvironment.value(name);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static Path path(String name)
    {
        String raw = BotEnvironment.value(name);
        return raw == null || raw.isBlank() ? null : Path.of(raw.trim()).toAbsolutePath().normalize();
    }
}
