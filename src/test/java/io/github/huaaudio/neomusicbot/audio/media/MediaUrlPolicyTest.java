/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MediaUrlPolicyTest
{
    @Test
    public void acceptsOnlySupportedExtractorInputs()
    {
        assertEquals("https://www.bilibili.com/video/BV1ab411c7de?p=2",
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI,
                        "https://www.bilibili.com/video/BV1ab411c7de?p=2", 1));
        assertEquals("https://b23.tv/Ab_12",
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, "b23.tv/Ab_12", 1));
        assertEquals("https://www.bilibili.com/video/BV1ab411c7de",
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, "BV1ab411c7de", 1));
        assertEquals("https://www.bilibili.com/video/av123",
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, "AV123", 1));
        assertEquals("ytsearch5:--exec harmless because it is data after --",
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.YOUTUBE,
                        "ytsearch:--exec harmless because it is data after --", 5));
    }

    @Test
    public void preservesPercentEncodedUrlComponentsWithoutDoubleEncoding()
    {
        String url = "https://www.bilibili.com/video/BV1ab411c7de?p=%32&tracking=a%2Fb%26c#fragment";
        assertEquals(url.substring(0, url.indexOf('#')),
                MediaUrlPolicy.normalizeExtractorInput(MediaSource.BILIBILI, url, 1));
    }

    @Test
    public void rejectsSchemeHostAndControlCharacterInjection()
    {
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.YOUTUBE, "http://www.youtube.com/watch?v=dQw4w9WgXcQ", 1));
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.YOUTUBE, "https://evil.example/watch?v=dQw4w9WgXcQ", 1));
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.YOUTUBE, "ytsearch:hello\n--exec calc", 1));
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.BILIBILI, "BV1ab411c7de--exec", 1));
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.BILIBILI, "av123;--exec", 1));
        assertThrows(IllegalArgumentException.class, () -> MediaUrlPolicy.normalizeExtractorInput(
                MediaSource.BILIBILI, "BV123456789012345678901", 1));
    }

    @Test
    public void blocksPrivateAndReservedNetworkTargets() throws Exception
    {
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("10.0.0.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("100.64.0.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("192.0.2.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("198.51.100.1")));
        assertTrue(MediaUrlPolicy.isPublic(InetAddress.getByName("198.51.1.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("::1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("fc00::1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("2001:db8::1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("2001::1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("2002:0a00:0001::1")));
        assertTrue(MediaUrlPolicy.isPublic(InetAddress.getByName("2002:0101:0101::1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("64:ff9b::127.0.0.1")));
        assertTrue(MediaUrlPolicy.isPublic(InetAddress.getByName("64:ff9b::1.1.1.1")));
        assertFalse(MediaUrlPolicy.isPublic(InetAddress.getByName("64:ff9b:1::10.0.0.1")));

        byte[] mappedPrivate = new byte[16];
        mappedPrivate[10] = (byte) 0xff;
        mappedPrivate[11] = (byte) 0xff;
        mappedPrivate[12] = 10;
        mappedPrivate[15] = 1;
        assertFalse(MediaUrlPolicy.isPublic(Inet6Address.getByAddress(null, mappedPrivate, -1)));
        assertTrue(MediaUrlPolicy.isPublic(InetAddress.getByName("1.1.1.1")));
    }
}
