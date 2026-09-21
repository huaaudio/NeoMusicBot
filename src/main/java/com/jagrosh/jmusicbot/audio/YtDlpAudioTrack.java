package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.media.MediaTrackKey;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.util.Locale;

/** A stable-key track which resolves its short-lived URL at playback time. */
public final class YtDlpAudioTrack extends DelegatedAudioTrack
{
    private final MediaTrackKey mediaKey;
    private final YtDlpAudioSourceManager sourceManager;

    YtDlpAudioTrack(AudioTrackInfo trackInfo, MediaTrackKey mediaKey, YtDlpAudioSourceManager sourceManager)
    {
        super(trackInfo);
        this.mediaKey = mediaKey;
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception
    {
        long resumePosition = 0;
        for (int attempt = 0; attempt < 2; attempt++)
        {
            try (YtDlpAudioSourceManager.PlaybackDelegate delegate = sourceManager.openPlaybackDelegate(mediaKey))
            {
                PlaybackResume.apply(executor, resumePosition, delegate.track().isSeekable(), trackInfo.isStream);
                processDelegate(delegate.track(), executor);
                return;
            }
            catch (Exception ex)
            {
                if (attempt == 0 && isExpiredStreamResponse(ex))
                {
                    resumePosition = executor.getPosition();
                    sourceManager.invalidate(mediaKey);
                    continue;
                }
                throw ex;
            }
        }
    }

    private static boolean isExpiredStreamResponse(Throwable throwable)
    {
        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            String message = current.getMessage();
            if (message == null)
                continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.matches("(?s).*\\b(?:403|410)\\b.*"))
                return true;
        }
        return false;
    }

    @Override
    protected AudioTrack makeShallowClone()
    {
        return new YtDlpAudioTrack(trackInfo, mediaKey, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager()
    {
        return sourceManager;
    }

    public MediaTrackKey getMediaKey()
    {
        return mediaKey;
    }
}
