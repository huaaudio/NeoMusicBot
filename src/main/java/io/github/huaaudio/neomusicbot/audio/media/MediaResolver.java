package io.github.huaaudio.neomusicbot.audio.media;

/** Resolves stable media keys into metadata and short-lived stream URLs. */
public interface MediaResolver
{
    MediaMetadata resolveMetadata(MediaTrackKey key);

    ResolvedMedia resolveStream(MediaTrackKey key);

    default void invalidate(MediaTrackKey key)
    {
    }
}
