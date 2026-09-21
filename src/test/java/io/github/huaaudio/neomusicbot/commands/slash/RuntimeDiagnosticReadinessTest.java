package io.github.huaaudio.neomusicbot.commands.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuntimeDiagnosticReadinessTest
{
    @Test
    void onlyASuccessfulLockedVersionProbeIsReportedReady()
    {
        assertTrue(SlashCommandListener.lockedVersionReady("2026.07.04", "2026.07.04"));
        assertFalse(SlashCommandListener.lockedVersionReady("timeout", "2026.07.04"));
        assertFalse(SlashCommandListener.lockedVersionReady("2026.08.01", "2026.07.04"));

        assertEquals("missing",
                SlashCommandListener.versionState("missing", false, "2026.07.04"));
        assertEquals("timeout",
                SlashCommandListener.versionState("timeout", true, "2026.07.04"));
        assertEquals("2026.07.04 (ready)",
                SlashCommandListener.versionState("2026.07.04", true, "2026.07.04"));
        assertEquals("2026.08.01 (unsupported; expected 2026.07.04)",
                SlashCommandListener.versionState("2026.08.01", true, "2026.07.04"));
    }
}
