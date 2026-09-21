package com.jagrosh.jmusicbot.audio.media;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A short-lived, validated stream address returned immediately before playback. */
public record ResolvedMedia(
        URI uri,
        Map<String, String> headers,
        Instant expiresAt,
        String formatId,
        boolean live)
{
    public ResolvedMedia
    {
        uri = Objects.requireNonNull(uri, "uri");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        formatId = formatId == null ? "unknown" : formatId;
    }
}
