package com.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/** SHA-1 file hashing with cooperative, mid-file abort support. */
final class Checksums
{
    private static final int BUFFER_SIZE = 1024 * 1024; // 1 MB

    private Checksums()
    {
    }

    /**
     * Allocates one reusable read buffer; pass it to {@link #hash} to avoid a per-file allocation.
     */
    static byte[] newBuffer()
    {
        return new byte[BUFFER_SIZE];
    }

    /**
     * Calculates the SHA-1 checksum of a file, allocating a fresh read buffer each call. Convenience
     * for one-shot callers (e.g. the database file); for per-file hashing in a loop prefer
     * {@link #hash} with a shared buffer to avoid the repeated 1 MB allocation.
     *
     * @throws AbortedException if {@code abortRequested} becomes true while reading
     */
    static String sha1(File file, BooleanSupplier abortRequested) throws IOException, AbortedException
    {
        return hash(file, abortRequested, newBuffer()).hex();
    }

    /**
     * Calculates the SHA-1 checksum of a file using the supplied read buffer. {@code abortRequested}
     * is polled between buffer reads; when it returns true the loop throws {@link AbortedException}
     * promptly (as responsive as one buffer read). Pass {@code () -> false} for an uninterruptible hash.
     *
     * @return the hex digest plus the number of bytes read
     * @throws AbortedException if {@code abortRequested} becomes true while reading
     */
    static Sha1Result hash(File file, BooleanSupplier abortRequested, byte[] buffer) throws IOException, AbortedException
    {
        MessageDigest digest = newSha1Digest();

        long bytesRead = 0;
        try (var fis = new FileInputStream(file);
             var dis = new DigestInputStream(fis, digest))
        {
            int read;
            while ((read = dis.read(buffer)) != -1)
            {
                bytesRead += read;
                if (abortRequested.getAsBoolean())
                {
                    throw new AbortedException();
                }
            }
        }

        return new Sha1Result(HexFormat.of().formatHex(digest.digest()), bytesRead);
    }

    /**
     * SHA-1 is guaranteed present on every conformant JRE, so its absence is an unrecoverable fault.
     */
    private static MessageDigest newSha1Digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-1");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * A computed SHA-1 digest plus the number of bytes actually read while computing it.
     */
    record Sha1Result(String hex, long bytesRead)
    {
    }
}
