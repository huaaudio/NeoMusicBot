/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BilibiliPageSelectionTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void selectsTheRequestedPlaylistIndexInsteadOfTheFirstEntry() throws Exception
    {
        JsonNode root = JSON.readTree("""
                {"entries":[
                  {"id":"BV1ab411c7de","playlist_index":1,"title":"part one"},
                  {"id":"BV1ab411c7de","playlist_index":2,"title":"part two"}
                ]}
                """);
        JsonNode selected = YtDlpMediaResolver.selectRequestedEntry(root, 2);
        assertEquals("part two", selected.path("title").asText());
    }

    @Test
    public void doesNotSilentlyPlayAnotherPageWhenRequestedPageIsMissing() throws Exception
    {
        JsonNode root = JSON.readTree("{\"entries\":[{\"playlist_index\":1}]}");
        assertThrows(YtDlpException.class,
                () -> YtDlpMediaResolver.selectRequestedEntry(root, 3));
    }

    @Test
    public void rejectsMissingPageInASparseOrReorderedCollection() throws Exception
    {
        JsonNode root = JSON.readTree("{\"entries\":[{\"playlist_index\":2},{\"playlist_index\":3}]}");
        assertThrows(YtDlpException.class,
                () -> YtDlpMediaResolver.selectRequestedEntry(root, 1));
    }

    @Test
    public void preservesResolvedPageFromShortLinkOutput() throws Exception
    {
        JsonNode output = JSON.readTree("""
                {"extractor_key":"BiliBili", "id":"BV1ab411c7de_p3",
                 "webpage_url":"https://www.bilibili.com/video/BV1ab411c7de?p=3",
                 "original_url":"https://b23.tv/Ab_12"}
                """);
        MediaTrackKey key = YtDlpMediaResolver.keyFromNode(MediaSource.BILIBILI, output, null, null);
        assertEquals(Integer.valueOf(3), key.subIndex());
        assertEquals("https://www.bilibili.com/video/BV1ab411c7de?p=3", key.webUri().toString());
    }

    @Test
    public void preservesPageInExtractorIdWhenWebpageUrlIsAShortLink() throws Exception
    {
        JsonNode output = JSON.readTree("""
                {"extractor_key":"BiliBili", "id":"BV1ab411c7de_p2",
                 "webpage_url":"https://b23.tv/Ab_12"}
                """);
        assertEquals(Integer.valueOf(2), YtDlpMediaResolver.keyFromNode(
                MediaSource.BILIBILI, output, null, null).subIndex());
    }

    @Test
    public void rejectsOutputForADifferentRequestedPage() throws Exception
    {
        JsonNode output = JSON.readTree("""
                {"extractor_key":"BiliBili", "id":"BV1ab411c7de_p1",
                 "webpage_url":"https://www.bilibili.com/video/BV1ab411c7de?p=1"}
                """);
        assertThrows(YtDlpException.class, () -> YtDlpMediaResolver.keyFromNode(
                MediaSource.BILIBILI, output,
                new MediaTrackKey(MediaSource.BILIBILI, "BV1ab411c7de", 2), 2));
    }
}
