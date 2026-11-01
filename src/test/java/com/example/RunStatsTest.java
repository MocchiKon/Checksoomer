package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunStatsTest
{
    @Test
    void shouldTallyEachKindIntoItsCounterWhenRecording()
    {
        // GIVEN a fresh stats accumulator
        RunStats stats = new RunStats(Mode.NEW_TEST);

        // WHEN a mix of result kinds is recorded
        stats.record(ResultKind.OK);
        stats.record(ResultKind.OK);
        stats.record(ResultKind.WRONG_CHECKSUM);
        stats.record(ResultKind.MODIFIED_DIFF_CHECKSUM);
        stats.record(ResultKind.MISSING);
        stats.record(ResultKind.INVALID_PATH);
        stats.record(ResultKind.ERROR_CALCULATING);

        // THEN each counter reflects its kind
        assertEquals(2, stats.ok);
        assertEquals(1, stats.wrongChecksum);
        assertEquals(1, stats.modifiedDiffChecksum);
        assertEquals(1, stats.missing);
        assertEquals(1, stats.invalidPath);
        assertEquals(1, stats.errorCalculating);
    }

    @Test
    void shouldSummarizeVerificationCountersWhenModeIsTest()
    {
        // GIVEN a test-mode run with one ok, one wrong, and one modDiff result
        RunStats stats = new RunStats(Mode.RESUME_TEST);
        stats.record(ResultKind.OK);
        stats.record(ResultKind.WRONG_CHECKSUM);
        stats.record(ResultKind.MODIFIED_DIFF_CHECKSUM);

        // WHEN the summary line is built
        String summary = stats.summaryLine();

        // THEN it lists the verification counters
        assertEquals("ok=1 wrong=1 modDiff=1 missing=0 invalidPath=0 errorCalc=0", summary);
    }

    @Test
    void shouldSummarizeNewUpdatedAndSkippedWhenModeIsCompute()
    {
        // GIVEN a compute run with new, updated and skipped entries
        RunStats stats = new RunStats(Mode.COMPUTE_CHECKSUMS);
        stats.newEntries = 3;
        stats.updatedEntries = 2;
        stats.skippedUnchanged = 5;

        // WHEN the summary line is built
        String summary = stats.summaryLine();

        // THEN it lists the compute counters
        assertEquals("new=3 updated=2 skipped=5", summary);
    }

    @Test
    void shouldSummarizeScannedAndRemovedWhenModeIsClean()
    {
        // GIVEN a clean run with scanned and removed entries
        RunStats stats = new RunStats(Mode.CLEAN_CHECKSUMS_FOR_REMOVED_FILES);
        stats.entriesScanned = 10;
        stats.entriesRemoved = 4;

        // WHEN the summary line is built
        String summary = stats.summaryLine();

        // THEN it lists the clean counters
        assertEquals("scanned=10 removed=4", summary);
    }

    @Test
    void shouldDefaultSidecarWriteFailedToFalseWhenCreated()
    {
        // GIVEN a freshly created stats accumulator
        RunStats stats = new RunStats(Mode.COMPUTE_CHECKSUMS);

        // WHEN the sidecar-write-failed flag is read
        // THEN it defaults to false
        assertEquals(false, stats.sidecarWriteFailed);
    }
}