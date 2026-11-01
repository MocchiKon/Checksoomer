package com.example;

import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Swing-free processing engine. Runs the selected {@link Mode} against the checksum database,
 * reporting progress through a {@link Progress} and honoring stop/abort requests via a
 * {@link RunControl}. All UI concerns live in {@link Main}.
 */
final class ChecksumEngine
{
    private static final Logger log = LoggerFactory.getLogger(ChecksumEngine.class);

    private static final int PAGE_SIZE = 100;

    private final RunControl control;
    private final Progress progress;
    private final BatchCommitter batchCommitter;

    /**
     * One reusable 1 MB hash buffer; hashing is single-threaded on the worker, so sharing it is safe.
     */
    private final byte[] hashBuffer = Checksums.newBuffer();

    ChecksumEngine(RunControl control, Progress progress)
    {
        this(control, progress, new BatchCommitter());
    }

    // Package-private for testing
    ChecksumEngine(RunControl control, Progress progress, BatchCommitter batchCommitter)
    {
        this.control = control;
        this.progress = progress;
        this.batchCommitter = batchCommitter;
    }

    /**
     * Verifies the database file against its stored checksum (see {@link ChecksumDb#verifyChecksum}).
     *
     * @throws AbortedException if an abort was requested while hashing the DB file
     */
    boolean verifyDatabaseChecksum(RunConfig cfg) throws AbortedException, DbVerificationException
    {
        return new ChecksumDb(cfg).verifyChecksum(control::isAbortRequested);
    }

    /**
     * Runs the configured mode. The stored DB checksum sidecar is refreshed after every committed
     * modification of the database (the NEW_TEST reset, each batch commit, and the stop/abort unwind),
     * not just at the end. That way a crash or power loss mid-run leaves the sidecar consistent with the
     * database that actually landed on disk, so the next session verifies cleanly instead of falsely
     * flagging corruption. The {@code finally} block re-stamps once more as a final safety net.
     * The NEW_TEST reset handles its own checksum stamping via {@link ChecksumDb#resetTestedFlags}.
     */
    RunStats run(RunConfig cfg) throws Exception
    {
        RunStats stats = new RunStats(cfg.mode());
        ChecksumDb db = new ChecksumDb(cfg);
        try
        {
            switch (cfg.mode())
            {
                case NEW_TEST ->
                {
                    // Compute the subset filter once for the whole run (it canonicalizes and stats the
                    // subset directory); reuse it for the reset, the COUNT and the page SELECT so a
                    // mid-run isDirectory() change can't desync them.
                    SubsetFilter filter = Subsets.filter(cfg);
                    if (!db.resetTestedFlags(filter))
                    {
                        stats.sidecarWriteFailed = true;
                    }
                    testChecksums(cfg, db, stats, filter);
                }
                case RESUME_TEST -> testChecksums(cfg, db, stats, Subsets.filter(cfg));
                case COMPUTE_CHECKSUMS -> computeChecksums(cfg, db, stats);
                case CLEAN_CHECKSUMS_FOR_REMOVED_FILES -> cleanChecksums(cfg, db, stats, Subsets.filter(cfg));
            }
            // On a graceful stop/abort the modes skip snapComplete(); snap the labels to the true final
            // counts here so they don't disagree with the summary popup.
            if (shouldStop())
            {
                progress.snapFinal();
            }
        }
        finally
        {
            if (!db.saveChecksum())
            {
                stats.sidecarWriteFailed = true;
            }
        }
        return stats;
    }

    private boolean shouldStop()
    {
        return control.shouldStop();
    }

    /**
     * Commits the open transaction and immediately re-stamps the DB-checksum sidecar so the two never
     * drift apart. Every committed mutation of the database goes through here; that way a crash or power
     * loss between batched commits leaves the sidecar describing exactly the rows that landed on disk,
     * and the next session can verify instead of falsely reporting corruption. A sidecar write failure
     * is recorded on {@code stats} rather than thrown, so one bad stamp can't abort an otherwise-good run.
     * @throws SQLException
     */
    @SneakyThrows
    private void commitAndStamp(Connection conn, ChecksumDb db, RunStats stats)
    {
        conn.commit();
        if (!db.saveChecksum())
        {
            stats.sidecarWriteFailed = true;
        }
    }

    /**
     * Hashes a file with the shared buffer, aborting promptly mid-read when an abort has been requested.
     */
    private Checksums.Sha1Result hashFile(File file) throws IOException, AbortedException
    {
        return Checksums.hash(file, control::isAbortRequested, hashBuffer);
    }

    // ---------------------------------------------------------------------------------------------
    // Test modes
    // ---------------------------------------------------------------------------------------------

    private void testChecksums(RunConfig cfg, ChecksumDb db, RunStats stats, SubsetFilter filter) throws Exception
    {
        File errorFile = db.layout().newErrorFile();
        stats.detailLogPath = errorFile.getAbsolutePath();
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg);

        try (Connection conn = db.open())
        {
            conn.setAutoCommit(false);

            // Count total files to test for progress bar
            progress.setTotal(db.countUntested(conn, filter));

            // Mark-and-sweep pagination: each page is the next PAGE_SIZE untested rows (the idx_untested
            // partial index makes that ~O(log n + page)), and marking them tested removes them from the
            // index, so the same query re-run finds the next page. No ORDER BY (order doesn't matter once
            // rows can't reappear) and no keyset cursor needed.
            String pageSql = "SELECT id, path, checksum, modification_time FROM checksums WHERE tested = FALSE"
                    + filter.sqlFragment() + " LIMIT " + PAGE_SIZE;

            try (PreparedStatement pageStmt = conn.prepareStatement(pageSql);
                    PreparedStatement markStmt = conn.prepareStatement("UPDATE checksums SET tested = TRUE WHERE id = ?"))
            {
                if (filter.param() != null)
                {
                    pageStmt.setString(1, filter.param());
                }

                while (true)
                {
                    if (shouldStop())
                    {
                        commitAndStamp(conn, db, stats);
                        return;
                    }

                    // Buffer the page and close the cursor BEFORE mutating: the page query scans
                    // idx_untested and the UPDATEs mutate that same index (the tested column), so we
                    // must not modify it while a cursor is traversing it (Halloween problem).
                    List<Row> page = new ArrayList<>(PAGE_SIZE);
                    try (ResultSet rs = pageStmt.executeQuery())
                    {
                        while (rs.next())
                        {
                            page.add(new Row(rs.getLong("id"), rs.getString("path"), rs.getString("checksum"),
                                    rs.getLong("modification_time")));
                        }
                    }
                    if (page.isEmpty())
                        break;

                    for (Row row : page)
                    {
                        if (shouldStop())
                        {
                            commitAndStamp(conn, db, stats);
                            return;
                        }

                        // Update status before processing so the first file shows immediately (throttling can skip the update after)
                        progress.status("Testing: " + row.path());

                        // Verify file integrity
                        File file = resolver.resolve(row.path());
                        FileIntegrityResult integrityResult;
                        if (file == null)
                        {
                            integrityResult = new FileIntegrityResult(ResultKind.INVALID_PATH,
                                    RunLog.errorLine("INVALID_PATH", row.path(), "", row.checksum()), 0);
                        }
                        else
                        {
                            try
                            {
                                integrityResult = verifyFileIntegrity(file, row.checksum(), row.storedModTime());
                            }
                            catch (AbortedException e)
                            {
                                // Discard the current row's result: do NOT mark it tested.
                                commitAndStamp(conn, db, stats);
                                return;
                            }
                        }

                        // Write error message only if there's an error
                        if (integrityResult.errorMessage() != null)
                        {
                            RunLog.append(errorFile, RunLog.ERROR_LOG_HEADER, integrityResult.errorMessage());
                        }
                        stats.record(integrityResult.kind());

                        // Mark as tested (reused statement)
                        markStmt.setLong(1, row.id());
                        markStmt.executeUpdate();

                        progress.fileProcessed(integrityResult.bytesHashed(), "Testing: " + row.path());

                        tryCommitIfIntervalElapsed(db, stats, conn);
                    }
                }

                commitAndStamp(conn, db, stats);
                if (!shouldStop())
                {
                    progress.snapComplete();
                }
            }
        }
    }

    private FileIntegrityResult verifyFileIntegrity(File file, String storedChecksum, long storedModTime) throws AbortedException
    {
        if (!file.exists())
        {
            String errorMessage = RunLog.errorLine("MISSING", file.getAbsolutePath(), "", storedChecksum);
            return new FileIntegrityResult(ResultKind.MISSING, errorMessage, 0);
        }

        long currentModTime = file.lastModified();
        boolean modTimeMatches = currentModTime == storedModTime;

        try
        {
            // AbortedException is not an IOException, so it propagates out of this method untouched.
            Checksums.Sha1Result result = hashFile(file);
            if (!result.hex().equals(storedChecksum))
            {
                ResultKind kind = modTimeMatches ? ResultKind.WRONG_CHECKSUM : ResultKind.MODIFIED_DIFF_CHECKSUM;
                String errorMessage = RunLog.errorLine(kind.name(), file.getAbsolutePath(), storedChecksum, result.hex());
                return new FileIntegrityResult(kind, errorMessage, result.bytesRead());
            }
            return new FileIntegrityResult(ResultKind.OK, null, result.bytesRead());
        }
        catch (IOException e)
        {
            log.warn("Failed to compute checksum for {}", file, e);
            String errorMessage = RunLog.errorLine("ERROR_CALCULATING", file.getAbsolutePath(), "", storedChecksum);
            return new FileIntegrityResult(ResultKind.ERROR_CALCULATING, errorMessage, 0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Compute mode
    // ---------------------------------------------------------------------------------------------

    private void computeChecksums(RunConfig cfg, ChecksumDb db, RunStats stats) throws Exception
    {
        File errorFile = db.layout().newErrorFile();
        stats.detailLogPath = errorFile.getAbsolutePath();

        try (Connection conn = db.open())
        {
            conn.setAutoCommit(false);
            // Canonicalize the working root once and use that single value for walking, relativizing
            // (DB keys) and the app-dir skip, so a symlinked/short-name working-dir component can't make
            // stored keys disagree with the test/clean filter (which also canonicalizes).
            Path workingRoot = FsPaths.canonical(cfg.workingDirectory());
            Path startPath = cfg.subset() != null ? FsPaths.canonical(cfg.subset()) : workingRoot;
            Path appDirPath = db.layout().appDirPath();

            // Count total files for progress bar (walk that tolerates unreadable directories). This is a
            // second full traversal: a deliberate trade-off for an accurate progress denominator, kept in
            // sync with the processing walk via the shared isAppDir/isEligibleFile predicates.
            progress.status("Counting files...");
            progress.setTotal(countComputeFiles(startPath, appDirPath));

            // Prepare the per-file statements once and reuse them across the whole walk.
            String checkSql = "SELECT modification_time, size FROM checksums WHERE path = ?";
            String upsertSql = "INSERT INTO checksums (modification_time, checksum, path, size, tested) "
                    + "VALUES (?, ?, ?, ?, TRUE) "
                    + "ON CONFLICT(path) DO UPDATE SET "
                    + "modification_time = excluded.modification_time, checksum = excluded.checksum, "
                    + "size = excluded.size, tested = TRUE";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                    PreparedStatement upsertStmt = conn.prepareStatement(upsertSql))
            {
                try
                {
                    Files.walkFileTree(startPath, new SimpleFileVisitor<>()
                    {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        {
                            // Never descend into the app's own directory (DB, journal, logs).
                            if (AppLayout.isAppDir(dir, appDirPath))
                            {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                        {
                            if (shouldStop())
                            {
                                return FileVisitResult.TERMINATE;
                            }
                            if (!AppLayout.isEligibleFile(path, attrs))
                            {
                                return FileVisitResult.CONTINUE;
                            }
                            try
                            {
                                processComputeFile(conn, db, stats, workingRoot, path, attrs, errorFile,
                                        checkStmt, upsertStmt);
                            }
                            catch (AbortedException ae)
                            {
                                throw new WalkAbortedException();
                            }
                            catch (SQLException se)
                            {
                                throw new UncheckedSqlException(se);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc)
                        {
                            logWalkFailure(errorFile, file, exc);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        {
                            if (exc != null)
                            {
                                logWalkFailure(errorFile, dir, exc);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                catch (WalkAbortedException abortSignal)
                {
                    commitAndStamp(conn, db, stats);
                    return;
                }
                catch (UncheckedSqlException sqlSignal)
                {
                    throw sqlSignal.getCause();
                }
            }

            if (shouldStop())
            {
                commitAndStamp(conn, db, stats);
                return;
            }

            commitAndStamp(conn, db, stats);
            progress.snapComplete();
        }
    }

    private void processComputeFile(Connection conn, ChecksumDb db, RunStats stats, Path workingRoot, Path path,
                                    BasicFileAttributes attrs, File errorFile,
            PreparedStatement checkStmt, PreparedStatement upsertStmt)
            throws AbortedException, SQLException
    {
        File file = path.toFile();
        final String relativePath = Subsets.toDbPath(workingRoot, path);

        // Reuse the attributes the walk already obtained instead of issuing extra stat syscalls.
        long modTime = attrs.lastModifiedTime().toMillis();
        long fileSize = attrs.size();

        // Update status before processing so the first file shows immediately (throttling can skip the update after)
        progress.status("Computing: " + relativePath);

        // Check if already computed and up to date (mtime AND size; size catches content changes that
        // preserve mtime, e.g. touch -r / archive restore / same-second rewrite).
        boolean exists = false;
        checkStmt.setString(1, relativePath);
        try (ResultSet rs = checkStmt.executeQuery())
        {
            if (rs.next())
            {
                exists = true;
                long storedModTime = rs.getLong("modification_time");
                long storedSize = rs.getLong("size");
                boolean sizeMatches = fileSize == storedSize;
                if (modTime == storedModTime && sizeMatches)
                {
                    stats.skippedUnchanged++;
                    progress.fileProcessed(0, "Skipped (unchanged): " + relativePath); // no bytes read
                    return; // Already computed and up to date
                }
            }
        }

        // Calculate checksum (AbortedException is not an IOException, so it propagates to the walk).
        String checksum;
        long bytesHashed;
        try
        {
            Checksums.Sha1Result result = hashFile(file);
            checksum = result.hex();
            bytesHashed = result.bytesRead();
        }
        catch (IOException e)
        {
            RunLog.append(errorFile, RunLog.ERROR_LOG_HEADER, RunLog.errorLine("ERROR_COMPUTING", file.getAbsolutePath(), "", ""));
            progress.incrementErrors();
            log.warn("Failed to compute checksum for {}", file, e);
            progress.fileProcessed(0, "Computing: " + relativePath);
            return;
        }

        // Upsert by path (ON CONFLICT keeps the row's id stable instead of reassigning it).
        upsertStmt.setLong(1, modTime);
        upsertStmt.setString(2, checksum);
        upsertStmt.setString(3, relativePath);
        upsertStmt.setLong(4, fileSize);
        upsertStmt.executeUpdate();

        if (exists)
        {
            stats.updatedEntries++;
        }
        else
        {
            stats.newEntries++;
        }

        progress.fileProcessed(bytesHashed, "Computing: " + relativePath);

        tryCommitIfIntervalElapsed(db, stats, conn);
    }

    private long countComputeFiles(Path startPath, Path appDirPath) throws IOException
    {
        var count = new AtomicLong(0);
        Files.walkFileTree(startPath, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                // Shares isAppDir/isEligibleFile with the processing walk so the totals match.
                if (AppLayout.isAppDir(dir, appDirPath))
                {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
            {
                if (shouldStop())
                {
                    return FileVisitResult.TERMINATE;
                }
                if (AppLayout.isEligibleFile(path, attrs))
                {
                    count.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                // Failures are logged during the processing pass, not here.
                return FileVisitResult.CONTINUE;
            }
        });
        return count.get();
    }

    private void logWalkFailure(File errorFile, Path path, IOException exc)
    {
        RunLog.append(errorFile, RunLog.ERROR_LOG_HEADER, RunLog.errorLine("ERROR_WALKING", path.toString(), "", ""));
        progress.incrementErrors();
        log.warn("Failed to walk {}", path, exc);
        progress.fileProcessed(0, "Walk error: " + path);
    }

    // ---------------------------------------------------------------------------------------------
    // Clean mode
    // ---------------------------------------------------------------------------------------------

    private void cleanChecksums(RunConfig cfg, ChecksumDb db, RunStats stats, SubsetFilter filter) throws Exception
    {
        File logFile = db.layout().removedSumsFile();
        stats.detailLogPath = logFile.getAbsolutePath();
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg);

        try (Connection conn = db.open())
        {
            conn.setAutoCommit(false);
            long removedCount = 0;
            long lastSeenId = 0;

            // Count total entries for progress bar, restricted to the picked subset so the denominator
            // matches the rows actually scanned below (SubsetFilter.sqlFragment() follows an existing
            // WHERE; "WHERE TRUE" supplies one for the no-subset case).
            String countSql = "SELECT COUNT(*) FROM checksums WHERE TRUE" + filter.sqlFragment();
            try (PreparedStatement countStmt = conn.prepareStatement(countSql))
            {
                if (filter.param() != null)
                {
                    countStmt.setString(1, filter.param());
                }
                try (ResultSet countRs = countStmt.executeQuery())
                {
                    if (countRs.next())
                    {
                        progress.setTotal(countRs.getLong(1));
                    }
                }
            }

            // Keyset pagination by id, restricted to the subset so only its rows are paged and counted
            // against the progress bar (the bind order is id first, then the optional subset param).
            String pageSql = "SELECT id, path, checksum FROM checksums WHERE id > ?"
                    + filter.sqlFragment() + " ORDER BY id LIMIT " + PAGE_SIZE;
            try (PreparedStatement pageStmt = conn.prepareStatement(pageSql);
                    PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM checksums WHERE id = ?"))
            {
                if (filter.param() != null)
                {
                    pageStmt.setString(2, filter.param());
                }
                while (true)
                {
                    if (shouldStop())
                    {
                        commitAndStamp(conn, db, stats);
                        stats.entriesRemoved = removedCount;
                        return;
                    }

                    // Buffer the page, then close the cursor before issuing DELETEs (keyset cursor by id,
                    // so deleted/seen rows can't reappear in a later page).
                    pageStmt.setLong(1, lastSeenId);
                    List<CleanRow> page = new ArrayList<>(PAGE_SIZE);
                    try (ResultSet rs = pageStmt.executeQuery())
                    {
                        while (rs.next())
                        {
                            page.add(new CleanRow(rs.getLong("id"), rs.getString("path"), rs.getString("checksum")));
                        }
                    }
                    if (page.isEmpty())
                        break;

                    for (CleanRow row : page)
                    {
                        if (shouldStop())
                        {
                            commitAndStamp(conn, db, stats);
                            stats.entriesRemoved = removedCount;
                            return;
                        }

                        lastSeenId = row.id();

                        // Update status before processing so the first file shows immediately (throttling can skip the update after)
                        progress.status("Checking: " + row.path());

                        // The subset restriction is enforced by the SQL filter above, so every paged row
                        // belongs to the subset and is genuinely examined.
                        stats.entriesScanned++;

                        File file = resolver.resolve(row.path());

                        // If the path is invalid or the file no longer exists, remove it from database and log it.
                        if (file == null || !file.exists())
                        {
                            String reason = file == null ? "INVALID_PATH" : "MISSING_FILE";
                            RunLog.append(logFile, RunLog.REMOVED_LOG_HEADER,
                                    "REMOVED ENTRY [" + reason + "]: " + row.path() + " | Checksum: " + row.checksum() + "\n");

                            // Remove from database (reused statement)
                            deleteStmt.setLong(1, row.id());
                            deleteStmt.executeUpdate();
                            removedCount++;
                            tryCommitIfIntervalElapsed(db, stats, conn);
                        }
                        progress.fileProcessed(0, "Checking: " + row.path());
                    }
                }
            }

            commitAndStamp(conn, db, stats);
            stats.entriesRemoved = removedCount;
            progress.snapComplete();
        }
    }

    private void tryCommitIfIntervalElapsed(ChecksumDb db, RunStats stats, Connection conn)
    {
        batchCommitter.tryCommit(() -> commitAndStamp(conn, db, stats));
    }

    /**
     * One buffered checksum row (the columns the test pages read), held while the cursor is closed.
     */
    private record Row(long id, String path, String checksum, long storedModTime)
    {
    }

    /**
     * One buffered checksum row for clean mode (doesn't need storedModTime).
     */
    private record CleanRow(long id, String path, String checksum)
    {
    }

    /** Signals an abort raised inside a file-tree walk (which only permits IOException). */
    private static final class WalkAbortedException extends RuntimeException { }

    /** Carries a checked SQLException out of a file-tree walk so it can be unwrapped at the call site. */
    private static final class UncheckedSqlException extends RuntimeException
    {
        UncheckedSqlException(SQLException cause)
        {
            super(cause);
        }

        @Override
        public SQLException getCause()
        {
            return (SQLException) super.getCause();
        }
    }
}