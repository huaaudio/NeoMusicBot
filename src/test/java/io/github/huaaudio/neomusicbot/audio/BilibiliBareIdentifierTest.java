/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BilibiliBareIdentifierTest
{
    @Test
    public void detectsOnlyStrictBareBvAndAvIdentifiers()
    {
        assertTrue(BilibiliAudioSourceManager.looksLikeBilibili("BV1ab411c7de"));
        assertTrue(BilibiliAudioSourceManager.looksLikeBilibili("av123"));
        assertTrue(BilibiliAudioSourceManager.looksLikeBilibili("AV123"));

        assertFalse(BilibiliAudioSourceManager.looksLikeBilibili("BV1ab411c7de--exec"));
        assertFalse(BilibiliAudioSourceManager.looksLikeBilibili("av123;calc"));
        assertFalse(BilibiliAudioSourceManager.looksLikeBilibili("BV123456789012345678901"));
        assertFalse(BilibiliAudioSourceManager.looksLikeBilibili("--exec"));
    }
}
