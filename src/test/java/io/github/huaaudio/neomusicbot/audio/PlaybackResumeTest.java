/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlaybackResumeTest
{
    @Test
    public void replacementQueuesCapturedPositionOnTheSharedExecutor()
    {
        LocalAudioTrackExecutor executor = executor(false);

        PlaybackResume.apply(executor, 42_500, true, false);

        assertEquals(42_500, executor.getPosition());
    }

    @Test
    public void liveAndNonSeekableReplacementsNeverQueueResumeSeek()
    {
        LocalAudioTrackExecutor live = executor(false);
        LocalAudioTrackExecutor nonSeekable = executor(false);

        PlaybackResume.apply(live, 42_500, true, true);
        PlaybackResume.apply(nonSeekable, 42_500, false, false);

        assertEquals(0, live.getPosition());
        assertEquals(0, nonSeekable.getPosition());
    }

    private static LocalAudioTrackExecutor executor(boolean stream)
    {
        AudioTrackInfo info = new AudioTrackInfo(
                "test", "test", 60_000, "resume-test", stream,
                "https://example.invalid", null, null);
        BaseAudioTrack track = new BaseAudioTrack(info)
        {
            @Override
            public void process(LocalAudioTrackExecutor executor)
            {
            }
        };
        return new LocalAudioTrackExecutor(track, new AudioConfiguration(),
                new AudioPlayerOptions(), false, 400);
    }
}
