/* Modified by Huaaudio: NeoMusicBot namespace migration (2026). */
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
package io.github.huaaudio.neomusicbot.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TimeUtil
{

    public static String formatTime(long duration)
    {
        if(duration == Long.MAX_VALUE)
            return "LIVE";
        long seconds = Math.round(duration/1000.0);
        long hours = seconds/(60*60);
        seconds %= 60*60;
        long minutes = seconds/60;
        seconds %= 60;
        return (hours>0 ? hours+":" : "") + (minutes<10 ? "0"+minutes : minutes) + ":" + (seconds<10 ? "0"+seconds : seconds);
    }

    /**
     * Parses a seek time string into milliseconds and determines if it's relative.
     * Supports "colon time" (HH:MM:SS) or "unit time" (1h20m)
     * @param args time string
     * @return SeekTime object, or null if the string could not be parsed
     */
    public static SeekTime parseTime(String args)
    {
        if(args == null) return null;
        String timestamp = args.strip();
        if(timestamp.isEmpty()) return null;
        boolean relative = false; // seek forward or backward
        boolean isSeekingBackwards = false;
        char first = timestamp.charAt(0);
        if (first == '+' || first == '-')
        {
            relative = true;
            isSeekingBackwards = first == '-';
            timestamp = timestamp.substring(1).stripLeading();
        }
        if(timestamp.regionMatches(true, 0, "0x", 0, 2)) return null;

        long milliseconds = parseColonTime(timestamp);
        // Numeric/colon input must not be reinterpreted as unit tokens after
        // an overflow (for example, "1e999" becoming 1 + 999 seconds).
        if(milliseconds == -1 && timestamp.indexOf(':') < 0 && !timestamp.matches("[0-9.,:+eE\\-\\s]+"))
            milliseconds = parseUnitTime(timestamp);
        if(milliseconds == -1) return null;

        milliseconds *= isSeekingBackwards ? -1 : 1;

        return new SeekTime(milliseconds, relative);
    }

    /**
     * @param timestamp timestamp formatted as: [+ | -] &lt;HH:MM:SS | MM:SS | SS&gt;
     * @return Time in milliseconds
     */
    public static long parseColonTime(String timestamp)
    {
        if(timestamp == null) return -1;
        String[] timestampSplitArray = timestamp.split(":+", -1);
        if(timestampSplitArray.length > 3 )
            return -1;
        int[] multipliers = {3_600_000, 60_000, 1_000};
        BigDecimal milliseconds = BigDecimal.ZERO;
        try
        {
            for(int index = 0; index < timestampSplitArray.length; index++)
            {
                String unit = timestampSplitArray[index].strip().replace(",", ".");
                // Bound exponent length before exact arithmetic to prevent
                // pathological scale allocation. Preserve finite scientific
                // notation, but reject Java's NaN/Infinity and hex literals.
                if(!unit.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))
                    return -1;
                milliseconds = milliseconds.add(new BigDecimal(unit).multiply(
                        BigDecimal.valueOf(multipliers[index + 3 - timestampSplitArray.length])));
            }
            return milliseconds.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        catch(NumberFormatException | ArithmeticException invalid) { return -1; }
    }

    /**
     *
     * @param timestr time string formatted as a unit time, e.g. 20m10, 1d5h20m14s or 1h and 20m
     * @return Time in milliseconds
     */
    public static long parseUnitTime(String timestr)
    {
        if(timestr == null) return -1;
        timestr = timestr.replaceAll("(?i)(\\s|,|and)","")
                .replaceAll("(?is)(-?\\d+|[a-z]+)", "$1 ")
                .trim();
        String[] vals = timestr.split("\\s+");
        long time = 0;
        try
        {
            for(int j=0; j<vals.length; j+=2)
            {
                long num = Long.parseLong(vals[j]);
                if(num < 0) return -1;

                if(vals.length > j+1)
                {
                    if(vals[j+1].toLowerCase().startsWith("m"))
                        num = Math.multiplyExact(num, 60);
                    else if(vals[j+1].toLowerCase().startsWith("h"))
                        num = Math.multiplyExact(num, 60 * 60);
                    else if(vals[j+1].toLowerCase().startsWith("d"))
                        num = Math.multiplyExact(num, 60 * 60 * 24);
                }

                time = Math.addExact(time, Math.multiplyExact(num, 1000));
            }
        }
        catch(NumberFormatException | ArithmeticException ex)
        {
            return -1;
        }
        return time;
    }

    public static class SeekTime
    {
        public final long milliseconds;
        public final boolean relative;

        private SeekTime(long milliseconds, boolean relative)
        {
            this.milliseconds = milliseconds;
            this.relative = relative;
        }
    }
}
