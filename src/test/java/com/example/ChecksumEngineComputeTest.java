package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.EngineTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@code COMPUTE_CHECKSUMS} driven through {@link ChecksumEngine#run}.
 */
class ChecksumEngineComputeTest
{
    private static RunConfig compute(Path work)
    {
        return cfg(work, null, Mode.COMPUTE_CHECKSUMS);
    }

    @Test
    void shouldIndexFreshTreeWithCorrectPathsSizesAndChecksumsWhenComputing(@TempDir Path work) throws Exception
    {
        // GIVEN a fresh tree of user files
        writeFile(work, "a.txt", "alpha");
        File b = writeFile(work, "dir/b.txt", "beta");
        writeFile(work, "dir/sub/c.bin", "gamma-content");

        // WHEN the tree is computed
        RunStats stats = run(new RecordingProgress(), compute(work));

        // THEN every file is a new entry with correct relative paths, size and checksum
        assertEquals(3, stats.newEntries);
        assertEquals(0, stats.updatedEntries);
        assertEquals(0, stats.skippedUnchanged);

        Map<String, DbRow> byPath = readRows(compute(work)).stream()
                .collect(Collectors.toMap(DbRow::path, r -> r));
        assertEquals(3, byPath.size());
        assertTrue(byPath.containsKey("a.txt"));
        assertTrue(byPath.containsKey("dir/b.txt"), "paths are working-dir-relative and /-separated");
        assertTrue(byPath.containsKey("dir/sub/c.bin"));

        DbRow bRow = byPath.get("dir/b.txt");
        assertEquals(Checksums.sha1(b, () -> false), bRow.checksum());
        assertEquals(b.length(), bRow.size());
    }

    @Test
    void shouldSkipUnchangedFilesWhenReRunning(@TempDir Path work) throws Exception
    {
        // GIVEN a tree that has already been computed once
        writeFile(work, "a.txt", "alpha");
        writeFile(work, "dir/b.txt", "beta");
        writeFile(work, "dir/c.txt", "gamma");
        run(new RecordingProgress(), compute(work));

        // WHEN it is computed again with nothing changed
        RunStats second = run(new RecordingProgress(), compute(work));

        // THEN all files are skipped as unchanged
        assertEquals(3, second.skippedUnchanged);
        assertEquals(0, second.newEntries);
        assertEquals(0, second.updatedEntries);
    }

    @Test
    void shouldReHashWhenSizeChangesButMtimeIsPreserved(@TempDir Path work) throws Exception
    {
        // GIVEN a computed file whose mtime we can restore
        File f = writeFile(work, "f.txt", "short");
        run(new RecordingProgress(), compute(work));
        long storedMtime = f.lastModified();

        // WHEN it is rewritten with a different length but the exact original mtime
        writeFile(work, "f.txt", "a much longer body of content");
        assumeTrue(f.setLastModified(storedMtime), "filesystem must support setting mtime");
        RunStats stats = run(new RecordingProgress(), compute(work));

        // THEN the size change forces a re-hash even though the mtime is unchanged
        assertEquals(1, stats.updatedEntries, "size change must force a re-hash even with unchanged mtime");
        assertEquals(0, stats.skippedUnchanged);
    }

    @Test
    void shouldReHashWhenMtimeChanges(@TempDir Path work) throws Exception
    {
        // GIVEN a computed file
        File f = writeFile(work, "f.txt", "body");
        run(new RecordingProgress(), compute(work));

        // WHEN its mtime moves forward
        assumeTrue(f.setLastModified(f.lastModified() + 5_000), "filesystem must support setting mtime");
        RunStats stats = run(new RecordingProgress(), compute(work));

        // THEN the entry is re-hashed
        assertEquals(1, stats.updatedEntries);
    }

    @Test
    void shouldExcludeAppDirectoryWhenScanning(@TempDir Path work) throws Exception
    {
        // GIVEN a first run that creates Checksoomer/ with the DB and sidecar
        writeFile(work, "a.txt", "alpha");
        run(new RecordingProgress(), compute(work));

        // WHEN the tree is scanned again
        RunStats second = run(new RecordingProgress(), compute(work));

        // THEN only the user file is re-seen and nothing under the app directory is indexed
        assertEquals(1, second.skippedUnchanged);
        List<DbRow> rows = readRows(compute(work));
        assertEquals(1, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.path().startsWith(AppLayout.APP_DIR_NAME)),
                "no row should reference a file inside the app directory");
    }

    @Test
    void shouldScopeTheWalkWhenSubsetGiven(@TempDir Path work) throws Exception
    {
        // GIVEN files both inside and outside a subdirectory
        writeFile(work, "top.txt", "x");
        writeFile(work, "dir/inside.txt", "y");
        writeFile(work, "dir/sub/deep.txt", "z");

        // WHEN only the subdirectory is computed
        Path subset = work.resolve("dir");
        RunStats stats = run(new RecordingProgress(), cfg(work, subset, Mode.COMPUTE_CHECKSUMS));

        // THEN only files under the subset are indexed
        assertEquals(2, stats.newEntries);
        List<String> paths = readRows(compute(work)).stream().map(DbRow::path).sorted().toList();
        assertEquals(List.of("dir/inside.txt", "dir/sub/deep.txt"), paths);
    }

    @Test
    void shouldLogButNotFailWhenFileIsUnreadable(@TempDir Path work) throws Exception
    {
        // GIVEN a readable file and an unreadable one
        writeFile(work, "ok.txt", "fine");
        File locked = writeFile(work, "locked.txt", "secret");
        assumeTrue(locked.setReadable(false), "platform must support clearing the read permission");
        try
        {
            assumeTrue(!locked.canRead(), "platform must actually enforce the read permission");

            // WHEN the tree is computed
            RecordingProgress rec = new RecordingProgress();
            RunStats stats = run(rec, compute(work));

            // THEN the good file is indexed and the unreadable one is logged as ERROR_COMPUTING
            assertEquals(1, stats.newEntries);
            assertTrue(readDetailLog(stats).contains("ERROR_COMPUTING"));
        }
        finally
        {
            locked.setReadable(true);
        }
    }

    @Test
    void shouldMatchProgressDenominatorToEligibleFilesWhenComputing(@TempDir Path work) throws Exception
    {
        // GIVEN three eligible files
        writeFile(work, "a.txt", "1");
        writeFile(work, "b.txt", "2");
        writeFile(work, "dir/c.txt", "3");

        // WHEN the tree is computed
        RecordingProgress rec = new RecordingProgress();
        run(rec, compute(work));

        // THEN progress totals the eligible files and completion snaps to 100%
        assertEquals(3, rec.total, "progress total must equal the number of eligible files");
        assertTrue(rec.completed, "a normal completion must snap to 100%");
        assertEquals(3, rec.lastFiles);
    }

    @Test
    void shouldProduceNoRowsWhenDirectoryIsEmpty(@TempDir Path work) throws Exception
    {
        // GIVEN an empty working directory
        // WHEN it is computed
        RunStats stats = run(new RecordingProgress(), compute(work));

        // THEN nothing is indexed
        assertEquals(0, stats.newEntries);
        assertTrue(readRows(compute(work)).isEmpty());
    }

    @Test
    void shouldMarkNewlyComputedRowsAsTestedWhenComputing(@TempDir Path work) throws Exception
    {
        // GIVEN a single file
        writeFile(work, "a.txt", "alpha");

        // WHEN it is computed
        run(new RecordingProgress(), compute(work));

        // THEN the new row is marked tested so RESUME won't re-verify it
        assertEquals(1, countTested(compute(work)));
        DbRow r = row(compute(work), "a.txt");
        assertNotNull(r);
        assertTrue(r.tested());
    }
}
