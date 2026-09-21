package io.github.huaaudio.neomusicbot.audio.media;

import java.util.List;

/** A bounded set of stable entries returned by yt-dlp. */
public record MediaCollection(String name, List<MediaEntry> entries, boolean searchResult)
{
    public MediaCollection
    {
        name = name == null || name.isBlank() ? "Media" : name;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
