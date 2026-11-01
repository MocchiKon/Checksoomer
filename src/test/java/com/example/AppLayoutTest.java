package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the scan-exclusion predicates that keep the app's own files out of the index.
 */
class AppLayoutTest
{
    private static BasicFileAttributes attrs(Path p) throws Exception
    {
        return Files.readAttributes(p, BasicFileAttributes.class);
    }

    @Test
    void shouldMatchAllAppManagedNamesWhenCheckingIsInternalFile()
    {
        assertTrue(AppLayout.isInternalFile(AppLayout.DB_FILE));
        assertTrue(AppLayout.isInternalFile(AppLayout.DB_FILE + "-journal"), "SQLite journal sidecar");
        assertTrue(AppLayout.isInternalFile(AppLayout.DB_FILE + "-wal"), "SQLite WAL sidecar");
        assertTrue(AppLayout.isInternalFile(AppLayout.DB_FILE + "-shm"), "SQLite shared-memory sidecar");
        assertTrue(AppLayout.isInternalFile(AppLayout.DB_CHECKSUM_FILE));
        assertTrue(AppLayout.isInternalFile(AppLayout.ERROR_FILE_PREFIX + "2026-06-19_10-00-00-000.txt"));
        assertTrue(AppLayout.isInternalFile(AppLayout.REMOVED_SUMS_TXT));
    }

    @Test
    void shouldReturnFalseWhenCheckingIsInternalFileForUserFiles()
    {
        assertFalse(AppLayout.isInternalFile("photo.jpg"));
        assertFalse(AppLayout.isInternalFile("notes.txt"));
        assertFalse(AppLayout.isInternalFile("sumdb_other.sqlite3"));
    }

    @Test
    void shouldMatchOnlyCanonicalAppDirectoryWhenCheckingIsAppDir(@TempDir Path work) throws Exception
    {
        // GIVEN
        Path appDirPath = new AppLayout(work.toFile()).appDirPath();

        // WHEN the app directory, a subdirectory and the working directory are checked
        // THEN only the app directory itself matches
        assertTrue(AppLayout.isAppDir(appDirPath, appDirPath));
        assertFalse(AppLayout.isAppDir(appDirPath.resolve("sub"), appDirPath));
        assertFalse(AppLayout.isAppDir(work, appDirPath), "the working directory itself is not the app dir");
    }

    @Test
    void shouldSkipInternalFilesButAcceptUserFilesWhenCheckingEligibility(@TempDir Path work) throws Exception
    {
        // GIVEN a user file and an internal db file
        Path user = work.resolve("user.txt");
        Files.writeString(user, "x");
        Path internal = work.resolve(AppLayout.DB_FILE);
        Files.writeString(internal, "db");

        // WHEN / THEN
        assertTrue(AppLayout.isEligibleFile(user, attrs(user)));
        assertFalse(AppLayout.isEligibleFile(internal, attrs(internal)));
    }

    @Test
    void shouldCreateTimestampedFileInsideAppDirectoryWhenMakingNewErrorFile(@TempDir Path work)
    {
        // GIVEN
        AppLayout layout = new AppLayout(work.toFile());

        // WHEN
        File errorFile = layout.newErrorFile();

        // THEN its name is timestamped and it lives inside the app directory
        String name = errorFile.getName();
        String expectedPattern = Pattern.quote(AppLayout.ERROR_FILE_PREFIX)
                + "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.txt";
        assertTrue(name.matches(expectedPattern),
                "filename should be <prefix>yyyy-MM-dd_HH-mm-ss-SSS.txt but was: " + name);
        assertTrue(FsPaths.isSame(errorFile.getParentFile(), layout.appDir()),
                "error logs live inside the app directory");
    }
}
