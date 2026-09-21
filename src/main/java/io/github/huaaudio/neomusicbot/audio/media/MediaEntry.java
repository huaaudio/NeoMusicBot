package io.github.huaaudio.neomusicbot.audio.media;

import java.util.Objects;

/** One stable item returned by a URL, search, or playlist resolution. */
public record MediaEntry(MediaTrackKey key, MediaMetadata metadata)
{
    public MediaEntry
    {
        key = Objects.requireNonNull(key, "key");
        metadata = Objects.requireNonNull(metadata, "metadata");
    }
}
