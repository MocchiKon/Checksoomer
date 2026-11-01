package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.example.EngineTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@code NEW_TEST} / {@code RESUME_TEST} verification through {@link ChecksumEngine#run}.
 */
class ChecksumEngineTestModeTest
{
    private static void compute(Path work) throws Exception
    {
        run(new RecordingProgress(), cfg(work, null, Mode.COMPUTE_CHECKSUMS));
    }

    @Test
    void shouldReportAllOkWhenNothingChanged(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree left untouched
        writeFile(work, "a.txt", "alpha");
        writeFile(work, "dir/b.txt", "beta");
        compute(work);

        // WHEN it is verified
        RecordingProgress rec = new RecordingProgress();
        RunStats stats = run(rec, cfg(work, null, Mode.NEW_TEST));

        // THEN every file is OK with no failures and no error log
        assertEquals(2, stats.ok);
        assertEquals(0, stats.failures());
        assertEquals("", readDetailLog(stats), "no error log lines on a clean pass");
        assertTrue(rec.completed);
    }

    @Test
    void shouldReportWrongChecksumWhenFileIsCorrupted(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree
        writeFile(work, "a.txt", "alpha");
        File b = writeFile(work, "b.txt", "beta");
        compute(work);
        long storedMtime = b.lastModified();

        // WHEN one file's bytes change while keeping the same mtime
        Files.writeString(b.toPath(), "BETA");
        assumeTrue(b.setLastModified(storedMtime), "filesystem must support setting mtime");

        RunStats stats = run(new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));

        // THEN the corruption is reported as WRONG_CHECKSUM (mtime matches, content differs)
        assertEquals(1, stats.ok);
        assertEquals(1, stats.wrongChecksum);
        assertEquals(0, stats.modifiedDiffChecksum);
        assertTrue(readDetailLog(stats).contains("WRONG_CHECKSUM"));
    }

    @Test
    void shouldReportModifiedDiffChecksumWhenMtimeChanged(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree where mtime can be explicitly set
        File f = writeFile(work, "changed.txt", "original");
        compute(work);
        long storedMtime = f.lastModified();

        // Ensure the filesystem supports setting mtime (needed for reliable test)
        assumeTrue(f.setLastModified(storedMtime + 5_000), "filesystem must support setting mtime");

        // WHEN the file's mtime is set forward AND content is changed
        // (both conditions trigger MODIFIED_DIFF_CHECKSUM)
        Files.writeString(f.toPath(), "modified-content");
        assumeTrue(f.setLastModified(storedMtime + 5_000), "filesystem must support setting mtime");

        RunStats stats = run(new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));

        // THEN the corruption is reported as MODIFIED_DIFF_CHECKSUM
        assertEquals(0, stats.ok);
        assertEquals(0, stats.wrongChecksum, "should be MODIFIED_DIFF_CHECKSUM not WRONG_CHECKSUM");
        assertEquals(1, stats.modifiedDiffChecksum, "should count as MODIFIED_DIFF_CHECKSUM");
        String log = readDetailLog(stats);
        assertTrue(log.contains("MODIFIED_DIFF_CHECKSUM"), "log should contain MODIFIED_DIFF_CHECKSUM");
    }

    @Test
    void shouldReportMissingWhenFileIsDeleted(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree
        writeFile(work, "a.txt", "alpha");
        File b = writeFile(work, "b.txt", "beta");
        compute(work);

        // WHEN one file is deleted
        assertTrue(b.delete());
        RunStats stats = run(new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));

        // THEN the deletion is reported as missing
        assertEquals(1, stats.ok);
        assertEquals(1, stats.missing);
        assertTrue(readDetailLog(stats).contains("MISSING"));
    }

    @Test
    void shouldTestOnlyUntestedRowsWhenResumingWhileNewTestRetestsEverything(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree where every row is already marked tested
        writeFile(work, "a.txt", "alpha");
        writeFile(work, "b.txt", "beta");
        compute(work);

        // WHEN RESUME runs, there is nothing untested to verify
        RunStats resume = run(new RecordingProgress(), cfg(work, null, Mode.RESUME_TEST));
        assertEquals(0, resume.tested());

        // THEN NEW_TEST resets the flags first and re-verifies everything
        RunStats fresh = run(new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));
        assertEquals(2, fresh.tested());
        assertEquals(2, fresh.ok);
    }

    @Test
    void shouldScopeResetToSubsetWhenNewTestRunsOnSubset(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree with all rows tested
        writeFile(work, "alpha/1.txt", "a");
        writeFile(work, "alpha/2.txt", "b");
        writeFile(work, "beta/3.txt", "c");
        compute(work);

        // WHEN NEW_TEST is scoped to alpha/ only
        RunStats stats = run(new RecordingProgress(), cfg(work, work.resolve("alpha"), Mode.NEW_TEST));

        // THEN only alpha/ rows are re-tested and beta/ keeps its tested state
        assertEquals(2, stats.tested(), "only the two alpha/ rows are re-tested");
        assertEquals(2, stats.ok);
        assertEquals(0, countUntested(cfg(work, null, Mode.NEW_TEST)),
                "beta/ rows were never reset, so nothing remains untested afterwards");
    }

    @Test
    void shouldReportInvalidPathWhenRowEscapesWorkingDir(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree plus a seeded traversal row that is the only untested row
        writeFile(work, "a.txt", "alpha");
        compute(work);
        insertRow(cfg(work, null, Mode.RESUME_TEST), "../escape.txt", "deadbeef", false);

        // WHEN RESUME verifies the untested rows
        RunStats stats = run(new RecordingProgress(), cfg(work, null, Mode.RESUME_TEST));

        // THEN the traversal row is rejected as an invalid path
        assertEquals(1, stats.tested());
        assertEquals(1, stats.invalidPath);
        assertTrue(readDetailLog(stats).contains("INVALID_PATH"));
    }

    @Test
    void shouldTestEveryFileExactlyOnceWhenSpanningMultiplePages(@TempDir Path work) throws Exception
    {
        // GIVEN more files than PAGE_SIZE (100) so mark-and-sweep pagination runs several pages
        int count = 250;
        for (int i = 0; i < count; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }
        compute(work);

        // WHEN the whole tree is verified
        RunStats stats = run(new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));

        // THEN every file is verified exactly once with no duplicates or gaps
        assertEquals(count, stats.ok, "every file verified exactly once with no duplicates or gaps");
        assertEquals(count, stats.tested());
        assertEquals(0, countUntested(cfg(work, null, Mode.NEW_TEST)));
    }

    @Test
    void shouldResumeExactlyTheUntestedRowsLeftByAStoppedNewTest(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree of ten files, where a NEW_TEST run is stopped after a few files
        for (int i = 0; i < 10; i++)
        {
            writeFile(work, "f" + i + ".txt", "body-" + i);
        }
        compute(work);
        RunStats stopped = run(ProgrammableControl.stopAfter(4), new RecordingProgress(), cfg(work, null, Mode.NEW_TEST));

        // The stop left some rows tested and the rest untested
        assertTrue(stopped.tested() > 0 && stopped.tested() < 10, "stop left a partial result");
        long untestedAfterStop = countUntested(cfg(work, null, Mode.NEW_TEST));
        assertEquals(10 - stopped.tested(), untestedAfterStop, "the remainder is exactly the untested rows");

        // WHEN RESUME runs (it must not reset flags, only verify what's still untested)
        RunStats resume = run(new RecordingProgress(), cfg(work, null, Mode.RESUME_TEST));

        // THEN RESUME verifies exactly the rows NEW_TEST had not finished, and nothing more
        assertEquals(untestedAfterStop, resume.tested(), "RESUME only touches the still-untested rows");
        assertEquals(10, countTested(cfg(work, null, Mode.RESUME_TEST)), "all rows are now tested");
        assertEquals(0, countUntested(cfg(work, null, Mode.RESUME_TEST)));
        assertTrue(resume.ok == untestedAfterStop, "the untouched remainder is clean");
    }

    @Test
    void shouldResumeOnlyUntestedRowsWithinASubset(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree plus one untested row under alpha/ and one under beta/
        writeFile(work, "alpha/1.txt", "a");
        writeFile(work, "alpha/2.txt", "b");
        writeFile(work, "beta/3.txt", "c");
        compute(work);
        insertRow(cfg(work, null, Mode.RESUME_TEST), "alpha/extra.txt", "deadbeef", false);
        insertRow(cfg(work, null, Mode.RESUME_TEST), "beta/extra.txt", "deadbeef", false);

        // WHEN RESUME is scoped to the beta/ subset
        RunStats resume = run(new RecordingProgress(), cfg(work, work.resolve("beta"), Mode.RESUME_TEST));

        // THEN only the untested beta/ row is verified and the alpha/ untested row stays untouched.
        // (The support counters read the whole table, so we check the whole-tree totals plus each
        // specific row's tested flag rather than subset-scoped counts.)
        assertEquals(1, resume.tested(), "the beta/-scoped resume examines exactly the one untested beta/ row");
        assertEquals(1, resume.invalidPath + resume.wrongChecksum + resume.missing + resume.modifiedDiffChecksum,
                "the seeded beta/ row is reported as a failure");
        assertTrue(row(cfg(work, null, Mode.RESUME_TEST), "beta/extra.txt").tested(),
                "the beta/ untested row is now tested after the scoped resume");
        assertFalse(row(cfg(work, null, Mode.RESUME_TEST), "alpha/extra.txt").tested(),
                "the alpha/ untested row is left alone by the beta/-scoped resume");
        assertEquals(1, countUntested(cfg(work, null, Mode.RESUME_TEST)),
                "exactly one row (the alpha/ one) remains untested across the whole tree");
        assertEquals(4, countTested(cfg(work, null, Mode.RESUME_TEST)),
                "the three computed rows plus the resumed beta/ row are tested");
    }
}