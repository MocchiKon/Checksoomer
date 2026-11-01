package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChecksumDbTest
{
    private static RunConfig cfg(Path workingDir)
    {
        return new RunConfig(workingDir.toFile(), null, Mode.COMPUTE_CHECKSUMS, false);
    }

    private static void seedRow(Statement stmt, String path, boolean tested) throws SQLException
    {
        stmt.execute("INSERT INTO checksums (modification_time, checksum, path, size, tested) "
                + "VALUES (0, 'x', '" + path + "', 0, " + (tested ? "TRUE" : "FALSE") + ")");
    }

    private static boolean hasColumn(Statement stmt, String column) throws SQLException
    {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(checksums)"))
        {
            while (rs.next())
            {
                if (column.equalsIgnoreCase(rs.getString("name")))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void shouldCreateSchemaWithSizeColumnAndUntestedIndexWhenOpeningFreshDb(@TempDir Path tmp) throws SQLException
    {
        // GIVEN a db for a fresh working directory
        ChecksumDb db = new ChecksumDb(cfg(tmp));

        // WHEN it is opened
        try (Connection conn = db.open(); Statement stmt = conn.createStatement())
        {
            // THEN the size column is required (NOT NULL) and the partial untested index exist
            assertTrue(hasColumn(stmt, "size"), "size column should exist on a fresh database");
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(checksums)"))
            {
                boolean sizeNotNull = false;
                while (rs.next())
                {
                    if ("size".equalsIgnoreCase(rs.getString("name")) && rs.getInt("notnull") == 1)
                    {
                        sizeNotNull = true;
                        break;
                    }
                }
                assertTrue(sizeNotNull, "size column should be NOT NULL on a fresh database");
            }

            boolean hasIndex = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA index_list(checksums)"))
            {
                while (rs.next())
                {
                    if ("idx_untested".equalsIgnoreCase(rs.getString("name")))
                    {
                        hasIndex = true;
                    }
                }
            }
            assertTrue(hasIndex, "partial idx_untested index should exist");
        }
    }

    @Test
    void shouldVerifyOkWhenNoDatabaseYet(@TempDir Path tmp) throws Exception
    {
        // GIVEN a db that has never been opened
        ChecksumDb db = new ChecksumDb(cfg(tmp));

        // WHEN the checksum is verified
        // THEN it passes because there is nothing to corrupt
        assertTrue(db.verifyChecksum(() -> false), "no database yet should verify as OK");
    }

    @Test
    void shouldFailVerificationWhenDbPresentButSidecarMissing(@TempDir Path tmp) throws Exception
    {
        // GIVEN a database file that exists but was never stamped with a sidecar
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open())
        {
            // open() creates the database file but no sidecar is written yet.
        }
        assertTrue(db.layout().dbFile().exists());
        assertFalse(db.layout().dbChecksumFile().exists());

        // WHEN the checksum is verified
        // THEN a present DB with a missing sidecar is treated as unverified
        assertFalse(db.verifyChecksum(() -> false),
                "present DB with missing sidecar should be treated as unverified");
    }

    @Test
    void shouldVerifyWhenChecksumIsSavedThenVerified(@TempDir Path tmp) throws Exception
    {
        // GIVEN a freshly created database file
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open())
        {
            // create the database file
        }

        // WHEN the checksum is saved
        assertTrue(db.saveChecksum(), "saveChecksum should succeed");

        // THEN the sidecar exists and verification passes
        assertTrue(db.layout().dbChecksumFile().exists());
        assertTrue(db.verifyChecksum(() -> false), "a freshly stamped DB should verify");
    }

    @Test
    void shouldFailVerificationWhenDatabaseChangedAfterSave(@TempDir Path tmp) throws Exception
    {
        // GIVEN a stamped database
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open())
        {
            // create the database file
        }
        assertTrue(db.saveChecksum());

        // WHEN the database file is mutated after stamping
        Files.write(db.layout().dbFile().toPath(), new byte[] { 0 }, StandardOpenOption.APPEND);

        // THEN verification fails because stored != current
        assertFalse(db.verifyChecksum(() -> false), "a changed DB must fail verification");
    }

    @Test
    void shouldThrowRetryableExceptionWhenSidecarIsUnreadable(@TempDir Path tmp) throws Exception
    {
        // GIVEN a stamped database whose sidecar cannot be read
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open())
        {
            // create the database file
        }
        assertTrue(db.saveChecksum());

        File sidecar = db.layout().dbChecksumFile();
        assumeTrue(sidecar.setReadable(false), "platform must support clearing the read permission");
        try
        {
            assumeTrue(!sidecar.canRead(), "platform must actually enforce the read permission");

            // WHEN the checksum is verified
            // THEN an unreadable sidecar is a retryable error, not silent corruption
            assertThrows(DbVerificationException.class, () -> db.verifyChecksum(() -> false),
                    "an unreadable sidecar is a retryable error, not silent corruption");
        }
        finally
        {
            sidecar.setReadable(true);
        }
    }

    @Test
    void shouldCountOnlyMatchingUntestedRowsWhenSubsetFilterApplied(@TempDir Path tmp) throws Exception
    {
        // GIVEN a mix of tested and untested rows across two directories
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open(); Statement stmt = conn.createStatement())
        {
            seedRow(stmt, "alpha/1.txt", false);
            seedRow(stmt, "alpha/2.txt", false);
            seedRow(stmt, "beta/3.txt", true);
            seedRow(stmt, "beta/4.txt", false);

            // WHEN untested rows are counted with and without a subset filter
            // THEN the count honours the filter
            assertEquals(3, db.countUntested(conn, SubsetFilter.NONE));

            SubsetFilter alpha = new SubsetFilter(" AND path LIKE ? ESCAPE '\\'", "alpha/%");
            assertEquals(2, db.countUntested(conn, alpha));
        }
    }

    @Test
    void shouldClearOnlyFilteredRowsWhenResettingTestedFlags(@TempDir Path tmp) throws Exception
    {
        // GIVEN three tested rows across two directories
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open(); Statement stmt = conn.createStatement())
        {
            seedRow(stmt, "alpha/1.txt", true);
            seedRow(stmt, "alpha/2.txt", true);
            seedRow(stmt, "beta/3.txt", true);
        }

        // WHEN tested flags are reset for alpha/ only
        assertTrue(db.resetTestedFlags(new SubsetFilter(" AND path LIKE ? ESCAPE '\\'", "alpha/%")));

        // THEN only the two alpha/ rows are cleared; beta/ stays tested
        try (Connection conn = db.open())
        {
            assertEquals(2, db.countUntested(conn, SubsetFilter.NONE));
        }
    }

    @Test
    void shouldClearEveryRowWhenResettingTestedFlagsWithNone(@TempDir Path tmp) throws Exception
    {
        // GIVEN two tested rows
        ChecksumDb db = new ChecksumDb(cfg(tmp));
        try (Connection conn = db.open(); Statement stmt = conn.createStatement())
        {
            seedRow(stmt, "a.txt", true);
            seedRow(stmt, "b.txt", true);
        }

        // WHEN tested flags are reset with the NONE filter
        assertTrue(db.resetTestedFlags(SubsetFilter.NONE));

        // THEN every row becomes untested
        try (Connection conn = db.open())
        {
            assertEquals(2, db.countUntested(conn, SubsetFilter.NONE));
        }
    }
}
