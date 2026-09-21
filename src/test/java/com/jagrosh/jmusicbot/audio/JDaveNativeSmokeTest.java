package com.jagrosh.jmusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import club.minnced.discord.jdave.ffi.LibDave;
import org.junit.jupiter.api.Test;

class JDaveNativeSmokeTest
{
    @Test
    void loadsThePlatformNativeAndReportsAProtocolVersion()
    {
        assertTrue(LibDave.getMaxSupportedProtocolVersion() > 0,
                "JDAVE native library did not report a supported DAVE protocol version");
    }
}
