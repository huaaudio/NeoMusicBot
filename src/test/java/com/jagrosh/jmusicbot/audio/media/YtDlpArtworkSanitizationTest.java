package com.jagrosh.jmusicbot.audio.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
