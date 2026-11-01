package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static com.example.EngineTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code CLEAN_CHECKSUMS_FOR_REMOVED_FILES} through {@link ChecksumEngine#run}.
 */
class ChecksumEngineCleanTest
{
    private static RunConfig clean(Path work)
    {
        return cfg(work, null, Mode.CLEAN_CHECKSUMS_FOR_REMOVED_FILES);
    }

    private static void compute(Path work) throws Exception
    {
        run(new RecordingProgress(), cfg(work, null, Mode.COMPUTE_CHECKSUMS));
    }

    @Test
    void shouldRemoveOnlyVanishedEntriesAndLogThemWhenCleaning(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree where two of the files are then deleted
        writeFile(work, "keep1.txt", "1");
        writeFile(work, "keep2.txt", "2");
        writeFile(work, "keep3.txt", "3");
        File gone1 = writeFile(work, "gone1.txt", "4");
        File gone2 = writeFile(work, "gone2.txt", "5");
        compute(work);
        assertTrue(gone1.delete());
        assertTrue(gone2.delete());

        // WHEN the DB is cleaned
        RunStats stats = run(new RecordingProgress(), clean(work));

        // THEN only the vanished entries are removed and logged
        assertEquals(5, stats.entriesScanned);
        assertEquals(2, stats.entriesRemoved);

        List<String> remaining = readRows(clean(work)).stream().map(DbRow::path).sorted().toList();
        assertEquals(List.of("keep1.txt", "keep2.txt", "keep3.txt"), remaining);

        String log = readDetailLog(stats);
        assertTrue(log.contains("gone1.txt"));
        assertTrue(log.contains("MISSING_FILE"));
    }

    @Test
    void shouldRemoveInvalidPathRowWithInvalidPathReasonWhenCleaning(@TempDir Path work) throws Exception
    {
        // GIVEN a computed tree with a seeded traversal row
        writeFile(work, "keep.txt", "1");
        compute(work);
        insertRow(clean(work), "../escape.txt", "deadbeef", true);

        // WHEN the DB is cleaned
        RunStats stats = run(new RecordingProgress(), clean(work));

        // THEN the traversal row is removed with the INVALID_PATH reason
        assertEquals(1, stats.entriesRemoved);
        assertFalse(readRows(clean(work)).stream().anyMatch(r -> r.path().equals("../escape.txt")));
        assertTrue(readDetailLog(stats).contains("INVALID_PATH"));
    }

    @Test
    void shouldRestrictScanningAndRemovalWhenSubsetGiven(@TempDir Path work) throws Exception
    {
        // GIVEN deleted files in two directories after a compute
        File alphaGone = writeFile(work, "alpha/gone.txt", "a");
        writeFile(work, "alpha/keep.txt", "b");
        File betaGone = writeFile(work, "beta/gone.txt", "c");
        compute(work);
        assertTrue(alphaGone.delete());
        assertTrue(betaGone.delete());

        // WHEN only alpha/ is cleaned
        RunStats stats = run(new RecordingProgress(), cfg(work, work.resolve("alpha"), Mode.CLEAN_CHECKSUMS_FOR_REMOVED_FILES));

        // THEN beta/gone.txt is out of scope and survives even though its file is gone
        assertEquals(2, stats.entriesScanned, "only the two alpha/ rows are scanned");
        assertEquals(1, stats.entriesRemoved);
        assertTrue(readRows(clean(work)).stream().anyMatch(r -> r.path().equals("beta/gone.txt")),
                "out-of-subset row is neither scanned nor removed");
    }

    @Test
    void shouldRemoveExactlyMissingRowsWhenKeysetPaginationRuns(@TempDir Path work) throws Exception
    {
        // GIVEN more files than PAGE_SIZE so keyset pagination runs several pages
        int total = 250;
        for (int i = 0; i < total; i++)
        {
            writeFile(work, "f" + i + ".txt", "b" + i);
        }
        compute(work);

        // WHEN every 5th file is deleted and the DB is cleaned
        int expectedRemoved = 0;
        for (int i = 0; i < total; i += 5)
        {
            assertTrue(work.resolve("f" + i + ".txt").toFile().delete());
            expectedRemoved++;
        }
        RunStats stats = run(new RecordingProgress(), clean(work));

        // THEN exactly the missing rows are removed across all pages
        assertEquals(total, stats.entriesScanned);
        assertEquals(expectedRemoved, stats.entriesRemoved);
        assertEquals(total - expectedRemoved, readRows(clean(work)).size());
    }
}
