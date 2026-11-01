package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Generic filesystem path helpers (canonicalization, containment, SQL {@code LIKE} escaping). */
final class FsPaths
{
    private static final Logger log = LoggerFactory.getLogger(FsPaths.class);

    private FsPaths() { }

    /** Resolves a file to its canonical, normalized absolute {@link Path} (resolves symlinks and {@code ..}). */
    static Path canonical(File file) throws IOException
    {
        return file.getCanonicalFile().toPath().normalize();
    }

    /** Escapes the SQL {@code LIKE} wildcards so a literal path is matched (used with {@code ESCAPE '\'}). */
    static String escapeLike(String s)
    {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** True if {@code child} is the same as, or nested within, {@code parent} (by canonical path). */
    static boolean isWithin(File child, File parent)
    {
        try
        {
            return canonical(child).startsWith(canonical(parent));
        }
        catch (IOException e)
        {
            log.warn("Failed to compare paths {} and {}", child, parent, e);
            return false;
        }
    }

    /** True if both files canonicalize to the same location. */
    static boolean isSame(File a, File b)
    {
        try
        {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        }
        catch (IOException e)
        {
            log.error("Failed to compare paths {} and {}", a, b, e);
            return false;
        }
    }
}
