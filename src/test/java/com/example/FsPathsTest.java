package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FsPathsTest
{
    @Test
    void shouldEscapeWildcardsAndBackslashWhenEscapingLikePattern()
    {
        // GIVEN strings containing LIKE wildcards and backslashes
        // WHEN they are escaped
        // THEN the backslash is escaped first, then % and _
        assertEquals("100\\%\\_done", FsPaths.escapeLike("100%_done"));
        assertEquals("a\\\\b", FsPaths.escapeLike("a\\b"));
        assertEquals("plain", FsPaths.escapeLike("plain"));
    }

    @Test
    void shouldReportWithinWhenPathIsNestedOrSame(@TempDir Path tmp) throws IOException
    {
        // GIVEN a parent directory containing a child directory and file
        File parent = tmp.toFile();
        File childDir = new File(parent, "sub");
        assertTrue(childDir.mkdir());
        File childFile = new File(childDir, "f.txt");
        Files.writeString(childFile.toPath(), "x");

        // WHEN nested entries and the parent itself are checked
        // THEN all count as within
        assertTrue(FsPaths.isWithin(childFile, parent));
        assertTrue(FsPaths.isWithin(childDir, parent));
        assertTrue(FsPaths.isWithin(parent, parent)); // same counts as within
    }

    @Test
    void shouldReportNotWithinWhenPathIsOutside(@TempDir Path tmp) throws IOException
    {
        // GIVEN two sibling directories
        File parent = new File(tmp.toFile(), "parent");
        File sibling = new File(tmp.toFile(), "sibling");
        assertTrue(parent.mkdir());
        assertTrue(sibling.mkdir());

        // WHEN a sibling and a non-existent child are checked
        // THEN neither is within the parent
        assertFalse(FsPaths.isWithin(sibling, parent));
        assertFalse(FsPaths.isWithin(parent, new File(parent, "child")));
    }

    @Test
    void shouldCompareCanonicalLocationWhenCheckingIsSame(@TempDir Path tmp)
    {
        // GIVEN a directory referenced both directly and via a dot-dot round trip
        File dir = tmp.toFile();
        File viaDot = new File(dir, "." + File.separator + "x" + File.separator + "..");

        // WHEN the locations are compared
        // THEN canonicalization makes the round-trip path equal but a child differ
        assertTrue(FsPaths.isSame(dir, viaDot));
        assertFalse(FsPaths.isSame(dir, new File(dir, "x")));
    }

    @Test
    void shouldNormalizeDotDotSegmentsWhenCanonicalizing(@TempDir Path tmp) throws IOException
    {
        // GIVEN a path containing a dot-dot segment
        File f = new File(tmp.toFile(), "a" + File.separator + ".." + File.separator + "b.txt");

        // WHEN it is canonicalized
        Path canonical = FsPaths.canonical(f);

        // THEN the dot-dot is resolved away
        assertFalse(canonical.toString().contains(".."));
        assertTrue(canonical.endsWith("b.txt"));
    }
}
