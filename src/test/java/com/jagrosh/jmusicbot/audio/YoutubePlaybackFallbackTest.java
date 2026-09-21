/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YoutubePlaybackFallbackTest
{
    @Test
    public void playbackSwitchIsLimitedToExpiredStreamStatuses() throws Exception
    {
        Method classifier = YoutubeFallbackAudioTrack.class
                .getDeclaredMethod("isRefreshableStatus", Throwable.class);
        classifier.setAccessible(true);
        assertTrue((boolean) classifier.invoke(null, new IllegalStateException("HTTP status 403")));
        assertTrue((boolean) classifier.invoke(null, new IllegalStateException("Response code: 410")));
        assertFalse((boolean) classifier.invoke(null, new IllegalStateException("HTTP status 404")));
        assertFalse((boolean) classifier.invoke(null, new IllegalStateException("decoder failed")));
    }

    @Test
    public void fallbackRefreshesOnceAndSecondOpenCanSucceed() throws Exception
    {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();

        YoutubeFallbackAudioTrack.processFallbackWithSingleRefresh(() ->
        {
            if (opens.incrementAndGet() == 1)
                throw new IllegalStateException("HTTP status 403");
        }, invalidations::incrementAndGet);

        assertEquals(2, opens.get());
        assertEquals(1, invalidations.get());
    }

    @Test
    public void fallbackSecondFailureIsTerminal()
    {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                YoutubeFallbackAudioTrack.processFallbackWithSingleRefresh(() ->
                {
                    int attempt = opens.incrementAndGet();
                    throw new IllegalStateException("HTTP status 410 attempt " + attempt);
                }, invalidations::incrementAndGet));

        assertEquals("HTTP status 410 attempt 2", failure.getMessage());
        assertEquals(2, opens.get());
        assertEquals(1, invalidations.get());
    }

    @Test
    public void fallbackDoesNotRefreshExtractorFailures()
    {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () ->
                YoutubeFallbackAudioTrack.processFallbackWithSingleRefresh(() ->
                {
                    opens.incrementAndGet();
                    throw new IllegalStateException("signature decipher failed");
                }, invalidations::incrementAndGet));

        assertEquals(1, opens.get());
        assertEquals(0, invalidations.get());
    }

    @Test
    public void playbackPrimaryFailuresUseTheFullFallbackPolicy()
    {
        assertTrue(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("signature decipher failed")));
        assertTrue(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("no playable formats found")));
        assertTrue(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("unexpected internal client parser state")));
        assertFalse(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("This is a private video")));
        assertFalse(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("HTTP 404 not found")));
        assertFalse(YoutubeFallbackAudioTrack.shouldSwitchFromPrimary(
                new IllegalStateException("login required")));
    }
}
