package io.github.huaaudio.neomusicbot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherUtilVersionTest
{
    @Test
    void onlyTreatsStrictlyHigherReleaseVersionsAsUpdates()
    {
        assertTrue(OtherUtil.isNewerVersion("0.4.4", "0.4.5"));
        assertTrue(OtherUtil.isNewerVersion("0.4.9", "0.4.10"));
        assertTrue(OtherUtil.isNewerVersion("0.4.9", "v0.5.0"));

        assertFalse(OtherUtil.isNewerVersion("0.4.4", "0.4.4"));
        assertFalse(OtherUtil.isNewerVersion("0.4.4", "0.4.3"));
        assertFalse(OtherUtil.isNewerVersion("0.4.4", null));
        assertFalse(OtherUtil.isNewerVersion("UNKNOWN", "0.4.5"));
    }

    @Test
    void followsSemverPrereleaseAndBuildMetadataOrdering()
    {
        assertTrue(OtherUtil.isNewerVersion("0.4.5-rc.1", "0.4.5-rc.2"));
        assertTrue(OtherUtil.isNewerVersion("0.4.5-rc.2", "0.4.5"));

        assertFalse(OtherUtil.isNewerVersion("0.4.5", "0.4.5-rc.2"));
        assertFalse(OtherUtil.isNewerVersion("0.4.5+local", "0.4.5+remote"));
    }
}
