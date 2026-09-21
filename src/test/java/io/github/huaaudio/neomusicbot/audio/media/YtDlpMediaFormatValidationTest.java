/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class YtDlpMediaFormatValidationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void acceptsSupportedDirectAudioAndHlsFormats() throws Exception
    {
        ResolvedMedia direct = YtDlpMediaResolver.mediaFromNode(format(
                "https", "webm", "opus", "https://1.1.1.1/audio.webm"));
        ResolvedMedia hls = YtDlpMediaResolver.mediaFromNode(format(
                "m3u8_native", "mp4", "mp4a.40.2", "https://1.1.1.1/audio/index.m3u8"));
        ResolvedMedia matroska = YtDlpMediaResolver.mediaFromNode(format(
                "https", "mka", "opus", "https://1.1.1.1/audio.mka"));
        ResolvedMedia transportStream = YtDlpMediaResolver.mediaFromNode(format(
                "https", "ts", "aac", "https://1.1.1.1/audio.ts"));

        assertEquals("https", direct.uri().getScheme());
        assertEquals("https", hls.uri().getScheme());
        assertEquals("https", matroska.uri().getScheme());
        assertEquals("https", transportStream.uri().getScheme());
    }

    @Test
    public void rejectsUnknownOrNonAudioExtractorFormats() throws Exception
    {
        assertInvalid(format("f4m", "flv", "aac", "https://1.1.1.1/manifest.f4m"));
        assertInvalid(format("https", "mhtml", "opus", "https://1.1.1.1/archive.mhtml"));
        assertInvalid(format("rtmp", "mp4", "aac", "https://1.1.1.1/audio.mp4"));
        assertInvalid(format("https", "webm", "none", "https://1.1.1.1/audio.webm"));
        assertInvalid(JSON.readTree("""
                {"url":"https://1.1.1.1/muxed.mp4","protocol":"https","ext":"mp4",
                 "acodec":"aac","vcodec":"h264"}
                """));
        assertInvalid(JSON.readTree("""
                {"url":"https://1.1.1.1/audio.webm","protocol":"https","ext":"webm"}
                """));
    }

    private static JsonNode format(String protocol, String extension,
                                   String codec, String url) throws Exception
    {
        return JSON.readTree("{\"url\":\"" + url + "\",\"protocol\":\"" + protocol
                + "\",\"ext\":\"" + extension + "\",\"acodec\":\"" + codec
                + "\",\"format_id\":\"test\"}");
    }

    private static void assertInvalid(JsonNode node)
    {
        YtDlpException failure = assertThrows(YtDlpException.class,
                () -> YtDlpMediaResolver.mediaFromNode(node));
        assertEquals(YtDlpException.Kind.INVALID_OUTPUT, failure.getKind());
    }
}
