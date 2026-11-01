package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends self-documenting, UTF-8 log lines to the per-run error and removed-sums logs. */
final class RunLog
{
    private static final Logger log = LoggerFactory.getLogger(RunLog.class);

    /** Header (comment lines) written once at the top of each error log, describing the line format. */
    static final String ERROR_LOG_HEADER =
            "# Checksoomer error log. Columns are tab-separated:\n" +
                    "# STATUS  PATH  EXPECTED_CHECKSUM  ACTUAL_CHECKSUM\n" +
                    "# STATUS is one of: WRONG_CHECKSUM, MODIFIED_DIFF_CHECKSUM, MISSING, INVALID_PATH, ERROR_CALCULATING, ERROR_COMPUTING, ERROR_WALKING\n";

    /** Header (comment lines) written once at the top of the removed-sums log, describing the line format. */
    static final String REMOVED_LOG_HEADER =
            "# Checksoomer removed-entries log. Line format:\n" +
                    "# REMOVED ENTRY [REASON]: <relative-path> | Checksum: <stored-checksum>\n" +
                    "# REASON is one of: MISSING_FILE, INVALID_PATH\n";

    private RunLog() { }

    /**
     * Appends a line to a log file (UTF-8). The {@code header} (a comment describing the line format)
     * is written once, when the file is first created, so every log is self-documenting.
     */
    static void append(File file, String header, String line)
    {
        try
        {
            Path path = file.toPath();
            if (path.getParent() != null)
            {
                Files.createDirectories(path.getParent());
            }
            String content = Files.exists(path) ? line : header + line;
            // Files.writeString uses UTF-8 by default, keeping all logs in a consistent encoding.
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            log.warn("Failed to append to log file {}", file, e);
        }
    }

    /**
     * Formats one error-log row in the tab-separated layout documented by {@link #ERROR_LOG_HEADER}:
     * {@code STATUS  PATH  EXPECTED_CHECKSUM  ACTUAL_CHECKSUM}. Unused checksum columns are
     * passed as empty strings. Keeping the layout in one place stops it from drifting from the header.
     */
    static String errorLine(String status, String path, String expectedChecksum, String actualChecksum)
    {
        return status + "\t" + path + "\t" + expectedChecksum + "\t" + actualChecksum + "\n";
    }
}