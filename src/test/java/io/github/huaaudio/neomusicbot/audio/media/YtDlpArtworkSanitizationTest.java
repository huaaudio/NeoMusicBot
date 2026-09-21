/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class YtDlpArtworkSanitizationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void stripsSignedQueryAndFragmentBeforeMetadataSerialization() throws Exception
    {
        URI artwork = YtDlpMediaResolver.safeArtworkUri(JSON.readTree("""
                {"thumbnail":"https://i.example/images/cover.jpg?expire=1&sig=secret#account"}
                """));

        assertEquals("https://i.example/images/cover.jpg", artwork.toString());
        assertNull(artwork.getRawQuery());
        assertNull(artwork.getFragment());
    }

    @Test
    public void rejectsArtworkUserInfoInsecureSchemeAndUnexpectedPort() throws Exception
    {
        assertNull(YtDlpMediaResolver.safeArtworkUri(JSON.readTree(
                "{\"thumbnail\":\"https://user:secret@i.example/cover.jpg\"}")));
        assertNull(YtDlpMediaResolver.safeArtworkUri(JSON.readTree(
                "{\"thumbnail\":\"http://i.example/cover.jpg\"}")));
        assertNull(YtDlpMediaResolver.safeArtworkUri(JSON.readTree(
                "{\"thumbnail\":\"https://i.example:444/cover.jpg\"}")));
    }
}
