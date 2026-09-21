package io.github.huaaudio.neomusicbot.audio.media;

import java.nio.file.Path;
import java.time.Duration;

/** Immutable process-level configuration for yt-dlp. */
public record YtDlpConfiguration(
        String executable,
        String denoExecutable,
        Path youtubeCookies,
        Path bilibiliCookies,
        Path pluginDirectory,
        Path potProviderHome,
        boolean youtubeFallbackEnabled,
        boolean potProviderEnabled,
        Duration itemTimeout,
        Duration collectionTimeout)
{
    public YtDlpConfiguration
    {
        executable = safeExecutable(executable, "yt-dlp");
        denoExecutable = safeExecutable(denoExecutable, "deno");
        itemTimeout = positive(itemTimeout, Duration.ofSeconds(30));
        collectionTimeout = positive(collectionTimeout, Duration.ofSeconds(60));
        validateExtractorPath(pluginDirectory, "plugin directory");
        validateExtractorPath(potProviderHome, "PO token provider home");
    }

    private static String safeExecutable(String value, String fallback)
    {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (result.indexOf('\0') >= 0 || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid executable path");
        return result;
    }

    private static Duration positive(Duration value, Duration fallback)
    {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static void validateExtractorPath(Path path, String name)
    {
        if (path == null)
            return;
        String value = path.toString();
        if (value.indexOf(';') >= 0 || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid " + name);
    }
}
