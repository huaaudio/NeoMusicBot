package io.github.huaaudio.neomusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamTrack;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection.checkNextBytes;
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.supportedFormat;
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.unsupportedFormat;

/**
 * HLS-only M3U probe. Unlike Lavaplayer's default probe it never emits an
 * AudioReference, and every manifest/quality/segment request uses the supplied
 * filtered interface manager with the resolver's required headers.
 */
final class SafeM3uPlaylistContainerProbe implements MediaContainerProbe
{
    private static final String TYPE_HLS_OUTER = "hls-outer";
    private static final int[] M3U_HEADER_TAG = new int[]{'#', 'E', 'X', 'T', 'M', '3', 'U'};
    private static final int[] M3U_ENTRY_TAG = new int[]{'#', 'E', 'X', 'T', 'I', 'N', 'F'};
    private static final Set<String> HLS_DIRECTIVES = Set.of(
            "EXT-X-VERSION", "EXT-X-TARGETDURATION", "EXT-X-MEDIA-SEQUENCE",
            "EXT-X-DISCONTINUITY-SEQUENCE", "EXT-X-ENDLIST", "EXT-X-PLAYLIST-TYPE",
            "EXT-X-I-FRAMES-ONLY", "EXT-X-KEY", "EXT-X-MAP", "EXT-X-BYTERANGE",
            "EXT-X-DISCONTINUITY", "EXT-X-PROGRAM-DATE-TIME", "EXT-X-DATERANGE",
            "EXT-X-GAP", "EXT-X-BITRATE", "EXT-X-PART-INF", "EXT-X-SERVER-CONTROL",
            "EXT-X-PART", "EXT-X-PRELOAD-HINT", "EXT-X-RENDITION-REPORT", "EXT-X-SKIP",
            "EXT-X-MEDIA", "EXT-X-STREAM-INF", "EXT-X-I-FRAME-STREAM-INF",
            "EXT-X-SESSION-DATA", "EXT-X-SESSION-KEY", "EXT-X-INDEPENDENT-SEGMENTS",
            "EXT-X-START", "EXT-X-DEFINE", "EXT-X-CONTENT-STEERING");

    private final HttpInterfaceManager httpInterfaceManager;

    SafeM3uPlaylistContainerProbe(HttpInterfaceManager httpInterfaceManager)
    {
        this.httpInterfaceManager = Objects.requireNonNull(httpInterfaceManager, "httpInterfaceManager");
    }

    @Override
    public String getName()
    {
        return "m3u";
    }

    @Override
    public boolean matchesHints(MediaContainerHints hints)
    {
        return false;
    }

    @Override
    public MediaContainerDetectionResult probe(AudioReference reference,
                                               SeekableInputStream inputStream) throws IOException
    {
        if (!checkNextBytes(inputStream, M3U_HEADER_TAG) && !checkNextBytes(inputStream, M3U_ENTRY_TAG))
            return null;

        String[] lines = DataFormatTools.streamToLines(inputStream, StandardCharsets.UTF_8);
        if (!containsHlsDirective(lines))
            return unsupportedFormat(this, "Only HLS M3U media is accepted");

        AudioReference httpReference = HttpAudioSourceManager.getAsHttpReference(reference);
        if (httpReference == null)
            return unsupportedFormat(this, "HLS media requires an HTTPS source URL");

        AudioTrackInfo trackInfo = AudioTrackInfoBuilder.create(reference, inputStream)
                .setIdentifier(httpReference.identifier)
                .build();
        return supportedFormat(this, TYPE_HLS_OUTER, trackInfo);
    }

    private static boolean containsHlsDirective(String[] lines)
    {
        for (String line : lines)
        {
            String directive = line == null ? "" : line.trim();
            if (!directive.startsWith("#"))
                continue;
            int colon = directive.indexOf(':');
            String name = directive.substring(1, colon < 0 ? directive.length() : colon);
            if (HLS_DIRECTIVES.contains(name))
                return true;
        }
        return false;
    }

    @Override
    public AudioTrack createTrack(String parameters, AudioTrackInfo trackInfo,
                                  SeekableInputStream inputStream)
    {
        if (!TYPE_HLS_OUTER.equals(parameters))
            throw new IllegalArgumentException("Unsupported safe HLS parameters");
        return new HlsStreamTrack(trackInfo, trackInfo.identifier, httpInterfaceManager, false);
    }
}
