package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Resolves the locations of all app-managed files for a single working directory, and identifies
 * which files/directories belong to the app so scans can skip them.
 * <p>
 * Everything the app writes lives under {@code {workingDirectory}/Checksoomer}: the SQLite database,
 * its checksum sidecar, per-run error logs, and the removed-sums log.
 */
final class AppLayout
{
    static final String APP_NAME = "Checksoomer";
    /** Subdirectory of the working directory where all app files (DB, checksum, logs) are stored. */
    static final String APP_DIR_NAME = ".Checksoomer";
    static final String DB_CHECKSUM_FILE = "sumdb_checksum_[e5122403-3dc8-452e-b430-ae1bb4a65c3d].txt";
    static final String DB_FILE = "sumdb_[e5122403-3dc8-452e-b430-ae1bb4a65c3d].sqlite3";
    static final String ERROR_FILE_PREFIX = "errors_[e5122403-3dc8-452e-b430-ae1bb4a65c3d]_";
    static final String REMOVED_SUMS_TXT = "removed_sums_[e5122403-3dc8-452e-b430-ae1bb4a65c3d].txt";

    /**
     * Timestamp pattern for the per-run error-log filename.
     */
    private static final DateTimeFormatter ERROR_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final File workingDirectory;

    public AppLayout(File workingDirectory)
    {
        this.workingDirectory = workingDirectory;
    }

    /**
     * The single definition of "the app's own directory", shared by the count and process walks so
     * their {@code preVisitDirectory} skip can never drift apart and desync the progress total.
     */
    public static boolean isAppDir(Path dir, Path appDirPath)
    {
        return dir.toAbsolutePath().normalize().equals(appDirPath);
    }

    /**
     * The single definition of "a file we count and process", shared by the count and process walks
     * so the progress denominator always matches the work actually done.
     */
    public static boolean isEligibleFile(Path path, BasicFileAttributes attrs)
    {
        return attrs.isRegularFile() && !isInternalFile(path.getFileName().toString());
    }

    /**
     * The directory where all app files live: {@code <workingDirectory>/Checksoomer}.
     * This whole directory is excluded from scanning.
     */
    public File appDir()
    {
        return new File(workingDirectory, APP_DIR_NAME);
    }

    /** Ensures the app directory exists before any file inside it is opened/created. */
    public void ensureAppDir() throws IOException
    {
        Files.createDirectories(appDir().toPath());
    }

    public File dbFile()
    {
        return new File(appDir(), DB_FILE);
    }

    public File dbChecksumFile()
    {
        return new File(appDir(), DB_CHECKSUM_FILE);
    }

    public File removedSumsFile()
    {
        return new File(appDir(), REMOVED_SUMS_TXT);
    }

    static boolean isInternalFile(String fileName)
    {
        return fileName.startsWith(DB_FILE)
                || fileName.equals(DB_CHECKSUM_FILE)
                || fileName.startsWith(ERROR_FILE_PREFIX)
                || fileName.equals(REMOVED_SUMS_TXT);
    }

    /** A fresh, timestamped error-log file in the app directory for this run. */
    public File newErrorFile()
    {
        return new File(appDir(), ERROR_FILE_PREFIX + LocalDateTime.now().format(ERROR_FILE_TIMESTAMP) + ".txt");
    }

    /**
     * Canonical (symlink-resolved, normalized, absolute) path of the app directory, for comparison
     * during file-tree walks. Canonical so it matches the canonical roots the compute walk uses
     * (see {@link ChecksumEngine}); otherwise a symlinked working-directory component
     * could leave the app's own directory un-skipped.
     */
    public Path appDirPath() throws IOException
    {
        return FsPaths.canonical(appDir());
    }
}
