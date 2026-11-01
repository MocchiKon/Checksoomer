package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the log-format contract enforced by {@link RunLog}.
 */
class RunLogTest
{
    @Test
    void shouldWriteHeaderOnceAndAppendLinesWhenAppendingMultipleTimes(@TempDir Path tmp) throws Exception
    {
        // GIVEN a log file target and two error lines
        File log = tmp.resolve("errors.txt").toFile();
        String first = RunLog.errorLine("MISSING", "a.txt", "", "exp");
        String second = RunLog.errorLine("WRONG_CHECKSUM", "b.txt", "exp", "act");

        // WHEN both are appended
        RunLog.append(log, RunLog.ERROR_LOG_HEADER, first);
        RunLog.append(log, RunLog.ERROR_LOG_HEADER, second);

        // THEN the header is written once and both lines are present
        String content = Files.readString(log.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.startsWith(RunLog.ERROR_LOG_HEADER), "header is written when the file is created");
        // The header's first comment line must appear exactly once (not re-emitted on the second append).
        String marker = "# Checksoomer error log.";
        assertEquals(content.indexOf(marker), content.lastIndexOf(marker), "header must appear only once");
        assertTrue(content.contains(first));
        assertTrue(content.contains(second));
    }

    @Test
    void shouldUseTabSeparatedLayoutWhenBuildingErrorLine()
    {
        // GIVEN a status, path, expected and actual checksum
        // WHEN an error line is built
        // THEN it is tab-separated with a trailing newline
        assertEquals("WRONG_CHECKSUM\t/p/q.txt\texpected\tactual\n",
                RunLog.errorLine("WRONG_CHECKSUM", "/p/q.txt", "expected", "actual"));
    }

    @Test
    void shouldCreateMissingParentDirectoriesWhenAppending(@TempDir Path tmp) throws Exception
    {
        // GIVEN a log path whose parent directories do not exist yet
        File log = tmp.resolve("nested").resolve("deeper").resolve("log.txt").toFile();

        // WHEN a line is appended
        RunLog.append(log, RunLog.REMOVED_LOG_HEADER, "REMOVED ENTRY [MISSING_FILE]: x | Checksum: abc\n");

        // THEN the parents are created on demand and the line is written
        assertTrue(log.exists(), "parent directories are created on demand");
        assertTrue(Files.readString(log.toPath()).contains("REMOVED ENTRY"));
    }
}