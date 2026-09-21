/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.media.YtDlpConfiguration;
import com.jagrosh.jmusicbot.audio.media.YtDlpMediaResolver;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YoutubeFallbackAudioSourceManagerTest
{
    @Test
    public void successfulPrimaryNeverInvokesTheFallbackExecutable()
    {
        AtomicInteger primaryCalls = new AtomicInteger();
        AudioReference primaryResult = new AudioReference("primary-success", "primary");
        YoutubeAudioSourceManager primary = new YoutubeAudioSourceManager()
        {
            @Override
            public AudioItem loadItem(com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager manager,
                                      AudioReference reference)
            {
                primaryCalls.incrementAndGet();
                return primaryResult;
            }
        };
        YtDlpMediaResolver resolver = new YtDlpMediaResolver(new YtDlpConfiguration(
                "definitely-missing-yt-dlp-test-executable", "deno", null, null, null, null,
                true, false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        YoutubeFallbackAudioSourceManager source = new YoutubeFallbackAudioSourceManager(
                primary, new YtDlpAudioSourceManager("youtube-fallback-test",
                com.jagrosh.jmusicbot.audio.media.MediaSource.YOUTUBE, resolver, manager),
                resolver, 5);
        try
        {
            AudioItem result = source.loadItem(manager, new AudioReference(
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null));
            assertSame(primaryResult, result);
            assertEquals(1, primaryCalls.get());
        }
        finally
        {
            source.shutdown();
            resolver.close();
            manager.shutdown();
        }
    }

    @Test
    public void primaryNoTrackNeverInvokesTheFallbackExecutable()
    {
        AtomicInteger primaryCalls = new AtomicInteger();
        YoutubeAudioSourceManager primary = new YoutubeAudioSourceManager()
        {
            @Override
            public AudioItem loadItem(com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager manager,
                                      AudioReference reference)
            {
                primaryCalls.incrementAndGet();
                return AudioReference.NO_TRACK;
            }
        };
        YtDlpMediaResolver resolver = new YtDlpMediaResolver(new YtDlpConfiguration(
                "definitely-missing-yt-dlp-test-executable", "deno", null, null, null, null,
                true, false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        YoutubeFallbackAudioSourceManager source = new YoutubeFallbackAudioSourceManager(
                primary, new YtDlpAudioSourceManager("youtube-fallback-test",
                com.jagrosh.jmusicbot.audio.media.MediaSource.YOUTUBE, resolver, manager),
                resolver, 5);
        try
        {
            AudioItem result = source.loadItem(manager, new AudioReference(
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null));
            assertSame(AudioReference.NO_TRACK, result);
            assertEquals(1, primaryCalls.get());
        }
        finally
        {
            source.shutdown();
            resolver.close();
            manager.shutdown();
        }
    }

    @Test
    public void doesNotRetryPermanentAccessFailures()
    {
        assertFalse(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("This is a private video", COMMON, null)));
        assertFalse(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Video not available in your country", FAULT, null)));
    }

    @Test
    public void retriesTransportAndExtractorFailures()
    {
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Server returned status 403", COMMON, null)));
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Signature decipher failed", COMMON, null)));
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Unexpected player failure", FAULT, null)));
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Restricted client is unavailable", COMMON, null)));
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("No supported clients remain", COMMON, null)));
        assertTrue(YoutubeFallbackAudioSourceManager.shouldFallback(
                new FriendlyException("Client unavailable", COMMON, null)));
    }
}
