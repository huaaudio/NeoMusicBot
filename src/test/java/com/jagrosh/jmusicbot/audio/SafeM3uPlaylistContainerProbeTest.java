package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.ThreadLocalHttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SafeM3uPlaylistContainerProbeTest
{
    @Test
    public void acceptsMasterAndDirectMediaHlsPlaylists() throws Exception
    {
        try (ThreadLocalHttpInterfaceManager manager =
                     YtDlpAudioSourceManager.createSecureHttpInterfaceManager(List.of()))
        {
            SafeM3uPlaylistContainerProbe probe = new SafeM3uPlaylistContainerProbe(manager);
            MediaContainerDetectionResult master = probe.probe(reference(), input(
                    "#EXTM3U\n"
                            + "#EXT-X-STREAM-INF:BANDWIDTH=128000\n"
                            + "https://1.1.1.1/audio/index.m3u8\n"));
            MediaContainerDetectionResult media = probe.probe(reference(), input(
                    "#EXTM3U\n"
                            + "#EXT-X-TARGETDURATION:6\n"
                            + "#EXT-X-MEDIA-SEQUENCE:0\n"
                            + "#EXTINF:6.0,\n"
                            + "https://1.1.1.1/audio/segment-0.ts\n"
                            + "#EXT-X-ENDLIST\n"));

            assertTrue(master.isSupportedFile());
            assertFalse(master.isReference());
            assertTrue(media.isSupportedFile());
            assertFalse(media.isReference());
        }
    }

    @Test
    public void rejectsOrdinaryExtendedM3uWithoutFollowingItsEntry() throws Exception
    {
        try (ThreadLocalHttpInterfaceManager manager =
                     YtDlpAudioSourceManager.createSecureHttpInterfaceManager(List.of()))
        {
            SafeM3uPlaylistContainerProbe probe = new SafeM3uPlaylistContainerProbe(manager);
            MediaContainerDetectionResult result = probe.probe(reference(), input(
                    "#EXTM3U\n#EXTINF:10,untrusted\nhttps://127.0.0.1/private\n"));

            assertFalse(result.isSupportedFile());
            assertFalse(result.isReference());
        }
    }

    private static AudioReference reference()
    {
        return new AudioReference("https://1.1.1.1/audio/index.m3u8", "test");
    }

    private static SeekableInputStream input(String content)
    {
        return new MemorySeekableInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MemorySeekableInputStream extends SeekableInputStream
    {
        private final byte[] data;
        private int position;

        private MemorySeekableInputStream(byte[] data)
        {
            super(data.length, data.length);
            this.data = data;
        }

        @Override
        public int read()
        {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length)
        {
            if (position >= data.length)
                return -1;
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, target, offset, count);
            position += count;
            return count;
        }

        @Override
        public long getPosition()
        {
            return position;
        }

        @Override
        protected void seekHard(long position) throws IOException
        {
            if (position < 0 || position > data.length)
                throw new IOException("Invalid in-memory seek");
            this.position = (int) position;
        }

        @Override
        public boolean canSeekHard()
        {
            return true;
        }

        @Override
        public List<AudioTrackInfoProvider> getTrackInfoProviders()
        {
            return List.of();
        }
    }
}
