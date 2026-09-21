/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BilibiliIdentityValidationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void acceptsVerifiedAvToBvNormalization() throws Exception
    {
        JsonNode output = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "id": "BV1ab411c7de",
                  "webpage_url": "https://www.bilibili.com/video/BV1ab411c7de",
                  "original_url": "https://www.bilibili.com/video/av123"
                }
                """);

        MediaTrackKey key = YtDlpMediaResolver.keyFromNode(
                MediaSource.BILIBILI, output,
                new MediaTrackKey(MediaSource.BILIBILI, "av123", 2), 2);

        assertEquals("BV1ab411c7de", key.canonicalId());
        assertEquals(Integer.valueOf(2), key.subIndex());
    }

    @Test
    public void rejectsDifferentStableIdOfTheSameType() throws Exception
    {
        JsonNode differentBv = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "id": "BV1zz411c7ff",
                  "webpage_url": "https://www.bilibili.com/video/BV1zz411c7ff",
                  "original_url": "https://www.bilibili.com/video/BV1ab411c7de"
                }
                """);
        JsonNode differentAv = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "id": "av456",
                  "webpage_url": "https://www.bilibili.com/video/av456",
                  "original_url": "https://www.bilibili.com/video/av123"
                }
                """);

        assertInvalid(differentBv, new MediaTrackKey(MediaSource.BILIBILI, "BV1ab411c7de", null));
        assertInvalid(differentAv, new MediaTrackKey(MediaSource.BILIBILI, "av123", null));
    }

    @Test
    public void rejectsMissingOrUnlinkedConvertedIdentifiers() throws Exception
    {
        JsonNode missingOutputId = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "title": "No stable output identifier",
                  "original_url": "https://www.bilibili.com/video/av123"
                }
                """);
        JsonNode missingOriginalEvidence = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "id": "BV1ab411c7de",
                  "webpage_url": "https://www.bilibili.com/video/BV1ab411c7de"
                }
                """);
        JsonNode untrustedOriginalEvidence = JSON.readTree("""
                {
                  "extractor_key": "BiliBili",
                  "id": "BV1ab411c7de",
                  "webpage_url": "https://www.bilibili.com/video/BV1ab411c7de",
                  "original_url": "https://evil.example/watch/av123"
                }
                """);
        MediaTrackKey expected = new MediaTrackKey(MediaSource.BILIBILI, "av123", null);

        assertInvalid(missingOutputId, expected);
        assertInvalid(missingOriginalEvidence, expected);
        assertInvalid(untrustedOriginalEvidence, expected);
    }

    private static void assertInvalid(JsonNode output, MediaTrackKey expected)
    {
        YtDlpException failure = assertThrows(YtDlpException.class, () ->
                YtDlpMediaResolver.keyFromNode(MediaSource.BILIBILI, output, expected, expected.subIndex()));
        assertEquals(YtDlpException.Kind.INVALID_OUTPUT, failure.getKind());
    }
}
