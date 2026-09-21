package io.github.huaaudio.neomusicbot.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSelfTestTest
{
    @Test
    void platformNativesDecodeEveryBundledCodecWithoutNetwork()
    {
        var bytes = new ByteArrayOutputStream();
        int result = RuntimeSelfTest.run(new PrintStream(bytes));
        String report = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, result, report);
        assertTrue(report.contains("native.dave=passed"));
        assertTrue(report.contains("native.opus=passed"));
        assertTrue(report.contains("crypto.rtp=passed"));
        assertTrue(report.contains("audio.m4a=passed"));
        assertTrue(report.contains("audio.ogg=passed"));
        assertTrue(report.contains("audio.mp3=passed"));
    }
}
