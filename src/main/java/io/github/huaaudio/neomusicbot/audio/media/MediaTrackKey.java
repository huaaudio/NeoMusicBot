package io.github.huaaudio.neomusicbot.audio.media;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A durable media identifier.  This is the only resolver state which may be
 * written into a Lavaplayer track; signed CDN URLs must never be serialized.
 */
public record MediaTrackKey(MediaSource source, String canonicalId, Integer subIndex)
{
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern BILIBILI_ID = Pattern.compile("(?:BV[A-Za-z0-9]{8,20}|av[0-9]{1,20})", Pattern.CASE_INSENSITIVE);

    public MediaTrackKey
    {
        source = Objects.requireNonNull(source, "source");
        canonicalId = Objects.requireNonNull(canonicalId, "canonicalId").trim();
        if (canonicalId.length() > 64)
            throw new IllegalArgumentException("Media identifier is too long");

        if (source == MediaSource.YOUTUBE && !YOUTUBE_ID.matcher(canonicalId).matches())
            throw new IllegalArgumentException("Invalid YouTube video identifier");
        if (source == MediaSource.BILIBILI && !BILIBILI_ID.matcher(canonicalId).matches())
            throw new IllegalArgumentException("Invalid Bilibili video identifier");
        if (source == MediaSource.YOUTUBE && subIndex != null)
            throw new IllegalArgumentException("YouTube tracks do not have a Bilibili page index");
        if (subIndex != null && subIndex < 1)
            throw new IllegalArgumentException("Page index must be positive");
    }

    public URI webUri()
    {
        return switch (source)
        {
            case YOUTUBE -> URI.create("https://www.youtube.com/watch?v=" + canonicalId);
            case BILIBILI -> URI.create("https://www.bilibili.com/video/" + canonicalId
                    + (subIndex == null ? "" : "?p=" + subIndex));
        };
    }

    public String serializedId()
    {
        return source.name().toLowerCase(Locale.ROOT) + ':' + canonicalId
                + (subIndex == null ? "" : ":" + subIndex);
    }
}
