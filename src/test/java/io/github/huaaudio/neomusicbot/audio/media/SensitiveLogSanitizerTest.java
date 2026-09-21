/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SensitiveLogSanitizerTest
{
    @Test
    public void stripsUrlsHeadersTokensAndStandaloneSecrets()
    {
        String discordToken = "ABCDEFGHIJKLMNOPQRSTUVWX.abcdef.abcdefghijklmnopqrstuvwxyz123456";
        String sanitized = SensitiveLogSanitizer.sanitize(
                "GET https://user:URL_PASSWORD@rr.example/videoplayback?expire=123&sig=MEDIA_QUERY_SECRET\n"
                        + "Authorization: Custom scheme AUTH SECRET status=AUTH_FIELD_STYLE\n"
                        + "Cookie: \"SID=COOKIE ONE; HSID=COOKIE_TWO; SESSDATA=COOKIE_THREE\" request_id=hidden\n"
                        + "status=403 request_id=kept\n"
                        + "SESSDATA=SESS_SECRET PO_TOKEN=PO_SECRET visitor_data=VISITOR_SECRET "
                        + "access_token=ACCESS_SECRET refresh-token=REFRESH_SECRET "
                        + "discord_token=DISCORD_FIELD_SECRET token " + discordToken);

        assertTrue(sanitized.contains("[url-redacted]"));
        assertTrue(sanitized.contains("status=403"));
        assertTrue(sanitized.contains("request_id=kept"));
        assertFalse(sanitized.contains("rr.example"));
        assertFalse(sanitized.contains("URL_PASSWORD"));
        assertFalse(sanitized.contains("videoplayback"));
        assertFalse(sanitized.contains("MEDIA_QUERY_SECRET"));
        assertFalse(sanitized.contains("AUTH SECRET"));
        assertFalse(sanitized.contains("AUTH_FIELD_STYLE"));
        assertFalse(sanitized.contains("COOKIE ONE"));
        assertFalse(sanitized.contains("COOKIE_TWO"));
        assertFalse(sanitized.contains("COOKIE_THREE"));
        assertFalse(sanitized.contains("request_id=hidden"));
        assertFalse(sanitized.contains("SESS_SECRET"));
        assertFalse(sanitized.contains("PO_SECRET"));
        assertFalse(sanitized.contains("VISITOR_SECRET"));
        assertFalse(sanitized.contains("ACCESS_SECRET"));
        assertFalse(sanitized.contains("REFRESH_SECRET"));
        assertFalse(sanitized.contains("DISCORD_FIELD_SECRET"));
        assertFalse(sanitized.contains(discordToken));
        assertTrue(sanitized.contains("Authorization: [redacted]"));
        assertTrue(sanitized.contains("Cookie: [redacted]"));
    }

    @Test
    public void stripsIndependentAccountIdentifiers()
    {
        String sanitized = SensitiveLogSanitizer.sanitize(
                "account=primary-account email: \"owner@example.com\" "
                        + "username='Named User' user-id=123456 channel_id=UC-secret "
                        + "contact backup@example.net status=failed");

        assertFalse(sanitized.contains("primary-account"));
        assertFalse(sanitized.contains("owner@example.com"));
        assertFalse(sanitized.contains("Named User"));
        assertFalse(sanitized.contains("123456"));
        assertFalse(sanitized.contains("UC-secret"));
        assertFalse(sanitized.contains("backup@example.net"));
        assertTrue(sanitized.contains("status=failed"));
        assertTrue(sanitized.contains("account=[redacted]"));
        assertTrue(sanitized.contains("email: [redacted]"));
    }
}
