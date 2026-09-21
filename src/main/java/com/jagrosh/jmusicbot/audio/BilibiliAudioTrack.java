/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 */
package com.jagrosh.jmusicbot.audio;

/**
 * Compatibility marker retained for source-level consumers of the old fork.
 * Bilibili playback now uses {@link YtDlpAudioTrack}, which stores only a
 * stable BV/av/page key and resolves the signed stream immediately before use.
 */
@Deprecated(forRemoval = true)
public final class BilibiliAudioTrack
{
    private BilibiliAudioTrack()
    {
    }
}
