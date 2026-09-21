/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YtDlpFailureClassificationTest
{
    @Test
    public void botChecksCanRetryWithPotProvider()
    {
        assertPotRetry("ERROR: Sign in to confirm you're not a bot");
        assertPotRetry("ERROR: Sign in to confirm you’re not a bot");
        assertPotRetry("ERROR: YouTube detected unusual traffic from this network");
        assertPotRetry("ERROR: JavaScript challenge failed");
        assertPotRetry("ERROR: HTTP Error 403: Forbidden");
        assertPotRetry("ERROR: No video formats found");
        assertPotRetry("ERROR: PO Token is required for this client");
    }

    @Test
    public void genuineAccessRestrictionsDoNotRetryWithPotProvider()
    {
        assertNoPotRetry("ERROR: Private video. Sign in if you've been granted access");
        assertNoPotRetry("ERROR: Login required to view this video");
        assertNoPotRetry("ERROR: This video is age-restricted. Sign in to confirm your age");
        assertNoPotRetry("ERROR: This video is members-only");
    }

    @Test
    public void unknownInfrastructureFailuresNeverStartPotProvider()
    {
        for (String diagnostic : new String[]{
                "ERROR: TLS certificate verification failed",
                "ERROR: No space left on device",
                "ERROR: Plugin crashed with unexpected exception"})
        {
            YtDlpException failure = YtDlpMediaResolver.classifyFailure(diagnostic);
            assertEquals(YtDlpException.Kind.INTERNAL, failure.getKind());
            assertFalse(failure.canRetryWithPotProvider());
        }
    }

    private static void assertPotRetry(String diagnostic)
    {
        YtDlpException failure = YtDlpMediaResolver.classifyFailure(diagnostic);
        assertEquals(YtDlpException.Kind.RETRYABLE, failure.getKind());
        assertTrue(failure.canRetryWithPotProvider());
    }

    private static void assertNoPotRetry(String diagnostic)
    {
        YtDlpException failure = YtDlpMediaResolver.classifyFailure(diagnostic);
        assertEquals(YtDlpException.Kind.AUTHENTICATION_REQUIRED, failure.getKind());
        assertFalse(failure.canRetryWithPotProvider());
    }
}
