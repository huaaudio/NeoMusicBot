package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.media.MediaSource;
import com.jagrosh.jmusicbot.audio.media.MediaTrackKey;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.util.Locale;

/**
 * Wraps a youtube-source track so eligible playback-time extractor failures
 * can switch to yt-dlp without restarting the guild player or losing its
 * executor position.
 */
final class YoutubeFallbackAudioTrack extends DelegatedAudioTrack
{
    private final InternalAudioTrack primaryTrack;
    private final YoutubeFallbackAudioSourceManager sourceManager;

    YoutubeFallbackAudioTrack(AudioTrackInfo trackInfo, InternalAudioTrack primaryTrack,
                              YoutubeFallbackAudioSourceManager sourceManager)
    {
        super(trackInfo);
        this.primaryTrack = primaryTrack;
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception
    {
        try
        {
            processDelegate(primaryTrack, executor);
            return;
        }
        catch (Exception primaryFailure)
        {
            if (!shouldSwitchFromPrimary(primaryFailure))
                throw primaryFailure;
        }

        MediaTrackKey key = sourceManager.keyForTrack(trackInfo);
        long[] resumePosition = {executor.getPosition()};
        processFallbackWithSingleRefresh(() ->
        {
            try (YtDlpAudioSourceManager.PlaybackDelegate fallback = sourceManager.openFallback(key))
            {
                PlaybackResume.apply(executor, resumePosition[0],
                        fallback.track().isSeekable(), trackInfo.isStream);
                try
                {
                    processDelegate(fallback.track(), executor);
                }
                catch (Exception failure)
                {
                    resumePosition[0] = executor.getPosition();
                    throw failure;
                }
            }
        }, () -> sourceManager.invalidateFallback(key));
    }

    static boolean shouldSwitchFromPrimary(Throwable failure)
    {
        return YoutubeFallbackAudioSourceManager.shouldFallback(failure);
    }

    static void processFallbackWithSingleRefresh(FallbackAttempt fallbackAttempt,
                                                 Runnable invalidate) throws Exception
    {
        for (int attempt = 0; attempt < 2; attempt++)
        {
            try
            {
                fallbackAttempt.openAndProcess();
                return;
            }
            catch (Exception failure)
            {
                if (attempt == 0 && isRefreshableStatus(failure))
                {
                    invalidate.run();
                    continue;
                }
                throw failure;
            }
        }
    }

    private static boolean isRefreshableStatus(Throwable throwable)
    {
        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:403|410)\\b.*"))
                return true;
        }
        return false;
    }

    InternalAudioTrack getPrimaryTrack()
    {
        return primaryTrack;
    }

    @Override
    protected AudioTrack makeShallowClone()
    {
        AudioTrack cloned = primaryTrack.makeClone();
        if (!(cloned instanceof InternalAudioTrack internal))
            throw new IllegalStateException("youtube-source returned a non-local track clone");
        return new YoutubeFallbackAudioTrack(trackInfo, internal, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager()
    {
        return sourceManager;
    }

    @Override
    public boolean isSeekable()
    {
        return primaryTrack.isSeekable();
    }

    @FunctionalInterface
    interface FallbackAttempt
    {
        void openAndProcess() throws Exception;
    }
}
