package com.example;

import java.time.Duration;
import java.util.Locale;

/** Human-readable formatting helpers for byte counts and durations. */
final class Format
{
    static final double BYTES_PER_MB = 1024.0 * 1024.0;
    static final double BYTES_PER_GB = BYTES_PER_MB * 1024.0;

    private Format() { }

    /** The "Processed: X.XX GB" progress-label text for a byte count. */
    static String processedGbText(long bytes)
    {
        return String.format(Locale.ROOT, "Processed: %.2f GB", bytes / BYTES_PER_GB);
    }

    /** "X.XX GB" for a byte count. */
    static String gb(long bytes)
    {
        return String.format(Locale.ROOT, "%.2f GB", bytes / BYTES_PER_GB);
    }

    /** "X.X MB/s" throughput for a byte count over a duration in seconds (0 when seconds <= 0). */
    static String mbPerSecond(long bytes, double seconds)
    {
        double mbps = seconds > 0 ? (bytes / BYTES_PER_MB) / seconds : 0;
        return String.format(Locale.ROOT, "%.1f MB/s", mbps);
    }

    /** A compact, zero-padded duration such as {@code "1d 02h 03m 04s 005ms"} (smallest unit always shown). */
    static String duration(long durationMs)
    {
        Duration d = Duration.ofMillis(durationMs);
        long days = d.toDaysPart();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        long milliseconds = d.toMillisPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0)
        {
            sb.append(days).append("d ");
        }
        if (hours > 0 || !sb.isEmpty())
        {
            sb.append(String.format("%02dh ", hours));
        }
        if (minutes > 0 || !sb.isEmpty())
        {
            sb.append(String.format("%02dm ", minutes));
        }
        if (seconds > 0 || !sb.isEmpty())
        {
            sb.append(String.format("%02ds ", seconds));
        }
        sb.append(String.format("%03dms", milliseconds));

        return sb.toString();
    }
}
