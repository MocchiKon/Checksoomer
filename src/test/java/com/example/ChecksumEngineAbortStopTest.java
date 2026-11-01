package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.example.EngineTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cooperative stop/abort behavior of {@link ChecksumEngine#run}, driven by a deterministic control.
 */
class ChecksumEngineAbortStopTest
{
    private static void compute(Path work) throws Exception
    {
        run(new RecordingProgress(), cfg(work, null, Mode.COMPUTE_CHECKSUMS));
    }

    @Test
    void shouldCommitWorkSoFarAndNotCompleteWhenStoppedGracefully(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree of ten files
        for (int i = 0; i < 10; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }
        compute(work);

        // WHEN Stop is latched partway through the single page of rows
        RecordingProgress rec = new RecordingProgress();
        RunStats stats = run(ProgrammableControl.stopAfter(4), rec, cfg(work, null, Mode.NEW_TEST));

        // THEN some but not all rows are tested and committed, and the run does not snap to 100%
        assertTrue(stats.tested() > 0 && stats.tested() < 10, "stopped mid-run: some but not all tested");
        assertFalse(rec.completed, "a stopped run must not snap to 100%");
        assertTrue(rec.finalized, "a stopped run snaps the final labels via snapFinal()");
        assertEquals(stats.tested(), countTested(cfg(work, null, Mode.NEW_TEST)),
                "rows verified before the stop are committed");
    }

    @Test
    void shouldDiscardInFlightRowWhenAbortedMidTest(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree of five files
        for (int i = 0; i < 5; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }
        compute(work);

        // WHEN Abort fires on the first mid-file poll, i.e. while hashing the very first row
        RunStats stats = run(ProgrammableControl.abortAfter(0), new RecordingProgress(),
                cfg(work, null, Mode.NEW_TEST));

        // THEN the aborted row's result is discarded and no row is marked tested
        assertEquals(0, stats.tested(), "the aborted row's result is discarded");
        assertEquals(5, countUntested(cfg(work, null, Mode.NEW_TEST)),
                "no row is marked tested, so RESUME would re-test all of them");
    }

    @Test
    void shouldCommitPartialWorkWithoutThrowingWhenAbortedDuringComputeWalk(@TempDir Path work) throws Exception
    {
        // GIVEN five files to be computed
        for (int i = 0; i < 5; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }

        // WHEN Abort fires while hashing the first file
        run(ProgrammableControl.abortAfter(0), new RecordingProgress(), cfg(work, null, Mode.COMPUTE_CHECKSUMS));

        // THEN the walk unwinds cleanly and the aborted file (and anything after it) is not committed
        // The sidecar must already match the partially-committed DB (stamped after the committed batch),
        // so a crash mid-run would still verify instead of falsely reporting corruption.
        assertTrue(verifyDb(cfg(work, null, Mode.COMPUTE_CHECKSUMS)), "the partially-committed DB still verifies via its sidecar");
        assertTrue(readRows(cfg(work, null, Mode.COMPUTE_CHECKSUMS)).size() < 5,
                "the aborted file (and anything after it) is not committed");
    }

    @Test
    void shouldRefreshSidecarWhenRunCompletesNormally(@TempDir Path work) throws Exception
    {
        // GIVEN a normally-completed compute run
        writeFile(work, "a.txt", "alpha");
        compute(work);

        // WHEN the DB sidecar is checked
        RunConfig cfg = cfg(work, null, Mode.COMPUTE_CHECKSUMS);

        // THEN it exists and verifies
        assertTrue(new ChecksumDb(cfg).layout().dbChecksumFile().exists());
        assertTrue(verifyDb(cfg), "a normally-completed run leaves a verifiable DB sidecar");
    }

    @Test
    void shouldRefreshSidecarWhenRunIsStopped(@TempDir Path work) throws Exception
    {
        // GIVEN ten files and a compute run that is stopped partway through
        for (int i = 0; i < 10; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }
        run(ProgrammableControl.stopAfter(3), new RecordingProgress(), cfg(work, null, Mode.COMPUTE_CHECKSUMS));

        // WHEN the DB sidecar is checked
        RunConfig cfg = cfg(work, null, Mode.COMPUTE_CHECKSUMS);

        // THEN even the stopped run has refreshed the sidecar so the next session verifies
        assertTrue(new ChecksumDb(cfg).layout().dbChecksumFile().exists());
        assertTrue(verifyDb(cfg), "even a stopped run refreshes the sidecar so the next session verifies");
    }
}
