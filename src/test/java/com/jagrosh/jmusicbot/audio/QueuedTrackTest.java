package com.jagrosh.jmusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.junit.jupiter.api.Test;

class QueuedTrackTest
{
    @Test
    void nullMetadataUsesTheSharedEmptyObjectEverywhere()
    {
        AudioTrack track = track();
        QueuedTrack queued = new QueuedTrack(track, null);

        assertSame(RequestMetadata.EMPTY, queued.getRequestMetadata());
        assertSame(RequestMetadata.EMPTY, track.getUserData(RequestMetadata.class));
        assertEquals(0L, queued.getIdentifier());
    }

    @Test
    void metadataWithoutRequestInfoDoesNotTryToReadAStartTimestamp()
    {
        AudioTrack track = track();
        RequestMetadata metadata = new RequestMetadata(null, null);

        QueuedTrack queued = new QueuedTrack(track, metadata);

        assertSame(metadata, queued.getRequestMetadata());
        assertSame(metadata, track.getUserData(RequestMetadata.class));
        assertEquals(0L, queued.getIdentifier());
        assertEquals(0L, track.getPosition());
    }

    private static AudioTrack track()
    {
        return new BaseAudioTrack(new AudioTrackInfo(
                "title", "author", 1_000L, "id", false, "https://example.invalid/audio"))
        {
            @Override
            public void process(LocalAudioTrackExecutor executor)
            {
            }
        };
    }
}
