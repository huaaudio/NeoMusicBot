/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.MediaEntry;
import io.github.huaaudio.neomusicbot.audio.media.MediaMetadata;
import io.github.huaaudio.neomusicbot.audio.media.MediaResolver;
import io.github.huaaudio.neomusicbot.audio.media.MediaSource;
import io.github.huaaudio.neomusicbot.audio.media.MediaTrackKey;
import io.github.huaaudio.neomusicbot.audio.media.ResolvedMedia;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YtDlpTrackSerializationTest
{
    @Test
    public void serializesOnlyTheStableKey() throws Exception
    {
        MediaTrackKey key = new MediaTrackKey(MediaSource.BILIBILI, "BV1ab411c7de", 2);
        MediaResolver resolver = new MediaResolver()
        {
            @Override
            public MediaMetadata resolveMetadata(MediaTrackKey ignored)
            {
                throw new AssertionError("Metadata should not be resolved during serialization");
            }

            @Override
            public ResolvedMedia resolveStream(MediaTrackKey ignored)
            {
                return new ResolvedMedia(
                        URI.create("https://cdn.example/audio.m4s?token=do-not-serialize"), Map.of(),
                        Instant.now().plusSeconds(60), "30280", false);
            }
        };
        DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();
        try
        {
            YtDlpAudioSourceManager source = new YtDlpAudioSourceManager(
                    "bilibili-test", MediaSource.BILIBILI, resolver, playerManager);
            AudioTrack track = source.createTrack(new MediaEntry(key,
                    new MediaMetadata("title", "author", 1_000, key.webUri(), null, false)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            source.encodeTrack(track, new DataOutputStream(bytes));
            String encoded = bytes.toString(StandardCharsets.ISO_8859_1);
            assertTrue(encoded.contains("BV1ab411c7de"));
            assertFalse(encoded.contains("do-not-serialize"));
            assertFalse(encoded.contains("cdn.example"));
        }
        finally
        {
            playerManager.shutdown();
        }
    }
}
