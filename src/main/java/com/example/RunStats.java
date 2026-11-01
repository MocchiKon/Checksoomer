package com.example;

import java.util.Locale;

/**
 * Mutable accumulator of per-run statistics, populated on the worker thread and read after it finishes.
 */
final class RunStats
{
    final Mode mode;
    // Test modes
    long ok, wrongChecksum, modifiedDiffChecksum, missing, invalidPath, errorCalculating;
    // Compute mode
    long newEntries, updatedEntries, skippedUnchanged;
    // Clean mode
    long entriesScanned, entriesRemoved;
    // Absolute path of the log file holding this run's details (error log, or removed-sums log for CLEAN).
    String detailLogPath;
    // True when the DB self-checksum sidecar could not be (re)written after the run (see ChecksumDb.saveChecksum).
    boolean sidecarWriteFailed;

    RunStats(Mode mode)
    {
        this.mode = mode;
    }

    /** Tallies one file-verification outcome into the matching test-mode counter. */
    void record(ResultKind kind)
    {
        switch (kind)
        {
            case OK -> ok++;
            case WRONG_CHECKSUM -> wrongChecksum++;
            case MODIFIED_DIFF_CHECKSUM -> modifiedDiffChecksum++;
            case MISSING -> missing++;
            case INVALID_PATH -> invalidPath++;
            case ERROR_CALCULATING -> errorCalculating++;
        }
    }

    /**
     * Total files examined in a test run (every recorded outcome, including OK).
     */
    long tested()
    {
        return ok + wrongChecksum + modifiedDiffChecksum + missing + invalidPath + errorCalculating;
    }

    /**
     * Files that failed a test run (every non-OK outcome).
     */
    long failures()
    {
        return wrongChecksum + modifiedDiffChecksum + missing + invalidPath + errorCalculating;
    }

    String summaryLine()
    {
        return switch (mode)
        {
            case NEW_TEST, RESUME_TEST -> String.format(Locale.ROOT,
                    "ok=%d wrong=%d modDiff=%d missing=%d invalidPath=%d errorCalc=%d",
                    ok, wrongChecksum, modifiedDiffChecksum, missing, invalidPath, errorCalculating);
            case COMPUTE_CHECKSUMS -> String.format(Locale.ROOT,
                    "new=%d updated=%d skipped=%d", newEntries, updatedEntries, skippedUnchanged);
            case CLEAN_CHECKSUMS_FOR_REMOVED_FILES -> String.format(Locale.ROOT,
                    "scanned=%d removed=%d", entriesScanned, entriesRemoved);
        };
    }
}