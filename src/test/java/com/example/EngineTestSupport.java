package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared harness for the {@link ChecksumEngine} integration tests: fakeable {@link RunControl} and
 * {@link Progress.Listener} implementations plus small filesystem/DB helpers, so tests can drive the
 * highest Swing-free entry point ({@code engine.run(cfg)}) against a real SQLite DB and file tree.
 */
final class EngineTestSupport
{
    private EngineTestSupport()
    {
    }

    static RunConfig cfg(Path workingDir, Path subset, Mode mode)
    {
        return new RunConfig(workingDir.toFile(), subset == null ? null : subset.toFile(), mode, false);
    }

    /**
     * Writes {@code content} to {@code workingDir/relative} (creating parents) and returns the File.
     */
    static File writeFile(Path workingDir, String relative, String content) throws IOException
    {
        Path p = workingDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p.toFile();
    }

    /**
     * Runs the engine with a never-stopping control and the given recorder.
     */
    static RunStats run(RecordingProgress recorder, RunConfig cfg) throws Exception
    {
        return run(ProgrammableControl.never(), recorder, cfg);
    }

    static RunStats run(RunControl control, RecordingProgress recorder, RunConfig cfg) throws Exception
    {
        // Use a BatchCommitter with immediate commits for testing
        return new ChecksumEngine(control, new Progress(recorder), new BatchCommitter(0)).run(cfg);
    }

    static boolean verifyDb(RunConfig cfg) throws AbortedException, DbVerificationException
    {
        return new ChecksumEngine(ProgrammableControl.never(), new Progress(new RecordingProgress()),
                new BatchCommitter(0)).verifyDatabaseChecksum(cfg);
    }

    /**
     * All rows currently in the {@code checksums} table.
     */
    static List<DbRow> readRows(RunConfig cfg) throws SQLException
    {
        List<DbRow> out = new ArrayList<>();
        ChecksumDb db = new ChecksumDb(cfg);
        try (Connection c = db.open();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT path, checksum, tested, size FROM checksums"))
        {
            while (rs.next())
            {
                out.add(new DbRow(rs.getString("path"), rs.getString("checksum"),
                        rs.getBoolean("tested"), rs.getLong("size")));
            }
        }
        return out;
    }

    static DbRow row(RunConfig cfg, String path) throws SQLException
    {
        return readRows(cfg).stream().filter(r -> r.path().equals(path)).findFirst().orElse(null);
    }

    static long countTested(RunConfig cfg) throws SQLException
    {
        return readRows(cfg).stream().filter(DbRow::tested).count();
    }

    static long countUntested(RunConfig cfg) throws SQLException
    {
        return readRows(cfg).stream().filter(r -> !r.tested()).count();
    }

    /**
     * Inserts a raw row directly (used to seed traversal / invalid-path entries the walk would never create).
     */
    static void insertRow(RunConfig cfg, String path, String checksum, boolean tested) throws SQLException
    {
        ChecksumDb db = new ChecksumDb(cfg);
        try (Connection c = db.open();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO checksums (modification_time, checksum, path, size, tested) VALUES (?,?,?,?,?)"))
        {
            ps.setLong(1, 0);
            ps.setString(2, checksum);
            ps.setString(3, path);
            ps.setLong(4, 0);
            ps.setBoolean(5, tested);
            ps.executeUpdate();
        }
    }

    /**
     * Reads the run's detail log (error log, or removed-sums log for CLEAN), or "" if it was never created.
     */
    static String readDetailLog(RunStats stats) throws IOException
    {
        if (stats.detailLogPath == null)
        {
            return "";
        }
        Path p = Path.of(stats.detailLogPath);
        return Files.exists(p) ? Files.readString(p) : "";
    }

    /**
     * One row's relevant columns.
     */
    record DbRow(String path, String checksum, boolean tested, long size)
    {
    }

    /**
     * Deterministic {@link RunControl}: latches Stop after a fixed number of {@code shouldStop()} calls
     * and/or Abort after a fixed number of {@code isAbortRequested()} calls. The engine polls these
     * synchronously on a single worker thread, so the call counts are reproducible (no timing races).
     */
    static final class ProgrammableControl implements RunControl
    {
        private final int stopAfter;   // -1 = never
        private final int abortAfter;  // -1 = never
        private int stopCalls = 0;
        private int abortCalls = 0;
        private boolean stopLatched = false;
        private boolean abortLatched = false;

        private ProgrammableControl(int stopAfter, int abortAfter)
        {
            this.stopAfter = stopAfter;
            this.abortAfter = abortAfter;
        }

        static ProgrammableControl never()
        {
            return new ProgrammableControl(-1, -1);
        }

        /**
         * Latches Stop true once {@code shouldStop()} has been called {@code n} times (returns false the first n).
         */
        static ProgrammableControl stopAfter(int n)
        {
            return new ProgrammableControl(n, -1);
        }

        /**
         * Latches Abort true on the ({@code n}+1)th {@code isAbortRequested()} call (0 = abort on the first check).
         */
        static ProgrammableControl abortAfter(int n)
        {
            return new ProgrammableControl(-1, n);
        }

        @Override
        public synchronized boolean shouldStop()
        {
            if (!stopLatched && stopAfter >= 0 && stopCalls++ >= stopAfter)
            {
                stopLatched = true;
            }
            return stopLatched || abortLatched;
        }

        @Override
        public synchronized boolean isAbortRequested()
        {
            if (!abortLatched && abortAfter >= 0 && abortCalls++ >= abortAfter)
            {
                abortLatched = true;
            }
            return abortLatched;
        }
    }

    /**
     * Captures {@link Progress.Listener} callbacks instead of touching Swing.
     */
    static final class RecordingProgress implements Progress.Listener
    {
        long total = -1;
        long lastFiles, lastBytes;
        boolean reset, completed, finalized;

        @Override public void onReset()
        {
            reset = true;
        }

        @Override public void onTotal(long t)
        {
            total = t;
        }

        @Override public void onStatus(String s)
        {
        }

        @Override public void onProgress(long f, long t, long b, String s)
        {
            lastFiles = f;
            lastBytes = b;
        }

        @Override public void onComplete(long f, long b)
        {
            completed = true;
            lastFiles = f;
            lastBytes = b;
        }

        @Override public void onFinal(long f, long t, long b)
        {
            finalized = true;
            lastFiles = f;
        }
    }
}
