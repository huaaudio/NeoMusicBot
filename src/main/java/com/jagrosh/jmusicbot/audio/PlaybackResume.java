package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/** Applies an explicit seek when a failed short-lived stream is replaced. */
final class PlaybackResume
{
    private PlaybackResume()
    {
    }

    static void apply(LocalAudioTrackExecutor executor, long position,
                      boolean replacementSeekable, boolean live)
    {
        if (position > 0 && replacementSeekable && !live)
            executor.setPosition(position);
    }
}
