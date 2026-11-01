package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.util.function.BooleanSupplier;

/**
 * The SQLite checksum store for one run: opens connections (creating the schema), maintains the
 * DB-checksum sidecar that detects corruption between sessions, and offers the small queries the
 * processing modes share. The per-mode page/walk logic lives in {@link ChecksumEngine}.
 */
final class ChecksumDb
{
    private static final Logger log = LoggerFactory.getLogger(ChecksumDb.class);

    private final AppLayout layout;

    ChecksumDb(RunConfig cfg)
    {
        this.layout = new AppLayout(cfg.workingDirectory());
    }

    AppLayout layout()
    {
        return layout;
    }

    /** Opens a connection to the run's database, creating the app directory and {@code checksums} table if needed. */
    Connection open() throws SQLException
    {
        try
        {
            // SQLite will not create missing parent directories, so make sure the app dir exists first.
            layout.ensureAppDir();
        }
        catch (IOException e)
        {
            throw new SQLException("Could not create app directory " + layout.appDir(), e);
        }

        String url = "jdbc:sqlite:" + layout.dbFile().getAbsolutePath();
        Connection conn = DriverManager.getConnection(url);

        try (Statement stmt = conn.createStatement())
        {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS checksums (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        modification_time INTEGER NOT NULL,
                        checksum TEXT NOT NULL,
                        path TEXT NOT NULL UNIQUE,
                        size INTEGER NOT NULL,
                        tested BOOLEAN DEFAULT FALSE
                    )
                    """);
            // Partial index over only the untested rows so test-mode pagination is O(log n + page) per
            // page instead of O(rows-tested-so-far); rows drop out of it as they are marked tested.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_untested ON checksums(id) WHERE tested = FALSE");
        }
        catch (SQLException e)
        {
            try
            {
                conn.close();
            }
            catch (SQLException closeEx)
            {
                e.addSuppressed(closeEx);
            }
            throw e;
        }

        return conn;
    }

    /**
     * Verifies the database file against its stored checksum. Returns true when there is no database
     * yet (nothing to verify) or the checksums match; false on a genuine mismatch (corruption) or when
     * the database is present but its sidecar is missing (a crash signature the sidecar exists to catch).
     *
     * @param abortRequested polled while hashing the DB file so a large DB can abort promptly
     * @throws AbortedException        if an abort was requested while hashing the DB file
     * @throws DbVerificationException if the database/sidecar could not be read (transient/retryable),
     *                                 as opposed to a real mismatch
     */
    boolean verifyChecksum(BooleanSupplier abortRequested) throws AbortedException, DbVerificationException
    {
        File dbFile = layout.dbFile();
        File checksumFile = layout.dbChecksumFile();

        if (!dbFile.exists())
        {
            return true; // No database yet, nothing to verify.
        }
        if (!checksumFile.exists())
        {
            // Database present but sidecar gone: exactly the crash signature the sidecar exists to
            // detect (e.g. a crash after the DB was created but before the first saveChecksum). Treat
            // as unverified rather than silently trusting it.
            log.warn("Database present but checksum sidecar missing for {}; treating as unverified", dbFile);
            return false;
        }

        try
        {
            String storedChecksum = Files.readString(checksumFile.toPath()).trim();
            // AbortedException is not an IOException, so it propagates past the catch below untouched.
            String currentChecksum = Checksums.sha1(dbFile, abortRequested);
            return storedChecksum.equals(currentChecksum);
        }
        catch (IOException e)
        {
            // Could not read the DB or sidecar (locked file, AV/share violation): retryable, NOT
            // corruption. Surface a distinct, retryable error instead of the hard corruption block.
            log.warn("Database checksum could not be read for verification", e);
            throw new DbVerificationException("Could not read the database for verification", e);
        }
    }

    /**
     * Recomputes and persists the DB-checksum sidecar. Once started, the hash always finishes (no abort).
     *
     * @return true on success; false if the sidecar could not be (re)written, so the caller can warn
     * the user — otherwise the next session would see stale ≠ current and falsely block an intact DB.
     */
    boolean saveChecksum()
    {
        try
        {
            File dbFile = layout.dbFile();
            if (dbFile.exists())
            {
                String checksum = Checksums.sha1(dbFile, () -> false);
                Files.writeString(layout.dbChecksumFile().toPath(), checksum);
            }
            return true;
        }
        catch (AbortedException e)
        {
            // The () -> false supplier never aborts; this is defensive only.
            log.error("Unexpected abort while saving database checksum", e);
            return false;
        }
        catch (IOException e)
        {
            log.error("Failed to save database checksum", e);
            return false;
        }
    }

    /**
     * Clears the {@code tested} flag for the whole DB or the configured subset and saves the checksum,
     * using a filter the caller computed once for the whole run (so the live {@code isDirectory()}
     * stat inside {@link Subsets#filter} can't desync this UPDATE from the later COUNT/SELECT).
     *
     * @return true on success; false if the sidecar could not be written, so the caller can warn
     * the user — otherwise the next session would see stale ≠ current and falsely block an intact DB.
     */
    boolean resetTestedFlags(SubsetFilter filter) throws SQLException
    {
        boolean saved = false;
        try (Connection conn = open())
        {
            // SubsetFilter.sqlFragment() is shaped to follow an existing WHERE; "WHERE TRUE" supplies one
            // (and resets every row when there is no subset).
            String sql = "UPDATE checksums SET tested = FALSE WHERE TRUE" + filter.sqlFragment();
            try (PreparedStatement pstmt = conn.prepareStatement(sql))
            {
                if (filter.param() != null)
                {
                    pstmt.setString(1, filter.param());
                }
                pstmt.executeUpdate();
            }
            saved = saveChecksum();
        }
        return saved;
    }

    /**
     * Counts untested rows, restricted to the run's (precomputed) subset filter.
     */
    long countUntested(Connection conn, SubsetFilter filter) throws SQLException
    {
        String countSql = "SELECT COUNT(*) FROM checksums WHERE tested = FALSE" + filter.sqlFragment();
        try (PreparedStatement pstmt = conn.prepareStatement(countSql))
        {
            if (filter.param() != null)
            {
                pstmt.setString(1, filter.param());
            }
            try (ResultSet rs = pstmt.executeQuery())
            {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }
}
