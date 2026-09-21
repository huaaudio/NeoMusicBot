/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
/*
 * Copyright 2020 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.huaaudio.neomusicbot;


import io.github.huaaudio.neomusicbot.utils.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Whew., Inc.
 */
public class TimeUtilTest
{
    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "+Infinity", "1:NaN", "1e999", "1e999 ", "1 e999", ":::",
            "9223372036854776", "9223372036854775807d", "+-1m", "1h-1m",
            "0x1p2", "-0X1P2", "  + 0x1p2 "})
    void invalidOrUnrepresentableTimesDoNotBecomeSeekPositions(String value)
    {
        assertNull(TimeUtil.parseTime(value));
    }

    @Test
    void unitTimesUseTheFullLongRangeWithoutIntegerWraparound()
    {
        assertEquals(4_294_968_000L, TimeUtil.parseTime("4294968s").milliseconds);
        assertEquals(2_592_000_000L, TimeUtil.parseTime("30d").milliseconds);
        assertEquals(-2_592_000_000L, TimeUtil.parseTime("-30d").milliseconds);
    }

    @Test
    void decimalRoundingAndTheLongBoundaryRemainExact()
    {
        assertEquals(1, TimeUtil.parseTime("0.0005").milliseconds);
        assertEquals(0, TimeUtil.parseTime("0.0004").milliseconds);
        assertEquals(Long.MAX_VALUE, TimeUtil.parseTime("9223372036854775.807").milliseconds);
        assertNull(TimeUtil.parseTime("9223372036854775.808"));
        assertEquals(60_000, TimeUtil.parseTime("1m").milliseconds);
        assertEquals(60_000, TimeUtil.parseTime("1:00").milliseconds);
        assertEquals(1_000_000, TimeUtil.parseTime("1e3").milliseconds);
        assertEquals(1, TimeUtil.parseTime("1e-3").milliseconds);
        assertNull(TimeUtil.parseTime("1e1000"));
        assertNull(TimeUtil.parseTime(null));
        assertNull(TimeUtil.parseTime(" "));
        assertEquals(90_000, TimeUtil.parseTime(" 1: 30 ").milliseconds);
    }

    @Test
    public void singleDigit()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("5");
        assertNotNull(seek);
        assertEquals(5000, seek.milliseconds);
    }

    @Test
    public void multipleDigits()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("99:9:999");
        assertNotNull(seek);
        assertEquals(357939000, seek.milliseconds);

        seek = TimeUtil.parseTime("99h9m999s");
        assertNotNull(seek);
        assertEquals(357939000, seek.milliseconds);
    }

    @Test
    public void decimalDigits()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("99.5:9.0:999.777");
        assertNotNull(seek);
        assertEquals(359739777, seek.milliseconds);
    }

    @Test
    public void seeking()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("5");
        assertNotNull(seek);
        assertFalse(seek.relative);
        assertEquals(5000, seek.milliseconds);
    }

    @Test
    public void relativeSeekingForward()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("+5");
        assertNotNull(seek);
        assertTrue(seek.relative);
        assertEquals(5000, seek.milliseconds);
    }

    @Test
    public void relativeSeekingBackward()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("-5");
        assertNotNull(seek);
        assertTrue(seek.relative);
        assertEquals(-5000, seek.milliseconds);
    }

    @Test
    public void parseTimeArgumentLength()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("");
        assertNull(seek);
    }

    @Test
    public void timestampTotalUnits()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("1:1:1:1");
        assertNull(seek);

        seek = TimeUtil.parseTime("1h2m3m4s5s");
        assertNotNull(seek);
        assertEquals(3909000, seek.milliseconds);
    }

    @Test
    public void relativeSymbol()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("+-1:-+1:+-1");
        assertNull(seek);
    }

    @Test
    public void timestampNumberFormat()
    {
        TimeUtil.SeekTime seek = TimeUtil.parseTime("1:1:a");
        assertNull(seek);

        seek = TimeUtil.parseTime("1a2s");
        assertNotNull(seek);
        assertEquals(3000, seek.milliseconds);
    }
}
