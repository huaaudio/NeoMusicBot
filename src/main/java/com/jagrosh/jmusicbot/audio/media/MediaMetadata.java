package com.jagrosh.jmusicbot.audio.media;

import java.net.URI;
import java.util.Objects;

/** Metadata which is safe to keep with a queued track. */
public record MediaMetadata(
        String title,
        String author,
        long durationMillis,
        URI webUri,
        URI artworkUri,
        boolean live)
{
    public MediaMetadata
    {
        title = normalize(title, "Unknown title", 512);
        author = normalize(author, "Unknown uploader", 256);
        if (durationMillis < 0)
            durationMillis = 0;
        webUri = Objects.requireNonNull(webUri, "webUri");
    }

    private static String normalize(String value, String fallback, int maximumLength)
    {
        if (value == null || value.isBlank())
            return fallback;
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
