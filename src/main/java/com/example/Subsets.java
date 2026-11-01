package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Maps between a run's subset selection, the working-directory-relative paths stored in the database,
 * and the SQL subset filters used to restrict queries to that subset.
 */
final class Subsets
{
    private Subsets() { }

    /**
     * The working-directory-relative, {@code /}-separated path used as a row's key in the database.
     * Both the compute (write) and test/clean (read) paths must produce keys in this exact form.
     */
    static String toDbPath(Path workingRoot, Path file)
    {
        return workingRoot.relativize(file).toString().replace('\\', '/');
    }

    /** The subset's path relative to the working directory ({@code /}-separated). Empty when they coincide. */
    static String relativePath(RunConfig cfg) throws IOException
    {
        Path workingDirPath = FsPaths.canonical(cfg.workingDirectory());
        Path subsetPath = FsPaths.canonical(cfg.subset());
        if (!subsetPath.startsWith(workingDirPath))
        {
            throw new IOException("Subset is outside working directory");
        }
        return toDbPath(workingDirPath, subsetPath);
    }

    /**
     * Builds the optional subset {@code WHERE} fragment (and its bind parameter) for SELECT/COUNT queries.
     * Returns {@link SubsetFilter#NONE} for "no subset" or when the subset resolves to the working directory itself.
     */
    static SubsetFilter filter(RunConfig cfg) throws IOException
    {
        if (cfg.subset() == null)
        {
            return SubsetFilter.NONE;
        }
        String relPath = relativePath(cfg);
        if (relPath.isEmpty())
        {
            return SubsetFilter.NONE;
        }
        if (cfg.subset().isDirectory())
        {
            return new SubsetFilter(" AND path LIKE ? ESCAPE '\\'", FsPaths.escapeLike(relPath) + "/%");
        }
        return new SubsetFilter(" AND path = ?", relPath);
    }

    /**
     * Resolves DB-stored relative paths back to files under a run's working directory. Built once per
     * run via {@link #forRun} so the working directory is canonicalized a single time rather than once
     * per row.
     */
    static final class Resolver
    {
        private final RunConfig cfg;
        private final Path workingRoot;

        private Resolver(RunConfig cfg, Path workingRoot)
        {
            this.cfg = cfg;
            this.workingRoot = workingRoot;
        }

        /**
         * Captures the canonical working directory once for the whole run.
         */
        static Resolver forRun(RunConfig cfg) throws IOException
        {
            return new Resolver(cfg, FsPaths.canonical(cfg.workingDirectory()));
        }

        /**
         * Resolves a DB-stored relative path back to a file under the working directory, or {@code null}
         * if it is invalid or would escape the working directory (path traversal guard).
         */
        File resolve(String dbPath)
        {
            Path resolvedPath = workingRoot.resolve(dbPath).normalize();
            if (!resolvedPath.startsWith(workingRoot))
            {
                return null;
            }
            return resolvedPath.toFile();
        }

        /**
         * True if the DB-stored path resolves to a file within the run's subset (true when there is no subset).
         */
        boolean isWithin(String dbPath)
        {
            if (cfg.subset() == null)
            {
                return true;
            }
            File resolvedFile = resolve(dbPath);
            return resolvedFile != null && FsPaths.isWithin(resolvedFile, cfg.subset());
        }
    }
}
