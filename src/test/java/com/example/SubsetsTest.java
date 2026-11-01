package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SubsetsTest
{
    private static RunConfig cfg(File workingDir, File subset)
    {
        return new RunConfig(workingDir, subset, Mode.NEW_TEST, false);
    }

    @Test
    void shouldReturnRelativeForwardSlashedPathWhenConvertingToDbPath(@TempDir Path tmp)
    {
        // GIVEN a file nested under the working directory
        Path file = tmp.resolve("a").resolve("b.txt");

        // WHEN it is converted to a db path
        String dbPath = Subsets.toDbPath(tmp, file);

        // THEN the path is relative and forward-slashed
        assertEquals("a/b.txt", dbPath);
    }

    @Test
    void shouldReturnFileUnderWorkingDirWhenResolvingFromDb(@TempDir Path tmp) throws IOException
    {
        // GIVEN a resolver for a run rooted at the working directory
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg(tmp.toFile(), null));

        // WHEN a db path is resolved
        File resolved = resolver.resolve("a/b.txt");

        // THEN the result stays within the working directory
        assertNotNull(resolved);
        assertTrue(FsPaths.isWithin(resolved, tmp.toFile()));
    }

    @Test
    void shouldReturnNullWhenResolvingTraversalEscape(@TempDir Path tmp) throws IOException
    {
        // GIVEN a resolver for a run rooted at the working directory
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg(tmp.toFile(), null));

        // WHEN traversal paths that escape the root are resolved
        // THEN they are rejected
        assertNull(resolver.resolve("../outside.txt"));
        assertNull(resolver.resolve("a/../../escape.txt"));
    }

    @Test
    void shouldUseNoneFilterWhenNoSubsetGiven(@TempDir Path tmp) throws IOException
    {
        // GIVEN a run without a subset
        RunConfig cfg = cfg(tmp.toFile(), null);

        // WHEN the filter is built
        // THEN it is the NONE filter
        assertSame(SubsetFilter.NONE, Subsets.filter(cfg));
    }

    @Test
    void shouldUseNoneFilterWhenSubsetEqualsWorkingDir(@TempDir Path tmp) throws IOException
    {
        // GIVEN a run whose subset equals the working directory
        RunConfig cfg = cfg(tmp.toFile(), tmp.toFile());

        // WHEN the filter is built
        // THEN it is the NONE filter
        assertSame(SubsetFilter.NONE, Subsets.filter(cfg));
    }

    @Test
    void shouldUseLikePrefixFilterWhenSubsetIsDirectory(@TempDir Path tmp) throws IOException
    {
        // GIVEN a directory subset under the working directory
        File sub = new File(tmp.toFile(), "sub");
        assertTrue(sub.mkdir());

        // WHEN the filter is built
        SubsetFilter filter = Subsets.filter(cfg(tmp.toFile(), sub));

        // THEN it uses a LIKE prefix match
        assertEquals(" AND path LIKE ? ESCAPE '\\'", filter.sqlFragment());
        assertEquals("sub/%", filter.param());
    }

    @Test
    void shouldUseExactMatchFilterWhenSubsetIsFile(@TempDir Path tmp) throws IOException
    {
        // GIVEN a single-file subset under the working directory
        File file = new File(tmp.toFile(), "file.txt");
        Files.writeString(file.toPath(), "x");

        // WHEN the filter is built
        SubsetFilter filter = Subsets.filter(cfg(tmp.toFile(), file));

        // THEN it uses an exact path match
        assertEquals(" AND path = ?", filter.sqlFragment());
        assertEquals("file.txt", filter.param());
    }

    @Test
    void shouldHonourSubsetBoundaryWhenCheckingIsWithin(@TempDir Path tmp) throws IOException
    {
        // GIVEN a resolver scoped to a subset directory
        File sub = new File(tmp.toFile(), "sub");
        assertTrue(sub.mkdir());
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg(tmp.toFile(), sub));

        // WHEN paths inside and outside the subset are checked
        // THEN only those inside the subset count as within
        assertTrue(resolver.isWithin("sub/inside.txt"));
        assertFalse(resolver.isWithin("other/outside.txt"));
    }

    @Test
    void shouldTreatEveryPathAsWithinWhenNoSubsetGiven(@TempDir Path tmp) throws IOException
    {
        // GIVEN a resolver for a run without a subset
        Subsets.Resolver resolver = Subsets.Resolver.forRun(cfg(tmp.toFile(), null));

        // WHEN an arbitrary path is checked
        // THEN it counts as within
        assertTrue(resolver.isWithin("anything/at/all.txt"));
    }
}
