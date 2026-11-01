package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest
{
    @Test
    void shouldShowOnlyMillisecondsWhenDurationIsSubSecond()
    {
        // GIVEN sub-second durations
        // WHEN they are formatted
        // THEN only zero-padded milliseconds are shown
        assertEquals("000ms", Format.duration(0));
        assertEquals("005ms", Format.duration(5));
        assertEquals("999ms", Format.duration(999));
    }

    @Test
    void shouldZeroPadAndAlwaysShowSmallestUnitWhenFormattingDuration()
    {
        // GIVEN durations that span whole seconds, minutes and hours
        // WHEN they are formatted
        // THEN every larger unit is zero-padded and milliseconds are always present
        assertEquals("01s 000ms", Format.duration(1_000));
        assertEquals("01m 05s 000ms", Format.duration(65_000));
        assertEquals("01h 00m 00s 000ms", Format.duration(3_600_000));
    }

    @Test
    void shouldIncludeDaysWhenDurationExceedsOneDay()
    {
        // GIVEN a duration of 1 day + 2h 3m 4s 5ms
        long ms = (24L * 3600 + 2 * 3600 + 3 * 60 + 4) * 1000 + 5;

        // WHEN it is formatted
        String formatted = Format.duration(ms);

        // THEN the day component appears
        assertEquals("1d 02h 03m 04s 005ms", formatted);
    }

    @Test
    void shouldFormatWithTwoDecimalsWhenConvertingBytesToGb()
    {
        // GIVEN byte counts
        // WHEN they are converted to GB
        // THEN they render with two decimals
        assertEquals("0.00 GB", Format.gb(0));
        assertEquals("1.00 GB", Format.gb((long) Format.BYTES_PER_GB));
        assertEquals("0.50 GB", Format.gb((long) (Format.BYTES_PER_GB / 2)));
    }

    @Test
    void shouldWrapGbValueWhenBuildingProcessedText()
    {
        // GIVEN byte counts
        // WHEN the processed text is built
        // THEN the GB value is wrapped in the "Processed:" label
        assertEquals("Processed: 0.00 GB", Format.processedGbText(0));
        assertEquals("Processed: 1.00 GB", Format.processedGbText((long) Format.BYTES_PER_GB));
    }

    @Test
    void shouldReportZeroThroughputWhenElapsedTimeIsNonPositive()
    {
        // GIVEN a byte count with no positive elapsed time
        // WHEN throughput is computed
        // THEN it reports zero
        assertEquals("0.0 MB/s", Format.mbPerSecond(1_000_000, 0));
        assertEquals("0.0 MB/s", Format.mbPerSecond(1_000_000, -1));
    }

    @Test
    void shouldComputeThroughputWhenGivenBytesAndElapsedTime()
    {
        // GIVEN byte counts and their elapsed seconds
        // WHEN throughput is computed
        // THEN it equals bytes over elapsed seconds
        // 10 MB over 10 s = 1.0 MB/s
        assertEquals("1.0 MB/s", Format.mbPerSecond((long) (Format.BYTES_PER_MB * 10), 10.0));
        // 20 MB over 5 s = 4.0 MB/s
        assertEquals("4.0 MB/s", Format.mbPerSecond((long) (Format.BYTES_PER_MB * 20), 5.0));
    }
}
